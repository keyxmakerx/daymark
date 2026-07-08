# Daymark — Tool Provenance & Clinical Labeling

> ## ⚠️ STATUS: DESIGN — DIRECTION LOCKED, NOT YET IMPLEMENTED
>
> The **decision** to label every tool by provenance is settled
> ([PRODUCT_DIRECTION.md](./PRODUCT_DIRECTION.md)). This document specs the
> system. Roughly 90% of the enforcement machinery already exists — the
> load‑time + CI **instrument honesty gate** and the `INSTRUMENTS.md` license
> ledger (see [COMPANION_FEATURES.md](./COMPANION_FEATURES.md),
> [INSTRUMENTS.md](./INSTRUMENTS.md)). This formalizes and extends them.

---

## Contents

- [Why provenance](#why-provenance)
- [The three tiers](#the-three-tiers)
- [The disclaimer](#the-disclaimer)
- [Rules (enforced)](#rules-enforced)
- [The provenance field (schema)](#the-provenance-field-schema)
- [How it renders](#how-it-renders)
- [Who may publish what](#who-may-publish-what)
- [Relationship to the honesty gate & the ledger](#relationship-to-the-honesty-gate--the-ledger)
- [Open questions](#open-questions)

---

## Why provenance

Daymark should be *clinically useful without pretending to be a clinician*. The
resolution is **not** "make everything non‑diagnostic" and **not** "let anyone
publish anything." It is: **every authorable tool declares what it clinically is,
and the label — not the author — carries the trust.** A credentialed clinician
can publish a validated instrument; a user (including the maintainer) can author
a custom exercise; and the patient always sees, up front, which one they're
using.

"Tool" here means any authorable, patient‑facing item: a **questionnaire**, a
**task**, an **exercise**, a **guided flow**, or an **assignment**. (The word
"agent" has been used informally for guided flows — note Daymark is deliberately
**no‑AI / no‑generated‑text**, so these are deterministic scripted flows, not AI
agents.)

## The three tiers

| Tier | Badge | Meaning | Example |
|---|---|---|---|
| **Validated** | ✅ | A real, published instrument used **faithfully** — exact wording, scoring, and banding. Carries a citation + license. | PHQ‑9, GAD‑7, WHO‑5 |
| **Adapted** | ◐ | Built on an evidence‑based **method** but modified (shortened, reworded, recombined). Names the method it draws from. | a shortened sleep‑hygiene check |
| **Custom / non‑clinical** | ✎ | Self‑authored. Not validated, not clinical. Always shows the disclaimer. | a personal "what helped today?" prompt |

## The disclaimer

Every **Custom** tool opens with, verbatim and non‑dismissable‑until‑seen:

> **Custom‑made — a personal reflection tool, not a validated or clinical
> instrument. Not for diagnosis.**

**Adapted** tools show a lighter note: *"Adapted from &lt;method&gt; — not the
original validated instrument."* **Validated** tools show their **source +
license** instead of a warning.

## Rules (enforced)

Enforced at authoring, at content load‑time, and in CI (extending the existing
honesty gate):

1. **Provenance is required.** No tool publishes without a `provenance` value.
   Missing/invalid provenance = hard failure in CI and refuse‑to‑render at load.
2. **Custom tools cannot pose as clinical.** They may **not** declare clinical
   cutoffs, may **not** render an "all‑clear" or a diagnostic label, and **must**
   present the disclaimer before the first item.
3. **Validated means faithful.** A tool tagged Validated must match the
   registered instrument's items/scoring/banding exactly (verified against the
   catalog), and must have a ledger entry with a license that permits the use.
   Drift from the canonical form downgrades it to Adapted.
4. **No self‑harm item slot** in any tier's shareable output — carried over from
   the existing gate.
5. **The label is immutable per version.** Editing a Validated/Adapted tool in a
   way that changes wording/scoring forces a re‑classification.

## The provenance field (schema)

A sketch, to be finalized against the instrument‑definition format in
[COMPANION_FEATURES.md](./COMPANION_FEATURES.md):

```jsonc
"provenance": {
  "tier": "validated | adapted | custom",   // required
  "source": "Kroenke et al., 2001 (PHQ-9)", // required for validated/adapted
  "license": "Pfizer — free to use",        // required for validated
  "basedOn": "behavioral activation",       // required for adapted
  "authorRole": "psychologist",             // publisher's role at publish time
  "disclaimerVersion": 1                     // which disclaimer copy was shown
}
```

## How it renders

- **Patient:** a small badge on the tool's start screen (✅ / ◐ / ✎) and, for
  Custom, the full disclaimer card before item one. Consistent with the "modern
  paper" design system ([DESIGN.md](./DESIGN.md),
  [COMPANION_DESIGN_SYSTEM.md](./COMPANION_DESIGN_SYSTEM.md)).
- **Clinician:** the same badge in the assignment/builder list and in the
  assessment summary, so a clinician always knows whether a score came from a
  validated instrument or a custom one before acting on it.
- **In shares/exports:** the badge travels with the result; a PDF or dashboard
  never shows a custom‑tool score without its label.

## Who may publish what

Ties to the role model ([COMPANION_ACCESS_CONTROL.md](./COMPANION_ACCESS_CONTROL.md)):

- **Validated / Adapted** — only credentialed clinical roles (e.g. psychologist,
  psychiatrist) may publish these, and only from/against the vetted catalog.
- **Custom** — any authoring user (including the personal‑app owner) may publish,
  always labeled and disclaimed.

This is the concrete answer to "a rando like me shouldn't be handing out clinical
screeners": you *can't* — you can only publish **Custom**, which says so plainly.

## Relationship to the honesty gate & the ledger

This does not replace the existing controls — it **unifies** them:

- The **honesty gate** (non‑diagnostic wording, no self‑harm slot, no cutoffs,
  license‑clean) becomes the enforcement engine for the rules above.
- The **`INSTRUMENTS.md` ledger** becomes the source of truth for what may be
  tagged **Validated** and under what license.
- The **builder** (Phase 2) writes the `provenance` field at authoring time so
  the gate can check it before publish.

## Open questions

- Do we need a fourth tier for **clinician‑authored‑but‑local** tools that a
  specific practice validates internally?
- How is a Custom tool's result treated in aggregate insights — flagged, or
  excluded from any "clinical‑looking" rollup?
- Versioning/audit of provenance changes over a tool's lifetime.
