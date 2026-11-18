package app.merlinbattery

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Merlin Battery — pure battery-math engine.
 *
 * NO Android imports in this file on purpose: it compiles and unit-tests
 * with plain kotlinc on any machine, and the Android app calls into it.
 *
 * Sources / method (see README.md for the full write-up):
 * - AccuBattery method: health = measured full capacity / design capacity,
 *   measured by coulomb-integrating a charge session and dividing by its
 *   SoC span. One session is one noisy sample; the headline needs 8-10
 *   deep sessions. Day 1 the app MUST say "Learning", never a fake %.
 * - Android 11 (API 30) has no health / cycle-count API (that is API 34).
 *   Everything here is measured from BatteryManager data.
 * - Motorola One Action (XT2013, KR40 cell): design 3500 mAh, Li-ion
 *   polymer, 3.8 V nominal, 4.20 V max, ~10 W charging.
 *
 * Honest limits, baked into the code:
 * - Real % is an estimate between full-charge / empty anchors. It drifts.
 * - Health is unknown until >= 1 accepted session, rough until >= 3,
 *   settled until >= 8 deep sessions.
 * - A phone dying at "20%" can be faded capacity OR high internal
 *   resistance (voltage sag under load). No % fixes a cutoff.
 * - Cold temporarily cuts usable capacity; sessions < 10 C or > 40 C
 *   are excluded from learning.
 */

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

enum class ChargeStatus { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN }
enum class PlugType { AC, USB, WIRELESS, NONE }
enum class BatteryHealthFlag { GOOD, OVERHEAT, COLD, OVER_VOLTAGE, DEAD, UNKNOWN }
enum class Confidence { UNKNOWN, LEARNING, ESTIMATED, CONFIDENT }
enum class SocConfidence { LOW, MEDIUM, HIGH }

data class BatterySample(
    val wallMs: Long,
    val elapsedNs: Long,          // SystemClock.elapsedRealtimeNanos() — monotonic, reboot resets it
    val levelPct: Int,            // -1 = invalid
    val voltageMv: Int,           // -1 / 0 = invalid
    val currentUa: Long,          // µA, + = into battery. MIN_VALUE / -1 = unsupported
    val chargeCounterUah: Long,   // µAh remaining (stock Android). -1 / MIN_VALUE = unsupported
    val tempDeciC: Int,           // 0.1 C. Int.MIN_VALUE = missing
    val status: ChargeStatus,
    val plugged: PlugType,
    val healthFlag: BatteryHealthFlag,
    val screenOn: Boolean
) {
    val isLevelValid: Boolean get() = levelPct in 0..100
    val isVoltageValid: Boolean get() = voltageMv in 2800..4400
    val isCurrentValid: Boolean get() = currentUa != Long.MIN_VALUE && currentUa != Int.MIN_VALUE.toLong() && currentUa != -1L
    val isTempValid: Boolean get() = tempDeciC != Int.MIN_VALUE && tempDeciC in -200..600
    val isChargeCounterValid: Boolean get() = chargeCounterUah != -1L && chargeCounterUah != Long.MIN_VALUE && chargeCounterUah != Int.MIN_VALUE.toLong()
    val tempC: Float get() = if (isTempValid) tempDeciC / 10f else Float.NaN
}

data class FusionResult(
    val socFused: Float,          // 0..100, one decimal internally, display as int
    val socCoulomb: Float?,
    val socVoltage: Float?,
    val socAndroid: Int?,
    val confidence: SocConfidence,
    val confidenceScore: Int,     // 0..100
    val clampedJump: Boolean,
    val notes: List<String>
)

data class CapacityEstimate(
    val learnedMah: Float?,
    val sessionCount: Int,
    val lastSessionMah: Float?,
    val stdDevMah: Float?,
    val updatedWallMs: Long
)

data class HealthEstimate(
    val designMah: Int,
    val healthPct: Float?,        // null until >= 1 accepted session
    val lowPct: Float?,
    val highPct: Float?,
    val confidence: Confidence,
    val cyclesEquivalent: Float,
    val internalResistanceMohm: Float?,
    val designTooLowFlag: Boolean
)

