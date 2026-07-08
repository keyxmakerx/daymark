# Daymark — Product Direction

> ## ⚠️ STATUS: DIRECTION LOCKED (2026‑07) — IMPLEMENTATION PHASED & MOSTLY NET‑NEW
>
> The **decisions** in this document are settled and are the north star we plan
> against. The **implementation** is largely unbuilt and sequenced across phases
> (see [Roadmap](#roadmap)). The existing personal app and the Companion portal
> are the **foundation** — this is an *expansion*, not a rewrite.
>
> **This document supersedes** the "explicitly out of scope" exclusions in
> [COMPANION_SCOPE.md](./COMPANION_SCOPE.md) that ruled out **messaging,
> scheduling, multi‑client / multi‑tenant, and care teams**. Those are now *in
> scope*, phased. Where the two conflict, this document wins. The zero‑knowledge,
> patient‑owns‑keys, non‑diagnostic‑by‑default principles are **retained**.

---

## Contents

- [Purpose](#purpose)
- [The two locked decisions](#the-two-locked-decisions)
- [The fork we crossed (be honest)](#the-fork-we-crossed-be-honest)
- [Guiding principles](#guiding-principles)
- [Expand, don't rewrite](#expand-dont-rewrite)
- [Roadmap](#roadmap)
- [The compliance gate](#the-compliance-gate-non-negotiable)
- [Open questions](#open-questions)
- [Related documents](#related-documents)

---

## Purpose

Daymark today is a **private, offline‑first personal companion** for mood and
mental‑health self‑tracking, with an *optional* self‑hosted Companion that lets a
person share a curated, encrypted view with **one** therapist. This document
records the decision to grow Daymark into a **multi‑client clinical platform** —
a tool a whole practice can run — **without** giving up the properties that make
it worth trusting.

## The two locked decisions

1. **Direction: clinical platform, phased.** The personal app and the existing
   Companion are the foundation. Multi‑role access, multi‑client care teams, and
   HIPAA‑readiness are a **layer on top**, built in phase order. Not a rewrite.

2. **Clinical honesty: per‑tool provenance labeling.** Instead of the whole
   product being "diagnostic" or "non‑diagnostic," **every tool declares what it
   is** — Validated, Adapted, or Custom/non‑clinical — and custom tools open with
   a plain disclaimer. This lets credentialed clinicians publish validated
   instruments *and* lets anyone author custom tools, with **nobody ever misled
   about which is which**. See [PROVENANCE.md](./PROVENANCE.md).

## The fork we crossed (be honest)

The prior design deliberately stayed *out* of the regulated clinical world:
single therapist, non‑diagnostic, no messaging/scheduling/EHR, no multi‑tenant,
no key escrow. Going "clinical platform" reverses several of those on purpose.
That is a real repositioning, and it comes with weight — most of all a
regulatory burden (see the [gate](#the-compliance-gate-non-negotiable)) and a
bigger threat model. We do it **eyes open**, and we keep the principles below as
the guardrails that make it defensible.

## Guiding principles

These are non‑negotiable. Every feature is judged against them.

1. **The patient owns the keys.** Zero‑knowledge stays the point — the server
   (and therefore we) cannot read plaintext.
2. **Roles gate *actions*; crypto gates *reading*.** A role lets you *do* things
   the server permits; only a **grant/key** lets you *read* content. Keep the two
   layers independent, so a hijacked admin account still can't read patient data.
3. **Minimum necessary.** Every role gets the smallest grant that does its job.
4. **Consent roots at the patient.** Orgs and roles decide who *may request*
   access; the patient (or a consent they signed) is what *authorizes* it.
5. **Provenance, not authority.** A tool is trusted because it is *labeled*
   honestly, not because of who published it.
6. **Usability is a security property.** Security so painful it's bypassed
   (shared logins, keys on sticky notes) is worse security.
7. **HIPAA‑ready ≠ "compliant."** Software provides safeguards; a *deployment +
   an organization* is what's compliant.

## Expand, don't rewrite

A full code + docs inventory (2026‑07) found far more already built than the
brainstorm assumed. Summary — full map in the phase docs:

| Area | Built | Extend | New |
|---|---|---|---|
| Patient app | mood, journal, insights, goals, trackers, widget, reminders, app‑lock, PDF, **behavioral activation, CBT thought records, breathing pacer, sleep diary + screeners, gentle‑support + 988** | Home redesign + Signals‑as‑router; EMA sampling; structured safety‑plan | HRV, passive sensing, relapse early‑warning, values/ACT, iOS |
| Provenance & content | honesty gate, instrument catalog, `INSTRUMENTS.md` ledger | provenance field + badges + disclaimers | no‑code builder |
| Portal | ZK sync, pairing, capability grants, assignments + inbox, game plans, audit log, TOTP, token recovery, hardened Docker | async notes, PDF from dashboard | care‑team / multi‑client, scheduling + front‑desk |
| Security / clinical layer | three‑party model, consent via grants, expiring shares, hash‑chained audit | true‑revocation key rotation, user‑held key recovery, finish WebAuthn, duress lock | multi‑role RBAC, behavioral guard (IDS), org‑consent, break‑glass, HIPAA‑readiness |

The takeaway: **Phase 1 is mostly surfacing and connecting what exists**, not new
construction. The genuinely new build is the clinical layer.

## Roadmap

Phases are ordered by *risk and dependency*, not dated.

- **Phase 1 — Native & surfaced.** Home redesign + Signals‑as‑router; provenance
  badges + disclaimers; structured safety‑plan builder; user‑held key recovery;
  EMA sampling scheduler. High impact, low risk, mostly wiring.
- **Phase 2 — Portal deepened.** Async encrypted notes (extend the game‑plan
  channel); finish WebAuthn (today a 501 stub); true‑revocation key rotation; PDF
  from the dashboard; the no‑code builder, provenance‑aware.
- **Phase 3 — The clinical layer.** Editable orgs/practices; multi‑role RBAC;
  care‑team / multi‑client; org‑consent; scheduling + front‑desk; behavioral
  guard (IDS). See [COMPANION_ACCESS_CONTROL.md](./COMPANION_ACCESS_CONTROL.md).
- **Phase 4 — Reach.** iOS via Kotlin Multiplatform; on‑device passive sensing
  (opt‑in); HRV measurement; personal early‑warning; JITAI nudges (hardened);
  break‑glass crisis access (hardened).

## The compliance gate (non‑negotiable)

**Before a single real patient's data is handled by a clinician using Daymark**,
there must be: (1) an external **HIPAA Security‑Rule** assessment, and (2) an
independent **security / cryptography audit** of the RBAC, key handling, and
recovery flows. Everything else can be iterative. This cannot. A mental‑health
data breach genuinely harms people. See the HIPAA‑readiness checklist in
[COMPANION_ACCESS_CONTROL.md](./COMPANION_ACCESS_CONTROL.md).

## Open questions

- Hosting model for the clinical edition: still strictly self‑hosted per
  practice, or an optional managed offering (which would make us a Business
  Associate and require BAAs)?
- Does the clinical platform ship as the same product or a separate **edition**
  of the app/portal, so the personal‑first experience stays uncluttered?
- Minimum viable role set for a first clinical pilot (which of the Phase‑3 roles
  are actually needed on day one)?

## Related documents

- [PROVENANCE.md](./PROVENANCE.md) — the tool‑labeling system.
- [COMPANION_ACCESS_CONTROL.md](./COMPANION_ACCESS_CONTROL.md) — orgs, roles,
  consent, sharing, revocation, recovery, IDS, HIPAA‑readiness.
- [CLINICAL_NOTES.md](./CLINICAL_NOTES.md) — the notes & assessment model.
- [COMPANION_SCOPE.md](./COMPANION_SCOPE.md) — prior scope (partially superseded
  here).
- [COMPANION_SECURITY.md](./COMPANION_SECURITY.md) — the existing threat model &
  crypto that this builds on.
