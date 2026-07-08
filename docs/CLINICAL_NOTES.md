# Daymark Companion — Clinical Notes & Assessment Summary

> ## ⚠️ STATUS: DESIGN — DIRECTION LOCKED, NOT YET IMPLEMENTED
>
> Clinician notes are **net‑new** and are **PHI**. This document records the
> decided model. It builds on the existing game‑plan / assignment channel and the
> provenance‑labeled assessment engine
> ([COMPANION_ASSIGNMENTS.md](./COMPANION_ASSIGNMENTS.md),
> [PROVENANCE.md](./PROVENANCE.md)) and inherits the access‑control rules in
> [COMPANION_ACCESS_CONTROL.md](./COMPANION_ACCESS_CONTROL.md).

---

## Contents

- [What exists today](#what-exists-today)
- [The notes model: open by default + a private carve‑out](#the-notes-model-open-by-default--a-private-carve-out)
- [Assessment summary (not "diagnosis")](#assessment-summary-not-diagnosis)
- [Crypto, visibility & audit](#crypto-visibility--audit)
- [Integrity: amend, don't erase](#integrity-amend-dont-erase)
- [Open questions](#open-questions)

---

## What exists today

- **Patient‑authored:** journal entries, CBT thought records — on‑device.
- **Clinician‑authored:** **game plans** (free‑text, sealed + signed) and
  **assignments** over the per‑relationship channel.
- **Assessment results:** scores + bands only (never raw item answers).

There is **no formal clinician progress‑notes surface**, and — deliberately — no
"diagnosis" page. Both are addressed here.

## The notes model: open by default + a private carve‑out

**Decision:** clinician notes are **patient‑visible by default**, with a
separate, protected **process‑notes** type for a clinician's own working
thoughts.

- **Progress notes (open).** The default. Visible to the patient. This fits
  Daymark's transparency ethos (the patient already sees the audit log and
  controls consent) and aligns with current US norms, where patients are
  generally entitled to their clinical notes (the "Cures Act" open‑notes
  direction). *(Not legal advice — confirm at the compliance gate.)*
- **Process notes (private carve‑out).** A distinct, separately‑protected note
  type for the clinician's raw working notes — modeled on HIPAA's special
  **"psychotherapy notes"** category, which is kept apart from the main record.
  Clinician‑only by default, **separately keyed**, and **excluded** from routine
  shares/exports.

The UI must make the type unmistakable at authoring time — a clinician always
knows, before they type, whether this note the patient will read.

## Assessment summary (not "diagnosis")

The clinical surface a provider shares is an **assessment summary**, not a
diagnosis:

- Shows results as **scores + bands**, each carrying its **provenance badge**
  (✅ / ◐ / ✎ from [PROVENANCE.md](./PROVENANCE.md)).
- A **credentialed role** (psychologist/psychiatrist) may publish and interpret
  **Validated/Adapted** instruments; a custom tool's score is always shown with
  its "not clinical" label and never a cutoff or an "all‑clear."
- The summary is what gets shared in a referral / cross‑provider handoff (subject
  to the patient's grant — see
  [COMPANION_ACCESS_CONTROL.md](./COMPANION_ACCESS_CONTROL.md#cross-provider-sharing--referrals)).

## Crypto, visibility & audit

Notes are PHI and get the same E2E treatment as everything else:

- **Open progress notes** — encrypted and readable by the **patient** and the
  **authoring clinician** (and any care‑team member the patient granted). Sealed
  to recipient keys, **signed** by the author.
- **Private process notes** — encrypted to the **authoring clinician only**,
  under a separate key; not included when a client's record is shared or when a
  patient exports their data.
- **Every read is audited** in the patient‑readable, hash‑chained log — including
  who opened a note and when (open notes; the patient does not see the *content*
  of another clinician's private process notes, but the platform still records
  access events at the metadata level).
- Notes never phone home; they live in the zero‑knowledge blob store like all
  other content.

## Integrity: amend, don't erase

Clinical records should be tamper‑evident and historically honest:

- Notes are **append‑only / amendable**, not silently editable — an edit creates
  an **amendment** with its own timestamp and signature; the prior version is
  retained.
- This supports the HIPAA **integrity** safeguard and makes the audit trail
  meaningful.
- Deletion, where allowed at all, is a **tombstone** (recorded), not a true
  erase — subject to the practice's retention policy and any legal‑hold rules
  (an org/compliance decision, not a silent default).

## Open questions

- Can a patient **annotate / respond to** an open note (a two‑way record)?
- Minors / guardianship: who is "the patient" (key holder) when the client is a
  minor, and how does open‑notes visibility apply?
- Retention & legal hold: defaults, and who controls them per org.
- Does a private process note ever become disclosable (e.g., patient request,
  legal process), and how is that handled without breaking the separate‑key
  model?
