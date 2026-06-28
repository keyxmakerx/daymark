# Daymark Companion — Scope & Purpose

> ## ⚠️ STATUS: DESIGN ONLY — NO CODE EXISTS YET
>
> **Nothing in this document is implemented.** The Daymark Companion is a *design
> proposal*. There is no server binary, no Docker image, no "Daymark Sync" build
> flavor, and no therapist portal in the shipping app today. Every behavior,
> guarantee, table, and config snippet below describes an **intended** design that
> is still subject to review and may change or be dropped entirely.
>
> The flagship Daymark app remains **fully offline, no `INTERNET` permission, no
> server** (see [../PRIVACY.md](../PRIVACY.md)). The Companion, if built, lives
> only in a **separate, opt-in flavor** and never alters that default.
>
> Open sequencing questions — including *whether the owner→therapist sharing and
> game-plan tracks ship at all in the first release, or whether sync lands first* —
> are unresolved. See [Open Questions](#open-questions-unresolved).

---

## Contents

- [Purpose](#purpose)
- [Why a Companion](#why-a-companion)
- [Roles & Boundaries](#roles--boundaries)
- [In Scope](#in-scope)
- [Explicitly Out of Scope](#explicitly-out-of-scope)
- [Guiding Principles](#guiding-principles)
- [Success Criteria](#success-criteria)
- [Non-Goals](#non-goals)
- [Honest Limits](#honest-limits-read-this)
- [Open Questions](#open-questions-unresolved)
- [Related Documents](#related-documents)

---

## Purpose

The **Daymark Companion** is an *optional, self-hosted* server that the **owner**
runs on their **own** machine to do three things: (1) keep an end-to-end-encrypted,
durable backup of their journal/mood data synced across **their own** devices,
(2) grant **their** therapist a **revocable, time-boxed, MFA-protected, read-only**
view of a **curated subset** of that data, and (3) receive **non-diagnostic
"game plans"** that the therapist authors back into the app — all while the server
stays **zero-knowledge** of plaintext, **phones home to no one**, and **makes no
medical claims**. There is no Daymark-operated cloud; the owner owns the container,
the data, and the keys.

---

## Why a Companion

Daymark is, and will remain, a strictly local, offline self-tracking app. But a few
real needs cannot be met by a single phone in isolation:

- **Durability and multi-device continuity.** A phone can be lost, broken, or
  replaced. Owners with a tablet or a second phone want their own data on their own
  devices without trusting a third party's cloud.
- **Sharing with a real clinician — on the owner's terms.** Some owners *want* to
  show a slice of their history to a therapist they already see. Today the only path
  is exporting a plaintext JSON/PDF and handing it over with zero control over what
  happens next. A scoped, expiring, revocable share is strictly better for the owner.
- **Receiving guidance back.** A therapist may want to hand the owner a structured
  "game plan" (e.g. a sleep-hygiene routine, a behavioral-activation schedule). Today
  that lives on paper. Bringing it into the app — *as advisory, owner-accepted,
  non-diagnostic guidance* — keeps it actionable without turning Daymark into a
  clinical record system.

The Companion exists to serve those needs **without breaking the core promise**: the
server never sees plaintext, the owner stays sovereign over their keys and their
sharing, and the default app never gains a network. The design's whole job is to add
these capabilities *as least-authority, opt-in additions* — not to become a backend
the app depends on.

For how the pieces fit together, see
[COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md); for the cryptographic and
threat-model details, see [COMPANION_SECURITY.md](COMPANION_SECURITY.md).

---

## Roles & Boundaries

Three parties, three very different levels of trust. The owner is the sole **root of
trust**; the therapist is a *pinned, verified, read-mostly* second party; the server
is a **blind relay and policy gatekeeper** that can withhold data but can never read it.

| Role | Needs | Boundaries (what they explicitly cannot do) |
|---|---|---|
| **Owner / patient** | Encrypted multi-device sync and durable backups; full control to create, scope, time-box, and **instantly revoke** a therapist share; ability to read therapist-authored game plans inside the app; visibility into when a share was accessed; **sole** possession of keys and passphrase. | Holds the only copy of the sync passphrase and root keys. The server and therapist can never read their plaintext without an explicit, owner-granted share. The owner alone decides what subset is shared and for how long. Their **phone-local data remains the authoritative source of truth** — the server is a replica. |
| **Therapist** | A secure, MFA-protected, time-boxed, **read-only** view of exactly the curated subset the owner shared; the ability to author **non-diagnostic** game plans that flow back to the owner. | Access is read-only over a **curated subset**, never the full dataset. Access is granted, scoped, and revoked **solely by the owner** and **auto-expires**. The therapist cannot read anything outside an active share, **cannot enumerate** other records or other patients, and cannot edit the owner's raw records. Game plans are **advisory guidance, not diagnoses or orders**; the owner can ignore or delete them. Identity is **out-of-band pinned** (TOFU); the server never vouches for the therapist's keys. |
| **Server (the Companion container)** | To store and serve ciphertext blobs, enforce server auth/MFA and rate limits, mediate share grants/revocations and expiry, and keep an access-metadata log. | **Zero-knowledge of plaintext** — never holds passphrases or decryption keys. Makes **no outbound connections** and runs no telemetry. Enforces access *policy* but **cannot read** what it stores or transmits. A full server/disk compromise must leak **only opaque blobs plus non-secret metadata**. It is a **convenience replica and policy mediator — never the source of truth and never a clinician.** Its integrity claims (version chains, audit lists) are *DoS-hygiene only* and are **not trusted** by the client without local signature verification. |

### Trust topology

```
                    out-of-band pairing (QR / 4–6 word SAS)
        ┌───────────────── mutual fingerprint verification ─────────────────┐
        │  owner verifies therapist X25519+Ed25519                          │
        │  therapist verifies owner X25519 (enc) + Ed25519 (sign)           │
        ▼                                                                   ▼
  ┌───────────┐                                                     ┌──────────────┐
  │   OWNER   │   E2EE snapshots / signed shares / game-plan reads  │  THERAPIST   │
  │  (phone,  │ ◀─────────────────────────────────────────────────▶│  (pinned /   │
  │  Sync     │                                                     │  installed   │
  │  flavor)  │                  all ciphertext                     │  client)     │
  └─────┬─────┘                                                     └──────┬───────┘
        │                                                                  │
        │            ┌────────────────────────────────────────┐           │
        └───────────▶│        SERVER (Companion container)      │◀──────────┘
                     │  zero-knowledge blind relay + gatekeeper │
                     │  stores: ciphertext + non-secret metadata│
                     │  enforces: auth, MFA, rate-limit, expiry │
                     │  CANNOT: decrypt, escrow, phone home     │
                     └────────────────────────────────────────┘
                        no outbound network · no telemetry
```

The **out-of-band pairing** step is mandatory and **bidirectional** — the server
never mediates an unverified key. The pairing/key-exchange protocol is a named
deliverable (`PAIRING.md`), referenced from
[COMPANION_SECURITY.md](COMPANION_SECURITY.md), not hand-waved.

---

## In Scope

The following are *intended* for the Companion design (subject to the
[sequencing questions](#open-questions-unresolved)):

1. **Self-hosted deployment.** Docker Compose behind the owner's **own** reverse
   proxy (Caddy / Traefik / nginx), with a documented LAN-only self-signed fallback.
   The owner runs one `docker compose up`; there is no account and no vendor.
   Deployment specifics live in [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md).

2. **End-to-end-encrypted, append-only sync** of the existing versioned `BackupData`
   JSON snapshots. The server stores **only** ciphertext plus non-secret metadata
   (size, timestamp, content hash, monotonic version, device label).
   - **v1 is SINGLE-WRITER, LAST-SNAPSHOT-WINS replication** — the newest full
     snapshot is authoritative; an older device pulls and replaces; history is
     preserved append-only server-side. **True row-level multi-device merge is NOT
     in v1** (the current schema has no `updatedAt`/UUID columns) and is gated behind
     a prerequisite schema migration, deferred to a later phase. See
     [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md).

3. **Client-side crypto only.** Argon2id KDF (≥256 MiB, client-side) from a sync
   passphrase that **never leaves the device**; **XChaCha20-Poly1305** AEAD
   everywhere (192-bit random nonce); X25519 + Ed25519 for the therapist party;
   browser decryption via WASM/WebCrypto. The single primitive contract is specified
   in [COMPANION_SECURITY.md](COMPANION_SECURITY.md).

4. **Therapist Access as an explicit, owner-created "share":** a curated,
   owner-selected subset of records, encrypted to the therapist (random XChaCha20
   CEK wrapped to the therapist's X25519 key), **Ed25519-signed by the owner**, gated
   behind server auth + MFA, **time-boxed with an expiry**, and **revocable** by the
   owner. The therapist verifies the owner's pinned signing fingerprint before
   rendering anything.

5. **Therapist-authored Game Plans** that flow **one way** back into the owner's app,
   landing in a **new, segregated `game_plans` table (DB v13)** — **not** the
   existing `treatments` table — encrypted so only the owner can read them, framed as
   **non-diagnostic guidance**. The therapist body is immutable/append-only;
   owner-authored progress is a separate layer. See
   [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md).

6. **An offline, drag-in static report viewer (Phase 0)** for sit-down review of an
   exported backup JSON, with **no server and no network**.

7. **Server hardening defaults:** non-root, read-only container FS except the blob
   volume, **no outbound network**, rate-limited/locked-out auth, strict CSP with
   **fully vendored assets**, and blob/version/request-size limits with per-token
   storage quotas. Detailed in [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) and
   [COMPANION_SECURITY.md](COMPANION_SECURITY.md).

8. **An owner-readable access/audit log** of which share was accessed and when —
   **metadata only, no plaintext** — so the owner can see their therapist's access.
   (Suppression-resistance requires a signed monotonic chain; see
   [limits](#honest-limits-read-this).)

9. **Honest documentation surfaces:** a Companion `INSTRUMENTS.md` ledger and a
   `PRIVACY.md` "Companion (optional, self-hosted)" section that keep the license and
   data-flow claims verifiable, and that **retract** any prior "there is no server,
   so there is no server-side surface" wording for the opt-in Sync flavor.

---

## Explicitly Out of Scope

The following are **deliberately excluded.** Several are excluded *permanently* on
principle, not merely deferred:

- **Any Daymark-operated server, hosted SaaS, vendor account, or managed offering.**
  There is no "our cloud." Ever.
- **Server-side plaintext access, server-side decryption, key escrow, or passphrase
  recovery.** Losing the passphrase means the blobs are **unrecoverable by design**.
- **Adding the `INTERNET` permission to the default phone build.** All server talk
  lives **only** in the separate, opt-in "Daymark Sync" flavor.
- **Any telemetry, analytics, crash reporting, ads, third-party trackers, CDNs, or
  outbound calls** from the container.
- **Diagnosis, risk verdicts, scoring of self-harm items, clinical assessment**, or
  anything that would make the Companion a medical device. The synced bundle keeps
  PHQ-9 item-9 / self-harm scoring **structurally absent**.
- **Therapist write access to the owner's raw records**, a therapist **dashboard over
  multiple patients**, **multi-tenant / clinic hosting**, or a therapist-operated
  central server. **v1 is locked to one therapist, one keypair, one device.**
- **Real-time chat/messaging, video, scheduling, billing, EHR/EMR integration, or
  insurance workflows.**
- **Licensed/proprietary clinical instruments** (TOVA, Conners, CAARS, etc.). Only
  **public-domain or self-authored** instruments are permitted, tracked in
  `INSTRUMENTS.md`.
- **Treating the server as the source of truth.** It is a convenience replica, never
  the only copy of the owner's data.
- **True concurrent multi-device row-level merge in v1** (deferred behind a schema
  migration; see [In Scope #2](#in-scope)).

---

## Guiding Principles

1. **Zero-knowledge by default — extended to multiple parties.** Every payload
   (snapshots, shares, game plans) is end-to-end encrypted. The server is a blind
   relay and policy gatekeeper, never a reader.
2. **Self-hosted only.** The owner owns the container, the data, and the keys.
   Nothing phones home; there is no vendor in the loop.
3. **No telemetry, ever.** The container makes no outbound connections — documented
   and enforced via egress restrictions.
4. **The owner is sovereign.** They alone grant, scope, time-box, and revoke
   therapist access, and they alone hold the keys. Revocation and expiry are **real
   and immediate against an honest server** (and the honest limits against a
   *colluding* server are stated plainly — see below).
5. **Least authority across parties.** The therapist gets read-only access to a
   curated subset for a bounded time; game plans flow one way and are advisory.
6. **Append-only, never destructive.** Sync history is append-only and the phone
   keeps the authoritative local copy, so a bad sync or a hostile server **cannot
   destroy** the owner's data.
7. **Non-diagnostic and license-clean.** No medical claims, no risk verdicts, only
   public-domain or self-authored instruments, same crisis-resources posture as the
   app. Every new surface — share viewer, game-plan UI — carries the *"self-check,
   not a diagnosis"* framing.
8. **Verifiable and auditable.** Vendored assets, strict CSP, pinned/reproducible
   builds, an owner-readable access log, and a privacy doc that states exactly what
   leaves the device.
9. **Honest about limits.** Endpoint compromise of the owner's or therapist's device
   is **out of the threat model** and stated plainly. The server can **withhold but
   not read**, and that trade-off is documented — along with the retracted claims
   listed [below](#honest-limits-read-this).

---

## Success Criteria

The design is successful only if all of the following hold:

- ✅ A breach of the server or its disk leaks **only opaque ciphertext plus
  non-secret metadata** — no plaintext records, no passphrases, no keys, no game
  plans in the clear. *(Caveat: this does **not** extend to anyone who types a secret
  into the server-served **browser portal** — see [limits](#honest-limits-read-this).)*
- ✅ The **default phone build still declares no `INTERNET` permission** and the
  F-Droid privacy claim remains verifiable; **only** the opt-in Sync flavor talks to
  a server.
- ✅ The owner can self-host the full stack with **one `docker compose` command**
  behind their own reverse proxy, with **no account** and **no outbound connection**
  from the container.
- ✅ A therapist can view a share **only after MFA**, **only for the curated subset**,
  and **only until expiry**; the owner can revoke and the therapist **immediately
  loses future access against an honest server**.
- ✅ A therapist-authored game plan reaches the owner's app, lands in the **new
  `game_plans` table**, and is readable **only by the owner**.
- ✅ The owner can see an **access log** of when each share was opened, with **no
  plaintext** exposed in that log.
- ✅ Losing the sync passphrase makes server-stored blobs **unrecoverable**, and this
  is documented **loudly**, with the phone-local copy as the fallback.
- ✅ All bundled instruments are **public-domain or self-authored**, tracked in
  `INSTRUMENTS.md`, and every surface carries the **"self-check, not a diagnosis"**
  framing.
- ✅ Static analysis / runtime verification confirms the container makes **zero
  outbound network calls** and the web UI loads **zero third-party origins**.

---

## Non-Goals

These describe directions the project will **not** pursue, even if individually
tempting:

- Becoming a **backend the phone app depends on** — the app must keep working fully
  offline with no server.
- Building a **clinician-facing product**, multi-patient dashboard, or clinic-grade
  multi-tenant deployment.
- Being a **medical device**, diagnostic tool, or risk-detection system.
- Offering **convenience features** (password reset, key recovery, account sync) that
  would require the server to hold secrets.
- Competing with **EHR/telehealth platforms** or integrating with insurance, billing,
  or scheduling.
- **Maximizing therapist capability at the cost of owner control** — the owner's
  sovereignty and the zero-knowledge boundary **win every trade-off**.
- Supporting **proprietary or licensed clinical instruments** to look more "clinical."
- Shipping **anything that makes the no-telemetry / no-network claim unverifiable.**

---

## Honest Limits (read this)

This design refuses to overclaim. The following limits are **load-bearing** and are
documented in full in [COMPANION_SECURITY.md](COMPANION_SECURITY.md); they are
summarized here so the scope is not read as a stronger promise than it is.

| Claim that is **retracted / scoped** | The honest statement |
|---|---|
| ~~"Ephemeral sender = forward secrecy."~~ | Sealed-box (`crypto_box_seal`) gives **confidentiality + sender anonymity, no PFS**. One compromise of the recipient's long-term X25519 key retroactively decrypts **every** blob ever sealed to it. Server blob retention for shares/game-plans is **bounded (configurable TTL + hard-delete of superseded/expired bytes)** to make the harvest-now-decrypt-later window finite. |
| ~~"CEK rotation defeats a colluding server."~~ | Revocation (expiry + revoke-flag) stops **future fetches on an HONEST server only**. An already-pushed share remains readable to the therapist key against a **malicious/colluding** server. Real future-data revocation requires **therapist re-keying** (re-pair to a new verified key). |
| ~~"Full server compromise leaks only opaque ciphertext"~~ — for the **browser portal**. | SRI/CSP are **inert** when the same origin serves both the HTML and the assets; a hostile operator rewrites both. Entering the master passphrase into the browser portal is **forbidden/strongly discouraged**; the **native Sync flavor is the only secret-handling owner path**. The browser portal is a **lower-assurance convenience path, not zero-knowledge.** |
| ~~"Access cannot be hidden."~~ | Therapist-signed attestations prevent **forgery** but not silent **suppression** without a signed monotonic chain. Until that ships, the "access cannot be hidden" claim is **retracted**. |
| ~~"No server, so no server-side surface."~~ | **False for the opt-in Sync flavor.** The owner→therapist path is a genuine plaintext egress to a **third human party**, stated honestly in `PRIVACY.md`/`SECURITY.md`. |

Additional documented limits: TOTP-fallback therapists use a weaker,
server-stored-Argon2id authenticating secret (a parallel custody path); endpoint
compromise of either device is out of model; and v1 is locked to **one therapist /
one keypair / one device** with **no key escrow** (loss = owner re-invitation +
re-pair).

---

## Open Questions (unresolved)

These are **maintainer decisions still pending** and are *not* settled by this scope:

1. **Revocation vs. a colluding server:** ship therapist **re-keying** as the real
   revocation primitive in v1, or accept and loudly document honest-server-only
   revocation + future-data-via-rotation?
2. **Browser portal therapist path:** is an installed/pinned therapist client (or
   out-of-band bundle pinning + rotatable `prfSalt`) in scope for v1, or is the
   deterministic-KUK tampered-JS break accepted as a documented v1 limitation?
3. **Sequencing:** does the first Companion release ship the owner→therapist
   **sharing + game-plans** tracks at all, or land **sync-only** first?
4. **Server blob-retention TTL** for shares/game-plans: a concrete default
   (e.g. 30/90 days, or hard-delete on supersede/expiry) to bound the no-PFS window.
5. **Attestation hash-chain:** implement the signed monotonic sequence for
   suppression-detection in v1, or drop the "access cannot be hidden" claim until
   later?
6. **Size-padding bucket scheme** (e.g. 4 / 16 / 64 KiB) and whether padding is
   mandatory-default for **all** blob types including snapshots.
7. **Sub-path / RP-ID** for LAN / `.local` / bare-IP self-hosters: which
   WebAuthn-incompatible deployments are officially supported vs. documented as
   unsupported?
8. **Schema migration:** require the prerequisite UUID + `updatedAt` migration on the
   roadmap, or keep sync permanently single-writer last-snapshot-wins?

---

## Related Documents

| Document | Covers |
|---|---|
| [COMPANION_SCOPE.md](COMPANION_SCOPE.md) | **This doc** — purpose, roles, in/out of scope, principles, success criteria. |
| [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md) | System topology, sync replication model, data model (incl. `game_plans` v13), API surface. |
| [COMPANION_SECURITY.md](COMPANION_SECURITY.md) | Crypto primitive contract, threat model, pairing/`PAIRING.md`, retracted claims, anti-rollback. |
| [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) | Therapist flows: invite, OOB pairing, share viewing, game-plan authoring, non-diagnostic framing. |
| [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) | Docker Compose, reverse-proxy/trusted-proxy hardening, egress lockdown, WebAuthn RP-ID config. |
| [../PRIVACY.md](../PRIVACY.md) | The flagship app's offline, no-network privacy posture (unchanged by the Companion design). |
| [../SECURITY.md](../SECURITY.md) | Project-wide security reporting and posture. |

---

*This document describes a design under review. See the banner at the top: **no
Companion code exists yet.***
