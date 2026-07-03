# Daymark Companion — Coordination Plan (post-PR #6)

> **Audience:** any chat/agent picking up a Companion work track, and the maintainer.
> Read [`COMPANION_HANDOFF.md`](COMPANION_HANDOFF.md) first for full context, then this
> file for *what to build next and how the parallel tracks stay out of each other's way*.
>
> **Status snapshot (2026-07-03):** PR #6 is **merged**; `main` is green at every layer
> (web build 0 errors, web unit 134/134, sync integration 5/5, server 57/57, Docker
> smoke-test ✅ — re-verified locally *and* in CI post-merge). There is no build failure.

## 0. Decisions made (maintainer-confirmed 2026-07-03)

1. **App ⇄ server email = Option A**: owner **notifications + server-access-token
   re-issue only**. No owner accounts, no passwords, no escrow — the server still can
   never reset the PIN or E2EE passphrase. Uses the existing SMTP mailer.
2. **Phone 2b starts now, in parallel** — it touches only the Android app, so it cannot
   conflict with the web/server tracks.

## 1. The tracks

Three tracks run **in parallel now**; two are **queued** behind them. Each track is one
chat/agent, one branch, one PR to `main`. Suggested branch names below (an auto-assigned
`claude/...` branch name is fine too — just say which track the PR is in its title).

| # | Track | Branch (suggested) | Area | Runs |
|---|---|---|---|---|
| T1 | Audit log (therapist access) | `claude/companion-audit-log` | `companion/server` + owner web UI | **now** |
| T2 | Email Option A | `claude/companion-email-a` | `companion/server` (mail/routes) + owner web UI | **now** |
| T3 | Phone `sync` flavor (2b) | `claude/companion-phone-2b` | `app/` (Android) + CI | **now** |
| T4 | WebAuthn server-side | — | `companion/server/auth` + therapist portal | queued |
| T5 | Hardening chores batch | — | small server/docs items | queued |

### T1 — Owner-readable audit log of therapist access

*Why:* flagged in `COMPANION_SECURITY.md`; the strongest "other important thing."
*Mini-spec:*

- **Server**: append-only audit records, keyed by relationship. Record shape:
  `{ ts, relRef, actor: "therapist", action, objectRef?, meta? }` where `action` ∈
  `login_ok | login_fail | lockout | fetch_share | fetch_gameplan | post_assignment |
  post_gameplan | enrol_ok | session_expired` (extend as needed). **Metadata only —
  never plaintext, never decrypted content**; `objectRef` is the opaque blob id/version
  the server already sees. Storage: same storage layer as existing blobs (insert-only).
- **Read path**: owner-token-authenticated `GET /v1/relationships/{relRef}/audit`
  (paginated, newest-first). Therapists must NOT be able to read or write it directly.
- **Tamper-evidence (nice-to-have, keep simple)**: hash-chain each entry
  (`entryHash = H(prevHash ‖ entry)`); document honestly that suppression by a malicious
  server is still possible (see the retraction in `COMPANION_README.md` invariants).
- **Web (owner)**: an "Access log" panel per relationship in the owner surface —
  human-readable labels, timestamps, and a visible "metadata only" caveat.
- **Docs**: update `COMPANION_SECURITY.md` audit posture section from "flagged" to
  "shipped (metadata-only, hash-chained)".
- **Tests**: server route tests (events appended on the real access paths, owner-only
  read, pagination, nothing sensitive in entries); web unit tests for the log model.

### T2 — Email Option A (notifications + access-token re-issue)

*Why:* decision #1 above; the mailer + content guard already exist and are GreenMail-tested.
*Mini-spec:*

- **Owner email registration**: owner (authenticated by the owner access token) can set /
  change / remove a notification email + per-event preferences. The address lives
  server-side in plaintext by necessity — document that in `COMPANION_SECURITY.md`.
- **Notification events** (all through the existing content guard — event type + link
  only, never record content): therapist enrolled/paired, new item in the owner
  acceptance inbox, access-token re-issued, (optional) lockout alerts.
- **Access-token re-issue**: request endpoint (email only, unauthenticated,
  heavily rate-limited + always-same-response to avoid address probing) → single-use,
  time-limited confirmation link → on confirm, rotate the owner access token, show it
  once, invalidate the old one. This recovers *server access* only — E2EE keys/PIN are
  untouched and unrecoverable by design.
