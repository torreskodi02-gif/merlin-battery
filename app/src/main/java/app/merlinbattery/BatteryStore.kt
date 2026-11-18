package app.merlinbattery

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistence: SharedPreferences for scalars + plain JSON files for
 * samples / sessions / estimates / log. No Room, no extra dependencies.
 * Atomic writes (tmp + rename), schema versioning, corrupt-file quarantine.
 * Never crashes on bad data — E20 PERSISTENCE_CORRUPT.
 */
class BatteryStore(private val ctx: Context) {

    companion object {
        const val SCHEMA_VERSION = 1
        const val PREFS = "battery_prefs"
        const val KEY_DESIGN_MAH = "designCapacityMah"
        const val KEY_SIGN_INVERTED = "currentSignInverted"
        const val KEY_RESISTANCE_MOHM = "resistanceMohm"
        const val KEY_EMA_MAH = "emaCapacityMah"
        const val KEY_SESSION_COUNT = "acceptedSessionCount"
        const val KEY_CYCLES = "cyclesEquivalent"
        const val KEY_CUM_DISCHARGE_MAH = "cumDischargeMah"
        const val KEY_CALIBRATION_ACK = "calibrationWarningAck"
        const val MAX_SAMPLES = 2000
        const val MAX_SESSIONS = 100
        const val MAX_LOG = 500
    }

    val prefs: SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dir: File get() = File(ctx.filesDir, "battery").apply { if (!exists()) mkdirs() }

    var designMah: Int
        get() = prefs.getInt(KEY_DESIGN_MAH, BatteryConstants.DESIGN_MAH_DEFAULT)
            .coerceIn(BatteryConstants.DESIGN_MAH_MIN, BatteryConstants.DESIGN_MAH_MAX)
        set(v) {
            val clamped = v.coerceIn(BatteryConstants.DESIGN_MAH_MIN, BatteryConstants.DESIGN_MAH_MAX)
            prefs.edit().putInt(KEY_DESIGN_MAH, clamped).apply()
            appendLog(LogCode.DESIGN_CAPACITY_CHANGED, "design=$clamped")
        }

    var signInverted: Boolean
        get() = prefs.getBoolean(KEY_SIGN_INVERTED, false)
        set(v) { prefs.edit().putBoolean(KEY_SIGN_INVERTED, v).apply() }

    var resistanceMohm: Float
        get() = prefs.getFloat(KEY_RESISTANCE_MOHM, BatteryConstants.DEFAULT_RESISTANCE_MOHM)
        set(v) { prefs.edit().putFloat(KEY_RESISTANCE_MOHM, v).apply() }