data class ChargeSession(
    val id: String,
    val startWallMs: Long,
    val endWallMs: Long?,
    val startSoc: Float?,
    val endSoc: Float?,
    val chargeAddedMah: Float,
    val dischargeDuringMah: Float,
    val minTempC: Float?,
    val maxTempC: Float?,
    val avgTempC: Float?,
    val maxGapMs: Long,
    val rebooted: Boolean,
    val clockJumped: Boolean,
    val usableForLearning: Boolean,
    val rejectReason: String?,
    val sampleCount: Int
)

data class TimeEstimates(
    val minutesToFull: Int?,
    val minutesToEmpty: Int?,
    val chargeSpeedMahPerMin: Float?,
    val avgChargeMa: Float?,
    val avgDischargeMa: Float?,
    val screenOnDrainMa: Float?,
    val screenOffDrainMa: Float?
)

// Error / diagnostic log codes — exact strings, see README § Error handling.
object LogCode {
    const val LEVEL_INVALID = "LEVEL_INVALID"
    const val VOLTAGE_INVALID = "VOLTAGE_INVALID"
    const val CURRENT_UNSUPPORTED = "CURRENT_UNSUPPORTED"
    const val CURRENT_STUCK_ZERO = "CURRENT_STUCK_ZERO"
    const val CC_UNSUPPORTED = "CC_UNSUPPORTED"
    const val CC_RESET = "CC_RESET"
    const val VOLTAGE_STUCK = "VOLTAGE_STUCK"
    const val TEMP_MISSING = "TEMP_MISSING"
    const val TEMP_OUT_OF_RANGE = "TEMP_OUT_OF_RANGE"
    const val LEVEL_STUCK = "LEVEL_STUCK"
    const val LEVEL_JUMP = "LEVEL_JUMP"
    const val REBOOT_MID_SESSION = "REBOOT_MID_SESSION"
    const val CLOCK_JUMP = "CLOCK_JUMP"
    const val DOZE_GAP = "DOZE_GAP"
    const val CALIBRATION_INCOMPLETE = "CALIBRATION_INCOMPLETE"
    const val SIGN_INVERTED_DETECTED = "SIGN_INVERTED_DETECTED"
    const val VOLTAGE_OUT_OF_RANGE = "VOLTAGE_OUT_OF_RANGE"
    const val SAMPLE_STALE = "SAMPLE_STALE"
    const val BATTERY_HEALTH_FLAG = "BATTERY_HEALTH_FLAG"
    const val PERSISTENCE_CORRUPT = "PERSISTENCE_CORRUPT"
    const val SOC_JUMP_CLAMPED = "SOC_JUMP_CLAMPED"
    const val DESIGN_CAPACITY_CHANGED = "DESIGN_CAPACITY_CHANGED"
}

// ---------------------------------------------------------------------------
// Constants — One Action / KR40 cell
// ---------------------------------------------------------------------------

object BatteryConstants {
    const val DESIGN_MAH_DEFAULT = 3500
    const val DESIGN_MAH_MIN = 1000
    const val DESIGN_MAH_MAX = 10000
    const val VOLTAGE_FULL_MV = 4200
    const val VOLTAGE_EMPTY_USABLE_MV = 3400
    const val DEFAULT_RESISTANCE_MOHM = 150f
    const val RESISTANCE_MIN_MOHM = 50f
    const val RESISTANCE_MAX_MOHM = 500f
    const val EMA_ALPHA = 0.30f
    const val OUTLIER_REJECT_PCT = 0.15f
    const val MIN_LEARN_DELTA_SOC = 40f      // absolute minimum span for learning
    const val PREFERRED_LEARN_DELTA_SOC = 60f // headline health prefers >= 60% spans
    const val GAP_MAX_LEARN_MS = 5 * 60 * 1000L
    const val GAP_INTEGRATE_MAX_MS = 5 * 60 * 1000L
    const val STALE_FOREGROUND_MS = 120_000L
}

