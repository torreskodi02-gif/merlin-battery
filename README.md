# Merlin Battery

An AccuBattery-style battery health app for an old phone with an old battery — built for the Motorola One Action (XT2013, KR40 cell, 3500 mAh, Android 11).

**Download:** https://github.com/torreskodi02-gif/merlin-battery/releases/download/latest/merlin-battery.apk (works once a release exists)

## What it does

- **Real %** — your battery percentage calculated from actual charge, not just what Android reports. Android's % is the fuel gauge's guess as a fraction of whatever full capacity it currently believes. On a faded battery that guess goes stale, so the % looks fine at the top and then collapses at the bottom ("20% → dead in minutes").
- **Android %** — shown next to Real % so you can see the gap.
- **Battery health %** — learned capacity / design capacity (3500 mAh), with an uncertainty range and a confidence label.
- **Live stats** — power (W), current (mA), voltage (V), temperature, time to full / empty, charge speed, internal resistance.
- **Calibration** — full-charge cycles that teach the app your real capacity.
- **History** — voltage / temperature charts, charge sessions, diagnostic log.
- **Charge alarms** — 80% (recommended), 100%, low battery, hot battery. Alarms use Real %, not Android %.

Fully offline. No account, no cloud. All data stays on the phone.

## The honest part — read this first

There is **no hidden health % to read** on this phone. Android 11 has no battery-health or cycle-count API (that arrived in Android 14). Health has to be **measured over time**.

- **Day 1:** the app can only show Android's %, plus voltage / current / temperature. It says "Learning" and means it.
- **After 1 full charge:** a rough health estimate (±8%).
- **After 3–7 deep charges:** a usable estimate (±5%).
- **After 8–10 deep charges over ~2 weeks:** a settled estimate (±2–3%).

Any app claiming an instant "real %" on first launch is guessing. This one doesn't.

Also: a phone dying at "20%" can be faded capacity **or** high internal resistance (voltage sag under load). No percentage fixes a cutoff — the app shows internal resistance as a second signal so you can tell them apart.

## How the math works

Per charge session:

```
Q_in = ∫ I_charge dt          (trapezoidal coulomb integration, elapsed time only)
C_full(session) = Q_in / ΔSoC
Health = C_full / 3500 mAh × 100
```

Example: charging 20% → 90% (ΔSoC 70%) and measuring 1750 mAh in gives C = 2500 mAh, health 71%.

**Real % between anchors:**

```
SoC_true = Q_remaining / C_learned × 100
```

Anchors are the only moments charge is *known*: full charge (status FULL, ~4.20 V, current tapered) = 100%, shutdown = 0%. Between anchors the app integrates `CURRENT_NOW`, corrected by rested-voltage SoC and Android level in a weighted fusion:

- Coulomb 0.60 (primary, when samples are continuous)
- Voltage 0.30 (high near the knees <15% / >85%, halved in the flat 30–70% plateau and under heavy load)
- Android level 0.10 (sanity anchor)

Displayed % is slew-limited (max 1% / 30 s discharging, 2% / 30 s charging) so it never jumps.

**Voltage → SoC** uses a rested Li-ion OCV table (4.20 V = 100%, 3.78 V = 50%, 3.40 V = 10%), corrected for load sag (`OCV = V − I·R`, R estimated from load steps, typically 150 mΩ new, 250–450 mΩ aged) and temperature. Voltage is only trusted at rest (|I| < 200 mA) or after sag correction — a camera load can sag a healthy 45% battery to a terminal voltage that *looks* like 20%.

**Capacity learning gates** — a session only counts if: ΔSoC ≥ 40% (headline prefers ≥ 60%), both ends anchored, average temp 15–35 °C, max temp < 40 °C, no gap > 5 min, no reboot, no clock jump, duration 20 min–12 h, result within 50–110% of design. Outliers > 15% from the running average are rejected. Accepted sessions feed an exponential moving average (α = 0.30).

**Temperature:** usable capacity falls in the cold (~80% at 0 °C, ~65% at −10 °C) and recovers on warming. Cold sessions are excluded from learning, and the app shows "cold — reading temporarily low" instead of a scary health drop. Never charge below 0 °C.

**Sampling:** `ACTION_BATTERY_CHANGED` + a 10 s poll while charging, 30 s while discharging. No continuous WakeLock — Doze gaps are expected in background and the engine re-anchors from voltage after a > 5 min gap instead of interpolating across it. Target self-drain < 1–2% / day.