    var emaMah: Float?
        get() = if (prefs.contains(KEY_EMA_MAH)) prefs.getFloat(KEY_EMA_MAH, 0f) else null
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove(KEY_EMA_MAH) else e.putFloat(KEY_EMA_MAH, v)
            e.apply()
        }

    var acceptedSessionCount: Int
        get() = prefs.getInt(KEY_SESSION_COUNT, 0)
        set(v) { prefs.edit().putInt(KEY_SESSION_COUNT, v).apply() }

    var cyclesEquivalent: Float
        get() = prefs.getFloat(KEY_CYCLES, 0f)
        set(v) { prefs.edit().putFloat(KEY_CYCLES, v).apply() }

    var cumDischargeMah: Float
        get() = prefs.getFloat(KEY_CUM_DISCHARGE_MAH, 0f)
        set(v) { prefs.edit().putFloat(KEY_CUM_DISCHARGE_MAH, v).apply() }

    var calibrationWarningAck: Boolean
        get() = prefs.getBoolean(KEY_CALIBRATION_ACK, false)
        set(v) { prefs.edit().putBoolean(KEY_CALIBRATION_ACK, v).apply() }

    // ---- atomic file helpers ----
    private fun writeAtomic(name: String, text: String) {
        try {
            val tmp = File(dir, "$name.tmp")
            val dst = File(dir, name)
            tmp.writeText(text)
            if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
        } catch (e: Exception) {
            appendLog(LogCode.PERSISTENCE_CORRUPT, "write $name failed: ${e.message}")
        }
    }

    private fun readJson(name: String): JSONObject? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            try { f.renameTo(File(dir, "$name.corrupt-${System.currentTimeMillis()}")) } catch (_: Exception) {}
            appendLog(LogCode.PERSISTENCE_CORRUPT, "read $name failed, quarantined: ${e.message}")
            null
        }
    }

    // ---- samples: ring buffer, max 2000 ----
    fun loadSamples(): MutableList<BatterySample> {
        val out = mutableListOf<BatterySample>()
        val json = readJson("samples.json") ?: return out
        if (json.optInt("schema", -1) != SCHEMA_VERSION) return out
        val arr = json.optJSONArray("samples") ?: return out
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                out += BatterySample(
                    wallMs = o.getLong("wall"), elapsedNs = o.getLong("elapsed"),
                    levelPct = o.getInt("level"), voltageMv = o.getInt("voltage"),
                    currentUa = o.getLong("current"), chargeCounterUah = o.optLong("cc", -1L),
                    tempDeciC = o.optInt("temp", Int.MIN_VALUE),
                    status = ChargeStatus.valueOf(o.optString("status", "UNKNOWN")),
                    plugged = PlugType.valueOf(o.optString("plugged", "NONE")),
                    healthFlag = BatteryHealthFlag.valueOf(o.optString("health", "UNKNOWN")),
                    screenOn = o.optBoolean("screenOn", true)
                )
            } catch (_: Exception) { /* skip one bad sample, keep the rest */ }
        }
        return out
    }

    fun saveSamples(samples: List<BatterySample>) {
        val kept = samples.takeLast(MAX_SAMPLES)
        val arr = JSONArray()
        for (s in kept) {
            arr.put(JSONObject().apply {
                put("schema", SCHEMA_VERSION)
                put("wall", s.wallMs); put("elapsed", s.elapsedNs)
                put("level", s.levelPct); put("voltage", s.voltageMv)
                put("current", s.currentUa); put("cc", s.chargeCounterUah)
                put("temp", s.tempDeciC); put("status", s.status.name)
                put("plugged", s.plugged.name); put("health", s.healthFlag.name)
                put("screenOn", s.screenOn)
            })
        }
        writeAtomic("samples.json", JSONObject().put("schema", SCHEMA_VERSION).put("samples", arr).toString())
    }

    // ---- sessions ----
    fun loadSessionCapacities(): MutableList<Float> {
        val out = mutableListOf<Float>()
        val json = readJson("estimates.json") ?: return out
        val arr = json.optJSONArray("sessionCapacities") ?: return out
        for (i in 0 until arr.length()) out += arr.optDouble(i).toFloat()
        return out
    }

    fun saveSessionCapacities(capacities: List<Float>) {
        val arr = JSONArray()
        capacities.takeLast(MAX_SESSIONS).forEach { arr.put(it.toDouble()) }
        val prev = readJson("estimates.json")
        val json = JSONObject()
        json.put("schema", SCHEMA_VERSION)
        json.put("sessionCapacities", arr)
        if (prev != null) {
            if (prev.has("sessions")) json.put("sessions", prev.get("sessions"))
        }
        writeAtomic("estimates.json", json.toString())
    }

    fun saveSessions(sessions: List<ChargeSession>) {
        val arr = JSONArray()
        sessions.takeLast(MAX_SESSIONS).forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("start", s.startWallMs)
                put("end", s.endWallMs ?: JSONObject.NULL)
                put("startSoc", s.startSoc?.toDouble() ?: JSONObject.NULL)
                put("endSoc", s.endSoc?.toDouble() ?: JSONObject.NULL)
                put("addedMah", s.chargeAddedMah.toDouble())
                put("usable", s.usableForLearning)
                put("reject", s.rejectReason ?: JSONObject.NULL)
            })
        }
        val prev = readJson("estimates.json") ?: JSONObject()
        prev.put("schema", SCHEMA_VERSION)
        prev.put("sessions", arr)
        writeAtomic("estimates.json", prev.toString())
    }

    // ---- diagnostic log, ring 500 ----
    fun appendLog(code: String, detail: String) {
        try {
            val json = readJson("log.json") ?: JSONObject().put("schema", SCHEMA_VERSION)
            val arr = json.optJSONArray("entries") ?: JSONArray()
            val kept = JSONArray()
            // keep last MAX_LOG-1, then append
            val start = maxOf(0, arr.length() - (MAX_LOG - 1))
            for (i in start until arr.length()) kept.put(arr.get(i))
            kept.put(JSONObject().put("t", System.currentTimeMillis()).put("code", code).put("detail", detail))
            writeAtomic("log.json", JSONObject().put("schema", SCHEMA_VERSION).put("entries", kept).toString())
        } catch (_: Exception) { /* logging must never crash the app */ }
    }

    fun loadLog(): List<String> {
        val json = readJson("log.json") ?: return emptyList()
        val arr = json.optJSONArray("entries") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += "${o.optLong("t")} ${o.optString("code")} ${o.optString("detail")}"
        }
        return out
    }

    fun exportJson(): String {
        val root = JSONObject()
        root.put("app", "Merlin Battery 1.0.0")
        root.put("exportedAt", System.currentTimeMillis())
        root.put("designMah", designMah)
        root.put("emaMah", emaMah?.toDouble() ?: JSONObject.NULL)
        root.put("acceptedSessions", acceptedSessionCount)
        root.put("sessionCapacities", JSONArray().apply { loadSessionCapacities().forEach { put(it.toDouble()) } })
        root.put("log", JSONArray().apply { loadLog().forEach { put(it) } })
        return root.toString(2)
    }

    fun clearLearned() {
        emaMah = null
        acceptedSessionCount = 0
        cyclesEquivalent = 0f
        cumDischargeMah = 0f
        saveSessionCapacities(emptyList())
        saveSessions(emptyList())
    }
}