// ---------------------------------------------------------------------------
// Voltage -> SoC (rested open-circuit voltage, ~25 C, generic 4.20 V Li-ion)
// Research table: 4.20=100, 4.10=90, 4.00=80, 3.95=70, 3.85-3.90=60-75,
// 3.78=50, 3.70=40, 3.62=30, 3.50=20, 3.40=10, 3.30=5, 3.00=0 (cell floor).
// The phone's usable 0% is ~3.40 V under load, NOT 3.00 V — the table
// below is the rested-cell curve; fusion weights it low in the flat
// 30-70% plateau and high near the knees (<15% / >85%).
// ---------------------------------------------------------------------------

object OcvTable {
    // (SoC %, OCV volts), ascending SoC
    val points: List<Pair<Float, Float>> = listOf(
        0f to 3.00f, 5f to 3.30f, 10f to 3.40f, 20f to 3.50f, 30f to 3.62f,
        40f to 3.70f, 50f to 3.78f, 60f to 3.84f, 70f to 3.89f, 80f to 3.95f,
        90f to 4.05f, 95f to 4.13f, 100f to 4.20f
    )

    /** Linear interpolation. Returns null if voltage is outside the table. */
    fun socFromVoltage(voltageV: Float): Float? {
        if (voltageV < points.first().second || voltageV > points.last().second) return null
        for (i in 0 until points.size - 1) {
            val (s0, v0) = points[i]
            val (s1, v1) = points[i + 1]
            if (voltageV in v0..v1) {
                if (v1 == v0) return s0
                return s0 + (voltageV - v0) / (v1 - v0) * (s1 - s0)
            }
        }
        return points.last().first
    }

    fun voltageFromSoc(soc: Float): Float {
        val s = soc.coerceIn(0f, 100f)
        for (i in 0 until points.size - 1) {
            val (s0, v0) = points[i]
            val (s1, v1) = points[i + 1]
            if (s in s0..s1) {
                if (s1 == s0) return v0
                return v0 + (s - s0) / (s1 - s0) * (v1 - v0)
            }
        }
        return points.last().second
    }
}

object TempDerating {
    /** Usable-capacity factor vs temperature (1.0 at 25 C). */
    fun factor(tempC: Float): Float = when {
        tempC.isNaN() -> 1.0f
        tempC <= -10f -> 0.65f
        tempC <= 0f -> 0.65f + (tempC + 10f) / 10f * 0.15f   // -10..0: 0.65..0.80
        tempC <= 10f -> 0.80f + tempC / 10f * 0.10f         // 0..10: 0.80..0.90
        tempC <= 20f -> 0.90f + (tempC - 10f) / 10f * 0.07f // 10..20: 0.90..0.97
        tempC <= 25f -> 0.97f + (tempC - 20f) / 5f * 0.03f  // 20..25: 0.97..1.00
        tempC <= 35f -> 1.00f + (tempC - 25f) / 10f * 0.02f // 25..35: 1.00..1.02
        tempC <= 45f -> 1.02f - (tempC - 35f) / 10f * 0.02f // 35..45: 1.02..1.00
        else -> 1.00f
    }

    /** OCV shift to apply before lookup: cold reads low, hot reads high. */
    fun ocvShiftMv(tempC: Float): Float = when {
        tempC.isNaN() -> 0f
        tempC < 25f -> (25f - tempC) * 0.4f   // +mV to compensate cold sag in reading
        tempC > 25f -> -(tempC - 25f) * 0.2f
        else -> 0f
    }
}

// ---------------------------------------------------------------------------
// Coulomb counting
// ---------------------------------------------------------------------------

object Coulomb {
    /**
     * Trapezoidal integration of one step, using ELAPSED time only
     * (never wall clock — wall clock jumps with NTP / user changes).
     * currentUa: + = charging. Returns mAh added in this step.
     */
    fun stepMah(prev: BatterySample, cur: BatterySample, signInverted: Boolean): Float {
        val dtNs = cur.elapsedNs - prev.elapsedNs
        if (dtNs <= 0) return 0f
        val dtH = dtNs / 3.6e12
        val sign = if (signInverted) -1f else 1f
        val iPrevMa = prev.currentUa / 1000f * sign
        val iCurMa = cur.currentUa / 1000f * sign
        return (iPrevMa + iCurMa) / 2f * dtH.toFloat()
    }

