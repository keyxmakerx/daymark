# On-Body Breathing / Overnight Apnea Capture — Developer Notes

> Status: **algorithm built & unit-tested; screen-on capture harness built; overnight version NOT
> built; on-device validation PENDING.** Strictly experimental, non-diagnostic general-wellness.
> This doc captures everything needed to resume cold.

---

## 0. Why this exists (the research context)

The requester is a **very quiet sleep-apnea** sufferer with an **AC running next to the bed**.
The full sleep research (see `docs/SLEEP_FEATURE_PLAN.md`) established:

- **Passive microphone fails for quiet/central apnea** — it keys on the snore→pause→gasp cadence;
  no snore = nothing to anchor on. And **steady AC/fan noise is the single worst-case** for
  breathing audio (a state-of-the-art model's macro-F1 fell to 0.57 under fan/AC). So mic is the
  wrong primary sensor for this user.
- **The signal that survives is respiratory MOTION, not sound.** The strongest *phone-only* result
  in the literature put the **phone on the abdomen**: F1 0.786–0.821, AHI correlation r 0.84–0.93
  (Nature s41598-025-99801-3). It is **snore-independent and immune to AC/fan noise** (it's motion).
- The user themselves proposed "lay with the phone on you" — which is exactly this on-body route,
  and is **simpler to build than sonar** (just read the accelerometer; no 18–20 kHz per-device
  speaker/mic calibration).

**Hard limits (state plainly, never overclaim):** cannot produce an **AHI**; cannot **diagnose**;
**cannot distinguish central vs obstructive** apnea (that needs chest-vs-abdomen effort phase / two
belts); a single channel only gives "pauses happened." Output is always "we saw breathing pauses —
worth a clinician's look," never a number or verdict.

---

## 1. What "a 3-minute check" is and isn't (IMPORTANT)

A few minutes while **awake** CANNOT screen apnea. Apnea is **events per hour across the whole
night** (the AHI concept) — sporadic events you only catch over hours of *sleep*.

The shipped **2–3 min capture harness is a SENSOR-VALIDATION GATE**, not the apnea screen. It
answers one question: **can this phone's accelerometer + our algorithm detect the user's breathing
at all**, in ideal still/awake conditions? Rationale: validate the cheap step before building the
expensive overnight one.

- **If the 2-min check reads a sensible breaths/min (~12–18):** the pipeline works on this device →
  build the overnight version (§3) with confidence.
- **If it can't read breathing even when still/awake:** the on-body route is unlikely to work on
  this device → **pivot** (e.g. nightstand FMCW sonar, ApneaApp-style — see SLEEP_FEATURE_PLAN
  Tier 4; harder, device-variable, but contactless and also AC-immune).

**OPEN ITEM:** the requester has not yet run the 2-min check / reported a breaths/min. That result
gates everything below.

---

## 2. What's built today

### 2a. `com.daymark.app.sensing.BreathingDetector` (pure, Android-free, UNIT-TESTED)
Analyzes a window of **accelerometer-magnitude** samples (`sqrt(x²+y²+z²)`) + a sample rate:
1. **Detrend** — centred moving average (4 s window) removes the gravity/posture baseline, leaving
   the breathing oscillation. (Prefix-sum implementation, O(n).)
2. **Rate** — normalized **autocorrelation** within the breathing band (6–30 bpm → period 2–10 s);
   peak lag → bpm; peak value → confidence (0..1).
3. **Pauses** — moving **RMS envelope** (2 s window); baseline = median RMS; a *pause* = a
   contiguous stretch where RMS < `0.25 × baseline` for **≥ 10 s**. Returns `PauseEvent(startSec,
   durationSec)`.
- Returns `Result(breathingRatePerMin: Double?, confidence: Double, pauses: List<PauseEvent>)`.
- Constants at top of file (band, windows, MIN_ANALYSIS_SEC=20, MIN_PAUSE_SEC=10, PAUSE_FRACTION=0.25).

**Tests** (`app/src/test/.../sensing/BreathingDetectorTest.kt`, all green): recovers 15 & 10 bpm
within ±1.5 from synthetic sine; flags a 20 s pause (start/duration sane); returns null rate for
<20 s input; flat input → no rhythm, no phantom pauses, no crash.

### 2b. Screen-on capture harness (device-testable, NO permissions)
- `ui/sleep/BreathingCaptureViewModel.kt` — injects `@ApplicationContext`, gets `SensorManager`,
  registers `TYPE_ACCELEROMETER` at `SENSOR_DELAY_GAME` (~50 Hz), accumulates magnitude + uses
  `event.timestamp` (ns) to derive the **measured** sample rate, runs `BreathingDetector` on stop.
  States: Idle / NoSensor / Capturing(progress, secondsLeft) / Done(result). Nothing persisted.
- `ui/sleep/BreathingCaptureScreen.kt` — instructions ("phone flat on chest, breathe normally,
  stay still"), 1/2/3-min choice, live countdown, **keeps screen on only while capturing**
  (`view.keepScreenOn`), honest result (rate, pauses "worth a clinician's look", or
  "couldn't get a clear reading" retry). Reached via **More → Sleep → Breathing check**.
- Accelerometer needs **no runtime permission** and (at ~50 Hz) **no** `HIGH_SAMPLING_RATE_SENSORS`.
  This is why the harness is fully real/verifiable now without service/wake-lock plumbing.

---

## 3. The overnight version (NOT built — the real apnea screen)

The harness's screen-on/ViewModel approach does NOT work overnight (screen off, hours). The real
version needs:

### 3a. Foreground service + wake lock
- A **foreground service** (so the OS won't kill it) with a persistent notification. On targetSdk
  34+, declare an FGS **type** — `health` (fits sleep/fitness) or `dataSync`/`specialUse`; verify
  against the exact sensor + the matching permission. minSdk 26 plain FGS is fine; add the type
  declaration for 34+.
- A **`PARTIAL_WAKE_LOCK`** held during the session — the FGS keeps the process alive but does NOT
  keep the CPU awake; a recording-only workload gets no automatic CPU wake lock. (Audio playback
  does, recording does not.) Release on stop.
- **Mic FGS would need to start from the foreground** (RECORD_AUDIO is while-in-use) — N/A here
  since we use the accelerometer, which can run from the background. The user still starts the
  session from the app ("Track tonight").

### 3b. Low-rate, BATCHED sampling (the battery lever)
- Breathing is **< 0.5 Hz**, so **~10–25 Hz is ample** (even 10 Hz). Down-rate from the default.
- Register with a **large `maxReportLatencyUs`** (tens of seconds) so the **sensor-hub FIFO buffers
  in hardware while the CPU stays suspended**, waking only to drain. This is the single biggest
  battery saver. Requires `getFifoMaxEventCount() > 0` — **guard and degrade gracefully** where the
  device has no FIFO (fall back to a higher wake rate or warn).
- Being **plugged in disables Doze**, which improves overnight reliability (no deferred work).

### 3c. Window-by-window analysis (don't hold the whole night in RAM)
- 8 h at even 25 Hz = ~720k samples — do NOT keep them all or run one giant autocorrelation.
- Process in **~60 s windows**: run `BreathingDetector.analyze()` per window, record the window's
  rate + any `PauseEvent`s, **discard the raw samples** (privacy + memory), keep only features.
- **Aggregate across the night:** total pauses, **pauses-per-hour** (a screening *proxy*, label it
  honestly — NEVER call it an AHI), a breathing-rate trend, % of night with a usable signal.

### 3d. Safety guards (researched; policy already written — see SLEEP_FEATURE_PLAN §6 / thermal agent)
- **START preconditions:** on external power (`EXTRA_PLUGGED != 0`, status CHARGING/FULL), battery
  ≥30%, thermal NONE/LIGHT (API 29+), battery temp <40 °C.
- **RUNTIME triggers:** thermal MODERATE → back off (lower rate); **thermal SEVERE → STOP gracefully
  + flush/save + notify**; battery temp >45 °C → stop (warn 40 °C); unplugged → 60 s grace → stop;
  battery <10% → stop.
- **APIs:** `PowerManager.getCurrentThermalStatus`/`addThermalStatusListener` (API 29),
  `getThermalHeadroom` (API 30, rate-limited ~10 s, NaN if unsupported), `ACTION_BATTERY_CHANGED`
  sticky intent (status/plugged/temp in one read), `ACTION_POWER_CONNECTED/DISCONNECTED` (must be
  runtime-registered, API 26+).
- **minSdk-26 fallback (API 26–28, no thermal API):** poll battery temperature every 30–60 s, warn
  40 / stop 45; surface a "limited thermal protection" note.
- The OS guarantees no actual overheat (Thermal HAL); our job is to stop *adding* heat + save data
  before the system intervenes. **All temp/% thresholds are judgment defaults — tune on devices.**

### 3e. Persistence (new Room table, future migration v7)
Proposed `BreathingSession(id, night: Long /*epochDay*/, startedAt, endedAt, plugged: Boolean,
usableMinutes: Int, breathingRateMedian: Double?, pauseCount: Int, longestPauseSec: Int,
pausesPerHour: Double)`. Persist the **summary only** (events/features), never raw samples.
Surface in the Sleep hub + tie to the night's diary entry; feed pause counts into trends and the
treatment before/after (TreatmentStats).

### 3f. UX
- "Track tonight" from the Breathing screen → requires plugged-in → persistent notification while
  running → stops at a set wake time or on user stop → morning summary. Calibration profile
  (partner/pets/placement/position) gates honesty: if shares-bed/pets, caveat attribution; if phone
  not on the body, this mode is unavailable (offer nightstand/mic instead).

---

## 4. Tuning & known failure modes (validate on real overnight data)
- **Body movement / position changes** (rolling over) create big accelerometer transients that look
  nothing like breathing AND can masquerade as pause boundaries. The overnight pipeline must **gate
  out gross-movement windows** (high broadband energy) and mark them "no reliable signal" rather
  than counting them as breathing or pauses. Back-sleepers give the cleanest signal.
- **Pause thresholds** (`PAUSE_FRACTION=0.25`, `MIN_PAUSE_SEC=10`) are first-guesses — re-tune
  against real nights; clinically, apnea events are ≥10 s, but a phone may need a different envelope
  threshold. Risk of false pauses from the phone shifting/slipping off the chest.
- **Phone slips off the chest** → signal lost; detect "no breathing signal for X min" and flag the
  session low-quality rather than reporting confidently.
- **Sample-rate jitter** — Android accelerometer rate is approximate; we use the *measured* rate.
  Autocorrelation tolerates mild jitter; heavy batching may need per-window rate recompute.
- **Can't separate central vs obstructive** (no effort-phase) — only "pauses with little body
  motion, possibly central-type — see a clinician." Never assert the type.

## 5. Honest limits / overclaim flags (carry into all copy)
❌ No AHI. ❌ No diagnosis. ❌ No central-vs-obstructive. ❌ No SpO₂ (needs a real oximeter).
⚠️ Phone-on-mattress accelerometry has **no independent PSG validation**; the F1 0.79–0.82 result
is **phone-on-abdomen (worn-ish)**, not nightstand. ⚠️ Single mic/phone can't attribute to a
person (→ calibration profile). Frame everything as "we saw a pattern — get a real evaluation,"
and **never** an all-clear.

## 6. Files (current)
- `app/src/main/java/com/daymark/app/sensing/BreathingDetector.kt` (algorithm)
- `app/src/test/java/com/daymark/app/sensing/BreathingDetectorTest.kt` (tests)
- `app/src/main/java/com/daymark/app/ui/sleep/BreathingCaptureViewModel.kt` (harness capture)
- `app/src/main/java/com/daymark/app/ui/sleep/BreathingCaptureScreen.kt` (harness UI)
- Nav: `Routes.BREATHING`, wired in `DaymarkAppScaffold` + `SleepScreen` ("Breathing check").
- Context docs: `docs/SLEEP_FEATURE_PLAN.md` (full sleep research), this file.

## 7. Resume checklist
1. Get the **2-min check result** on the target device(s). Sensible bpm? → proceed. Not? → pivot to
   sonar.
2. Build the **overnight foreground service** (§3a–3c) — batched low-rate sampling, per-window
   analysis, discard raw, aggregate pauses/hour.
3. Wire the **safety guards** (§3d) — plugged-in required, thermal stop, unplug/low-battery stop.
4. Add **`BreathingSession`** table (migration v7) + Sleep-hub summary + diary/trend/treatment ties.
5. Add a **movement-gating** stage and re-tune pause thresholds on real nights.
6. Keep it experimental + non-diagnostic; honor the calibration profile for attribution honesty.
