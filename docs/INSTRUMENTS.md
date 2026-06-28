# Instruments & methods — license ledger

Daymark is a **clean-room, license-clean** project. This page is the authoritative record of which
standardized questionnaires we **bundle**, which we deliberately **do not** bundle, and why the
self-help **methods** in the app are free to implement in our own words.

> **Non-diagnostic.** The bundled check-ins are wellbeing self-checks, not clinical assessments.
> They do not detect, diagnose, or treat any condition. See [PRIVACY.md](PRIVACY.md) — the app
> stores **scores only**, never the individual item answers.

## Bundled questionnaires (Check-ins)

These three are reproduced with their **exact wording** in the app because they are free to use.
The relevant citation/attribution is shown **in-app** on each check-in screen.

| Instrument | Measures | License / status | Notes |
|---|---|---|---|
| **PHQ-9** | Depressive symptoms | **Free to reproduce** (developed by Drs. Spitzer, Williams, Kroenke and colleagues; funded by Pfizer) — no permission required | If the self-harm item (item 9) is non-zero, the app surfaces offline crisis resources. Score + band stored only; item 9 is never persisted. |
| **GAD-7** | Anxiety symptoms | **Free to reproduce** (same authorship; funded by Pfizer) — no permission required | Score + band stored only. |
| **WHO-5** | General wellbeing | **© World Health Organization.** Free to use for **non-commercial** purposes with attribution; not to be modified or sold | Shown as a 0–100 percentage; WHO attribution cited in-app. |

## NOT bundled (licensed or permission required)

These instruments are **intentionally absent**. They are copyrighted and/or require a license or
permission to reproduce, which does not fit a free, open-source app. A code comment in the
assessment module forbids adding them.

| Instrument | Domain | Why not bundled |
|---|---|---|
| **ISI** (Insomnia Severity Index) | Sleep / insomnia | Copyrighted; permission/license required. |
| **Epworth Sleepiness Scale (ESS)** | Daytime sleepiness | Copyrighted; license required for use/reproduction. |
| **STOP-Bang** | Sleep-apnea risk screen | Copyrighted by the owner; permission required. |
| **PSQI** (Pittsburgh Sleep Quality Index) | Sleep quality | Copyrighted (University of Pittsburgh); license required. |
| **PANAS** | Positive/negative affect | Copyrighted; permission required. |
| **WEMWBS** | Mental wellbeing | Copyrighted; registration/permission required. |
| **DASS-21** | Depression/anxiety/stress | Copyrighted; permission terms apply. |
| **PSS** (Perceived Stress Scale) | Perceived stress | Copyrighted; permission terms apply. |
| **ACT measures** — AAQ-II, VLQ, Bull's-Eye | Acceptance & values | Copyrighted by their authors; permission required. |

Daymark's own **sleep self-checks** (apnea-style signs, restless legs, insomnia signs) are
**original questionnaires written for the app** — they are not, and do not reproduce, any of the
instruments above.

## Methods & ideas (not copyrightable) — implemented in our own words

The self-help techniques in Daymark are general, well-established **methods**. Methods, processes,
and ideas are not protected by copyright; only a specific expression of them is. Every prompt,
instruction, and label below is **authored by us**, not copied from any book, worksheet, or app.

- **CBT thought records** — the situation → thought → evidence → balanced-thought structure is a
  standard method. Our prompts and labels are our own.
- **Cognitive distortions ("thinking traps")** — the list of traps is **self-authored**: our own
  names and definitions, not copied from any copyrighted checklist.
- **Gratitude / "Three Good Things"** — a well-known exercise; our wording is original.
- **Expressive Writing** — a timed free-writing method; our prompt and the supportive note are
  original.
- **Behavioral activation ("Do one thing")** — a standard skill; the activity suggestions and
  enjoyment/mastery ratings are our own.
- **Implementation intentions ("when X, I will Y")** — a general planning technique; our UI wording
  is original.
- **Breathing protocols** — slow ~6/min, box (4·4·4·4), and 4·7·8 are numeric cadences, not
  proprietary content. Described generically, with no brand names and no health claims.
- **Yoga / stretch & bodyweight routines (Move)** — built from public-domain sequences and generic
  bodyweight intervals, with all instructions in **our own words** and **original hand-drawn pose
  figures** (no branded programs, no third-party images).

## See also

- [PRIVACY.md](PRIVACY.md) — what is stored locally (scores only) and the no-`INTERNET` posture.
- [FEATURES.md](FEATURES.md) — what each feature does.