    /** Integrate a whole sample list. Returns total mAh (+ charge, - discharge). */
    fun integrate(samples: List<BatterySample>, signInverted: Boolean): Float {
        var total = 0f
        for (i in 1 until samples.size) {
            val gapMs = (samples[i].elapsedNs - samples[i - 1].elapsedNs) / 1_000_000L
            if (gapMs in 1..BatteryConstants.GAP_INTEGRATE_MAX_MS) {
                total += stepMah(samples[i - 1], samples[i], signInverted)
            }
            // Gap > 5 min: do NOT interpolate — caller must re-anchor from voltage.
        }
        return total
    }

    /** SoC from an anchor: anchorSoc + Q / capacity * 100. */
    fun socFromAnchor(anchorSoc: Float, deltaMah: Float, capacityMah: Float): Float =
        (anchorSoc + deltaMah / capacityMah * 100f).coerceIn(0f, 100f)
}

// ---------------------------------------------------------------------------
// Voltage SoC with load (sag) correction
// ---------------------------------------------------------------------------

object VoltageSoc {
    /**
     * Recover open-circuit voltage from a loaded terminal voltage:
     * discharging (I < 0) sags the terminal down, so OCV = V - I*R.
     * Charging (I > 0) inflates it, same formula holds.
     * currentMa is SIGNED (+ charging). resistance in ohms.
     */
    fun ocvFromLoaded(voltageV: Float, currentMa: Float, resistanceOhm: Float): Float =
        voltageV - currentMa / 1000f * resistanceOhm

    /** Voltage SoC, or null if the sample cannot support one. */
    fun soc(
        sample: BatterySample,
        resistanceMohm: Float,
        signInverted: Boolean
    ): Float? {
        if (!sample.isVoltageValid) return null
        if (!sample.isTempValid) return null // without temp we cannot correct OCV
        val sign = if (signInverted) -1f else 1f
        val currentMa = if (sample.isCurrentValid) sample.currentUa / 1000f * sign else 0f
        val ocv = ocvFromLoaded(
            sample.voltageMv / 1000f,
            currentMa,
            resistanceMohm / 1000f
        ) + TempDerating.ocvShiftMv(sample.tempC) / 1000f
        return OcvTable.socFromVoltage(ocv)
    }

    /** Estimate internal resistance from a load step. Null if not a clean step. */
    fun resistanceFromStep(
        before: BatterySample, after: BatterySample,
        signInverted: Boolean
    ): Float? {
        if (!before.isVoltageValid || !after.isVoltageValid) return null
        if (!before.isCurrentValid || !after.isCurrentValid) return null
        val sign = if (signInverted) -1f else 1f
        val dI = abs(after.currentUa / 1000f * sign - before.currentUa / 1000f * sign)
        if (dI < 200f) return null
        val dtS = (after.elapsedNs - before.elapsedNs) / 1e9
        if (dtS !in 0.0..5.0) return null
        if (!before.isTempValid || before.tempC !in 15f..35f) return null
        val dV = abs(after.voltageMv - before.voltageMv) / 1000f
        val rMohm = dV / (dI / 1000f) * 1000f
        if (rMohm !in BatteryConstants.RESISTANCE_MIN_MOHM..BatteryConstants.RESISTANCE_MAX_MOHM) return null
        return rMohm
    }
}

// ---------------------------------------------------------------------------
// Fusion — one SoC, no jumping %
// ---------------------------------------------------------------------------

object Fusion {
    data class Weights(val coulomb: Float, val voltage: Float, val android: Float)

