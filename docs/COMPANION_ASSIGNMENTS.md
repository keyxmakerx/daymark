# Daymark Companion — capabilities, assignments and the dashboard

A clinician has no inherent powers. For each paired clinician the owner holds a **grant**: a small,
signed policy that says exactly what that clinician may do, in the style of Android's runtime
permissions. Nothing is on by default, the owner turns things on one at a time, and can turn any of
them off. The clinician sees their current grant ("What you've allowed") and their console offers
only what it allows.

Code: `companion/web/src/lib/assignments/` (types, grant, validation, crypto, inbox), the clinician's
assign surface in `companion/web/src/lib/therapist/`, and the relationship channels on the server
(`RelationRoutes.kt`, `RelationStore.kt`). The security invariants are
[COMPANION_SECURITY.md](COMPANION_SECURITY.md)'s: the server stays zero-knowledge, the owner is the
only root of trust, and everything a clinician sends is signed and sealed to the owner.

## 1. The owner grants capabilities

### 1.1 Capabilities (each granted separately, all off to begin with)

| Capability | Lets the clinician… | Default apply mode |
|---|---|---|
| `read.share` | Read the curated records the owner shares | n/a (read) |
| `assign.questionnaire` | Assign a self-check from the catalogue | propose |
| `assign.task` | Assign a timed task (today, Steady Attention) | propose |
| `assign.largeAssessment` | Assign a declared bundle of catalogue self-checks and tasks | propose |
| `assign.reminder` | Suggest a cadence for an assigned item | propose |
| `assign.goal` | Propose a goal | propose |
| `authorGamePlan` | Send non-diagnostic game plans | propose |
| `suggest.setting` | Propose a change to one of four allowlisted settings | propose, always |

- The assignable surface is data: a new assignable type is a row here and a type in
  `assignments/types.ts`, not per-clinician code.
- No capability reads raw item answers or a self-harm item. Shares carry check-in scores and bands
  only; journal text and notes appear in a share only if the owner includes them.

### 1.2 Apply mode, per capability

- **`propose`** (the default): the item arrives signed and sealed, waits in the owner's inbox, and
  takes effect only after the owner accepts it.
- **`auto`** (opt-in, per capability): a granted, low-risk capability may apply without asking, for
  example "let my clinician choose which self-checks appear". **`suggest.setting` never applies
  automatically**, whatever its mode says (`shouldAutoApply`).

The grant records `{capability → {granted, apply}}`, is signed by the owner (Ed25519 over its canonical
JSON) and published as a new append-only version on the grants channel; the clinician verifies it
against the pinned owner key before trusting it. Revoking sets `granted: false` in a new version. That
stops future delivery through an honest server; a true cutoff for material already delivered is
re-pairing with new keys (COMPANION_SECURITY.md R3). "Revoking does not un-send what was already
read."

## 2. The assignment channel (clinician to owner)

Assignments reuse the game-plan shape exactly, so there is one write-back primitive. The clinician
signs the payload together with a context string (`daymark.assignment.v1`) and the fingerprint of the
owner key it is sealed to, then seals it to the owner's X25519 key with `crypto_box_seal`. The server
stores an opaque blob. The owner's console opens it, verifies the signature against the **pinned**
clinician key (never a key from the blob), refuses a wrong context or another owner's fingerprint, and
checks it against the current grant.

### 2.1 The assignment object (inside the signature)

```jsonc
{
  "assignmentId": "…", "lineageId": "…", "version": 3,
  "type": "questionnaire | task | largeAssessment | reminder | goal | setting",
  "capability": "assign.questionnaire",     // must match the type, and be granted
  "payload": { /* see §2.2 */ },
  "cadence": { "every": "week", "count": 1 }, // optional
  "note": "the clinician's short, non-diagnostic note",
  "issuedAt": 0,
  "authorFingerprint": "<clinician Ed25519 fingerprint>"
}
```

### 2.2 Payloads by type

- `questionnaire` `{instrumentId}`, `task` `{taskId}`: must name a catalogue item, which has already
  passed the honesty gate (COMPANION_FEATURES.md). A clinician cannot send a new instrument definition.
- `largeAssessment` `{bundle: [{kind, id}]}`: a non-empty list of catalogue self-checks and tasks.
- `reminder` `{every, count}`: a cadence for an assigned item.
- `goal` `{title, activityId?}`.
- `setting` `{key, value}`: the key must be one of `visibleSelfChecks`, `reminderTime`,
  `reminderCadence`, `theme`. PIN, lock, biometric, encryption, network and backup settings can never
  be assigned.

**Who enforces the setting allowlist.** The owner's console does, on the decrypted item
(`validateAssignment`, run from the inbox). That is the check that binds, because it is run by the
party being protected. The server also refuses an optional `X-Setting-Key` routing header outside the
same four keys, but that header is a second claim by the same author, the shipped clinician client
does not send it, and the server cannot read the sealed body. It keeps a stray string out of the
server's index and guarantees nothing about the setting.

### 2.3 Lifecycle

The clinician assigns (their console offers only granted capabilities) → the owner's inbox opens,
verifies and validates each item → the owner accepts, declines or snoozes (or it applies, where
`auto` is allowed). A forged, tampered, ungranted or off-allowlist item is never applicable, whatever
the owner clicks. Items are append-only and versioned per lineage; superseding one issues a new
version.

What is not built:

- The owner's decision is not saved yet, so the clinician never learns it: #234.
- The phone receives nothing yet, so nothing an owner accepts reaches the app: #177.
- Results of a self-check taken in the Companion stay on that device; they reach a clinician only
  once they are saved into the encrypted snapshot and shared: #237.

## 3. The dashboard

One component (`Dashboard.svelte`) renders a `BackupData`-shaped input for every audience: the owner
reading an exported file or their synced copy, the owner console, and the clinician's view of a share
(adapted from the share bundle, so it can only show what the owner included). Hand-rolled SVG, no
charting library, no network.

It has four expandable cards: mood over time with 30-day, 90-day, one-year and all-time ranges;
activities and mood, labelled as association and not cause; self-check history, one descriptive
trend per instrument; and a journal reader. Every panel keeps the non-diagnostic framing. A PDF or CSV
export is not part of it.

Year-in-pixels, brushing, journal search, sleep trends and export are not built: #245.

## 4. Security and consent

- **The server stays zero-knowledge.** Grants, shares, assignments and game plans are opaque signed
  and sealed blobs. The server enforces size caps, per-relationship quotas, version retention, rate
  limits, and which side may write each channel: the owner writes grants and shares, the clinician
  writes assignments and game plans.
- **Capability-bounded and owner-accepted.** The owner's console rejects any item whose capability
  is not currently granted, and applies nothing in `propose` mode without an explicit yes.
- **Mutual pinning.** Each side verifies the other's signatures against keys pinned in the pairing
  ([COMPANION_PAIRING.md](COMPANION_PAIRING.md)), never against a key the server supplied.
- **Revocation** is a capability turned off, for future delivery through an honest server, plus
  re-pairing for a true cutoff.
- **Transparency both ways.** The owner's access log records what the clinician opened and sent; the
  clinician sees their current grant.
- **Non-diagnostic and licence-clean throughout.** Only catalogue items that pass the honesty gate
  can be assigned, and the setting allowlist excludes anything clinical or security-sensitive.
