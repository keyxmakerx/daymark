# Daymark Companion — Expanded User Features: Questionnaire & Cognitive-Testing Engine (sit-down)

> ## ⚠️ STATUS: DESIGN ONLY — NO CODE EXISTS YET
>
> **Nothing in this document is implemented.** There is no questionnaire engine, no
> cognitive-testing harness, no `instrument_results`/`task_results` table, and no
> Companion sit-down portal in the shipping app today. This is a **build-ready design
> proposal** — a contract to be reviewed, sequenced, and possibly built. It may change
> or be dropped.
>
> Consistent with [COMPANION_SCOPE.md](COMPANION_SCOPE.md),
> [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md),
> [COMPANION_SECURITY.md](COMPANION_SECURITY.md),
> [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md),
> [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md),
> [COMPANION_README.md](COMPANION_README.md),
> [COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md),
> [COMPANION_UX.md](COMPANION_UX.md),
> [INSTRUMENTS.md](INSTRUMENTS.md), [DESIGN.md](DESIGN.md), and
> [../HANDOFF.md](../HANDOFF.md) §0. These features live **only** in the Companion
> sit-down portal (big screen, keyboard, mouse) — **never** on the phone, which stays
> offline with no `INTERNET` permission.

---

## Table of contents

- [0. Prime directives this design inherits (do not break)](#0-prime-directives-this-design-inherits-do-not-break)
- [1. Where this lives (surface map)](#1-where-this-lives-surface-map)
- [2. The Questionnaire Engine (data-driven, no code per instrument)](#2-the-questionnaire-engine-data-driven-no-code-per-instrument)
- [3. Cognitive / Attention Testing Harness (original tasks)](#3-cognitive--attention-testing-harness-original-tasks)
- [4. First catalog (license-clean) — mirrors & extends `INSTRUMENTS.md`](#4-first-catalog-license-clean--mirrors--extends-instrumentsmd)
- [5. Data model — recording, versioning, attribution, sync](#5-data-model--recording-versioning-attribution-sync)
- [6. Non-diagnostic framing, crisis posture, therapist boundary](#6-non-diagnostic-framing-crisis-posture-therapist-boundary)
- [7. Security as a first-class feature (UI/UX) + cross-surface continuity](#7-security-as-a-first-class-feature-uiux--cross-surface-continuity)
- [8. Extensibility & keeping it honest (maintainer playbook)](#8-extensibility--keeping-it-honest-maintainer-playbook)
- [9. Build sequencing (suggested)](#9-build-sequencing-suggested)

---

## 0. Prime directives this design inherits (do not break)

These are **hard invariants**. A surface, definition, or task that violates any of them
does not ship.

1. **Non-diagnostic, always.** Every surface says *"self-check, not a diagnosis; scores
   are not clinical thresholds."* No verdicts, no risk scoring, no diagnosis, no
   "positive/negative screen." Association, not causation. (See
   [../HANDOFF.md](../HANDOFF.md) §0.)
2. **No AI / no generated content.** Every word is a fixed, human-authored template with
   the person's own numbers slotted in. Scoring is deterministic arithmetic over a
   declarative definition — never a model.
3. **Zero-knowledge server.** Results are computed **client-side in the browser**, written
   into the same E2EE `BackupData` snapshot, and stored on the server only as ciphertext.
   The server never sees an item answer, a reaction time, or a score.
4. **Vendored, strict-CSP web UI.** `default-src 'self'`. No CDNs, no Google Fonts, no
   analytics, no third-party origins. Fonts (Fraunces + Inter, OFL) are vendored as local
   `@font-face`. The cognitive tasks use only `requestAnimationFrame`, `performance.now()`,
   WebCrypto, and the Canvas/DOM already on the page — no external libraries at runtime
   beyond the vendored libsodium-WASM on the crypto path.
5. **License-clean only.** Public-domain / open / self-authored instruments only. The
   Companion [INSTRUMENTS.md](INSTRUMENTS.md) ledger is authoritative and mirrors the
   app's. **TOVA, Conners, and CAARS are forbidden** — a code comment in the loader forbids
   adding them and the loader rejects their ids.
6. **These are SELF tools.** Results land in the owner's local data and sync to the
   owner's own devices. A therapist sees them **only** if the owner explicitly seals them
   into a curated share ([COMPANION_THERAPIST.md](COMPANION_THERAPIST.md)). Default:
   private.

---

## 1. Where this lives (surface map)

```
Companion sit-down portal  (served by the container; strict-CSP; vendored)
└── "Sit-down" workspace (only meaningful on big screen + keyboard)
    ├── Questionnaires        → declarative instrument runner
    │   ├── ASRS v1.1 (self-report) ……… WHO notice rendered verbatim in-app
    │   ├── IPIP facet sets   ……………… public domain
    │   ├── WHO-5             ……………… © WHO, non-commercial w/ attribution
    │   └── (self-authored check-ins)
    ├── Focus & timing tasks  → cognitive/attention harness
    │   ├── Sustained-attention task (original CPT-style)   ← anchor
    │   ├── Working-memory task (original n-back-style)
    │   └── Interference task (original Stroop-style)
    └── History               → trends per instrument/task (descriptive only)
```

The phone app is **unchanged**: it keeps PHQ-9 / GAD-7 / WHO-5 as quick check-ins
(scores-only). The Companion **adds** longer instruments and timed tasks that "don't fit
on a phone." Results sync back to the phone via the encrypted snapshot and appear in the
phone's existing Check-ins / history views where applicable (scores/bands only). See
[COMPANION_UX.md](COMPANION_UX.md) for the cross-surface flows and
[COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md) for the visual language these
screens inherit.

---

## 2. The Questionnaire Engine (data-driven, no code per instrument)

### 2.1 Design goal

A maintainer adds a new license-clean instrument by **dropping in a JSON definition** and a
**ledger row** in [INSTRUMENTS.md](INSTRUMENTS.md) — **no code change, no per-instrument
rebuild logic**. The runner is a generic interpreter of a declarative schema. Scoring,
banding, branching, and timing are all expressed in data.

### 2.2 Item types (declarative)

| `type` | Renders as | Captures | Notes |
|---|---|---|---|
| `likert` | Radio row / segmented control | integer (mapped via `options[].value`) | Anchored labels (e.g. Never…Very Often). Keyboard 1–5. |
| `multiSelect` | Checkbox group | array of option ids | Optional `min`/`max` selected. |
| `singleSelect` | Radio / dropdown | option id | |
| `freeText` | Textarea | string | **Sentence auto-capitalization** (matches the app). May be excluded from scoring and from shares (see §6). |
| `slider` | Range slider w/ live value | number | `min`/`max`/`step`/`unit`; optional snap ticks. |
| `numeric` | Number input | number | `unit`, `min`/`max` validation. |
| `timedItem` | Item with a per-item deadline | value + `responseLatencyMs` | For "answer within N s" self-report items; latency captured but framed descriptively. |
| `info` | Static panel (no input) | — | Section intros, attribution notices, disclaimers. |

### 2.3 Branching / skip logic (declarative)

Each item may carry a `visibleWhen` predicate over already-answered items. Predicates are a
small, **pure, sandboxed** boolean DSL — **no `eval`, no executable JS in a definition** —
so an instrument definition can never run arbitrary code in the portal:

```jsonc
{
  "all": [
    { "ref": "q3", "op": "gte", "value": 2 },
    { "any": [
      { "ref": "q1", "op": "in", "value": [3,4] },
      { "ref": "q2", "op": "includes", "value": "trouble_focusing" }
    ]}
  ]
}
```

Ops: `eq, ne, gt, gte, lt, lte, in, includes`. Hidden items are skipped and contribute
nothing to scoring (their slot is `null`). Definitions ship a **completeness check** so a
branch cannot strand a required scored item.

### 2.4 Scoring & banding (declarative)

Scoring is expressed as data and executed by a deterministic evaluator. Methods: `sum`,
`mean`, `count_threshold`, `percent_of_max`.

```jsonc
"scoring": {
  "scales": [
    {
      "id": "asrs_partA",
      "method": "sum",               // sum | mean | count_threshold | percent_of_max
      "items": ["q1","q2","q3","q4","q5","q6"],
      "reverse": [],                  // item ids scored in reverse
      "bands": [                      // ordered, non-overlapping, DESCRIPTIVE only
        { "max": 11, "label": "Fewer self-reported signs today", "tone": "neutral" },
        { "min": 12, "label": "More self-reported signs today",  "tone": "attention" }
      ],
      "bandFraming": "These bands describe your own answers today. They are not a diagnosis, not a screen, and not a clinical threshold."
    }
  ]
}
```

> **MUST-FIX (review):** the engine **does not** reproduce a validated clinical cutoff or
> emit a diagnostic-screen flag. There is **no** `count_threshold` "shaded-box" screening
> output, no `flagAt`, and no `perItemThresholds` that reconstruct an instrument's
> positive/negative decision rule. ASRS in particular is presented as a **descriptive
> count + within-person trend only**. A **CI gate** (see §8) forbids any cutoff-aligned
> positive/negative flag in a definition. Bands are descriptive labels chosen by us, set at
> our own informal boundaries, never the source instrument's published cutoff.

Rules:

- **No clinical cutoffs are presented as clinical.** Bands are descriptive labels with a
  fixed `bandFraming` disclaimer. We may reproduce a public instrument's *scoring arithmetic*
  (a method/idea, not copyrightable) while wording all band labels and helper text in **our
  own non-diagnostic language**.
- **Self-harm is structurally absent.** No instrument definition may contain a self-harm /
  suicidality item slot, and the scoring engine has **no such slot** at all (mirrors the
  app's PHQ-9 item-9 absence on the synced bundle — see [INSTRUMENTS.md](INSTRUMENTS.md)).
  If a future public instrument includes one, it is **forbidden** from the Companion catalog
  by the ledger and rejected at load time.

### 2.5 Instrument definition file (the unit of extension)

```jsonc
{
  "instrumentId": "asrs-v1_1-selfreport",
  "instrumentVersion": "1.0.0",          // semver of OUR definition, not the source
  "sourceVersion": "ASRS v1.1",          // human label of the source instrument
  "title": "Adult self-report attention check-in",
  "license": "WHO — free to reproduce with the WHO notice shown verbatim in-app",
  "attribution": "© World Health Organization. Reproduced with the required notice (see noticeText).",
  "noticeText": "<verbatim source notice; immutable; load-time-verified — see §4.3>",
  "ledgerRef": "INSTRUMENTS.md#asrs",     // MUST resolve; CI fails otherwise
  "nonDiagnostic": true,                  // hard-required true; CI rejects false
  "noScreeningFlag": true,                // hard-required true; CI rejects cutoff-aligned flags
  "estimatedMinutes": 4,
  "sections": [ /* info + items */ ],
  "items": [ /* typed items, see §2.2 */ ],
  "scoring": { /* see §2.4 */ },
  "framing": {
    "intro": "This is a self-check, not a diagnosis...",
    "crisisPosture": "offline-static"     // see §6.2
  },
  "integrity": { "sha256": "<hash of canonical JSON>" }   // pinned, verified at load
}
```

**Load-time validation (client + CI):** schema-validate; assert `nonDiagnostic === true`;
assert `noScreeningFlag === true` and that no scale emits a cutoff-aligned positive/negative
flag; assert `ledgerRef` resolves; assert `noticeText` matches the committed verbatim notice
for that source (byte-for-byte); assert no forbidden instrument id; verify `integrity.sha256`
over canonical JSON; assert scoring references only existing items; assert bands are ordered
and non-overlapping; assert no self-harm slot. A definition failing **any** check **does not
load** (and CI is red).

### 2.6 Runner UX (modern paper)

- One question (or one short section) per "sheet" — a `paper-surface` card with a 1px
  hairline border, generous whitespace, serif section heading (Fraunces), Inter body. See
  [COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md).
- Progress is a quiet hairline meter, not a loud bar. "Question 7 of 18."
- **Keyboard-first** (the sit-down advantage): number keys select Likert options, Enter
  advances, Shift+Enter goes back, Esc opens "Pause / Save & exit." Full focus-visible rings;
  everything reachable without a mouse.
- **Resumable:** partial responses are saved (encrypted, local) so a long instrument survives
  a pause. Free-text gets auto-capitalization, matching the app.
- Attribution and the non-diagnostic banner are **fixed UI** (not data-supplied beyond the
  required verbatim source notice), shown on the intro and result screens.
- **Result screen** is descriptive: score, band label, the fixed band-framing disclaimer, a
  small trend sparkline from history, and a "What this is / isn't" expander. Never "you have
  X." Optional one-tap "Save to my data" writes the result record (§5).

---

## 3. Cognitive / Attention Testing Harness (original tasks)

> All tasks are **original Daymark tasks** — our own stimuli, timings, and copy. They are
> **not** reproductions of TOVA/Conners/CAARS or any licensed battery. They produce
> **descriptive performance summaries**, explicitly **not diagnostic**.

### 3.1 Anchor task — Sustained-Attention / Reaction task (original CPT-style)

**Paradigm (self-authored):** a stream of stimuli appears one at a time. Most are
**non-targets** ("respond") and a minority are **targets** ("withhold") — or the inverse,
configurable per task definition. The person presses the **spacebar** to respond.

**Reported metrics (all computed client-side):**

| Metric | Definition | Trust class (see §3.4) |
|---|---|---|
| **Omission count** | "Respond" stimuli with no press before the window closed. | **Robust** (count-based) |
| **Commission count** | Presses to a "withhold" stimulus (responding when you shouldn't). | **Robust** (count-based) |
| **Accuracy** | % correct overall and by block. | **Robust** (count-based) |
| **Drift** | RT mean per time-quartile over a long window (does responding slow over the run). | **Robust** (long-window) |
| **RT mean** | Mean reaction time on correct responses (ms). | **Caveated** (within-session, same machine only) |
| **RT variability** | SD of RT and coefficient of variation (SD/mean). | **Caveated + error-barred** (see §3.4) |
| **Anticipations** | Presses < 150 ms after onset; flagged and **excluded** from RT mean (too fast to be a real response). | Robust |

**Default parameters (tunable in the task definition):**

- Stimulus on-screen: **250 ms**; inter-stimulus interval: **1500 ms** (jittered ±250 ms to
  prevent rhythmic anticipation).
- Target probability: configurable (e.g. 0.2 for a withhold-on-target design).
- Run length: **~6 minutes** default (long enough for a sustained-attention read, short
  enough to sit through), with a short **practice block** first (results discarded).
- Response window = stimulus + ISI; presses outside any window are logged as "stray."

### 3.2 Browser timing & accuracy requirements (load-bearing)

A browser is not a lab millisecond clock; we make the task **honest about its own precision**
and engineer for the best achievable:

1. **Clock:** use `performance.now()` (monotonic, sub-ms) for all latencies — never
   `Date.now()`.
2. **Stimulus onset timestamp:** record onset in the `requestAnimationFrame` callback that
   actually paints the frame, **after** a forced reflow, so onset is the frame time, not the
   schedule time. Pair with the frame's `DOMHighResTimeStamp`.
3. **Input timestamp:** use the **native event `event.timeStamp`** (same time origin as
   `performance.now()` in modern browsers) for the keypress, not the handler-run time, to
   avoid main-thread queueing skew. Listen on `keydown` with `{ passive: false }`; ignore
   auto-repeat (`event.repeat`).
4. **Calibration & disclosure:** before the run, measure the display's frame interval over
   ~1 s; if it is not ~16.7 ms (60 Hz) adjust expectations and **record the refresh rate into
   the result metadata**. Compute a per-session **timing-quality score** (frame-jitter SD,
   dropped-frame count). If jitter exceeds a threshold, the result is **flagged
   "lower-precision"** and the UI says so.
5. **Latency floor honesty:** display, OS, and keyboard add a roughly **constant** offset
   (tens of ms). Because it is roughly constant, **count-based within-person, within-session
   signals (omissions, commissions, accuracy) and long-window drift stay meaningful**;
   **absolute RT across different machines is not comparable** and we say so explicitly. We
   never publish a normative absolute-RT threshold. (RT *variability* is treated separately —
   see §3.4 must-fix.)
6. **No background contention:** require **fullscreen** (Fullscreen API) and pause if the tab
   loses focus (`visibilitychange`) — the run is invalidated and restarts the current block.
   Warn to close other tabs; this is sit-down, not background.
7. **Input device note:** Bluetooth keyboards add variable lag; the UI recommends a wired
   keyboard and records `keyboard: wired|unknown` if the user states it.
8. **Determinism for review:** the stimulus sequence is generated from a **recorded PRNG
   seed** (stored in the result) so a run is reproducible / auditable. No network, no AI.

### 3.3 Other original timed tasks (same harness)

- **Working-memory task (original n-back-style):** a stream of items; respond when the current
  item matches the one **N steps back** (N configurable; 1-back practice → 2-back). Reports
  hits, false alarms, a d-prime-style sensitivity (computed locally, labeled descriptively),
  and RT on hits (RT subject to the §3.4 caveats). Original stimuli (letters/shapes from our
  own set), our own instructions.
- **Interference task (original Stroop-style):** color words rendered in
  congruent/incongruent ink; respond with the **ink color** (keys mapped to colors, shown
  on-screen). Reports congruent vs incongruent **accuracy/error counts** as the robust signal,
  plus a congruent/incongruent RT delta presented with the §3.4 caveat. Color choices are
  **color-blind-safe** with redundant letter cues; a setup screen lets the user confirm they
  can distinguish the mapping.

All three share: practice block, seeded sequence, fullscreen + focus guard, timing-quality
metadata, and a **descriptive** result screen ("your responses were faster on congruent
trials" — never "impaired").

### 3.4 Keeping results meaningful but NOT diagnostic

> **MUST-FIX (review):** do **not** claim RT variability (SD/CV) is protected by the
> constant-offset argument. Frame quantization and display/input jitter inflate **exactly**
> that metric — a roughly constant offset cancels in a mean but **adds variance** to an SD.
> Therefore:
>
> - The "meaningful within-session" signals we trust are restricted to the **count-based
>   metrics (omissions, commissions, accuracy) and long-window drift**.
> - Any **RT-variability** number is shown with a **quantified jitter error bar** derived
>   from the session's measured frame-jitter SD and refresh interval (e.g. "RT variability
>   88 ms ± ~12 ms instrument jitter"), so the reader sees how much of the spread the clock
>   could have manufactured.
> - The **"lower-precision" flag explicitly caveats the variability output** (and downgrades
>   or hides the CV) when frame jitter / dropped frames exceed threshold. RT mean is shown
>   only with the within-session, same-machine caveat; absolute cross-machine RT is declared
>   non-comparable.

Beyond that:

- **Within-person framing only.** Trends compare you to your **own** past runs, never to a
  population norm. No norms, no percentiles-vs-others, no cutoffs.
- **No diagnostic language.** Result copy is fixed templates: counts, means, variability (with
  its error bar), and a plain-English "what this measures / what it can't tell you" panel.
- **Confound disclosure** on every result: sleep, caffeine, time of day, practice effects,
  hardware. Optional one-line context tags (well-rested? caffeinated?) stored with the run so
  the person interprets their own trend honestly.
- **Practice effects** are named explicitly; early runs are flagged "still learning the task."
- A persistent banner: *"This is a focus self-check, not an attention test or a diagnosis."*

---

## 4. First catalog (license-clean) — mirrors & extends `INSTRUMENTS.md`

### 4.1 Included first (public-domain / open / self-authored only)

| Instrument / task | Type | License / status | Companion notes |
|---|---|---|---|
| **ASRS v1.1 (self-report)** | Questionnaire | WHO — free to reproduce **with the WHO notice shown verbatim in-app** | Descriptive **count + within-person trend only**; **self-authored, non-diagnostic** band labels; **no shaded-box screen, no positive/negative flag**; WHO notice rendered verbatim. No self-harm item. |
| **IPIP facet sets** | Questionnaire | **Public domain** (International Personality Item Pool) | Self-authored framing; descriptive facet scores, never trait "verdicts." Items reproduced as permitted (public domain). |
| **WHO-5** | Questionnaire | **© WHO, free for non-commercial use w/ attribution; not modified/sold** | Shown 0–100%; WHO attribution cited verbatim in-app. Matches the app exactly. |
| **Sustained-attention task** | Timed task | **Self-authored (original)** | The anchor CPT-style task (§3.1). Original stimuli/timings/copy. |
| **Working-memory task** | Timed task | **Self-authored (original)** | n-back-style (§3.3). |
| **Interference task** | Timed task | **Self-authored (original)** | Stroop-style (§3.3). |
| **Daymark focus/check-in self-reports** | Questionnaire | **Self-authored (original)** | Original questionnaires written for Daymark; reproduce no copyrighted instrument. |

> PHQ-9 / GAD-7 remain the phone's quick check-ins (free to reproduce; developed by Drs.
> Spitzer, Williams, Kroenke and colleagues, funded by Pfizer). They are not the focus of
> the sit-down suite, but the engine can render them identically if desired, keeping the
> **scores-only, item-9-never-persisted** invariant.

### 4.2 Explicitly EXCLUDED (forbidden — do not add)

| Instrument | Why excluded |
|---|---|
| **TOVA** | Proprietary / licensed attention battery. **Forbidden.** |
| **Conners (CRS, CPT)** | Copyrighted / licensed. **Forbidden.** |
| **CAARS** | Copyrighted / licensed. **Forbidden.** |
| ISI, ESS, STOP-Bang, PSQI, PANAS, WEMWBS, DASS-21, PSS, AAQ-II / VLQ / Bull's-Eye | Already forbidden per [INSTRUMENTS.md](INSTRUMENTS.md) (copyright / permission required). The Companion inherits this list. |

A code comment in the instrument loader **forbids adding any of these**, and the loader
rejects their ids. The Companion [INSTRUMENTS.md](INSTRUMENTS.md) ledger carries the same
NOT-bundled table.

### 4.3 Verbatim source notices (committed, immutable, load-time-verified)

> **MUST-FIX (review):** the verbatim source notices below are **committed into
> [INSTRUMENTS.md](INSTRUMENTS.md) and into each definition's `noticeText`**, are
> **definition-immutable** (covered by `integrity.sha256`), and are **byte-for-byte verified
> at load time**. The corresponding **`ledgerRef` anchors must resolve** before any of these
> definitions can pass CI. The exact legal wording is finalized in the ledger; the engine
> treats whatever the ledger commits as the canonical, immutable string.

**ASRS — required notice (rendered verbatim on the ASRS intro and result screens):**

```
© World Health Organization (WHO), 2003. Adult ADHD Self-Report Scale (ASRS-v1.1).
Reproduced with the notice required by WHO. This self-report check-in is provided for
personal self-reflection only. It is not a diagnostic instrument and does not detect,
diagnose, or treat any condition. (Final verbatim WHO notice text is committed in
INSTRUMENTS.md#asrs and must match this definition byte-for-byte.)
```

**WHO-5 — required attribution (rendered verbatim):**

```
WHO-5 Well-Being Index, © World Health Organization. Used for non-commercial purposes
with attribution; not modified for sale. Shown as a 0–100 wellbeing percentage. This is a
wellbeing self-check, not a diagnosis. (Final verbatim WHO attribution is committed in
INSTRUMENTS.md#who-5 and must match this definition byte-for-byte.)
```

**IPIP — status note (rendered verbatim):**

```
Items drawn from the International Personality Item Pool (IPIP), which is in the public
domain. Facet scores are descriptive self-reflection only and are not personality
"verdicts." (Committed in INSTRUMENTS.md#ipip.)
```

New ledger rows to add to [INSTRUMENTS.md](INSTRUMENTS.md) so `ledgerRef` resolves:
`#asrs`, `#ipip`, `#who-5` (WHO-5 already present), and `#daymark-tasks` (self-authored
sustained-attention / n-back / Stroop tasks marked "original, written for Daymark").

---

## 5. Data model — recording, versioning, attribution, sync

### 5.1 New result records (phone DB + snapshot)

Results fold into the existing **versioned `BackupData` JSON snapshot** that already syncs
E2EE (see [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md), Flow A). Proposed **DB
v14** (non-destructive, with a committed schema JSON and a `MigrationTest`, per
[../HANDOFF.md](../HANDOFF.md) conventions; **never** `fallbackToDestructiveMigration`). Two
new aggregates, segregated from the existing `AssessmentResult` (which stays scores-only for
phone check-ins):

```jsonc
// instrument_results (questionnaire engine)
{
  "id": "uuid",
  "instrumentId": "asrs-v1_1-selfreport",
  "instrumentVersion": "1.0.0",        // OUR definition semver
  "sourceVersion": "ASRS v1.1",
  "definitionSha256": "<pinned hash>", // exact definition that produced this result
  "takenAt": 1750000000000,            // epoch millis (TZ owned by the client, per app convention)
  "device": "owner-tablet",            // non-secret label, matches sync metadata convention
  "scales": [ { "scaleId": "asrs_partA", "raw": 17, "bandLabel": "More self-reported signs today" } ],
  // ITEM ANSWERS: stored ONLY where retaining answers is appropriate AND never for any
  // self-harm slot (which does not exist). Default mirrors the app: SCORES ONLY.
  "itemAnswers": null,                 // or a sealed sub-object the owner can opt to keep
  "context": { "notes": "" }
}

// task_results (cognitive harness)
{
  "id": "uuid",
  "taskId": "sustained-attention",
  "taskVersion": "1.0.0",
  "definitionSha256": "<pinned hash>",
  "takenAt": 1750000000000,
  "device": "owner-tablet",
  "seed": "<prng seed>",               // reproducible sequence
  "timingQuality": { "refreshHz": 60, "frameJitterMs": 0.7, "droppedFrames": 2,
                     "flag": "ok|lower-precision" },
  "metrics": {
    "omissions": 3, "commissions": 5, "accuracyPct": 96.2,      // robust, count-based
    "driftByQuartileMs": [398,405,419,427],                     // robust, long-window
    "rtMeanMs": 412,                                            // caveated (same-machine)
    "rtSdMs": 88, "rtCv": 0.21, "rtSdJitterErrorMs": 12,        // caveated + error-barred (§3.4)
    "anticipations": 1
  },
  "contextTags": ["well-rested"]       // optional self-reported confounds
}
```

### 5.2 Versioning & attribution rules

- **Every result is attributed to an exact instrument/task version AND the pinned
  `definitionSha256`.** If a definition changes (even copy), bump `instrumentVersion`; old
  results remain interpretable against the definition that produced them.
- **Append-only.** Results are never silently mutated. History is preserved (matches the
  append-only sync philosophy in [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md)).
- **Backward compatibility.** `BackupData.CURRENT_VERSION` bumps to **14**; older snapshots
  still import (the app already supports older-version import). New tables are additive.
- **Trend computation is pure & local** (lives in the JVM-free `stats/` spirit on the phone;
  in a pure module in the portal): descriptive trends only.

### 5.3 How it syncs (no new server surface)

- Results are **just more rows in the same `BackupData` snapshot**. The phone Sync flavor (or
  the owner browser portal, lower-assurance) encrypts the whole snapshot with the
  Argon2id → XChaCha20-Poly1305 key and `PUT`s an append-only ciphertext blob. **The server
  stores only ciphertext + non-secret metadata** (size, timestamp, hash, version, device
  label). No new endpoints, no new plaintext columns. v1 replication is **single-writer,
  last-snapshot-wins** (per [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md)); results
  sync **symmetrically** with everything else.
- **Browser-computed results never leave plaintext on the server.** Scoring runs in-browser;
  the result is written into the snapshot and re-encrypted before any upload.

---

## 6. Non-diagnostic framing, crisis posture, therapist boundary

### 6.1 Framing (identical to the app)

- Fixed UI banner on every instrument/task intro and result: **"Self-check, not a diagnosis;
  scores are not clinical thresholds."**
- Descriptive result copy only (fixed templates + the person's own numbers). Never "you have
  X," never a risk verdict, never a positive/negative screen, never causation.

### 6.2 Offline crisis-resource posture (identical to the app)

- Crisis resources are **offline, static, user-editable** — same as the app. **No
  auto-escalation, ever.**
- **No self-harm scoring exists** in the engine; the scoring slot is structurally absent
  (mirrors PHQ-9 item-9 never persisting). So no instrument can trip a risk flow. A persistent
  "If you need support now" link surfaces the **static, offline** resource panel by the
  owner's choice — never automatically, never networked.

### 6.3 Boundary vs the therapist portal (these are SELF tools)

- **Default: private.** Instrument and task results live in the owner's local data and sync to
  the owner's **own** devices. The therapist sees **nothing** by default.
- A therapist sees a result **only if** the owner **explicitly seals it into a curated share**
  (see [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md)): a per-share XChaCha20 CEK, sealed to
  the therapist's X25519 key, Ed25519-signed by the owner, MFA-gated, time-boxed, revocable.
- **Shares carry scores/bands only** (the share viewer renders the same non-diagnostic
  disclaimers; bands are never clinical cutoffs). **Free-text answers and raw item answers are
  excluded from shares by default**, and the self-harm slot is structurally absent from every
  share bundle. **Raw task event logs are not shared** — only the metric summary, and only if
  the owner includes it.
- Game plans flow **one way** (therapist → owner, proposed → owner accepts). A therapist
  **cannot** push an instrument, trigger a task, or read results outside an active,
  owner-granted share. These tools are the **owner's**.

> **Honest scope (per [COMPANION_SECURITY.md](COMPANION_SECURITY.md) retractions):** the
> sealed-box share gives confidentiality **without forward secrecy**, and revocation defeats
> only an **honest** server. Nothing here changes that; these result types ride the same
> share machinery and inherit the same honest limits.

---

## 7. Security as a first-class feature (UI/UX) + cross-surface continuity

Security is visible and reassuring, not scary — rendered in the modern-paper language. See
[COMPANION_SECURITY.md](COMPANION_SECURITY.md) for the full threat model.

### 7.1 Strict-CSP, vendored portal (enforced, shown)

```
Content-Security-Policy:
  default-src 'self';
  script-src 'self' 'wasm-unsafe-eval';   /* libsodium-WASM, vendored; no remote */
  style-src 'self';
  font-src 'self';                         /* Fraunces + Inter, vendored OFL */
  img-src 'self' data:;
  connect-src 'self';
  frame-ancestors 'none'; base-uri 'none'; form-action 'self';
  object-src 'none';
```

- **No CDNs, no Google Fonts, no analytics, no third-party origins.** A "Security" panel in
  the portal lists, verifiably: assets are local, no outbound calls, CSP active.
- **Honest browser-portal limit** (per [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §9.4):
  because the **same origin serves both HTML and assets**, SRI/CSP are inert against a
  malicious operator — the browser portal is **lower-assurance**. The UI **discourages
  entering the master passphrase in the browser** and points to the native **Sync flavor** as
  the only secret-handling owner path. The Security panel states this boundary plainly.

### 7.2 Modern-paper design tokens (web mirror of the app)

```css
:root{
  /* light "paper" */
  --paper:#F4EFE6; --surface:#FCFAF5; --ink:#2A2722; --soft:#6B655B;
  --faint:#A49C8E; --hairline:#E7DFD1; --accent:#33302A;
  --radius:12px;
  --font-serif: "Fraunces", Georgia, serif;   /* vendored OFL, local @font-face */
  --font-sans:  "Inter", system-ui, sans-serif;
}
@media (prefers-color-scheme: dark){
  :root{ --paper:#1B1A17; --surface:#24221D; --ink:#EBE5D8; --hairline:#34312A;
         --accent:#EBE5D8; }
}
.paper-surface{                 /* the signature container */
  background:var(--surface); border:1px solid var(--hairline);
  border-radius:var(--radius); box-shadow:0 1px 0 rgba(0,0,0,.03);
}
```

- Flat surfaces, hairline rules, serif journal headings, sans body/numbers — the same warm,
  sleek identity as the phone (see [COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md)).
  Original hand-drawn glyphs only (no emoji, no icon packs).
- **Purposeful directional motion** (~240 ms shared-axis) between sheets, matching the app.
- **Focus-visible** rings everywhere (sit-down = keyboard-first); WCAG-AA contrast;
  large-font layouts; full keyboard operability; reduced-motion honored for the timed-task
  practice animations — but **task stimulus timing is preserved** (an accessibility note is
  shown, since the stimulus cadence is the measurement).

### 7.3 Cross-surface UX (phone ↔ Companion continuity)

See [COMPANION_UX.md](COMPANION_UX.md) for the full flows.

- **Symmetric sync, one identity.** A result taken on the Companion appears on the phone's
  Check-ins / history (scores/bands) after the next snapshot sync, and vice-versa — same data,
  two surfaces.
- **"Continue on the big screen" affordance:** the phone Check-ins screen notes that longer
  instruments and focus tasks live on the Companion (only if the owner has the Sync flavor + a
  paired Companion). The phone never gains the tasks themselves.
- **Consistent framing copy** across phone, Companion, and (if shared) the therapist viewer —
  the same fixed non-diagnostic banners, so the boundary reads identically everywhere.
- **Trends** use the same descriptive, association-not-causation voice as the app's Insights.

---

## 8. Extensibility & keeping it honest (maintainer playbook)

1. **Add an instrument with data, not code.** Drop a definition JSON (§2.5) + a ledger row in
   the Companion [INSTRUMENTS.md](INSTRUMENTS.md). The generic runner interprets it. No
   per-instrument logic.
2. **CI gate (the honesty enforcement):**
   - schema-validate every definition;
   - assert `nonDiagnostic === true`;
   - assert `noScreeningFlag === true` and **reject any cutoff-aligned positive/negative
     flag** (no reconstructed clinical screen);
   - assert `ledgerRef` resolves to a real [INSTRUMENTS.md](INSTRUMENTS.md) row;
   - assert `noticeText` matches the committed **verbatim** source notice byte-for-byte;
   - reject any forbidden id (TOVA / Conners / CAARS / the licensed list);
   - assert **no self-harm slot** anywhere;
   - verify `integrity.sha256` over canonical JSON;
   - assert scoring references valid items and ordered, non-overlapping bands.
   A failure is a **red build** — a dishonest, licensed, or cutoff-emitting instrument cannot
   ship.
3. **Versioning discipline.** Any change to a definition bumps `instrumentVersion` and the
   pinned hash; results stay attributable to the exact version that produced them.
4. **License ledger is law.** The Companion [INSTRUMENTS.md](INSTRUMENTS.md) mirrors the
   app's, adds the ASRS / IPIP / WHO-5 + self-authored rows (and their verbatim notices), and
   keeps the forbidden table. Never alter validated instrument wording; only bundle
   public-domain / open / self-authored content. Self-authored tasks state "original, written
   for Daymark."
5. **"Self-check, not a diagnosis" is non-negotiable.** It is fixed UI on every surface, not
   data-supplied, and cannot be turned off by a definition.
6. **Original tasks stay original.** New timed tasks must be self-authored (our stimuli,
   timings, copy) and pass a "does this reproduce a licensed battery?" review documented in
   the ledger. No norms, no cutoffs, no diagnostic claims — ever.

---

## 9. Build sequencing (suggested)

1. **Questionnaire engine** (declarative runner + scoring/banding + WHO-5 / ASRS / IPIP
   definitions with verbatim notices) — pure, testable, no new server surface; results fold
   into snapshot v14.
2. **Sustained-attention anchor task** (timing harness, calibration, fullscreen/focus guard,
   timing-quality metadata, RT-variability error bar).
3. **n-back- and Stroop-style tasks** on the same harness.
4. **Cross-surface trends + therapist-share inclusion toggles** (scores/bands only, opt-in).

All four are independently shippable, honest in isolation, and never touch the phone's
no-network promise or the server's zero-knowledge guarantee.

---

## See also

- [COMPANION_README.md](COMPANION_README.md) — documentation index and the three pillars.
- [COMPANION_SCOPE.md](COMPANION_SCOPE.md) — in/out of scope; this track's additions live here.
- [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md) — snapshot flow, DB versioning, single-writer sync.
- [COMPANION_SECURITY.md](COMPANION_SECURITY.md) — threat model, CSP, browser-portal honest limit (§9.4).
- [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) — share lifecycle these results ride on.
- [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) — how the portal is served and locked down.
- [COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md) — tokens, type, glyphs, motion.
- [COMPANION_UX.md](COMPANION_UX.md) — cross-surface flows and copy.
- [INSTRUMENTS.md](INSTRUMENTS.md) — the authoritative license ledger this catalog mirrors.
- [../HANDOFF.md](../HANDOFF.md) — the non-diagnostic prime directive (§0).
