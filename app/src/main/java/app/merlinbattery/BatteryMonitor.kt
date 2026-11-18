package app.merlinbattery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/**
 * Foreground service that samples the battery.
 *
 * Sampling rules (see README):
 * - ACTION_BATTERY_CHANGED drives most samples (fires on level/plug changes).
 * - Extra poll: 10 s while charging, 30 s while discharging in foreground.
 * - NO continuous WakeLock. Doze gaps are expected in background — the
 *   engine re-anchors from voltage after a >5 min gap instead of guessing.
 * - While charging, Doze does not engage, so charge sessions are the
 *   highest-quality data and the cheapest to collect.
 * - Battery-optimisation whitelist is NOT demanded; the user can enable it
 *   manually in Settings if they want tighter background sampling.
 */
class BatteryMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "merlin_battery_monitor"
        const val NOTIF_ID = 1001
        var state: MonitorState? = null
            private set
    }

    private lateinit var store: BatteryStore
    private lateinit var powerManager: PowerManager
    private lateinit var batteryManager: BatteryManager
    private val handler = Handler(Looper.getMainLooper())
    private val samples = mutableListOf<BatterySample>()
    private var sessionCapacities = mutableListOf<Float>()
    private var sessions = mutableListOf<ChargeSession>()
    private var displayedSoc: Float? = null
    private var lastDisplayedAtMs: Long = 0L
    private var anchorSoc: Float? = null
    private var anchorQMah: Float = 0f   // integrated mAh since anchor
    private var currentSessionStart: BatterySample? = null
    private var sessionStartSoc: Float? = null
    private var currentSessionSamples = mutableListOf<BatterySample>()
    private var lastSample: BatterySample? = null
    private var signChecked = false
    private var lastResistanceCheckIdx = 0

    private val pollRunnable = object : Runnable {
        override fun run() {
            takeSample(fromPoll = true)
            val charging = lastSample?.status == ChargeStatus.CHARGING || lastSample?.status == ChargeStatus.FULL
            handler.postDelayed(this, if (charging) 10_000L else 30_000L)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) takeSample(intent, fromPoll = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = BatteryStore(applicationContext)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        samples.addAll(store.loadSamples())
        sessionCapacities.addAll(store.loadSessionCapacities())
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Monitoring battery…"))
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED))
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_POWER_DISCONNECTED))
        handler.post(pollRunnable)
        takeSample(fromPoll = true)
    }

    override fun onDestroy() {
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(pollRunnable)
        persist()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Battery monitoring", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Keeps battery sampling running so Real % and health can be learned."
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder.setContentTitle("Merlin Battery").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging).build()
    }

    private fun updateNotification(text: String) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    /** Read one sample from BatteryManager + the sticky broadcast intent. */
    private fun takeSample(intent: Intent? = null, fromPoll: Boolean) {
        try {
            val sticky = intent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val levelPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val tempDeciC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            val statusInt = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                ?: BatteryManager.BATTERY_STATUS_UNKNOWN
            val status = when (statusInt) {
                BatteryManager.BATTERY_STATUS_CHARGING -> ChargeStatus.CHARGING
                BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeStatus.DISCHARGING
                BatteryManager.BATTERY_STATUS_FULL -> ChargeStatus.FULL
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargeStatus.NOT_CHARGING
                else -> ChargeStatus.UNKNOWN
            }
            val pluggedInt = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val plugged = when (pluggedInt) {
                BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AC
                BatteryManager.BATTERY_PLUGGED_USB -> PlugType.USB
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.WIRELESS
                else -> PlugType.NONE
            }
            val healthInt = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
                ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
            val healthFlag = when (healthInt) {
                BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealthFlag.GOOD
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealthFlag.OVERHEAT
                BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealthFlag.COLD
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealthFlag.OVER_VOLTAGE
                BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealthFlag.DEAD
                else -> BatteryHealthFlag.UNKNOWN
            }
            // getIntProperty sentinels: MIN_VALUE (targetSdk>=28) means unsupported — never treat as 0.
            val currentUa = try { batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) } catch (_: Exception) { Long.MIN_VALUE }
            val ccUah = try { batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) } catch (_: Exception) { -1L }

            val sample = BatterySample(
                wallMs = System.currentTimeMillis(),
                elapsedNs = SystemClock.elapsedRealtimeNanos(),
                levelPct = levelPct, voltageMv = voltageMv, currentUa = currentUa,
                chargeCounterUah = ccUah, tempDeciC = tempDeciC, status = status,
                plugged = plugged, healthFlag = healthFlag,
                screenOn = try { powerManager.isInteractive } catch (_: Exception) { true }
            )
            onSample(sample)
        } catch (e: Exception) {
            store.appendLog(LogCode.SAMPLE_STALE, "takeSample failed: ${e.message}")
        }
    }

    private fun onSample(s: BatterySample) {
        val prev = lastSample
        // Reboot detection: elapsed time went backwards.
        if (prev != null && s.elapsedNs < prev.elapsedNs) {
            store.appendLog(LogCode.REBOOT_MID_SESSION, "elapsedRealtime reset")
            closeSession(rebooted = true)
            anchorSoc = null
            displayedSoc = null
        }
        // Clock jump: wall vs elapsed diverged > 60 s.
        if (prev != null) {
            val wallDelta = s.wallMs - prev.wallMs
            val elapsedDeltaMs = (s.elapsedNs - prev.elapsedNs) / 1_000_000L
            if (kotlin.math.abs(wallDelta - elapsedDeltaMs) > 60_000L) {
                store.appendLog(LogCode.CLOCK_JUMP, "wall=$wallDelta elapsed=$elapsedDeltaMs")
            }
        }
        if (!s.isLevelValid) store.appendLog(LogCode.LEVEL_INVALID, "level=-1")
        if (!s.isVoltageValid) store.appendLog(LogCode.VOLTAGE_INVALID, "voltage=${s.voltageMv}")
        if (!s.isCurrentValid) store.appendLog(LogCode.CURRENT_UNSUPPORTED, "current unsupported")
        if (!s.isTempValid) store.appendLog(LogCode.TEMP_MISSING, "temp missing — learning paused")
        if (s.healthFlag != BatteryHealthFlag.GOOD && s.healthFlag != BatteryHealthFlag.UNKNOWN) {
            store.appendLog(LogCode.BATTERY_HEALTH_FLAG, "flag=${s.healthFlag}")
        }

        // Sign auto-detection, once, on the first charging samples.
        if (!signChecked && s.status == ChargeStatus.CHARGING) {
            val chargingSoFar = samples.filter { it.status == ChargeStatus.CHARGING }.takeLast(10) + s
            if (chargingSoFar.size >= 5 && Faults.detectSignInverted(chargingSoFar)) {
                store.signInverted = true
                store.appendLog(LogCode.SIGN_INVERTED_DETECTED, "OEM inverts CURRENT_NOW sign — flipped")
            }
            if (chargingSoFar.size >= 10) signChecked = true
        }

        // Session tracking.
        if (s.status == ChargeStatus.CHARGING || s.status == ChargeStatus.FULL) {
            if (currentSessionStart == null) {
                currentSessionStart = s
                sessionStartSoc = displayedSoc ?: s.levelPct.toFloat().takeIf { s.isLevelValid }
                currentSessionSamples = mutableListOf()
            }
            currentSessionSamples += s
        } else if (currentSessionStart != null) {
            closeSession(rebooted = false, endSample = s)
        }

        // Internal resistance from load steps.
        if (prev != null && samples.size - lastResistanceCheckIdx >= 2) {
            VoltageSoc.resistanceFromStep(prev, s, store.signInverted)?.let { r ->
                val old = store.resistanceMohm
                if (kotlin.math.abs(r - old) / old <= 2f) {
                    store.resistanceMohm = 0.8f * old + 0.2f * r
                }
                lastResistanceCheckIdx = samples.size
            }
        }

        // Coulomb integration since last anchor.
        if (prev != null && prev.isCurrentValid && s.isCurrentValid) {
            val gapMs = (s.elapsedNs - prev.elapsedNs) / 1_000_000L
            if (gapMs in 1..BatteryConstants.GAP_INTEGRATE_MAX_MS) {
                val step = Coulomb.stepMah(prev, s, store.signInverted)
                anchorQMah += step
                if (step < 0) {
                    // Discharge only: accumulate for the cycle counter.
                    val cap = store.emaMah ?: store.designMah.toFloat()
                    store.cumDischargeMah += -step
                    store.cyclesEquivalent = store.cumDischargeMah / cap
                }
            } else if (gapMs > BatteryConstants.GAP_INTEGRATE_MAX_MS) {
                store.appendLog(LogCode.DOZE_GAP, "gap ${gapMs}ms — re-anchoring from voltage")
                anchorSoc = null // re-anchor from voltage on next fuse
            }
        }

        samples += s
        if (samples.size > BatteryStore.MAX_SAMPLES) samples.removeAt(0)
        lastSample = s

        // Fuse + smooth.
        val fused = fuseCurrent()
        if (fused != null && !fused.socFused.isNaN()) {
            val now = System.currentTimeMillis()
            val charging = s.status == ChargeStatus.CHARGING
            val allowSnap = s.status == ChargeStatus.FULL
            val prevDisplayed = displayedSoc
            val next = if (prevDisplayed == null) {
                fused.socFused
            } else {
                Fusion.slewLimit(prevDisplayed, fused.socFused, now - lastDisplayedAtMs, charging, allowSnap).first
            }
            if (prevDisplayed != null && next != fused.socFused) {
                store.appendLog(LogCode.SOC_JUMP_CLAMPED, "display $prevDisplayed -> $next, raw ${fused.socFused}")
            }
            displayedSoc = next
            lastDisplayedAtMs = now
            if (s.status == ChargeStatus.FULL) anchorSoc = 100f
        }

        // Publish state for the UI (UI polls this every 2 s).
        state = buildState(s)
        // Persist in batches: every ~5 min or on session close.
        if (samples.size % 10 == 0) persist()
        updateNotification(state?.let { "Real ${it.realSocText} · Android ${s.levelPct}% · ${it.statusText}" } ?: "Monitoring battery…")
    }

    private fun fuseCurrent(): FusionResult? {
        val s = lastSample ?: return null
        val capacity = store.emaMah?.toFloat() ?: store.designMah.toFloat()
        val effectiveCapacity = capacity * TempDerating.factor(s.tempC)
        val socCoulomb = anchorSoc?.let { Coulomb.socFromAnchor(it, anchorQMah, effectiveCapacity) }
        val socVoltage = VoltageSoc.soc(s, store.resistanceMohm, store.signInverted)
        // If we have no anchor yet, try a voltage anchor at rest.
        if (anchorSoc == null && socVoltage != null && kotlin.math.abs(s.currentUa / 1000f) < 50f) {
            anchorSoc = socVoltage
            anchorQMah = 0f
        }
        val gapMs = if (samples.size >= 2) (s.elapsedNs - samples[samples.size - 2].elapsedNs) / 1_000_000L else 0L
        val currentDead = Faults.isCurrentStuckZero(samples, 2)
        val voltageStuck = Faults.isVoltageStuck(samples)
        val levelStuck = Faults.isLevelStuck(samples)
        return Fusion.fuse(socCoulomb, socVoltage, s, gapMs, currentDead, voltageStuck, levelStuck)
    }

    private fun closeSession(rebooted: Boolean, endSample: BatterySample? = null) {
        val start = currentSessionStart ?: return
        val end = endSample ?: lastSample ?: return
        val startSoc = sessionStartSoc // fused SoC captured when the session STARTED, not now
        val added = Coulomb.integrate(currentSessionSamples, store.signInverted).coerceAtLeast(0f)
        val temps = currentSessionSamples.mapNotNull { if (it.isTempValid) it.tempC else null }
        val maxGap = currentSessionSamples.zipWithNext { a, b -> (b.elapsedNs - a.elapsedNs) / 1_000_000L }.maxOrNull() ?: 0L
        val session = ChargeSession(
            id = java.util.UUID.randomUUID().toString(),
            startWallMs = start.wallMs, endWallMs = end.wallMs,
            startSoc = startSoc, endSoc = displayedSoc,
            chargeAddedMah = added, dischargeDuringMah = 0f,
            minTempC = temps.minOrNull(), maxTempC = temps.maxOrNull(),
            avgTempC = temps.average().toFloat().takeIf { temps.isNotEmpty() },
            maxGapMs = maxGap, rebooted = rebooted, clockJumped = false,
            usableForLearning = false, rejectReason = null,
            sampleCount = currentSessionSamples.size
        )
        val deltaSoc = if (session.startSoc != null && session.endSoc != null) session.endSoc!! - session.startSoc!! else 0f
        val learned = Learning.evaluateSession(session, added, deltaSoc, store.designMah)
        if (learned != null && !rebooted) {
            val (nextEma, accepted) = Learning.updateEma(store.emaMah, learned.capacityMah, store.acceptedSessionCount)
            if (accepted) {
                store.emaMah = nextEma
                store.acceptedSessionCount += 1
                sessionCapacities += learned.capacityMah
                store.saveSessionCapacities(sessionCapacities)
                sessions += session.copy(usableForLearning = true)
            } else {
                sessions += session.copy(usableForLearning = false, rejectReason = "OUTLIER")
            }
        } else {
            val reason = when {
                rebooted -> "REBOOT"
                deltaSoc < BatteryConstants.MIN_LEARN_DELTA_SOC -> "DELTA_TOO_SMALL"
                maxGap > BatteryConstants.GAP_MAX_LEARN_MS -> "GAP_TOO_LARGE"
                (session.avgTempC ?: 25f) !in 15f..35f -> "TEMP_OUT_OF_RANGE"
                else -> "GATES_FAILED"
            }
            sessions += session.copy(usableForLearning = false, rejectReason = reason)
        }
        store.saveSessions(sessions)
        currentSessionStart = null
        sessionStartSoc = null
        currentSessionSamples = mutableListOf()
        anchorSoc = displayedSoc
        anchorQMah = 0f
        persist()
    }

    private fun buildState(s: BatterySample): MonitorState {
        val capacity = store.emaMah ?: store.designMah.toFloat()
        val health = if (store.emaMah != null) {
            Learning.healthWithUncertainty(store.emaMah!!, sessionCapacities, store.designMah, store.cyclesEquivalent, store.resistanceMohm)
        } else Learning.healthFromCapacity(null, store.designMah)
        val fused = fuseCurrent()
        val real = displayedSoc
        val times = TimeEstimatesCalc.compute(
            real ?: s.levelPct.toFloat(), capacity, samples.toList(),
            s.status == ChargeStatus.CHARGING, store.signInverted
        )
        val statusText = when (s.status) {
            ChargeStatus.CHARGING -> "Charging"
            ChargeStatus.DISCHARGING -> "Discharging"
            ChargeStatus.FULL -> "Full"
            ChargeStatus.NOT_CHARGING -> "Not charging"
            ChargeStatus.UNKNOWN -> "Unknown"
        }
        return MonitorState(
            realSoc = real, androidSoc = s.levelPct.takeIf { it in 0..100 },
            health = health, capacityMah = capacity,
            voltageV = if (s.isVoltageValid) s.voltageMv / 1000f else null,
            currentMa = if (s.isCurrentValid) s.currentUa / 1000f * (if (store.signInverted) -1f else 1f) else null,
            tempC = if (s.isTempValid) s.tempC else null,
            powerW = if (s.isVoltageValid && s.isCurrentValid) s.voltageMv / 1000f * (s.currentUa / 1000f) / 1000f else null,
            statusText = statusText, charging = s.status == ChargeStatus.CHARGING || s.status == ChargeStatus.FULL,
            timeEstimates = times, confidence = fused?.confidence,
            confidenceScore = fused?.confidenceScore ?: 0,
            sessionCount = store.acceptedSessionCount,
            resistanceMohm = store.resistanceMohm,
            healthFlag = s.healthFlag,
            currentSensorOk = s.isCurrentValid,
            samples = samples.toList()
        )
    }

    private fun persist() {
        try {
            store.saveSamples(samples)
        } catch (_: Exception) {}
    }
}

data class MonitorState(
    val realSoc: Float?,
    val androidSoc: Int?,
    val health: HealthEstimate,
    val capacityMah: Float,
    val voltageV: Float?,
    val currentMa: Float?,
    val tempC: Float?,
    val powerW: Float?,
    val statusText: String,
    val charging: Boolean,
    val timeEstimates: TimeEstimates,
    val confidence: SocConfidence?,
    val confidenceScore: Int,
    val sessionCount: Int,
    val resistanceMohm: Float,
    val healthFlag: BatteryHealthFlag,
    val currentSensorOk: Boolean,
    val samples: List<BatterySample>
) {
    val realSocText: String get() = if (realSoc == null) "—" else "${realSoc.toInt()}%"
}