- **Stays OFF by default**: everything no-ops unless `DAYMARK_SMTP_*` is configured AND
  the owner registered an address.
- **Tests**: GreenMail sends for each event, content-guard extension tests, re-issue
  route tests (single-use, expiry, rate limit, old-token invalidation).

### T3 — Phone `sync` flavor (Kotlin, Milestone 2b)

*Why:* decision #2; spec already written.
*Mini-spec:* implement [`COMPANION_PHONE_2B.md`](COMPANION_PHONE_2B.md). Key constraints:

- **First commit = the CI enabler**: an Android CI job (extend `.github/workflows/build.yml`
  or a new workflow) that builds the `sync` flavor and runs its unit tests — this track
  is CI-verified only (no Android SDK in the cloud workspace, no emulator needed for v1).
- The web crypto + [`SYNC_PROTOCOL.md`](SYNC_PROTOCOL.md) are the **conformance oracle** —
  the Kotlin client must pass the protocol's base64/envelope conformance vector as a unit
  test before any networking lands.
- The default flavor must remain byte-for-byte offline: **no `INTERNET` permission
  outside the `sync` flavor manifest** (add a CI check for this).
- Work in vertical slices (crypto conformance → pairing → pull → push → inbound
  assignments/game-plans), PR-able independently if the track gets big.

### T4 — WebAuthn server-side (queued; start after T1 or T2 merges)

Replace the 501 scaffolds with real attestation/assertion using the well-audited
**Yubico `java-webauthn-server`** library (don't hand-roll COSE/CBOR): registration +
assertion ceremonies behind the already-pinned RP-ID/origins, credentials in the existing
storage layer, TOTP remains the fallback path. CI-testable server-side; the browser
ceremony stays deploy-time-verified. Touches `auth/` — **do not run concurrently with T2**
(both edit auth/routes wiring).

### T5 — Hardening chores batch (queued; cheap-model friendly)

From handoff §6.5: credential-rotation endpoint, expired-invite/ticket sweep,
crypto-random lineage IDs, trusted-proxy per-client rate-limit config, server `/data`
backup/restore docs. Small, independent, well-specified — batch into one PR.

## 2. Coordination rules (all tracks)

1. **Start**: read `docs/COMPANION_HANDOFF.md` + this file; run the handoff §2 verify
   commands to confirm green *before* changing anything (in the cloud workspace, if
   `./gradlew` 403s downloading its distribution, use the preinstalled system `gradle` —
   it's an egress-proxy policy, not a build failure).
2. **Branches/PRs**: one branch per track off current `main`; PR to `main`; the
   path-filtered `Companion` CI (web, server, integration, Docker) must be green.
   Android work must keep `Build` green.
3. **File ownership while tracks are in flight** — additive-first, new files preferred:
   - T1 owns `server/**/audit*`, T2 owns `server/**/mail/**` + recovery routes. Both
     will touch route wiring (`Application.kt`/routing) and the owner UI shell —
     keep those diffs minimal.
   - **Merge order on conflict: T1 first, T2 rebases.** T2 should emit its events
     through a tiny seam (or a TODO hook) rather than depending on T1's unmerged code.
   - T3 must not touch `companion/**` at all (protocol questions → flag to the
     coordinator, don't edit the contract docs unilaterally).
4. **Review gate**: before merging, bring the PR back to the **coordinator chat** for a
   code + security review pass. Security-sensitive surfaces (T2 re-issue flow, T4) get
   an adversarial review, not a rubber stamp.
5. **Honesty rules still bind**: non-diagnostic framing, zero-knowledge server, no new
   outbound beyond the owner-configured mailer, no record content in email — CI gates
   and the content guard are there to enforce this; don't weaken them to get green.
6. **When a track finishes**: update the build-status matrix in `COMPANION_README.md`
   and strike the item from this plan in the same PR.

## 3. Suggested model/effort split (usage-saving)

- **T1, T2, T3 implementation**: a mid-tier model (e.g. Sonnet) is sufficient — the
  designs above + existing patterns in the codebase are the guardrails. Escalate to a
  stronger model only if stuck on crypto/protocol subtleties.
- **T4 WebAuthn**: mid-tier is fine *with* the Yubico library; hand-rolling would need a
  stronger model (so: use the library).
- **T5 chores**: smallest/cheapest model available.
- **Coordination + pre-merge review**: strongest available model (the coordinator chat).
