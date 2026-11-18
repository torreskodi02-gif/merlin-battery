package app.merlinbattery

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import java.io.File

/**
 * Merlin Battery v1.0.0 — five tabs, all programmatic Views, zero extra
 * dependencies beyond AppCompat. One BatteryState feeds every tab.
 *
 * Tabs: Dashboard | Health | Charging | History | Settings (+ Calibration)
 *
 * Rule everywhere: "—" + an explanation beats a wrong number. Real % is
 * shown next to Android %, with its confidence, until calibrated.
 */
class MainActivity : Activity() {

    private lateinit var store: BatteryStore
    private lateinit var content: FrameLayout
    private lateinit var navRow: LinearLayout
    private var tab = 0
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 2000L) }
    }

    // Live views we update in place on the Dashboard (rebuilt on tab switch).
    private var gauge: GaugeView? = null
    private var gaugeSub: TextView? = null
    private var confidencePill: TextView? = null
    private var timeValue: TextView? = null
    private var timeLabel: TextView? = null
    private var powerValue: TextView? = null
    private var voltValue: TextView? = null
    private var currentValue: TextView? = null
    private var tempValue: TextView? = null
    private var healthStrip: TextView? = null
    private var miniChart: LineChartView? = null
    private var warnBanner: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BatteryStore(this)
        // Start the monitor service so sampling continues in background.
        try {
            val svc = Intent(this, BatteryMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start background monitoring: ${e.message}", Toast.LENGTH_LONG).show()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(content)
        // Bottom nav always has a visible 2dp top border — never borderless.
        val navBorder = View(this).apply {
            setBackgroundColor(Ui.BORDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@MainActivity, 2))
        }
        root.addView(navBorder)
        navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(0, Ui.dp(this@MainActivity, 6), 0, Ui.dp(this@MainActivity, 6))
        }
        val tabs = listOf("Dashboard", "Health", "Charging", "History", "Settings")
        tabs.forEachIndexed { i, name ->
            val b = Button(this).apply {
                text = name; textSize = 12f
                setTextColor(if (i == tab) Ui.GREEN else Ui.TEXT2)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@MainActivity, 48), 1f)
                setOnClickListener { tab = i; render() }
            }
            navRow.addView(b)
        }
        root.addView(navRow)
        setContentView(root)
        render()
        handler.post(refresh)
    }

    override fun onDestroy() { handler.removeCallbacks(refresh); super.onDestroy() }
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        // Refresh nav colours.
        for (i in 0 until navRow.childCount) {
            (navRow.getChildAt(i) as? Button)?.setTextColor(if (i == tab) Ui.GREEN else Ui.TEXT2)
        }
        content.removeAllViews()
        gauge = null
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16))
        }
        scroll.addView(col)
        content.addView(scroll)
        when (tab) {
            0 -> buildDashboard(col)
            1 -> buildHealth(col)
            2 -> buildCharging(col)
            3 -> buildHistory(col)
            else -> buildSettings(col)
        }
        updateLive()
    }

    private fun state(): MonitorState? = BatteryMonitorService.state

    private fun updateLive() {
        val s = state() ?: return
        gauge?.let {
            it.value = s.realSoc ?: Float.NaN
            it.arcColor = when {
                s.realSoc == null -> Ui.MUTED
                s.realSoc < 15f -> Ui.RED
                s.charging -> Ui.GREEN
                else -> Ui.BLUE
            }
            it.invalidate()
        }
        gaugeSub?.text = "Android ${s.androidSoc?.let { "$it%" } ?: "—"}  ·  ${s.statusText}"
        confidencePill?.text = when (s.health.confidence) {
            Confidence.CONFIDENT -> "High confidence · ${s.sessionCount} cycles learned"
            Confidence.ESTIMATED -> "Medium confidence · ${s.sessionCount} cycles learned"
            Confidence.LEARNING -> if (s.sessionCount > 0) "Low confidence · ${s.sessionCount} cycle learned — calibrate more" else "Learning · 0 cycles yet — calibrate to improve"
            Confidence.UNKNOWN -> "Learning · 0 cycles yet — calibrate to improve"
        }
        warnBanner?.let { b ->
            val diff = if (s.realSoc != null && s.androidSoc != null) s.androidSoc!! - s.realSoc!!.toInt() else 0
            if (kotlin.math.abs(diff) >= 5) {
                b.visibility = View.VISIBLE
                b.text = "Android reads ${if (diff > 0) "$diff% high" else "${-diff}% low"} — trust Real %"
            } else b.visibility = View.GONE
            if (s.tempC != null && s.tempC!! >= 45f) {
                b.visibility = View.VISIBLE
                b.text = "Battery hot (${s.tempC} °C) — let it cool, avoid charging"
            }
            if (!s.currentSensorOk) {
                b.visibility = View.VISIBLE
                b.text = "Current sensor not reported by this phone — estimates use voltage only, calibration is essential"
            }
        }
        val charging = s.charging
        timeLabel?.text = if (charging) "TIME TO FULL" else "TIME TO EMPTY"
        timeValue?.text = Ui.formatMinutes(if (charging) s.timeEstimates.minutesToFull else s.timeEstimates.minutesToEmpty)
        powerValue?.text = s.powerW?.let { "%.1f W".format(kotlin.math.abs(it)) } ?: "—"
        voltValue?.text = s.voltageV?.let { "%.2f V".format(it) } ?: "—"
        currentValue?.text = s.currentMa?.let { "%+d mA".format(it.toInt()) } ?: "—"
        tempValue?.text = s.tempC?.let { "%.1f °C".format(it) } ?: "—"
        healthStrip?.text = s.health.healthPct?.let {
            "Health ${it.toInt()}% — ${Ui.healthWord(it)} · ${s.capacityMah.toInt()} / ${s.health.designMah} mAh"
        } ?: "Health — Learning · complete 1 full charge to estimate"
        miniChart?.let { it.values = s.samples.takeLast(120).mapNotNull { sm -> if (sm.isCurrentValid) sm.currentUa / 1000f else null }; it.invalidate() }
    }

    // ---- Dashboard ----
    private fun buildDashboard(col: LinearLayout) {
        val s = state()
        col.addView(Ui.title(this, "Merlin Battery"))
        warnBanner = Ui.body(this, "", 13f, Ui.AMBER).apply { visibility = View.GONE }
        col.addView(warnBanner!!)
        if (s?.sessionCount == 0) {
            val banner = Ui.card(this)
            banner.addView(Ui.body(this, "Welcome — let's calibrate your battery. Real % won't be fully trustworthy until 2–3 full charges.", 14f, Ui.TEXT))
            val go = Button(this).apply { text = "Start Calibration"; setOnClickListener { tab = 4; render() } }
            banner.addView(go)
            col.addView(banner)
        }
        val gaugeCard = Ui.card(this)
        gauge = GaugeView(this).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 280), Ui.dp(this@MainActivity, 280)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            centerLabel = "REAL"
        }
        gaugeCard.addView(gauge!!)
        gaugeSub = Ui.body(this, "", 14f, Ui.TEXT2).apply { gravity = Gravity.CENTER }
        gaugeCard.addView(gaugeSub!!)
        confidencePill = Ui.pill(this, "", Ui.AMBER).apply { gravity = Gravity.CENTER }
        gaugeCard.addView(confidencePill!!)
        col.addView(gaugeCard)

        // Time card
        val timeCard = Ui.card(this)
        timeLabel = Ui.label(this, "TIME TO EMPTY")
        timeValue = Ui.value(this, "—")
        timeCard.addView(timeLabel!!); timeCard.addView(timeValue!!)
        timeCard.addView(Ui.body(this, "Based on your recent drain / charge rate. Shows — while it calculates.", 12f, Ui.MUTED))
        col.addView(timeCard)

        // Live stats 2x2
        val row1 = Ui.row(this)
        powerValue = Ui.value(this, "—", 24f)
        val pCard = Ui.statCard(this, "Power", "—", "live"); row1.addView(pCard)
        val vCard = Ui.statCard(this, "Voltage", "—", "live"); row1.addView(vCard)
        col.addView(row1)
        val row2 = Ui.row(this)
        row2.addView(Ui.statCard(this, "Current", "—", "live"))
        row2.addView(Ui.statCard(this, "Temperature", "—", "live"))
        col.addView(row2)
        // Rebind the stat values: statCard created its own TextViews; update via updateLive is
        // approximated by re-reading state in the named fields above for power/voltage.
        // (Power/voltage/current/temp TextViews are rebound here for correctness.)
        powerValue = (pCard.getChildAt(1) as TextView)
        voltValue = (vCard.getChildAt(1) as TextView)
        currentValue = (row2.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
        tempValue = (row2.getChildAt(1) as LinearLayout).getChildAt(1) as TextView
        // Colour the temp value.
        tempValue?.setTextColor(Ui.tempColor(s?.tempC))

        val healthCard = Ui.card(this)
        healthCard.addView(Ui.title(this, "Battery Health", 15f))
        healthStrip = Ui.body(this, "", 14f, Ui.TEXT)
        healthCard.addView(healthStrip!!)
        healthCard.setOnClickListener { tab = 1; render() }
        col.addView(healthCard)

        val chartCard = Ui.card(this)
        chartCard.addView(Ui.title(this, "Current — last 2 hours", 15f))
        miniChart = LineChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@MainActivity, 160))
            lineColor = Ui.BLUE
        }
        chartCard.addView(miniChart!!)
        col.addView(chartCard)
        col.addView(Ui.body(this, "Real % = % of what your battery holds today. Android % = % of what the fuel gauge thinks it holds. They differ on an old battery — that is the point of this app.", 12f, Ui.MUTED))
    }

    // ---- Health ----
    private fun buildHealth(col: LinearLayout) {
        val s = state()
        col.addView(Ui.title(this, "Battery Health"))
        val h = s?.health
        val ringCard = Ui.card(this)
        val ring = GaugeView(this).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 180), Ui.dp(this@MainActivity, 180)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            centerLabel = "HEALTH"
            value = h?.healthPct ?: Float.NaN
            arcColor = Ui.healthColor(h?.healthPct)
            centerTextSizeSp = 36f
        }
        ringCard.addView(ring)
        val word = Ui.body(this, h?.healthPct?.let { Ui.healthWord(it) } ?: "Not known yet", 16f, Ui.healthColor(h?.healthPct)).apply { gravity = Gravity.CENTER }
        ringCard.addView(word)
        ringCard.addView(Ui.body(this, h?.healthPct?.let {
            val range = if (h.lowPct != null && h.highPct != null) " (${h.lowPct!!.toInt()}–${h.highPct!!.toInt()}%)" else ""
            "Estimated capacity vs. design capacity$range · ${s?.sessionCount ?: 0} learned sessions"
        } ?: "We need at least one full charge from below 30% to above 80% to estimate your real capacity. Your battery is old, so Android's % alone can't tell us.", 13f, Ui.TEXT2).apply { gravity = Gravity.CENTER })
        col.addView(ringCard)

        val row = Ui.row(this)
        row.addView(Ui.statCard(this, "Estimated capacity", s?.let { "${it.capacityMah.toInt()} mAh" } ?: "—", "learned from charges"))
        row.addView(Ui.statCard(this, "Design capacity", "${h?.designMah ?: 3500} mAh", "KR40 · One Action"))
        col.addView(row)

        val details = Ui.card(this)
        details.addView(Ui.title(this, "Details", 15f))
        val lines = listOf(
            "Charge cycles (since install): ${"%.1f".format(h?.cyclesEquivalent ?: 0f)}",
            "Full charges learned: ${s?.sessionCount ?: 0}",
            "Internal resistance: ~${s?.resistanceMohm?.toInt() ?: 150} mΩ ${if ((s?.resistanceMohm ?: 150f) > 250f) "(aged — expect voltage sag)" else "(normal)"}",
            "Voltage at full: 4.20 V · usable empty: ~3.40 V",
            "Design capacity source: device profile (KR40, 3500 mAh) — editable in Settings"
        )
        lines.forEach { details.addView(Ui.body(this, "•  $it", 14f)) }
        col.addView(details)

        val meaning = Ui.card(this)
        meaning.addView(Ui.title(this, "What this means", 15f))
        meaning.addView(Ui.body(this, when {
            h?.healthPct == null -> "No health estimate yet. Do a calibration charge and come back — one full cycle teaches the app more than a week of guessing."
            h.healthPct!! >= 85f -> "Good — your battery still holds ${h.healthPct!!.toInt()}% of its original charge. Expect slightly less screen time than when new. Avoid leaving it at 100% overnight to slow further wear."
            h.healthPct!! >= 70f -> "Fair — your battery holds ${h.healthPct!!.toInt()}% of its original charge. Expect noticeably less screen time. Charge before 15% — old batteries can drop suddenly below there."
            else -> "Poor — your battery holds ${h.healthPct!!.toInt()}% of its original charge. Consider a replacement KR40 battery if the phone dies early or shuts off above 15%."
        }, 14f))
        col.addView(meaning)

        val recal = Button(this).apply { text = "Recalibrate"; setOnClickListener { tab = 4; render() } }
        col.addView(recal)
        if (h?.designTooLowFlag == true) {
            col.addView(Ui.body(this, "Learned capacity is above design capacity — your design capacity may be set too low. Check Settings.", 13f, Ui.AMBER))
        }
    }

    // ---- Charging ----
    private fun buildCharging(col: LinearLayout) {
        val s = state()
        col.addView(Ui.title(this, "Charging"))
        val hero = Ui.card(this)
        hero.addView(Ui.title(this, if (s?.charging == true) "Charging" else "Not charging", 18f))
        hero.addView(Ui.value(this, s?.powerW?.let { "%.1f W".format(kotlin.math.abs(it)) } ?: "—"))
        hero.addView(Ui.body(this, s?.let {
            "Real ${it.realSocText} · Android ${it.androidSoc ?: "—"}% · ${it.timeEstimates.chargeSpeedMahPerMin?.let { sp -> "+${sp.toInt()} mAh/min" } ?: ""}"
        } ?: "Waiting for a sample…", 13f, Ui.TEXT2))
        col.addView(hero)

        col.addView(Ui.title(this, "Alarms (use Real %, not Android %)", 15f))
        val prefs = getSharedPreferences("alarms", Context.MODE_PRIVATE)
        fun alarmRow(text: String, key: String, def: Boolean) {
            val row = Ui.row(this)
            val label = Ui.body(this, text, 15f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            val sw = Switch(this).apply {
                isChecked = prefs.getBoolean(key, def)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
            }
            row.addView(label); row.addView(sw)
            col.addView(row)
        }
        alarmRow("Alarm at 80% — recommended for battery life", "alarm80", true)
        alarmRow("Alarm at 100%", "alarm100", false)
        alarmRow("Alarm at low battery (15% Real %)", "alarmLow", true)
        alarmRow("Alarm if battery gets hot (45 °C)", "alarmHot", true)
        col.addView(Ui.body(this, "Alarms fire as notifications while the monitor service runs. 80% charging slows wear — every 0.1 V less roughly halves voltage-related wear.", 12f, Ui.MUTED))
    }

    // ---- History ----
    private fun buildHistory(col: LinearLayout) {
        val s = state()
        col.addView(Ui.title(this, "History"))
        val samples = s?.samples ?: emptyList()
        if (samples.size < 10) {
            val empty = Ui.card(this)
            empty.addView(Ui.title(this, "No history yet", 16f))
            empty.addView(Ui.body(this, "Charge and use your phone normally — history appears after your first full session. Calibration charges count double.", 14f))
            col.addView(empty)
            return
        }
        val summary = Ui.card(this)
        summary.addView(Ui.title(this, "Today", 15f))
        val avgDrain = samples.filter { !it.isCurrentValid }.size // placeholder guard
        val currents = samples.mapNotNull { if (it.isCurrentValid) it.currentUa / 1000f else null }
        val avg = if (currents.isNotEmpty()) currents.average() else 0.0
        summary.addView(Ui.body(this, "Samples collected: ${samples.size} · average current: ${"%.0f".format(avg)} mA · sessions learned: ${s?.sessionCount ?: 0}", 14f))
        col.addView(summary)
        val chartCard = Ui.card(this)
        chartCard.addView(Ui.title(this, "Voltage (V)", 15f))
        val vc = LineChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@MainActivity, 160))
            values = samples.takeLast(200).mapNotNull { if (it.isVoltageValid) it.voltageMv / 1000f else null }
            lineColor = Ui.BLUE
        }
        chartCard.addView(vc)
        col.addView(chartCard)
        val tc = Ui.card(this)
        tc.addView(Ui.title(this, "Temperature (°C)", 15f))
        val tch = LineChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@MainActivity, 160))
            values = samples.takeLast(200).mapNotNull { if (it.isTempValid) it.tempC else null }
            lineColor = Ui.AMBER
        }
        tc.addView(tch)
        col.addView(tc)
        // Diagnostic log, last 10 entries.
        val logCard = Ui.card(this)
        logCard.addView(Ui.title(this, "Diagnostic log", 15f))
        store.loadLog().takeLast(10).forEach { logCard.addView(Ui.body(this, it, 12f, Ui.MUTED)) }
        if (store.loadLog().isEmpty()) logCard.addView(Ui.body(this, "No warnings yet.", 13f, Ui.MUTED))
        col.addView(logCard)
    }

    // ---- Settings + Calibration ----
    private fun buildSettings(col: LinearLayout) {
        col.addView(Ui.title(this, "Settings & Calibration"))
        // Calibration card — always on top.
        val cal = Ui.card(this)
        cal.addView(Ui.title(this, "Calibration — teach the app your real battery", 16f))
        val count = store.acceptedSessionCount
        cal.addView(Ui.body(this, "Learned cycles: $count / 3 recommended  ${"●".repeat(count.coerceAtMost(3))}${"○".repeat(3 - count.coerceAtMost(3))}", 14f))
        cal.addView(Ui.body(this, "1. Use the phone until 15% Real % or lower.\n2. Plug in and charge uninterrupted to 100%.\n3. Keep charging 10 min past 100%.\n4. Done — capacity updates.", 14f))
        if (!store.calibrationWarningAck) {
            cal.addView(Ui.body(this, "Your Real % won't be fully trustworthy until 2–3 full calibration cycles. Until then, treat it as an estimate and keep an eye on Android % too. Old batteries can drop suddenly below 15% — charge before then.", 13f, Ui.AMBER))
            val ack = Button(this).apply {
                text = "I understand"
                setOnClickListener { store.calibrationWarningAck = true; render() }
            }
            cal.addView(ack)
        }
        col.addView(cal)

        val meas = Ui.card(this)
        meas.addView(Ui.title(this, "Measurement", 15f))
        meas.addView(Ui.body(this, "Design capacity (mAh) — KR40 default 3500. Wrong design capacity makes health % wrong. Only change if you know the spec.", 13f, Ui.TEXT2))
        val edit = EditText(this).apply {
            setText(store.designMah.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        meas.addView(edit)
        val save = Button(this).apply {
            text = "Save design capacity"
            setOnClickListener {
                val v = edit.text.toString().toIntOrNull()
                if (v == null || v !in BatteryConstants.DESIGN_MAH_MIN..BatteryConstants.DESIGN_MAH_MAX) {
                    Toast.makeText(this@MainActivity, "Enter 1000–10000 mAh", Toast.LENGTH_SHORT).show()
                } else {
                    store.designMah = v
                    Toast.makeText(this@MainActivity, "Design capacity saved: $v mAh", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        }
        meas.addView(save)
        col.addView(meas)

        val data = Ui.card(this)
        data.addView(Ui.title(this, "Data", 15f))
        val export = Button(this).apply {
            text = "Export data (JSON)"
            setOnClickListener {
                try {
                    val f = File(filesDir, "battery-export-${System.currentTimeMillis()}.json")
                    f.writeText(store.exportJson())
                    Toast.makeText(this@MainActivity, "Exported to ${f.name}", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        data.addView(export)
        val clear = Button(this).apply {
            text = "Clear learned capacity"
            setOnClickListener { store.clearLearned(); Toast.makeText(this@MainActivity, "Learned capacity cleared — recalibrate", Toast.LENGTH_SHORT).show(); render() }
        }
        data.addView(clear)
        col.addView(data)

        val about = Ui.card(this)
        about.addView(Ui.title(this, "About", 15f))
        val s = state()
        about.addView(Ui.body(this, "Merlin Battery v1.0.0\nSensor support: Current ${if (s?.currentSensorOk == true) "✓" else "—"} · Voltage ${if (s?.voltageV != null) "✓" else "—"} · Temp ${if (s?.tempC != null) "✓" else "—"}\nFully offline. No account, no cloud. All data stays on this phone.\nMotorola One Action · KR40 3500 mAh · Android 11", 13f, Ui.TEXT2))
        col.addView(about)
    }
}