    fun weights(
        socCoulomb: Float?, socVoltage: Float?,
        sample: BatterySample, gapMs: Long,
        currentSensorDead: Boolean, voltageStuck: Boolean, levelStuck: Boolean
    ): Weights {
        var wC = if (socCoulomb == null || currentSensorDead) 0f else 0.60f
        var wV = if (socVoltage == null || voltageStuck) 0f else 0.30f
        var wA = 0.10f
        if (gapMs > BatteryConstants.GAP_MAX_LEARN_MS) wC = 0f
        // Voltage is weak in the flat 30-70% plateau, strong near the knees.
        if (socVoltage != null) {
            if (socVoltage in 30f..70f) wV *= 0.5f
            if (abs(sample.currentUa / 1000f) > 300f) wV *= 0.5f
        }
        if (levelStuck) wA = 0.02f
        // If coulomb + voltage agree and Android disagrees by > 8%, distrust Android.
        if (socCoulomb != null && socVoltage != null && sample.isLevelValid) {
            if (abs(socCoulomb - socVoltage) <= 3f && abs(sample.levelPct - socCoulomb) > 8f) wA = 0.02f
        }
        // At rest, voltage deserves more weight.
        if (socVoltage != null && abs(sample.currentUa / 1000f) < 50f) wV = max(wV, 0.40f)
        return Weights(wC, wV, wA)
    }

    fun fuse(
        socCoulomb: Float?, socVoltage: Float?, sample: BatterySample,
        gapMs: Long, currentSensorDead: Boolean, voltageStuck: Boolean, levelStuck: Boolean
    ): FusionResult {
        val w = weights(socCoulomb, socVoltage, sample, gapMs, currentSensorDead, voltageStuck, levelStuck)
        var sum = 0f; var wSum = 0f
        if (socCoulomb != null && w.coulomb > 0) { sum += w.coulomb * socCoulomb; wSum += w.coulomb }
        if (socVoltage != null && w.voltage > 0) { sum += w.voltage * socVoltage; wSum += w.voltage }
        if (sample.isLevelValid && w.android > 0) { sum += w.android * sample.levelPct; wSum += w.android }
        val notes = mutableListOf<String>()
        if (wSum == 0f) {
            return FusionResult(Float.NaN, socCoulomb, socVoltage, sample.levelPct.takeIf { sample.isLevelValid }, SocConfidence.LOW, 0, false, listOf("No usable SoC source"))
        }
        val fused = (sum / wSum).coerceIn(0f, 100f)
        // Confidence: disagreement between sources penalises the score.
        var score = 100
        val vals = listOfNotNull(socCoulomb, socVoltage, sample.levelPct.takeIf { sample.isLevelValid }?.toFloat())
        if (vals.size >= 2) {
            val spread = vals.max() - vals.min()
            if (spread > 3f) score -= ((spread - 3f) * 5).toInt()
        }
        if (gapMs > 60_000L) score -= 10
        if (currentSensorDead) score -= 25
        if (voltageStuck || levelStuck) score -= 15
        if (socCoulomb == null) score -= 20
        score = score.coerceIn(0, 100)
        val conf = when { score >= 80 -> SocConfidence.HIGH; score >= 50 -> SocConfidence.MEDIUM; else -> SocConfidence.LOW }
        if (conf == SocConfidence.LOW) notes += "Low confidence — treat Real % as an estimate"
        return FusionResult(fused, socCoulomb, socVoltage, sample.levelPct.takeIf { sample.isLevelValid }, conf, score, false, notes)
    }

    /**
     * Slew limit: displayed SoC may move at most 1% / 30 s discharging,
     * 2% / 30 s charging. Returns the clamped displayed value.
     * Anchors (FULL -> 100%) are allowed to snap — pass allowSnap = true.
     */
    fun slewLimit(displayed: Float, target: Float, elapsedMs: Long, charging: Boolean, allowSnap: Boolean): Pair<Float, Boolean> {
        if (allowSnap) return target to false
        if (elapsedMs <= 0) return displayed to false
        val maxMove = (if (charging) 2f else 1f) * elapsedMs / 30_000f
        val clamped = when {
            target > displayed + maxMove -> displayed + maxMove
            target < displayed - maxMove -> displayed - maxMove
            else -> target
        }
        return clamped to (clamped != target)
    }
}

// ---------------------------------------------------------------------------
// Capacity learning — from one charge session
// ---------------------------------------------------------------------------

