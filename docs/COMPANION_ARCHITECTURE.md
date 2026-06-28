# Daymark Companion — Architecture

> ## ⚠️ STATUS: DESIGN ONLY — NO CODE EXISTS YET
>
> This document describes a **target architecture** for the Daymark Companion's optional,
> self-hosted, two-party (owner + therapist) model. **None of it is implemented.** There is no
> Companion container, no Sync flavor, no portal, and no `game_plans` table in the shipping app
> today. Everything below is a build-ready design contract to be reviewed, sequenced, and built —
> not a description of working software. Where this doc states a guarantee, treat it as a
> *requirement on the eventual implementation*, not a claim about current behavior.
>
> This document also **supersedes and corrects** several earlier informal design notes. Where an
> older note claimed forward secrecy for sealed-box shares, an AES-GCM "equivalent" for sync,
> server-side revocation that defeats a colluding server, or a zero-knowledge browser portal, those
> claims are **retracted here** and the honest property is stated instead. See
> [§9 Retractions & Honest Limits](#9-retractions--honest-limits).

**Sibling documents** (relative links): [COMPANION_SCOPE.md](COMPANION_SCOPE.md) ·
[COMPANION_SECURITY.md](COMPANION_SECURITY.md) · [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) ·
[COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) · plus the pairing protocol spec
**PAIRING.md** (named deliverable, see [§5.2](#52-pairing-is-mandatory-bidirectional-and-out-of-band)).
Baseline context: [DOCKER_COMPANION.md](DOCKER_COMPANION.md), [ARCHITECTURE.md](ARCHITECTURE.md),
[PRIVACY.md](PRIVACY.md), [HANDOFF.md](../HANDOFF.md).

---

## 1. Overview

The target architecture extends the existing single-user, zero-knowledge Daymark Companion to a
**curated, two-party model (owner + therapist)** *without* giving the server any plaintext or any
long-term key material.

The existing spine is preserved **unchanged**:

- The phone **Sync flavor** encrypts the versioned `BackupData` JSON snapshot with a
  passphrase-derived symmetric key (**Argon2id → XChaCha20-Poly1305**) and `PUT`s **append-only**
  ciphertext blobs.
- The server remains a **dumb ciphertext blob host** with a SQLite metadata index (no plaintext
  columns) and a static web portal that decrypts **in-browser**.

The **second party** (the therapist) is grafted on with **asymmetric** cryptography — *not* by
sharing the owner's symmetric passphrase. Each party holds an **X25519** (encryption) +
**Ed25519** (signing) keypair generated **in-browser / on-device** and **never** sent to the
server.

- **Therapist access is a SHARE.** The owner selects a curated subset of records, serializes a
  self-contained bundle, **signs it (Ed25519)**, envelope-encrypts it (random per-share XChaCha20
  CEK, the CEK wrapped to the therapist's X25519 key), and uploads it as another opaque blob.
- **Game plans flow back the same way in reverse.** The therapist authors a plan, **signs it
  (Ed25519)**, encrypts it to the **owner's** X25519 key, and stores the result as a ciphertext
  blob. The owner's app/portal decrypts, **verifies the therapist signature against a pinned
  fingerprint**, and surfaces it as a read-only `PROPOSED` item the owner explicitly accepts.

The server at no point holds a symmetric content key, a private key, a passphrase, or any
plaintext. A full server compromise leaks only **opaque blobs plus routing metadata** (sizes,
timestamps, key fingerprints, expiry). The owner's symmetric sync passphrase is **never** shared
with the therapist — the share is a separate, recipient-encrypted artifact — so granting therapist
access does **not** widen who can read the owner's full backup.

This keeps the app's core promise intact: the **default phone build still has no `INTERNET`
permission**, sync stays an **opt-in separate flavor**, and the companion remains
**non-diagnostic / not a medical device**.

> **V1 scope lock:** one therapist, one keypair, one device. Multi-therapist / multi-device fan-out
> and a therapist practice dashboard are **out of scope** and documented as added trust surface in
> [COMPANION_SCOPE.md](COMPANION_SCOPE.md). The owner is the sole root of trust.

---

## 2. Parties (ASCII diagram)

```
        TRUSTED ENDPOINTS (owner-controlled)                 SECOND PARTY (owner-granted, curated)
 ┌──────────────────────────────────────────────┐        ┌────────────────────────────────────────┐
 │                                                │        │                                          │
 │  ┌────────────────────────┐                    │        │   ┌──────────────────────────────────┐  │
 │  │ Phone — CORE build      │  no INTERNET       │        │   │ Therapist browser / portal session│  │
 │  │ (flagship, default)     │  fully offline     │        │   │  - X25519 (read) + Ed25519 (sign) │  │
 │  │  - local SQLite (Room)  │  plaintext, local  │        │   │    keypair, generated in-browser, │  │
 │  │  - source of truth      │                    │        │   │    wrapped at rest (PRF/passphrase)│  │
 │  └────────────────────────┘                    │        │   │  - decrypts SHARE in memory only  │  │
 │                                                │        │   │  - authors + SIGNS game plans     │  │
 │  ┌────────────────────────┐                    │        │   └──────────────────────────────────┘  │
 │  │ Phone — SYNC flavor     │  +INTERNET (opt-in)│        │                    │                     │
 │  │  - owner passphrase      │                    │        │     pinned therapist fp ◀──┐            │
 │  │  - owner X25519+Ed25519 │                    │        └──────────────────────│──────────────────┘
 │  │  - encrypt snapshot      │                    │                               │  OOB SAS / QR
 │  │  - author SHARES         │                    │      pinned owner fp ◀────────┘  (BOTH directions)
 │  │  - verify GAME PLANS     │                    │                               │
 │  └────────────────────────┘                    │                               │
 │                                                │                               │
 │  ┌────────────────────────┐                    │                               │
 │  │ Owner browser (sit-down)│  transient secrets │                               │
 │  │  - report viewer         │  in memory only    │                               │
 │  │  - (lower-assurance path)│                    │                               │
 │  └────────────────────────┘                    │                               │
 └──────────────────────────────────────────────┘                               │
              │                  │                                                │
   ciphertext │ (HTTPS)          │ ciphertext (HTTPS)                ciphertext   │ (HTTPS)
              ▼                  ▼                                                ▼
 ┌───────────────────────────────────────────────────────────────────────────────────────────┐
 │  COMPANION CONTAINER (UNTRUSTED for confidentiality — zero-knowledge)                       │
 │                                                                                             │
 │   ┌──────────────┐   ┌───────────────────────────┐   ┌────────────────────────────────┐    │
 │   │ API (Ktor)   │   │ blob store + metadata index│   │ static web portal assets       │    │
 │   │ auth/rate/   │   │  - 1 ciphertext blob/artifact│  │  - vendored HTML/JS/WASM       │    │
 │   │ caps/expiry/ │◀─▶│  - SQLite: NO plaintext cols │  │  - strict CSP, no CDN          │    │
 │   │ revoke flags │   │  - sizes,fps,expiry,hashes   │  │  - all crypto client-side      │    │
 │   └──────────────┘   └───────────────────────────┘   └────────────────────────────────┘    │
 │   non-root · read-only FS except blob volume · NO outbound network                          │
 │                                                                                             │
 │   Sees: opaque blobs + routing metadata.  Never sees: passphrase, private key, CEK, plaintext│
 └───────────────────────────────────────────────────────────────────────────────────────────┘
                          ▲
                          │  the reverse proxy is multi-homed onto the companion's OWN
                          │  internal:true bridge (no gateway path); default trust-NO
                          │  forwarded headers (see COMPANION_DEPLOYMENT.md).
```

---

## 3. Components & trust levels

| Component | Responsibility | Trust level |
|---|---|---|
| **Phone — Daymark core build (default)** | Flagship app. **No `INTERNET`**, fully offline, local SQLite (Room). Source of truth for the owner's data. **Unchanged** by this design — neither syncs nor talks to the companion. | **Trusted endpoint** (owner-controlled). Holds plaintext. Endpoint security is the user's; out of scope for the server threat model. |
| **Phone — Daymark Sync flavor (opt-in)** | Separate flavor that adds `INTERNET` only when deliberately installed. Derives the symmetric sync key (Argon2id + stored salt), encrypts the snapshot, `PUT`s append-only ciphertext. Holds/generates the **owner** X25519+Ed25519 keypair to author shares and decrypt+verify game plans on-device. Surfaces verified game plans into the new `game_plans` table. | **Trusted endpoint** (owner-controlled). Holds owner passphrase, owner private keys, plaintext. **Never** uploads keys or passphrase. |
| **Owner browser (sit-down portal client)** | Static web app served by the container. Decrypts snapshots and renders reports; can author shares and read game plans on the big screen. Crypto runs in-browser (WebCrypto / libsodium-WASM). | **Trusted endpoint** (owner-controlled) **but lower-assurance** — see [§9.4](#94-the-browser-portal-is-not-zero-knowledge-against-a-malicious-server). Secrets held transiently in memory; entering the **master passphrase** here is **discouraged**. |
| **Companion container — API (Ktor)** | Auth, rate limiting, lockout, store/list/fetch opaque blobs, enforce blob/version/size caps + per-token quota, share + game-plan endpoints, expiry/revocation flags, serve the static portal. Non-root, read-only FS except blob volume, **no outbound network**. | **UNTRUSTED for confidentiality** (zero-knowledge): never holds keys, passphrase, or plaintext. **Semi-trusted for availability/integrity only** as a convenience replica — can withhold/serve stale blobs but cannot read or forge content (AEAD + signatures detect tampering). |
| **Companion container — blob store + metadata index** | One ciphertext blob per artifact (snapshot version, share bundle, game plan). SQLite index of **non-secret metadata only**: blob id, type, device label, createdAt, size, server-computed content hash, recipient key fingerprint, share expiry, revocation flag, monotonic version. **No plaintext columns, ever.** | **Untrusted for confidentiality.** Disk theft leaks only opaque blobs + routing metadata. |
| **Companion container — static web portal assets** | Vendored static HTML/JS/WASM (no CDN, strict CSP, no third-party origins). Hosts owner report viewer + therapist share-viewer / game-plan UI. All crypto client-side. | **Server-delivered code ⇒ integrity depends on the server and TLS.** **NOT zero-knowledge** against a malicious operator (SRI/CSP are inert here — same origin serves HTML and assets). A lower-assurance convenience path, not a security boundary. |
| **Therapist browser / portal session** | Generates and holds the **therapist** X25519 (decrypt) + Ed25519 (sign) keypair, wrapped at rest under a WebAuthn-PRF / passphrase-derived key. Authenticates (MFA-gated), fetches the share blob, decrypts in-browser, renders the curated subset, authors + signs game plans, encrypts them to the owner's key. | **Second party, partially trusted by the OWNER** (deliberate, time-boxed, revocable, curated view). **Untrusted by the server** for confidentiality. Sees only the curated share plaintext after in-browser decrypt — never the owner's full backup or passphrase. |

> **Owner identity is a first-class pinned trust anchor**, symmetric with the therapist's. The owner
> Ed25519 key is used for **both** share-signing and game-plan verification. See
> [§5.2](#52-pairing-is-mandatory-bidirectional-and-out-of-band).

---

## 4. The four core data flows

Each flow is described with its endpoints, payload, and exact cryptographic protection. The crypto
primitive contract is **single and cross-party** ([§5.1](#51-one-crypto-primitive-set-everywhere)).

### Flow summary table

| # | Flow | From → To | Payload | Primary protection |
|---|---|---|---|---|
| **A** | Owner snapshot sync (baseline) | Phone Sync flavor → API → blob store | Full `BackupData` JSON snapshot | XChaCha20-Poly1305 under Argon2id-derived key; Ed25519-signed hash-chained manifest |
| **B** | Owner → therapist share | Phone/Owner browser → API → blob store | Curated **subset** bundle | Per-share XChaCha20 CEK; CEK `crypto_box_seal`-wrapped to therapist X25519; **owner Ed25519 signature** over the bundle |
| **C** | Therapist reads share | Therapist browser → API → fetch blob | Auth + share ciphertext fetch | WebAuthn-bound capability token; in-browser unseal + AEAD decrypt; server expiry/revoke gate |
| **D** | Therapist → owner game plan | Therapist browser → API → blob store → owner | Signed game-plan object | Ed25519 sign **then** `crypto_box_seal` to owner X25519; owner verifies signature vs pinned fp before surfacing |

> **Sync merge note (descoped):** v1 sync is **single-writer, last-snapshot-wins** replication
> (newest full snapshot is authoritative; older device pulls and replaces; append-only history
> preserved server-side). It is **not** row-level three-way merge. The codebase has no
> `updatedAt`/`lastModified` columns, `entry_activity` is keyed on raw rowids, and `importMerge` is
> insert-only fresh-id-remap — so deterministic per-row merge is **not implementable** and would
> corrupt the entry↔activity join and photo associations. True multi-device row-merge is **gated
> behind a prerequisite schema migration** that adds stable cross-device UUIDs and real `updatedAt`
> columns to every synced table and re-keys `entry_activity`/tombstones on UUID. Deferred; never
> claimed as working in v1. See [COMPANION_SECURITY.md](COMPANION_SECURITY.md).

---

### Flow A — Owner snapshot sync (unchanged baseline)

```
Phone (Sync flavor)  ──PUT /v1/snapshots/{version} (opaque blob)──▶  API (Ktor) ──▶ blob store
                     ◀──list/fetch ciphertext + signed manifest───
```

- **Payload:** versioned `BackupData` JSON snapshot (full owner dataset; photos ride inside the
  encrypted blob).
- **Symmetric AEAD:** **XChaCha20-Poly1305** (`crypto_aead_xchacha20poly1305_ietf`), **24-byte
  random nonce per blob**, key derived via **Argon2id** (≥256 MiB, ops≥4, p=1) from the owner
  passphrase + stored random salt. The passphrase **never** leaves the device.
- **Purpose-separated subkeys** via `crypto_kdf` (BLAKE2b) from one master: `content_key`,
  `manifest_sign_seed` (→ Ed25519), `device_label_key`. The master never both encrypts and signs.
- **AAD binding:** the plaintext blob header (`version`, `schemaVersion`, `deviceId`, `prevHash`,
  `createdAt`) is fed as AEAD AAD, so the server cannot relabel/reorder a ciphertext without
  breaking the Poly1305 tag.
- **Append-only, monotonic version + hash chain.** Each blob carries a `prevHash`; the owner
  publishes an **Ed25519-signed, hash-chained manifest** (signing key derived from the passphrase,
  so the server cannot forge it).
- **Anti-rollback is CLIENT-anchored.** Server-side prevHash/version checks are **DoS hygiene
  only** and provide **zero** integrity guarantee (a hostile server forges a consistent chain over
  client-supplied metadata). The only real anti-rollback is the signed manifest verified against a
  **local trust watermark on the phone** that sync refuses to regress past. The client never trusts
  server list/prevHash/log output without manifest verification.
- TLS in transit (defense in depth; the payload is already E2EE).

> See [COMPANION_SECURITY.md](COMPANION_SECURITY.md) for the salt bootstrap (keycheck token +
> TOFU-pinned manifest pubkey), conflict handling, prune policy, and the full anti-rollback
> argument.

---

### Flow B — Owner creates a therapist share

```
Phone Sync flavor  ──┐
   OR                 ├─PUT /shares/{id}/v{n} (opaque blob + sealed CEK + meta)─▶ API ─▶ blob store
Owner browser ───────┘
```

- **Payload:** a **curated, self-contained `ShareBundle`** — a hand-selected subset (e.g. a date
  range of moods + chosen journal entries + check-in **scores/bands only**). **NOT** the full
  backup. The PHQ-9 self-harm item is **structurally absent** (never persisted).
- **Envelope encryption (hybrid):**
  - A fresh random 256-bit **CEK** encrypts the bundle under XChaCha20-Poly1305 (24-byte random
    nonce).
  - Only the 32-byte CEK is **wrapped to the therapist's pinned X25519 key** via
    `crypto_box_seal`. (Sealing only the CEK — not the whole bundle — means refresh re-seals 32
    bytes, not megabytes.)
- **Owner Ed25519 signature is MANDATORY.** `crypto_box_seal` is **anonymous**, so the owner
  **signs the bundle** with their owner signing key. This closes the content-injection break where
  a hostile server (holding the therapist pubkey) could fabricate a fully valid share with
  attacker-chosen "clinical" content.
- **AAD / transcript binding:** the AEAD AAD binds
  `shareId || version || recipientFp || expiry || ownerSigningFp`, so a stolen wrapped-CEK cannot be
  spliced onto a different ciphertext, and the bundle is bound to its intended recipient and owner
  identity.
- **Server stores** `nonce||ciphertext`, the sealed CEK, and metadata (recipient fingerprint,
  expiry, revocation handle, monotonic version, server-computed content hash). It can decrypt
  **none** of it.
- **Data minimization is a crypto-layer control, not UI etiquette.** Because the bundle is
  materialized, even a buggy or hostile portal cannot leak more than what was sealed in.

> **No forward secrecy.** The sealed box gives **confidentiality + sender anonymity, NOT recipient
> forward secrecy.** One compromise of the therapist's long-term X25519 key retroactively decrypts
> every blob ever sealed to it, and the server retains those blobs. CEK rotation does **not**
> mitigate this (all versions seal to the same long-term key). Mitigation is **bounded server-side
> blob retention** (configurable TTL + hard-delete of superseded/expired bytes) to make the
> harvest-now-decrypt-later window **finite**. See [§9.1](#91-forward-secrecy-claim-retracted) and
> [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md).

---

### Flow C — Therapist authenticates and reads the share

```
Therapist browser ──auth (WebAuthn-PRF / TOTP)──▶ API
                  ──GET /shares/{id}/current────▶ API ──(expiry? revoked? token+session bound?)──▶ blob
                  ◀──nonce||ciphertext + sealed CEK──
   in-browser: unwrap therapist secret key (PRF/passphrase) → crypto_box_seal_open → AEAD decrypt
               → VERIFY owner Ed25519 signature vs PINNED owner fp → render (in memory only)
```

- **Server-side gates (who can fetch):** MFA-gated credential (**WebAuthn passkey** primary; TOTP
  fallback) **plus** a per-share 256-bit **capability token** stored only as a BLAKE2b hash. The
  **capability token MUST be bound to the authenticated WebAuthn credential at first fetch** (no
  single-factor bearer fetch). **Step-up assertions** for sensitive actions
  (`share.open`, `gameplan.publish`, `key.rotate`, `revoke`) MUST be bound to the **active, live,
  non-revoked session id** — an assertion minted in one context cannot be executed from a stolen
  session. Rate-limit + lockout on failures.
- **Client-side gate (who can read):** the therapist unwraps their in-browser private key, unseals
  the CEK, and AEAD-decrypts. **Plaintext exists only in browser memory** (thin viewer, no default
  disk cache).
- **Owner authentication on read:** the therapist **MUST verify the owner's Ed25519 signature** over
  the bundle against the owner's **out-of-band-pinned** fingerprint before rendering any record.
  `recipientFp` inside the payload is necessary but **not sufficient** (an attacker can set it) — the
  binding step is the OOB pin.
- **Expiry + revocation** enforced server-side (refuse `403`) using only metadata. **Honestly
  scoped** — see [§9.2](#92-revocation-is-honest-server-only).
- **Same non-diagnostic framing** as the app: the share viewer shows "self-check, not a diagnosis;
  scores are not clinical thresholds." Bands/scores are never presented as clinical cutoffs.

---

### Flow D — Therapist writes a game plan back to the owner

```
Therapist browser:  canonicalize → Ed25519 SIGN payload → crypto_box_seal to OWNER X25519
                    ──POST /plans (opaque blob + non-secret meta)──▶ API ─▶ blob store
Owner (phone/browser): ──GET /plans?since=v── fetch blob
                       open seal w/ owner X25519 → VERIFY Ed25519 sig vs PINNED therapist fp
                       → check context + recipientOwnerFp + monotonic version
                       → show as PROPOSED (read-only) → owner ACCEPTS → game_plans table
```

- **Payload:** therapist-authored, **non-diagnostic** guidance (goals, exercises, between-session
  tasks, notes, review cadence) targeting the owner.
- **Sign-then-encrypt:** (1) the therapist **Ed25519-signs the canonical plaintext**
  (authorship + integrity, non-repudiable to the owner); (2) the signed envelope is
  `crypto_box_seal`-encrypted to the **owner's X25519** key.
- **Domain separation against surreptitious forwarding:** the signed payload binds
  `recipientOwnerFp`, `lineageId`, `version`, and a fixed `context: "daymark.gameplan.v1"`. The
  owner checks recipient == self and context match **in addition to** the Ed25519 signature.
- **Owner verifies before surfacing.** A tampered or wrong-author plan fails verification and is
  rejected — the untrusted server cannot inject guidance. A signature that verifies under a
  *different* key (server key substitution) is rejected as hostile via the **pinned fingerprint**
  check; never auto-accepted.
- **Mandatory `PROPOSED` accept gate.** Verified plans are never silently written. The owner
  explicitly accepts; only then is the plan inserted (read-only) into `game_plans`.
- **Segregated landing:** plans land in a **new `game_plans` table**, **NOT** the existing
  `treatments` table (see [§6](#6-game-plans-data-model-decision)).

---

## 5. Key architectural decisions

### 5.1 One crypto primitive set, everywhere

A **single, cross-party** primitive contract, defined once and used identically on JVM (phone,
lazysodium) and browser (libsodium-WASM / WebCrypto):

| Purpose | Primitive | libsodium call |
|---|---|---|
| Symmetric AEAD (everywhere) | **XChaCha20-Poly1305**, 24-byte random nonce | `crypto_aead_xchacha20poly1305_ietf_*` |
| KDF (client-side only) | **Argon2id**, ≥256 MiB, ops≥4, p=1 | `crypto_pwhash` |
| Subkey separation | **BLAKE2b** purpose-separated derivation | `crypto_kdf_derive_from_key` |
| Encryption keypair (both parties) | **X25519** | `crypto_box_keypair` |
| Signing keypair (both parties) | **Ed25519** | `crypto_sign_keypair` |
| CEK wrap / sealed box | **`crypto_box_seal`** (anonymous) | `crypto_box_seal` / `_open` |
| Fingerprint | **BLAKE2b-256** over the 32-byte pubkey | `crypto_generichash` |

- **XChaCha20-Poly1305 is MANDATORY everywhere.** The 192-bit random nonce makes reuse
  statistically negligible across many uncoordinated offline devices sharing one `content_key`.
- **AES-256-GCM is REMOVED** as a documented "equivalent" for the long-lived append-only `SYNC_KEY`
  path: 96-bit random GCM nonces under one indefinitely-reused key hit **birthday-bound reuse**,
  which is catastrophic.
- **Argon2id is the only KDF, client-side only.** No server-side passphrase hashing on the
  zero-knowledge path. (The one exception — the TOTP-fallback therapist's wrapping secret — is
  honestly flagged as a weaker parallel custody path in
  [COMPANION_SECURITY.md](COMPANION_SECURITY.md).)

> A startup known-answer self-test runs on both JVM and WASM (round-trip a fixed vector, golden
> cross-runtime canonicalization tests) so a broken WASM build or JNI mismatch **fails fast** rather
> than silently weakening confidentiality.

### 5.2 Pairing is mandatory, bidirectional, and out-of-band

- **TOFU pinning is required in BOTH directions before any payload flows.** The owner verifies the
  therapist's X25519+Ed25519 fingerprints, **and** the therapist verifies the owner's X25519
  (encryption) + Ed25519 (signing) fingerprints.
- Verification uses a **short authentication string** (4–6 word BLAKE2b code / QR) read
  **out-of-band** (aloud at a session, scanned in person).
- The **server NEVER vouches for keys** and never mediates the encryption key unverified.
  `recipientFp` inside a signed payload is necessary but not sufficient (an attacker can set it), so
  the **OOB SAS comparison is the binding step**.
- The pairing/key-exchange protocol is a **named deliverable, PAIRING.md** — not hand-waved as
  "(out-of-band)". Keypairs are generated in-browser/on-device and **never uploaded**; only public
  keys + fingerprints + the WebAuthn credential pubkey reach the server.

### 5.3 Asymmetric second party, not passphrase sharing

The therapist gets an X25519/Ed25519 keypair; shares are encrypted to their public key and game
plans signed by them + encrypted to the owner's key. This is what lets a second reader exist while
the server stays zero-knowledge **and** the owner's symmetric sync passphrase stays private —
granting therapist access never unlocks the full backup. **No key escrow** (consistent with the
existing "lose the passphrase = unrecoverable" posture).

### 5.4 Reuse the spine verbatim — shares & plans are just blob types

Append-only ciphertext blobs, SQLite metadata index with no plaintext columns, static
in-browser-decrypting portal, Ktor as a dumb blob host, non-root / read-only / no-egress container.
Shares and game plans are additional blob **types** with extra non-secret routing metadata —
**no new server trust is introduced.**

### 5.5 Token holders are an integrity/availability weapon — quota and validate

A bearer/cap-token holder (no E2EE key needed) can `PUT` garbage versions to evict real history
(append-only-prune eviction DoS) and exhaust disk. Mitigations baked into the API:

- per-token storage quota + disk-full **fail-closed** handling;
- **server-derived** (never client-supplied) `blob_path`, validated against a strict charset
  (closes path-traversal on `DELETE`/store);
- **server-side content hashing** over the opaque blob (client `X-Content-Hash` is untrusted);
- caps + rate-limits on version + lineage creation (prevents monotonic-poisoning like
  `X-Plan-Version=MAXINT` lineage-lock, and `PROPOSED`-queue flooding);
- **prune executes only after** the client confirms a newer durable version exists.

### 5.6 Reverse-proxy / forwarded-header hardening (one policy, all flows)

- **Default is trust-NO forwarded headers** (use the socket peer). The operator must explicitly pin
  a **single narrow** trusted-proxy IP/CIDR. The shipped `172.16.0.0/12` default is **REMOVED** (it
  lets any co-resident Docker container forge `X-Forwarded-*`).
- The proxy **strips inbound** `X-Forwarded-For` / `X-Real-IP` / `X-Forwarded-Host` / `Forwarded`
  before setting its own.
- Rate-limit identity, lockout key, audit source-IP, and the "HTTPS-required-for-non-loopback" gate
  derive from forwarded headers **only when the pinned proxy is trusted**, else from the socket
  peer.
- **WebAuthn RP-ID / origin is config-pinned**, never client-derived: `rp.id` and the origin
  allowlist come from explicit `DAYMARK_WEBAUTHN_RP_ID` / origin config, **not** from
  client-controllable `Host` / `X-Forwarded-Host`. Sub-path deployment: proxy strips the prefix, app
  re-adds via `DAYMARK_BASE_PATH`; cookie Path / CSP / RP-ID computed from config; assertion origin
  verification is exact.

> Full proxy + egress + sub-path specifics live in
> [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md). **Egress lockdown:** the companion sits on its
> **own `internal:true` bridge** that the proxy also joins (proxy multi-homed), so the companion has
> no path to the gateway — otherwise the "structurally enforced no-egress" wording is dropped in
> favor of a host-firewall egress-deny. The flagship F-Droid build remains provably network-free
> (no `INTERNET`); all server talk stays in the opt-in Sync flavor.

### 5.7 Default phone build untouched

All second-party functionality lives in the **opt-in Sync flavor** and the self-hosted container.
The app's verifiable network-free privacy claim is preserved.

---

## 6. Game plans data model (decision)

Game plans go in a **NEW, segregated `game_plans` table (+ `game_plan_items`,
`game_plan_progress`) at DB v13** — **NOT** the existing `treatments` table.

**Why not `treatments`:** the existing `treatments` table is confirmed in code (`Treatment.kt`) as
an **owner-authored, explicitly non-evaluative sleep-marker** (`KINDS = CPAP / Surgery / Oral
appliance / Positional therapy / Medication / Other`) whose KDoc says it is "never a measure of
whether the treatment works." Writing therapist-authored clinical guidance into its free-text
`note` would:

- let the owner silently edit therapist-authored content (breaks read-only segregation),
- destroy provenance and signature verifiability on backup round-trip,
- misrepresent the schema and **violate HANDOFF.md §0** (the prime directive: non-diagnostic, no
  externally-authored clinical content).

**Resolution & layering:**

| Layer | Table | Author | Mutability |
|---|---|---|---|
| Therapist guidance body | `game_plans`, `game_plan_items` | Therapist (signed) | **Immutable / read-only / append-only** (supersede, never mutate) |
| Owner reactions | `game_plan_progress` keyed by `(lineageId, itemRef)` | Owner | Owner-authored; the only place the owner writes for plans |

- A plan **MAY optionally** spawn an owner-authored `Treatment`/`Goal` marker **only on explicit
  owner accept** — guidance never auto-lands in owner data.
- Progress keyed by `(lineageId, itemRef)` carries forward across version bumps.
- The verbatim `canonicalPayloadJson` + signature + author fingerprint are retained (and embedded in
  the owner's `BackupData` v13 snapshot) so plans remain **re-verifiable forever**, including after
  restore on a new device.
- Embedding plans in the owner snapshot does **not** widen backup readership (plans are already
  owner-readable post-decrypt) and does **not** expose the sync passphrase — the two crypto channels
  stay independent.

> Full schema, migration `12→13`, inbound verify/accept state machine, and threat model are in
> [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md).

---

## 7. Metadata minimization (requirement, not an open question)

`recipient_fp` is a **stable cross-owner correlator** that can reconstruct a therapist's whole
patient panel (caseload re-identification) — a risk to the therapist's **other** patients, not just
one owner. Required mitigations:

- **Remove `recipientFp` / owner fp from query strings**; use **opaque per-relationship inbox
  tokens** for routing.
- **Pad blob sizes to fixed buckets BY DEFAULT** (not optional) — closes the per-version size-delta
  acuity proxy and the `isTombstone + size` withdrawal de-anonymization.
- Keep the access log **owner-local** where possible; short-retention when server-stored.
- Minimize `device_label` / timestamp exposure.

The threat-model docs **stop calling this "harmless routing metadata."**

---

## 8. Audit & attestations (honestly scoped)

- The audit log records **EVENTS not CONTENT** (auth, `share_opened`, `version_published`,
  `revoked`, `expired`), is **owner-readable**, metadata-only, with **optional-IP off by default**
  (IP geolocates the clinic) and short retention.
- Therapist-signed `share.open` / `gameplan.publish` **attestations prevent FORGERY but NOT silent
  SUPPRESSION / reordering / truncation** by the server — without a hash-chain or sequence number, a
  hostile server can simply never return an attestation and the owner cannot detect the gap.
- **FIX:** add a **signed monotonic sequence number / hash-chain** to attestations so omitted events
  are detectable. **Until that ships, the "access cannot be hidden" claim is RETRACTED.** See
  [§9.5](#95-attestation-suppression).

---

## 9. Retractions & honest limits

This section consolidates claims that earlier informal notes got wrong. Each is corrected loudly so
the honest property is unambiguous.

### 9.1 Forward-secrecy claim retracted

`crypto_box_seal` / X25519 sealed box provides **sender anonymity ONLY, NOT recipient forward
secrecy.** The phrase "ephemeral sender = forward secrecy" is **struck everywhere**. The honest
property is **"confidentiality + sender anonymity, no PFS."** Consequence: one compromise of the
recipient long-term X25519 key (therapist for shares, owner for game plans) **retroactively
decrypts every blob ever sealed to it**, and the zero-knowledge server retains those blobs
append-only. CEK rotation does **not** mitigate (all versions seal to the same long-term key).
**Mitigation:** server-side blob retention for shares/game-plans is **BOUNDED** (configurable TTL +
hard-delete of superseded/expired bytes) to make the harvest-now-decrypt-later window finite.

### 9.2 Revocation is honest-server-only

Server-side expiry + revoke-flag stop **FUTURE fetches against an HONEST server only.** Because the
wrapped CEK persists on disk and the therapist long-term key never rotates on revoke, an
already-pushed (even not-yet-decrypted) share **remains readable to the therapist key against a
malicious/colluding server.** The "CEK-rotation defeats a colluding server" claim is **RETRACTED.**
Real future-data revocation requires therapist **RE-KEYING** (re-pair to a new verified key); CEK
rotation only protects data published *after* rotation and only against the *old* key.

**Three honest guarantees** (the only ones claimed):

1. Future server-mediated fetches blocked **on an honest server**;
2. Data published **after** re-key is unreadable to the old key;
3. Already-decrypted plaintext is never recallable (same as handing someone a PDF).

A thin in-memory therapist viewer (no default disk cache) shrinks the residue to **one session**.

### 9.3 Share integrity requires owner signing

`crypto_box_seal` is **anonymous**, so the owner **MUST Ed25519-sign** every share bundle, and the
therapist **MUST verify** that signature against the owner's **OOB-pinned** Ed25519 fingerprint
before rendering. This kills the attack where a hostile server (holding the therapist pubkey)
fabricates a fully valid share with attacker-chosen "clinical" content. The owner Ed25519 key is now
a **first-class pinned trust anchor**, symmetric with the therapist's.

### 9.4 The browser portal is NOT zero-knowledge against a malicious server

SRI/CSP are **INERT** here: the same first-party origin serves both the SRI-referencing HTML and the
assets, so a hostile operator rewrites both together. The headline "full server compromise leaks
only opaque ciphertext" is **FALSE for anyone who types the passphrase (owner) or unlocks the
reading key (therapist) in the server-served portal** — and worse for the therapist because the
PRF-derived KUK is deterministic, so a single tampered-JS capture yields **permanent offline
decryption.**

**Resolution:**

1. the **native phone Sync flavor is the ONLY secret-handling owner path**; entering the master
   passphrase into the browser portal is **forbidden / strongly discouraged** in product copy;
2. the therapist decrypt path moves toward a **pinned/installed client** OR the portal bundle is
   **pinned out-of-band**, and `prfSalt` is **rotatable** so one capture is not forever;
3. **SRI is dropped** as a stated mitigation against tampered portal JS; the browser portal is
   documented as a **lower-assurance convenience path**, not a zero-knowledge one.

### 9.5 Attestation suppression

Attestations prevent forgery but not silent suppression/reordering/truncation. Until a signed
monotonic chain ships, the **"access cannot be hidden" claim is retracted** ([§8](#8-audit--attestations-honestly-scoped)).

### 9.6 Diagnostic-claim creep is policed at every new surface

The therapist share viewer and game-plan UI MUST carry the same **"self-check, not a diagnosis;
scores are not clinical thresholds"** framing as the app; bands/scores are not presented as clinical
cutoffs. Free-text game-plan bodies can contain content the schema cannot constrain, so
"non-diagnostic by construction" is downgraded to **"non-diagnostic by FRAMING"** with explicit UI
disclaimers and a documented product boundary (*this is guidance from your real clinician; the app
is not practicing medicine*). The synced bundle deliberately keeps item-9 / self-harm scoring
**structurally absent**. [PRIVACY.md](PRIVACY.md) / [COMPANION_SECURITY.md](COMPANION_SECURITY.md)
retract "there is no server, so there is no server-side surface" (now false for the opt-in Sync
flavor) and state the owner→therapist **plaintext egress to a third human party** honestly.

### Quick reference

| Component delivered by the server | Zero-knowledge against a malicious operator? |
|---|---|
| Opaque blob bytes at rest | **Yes** (AEAD; server has no key) |
| Routing metadata (fps, sizes, expiry) | **No** — leaks the relationship graph; minimized + padded, not hidden |
| Native phone Sync flavor crypto | **Yes** — native code, not server-delivered |
| Browser portal crypto (typed-secret paths) | **No** — server-delivered JS can be tampered ([§9.4](#94-the-browser-portal-is-not-zero-knowledge-against-a-malicious-server)) |

---

## 10. Open questions (maintainer decisions)

These are **not yet decided** and affect sequencing/scope:

1. **Revocation vs a colluding server:** ship therapist **re-keying** as the real revocation
   primitive in v1, or accept and loudly document v1 revocation as honest-server-only +
   future-data-via-rotation? (Re-keying adds owner-side re-wrap + re-verification friction.)
2. **Browser portal therapist path:** is an installed/pinned therapist client (or OOB bundle pinning
   + rotatable `prfSalt`) in scope for v1, or is the deterministic-KUK tampered-JS break accepted as
   a documented v1 limitation with native-only owner secrets?
3. **Sequencing:** does the first Companion release ship the owner→therapist sharing + game-plans
   tracks **at all**, or land **sync-only first**? The multi-party portal is a step toward the
   clinical posture the project disclaims.
4. **Blob-retention TTL** for shares/game-plans: concrete default (e.g. 30/90 days, or hard-delete
   on supersede/expiry) to bound the no-PFS harvest window — **needs a number.**
5. **Attestation hash-chain:** implement the signed monotonic sequence for suppression-detection in
   v1, or drop the "access cannot be hidden" claim until later?
6. **Size-padding buckets** (e.g. 4 / 16 / 64 KiB) and confirmation that padding is
   mandatory-default for **all** blob types including snapshots — needs concrete buckets.
7. **Sub-path / RP-ID for LAN / `.local` / bare-IP self-hosters:** which WebAuthn-incompatible
   deployments are officially supported vs documented-as-unsupported (WebAuthn requires a real
   origin)?
8. **Schema migration:** require the prerequisite UUID + `updatedAt` migration on the roadmap, or
   keep sync **permanently** single-writer last-snapshot-wins (simpler, but no true concurrent
   multi-device editing)?

---

## 11. Cross-references

| Topic | Document |
|---|---|
| Scope, v1 lock, what's deliberately out | [COMPANION_SCOPE.md](COMPANION_SCOPE.md) |
| Threat model, crypto contract details, anti-rollback, sync internals | [COMPANION_SECURITY.md](COMPANION_SECURITY.md) |
| Sharing + game-plans crypto, schema, inbound state machine, disclaimers | [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) |
| Container, reverse proxy, egress, sub-path, WebAuthn RP-ID config | [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) |
| Pairing / key-exchange protocol (named deliverable) | **PAIRING.md** |
| Existing app architecture, Room schema, migrations | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Baseline companion plan & principles | [DOCKER_COMPANION.md](DOCKER_COMPANION.md) |
| Privacy posture (to be updated for the Sync flavor) | [PRIVACY.md](PRIVACY.md) |
| Prime directive (non-diagnostic, §0) | [HANDOFF.md](../HANDOFF.md) |

---

*Document status: **DESIGN ONLY — no code exists yet.** This is a contract for review and
sequencing, not a description of shipping software.*
