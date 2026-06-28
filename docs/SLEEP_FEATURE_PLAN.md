# Daymark — Sleep & Sleep-Apnea Screening: Full Feature Plan

> Status: **planning / research-complete, not yet built.** This document captures the entire
> investigation (June 2026) — mission, ethics, every sensor approach with pros/cons/maybes,
> the architecture, safety, privacy, licensing, what's possible vs impossible, coping & treatment
> tracking, and a build order. It is intentionally exhaustive so work can resume cold.
>
> **Nothing here is a medical device or a diagnosis.** Everything is general-wellness screening.

---

## 0. Mission & non-negotiable ethics

**Why this exists:** a free, open-source, local-only tool for people who **cannot pay** for a
sleep app or easily access testing. The goal is to be the nudge that says *"yeah, you really
should get this properly checked"* — lowering the barrier to seeking real care.

**The asymmetric-honesty rule (THE most important principle):**
- ✅ The tool MAY **raise concern**: "we saw a pattern some people with sleep apnea show — please
  get a proper evaluation."
- ❌ The tool MUST NEVER **give the all-clear**: it must never say "you don't have apnea," "you're
  fine," or "you don't need testing." Absence of a detected pattern means *we didn't see one*,
  **not** that there's nothing there. A quiet sleeper, a bad sensor night, or the wrong mode can
  all hide a real problem. **False reassurance is the dangerous failure mode** — design so it is
  literally impossible for the app to emit a reassuring "no."
- Every output is an **observation + "this is not a diagnosis, consider a clinician"**, offline.

**Regulatory posture:** stay firmly in the FDA "general wellness" lane — non-diagnostic, no AHI
number, no treatment claims, no "you have X." Frame as awareness + self-tracking.

**Two halves the user defined (earlier "testing vs care"):**
1. **Testing / Check-ins** — *detect* possible issues, nudge toward real evaluation.
2. **Care / Coping** — for people who already know (or are being treated): supportive,
   evidence-based, non-prescriptive help + track whether their treatment is working.
Each is individually opt-in, default OFF.

---

## 1. The core reframe (what the research changed)

We started thinking "microphone → detect snoring." The research flipped the priority:

- **Microphone is snore-biased.** It keys on the snore→pause→gasp cadence. For **quiet** sleepers
  and **central** apnea (often silent) it structurally fails.
- The signal that survives when there's no snore is **respiratory MOTION, not sound.**
- So the right primary sensor depends on the *person*. There is no one magic sensor — there's a
  **mode per sleeper**, chosen by a short calibration questionnaire.

---

## 2. The sensing approaches — full pros/cons/maybes

### 2.1 Sleep-window estimate (phone usage: screen + charging + taps) — TIER 1
- **What:** infer sleep onset/wake from last screen-off, first meaningful screen-on, charging
  window, and touchscreen-interaction gaps ("tappigraphy").
- **Pros:** ~free battery, no sensors, no mic, no new permissions (or one settings grant);
  strong for *timing* (R² ~0.84–0.90 vs actigraphy; tappigraphy onset R²~0.84, wake R²~0.90).
  Privacy-perfect. Feeds mood correlation immediately.
- **Cons / buts:** underestimates *duration* (people glance at phones at night); can't see naps,
  shift work, or insomnia well; validated mostly on young adults.
- **Verdict:** **build first.** Real value, zero risk.

### 2.2 Accelerometer actigraphy (phone on mattress) — TIER 2
- **What:** movement → sleep/wake via published Cole-Kripke / Sadeh formulas (pure Kotlin, no ML,
  no model file). Gives restlessness index + bed-exit count.
- **Pros:** low power **if batched** (hardware FIFO buffers while CPU sleeps — wake every few min);
  formulas are public facts, free to reimplement; no mic.