object Learning {
    data class SessionCapacity(val capacityMah: Float, val deltaSoc: Float, val addedMah: Float)

    /** All gates must pass. Returns null + reason when the session is unusable. */
    fun evaluateSession(
        session: ChargeSession,
        addedMahBattery: Float,   // net mAh into the cell (charge - discharge-during-charge)
        deltaSoc: Float,
        designMah: Int
    ): SessionCapacity? {
        if (deltaSoc < BatteryConstants.MIN_LEARN_DELTA_SOC) return null
        if (session.maxGapMs > BatteryConstants.GAP_MAX_LEARN_MS) return null
        if (session.rebooted || session.clockJumped) return null
        val avgTemp = session.avgTempC
        if (avgTemp == null) return null // temp missing: learning disabled
        if (avgTemp !in 15f..35f) return null
        if ((session.maxTempC ?: 99f) >= 40f) return null
        val durationMs = (session.endWallMs ?: session.startWallMs) - session.startWallMs
        if (durationMs !in (20 * 60 * 1000L)..(12 * 60 * 60 * 1000L)) return null
        val capacity = addedMahBattery / (deltaSoc / 100f)
        if (capacity < designMah * 0.50f || capacity > designMah * 1.10f) return null
        return SessionCapacity(capacity, deltaSoc, addedMahBattery)
    }

    /** EMA update with outlier rejection. Returns (newEma, accepted). */
    fun updateEma(
        currentEma: Float?, sessionCapacity: Float, sessionCount: Int
    ): Pair<Float, Boolean> {
        if (currentEma == null) return sessionCapacity to true
        if (sessionCount >= 3 &&
            abs(sessionCapacity - currentEma) / currentEma > BatteryConstants.OUTLIER_REJECT_PCT
        ) return currentEma to false
        val next = 0.7f * currentEma + BatteryConstants.EMA_ALPHA * sessionCapacity
        return next to true
    }

    fun healthFromCapacity(learnedMah: Float?, designMah: Int): HealthEstimate {
        if (learnedMah == null) {
            return HealthEstimate(designMah, null, null, null, Confidence.UNKNOWN, 0f, null, false)
        }
        val health = learnedMah / designMah * 100f
        return HealthEstimate(designMah, health, null, null, Confidence.LEARNING, 0f, null, learnedMah > designMah)
    }

    /** Health with uncertainty band + confidence ladder from session count. */
    fun healthWithUncertainty(
        learnedMah: Float, sessionCapacities: List<Float>, designMah: Int,
        cyclesEquivalent: Float, resistanceMohm: Float?
    ): HealthEstimate {
        val n = sessionCapacities.size
        val rawHealth = learnedMah / designMah * 100f
        val displayHealth = min(rawHealth, 100f)
        val conf = when {
            n >= 8 -> Confidence.CONFIDENT
            n >= 3 -> Confidence.ESTIMATED
            n >= 1 -> Confidence.LEARNING
            else -> Confidence.UNKNOWN
        }
        val (low, high) = if (n >= 3) {
            val mean = sessionCapacities.average().toFloat()
            val variance = sessionCapacities.map { (it - mean) * (it - mean) }.average().toFloat()
            val std = sqrt(variance)
            val half = max(1.96f * std / sqrt(n.toFloat()) / designMah * 100f, 3f)
            (displayHealth - half) to (displayHealth + half)
        } else {
            (displayHealth - 8f) to (displayHealth + 8f)
        }
        return HealthEstimate(
            designMah, displayHealth,
            low.coerceIn(0f, 100f), high.coerceIn(0f, 100f),
            conf, cyclesEquivalent, resistanceMohm, learnedMah > designMah
        )
    }
}

// ---------------------------------------------------------------------------
// Time estimates
// ---------------------------------------------------------------------------

object TimeEstimatesCalc {
    fun rollingAverageMa(samples: List<BatterySample>, windowMs: Long, signInverted: Boolean): Float? {
        if (samples.size < 2) return null
        val now = samples.last().elapsedNs
        val window = samples.filter { (now - it.elapsedNs) / 1_000_000L <= windowMs && it.isCurrentValid }
        if (window.size < 2) return null
        val sign = if (signInverted) -1f else 1f
        return window.map { it.currentUa / 1000f * sign }.average().toFloat()
    }

