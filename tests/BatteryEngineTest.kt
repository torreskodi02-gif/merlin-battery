import app.merlinbattery.*
import kotlin.math.abs

// Plain-kotlin test runner: compile with kotlinc alongside BatteryEngine.kt
// and run. No Android, no JUnit — exit code 1 on any failure.
var passed = 0; var failed = 0
fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) { passed++; println("PASS $name") }
    else { failed++; println("FAIL $name $detail") }
}
fun near(a: Float, b: Float, tol: Float = 1.0f) = abs(a - b) <= tol

fun sample(elapsedSec: Long, level: Int, voltageMv: Int, currentUa: Long, status: ChargeStatus, tempDeciC: Int = 250, cc: Long = 2000000L): BatterySample =
    BatterySample(wallMs = 1_700_000_000_000L + elapsedSec * 1000, elapsedNs = elapsedSec * 1_000_000_000L,
        levelPct = level, voltageMv = voltageMv, currentUa = currentUa, chargeCounterUah = cc,
        tempDeciC = tempDeciC, status = status, plugged = PlugType.NONE,
        healthFlag = BatteryHealthFlag.GOOD, screenOn = true)

fun main() {
    // T01 Coulomb discharge: anchor 80%, -500mA 1h, C=3500 -> 65.7%
    val s0 = sample(0, 80, 3900, -500_000L, ChargeStatus.DISCHARGING)
    val s1 = sample(3600, 80, 3850, -500_000L, ChargeStatus.DISCHARGING)
    val dq = Coulomb.stepMah(s0, s1, false)
    check("T01 coulomb discharge -500mAh", near(dq, -500f, 1f), "got $dq")
    check("T01 SoC 65.7%", near(Coulomb.socFromAnchor(80f, dq, 3500f), 65.7f, 0.5f))

    // T02 charge: +1000mA 30min from 50% -> 64.3%
    val c0 = sample(0, 50, 3900, 1_000_000L, ChargeStatus.CHARGING)
    val c1 = sample(1800, 50, 4000, 1_000_000L, ChargeStatus.CHARGING)
    val dq2 = Coulomb.stepMah(c0, c1, false)
    check("T02 charge +500mAh", near(dq2, 500f, 1f), "got $dq2")
    check("T02 SoC 64.3%", near(Coulomb.socFromAnchor(50f, dq2, 3500f), 64.3f, 0.5f))

    // T03 trapezoidal -400 -> -600 over 1h = -500mAh
    val t0 = sample(0, 80, 3900, -400_000L, ChargeStatus.DISCHARGING)
    val t1 = sample(3600, 80, 3850, -600_000L, ChargeStatus.DISCHARGING)
    check("T03 trapezoidal -500mAh", near(Coulomb.stepMah(t0, t1, false), -500f, 1f))

    // T12 OCV 3.78V -> 50% (table point)
    check("T12 OCV 3.78V = 50%", near(OcvTable.socFromVoltage(3.78f) ?: -1f, 50f, 0.5f))
    // T13 OCV 3.81V -> 55% (midpoint 3.78..3.84)
    check("T13 OCV 3.81V = 55%", near(OcvTable.socFromVoltage(3.81f) ?: -1f, 55f, 0.5f))
    // T14 load correction: 3.70V, 1000mA discharge, R=150mOhm -> OCV 3.85V -> ~61%
    val ocv = VoltageSoc.ocvFromLoaded(3.70f, -1000f, 0.15f)
    check("T14 OCV corrected 3.85V", near(ocv, 3.85f, 0.01f), "got $ocv")
    check("T14 SoC ~61%", near(OcvTable.socFromVoltage(ocv) ?: -1f, 61f, 2f))
    // T15 R estimation: dI 500mA, dV 75mV -> 150 mOhm
    val r0 = sample(0, 60, 3850, -100_000L, ChargeStatus.DISCHARGING)
    val r1 = sample(3, 60, 3775, -600_000L, ChargeStatus.DISCHARGING)
    check("T15 R=150mOhm", near(VoltageSoc.resistanceFromStep(r0, r1, false) ?: -1f, 150f, 5f))
    // T17 temp derating 0C -> 0.80
    check("T17 derating 0C = 0.80", near(TempDerating.factor(0f), 0.80f, 0.01f))
    check("T17 derating 25C = 1.00", near(TempDerating.factor(25f), 1.00f, 0.01f))
    // T21 capacity learning: 20->90%, 1750mAh -> 2500mAh, health 71%
    val sess = ChargeSession("t", 0L, 7200000L, 20f, 90f, 1750f, 0f, 20f, 30f, 25f, 60000L, false, false, true, null, 100)
    val cap = Learning.evaluateSession(sess, 1750f, 70f, 3500)
    check("T21 capacity 2500mAh", cap != null && near(cap.capacityMah, 2500f, 25f), "got ${cap?.capacityMah}")
    val health = Learning.healthWithUncertainty(2500f, listOf(2500f), 3500, 0f, null)
    check("T21 health 71%", health.healthPct != null && near(health.healthPct!!, 71.4f, 1f), "got ${health.healthPct}")
    // T22 delta too small rejected
    val sess2 = sess.copy(endSoc = 75f, startSoc = 50f)
    check("T22 delta 25% rejected", Learning.evaluateSession(sess2, 625f, 25f, 3500) == null)
    // T23 temp too cold rejected
    val sess3 = sess.copy(avgTempC = 8f, minTempC = 5f, maxTempC = 10f)
    check("T23 cold rejected", Learning.evaluateSession(sess3, 1750f, 70f, 3500) == null)
    // T24 outlier rejected
    val (ema24, acc24) = Learning.updateEma(2500f, 3200f, 3)
    check("T24 outlier rejected", !acc24 && near(ema24, 2500f, 1f))
    // T25 EMA: 2500 + 2600 -> 2530
    val (ema25, acc25) = Learning.updateEma(2500f, 2600f, 1)
    check("T25 EMA 2530", acc25 && near(ema25, 2530f, 1f), "got $ema25")
    // T27 health clamp at 100%
    val h27 = Learning.healthWithUncertainty(3800f, listOf(3800f), 3500, 0f, null)
    check("T27 health clamped 100%", h27.healthPct == 100f && h27.designTooLowFlag)
    // T28 TTE: SoC 50%, C=2500, 250mA -> 5h
    val samples28 = (0..10).map { sample(it * 60L, 50, 3800, -250_000L, ChargeStatus.DISCHARGING) }
    val te28 = TimeEstimatesCalc.compute(50f, 2500f, samples28, false, false)
    check("T28 TTE 300min", te28.minutesToEmpty != null && near(te28.minutesToEmpty!!.toFloat(), 300f, 30f), "got ${te28.minutesToEmpty}")
    // T20 slew limit: 60 -> 66 in one sample, discharging, 30s -> max 61%
    val (clamped, wasClamped) = Fusion.slewLimit(60f, 66f, 30_000L, false, false)
    check("T20 slew clamp 61%", near(clamped, 61f, 0.1f) && wasClamped, "got $clamped")
    // T11 sign inversion: charging but median current negative
    val inv = (0..9).map { sample(it * 10L, 50, 4000, -800_000L, ChargeStatus.CHARGING) }
    check("T11 sign inverted detected", Faults.detectSignInverted(inv))
    // T10 current stuck zero with level falling
    val stuck = (0..19).map { sample(it * 30L, 80 - it / 5, 3800, 0L, ChargeStatus.DISCHARGING) }
    check("T10 current stuck zero", Faults.isCurrentStuckZero(stuck, 2))
    // Fusion at rest: coulomb 62, voltage 60, android 61 -> ~61, high confidence
    val f = Fusion.fuse(62f, 60f, sample(0, 61, 3780, -20_000L, ChargeStatus.DISCHARGING), 10000L, false, false, false)
    check("T18 fusion ~61% high", near(f.socFused, 61f, 2f) && f.confidence == SocConfidence.HIGH, "got ${f.socFused} ${f.confidence}")
    // Health unknown with no sessions
    val h0 = Learning.healthFromCapacity(null, 3500)
    check("T33 health unknown", h0.healthPct == null && h0.confidence == Confidence.UNKNOWN)
    // Voltage stuck detection
    val vstuck = (0..19).map { sample(it * 30L, 60, 3800, (-100_000L - it * 40_000L), ChargeStatus.DISCHARGING) }
    check("T35 voltage stuck", Faults.isVoltageStuck(vstuck))

    println("\n$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