- **Cons / buts:** phone-on-mattress is materially worse than a wrist device (measures mattress
  vibration; mattress type, partner movement, position all matter); **no independent PSG
  validation** for phone-on-mattress — don't oversell. Coefficients were tuned for wrist
  "activity counts," NOT raw phone m/s² → must derive a per-epoch count proxy and **re-tune**.
  Needs a non-zero sensor FIFO (`getFifoMaxEventCount() > 0`) — degrade gracefully where absent.
- **Maybe:** good for "restless vs restful" *relative* trend and "you got up N times," not a
  disorder label.

### 2.3 Microphone — passive event detection (snore/cough/sleep-talk) — TIER 3
- **What:** real-time streaming, cheap DSP gate → defensive VAD veto → feature extraction in RAM →
  classifier → **rhythm confirmation** → event log. (See §3 architecture, §4 snore-vs-talk.)
- **Pros:** snore/cough/breathing detection is well-validated (snore sens ~86–90%, spec ~99.5%);
  works in the dark, no aiming; runs on low-power audio path.
- **Cons / buts:** **the costly tier** (~10–25%/night → must be plugged in + thermal-gated);
  **fails for quiet/central apnea**; **AC/fan is its single worst-case noise** (breathing-detector
  macro-F1 fell to 0.57 under fan/AC — worse than speech); whisper/sleep-talk is the dominant
  confuser (~11% FP); a single mic **cannot attribute a snore to a person** (→ partner/pet Q);
  bystander speech privacy risk.
- **Verdict:** great for **snorers in a quiet room**; wrong default otherwise.

### 2.4 Active sonar (FMCW 18–20 kHz, ApneaApp-style) — TIER 4 (promoted for quiet apnea)
- **What:** phone speaker emits inaudible chirps; mic reads the echo off the chest; FFT extracts
  chest-motion → breathing waveform + apnea pauses. Contactless.
- **Pros:** **strongest phone-only route for QUIET/CENTRAL apnea** — senses chest motion, snore-
  independent. ApneaApp: **central-apnea ICC 0.9957** (its *best* number), OA 0.9860, hypopnea
  0.9533 (37 pts, phone 0.3–0.7 m). Replication 94% sens/97% spec @AHI≥15. **The ultrasonic band
  sits ABOVE the AC's low-frequency noise → the AC that kills the mic barely affects sonar.**
- **Cons / buts:** **hardest to build** — per-device speaker/mic response varies near 20 kHz; OS
  audio preprocessing can silently corrupt the probe (need UNPROCESSED capture, API 24+); **no
  drop-in OSS** (UltraSense = GPL-2.0 emit/record only, no detection; ApneaApp DSP repo = MIT but
  Python/laptop, must port + build detection). Solo sleeper, ≤~0.7 m; **audible to kids/pets**;
  needs plugged in + screen-off; can't be marketed "inaudible" or "98% accurate" (clinical-lab
  numbers, controlled placement).

### 2.5 On-body motion (phone on chest/abdomen) — the quiet-apnea sweet spot ★ (user idea)
- **What:** user lies WITH the phone resting on the torso → accelerometer reads chest rise/fall
  directly. The phone becomes a contact breathing sensor.
- **Pros:** **best path for the quiet+AC case** — it's *motion not sound* so the **AC is
  irrelevant**; snore-independent so it catches silent pauses (chest stops/restarts); phone on
  YOUR body → **partner/pet attribution confound largely gone.** **Strongest phone-only apnea
  result in the whole search** = phone ON ABDOMEN, F1 0.786–0.821, AHI r 0.84–0.93
  (Nature s41598-025-99801-3). **Simpler than sonar** (just read the accelerometer; no 20 kHz
  per-device calibration). Accel alone suffices.
- **Cons / buts:** comfort — sleeping with a phone on you; it can **slide off**; **rolling over /
  side-sleeping create artifacts** (must be gated; back-sleepers cleanest). "Wearable-ish" —
  more accurate *because* coupled to the body, less convenient for the same reason.
- **Maybe setups:** loose chest pocket, soft band, or rest-on-sternum-while-falling-asleep.
- **Verdict:** **the most personally-useful mode to prototype** for users like the requester.

