# Daymark Companion — the self-check engine

The owner page's "Self-checks, on this device" runs sit-down self-checks, guided exercises and one
original focus task, Steady Attention, in the browser. Everything is computed on the device and
nothing is uploaded. A new instrument is data — a definition and a ledger row — never code, and a
build-failing honesty gate decides whether a definition may load at all.

What the catalogue holds, and under what licence: [companion/INSTRUMENTS.md](../companion/INSTRUMENTS.md).
How every tool declares what it clinically is: [PROVENANCE.md](PROVENANCE.md).

Code: `companion/web/src/lib/instruments/` (types, predicate, scoring, validate, catalog, builder),
`companion/web/src/lib/tasks/attention.ts`, and the components `QuestionnaireRunner.svelte`,
`AttentionTask.svelte`, `Assessments.svelte` and `ToolBuilder.svelte`.

**Results stay on this device.** A result can be downloaded as JSON and nothing more: it is not saved
into the encrypted snapshot, so it neither syncs nor reaches a clinician. Not built: #237.

## 0. Rules this engine keeps

A definition, task or screen that breaks one of these does not ship.

1. **Non-diagnostic, always.** No verdicts, no risk scores, no diagnosis, no positive or negative
   screen. Every band carries a framing sentence that must say it is "not a diagnosis", "not a
   screen" or "not a clinical" threshold, and a self-authored tool's introduction must say so too;
   the validator rejects a definition that does not.
2. **No AI, no generated content.** Every word is fixed, human-written text with the person's own
   numbers slotted in. Scoring is deterministic arithmetic over a declarative definition.