    fun compute(
        socFused: Float, capacityMah: Float, samples: List<BatterySample>,
        charging: Boolean, signInverted: Boolean
    ): TimeEstimates {
        val avg15 = rollingAverageMa(samples, 15 * 60 * 1000L, signInverted)
        val avg5 = rollingAverageMa(samples, 5 * 60 * 1000L, signInverted)
        val screenOn = samples.filter { it.screenOn }.let { rollingAverageMa(it, 15 * 60 * 1000L, signInverted) }
        val screenOff = samples.filter { !it.screenOn }.let { rollingAverageMa(it, 15 * 60 * 1000L, signInverted) }
        var ttf: Int? = null
        var tte: Int? = null
        if (charging && avg15 != null && avg15 > 50f) {
            var effectiveMa = avg15
            if (socFused > 80f) effectiveMa *= (1f - (socFused - 80f) / 20f * 0.6f) // taper
            val remainingMah = (100f - socFused) / 100f * capacityMah
            if (effectiveMa > 0) ttf = (remainingMah / effectiveMa * 60f).toInt()
        }
        if (!charging && avg15 != null && avg15 < -50f) {
            val remainingMah = socFused / 100f * capacityMah
            tte = (remainingMah / abs(avg15) * 60f).toInt()
        }
        // Charge speed: mAh added in last 10 min while charging.
        var speed: Float? = null
        if (charging && samples.size >= 2) {
            val recent = samples.takeLast(40)
            val q = Coulomb.integrate(recent, signInverted)
            val dtMin = (recent.last().elapsedNs - recent.first().elapsedNs) / 60e9
            if (dtMin > 1) speed = (q / dtMin.toFloat())
        }
        return TimeEstimates(ttf, tte, speed, avg15?.takeIf { it > 0 }, avg15?.takeIf { it < 0 }, screenOn, screenOff)
    }
}

// ---------------------------------------------------------------------------
// Sensor fault detectors (stuck / dead sensors — never show 0 as a real value)
// ---------------------------------------------------------------------------

object Faults {
    fun isCurrentStuckZero(samples: List<BatterySample>, levelDropPct: Int): Boolean {
        if (samples.size < 5) return false
        val recent = samples.takeLast(20)
        val allZero = recent.all { it.isCurrentValid && abs(it.currentUa) < 5_000L }
        val levelDrop = (samples.first().levelPct - samples.last().levelPct)
        return allZero && levelDrop >= 2
    }

    fun isVoltageStuck(samples: List<BatterySample>): Boolean {
        val recent = samples.takeLast(20)
        if (recent.size < 10) return false
        val voltages = recent.mapNotNull { it.voltageMv.takeIf { v -> v in 2800..4400 } }
        if (voltages.size < 10) return false
        val spread = voltages.max() - voltages.min()
        val currentSpread = recent.mapNotNull { it.currentUa.takeIf { c -> c != Long.MIN_VALUE } }.let {
            if (it.size < 2) 0L else it.max() - it.min()
        }
        return spread <= 4 && currentSpread > 100_000L
    }

    fun isLevelStuck(samples: List<BatterySample>): Boolean {
        val recent = samples.takeLast(20)
        if (recent.size < 10) return false
        if (!recent.all { it.levelPct == recent.first().levelPct }) return false
        val avgCurrent = recent.mapNotNull { it.currentUa.takeIf { c -> c != Long.MIN_VALUE } }.map { abs(it) }.average()
        return avgCurrent > 100_000.0
    }

    /** Sign auto-detection: first 10 charging samples, median current < 0 => inverted. */
    fun detectSignInverted(chargingSamples: List<BatterySample>): Boolean {
        val currents = chargingSamples.filter { it.status == ChargeStatus.CHARGING && it.isCurrentValid }
            .take(10).map { it.currentUa }.sorted()
        if (currents.size < 5) return false
        return currents[currents.size / 2] < 0
    }
}