### 2.6 Gyroscope — SKIP (continuous)
- Near-tie with the accelerometer for breathing, but **~6× the power**; only edge is cardiac
  (which we don't need). On a nightstand it's useless. **Skip continuous logging**; at most a
  brief on-chest "spot check."

### 2.7 Ambient light sensor (TYPE_LIGHT) — BUILD (context only)
- **Pros:** near-zero power; good for "is the room dark?" bedtime context and **coarse confounder
  tagging** (room brightened → distrust audio there).
- **The TV-flicker idea — BLOCKED by Android, not physics:** a TV flickers at 100–120 Hz, but
  `TYPE_LIGHT` is an *on-change* sensor effectively capped at a few Hz — it **cannot sample fast
  enough** to resolve flicker, and the phone's dedicated flicker sensor (for camera anti-banding)
  isn't exposed to apps. **We can detect "room light changed / not steadily dark" but NOT
  fingerprint a TV by flicker.** Honest copy = "ambient light changed," not "TV detected."
- **Caveat:** phone face-down/covered reads ~0 lux → darkness is a *soft prior*, never proof.

### 2.8 Camera (rPPG / chest-motion video) — DEFER, experimental opt-in only
- **Pros (in principle):** can read chest rise/fall (resp MAE 0.1–0.9 bpm in good light) and even
  heart rate (rPPG) — impressive in a lab.
- **Cons / buts (disqualifying for our brand):** **needs light** — phones lack usable IR; every
  robust dark-bedroom result uses **thermal/NIR hardware we don't have.** Motion-fragile over a
  night (collapses when you roll over). **Heavy battery/thermal** (one of the hottest loads a
  phone runs → must be plugged in, runs warm by the bed). **"A camera pointed at the bed all
  night" corrodes a privacy-first brand** even with a clean no-record-in-RAM pipeline (users can't
  verify it; trust cost is highest exactly where they're most defensive). rPPG HR/HRV across a
  dark room = research curiosity; HRV especially unreliable.
- **Verdict:** if ever, an explicit opt-in, plugged-in, **lit-room "spot reading"** — never
  overnight surveillance.

### 2.9 SpO₂ / blood oxygen — NOT viable on a phone
- Fingertip-camera SpO₂ is spot-only, accurate only 90–100%, **fails hypoxia-lab testing**, and
  cannot run continuously overnight. The overnight desaturation index (ODI) real home tests use
  **needs a real pulse oximeter.** Exclude; never imply otherwise. (A future *optional* BLE
  pulse-oximeter integration could be considered, but that adds hardware + likely networking —
  against the local-only model unless purely local BLE.)

---

## 3. Battery architecture (the "do we chunk it?" question, answered)

- **Don't record-then-batch.** Saving raw audio to process hourly is the *worst* option: you pay
  the full "keep mic + CPU awake" cost anyway, **plus** ~0.9 GB/night storage **plus** a privacy
  liability (a full night's bedroom audio sitting on disk).
- **The cost is keeping things awake, not the math.** Processing is cheap; preventing CPU deep-
  sleep + holding the mic is the expense, and it's the same whether you crunch live or later.
- **Mic → real-time streaming + cheap gate.** Process ~1-second slices in RAM; a featherweight
  energy/ZCR/flatness gate runs constantly (silence ≈ free) and only wakes the heavier classifier
  when something's loud. Cuts heavy work ~10–20×. **Discard each audio window immediately.**
- **Motion → TRUE hardware batching** (the user's "chunk it" instinct, correctly applied): register
  the accelerometer with a large `maxReportLatencyUs` so the **sensor-hub FIFO buffers in hardware
  while the whole phone sleeps**, waking the CPU only every few minutes to drain a chunk. This is
  where chunking genuinely saves battery (low single-digit %/night).
- **Being plugged in DISABLES Doze** → more reliable overnight (no deferred alarms/jobs).

---

## 4. Snore vs talking vs other sounds (mic discrimination)

- **Cleanest discriminator is RHYTHM, not pitch** (pitch overlaps): snores **lock to the breathing
  cycle** (~0.2–0.3 Hz, one burst per breath); speech bursts at the **syllable rate ~4–5 Hz**. ~20×
  separation, hard to fake, cheap to compute (autocorrelation of the energy envelope).
- **Pipeline:** cheap DSP gate → **defensive VAD veto** (detect speech only to DISCARD that window
  — privacy + kills TV/talk confounders, never transcribe/store) → MFCC+Δ & spectral features in
  RAM → small int8 CNN (~94% on-phone) or YAMNet embedding + tiny head (YAMNet Apache-2.0, has
  Snoring/Speech/Cough/Breathing/Television classes) → **rhythm confirmation** (require recurrence
  at the breathing cycle across a multi-breath window before logging) → persist EVENTS ONLY.
- **Reality:** snore sens ~86%, spec ~99.5%; **whisper/sleep-talk is the dominant confuser (~11%
  FP)**; cough & loud speech easily rejected; lab "98.5%" numbers are clean-data overclaims.
- **Confusers:** bed partner (single mic can't attribute), TV/white-noise/AC, distance attenuation.
  The rhythm-confirmation step is the strongest defense (rejects one-off coughs / aperiodic TV).

---

## 5. Audio retention — clip-on-confirmation (user-designed)
- **Rolling ~10–15 s buffer in RAM**, constantly overwriting. On a **confirmed** event (snore /
  sleep-talk / restlessness), flush *just that short clip*; everything else is overwritten into
  oblivion. ~0.9 GB/night → a few MB.
- **Default mode = "events only, no audio saved"** (you still get graphs + counts, zero clips).
  Opt-in **"save confirmed clips"** for playback. Auto-delete clips after ~7 days unless starred.
  Clearly labeled.
- **But:** a sleep-talk clip can capture words / a partner's voice → events-only is the safe
  default, and the partner/pet calibration answer governs whether clip-saving is even offered.
- **Never persist** raw PCM by default or frame-level cepstra (MFCCs are partially invertible) —
  store event metadata only.

---

## 6. Safety — plugged-in + thermal (required for mic & sonar tiers)
- **START preconditions:** on external power (`EXTRA_PLUGGED != 0`, status CHARGING/FULL),
  battery ≥30%, thermal NONE/LIGHT (API 29+), battery temp <40 °C.
- **RUNTIME triggers:** thermal MODERATE → back off (cut sample/DSP rate); **thermal SEVERE → STOP
  gracefully, flush/save, notify**; battery temp >45 °C → stop (warn 40 °C); unplugged → 60 s
  grace → stop; battery <10% → stop.
- **APIs:** `PowerManager.getCurrentThermalStatus`/`addThermalStatusListener` (API 29),
  `getThermalHeadroom` forecast (API 30, rate-limited ~10 s, returns NaN on unsupported),
  `ACTION_BATTERY_CHANGED` sticky intent (status/plugged/temp in one read),
  `ACTION_POWER_CONNECTED/DISCONNECTED` (must be runtime-registered, API 26+).
- **minSdk-26 fallback (API 26–28, no thermal API):** poll battery temperature every 30–60 s,
  warn 40 / stop 45; surface a "limited thermal protection" note.
- **The OS guarantees the phone won't actually overheat** (Thermal HAL throttles/shuts down). Our
  job: stop *adding* heat + save data before the system intervenes. **All temperature/percent
  thresholds are judgment defaults — tune on real devices.**
- **FGS types (if targetSdk 34+):** `microphone` (mic; must be user-started from foreground, no
  background/boot start), `health`/`dataSync` (sensors).

---

## 7. Multi-issue screening — report observations, not causes

**Core limit:** a phone sees *movement* and *sound*, not their *cause*. Movement events (apnea
arousal / nocturia bed-exit / RLS) are **mutually confounded** and clinically correlated → report
phenomena ("you left the bed 3×," "we heard snore-pause patterns," "high movement 2–4am"), never
disorder labels.

| Target | Phone feasibility | How / verdict |
|---|---|---|
| Snoring | **Easy** | mic events + nightly snore-load trend |
| Sleep apnea (snorers) | **Moderate** | mic snore-pause cadence; "possible breathing-pause patterns — see a clinician," **never an AHI** (#1 overclaim risk) |
| **Quiet/central apnea** | **Moderate** (right mode) | **on-body motion** or **sonar** (NOT passive mic); chest stop/restart; can't separate central vs obstructive |
| Frequent awakenings / bed-exits / nocturia | **Moderate** (count), **Hard** (cause) | accel + steps + screen + light corroborate the count/timing |
| Tossing / restlessness | **Easy** (relative index) | accel(+gyro) movement energy; not a disorder |
| **Restless legs (RLS/PLMD)** | **Needs-wearable → questionnaire only** | mattress phone **cannot** localize periodic *leg* jerks (only smartphone study was n=1, phone strapped to leg). Use **Ferri single-question** screener (100% sens / 96.8% spec) |
| Bruxism (teeth grinding) | **Hard** | too faint for a nightstand mic → symptom question |
| Sleep talking / vocalizations / night terrors | **Easy** (detect event), **Hard** (label) | log "loud vocal event," never name the disorder |
| Irregular schedule / social jetlag / delayed phase | **Easy** | timing only, robust, directly mood-relevant |
| Circadian / long-term trends | **Easy** | multi-night aggregation is the real strength; single nights are noisy |

**Central vs obstructive — hard impossibility:** needs respiratory *effort* (chest vs abdomen
phase) → RIP effort belts / esophageal pressure / airflow / multi-region camera. One phone channel
can't. Ceiling: "pauses with little body motion, possibly central-type — worth a doctor's review."

---

## 8. Sensor fusion — what it buys, and the wall
- **Buys (1) CONFIDENCE via corroboration:** accel bed-empty + step-counter walking + screen-on +
  light-change ⇒ high-confidence "got up." Several weak signals → one strong conclusion.
- **Buys (2) CONFOUNDER REJECTION:** light says room not dark ⇒ distrust audio; AC detected ⇒
  switch to sonar/on-body; partner/pet answer ⇒ caveat attribution.
- **The wall:** fusion does NOT grant new physical access. Can't localize to legs (RLS), can't see
  effort (central vs obstructive), can't see airflow/EEG (AHI/staging), can't infer a movement's
  *cause*. Rule: fusion turns "we think"→"fairly sure" and "you or the TV?"→"the TV," **not**
  "something happened"→"you have X." Calibration questionnaire sets priors; confidence scales with
  how many independent sensors agree.

---

## 9. Two questionnaires

### 9.1 Calibration / "sleep setup" profile (ask once, editable) — sets the priors
- **"Do you share the bed with a partner?"** → caveat + down-weight snore/movement attribution.
- **"Do pets sleep on/near the bed?"** → a cat looks like an RLS jerk to an accelerometer; down-
  weight motion.
- **"Where's the phone — mattress / nightstand / on your body?"** → selects the sensing mode.
- **"AC / fan / white-noise machine running?"** → expect mic unreliable; prefer on-body/sonar.
- **"How do you sleep — back / side / stomach?"** → artifact expectations + positional-apnea angle.

### 9.2 Screening self-checks (license-clean ONLY — the famous ones are encumbered)
- **Apnea:** STOP-Bang is **CC BY-NC-ND** (NonCommercial + NoDerivatives) → **do NOT bundle
  verbatim.** NoSAS/Berlin also "verify permission." **Safe path:** collect the underlying
  non-proprietary RISK FACTORS in our own wording/scoring (age, sex, BMI, neck circumference,
  bed-partner-observed pauses/gasping **without** snoring, hypertension, daytime sleepiness; +
  central-risk context: heart failure, opioid use, stroke) as a plain "discuss with your doctor"
  checklist. Works for non-snorers (most items are non-acoustic). No questionnaire validates
  *central* screening → present as risk context, not a score.
- **Sleepiness:** Epworth is licensed (Mapi) → plain "do you doze off unintentionally by day?"
- **Insomnia:** ISI licensed → **Athens Insomnia Scale** or self-authored items.
- **RLS:** IRLS licensed → **Ferri single question**.
- Keep an `INSTRUMENTS.md` license ledger; never alter validated wording; non-diagnostic banner.

---

## 10. CARE / COPING — for people who already wear it or are diagnosed (the "care" half)

> Coping ≠ treatment. For apnea, the app must **never** position itself as a substitute for CPAP /
> surgery / medical care. Coping nudges are *adjuncts*, always "in addition to, not instead of."

### 10.1 Evidence-based, non-prescriptive coping nudges (opt-in)
- **Positional therapy** (strong, actionable): many people have **positional OSA** — pauses cluster
  when supine (on the back). The phone can **infer sleep position** (accel/on-body) and correlate
  apnea-pattern events with position → *"your pauses cluster when you're on your back — side-
  sleeping may help; mention it to your doctor."* This is one of the highest-value, genuinely
  detectable coping angles.
- **Schedule regularity / sleep hygiene:** the app already tracks the sleep window → nudge
  consistent bed/wake times, wind-down reminders, caffeine cutoff.
- **Alcohol / sedative timing:** these worsen apnea — correlate the existing activity tracker
  (alcohol logged) with that night's snore-load/restlessness → surface the pattern (not a lecture).
- **Weight** is a risk factor but **sensitive** — handle with extreme care, opt-in, neutral
  language, never shaming; probably just "weight is a known factor, discuss with your doctor."
- Mind: keep all of this as *information + patterns*, defer specifics to clinicians.

### 10.2 Treatment-efficacy tracking — "is your CPAP / surgery actually helping?" (high value)
The app **cannot read the CPAP's own data** (that needs the vendor's cloud/account — against our
local-only model), **but** it can track the **downstream signals it already measures**, before vs
after:
- **CPAP:** log adherence ("used it tonight?" + optional nights-used streak) and overlay against
  the app's own trends — snore-load down? restlessness/arousal cadence down? morning mood/energy
  up? bed-exits down? → a personal *"things look better/worse since starting CPAP"* trend for
  people without access to the machine's report, or as a sanity check.
- **Surgery / oral appliance / positional therapy:** mark a "treatment started" date → before/after
  comparison of the same trends. Honest framing: *"your snore-load is lower than before your
  procedure"* is an **observation**, not proof the surgery "worked"; confounders abound.
- **Mask/pressure problems surfaced indirectly:** if restlessness/arousal patterns *worsen* after
  starting CPAP, that's worth flagging — *"things look rougher since you started — worth telling
  your sleep doctor"* (mask fit / pressure can need tuning). Again: nudge to the clinician, never a
  fix.
- **Adherence support (gentle):** optional reminders, a non-gamified "nights used" log, and tying
  better mornings to use-nights as positive reinforcement (carefully — not coercive).
- **Hard limit:** none of this is an AHI or a treatment outcome measure. It's "your *self-tracked
  patterns* trended this way — bring it to your clinician."

---

## 11. Privacy / trust architecture (a competitive advantage)
- **Ship with NO `INTERNET` permission** → "your audio/data never leaves the phone" becomes an
  OS-enforced fact, not a promise. F-Droid surfaces this; **reproducible builds** let anyone verify
  the APK matches the public source. No commercial sleep app can match this.
- **Process in RAM, persist events only** (Google's own Pixel cough/snore pattern). Never write raw
  PCM by default; never frame-level cepstra.
- **Granular control** (Samsung-style): events-only vs save-clips; short retention + auto-delete.
- **Honest about the mic dot:** Android 12+ shows a persistent mic indicator all night — tell users
  upfront it's expected.

---

## 12. Licensing (all clean for GPL-3.0 / F-Droid)
- **Algorithms** (Cole-Kripke, Sadeh, snore DSP, FMCW sonar math) are **published facts** →
  reimplement freely. Don't copy GPL `actigraph.sleepr` code or the unlicensed snore repos — study
  the technique (Edge Impulse tutorial is the clean reference), write our own.
- **Training data (commercially-usable, CC-BY):** PSG-Audio (CC-BY 4.0, 212 nights mic+PSG),
  APSAA (CC-BY 4.0), COUGHVID + Coswara (CC-BY 4.0). **Avoid:** ESC-50 (NC), Munich snore corpus
  (research-only), clinical PSG behind DUAs.
- **Model backbone:** YAMNet weights Apache-2.0.
- **Sonar reference code:** UltraSense (GPL-2.0, emit/record only), ApneaApp partial reimpl (MIT,
  Python) — reference, build our own.
- **Questionnaires:** STOP-Bang CC-BY-NC-ND, Epworth/ISI/IRLS licensed → reimplement risk factors /
  use Ferri & Athens (see §9.2).

---

## 13. Data model (sketch) & roadmap placement
Slots into **Phase 2b (Sleep)**, AFTER the Tier-2a custom-tracker foundation (a sleep score is just
a tracker value; events/screeners reuse `TrackerLog`). Proposed local entities:
- `SleepSession(id, start, end, mode[USAGE/MOTION/ONBODY/MIC/SONAR], plugged, noiseFloorDb, …)`
- `SleepEvent(id, sessionId, type[SNORE/COUGH/TALK/MOVEMENT/BED_EXIT/PAUSE], at, durationMs, confidence, clipPath?)`
- `SleepProfile` (calibration answers — partner/pet/placement/AC/position)
- `Treatment(id, kind[CPAP/SURGERY/APPLIANCE/POSITIONAL], startedAt, note)` + per-night adherence
All local, no INTERNET permission.

---

## 14. Build order (incorporating quiet-apnea reality)
1. **Tier 1 — sleep-window estimator** (free, private, real value). *Ship first.*
2. **Tier 2 — motion** (restlessness + bed-exit) **+ light** (context). Batched, low power.
3. **Calibration + risk-factor questionnaires** (apnea/insomnia/RLS, license-clean, always
   available — the backstop when sensors fail; works for quiet sleepers).
4. **On-body breathing mode ★** (the quiet-apnea sweet spot; simplest real apnea-screen for the
   requester — accel on the chest, AC-proof, snore-independent). *Strong candidate to prototype
   early since it's the most personally useful.*
5. **Tier 3 — mic events** (snorers; events-only default; plugged-in + thermal-gated; AC-detection
   → mode switch).
6. **Tier 4 — sonar** (contactless quiet/central mode; hardest to build).
7. **Care/coping layer** — positional-therapy nudge, schedule/hygiene, treatment-efficacy
   before/after tracking (CPAP/surgery), adherence support.
8. **Multi-night fusion dashboard** — the honest, trend-based payoff.

Every output, every tier: **observation + "this is not a diagnosis — if in doubt, get properly
tested," and never an all-clear.**

---

## 15. Hard "NOT possible on a phone" list (state plainly, never overclaim)
- ❌ An **AHI** number. ❌ A **diagnosis**. ❌ **Central vs obstructive** classification (needs effort
  belts). ❌ **Overnight SpO₂ / desaturation** (needs a real oximeter). ❌ **Sleep staging**
  (REM/deep %) from a phone. ❌ Telling **whose** snore/movement it was, from a single nightstand
  mic. ❌ Reliable **RLS/PLMD** from a mattress phone (questionnaire only). ❌ **TV-flicker
  fingerprint** via `TYPE_LIGHT` (API too slow). ❌ A reassuring **"you're fine, don't get tested."**