3. **The server sees nothing.** Everything is computed in the browser. When results are saved into
   the snapshot (#237) they will reach the server only as ciphertext.
4. **Vendored and same-origin.** No CDN, no web-font service, no analytics, no third-party origin;
   the served Content-Security-Policy is quoted in [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §6.
   The timed task uses only `requestAnimationFrame`, `performance.now()` and the DOM already on the
   page.
5. **Licence-clean only.** Public-domain, openly licensed, or self-authored. The licensed batteries
   and the permission-required instruments in §4.2 are rejected by name and by item text.
6. **These are the owner's own tools.** Results belong to the owner. A clinician may assign a
   catalogue item (COMPANION_ASSIGNMENTS.md), and sees a result only if the owner puts it in a share,
   as scores and bands only.

## 2. The questionnaire engine

A definition is a data object (`instruments/types.ts`) and the runner is a generic interpreter of it.
Item types: `info`, `likert`, `singleSelect`, `multiSelect`, `slider`, `numeric`, `freeText`. A
definition with no scales is a **guided exercise**: unscored, with every writing item excluded from
scoring, and labelled as an exercise rather than a questionnaire.

### 2.3 Branching (declarative)

An item may carry a `visibleWhen` predicate over answers already given. Predicates are a small,
pure, sandboxed language — **no `eval`, no executable code in a definition** — so a definition can
never run code in the page:

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

Operators: `eq, ne, gt, gte, lt, lte, in, includes`. A hidden item is skipped and contributes nothing.
A reference to an answer that does not exist fails closed for every operator, and a predicate is
bounded in depth (16) and size (256 nodes) at validation and evaluation alike
(COMPANION_DIALOGUE.md, Findings 1 and 2).

### 2.4 Scoring and bands (declarative)

Scoring is data, run by a deterministic evaluator. Methods: `sum`, `mean`, `percent_of_max`. Each
scale lists its items, any reversed items, ordered bands with descriptive labels, and the framing
sentence:

```jsonc
"scoring": {
  "scales": [
    {
      "id": "wellbeing",
      "method": "sum",
      "items": ["q1", "q2", "q3", "q4", "q5"],
      "reverse": [],
      "bands": [
        { "max": 11, "label": "…a descriptive label of our own…", "tone": "neutral" },
        { "min": 12, "label": "…another…", "tone": "neutral" }
      ],
      "bandFraming": "These bands describe your own answers today. They are not a diagnosis, not a screen, and not a clinical threshold."
    }
  ]
}
```

- **No clinical cutoff is presented as clinical.** Bands are our own descriptive labels at our own
  informal boundaries, never a source instrument's published cutoff, and a band label using screening
  language ("screen positive", "meets criteria", "you likely have", …) is rejected. There is no
  count-threshold method and no screening flag.
- **Bands are ordered and do not overlap,** and a sum scale's bands leave no gap, so every score has a
  label.
- **Branching cannot strand a score.** For `sum` and `percent_of_max`, a scored item may not be
  conditionally hidden, because hiding it would deflate the total and shift the band; `mean` tolerates
  it.
- **Self-harm is structurally absent.** The engine has no self-harm slot, and any item whose id,
  prompt, body or option text refers to self-harm or suicide is rejected, so no instrument can trip a
  risk flow.

### 2.5 The definition, and the honesty gate

```jsonc
{
  "instrumentId": "wellbeing-selfcheck",
  "instrumentVersion": "1.0.0",            // semver of OUR definition
  "title": "…",
  "license": "…",
  "ledgerRef": "INSTRUMENTS.md#wellbeing-selfcheck",
  "provenance": { "tier": "custom" },       // validated | adapted | custom
  "nonDiagnostic": true,                    // must be exactly true
  "noScreeningFlag": true,                  // must be exactly true
  "estimatedMinutes": 2,
  "items": [ /* §2 */ ],
  "scoring": { /* §2.4 */ },
  "framing": { "intro": "This is a self-check, not a diagnosis…", "crisisPosture": "offline-static" }
}
```

`validateDefinition()` refuses to load a definition, and `instruments.test.ts` fails the build, when:

- `nonDiagnostic` or `noScreeningFlag` is not exactly `true`, or the licence or introduction is missing;
- the provenance tier is missing or unknown; a `validated` tool names no source; an `adapted` tool
  names no method it draws on; a `custom` tool's introduction carries no non-diagnostic disclaimer;
- a `validated` or `adapted` tool has no `ledgerRef`, or any `ledgerRef` is malformed or names an
  anchor that `companion/INSTRUMENTS.md` does not contain;
- the identity or any item text names a forbidden source (§4.2);
- any item refers to self-harm;
- a scale references a missing item, reverses a non-member, hides a scored item, has no framing or a
  framing without the disclaimer, or has bands that overlap, leave a gap, or use screening language;
- a predicate exceeds its bounds.

A definition failing any check does not load. The tool builder (`ToolBuilder.svelte`, "Build a
self-check of your own") compiles a draft through the same gate. Publishing a built tool to the
catalogue or assigning it is not built: its "Publish" downloads JSON (#255).

## 3. Timed tasks

Tasks are original Daymark tasks with our own stimuli, timings and copy — not reproductions of TOVA,
Conners, CAARS or any licensed battery — and they report descriptive summaries, never a diagnosis.
They exist only here, never on the phone (COMPANION_ARCHITECTURE.md §8).

**Steady Attention** (`tasks/attention.ts`) is the one task built: 40 trials of about 1.75 seconds;
a stimulus shown for 250 ms with a 1500 ms interval jittered by ±250 ms; three in four trials are the
target (●), to be answered with Space or a tap, and the rest (■) are to be left alone. It reports
omissions, commissions, accuracy, and a reaction-time mean, plus a variability figure under the
conditions in §3.4. Working-memory (n-back-style) and interference (Stroop-style) tasks are not
built: #240.

### 3.2 Browser timing: honest about its own precision

A browser is not a laboratory clock. The rules, and where each stands:

1. **Clock.** Every latency uses `performance.now()`, never `Date.now()`. Built.
2. **Auto-repeat is ignored,** so a held key is one response, not many. Built.
3. **Measure and disclose the clock.** The frame interval is sampled throughout the run; the result
   records the refresh interval, the frame-jitter spread and dropped frames, and a run is flagged
   **lower-precision** when jitter exceeds 4 ms, more than three frames drop, or too few frames were
   seen to judge. The screen says so in words. Built.
4. **The latency floor.** Display, operating system and keyboard add a roughly constant offset, so
   within-person, within-session counts stay meaningful and absolute reaction time across machines
   is not comparable; the result says so. Built as the result's own caveat.
5. **Onset and input timestamps.** Stimulus onset taken from the frame that actually paints it, and
   the key press from the event's own `timeStamp`, to avoid main-thread skew. Not built: #242.
6. **No background contention.** Full screen, and a run invalidated when the tab loses focus. Not
   built: #242.
7. **Reproducible runs.** The trial sequence drawn from a recorded seed, and a practice block first.
   Not built: the sequence uses `Math.random` (#242).

### 3.4 Keeping results meaningful but not diagnostic

A roughly constant offset cancels in a mean but **adds variance** to a spread, so reaction-time
variability is exactly the figure a jittery clock manufactures. Therefore:

- The trusted signals are the **count-based** ones: omissions, commissions, accuracy.
- Variability is shown only on a run that is not lower-precision and has at least three hits, and
  always with its error bar: "± the clock's measured jitter".
- The reaction-time mean carries the within-session, same-machine caveat.
- **Within-person only.** No norms, no percentiles against other people, no cutoffs. Result text is a
  fixed template: counts, a mean, a caveat.

Comparing a run with the same person's earlier runs, naming practice effects on early runs, and
optional context notes (sleep, caffeine, time of day) all need results that are kept: #237.

## 4. The catalogue

The catalogue is four self-authored tools — two scored self-checks and two guided exercises — and
Steady Attention. The ledger with each one's licence and anchor is
[companion/INSTRUMENTS.md](../companion/INSTRUMENTS.md). The phone keeps its own check-ins (PHQ-9,
GAD-7, WHO-5; see [INSTRUMENTS.md](INSTRUMENTS.md)). The Companion's catalogue stays self-written:
it adds no published questionnaire, even one that is free with attribution (#264).

### 4.2 Explicitly excluded (never add)

| Instrument | Why excluded |
|---|---|
| **TOVA** | Proprietary, licensed attention battery |
| **Conners (CRS, CPT)** | Copyrighted, licensed |
| **CAARS** | Copyrighted, licensed |
| ISI, ESS, STOP-Bang, PSQI, PANAS, WEMWBS, DASS-21, PSS, AAQ-II / VLQ / Bull's-Eye | Copyright or permission required; the app excludes them for the same reason ([INSTRUMENTS.md](INSTRUMENTS.md)) |

The validator's `FORBIDDEN_SOURCES` list rejects every one of these, by identity and by item text,
with word-boundary matching so ordinary words do not trip it.

## 8. Adding a tool honestly (maintainer playbook)

1. **Add an instrument with data, not code.** A definition (§2.5) in `instruments/catalog/`, and a
   row with an `id="…"` anchor in `companion/INSTRUMENTS.md`. The runner interprets it; there is no
   per-instrument logic.
2. **The honesty gate is the enforcement.** §2.5's checks run at load and in CI. A dishonest,
   licensed, or cutoff-emitting definition is a red build, not a review comment.
3. **Version discipline.** Any change to a definition, even to its wording, bumps
   `instrumentVersion`, so a result can always be attributed to the definition that produced it.
4. **The ledger is law.** Only public-domain, openly licensed or self-authored content; never alter
   validated wording; self-authored tools say so. The catalogue itself is self-written, so it adds
   no published instrument and no verbatim notice to show (#264).
5. **"Self-check, not a diagnosis" cannot be switched off** by a definition: the validator requires
   the framing, and the screens around the runner are fixed text.
6. **Original tasks stay original.** A new timed task is self-authored — our stimuli, timings and
   copy — and passes a "does this reproduce a licensed battery?" review recorded in the ledger. No
   norms, no cutoffs, no diagnostic claims.