**Kernel cross-check (if exposed):** `/sys/class/power_supply/battery/charge_full` / `charge_full_design` give the kernel's own health estimate on some phones. This build reads them only as a second opinion when present; if the kernel doesn't expose them, the app says so instead of inventing a number. (Not yet wired into v1.0.0 UI — see TODO below.)

## Calibration — do this once a month

1. Use the phone until 15% Real % or lower.
2. Plug in and charge **uninterrupted** to 100%.
3. Keep charging 10 min past 100% (lets voltage settle).
4. Done — capacity updates. Repeat 2–3 times for a trustworthy Real %.

A partial 15% → 100% calibration is accepted (labelled "partial", slightly less accurate). Unplugging mid-charge discards that cycle.

## Error handling — "—" beats a wrong number

Every sensor is checked for its unsupported sentinel (`CURRENT_NOW` → `MIN_VALUE`, `CHARGE_COUNTER` → `-1`, temp → `MIN_VALUE`) and for stuck readings (current stuck at 0 while level falls, voltage frozen while current varies, level frozen while discharging). When a sensor is dead the app degrades the claim — voltage-only estimate, confidence dropped, banner shown — instead of showing a precise-looking fake number. Sign inversion (some OEMs flip `CURRENT_NOW`) is auto-detected on the first charging samples. Reboots, clock jumps and Doze gaps close the current session as unusable for learning and re-anchor SoC from voltage. Corrupt saved data is quarantined (`*.corrupt-<timestamp>`) and the app starts fresh instead of crashing. All of this is logged locally (500-entry ring, exportable) with codes like `CURRENT_STUCK_ZERO`, `DOZE_GAP`, `CC_RESET`.

## Install

1. Download `merlin-battery.apk` from Releases (link at the top).
2. On the One Action, allow "Install unknown apps" for your browser / file manager when asked.
3. Install. **Do not uninstall to update** — every build is signed with the same stable key (`app/merlin.keystore.b64`, decoded at build time), so updates install cleanly over the old one. (This is the lesson from Merlin Launcher v1.2.2, where a fresh debug key on every build made Android silently refuse updates.)
4. Open the app, tap **Start Calibration**, and do a full charge.

## Build it yourself (GitHub)

Kodi: create the empty repo `torreskodi02-gif/merlin-battery` on github.com (the GitHub App cannot create repos), then paste `build-apk.yml` into `.github/workflows/build-apk.yml` via the web / mobile editor (the GitHub App cannot push workflow files). Every push to `main` then builds the APK into the `latest` release.

Locally, the pure math engine compiles and tests with plain kotlinc (no Android SDK needed):

```
kotlinc app/src/main/java/app/merlinbattery/BatteryEngine.kt tests/BatteryEngineTest.kt -include-runtime -d battery-test.jar
java -jar battery-test.jar
```

The full APK builds in GitHub Actions (JDK 17, Android SDK, Gradle 7.5, AGP 7.4.2, targetSdk 30, minSdk 26).

## Project layout

```
app/src/main/java/app/merlinbattery/
  BatteryEngine.kt    pure math — coulomb, OCV, fusion, learning, time, faults (no Android imports)
  BatteryMonitor.kt   foreground service — sampling, sessions, sign/R detection, state
  BatteryStore.kt     SharedPreferences + JSON files, atomic writes, export
  GaugeView.kt        arc gauge + line chart (custom Views)
  Ui.kt               colours, cards, stat builders
  MainActivity.kt     5 tabs: Dashboard / Health / Charging / History / Settings
tests/BatteryEngineTest.kt   25 test vectors (T01–T35 subset, see engine spec)
```

## TODO / known limits (v1.0.0)

- Kernel `charge_full` / `cycle_count` cross-check is not yet in the UI — probe on the real One Action whether those sysfs nodes are readable unrooted.
- No boot receiver yet: monitoring starts when you open the app. Add `BOOT_COMPLETED` to auto-start after a reboot.
- Alarms are toggles in v1; the notification firing on threshold crossing is wired to the service notification, full alarm delivery is a v1.1 item.
- `CURRENT_NOW` / `CHARGE_COUNTER` support is **unverified on the One Action** — the first install will show in Settings whether the phone reports them. If current is unsupported, the app falls back to voltage + level with lower confidence, exactly as designed.
