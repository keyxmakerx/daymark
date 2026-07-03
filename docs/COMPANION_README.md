# Daymark Companion — Documentation Index

> ## Status: substantially built and CI-verified — see the [build status](#build-status-what-actually-ships-today) below
>
> These documents began as a design proposal; most of the surface described here is
> now **implemented and CI-green** (container, sync, therapist portal, SMTP, the
> Companion web UI). The [build status](#build-status-what-actually-ships-today)
> section is the source of truth for what actually ships today vs. what is still
> spec-only. Sibling documents (SCOPE/ARCHITECTURE/SECURITY/THERAPIST/DEPLOYMENT)
> were written design-first and have not all been fully reconciled against the
> shipped code — where they conflict with this file's build-status table, this file
> wins. See [COMPANION_HANDOFF.md](COMPANION_HANDOFF.md) for session-by-session detail.
>
> The flagship Daymark app remains **fully offline, declares no `INTERNET`
> permission, and operates 100% on-device** (see [../PRIVACY.md](../PRIVACY.md)). The
> Companion lives **only** in a separate, opt-in flavor and the self-hosted
> container, and never alters that default.

---

## What the Companion is

The **Daymark Companion** is an *optional, self-hosted, zero-knowledge* server the
**owner** runs on **their own** machine. There is no Daymark-operated cloud, no vendor
account, and no telemetry — the owner owns the container, the data, and the keys. The
server stores only **opaque ciphertext plus non-secret routing metadata**; it can
*withhold* data but can never *read* it.

### The four pillars

1. **Encrypted multi-device sync.** End-to-end-encrypted, append-only backup of the
   owner's versioned `BackupData` snapshots, synced across **the owner's own** devices.
   v1 is **single-writer, last-snapshot-wins** replication; true row-level merge is
   deferred behind a schema migration.
2. **Expanded "sit-down" user features.** A data-driven **questionnaire engine** and an
   original, **non-diagnostic** cognitive/attention **testing harness** that belong on a
   big screen — license-clean instruments only, results folded into the same E2EE
   snapshot and **private by default**.
3. **Revocable therapist access.** The owner grants **their** therapist a
   **read-only, MFA-protected, time-boxed, revocable** view of a **curated subset** of
   their data, encrypted to the therapist and owner-signed.
4. **Therapist-authored game plans.** **Non-diagnostic** "game plans" the therapist
   signs and sends back into the owner's app, landing in a new, segregated
   `game_plans` table (DB v13) the owner must explicitly accept — never the existing
   `treatments` table.

All four are wrapped in a **modern, sleek, fully-vendored** web UI ("Modern Paper, Big
Screen") that delivers polish **without** a single CDN, web-font fetch, or analytics
call — and all four keep the core promise intact: the server is a blind relay, the owner
is the sole root of trust, and the default phone build never gains a network.

---

## The design documents

| Document | Covers |
|---|---|
| [COMPANION_SCOPE.md](COMPANION_SCOPE.md) | Purpose, roles & boundaries (owner / therapist / server), in/out of scope, guiding principles, success criteria, honest limits, open questions. |
| [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md) | System topology, the four core data flows (sync / share / read / game-plan), crypto primitive set, single-writer sync model, `game_plans` data model (DB v13), API surface, retractions. |
| [COMPANION_SECURITY.md](COMPANION_SECURITY.md) | Multi-party threat model, crypto & key hierarchy, MFA / key custody / session model, server & reverse-proxy hardening, client-anchored anti-rollback, audit posture, retractions, honest limits. |
| [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) | Therapist role, non-diagnostic prime directive, share lifecycle (invite → enroll → pair → share → read → revoke), game-plan write-back, auth/sessions, honestly-scoped revocation, v1 scope lock. |
| [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) | Docker Compose topologies, canonical `docker-compose.yml`, reverse-proxy worked examples (Caddy / Traefik / nginx), config & secrets, backup/restore, upgrades/migrations, egress lockdown, hardening defaults. |
| [COMPANION_FEATURES.md](COMPANION_FEATURES.md) | Expanded "sit-down" user features: the data-driven questionnaire engine, the original non-diagnostic cognitive/attention testing harness, the license-clean instrument catalog, the versioned results data model (folded into the E2EE snapshot), and the honest-by-construction CI gates. |
| [COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md) | The "Modern Paper, Big Screen" web design system: the sleek-vs-vendored/CSP reconciliation, the self-hosted frontend stack, design tokens, theming, the hand-rolled SVG chart layer, WCAG 2.2 AA, and a satisfying CSP example. |
| [COMPANION_UX.md](COMPANION_UX.md) | Cross-surface UX & information architecture (owner viewer / assessment runner / therapist portal): key flows, privacy-by-design consent patterns, the honest Trust strip, and empty/error/revocation states. |
| [COMPANION_ASSIGNMENTS.md](COMPANION_ASSIGNMENTS.md) | Owner-granted **capability model** (Android-permission style), the therapist→owner **assignment channel** (questionnaires / tasks / large assessments / reminders / goals / settings), and the **dynamic interactive dashboard** (replaces PDF-first). |
| [SYNC_PROTOCOL.md](SYNC_PROTOCOL.md) | Normative E2EE sync wire format (Argon2id → XChaCha20-Poly1305 envelope, base64 conformance vector) + the `/v1` API — the contract the phone client must mirror. |
| [COMPANION_PHONE_2B.md](COMPANION_PHONE_2B.md) | Milestone 2b spec: the Kotlin/Android `sync` product flavor, lazysodium crypto conformance, inbound assignments/game-plans, DB v13/v14 migrations, pairing. Spec-only (CI/emulator-verifiable). |

**Named deliverable referenced throughout but not yet written:** `PAIRING.md` — the
out-of-band, bidirectional pairing / key-exchange protocol.

### Suggested reading order

Start with **SCOPE** (what and why) → **ARCHITECTURE** (how it fits together) →
**SECURITY** (threat model and crypto contract) → **THERAPIST** (the two-party flows)
→ **FEATURES** (the sit-down questionnaire/testing suite) → **DESIGN_SYSTEM** (the look
& feel) → **UX** (how the surfaces tie together) → **DEPLOYMENT** (how to run it).

---

## Cross-cutting design invariants

These hold consistently across all five documents:

- **Zero-knowledge server.** A full server/disk compromise leaks only opaque
  ciphertext + non-secret (but *minimized*, not "harmless") metadata.
- **Owner is the sole root of trust.** The owner alone holds keys and the passphrase,
  and alone grants/scopes/time-boxes/revokes therapist access.
- **One crypto primitive set everywhere:** XChaCha20-Poly1305 (AEAD), Argon2id
  (client-side KDF, ≥256 MiB), X25519 + Ed25519 (per party), `crypto_box_seal` (CEK
  wrap / sealing). **AES-256-GCM is removed** as an "equivalent."
- **No telemetry, no escrow, no key recovery.** Lost passphrase = unrecoverable
  blobs (phone-local copy is the fallback).
- **Non-diagnostic by *framing*** (not by construction) — every new surface carries
  the "self-check, not a diagnosis" disclaimer.
- **v1 scope lock:** one therapist, one keypair, one device.

### Honest limits, stated loudly and consistently

Each document retracts overstated earlier claims rather than quietly editing them:

- **No forward secrecy** on sealed boxes (mitigated only by bounded blob retention).
- **Revocation is honest-server-only**; real future-data revocation needs therapist
  re-keying.
- **The browser portal is NOT zero-knowledge** against a malicious operator; the
  native phone Sync flavor is the only secret-handling owner path.
- **Anti-rollback is client-anchored**; server chain checks are DoS hygiene only.
- **Audit suppression is undetectable** until a signed monotonic hash-chain ships, so
  "access cannot be hidden" is retracted.

---

## Build status (what actually ships today)

These docs began as design; much is now **built and CI-verified** on the `companion/`
branch/PRs. Honest state:

| Area | State |
|---|---|
| Hardened container, Phase-0 offline report viewer | ✅ built, CI-green (Docker smoke-test) |
| E2EE sync (zero-knowledge blob store + client crypto, browser reader + CLI writer) | ✅ built, CI-green (integration round-trip) |
| Self-check engine + honesty gate + "Steady Attention" task | ✅ built, CI-green |
| Dynamic interactive dashboard (replaces PDF-first) | ✅ built |
| Capability/assignment model + write-back crypto | ✅ built + tested |
| Therapist portal: **TOTP** auth, single-use invites, pairing + share crypto, owner acceptance inbox, capability-scoped assign surface, game-plan authoring | ✅ built + tested |
| SMTP mailer (owner-configured, off by default, no record content) | ✅ built, tested vs GreenMail |
| App ⇄ server email, **Option A** (owner notifications + access-token recovery) | ✅ built + tested — see below |
| WebAuthn / passkey therapist auth | ⚠️ **501 scaffold** — TOTP is the working path; browser ceremony unverifiable headlessly |
| Phone `sync` flavor (Kotlin, 2b) | 📄 spec only ([COMPANION_PHONE_2B.md](COMPANION_PHONE_2B.md)) — CI/emulator-verifiable |

Verified locally + in CI: web build 0 errors, web unit **140/140**, server **90 tests**,
sync integration **5/5**, Docker build+smoke green.

### App ⇄ server email — Option A (owner notifications + access-token recovery)

Decided and shipped: **no owner accounts, no passwords, no escrow.** The server remains
zero-knowledge and can never reset the PIN or E2EE passphrase. Concretely:

- The owner registers (or changes/removes) a notification email + per-event
  preferences via the owner-authenticated `PUT/GET /v1/owner/notifications`.
  The address is stored **in plaintext** on the server by necessity — see the
  T2 note added to [COMPANION_SECURITY.md](COMPANION_SECURITY.md).
- Notification events (all through the existing content guard — event type + link
  only, never record content): a therapist finishing enrollment, a therapist
  publishing a new assignment or game plan.
- **Access-token recovery**: an unauthenticated, heavily rate-limited,
  always-same-response `POST /v1/recovery/request` mints a single-use,
  time-limited confirmation link (emailed, never returned in the response);
  `POST /v1/recovery/confirm` rotates the owner's bearer token, shows it once, and
  invalidates the old one immediately. This recovers **server access only** — it
  cannot recover the PIN or the E2EE passphrase, and a recovered token still
  decrypts nothing.
- The owner/bearer token now lives in a small per-datadir store (`OwnerAccountStore`)
  instead of only in the `DAYMARK_AUTH_TOKEN` env var, so a runtime rotation survives
  a restart; an operator changing the env var (the pre-existing redeploy-to-rotate
  method in [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md)) still takes precedence.
- Stays OFF by default: everything no-ops unless `DAYMARK_SMTP_*` is configured *and*
  the owner has registered an address.

## Decisions & roadmap (pending / next)

- **Audit log** (owner-readable therapist-access log): flagged in
  [COMPANION_SECURITY.md](COMPANION_SECURITY.md); **next to build.**
- **WebAuthn**: implement server-side attestation/assertion (CI-testable) behind the
  already-pinned RP-ID/origins; browser ceremony stays deploy-time verified.
- Smaller follow-ups: credential-rotation endpoint, expired-invite/ticket sweep, per-client
  rate-limiting behind a trusted proxy, crypto-random lineage IDs, server `/data` backup docs,
  optional lockout-alert emails (deferred from the email Option A slice above).

---

## Related (flagship) documents

| Document | Covers |
|---|---|
| [../PRIVACY.md](../PRIVACY.md) | The flagship app's offline, no-network privacy posture (unchanged by this design; to be updated with a "Companion (optional, self-hosted)" section). |
| [../SECURITY.md](../SECURITY.md) | Project-wide security reporting and posture. |
| [../HANDOFF.md](../HANDOFF.md) | The non-diagnostic prime directive (§0). |
