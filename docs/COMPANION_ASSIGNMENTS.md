# Daymark Companion — Capability-Scoped Therapist Assignments & Dynamic Dashboard

> **Status: design.** No code yet. This document extends the committed therapist design
> ([COMPANION_THERAPIST.md](COMPANION_THERAPIST.md)) with three maintainer decisions made
> after that doc was written:
> 1. Therapist actions are gated by an **owner-granted capability model** (Android-app-
>    permission style) — the owner allows specific things, and the therapist can *see*
>    exactly what they've been granted.
> 2. A **therapist → owner assignment channel** (assign questionnaires, tasks, longer
>    assessments, reminders/cadence, goals, and other in-app tasks), on top of the existing
>    "game plans" write-back.
> 3. Reports are a **dynamic, interactive, expandable dashboard**, not a PDF. PDF/CSV
>    become optional exports of what the dashboard shows.
>
> Where this differs from [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) /
> [COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md) (PDF-first reporting, game-plans
> as the only write-back), **this doc is authoritative.** All the security invariants of
> [COMPANION_SECURITY.md](COMPANION_SECURITY.md) still hold — the server stays zero-knowledge,
> the owner is the sole root of trust, and everything a therapist sends is signed and sealed
> to the owner.

## 1. Guiding principle: the owner grants capabilities, like app permissions

The therapist has **no inherent powers.** For each paired therapist the owner holds a
**capability grant** — a small, signed, revocable policy object that says exactly what that
therapist may do. The therapist's portal renders **only** the capabilities they've been
granted (the rest are hidden/disabled), and a "What you've allowed" panel shows the therapist
their current grant so expectations are clear on both sides. This mirrors Android runtime
permissions: nothing is on by default; the owner turns things on individually; the owner can
turn any of them off at any time.

### 1.1 Capabilities (each independently grantable, default OFF)

| Capability | Lets the therapist… | Default apply mode |
|---|---|---|
| `read.share` | See the curated records the owner shares (existing share flow). | n/a (read) |
| `assign.questionnaire` | Assign a self-check instrument (from the license-clean catalog). | **propose** |
| `assign.task` | Assign a cognitive/attention task (e.g. Steady Attention). | **propose** |
| `assign.largeAssessment` | Assign a longer, multi-part sit-down assessment/battery. | **propose** |
| `assign.reminder` | Suggest a cadence/schedule for an assigned item. | **propose** |
| `assign.goal` | Propose a goal (maps to the app's existing goals). | **propose** |
| `authorGamePlan` | Author non-diagnostic "game plans" (existing write-back). | **propose** |
| `suggest.setting` | Propose a change to a **defined, safe subset** of app settings. | **propose** |

Notes:
- The **assignable surface is data-driven** — new assignable item types (other in-app
  tasks/features) are added to this table + the schema, not hard-coded per therapist.
- There is **no capability** for a therapist to read raw item answers, free text, or the
  (structurally absent) self-harm slot; shares carry scores/bands only, per
  [COMPANION_FEATURES.md](COMPANION_FEATURES.md) §6 and [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md).

### 1.2 Apply mode per capability (the owner's choice, per Q&A)

Each granted capability carries an **apply mode** the owner picks:

- **`propose` (default, recommended):** the assignment/change arrives signed + encrypted,
  is shown in an inbox, and takes effect **only after the owner accepts** it. This is the
  Android "ask every time" equivalent and the safe default for everything, especially
  `suggest.setting`.
- **`auto` (opt-in, per capability):** a *low-risk* granted capability may be set to apply
  automatically once paired (e.g. "let my therapist set which self-checks appear, without
  asking each time"). `auto` is **never available** for `suggest.setting` beyond an explicit
  allowlist of non-sensitive keys, and is off unless the owner turns it on.

The grant object records `{capability → {granted: bool, apply: 'propose'|'auto'}}`. Revoking
sets `granted:false`; a subsequent re-key (per [COMPANION_SECURITY.md](COMPANION_SECURITY.md)
§R3) is the real cutoff for already-delivered material.

## 2. The assignment channel (therapist → owner)

Assignments reuse the **game-plan cryptographic shape** exactly (so there is one write-back
primitive, not two): the therapist **Ed25519-signs** the assignment and **`crypto_box_seal`s**
it to the owner's X25519 public key; the server stores an opaque blob + minimized metadata;
the owner's client fetches, **verifies the signature against the pinned therapist
fingerprint**, checks it against the **granted capability** (reject if not granted), then —
per apply mode — either surfaces it for acceptance or applies it.

### 2.1 Assignment object (plaintext, before signing+sealing)

```jsonc
{
  "assignmentId": "…", "lineageId": "…", "version": 3,
  "type": "questionnaire | task | largeAssessment | reminder | goal | setting",
  "capability": "assign.questionnaire",     // MUST match a granted capability
  "payload": { /* type-specific, see §2.2 */ },
  "cadence": { "every": "week", "count": 1 },   // optional (needs assign.reminder)
  "note": "therapist's short, non-diagnostic note",
  "issuedAt": 0,
  "authorFingerprint": "<therapist Ed25519 fp>"  // verified vs the owner's pinned value
}
```

### 2.2 Payloads by type

- `questionnaire` / `task` / `largeAssessment`: reference a catalog `instrumentId` /
  `taskId` (+ version). **License-clean + non-diagnostic gate still applies** — a therapist
  can only assign an instrument that passes the [COMPANION_FEATURES.md](COMPANION_FEATURES.md)
  honesty gate; they cannot inject a new instrument definition (that's a maintainer act via
  the ledger). A "large assessment" is a declared **bundle** of catalog instruments/tasks.
- `reminder`: a cadence for an existing assigned item.
- `goal`: title + optional linked activity → maps onto the app's existing `Goal`.
- `setting`: `{ key, value }` restricted to a **server-and-client-enforced allowlist** of
  non-sensitive keys (e.g. which self-checks are visible, reminder time). **Never** security
  keys (PIN, lock, encryption, network) — those are not in the allowlist and are rejected.

### 2.3 Lifecycle

`therapist assigns` → (capability check) → owner **inbox** → owner **accepts/declines**
(or auto-applies if the owner set that capability to `auto`) → owner completes the item →
**results flow back only through the normal share** (owner-curated, scores/bands only) if
`read.share` is granted. Assignments are append-only/versioned like snapshots; superseding
an assignment issues a new version. The owner can **snooze/dismiss** any assignment.

## 3. Dynamic dashboard (replaces the PDF-first report)

The report surface is an **interactive, expandable dashboard**, for both the owner (their own
data) and the therapist (the decrypted shared subset, in-browser). PDF/CSV are **optional
exports of the current view**, not the primary artifact.

### 3.1 Shape

- A **grid of cards**, each independently **expandable** (summary → full detail) and
  **interactive**: mood-over-time with range toggles (week/month/year/all) and brushing;
  a year-in-pixels/heatmap; activity ↔ mood association (correlation-not-causation, clearly
  labeled); a journal reader with search; sleep trends (if present); **assessment history**
  (per-instrument descriptive trend lines from the self-check engine); and, in the therapist
  view, the **assignment/game-plan** panel scoped to granted capabilities.
- Built on the existing hand-rolled SVG chart layer
  ([COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md)) — **still vendored, strict-CSP,
  no charting library, no CDN.** Interactivity (expand/collapse, range toggles, brushing,
  cross-filtering) is local state; no new network surface.
- **Accessibility:** every interactive chart keeps a table/text alternative; keyboard
  operable; respects reduced-motion. Non-diagnostic framing persists on every panel.

### 3.2 Two audiences, one component

The dashboard renders from a `BackupData`-shaped input, so it serves the owner (full data)
and the therapist (the curated share — scores/bands/aggregates only, never raw answers or
free text). What the therapist can see is bounded by the **share contents**, not the
dashboard — the dashboard just refuses to render fields absent from the share.

## 4. Security & consent (unchanged invariants, made concrete)

- **Server stays zero-knowledge.** Grants, assignments, and game plans are opaque signed +
  sealed blobs; the server enforces caps/rate limits/quota and the `setting` allowlist
  **structurally** (it can reject a non-allowlisted key without reading plaintext, because
  the key list is a fixed server constant — the *value* stays encrypted).
- **Capability-bounded, owner-accepted.** The owner's client rejects any assignment whose
  `capability` isn't currently granted, and (for `propose` mode) applies nothing without
  explicit acceptance. `auto` is opt-in per capability and never covers sensitive settings.
- **Mutual pinning + signatures** as in [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §4/§5:
  the owner verifies the therapist's signature against the OOB-pinned fingerprint before any
  assignment renders; the therapist verifies the owner's grant signature.
- **Revocation** is a capability toggle for future *server-mediated* delivery + a re-key for
  a true cutoff (honestly scoped per §R3).
- **Transparency both ways.** The owner sees an audit of what was assigned/accepted; the
  therapist sees their current granted capabilities ("What you've allowed").
- **Non-diagnostic & license-clean throughout.** A therapist can only assign catalog items
  that pass the honesty gate; the `setting` allowlist excludes anything clinical or security-
  sensitive.

## 5. Build sequence (each an independently verifiable PR)

1. **Capability + assignment core (web/TS + server):** the grant policy object, the
   assignment schema + validator (capability match, setting allowlist, non-diagnostic
   catalog check), and the crypto (reuse the game-plan signed+sealed primitive). Server
   endpoints to store/list opaque grant/assignment blobs. Unit + integration tests. *(No
   phone, no MFA needed — fully verifiable, like Milestone 2.)*
2. **Dynamic dashboard (web):** the interactive/expandable dashboard replacing the static
   Overview, driven by `BackupData`; reused for owner + therapist views. Verifiable via
   build + component/stats tests.
3. **Therapist auth + pairing (web/server):** WebAuthn/passkey + TOTP fallback, the OOB
   pairing/fingerprint flow, sessions ([COMPANION_SECURITY.md](COMPANION_SECURITY.md) §5).
4. **Therapist portal UI:** capability-scoped assign surface + "What you've allowed" +
   the shared-data dashboard + game-plan authoring.
5. **Owner acceptance UX + inbox** in the portal (and, later, in the phone app, 2b).
6. **Phone integration (2b+):** surface assignments/game-plans and apply accepted settings/
   goals into the app's DB (new tables, owner-accepted only).

Recommended start: **(1) then (2)** — both are fully local/CI-verifiable and don't depend on
the phone or WebAuthn, and they're the foundation the portal (3–5) sits on.
