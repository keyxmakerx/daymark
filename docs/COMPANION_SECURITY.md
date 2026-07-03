# Daymark Companion — Security: Multi-Party Threat Model & Hardening

> ## ⚠️ STATUS: DESIGN ONLY — NO CODE EXISTS YET
>
> **Nothing in this document is implemented.** The Daymark Companion is a *design
> proposal*. There is no server binary, no Docker image, no "Daymark Sync" build
> flavor, no therapist portal, and no pairing/sharing/game-plan crypto in the
> shipping app today. Every threat-model row, guarantee, table, ASCII diagram, and
> config snippet below describes an **intended** design still under review. It may
> change or be dropped entirely.
>
> The flagship Daymark app remains **fully offline, declares no `INTERNET`
> permission, and operates 100% on-device** (see [../PRIVACY.md](../PRIVACY.md) and
> [../SECURITY.md](../SECURITY.md)). All second-party functionality described here
> lives **only** in a separate, opt-in **Daymark Sync** flavor and the self-hosted
> Companion container, and never alters that default.
>
> This document folds in the findings of an adversarial security review. Where a
> previously-claimed property was found to be false or overstated, it is **retracted
> in place and loudly** rather than quietly edited — see [Retractions &
> corrections](#0-retractions--corrections-read-this-first).

---

## Contents

- [0. Retractions & corrections (read this first)](#0-retractions--corrections-read-this-first)
- [1. Security objectives (what must hold)](#1-security-objectives-what-must-hold)
- [2. Parties, assets & trust boundaries](#2-parties-assets--trust-boundaries)
- [3. Multi-party threat model](#3-multi-party-threat-model)
- [4. Cryptography & key hierarchy](#4-cryptography--key-hierarchy)
- [5. MFA, key custody & session model](#5-mfa-key-custody--session-model)
- [6. Server hardening defaults](#6-server-hardening-defaults)
- [7. Reverse-proxy / trusted-proxy hardening](#7-reverse-proxy--trusted-proxy-hardening)
- [8. Anti-rollback & integrity (client-anchored)](#8-anti-rollback--integrity-client-anchored)
- [9. Audit-logging posture](#9-audit-logging-posture)
- [10. Hardening checklist (copy-paste)](#10-hardening-checklist-copy-paste)
- [11. Out of scope / honest limits](#11-out-of-scope--honest-limits)
- [Related documents](#related-documents)

---

## 0. Retractions & corrections (read this first)

The original Companion security drafts contained several claims that an adversarial
review found to be **false or materially overstated**. Each is corrected here. If you
have read an earlier draft, **these supersede it.**

| # | Earlier claim | Verdict | Honest replacement |
|---|---|---|---|
| R1 | "AES-256-GCM is a documented equivalent for the sync path." | **REMOVED.** Random 96-bit GCM nonces under one indefinitely-reused `SYNC_KEY` hit birthday-bound reuse — catastrophic for an append-only single-key store. | **XChaCha20-Poly1305 (192-bit random nonce) is MANDATORY everywhere**, on JVM (lazysodium) and in the browser (libsodium-wasm). No GCM "equivalent" for the long-lived key path. |
| R2 | "Ephemeral sender ⇒ forward secrecy" for `crypto_box_seal`. | **FALSE, retracted everywhere.** A sealed box makes the *sender* anonymous; the *recipient* key is long-term. | The honest property is **"confidentiality + sender anonymity, no PFS."** One compromise of the recipient long-term X25519 key retroactively decrypts **every** blob ever sealed to it. Mitigated only by **bounded server retention** (§3 T1, §11). |
| R3 | "CEK rotation defeats a colluding/malicious server's revocation." | **RETRACTED.** The wrapped CEK persists on disk and the therapist key never rotates on revoke. | Revocation stops **future fetches against an *honest* server only.** Real future-data revocation requires **therapist re-keying** (re-pair to a new verified key). See [§11](#11-out-of-scope--honest-limits). |
| R4 | "Owner share is authenticated by `crypto_box_seal`." | **FALSE.** Sealed boxes are anonymous; `ShareBundle.owner` was unauthenticated JSON. A hostile server holding the therapist pubkey could fabricate a fully valid share with attacker-chosen "clinical" content. | The owner **MUST Ed25519-sign every bundle**; the therapist **MUST verify** against the owner's **OOB-pinned** fingerprint before rendering. AAD binds `shareId‖version‖recipientFp‖expiry‖ownerSigningFp`. |
| R5 | "SRI/CSP make the browser portal zero-knowledge against a malicious server." | **FALSE, dropped.** The same first-party origin serves both the SRI-referencing HTML and the assets; a hostile operator rewrites both together. | The browser portal is a **lower-assurance convenience path, not zero-knowledge.** The native phone Sync flavor is the only secret-handling owner path. See [§3 T3](#t3--malicious--compromised-server-the-hardest-party). |
| R6 | "Server-side prevHash/version-chain enforcement provides anti-rollback integrity." | **RETRACTED.** A hostile server forges a consistent chain over client-supplied metadata. | The only real anti-rollback is the **Ed25519-signed, hash-chained manifest verified against a local trust watermark** on the phone. Server chain checks are **DoS hygiene only — zero integrity guarantee.** See [§8](#8-anti-rollback--integrity-client-anchored). |
| R7 | "internal:true structurally enforces no egress (Topology A)." | **FALSE.** The companion shared the egress-capable `edge` network with the proxy. | Put the companion on its **own** `internal: true` bridge that the proxy *also* joins (proxy multi-homed), or drop "structurally enforced" wording and require a host-firewall egress-deny. The flagship F-Droid build remains provably network-free. See [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md). |
| R8 | "There is no server, so there is no server-side surface." | **FALSE for the opt-in Sync flavor.** Owner→therapist sharing is a plaintext egress of curated clinical records to a third human party. | Documented honestly here and in PRIVACY.md/SECURITY.md. The flagship build is unchanged; the Sync flavor holds `INTERNET` and introduces a bounded, opt-in server surface. |
| R9 | "Trust 172.16.0.0/12 as the default proxy CIDR." | **REMOVED.** A /12 lets any co-resident Docker container forge `X-Forwarded-*`. | **Default trusts NO forwarded headers** (use socket peer); operator pins a single narrow proxy IP/CIDR. See [§7](#7-reverse-proxy--trusted-proxy-hardening). |
| R10 | "Therapist-authored game plans land in the existing `treatments` table." | **REMOVED.** `Treatment.kt` is an owner-authored, explicitly non-evaluative sleep-marker; writing clinical guidance there violates [HANDOFF.md](../HANDOFF.md) §0. | Game plans go in a **new segregated `game_plans` table** (DB v13). See [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md). |
| R11 | "Deterministic three-way LWW row-merge with per-row `updatedAt`/tombstones." | **NOT IMPLEMENTABLE.** No `updatedAt` column exists anywhere; every entity is `@PrimaryKey(autoGenerate=true) Long`; `entry_activity` is keyed on raw rowids. | **v1 sync is SINGLE-WRITER, LAST-SNAPSHOT-WINS replication.** True row-merge is gated behind a prerequisite UUID+`updatedAt` schema migration, deferred. See [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md). |
| R12 | "Therapist-signed attestations make access non-hideable." | **PARTIALLY RETRACTED.** A server-computed monotonic hash-chain now ships (Track T1), making a *stored* entry's tampering/reordering detectable. It is **not** a therapist-signed attestation, so it adds no non-repudiation, and a hostile server can still simply never append an event or truncate the chain. | The "cannot be hidden" claim stays dropped for **withholding**; it is lifted only for **tampering/reordering of returned entries**. See [§9](#9-audit-logging-posture). |

---

## 1. Security objectives (what must hold)

| # | Invariant | Why it matters |
|---|---|---|
| **O1** | Server breach (process + disk) leaks **only opaque ciphertext + non-secret routing metadata** — and that metadata is itself minimized (§3 T1). | Core zero-knowledge promise, honestly bounded. |
| **O2** | The owner's symmetric **sync passphrase is never shared** with the therapist or server. Granting therapist read access never widens who can read the full backup. | Least authority across parties. |
| **O3** | The therapist reads **only the curated subset**, only after MFA, only until expiry — and the owner can block **future honest-server fetches** instantly. *(Not retroactive; see R3.)* | Owner sovereignty + minimization, honestly scoped. |
| **O4** | Game plans reaching the owner are **therapist-signed and owner-verified**; shares reaching the therapist are **owner-signed and therapist-verified**. A hostile server cannot forge or inject content in either direction. | Bidirectional integrity (R4). |
| **O5** | The default phone build makes **zero outbound calls** and declares no `INTERNET`. The Companion container's no-egress posture is enforced by network topology *and/or* host firewall (R7). | No telemetry. |
| **O6** | **No key escrow anywhere.** Lost passphrase / lost therapist key → unrecoverable by design, documented loudly. | Honest, no-secret-on-server posture. |
| **O7** | The phone-local Room DB is the **source of truth**; the server is a convenience replica that can withhold but never read or author. | Append-only, never destructive. |

---

## 2. Parties, assets & trust boundaries

```
        TRUSTED ENDPOINTS (out of server threat model)        UNTRUSTED FOR CONFIDENTIALITY
 ┌───────────────────────────┐  ┌──────────────────────────┐  ┌──────────────────────────────┐
 │ Phone — flagship build     │  │ Phone — Sync flavor       │  │ Companion container (Ktor)    │
 │ no INTERNET, plaintext SoT │  │ owner passphrase + priv   │  │ blob store + SQLite index     │
 └───────────────────────────┘  │ keys; SIGNS+authors shares│  │ static portal (server-served) │
                                 │ verifies+adopts plans     │  │ ───────────────────────────── │
 ┌───────────────────────────┐  └──────────────────────────┘  │ NEVER holds: passphrase, any  │
 │ Therapist installed/pinned │  ┌──────────────────────────┐  │ private key, CEK, plaintext   │
 │ client (preferred)         │  │ Therapist browser portal  │  │ Worst case: withhold / serve  │
 │ RAM-only secrets           │  │ (LOWER ASSURANCE, R5)     │  │ stale/tampered blobs + JS     │
 └───────────────────────────┘  └──────────────────────────┘  └──────────────────────────────┘
            ▲                                ▲                              ▲
            └──── OOB SAS pairing (§5.6) ────┴──── E2EE payload over TLS ───┘
                  BIDIRECTIONAL pinning           (TLS = defense in depth,
                  before ANY payload flows         NOT the confidentiality boundary)
```

**Assets, ranked.** (1) Owner sync passphrase + owner X25519/Ed25519 private keys.
(2) Owner plaintext journal/mood records. (3) Therapist X25519 reading key + Ed25519
signing key. (4) Per-share CEKs. (5) Share-subset / game-plan plaintext on the
endpoint. (6) **Routing metadata** — now treated as sensitive (caseload
re-identification, acuity proxies; see §3 T1). (7) Server auth / capability tokens /
WebAuthn credential public keys.

**Trust boundaries crossed:** endpoint → container (HTTPS; payload already E2EE);
container disk; container → (ideally) nothing outbound; **owner ↔ therapist key
pairing**, which is the only trust anchor and is established **out-of-band and
bidirectionally** (§5.6) — the server never vouches for a key.

---

## 3. Multi-party threat model

Each adversary lists **can**, **cannot (when defenses honored)**, and **defenses**.

### T1 — Stolen server / stolen disk (offline attacker, full storage)

- **Can:** read the entire blob volume + SQLite index; copy everything; correlate
  metadata across owners.
- **Cannot:** read any record, game plan, or share content; derive any key; recover
  the passphrase (Argon2id ≥ 256 MiB over a strong passphrase).
- **Defenses:**
  - Every blob is XChaCha20-Poly1305 ciphertext or a sealed box; **no plaintext
    columns** in SQLite (CI grep over the index schema asserts this).
  - **Metadata minimization is a requirement, not a footnote.** `recipient_fp` is a
    stable cross-owner correlator that reconstructs a therapist's whole patient panel
    (a risk to the therapist's *other* patients). Therefore:
    - **Remove `recipientFp` / owner fp from query strings**; route via opaque
      **per-relationship inbox tokens**.
    - **Pad blob sizes to fixed buckets BY DEFAULT** (e.g. `4 / 16 / 64 KiB`, larger
      tiers for snapshots) — padding is **on by default for all blob types**, closing
      the per-version size-delta acuity proxy and the `isTombstone+size` withdrawal
      de-anonymization.
    - Keep the access log **owner-local** where possible; short retention when
      server-stored; `device_label`/timestamp exposure minimized.
  - **Bounded retention** (R2 consequence): shares/game-plan blobs have a configurable
    TTL + **hard-delete of superseded/expired blob bytes**, making the
    harvest-now-decrypt-later window **finite** instead of "keep forever."

### T2 — Passive / active network attacker (LAN, Wi-Fi, on-path)

- **Can:** observe/redirect traffic; attempt to strip TLS.
- **Cannot:** read payloads (E2EE *before* TLS); forge content (AEAD + Ed25519
  signatures); silently roll back (client-anchored manifest, §8).
- **Defenses:** payload is E2EE end-to-end, TLS is defense-in-depth; reverse-proxy
  TLS for WAN with HSTS when a real cert is present; WebAuthn assertions are
  origin-bound (config-pinned, §5.5) defeating credential phishing/relay.

### T3 — Malicious / compromised server (the hardest party)

- **Can:** withhold, replay, or serve stale blobs; **serve tampered portal JS/WASM**;
  attempt key substitution during pairing; observe (minimized) routing metadata;
  ignore its own `revoked`/`expiry` flags.
- **Cannot (when defenses honored):** read any plaintext; **forge a valid share**
  (owner Ed25519 signature, R4) or **game plan** (therapist Ed25519 signature);
  silently roll back versions past the local watermark (§8); pass off a substituted
  key once the OOB SAS is compared (§5.6).
- **Defenses:**
  - **Bidirectional content authentication (R4):** the owner Ed25519-signs every share
    bundle; the therapist Ed25519-signs every game plan. Each side verifies against the
    **OOB-pinned** fingerprint of the other before rendering. AAD/transcript binds
    `shareId‖version‖recipientFp‖expiry‖ownerSigningFp`, killing the
    hostile-server content-injection break.
  - **Key authenticity (anti-MITM):** TOFU pinning + **mandatory bidirectional OOB
    SAS** (4–6 word BLAKE2b code / QR, read out-of-band). `recipientFp` inside a signed
    payload is necessary but **not sufficient** (an attacker can set it); the OOB SAS
    comparison is the binding step. Specified as a named deliverable — see
    **PAIRING.md** (a planned sibling) and [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md).
  - **Tampered portal JS — the unclosable browser hole (R5).** SRI/CSP are **inert**
    against the first-party origin that serves both the policy and the assets. Worse for
    the therapist: a PRF-derived KUK is **deterministic**, so a single tampered-JS
    capture yields **permanent offline decryption**. Resolution:
    1. The **native phone Sync flavor is the only secret-handling owner path.**
       Entering the master passphrase into the browser portal is **forbidden /
       strongly discouraged** in product copy.
    2. The therapist decrypt path moves toward a **pinned/installed client** OR an
       **out-of-band-pinned** portal bundle, and **`prfSalt` is rotatable** so one
       capture is not forever.
    3. **SRI is dropped as a stated mitigation** against tampered portal JS. The
       browser portal is documented as a **lower-assurance convenience path, not a
       zero-knowledge one.**

### T4 — Malicious therapist OR stolen/compromised therapist device

- **Can:** read the curated share legitimately granted; copy/screenshot it; author
  plans. A stolen device holds the WebAuthn-bound, at-rest-wrapped reading key.
- **Cannot:** read the owner's full backup (only the curated subset was sealed to
  them); enumerate other shares/patients (per-relationship inbox tokens, scoped caps);
  forge the owner's data (owner signature); persist plaintext by default (thin viewer).
- **Defenses:** crypto-enforced minimization (self-contained subset under a fresh CEK);
  **thin in-memory viewer**, no default disk cache, re-fetch per session (residue
  bounded to one live session); time-box + revoke (honest-server scope, R3); reading
  key wrapped under WebAuthn-PRF / passphrase at rest.
- **Honest limit:** already-decrypted plaintext is never recallable — identical to
  handing someone a PDF. **Real future revocation = therapist re-keying** (R3).

### T5 — Malicious owner (against the server operator / therapist)

- **Token-holder is an integrity/availability weapon, not just an enumeration guard.**
  A bearer/cap-token holder (no E2EE key needed) can PUT garbage versions to evict real
  history (append-only-prune eviction DoS) and exhaust disk
  (`MAX_BLOB_SIZE × MAX_VERSIONS`). Defenses:
  - **Per-token storage quota** + **disk-full fail-closed** handling.
  - **Server-derived `blob_path`** (never client-supplied), validated against a strict
    charset — closes path-traversal on DELETE/store.
  - **Server-side content hashing** over the opaque blob; the client `X-Content-Hash` is
    **untrusted** and never used for integrity decisions.
  - **Caps + rate-limits on version + lineage creation** to prevent monotonic-poisoning
    (e.g. `X-Plan-Version=MAXINT` lineage-lock) and PROPOSED-queue flooding.
  - **Prune executes only after the client confirms a newer durable version exists.**
- Owner cannot coerce server-side decryption — architecturally impossible (no keys on
  the server, no decrypt endpoint). The access log is owner-readable and therapist
  attestations are therapist-signed, keeping accountability symmetric.

### T6 — Supply chain (image, deps, build, vendored web assets)

- **Defenses:** base image **pinned by digest**; lockfiles with hashes; **vendored web
  assets in-repo** (no runtime `npm`/CDN fetch); **SBOM** (CycloneDX/SPDX) + **build
  provenance / SLSA** + **cosign-signed** images and tags per release; **reproducible
  builds**; CI vuln/license/secret scans + an **egress=0 test** (which covers only the
  *shipped image*, not the operator's runtime compose — documented as such, R7). No
  package manager in the final layer.

### T7 — Brute force (passphrase, server auth, MFA, enumeration)

- **Defenses:** Argon2id (≥ 256 MiB, client-side) over a long passphrase makes offline
  attack infeasible even after a disk steal; **per-credential + per-IP rate-limit +
  exponential backoff + lockout** keyed on the **trusted source identity** (§7) so a
  spoofed `X-Forwarded-For` cannot bypass lockout; constant-time comparisons; generic,
  non-enumerating errors; unguessable IDs; per-relationship inbox tokens prevent
  cross-share enumeration.

---

## 4. Cryptography & key hierarchy

**One primitive set, used identically on JVM (lazysodium) and browser
(libsodium-wasm). No custom crypto, no hand-rolled modes.**

| Purpose | Primitive | Parameters / notes |
|---|---|---|
| KDF (owner passphrase → keys) | **Argon2id** *(only KDF)* | `memlimit ≥ 256 MiB`, `opslimit ≥ 3`, 16-byte random salt (non-secret), 32-byte output. **Client-side only.** |
| Purpose separation | **`crypto_kdf`** | Derives `content_key`, manifest signing key, device-label key from one master — distinct subkeys, never reused across purposes. |
| Content AEAD (snapshots, share subset, plan body) | **XChaCha20-Poly1305** | 192-bit **random** nonce per blob (collision-safe), 256-bit key, Poly1305 tag. **MANDATORY everywhere. AES-256-GCM is NOT an accepted equivalent (R1).** |
| Per-share content key | random 256-bit **CEK** | Fresh per share; never reused; isolates a share from the master archive and other shares. |
| Envelope / recipient encryption | **X25519 sealed box** (`crypto_box_seal`) | Wraps the CEK / seals plan bodies to the recipient's long-term key. **Property: confidentiality + sender anonymity. NO PFS (R2).** |
| Signing / authorship | **Ed25519** | **Owner** signs every share bundle *and* verifies game plans. **Therapist** signs every game plan *and* signed attestations. Owner identity is a first-class pinned trust anchor, symmetric with the therapist's. |
| Fingerprints / SAS | **BLAKE2b** over the raw pubkey | Rendered as a 4–6 word code / QR for **bidirectional** OOB verification (§5.6). |
| Therapist key custody at rest | wrapped under **WebAuthn-PRF**-derived KUK (else Argon2id passphrase) | Private key never leaves the client; server stores only the public key + WebAuthn credential public key. **`prfSalt` is rotatable (R5).** |
| Capability / inbox token | 256-bit CSPRNG, stored **hashed** (BLAKE2b) | Per-relationship; never logged in plaintext; **bound to the authenticated WebAuthn credential at first fetch** (§5.5). |

### Key hierarchy

```
OWNER
  passphrase ──Argon2id(salt)──▶ master ──crypto_kdf──┬─▶ SYNC_KEY  ──XChaCha20-Poly1305──▶ snapshot blobs
                                                       ├─▶ manifest signing key (Ed25519 seed)
                                                       └─▶ device-label key
  owner_x25519_priv / owner_ed25519_priv (on-phone, wrapped at rest)        [NEVER uploaded]
      └─ owner_*_pub  ─────────────────────────────────────────▶ published (therapist pins via OOB SAS)

THERAPIST
  ther_x25519_priv / ther_ed25519_priv (wrapped under WebAuthn-PRF KUK)     [NEVER uploaded]
      └─ ther_*_pub  ──────────────────────────────────────────▶ published (owner pins via OOB SAS)

SHARE  (owner → therapist)
  subset_plaintext ──XChaCha20(CEK)──▶ share_blob
  bundle ──Ed25519.sign(owner_ed25519_priv)──▶ signed bundle   (therapist verifies vs PINNED owner fp)
  CEK ──crypto_box_seal(ther_x25519_pub)──▶ wrapped_CEK
  AAD = shareId‖version‖recipientFp‖expiry‖ownerSigningFp

GAME PLAN  (therapist → owner)         [lands in NEW game_plans table, DB v13 — see COMPANION_THERAPIST.md]
  plan_body ──Ed25519.sign(ther_ed25519_priv)──▶ signed plan   (owner verifies vs PINNED therapist fp)
  signed plan ──crypto_box_seal(owner_x25519_pub)──▶ plan_blob

SERVER holds: ciphertext blobs, wrapped CEKs, PUBLIC keys, hashed tokens, MINIMIZED routing
metadata. NOTHING that decrypts, authenticates a party, or authors content.
```

**No escrow, anywhere (O6).** Lose the passphrase → snapshots unrecoverable
(phone-local copy is the fallback). Lose the therapist key → owner re-invitation +
**re-pair (re-verify a new fingerprint) + re-wrap** (also the only real revocation
primitive, R3).

---

## 5. MFA, key custody & session model

### 5.1 Primary credential — WebAuthn / passkey (default)

Resident/discoverable credential (`residentKey: "required"`),
`userVerification: "required"`, attestation `"none"`. User verification binds
*possession* (the authenticator) to an *inherence/knowledge* factor in one ceremony —
two-factor with no IdP and no outbound message. The **WebAuthn PRF extension** output
is the **Key-Unlock Key (KUK)** that unwraps the therapist's in-browser reading/signing
keys, so **"authenticated" and "able to decrypt" are the same client-side gate** — the
server never holds anything that decrypts.

> **Deterministic-KUK caveat (R5):** because `KUK = PRF(credential, prfSalt)` is
> deterministic, a single tampered-JS capture in the server-served portal yields
> *permanent* offline decryption. Mitigations: pinned/installed therapist client,
> rotatable `prfSalt`, native-only owner secrets.

### 5.2 Fallback — TOTP (honestly flagged as weaker, R/limits)

TOTP-only therapists cannot use PRF, so their reading key is wrapped under a **separate
Argon2id passphrase** (never under the TOTP secret). The TOTP authenticating secret:

- is **distinct** from the single-use bootstrap invite code,
- is **client-set, high-entropy, rotatable**,
- is **never sent in cleartext**, and is stored server-side only as an Argon2id hash.

This is a **phishable, server-stored authenticating secret** that breaks the "server
holds nothing that authenticates" property — documented in [§11](#11-out-of-scope--honest-limits),
not glossed. `signCount` regression / synced-passkey (`signCount=0`) clone-detection
limits are documented there too.

### 5.3 Step-up "sign-off" for sensitive actions

`share.open`, `gameplan.publish`, `key.rotate`, and `revoke` each require a fresh,
single-use, action-scoped step-up WebAuthn assertion — **bound to the active, live,
non-revoked session id** (R/mustFix). An assertion minted in one context **cannot be
exec'd from a stolen session**, and the server returns blobs **only to the bound
session.** The same biometric gesture both proves intent and yields the PRF decryption
secret.

### 5.4 Sessions

| Control | Spec |
|---|---|
| Token | Opaque, server-side **256-bit random** session id (a stored record, not a JWT) → instant revocation, no forgeable client secret. `HttpOnly; Secure; SameSite=Strict`. |
| Lifetime | **15 min idle**, **8 h absolute**. Sensitive actions require a fresh assertion regardless. |
| Binding | Session record stores `credentialId`; every request re-checks active, non-revoked credential + active relationship. Step-up assertions are bound to the live session id (§5.3). |
| CSRF | `SameSite=Strict` **plus** a per-session anti-CSRF token on all state-changing requests. |
| Logout/expiry | Server deletes the session; client zeroizes in-memory KUK, private keys, CEK, plaintext. |

> The **device-cookie binding** from earlier drafts is **not counted** as a security
> control: it travels with the session cookie under every realistic theft vector
> (XSS, endpoint malware, hostile server) and is security theater. The real
> stolen-session defense is the **session-bound step-up assertion** (§5.3).

### 5.5 WebAuthn RP-ID / origin (config-pinned, never client-derived)

`rp.id` and the origin allowlist come from explicit
`DAYMARK_WEBAUTHN_RP_ID` / origin config — **never** from a client-controllable
`Host` / `X-Forwarded-Host`. Sub-path deployment is fully specified: the proxy strips
the prefix, the app re-adds it via `DAYMARK_BASE_PATH`, and cookie `Path` / CSP /
RP-ID are computed **from config**. Assertion origin verification is **exact**.
Capability tokens **must be bound to the authenticated WebAuthn credential at first
fetch** — no single-factor bearer fetch.

```yaml
# Companion config (illustrative — DESIGN ONLY)
DAYMARK_WEBAUTHN_RP_ID: "companion.example.org"   # pinned, NOT from Host header
DAYMARK_WEBAUTHN_ORIGINS:                          # exact allowlist
  - "https://companion.example.org"
DAYMARK_BASE_PATH: "/"                             # set to "/daymark" for sub-path deploys
```

### 5.6 Pairing — mutual, out-of-band, bidirectional (mandatory)

TOFU pinning is required in **both directions before any payload flows**:

1. The **owner verifies the therapist's** X25519 + Ed25519 fingerprints.
2. The **therapist verifies the owner's** X25519 (encryption) + Ed25519 (signing)
   fingerprints.

All via a short-authentication-string (4–6 word BLAKE2b code / QR) read **out-of-band**.
The server **never** vouches for keys and **never** mediates an unverified encryption
key. `recipientFp` inside a signed payload is necessary but not sufficient; the **OOB
SAS comparison is the binding step**. This protocol is a **named deliverable
(PAIRING.md)**, not hand-waved as "(out-of-band)". See
[COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) for the enrollment ceremony.

### 5.7 Recovery & revocation

- **No server-side escrow.** Single lost device → optional self-held,
  Argon2id-passphrase-protected recovery file (never touches the server).
- Compromised/no-file case → **owner re-invitation**: revoke, re-pair (re-verify new
  fingerprint), re-wrap each active share's CEK to the new reading key.
- **Therapist key loss = owner re-invitation + re-pair + re-wrap; no escrow** — and is
  also the only real revocation primitive against a colluding server (R3).
- **V1 scope lock:** **one therapist, one keypair, one device.** Multi-therapist /
  multi-device fan-out and a therapist dashboard are **out of scope** (added trust
  surface). The owner remains the **sole root of trust.**

---

## 6. Server hardening defaults

### Container / runtime

- **Non-root** (`USER 10001:10001`); `cap_drop: ["ALL"]`; `no-new-privileges`.
- **Read-only root filesystem**; only the blob volume + a small `tmpfs` writable.
- **Egress lockdown (R7):** the companion runs on its **own** `internal: true` bridge
  that the proxy *also* joins (proxy multi-homed) so the companion has no path to the
  gateway — **or** the "structurally enforced" wording is dropped and a **host-firewall
  egress-deny** is required. The shipped image contains **no package manager** and
  installs nothing at runtime. See [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md).
- Minimal distroless/alpine base **pinned by digest**; SBOM + provenance per release.
- Healthcheck is **loopback-only** and does **no DB work**; the unauthenticated
  `/healthz` and any meta endpoint are liveness-only and reveal no init state.

### HTTP / portal

- **Strict CSP:** `default-src 'self'; script-src 'self' 'wasm-unsafe-eval';
  style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none';
  base-uri 'none'; frame-ancestors 'none'; form-action 'self'`. No `unsafe-inline`, no
  `unsafe-eval`, no third-party origins, **no CDN**.
  > **CSP/SRI are NOT counted as a zero-knowledge defense against the first-party
  > origin (R5).** They harden against third-party tampering only.
- Security headers: `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
  `X-Frame-Options: DENY`, `Cross-Origin-Opener-Policy: same-origin`,
  `Cross-Origin-Resource-Policy: same-origin`, `Permissions-Policy` disabling
  geolocation/camera/mic (except WebAuthn), HSTS with a real cert.
- No directory listing; generic error bodies; no stack traces.

### Auth, rate-limiting, DoS hygiene

- Server access credential **separate from** the E2EE passphrase.
- **Per-credential + per-IP** rate-limit + exponential backoff + lockout, keyed on the
  **trusted source identity** (§7); constant-time comparisons; generic, non-enumerating
  errors.
- **Caps:** `MAX_BLOB_SIZE`, `MAX_VERSIONS` (prune), max request body, **per-token
  storage quota**, max in-flight requests, lineage/version creation caps (T5). All apply
  to owner and therapist endpoints.
- **Server-derived `blob_path`** validated against a strict charset; **server-side
  content hashing** (client hash untrusted); **disk-full fail-closed**.
- Unguessable per-relationship inbox tokens; listing scoped to the caller's own
  relationship only.

### Owner notifications + access-token recovery (Track T2, shipped)

Email Option A: **owner notifications + server-access-token re-issue only** — no owner
accounts, no passwords, no escrow. The server still can never reset the PIN or E2EE
passphrase.

- **The registered notification email is stored in plaintext** on the server. This is
  necessary — the server must read the address to send to it — and is documented
  loudly rather than glossed. It is comparable in sensitivity to the routing metadata
  already covered by §3 T1 (a leak reveals that a relationship/owner exists at this
  address, not any record content).
- **The owner/bearer token (`DAYMARK_AUTH_TOKEN`) is likewise stored in plaintext**, now
  in a small per-datadir store rather than only held in process memory from the env
  var. This does not change its threat classification: it was already an
  operator-plaintext secret (env var / mounted file), and remains a
  network-enumeration/DoS guard, **not** a confidentiality boundary (§ above) — it
  gates PUT/GET of opaque blobs and never decrypts anything.
- **The access-token recovery request endpoint is unauthenticated by necessity** (that
  is the point of a recovery path) but is heavily rate-limited per source (with a
  bounded, evicted rate-limit table so an unauthenticated flood cannot grow it without
  bound) and **always responds `202` identically** whether the submitted email matches
  the registered one or not, so a status/body oracle cannot reveal whether an address
  is registered. The one branch that does real work on a match — sending the email —
  is **dispatched to a background task, never awaited inline**, so a match and a
  non-match take the same time to respond too; without this, the real SMTP round-trip
  that only happens on a match would itself be a timing side-channel defeating the
  "always responds identically" property. Email comparison is case-insensitive
  (addresses are normalized to lowercase at registration and at compare time) so a
  registered `Owner@Example.org` still matches a recovery request for the everyday
  lowercase form.
- **The recovery link is built ONLY from the operator-configured `DAYMARK_PUBLIC_BASE_URL`
  (or `DAYMARK_WEBAUTHN_ORIGINS` as a fallback) — never from the request's `Host`
  header.** Unlike the owner-authenticated invite link (which does fall back to `Host`
  as a best effort, acceptable there because minting an invite already requires the
  owner's bearer token), this route has no credential gate at all; trusting a
  client-supplied `Host` here would let an unauthenticated attacker cause a real,
  single-use recovery token to be emailed inside a link pointing at a domain *they*
  control. If no public base URL is configured, the server accepts the request
  (still responding identically) but skips sending and logs a warning — see
  COMPANION_DEPLOYMENT.md.
- **Recovery is single-use and time-limited**: the confirmation link is a high-entropy,
  one-time token; confirming it rotates the bearer token immediately (the old value
  stops working with no overlap) and the new token is shown **once**, in the response
  to the confirm call — **never** emailed. The rotation is applied to the live
  in-process token guard from *inside* the same critical section that persists it, so
  two concurrent confirms (e.g. two outstanding valid links) can't leave the live guard
  and the persisted/restart-recovered token disagreeing. A follow-up "your access token
  was just re-issued" receipt is sent to the registered address so an owner who did not
  initiate a rotation is alerted.
- **Recovering server access never recovers anything else.** A newly issued bearer
  token still cannot decrypt a single record — the E2EE passphrase and PIN remain
  entirely client-side and are unrecoverable by design (O6).
- **Deferred from this slice:** lockout-alert emails (the "(optional)" item in the
  original mini-spec) are not yet wired up; tracked as a follow-up.

---

## 7. Reverse-proxy / trusted-proxy hardening

This unifies the proxy posture across **every** track (sync, sharing, auth, game-plans,
deploy, threat). It closes the rate-limit/lockout bypass — the **sole brute-force
defense** for the bearer/cap token — and the loopback-spoof bypass.

| Rule | Spec |
|---|---|
| **Default** | **Trust NO forwarded headers** — derive everything from the **socket peer**. The shipped `172.16.0.0/12` default is **REMOVED (R9)** — a /12 lets any co-resident Docker container forge `X-Forwarded-*`. |
| Trusted proxy | Operator **explicitly** sets a **single narrow** proxy IP/CIDR. |
| Header stripping | The proxy **strips inbound** `X-Forwarded-For` / `X-Real-IP` / `X-Forwarded-Host` / `Forwarded` **before** setting its own. |
| Derived identities | Rate-limit identity, lockout key, audit source-IP, and the **"HTTPS-required-for-non-loopback"** gate derive from forwarded headers **only when the pinned proxy is trusted**, else from the **socket peer**. |
| RP-ID / origin | **Never** derived from client-controllable `Host` / `X-Forwarded-Host` — pinned to config (§5.5). The nginx `$host` example is fixed to a configured `server_name`. |

```nginx
# Illustrative (DESIGN ONLY) — pin server_name, do NOT pass client Host through.
server {
    server_name companion.example.org;           # NOT $host
    location / {
        proxy_set_header Host              $server_name;
        proxy_set_header X-Forwarded-Proto https;  # proxy-asserted, client value stripped
        proxy_set_header X-Forwarded-For   $remote_addr;   # replace, never append client value
        proxy_pass http://companion:8080;
    }
}
```

---

## 8. Anti-rollback & integrity (client-anchored)

**Server-side `prevHash` / version-chain enforcement provides ZERO integrity guarantee
(R6).** A hostile server forges a consistent chain over client-supplied metadata; an
owner-readable "expected hash" log fetched from the *same* server is equally forgeable.

The **only** real anti-rollback is:

- an **Ed25519-signed, hash-chained manifest** (signing key derived via `crypto_kdf`,
  §4),
- verified against a **local trust watermark** on the phone that **sync refuses to
  regress past.**

The client **never** trusts server `list` / `prevHash` / log output without manifest
verification. Server-side chain checks are **DoS hygiene only.** This is stated
explicitly here per the reviewer must-fix.

> **v1 sync model (R11):** single-writer, **last-snapshot-wins** replication
> (newest full snapshot is authoritative; older device pulls and replaces;
> append-only history preserved server-side). **Not** row-level merge — the schema
> has no `updatedAt` and `entry_activity` is keyed on raw rowids, so a deterministic
> three-way merge is unimplementable and would corrupt the entry↔activity join and
> photo associations. True multi-device row-merge is gated behind a prerequisite
> migration (stable cross-device UUIDs + real `updatedAt` + UUID-re-keyed tombstones),
> deferred. See [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md).

---

## 9. Audit-logging posture

**Log events, not content.** Owner-readable, append-only, metadata-only. **Shipped**
(Track T1): `GET /v1/rel/{relRef}/audit`, gated the same two ways as the blob API — the
caller must hold the relationship's inbox token (`X-Rel-Token`, hashed to `relRef`) AND
present the owner bearer token. Therapists cannot read or write it directly; entries are
appended server-side from the real access paths (auth, TOTP enrol, share/game-plan
fetch, assignment/game-plan publish, session expiry) — never client-supplied.

```jsonc
{
  "seq": 412,                    // monotonic per relationship, starts at 1
  "ts": 1719600123,               // server time, epoch seconds
  "actor": "therapist",           // "owner" | "therapist" — who performed the action
  "action": "share.open",         // auth.success | auth.fail | lockout | enrol.ok |
                                   //   share.open | gameplan.open | assignment.publish |
                                   //   gameplan.publish | session.expired (extend as needed)
  "objectRef": "lin1:3",          // opaque channel-scoped id (lineage:version); never content
  "meta": { "credentialId": "…" },// small, fixed, non-content annotations only (optional)
  "entryHash": "<sha256 hex>"      // = SHA256(prevHash ‖ seq ‖ ts ‖ relRef ‖ actor ‖ action ‖ objectRef ‖ meta)
}
```

- **Never logged:** which individual records/moods were viewed, any plaintext, CEKs,
  private keys, PRF output, TOTP codes, passphrases.
- **IP is OFF by default** (`DAYMARK_ACCESS_LOG_SOURCE_IP`) — a logged IP geolocates the
  clinic; when enabled it rides in `meta.sourceIp`. Retention is configurable
  (`DAYMARK_ACCESS_LOG_RETENTION_DAYS`, default 90) — entries older than the window are
  pruned on the relationship's next append.
- **Suppression resistance, updated (R12):** each entry is now **server-computed
  hash-chained** (`entryHash` above) — a *stored* entry cannot be silently altered or
  reordered without breaking the chain for every entry after it. This is **not** the
  therapist-signed attestation originally described here (no Ed25519 signature; every
  event above is server-asserted, the same trust level `auth.fail`/`lockout` already
  had) — so it adds no non-repudiation, and it does **not** stop a hostile server from
  simply never appending an event, or from truncating the chain and serving a
  shorter-but-internally-consistent history. **"Access cannot be hidden" therefore
  remains partially retracted:** tampering/reordering of what the server *does* return
  is now detectable; withholding is not. Full suppression-resistance still needs the
  originally-scoped signed client attestation, which has not shipped.

---

## 10. Hardening checklist (copy-paste)

**Crypto**
- [ ] Argon2id `memlimit ≥ 256 MiB`, `opslimit ≥ 3`, client-side only; the **only** KDF.
- [ ] **XChaCha20-Poly1305**, fresh 192-bit random nonce per blob, **everywhere**. **No AES-GCM equivalent.**
- [ ] `crypto_kdf` purpose-separation for `content_key` / manifest-signing / device-label keys.
- [ ] Fresh random 256-bit CEK per share; never reused.
- [ ] CEK / plan body wrapped via X25519 `crypto_box_seal` — documented as **no PFS**.
- [ ] **Owner Ed25519-signs every share bundle**; therapist verifies vs pinned owner fp.
- [ ] **Therapist Ed25519-signs every game plan**; owner verifies vs pinned therapist fp.
- [ ] AAD binds `shareId‖version‖recipientFp‖expiry‖ownerSigningFp`.
- [ ] Private keys generated client-side, wrapped at rest (WebAuthn-PRF / Argon2id); **never uploaded**. `prfSalt` rotatable.
- [ ] No plaintext columns in SQLite (CI grep asserts schema).

**Container / runtime / supply chain**
- [ ] Non-root, caps dropped, `no-new-privileges`, read-only root FS, tmpfs-only scratch.
- [ ] Companion on its **own** `internal: true` bridge (proxy multi-homed) **or** host-firewall egress-deny; **no** `172.16.0.0/12` trust.
- [ ] No package manager / no runtime install in final image; base pinned by digest.
- [ ] SBOM + SLSA provenance + cosign signature + reproducible build; CI vuln/license/secret scans + egress=0 (image-only) test.

**HTTP / portal**
- [ ] Strict CSP (`'self'` + `wasm-unsafe-eval` only; no inline/eval; no CDN). **SRI NOT counted as anti-tamper vs first-party origin.**
- [ ] Security headers: nosniff, no-referrer, frame DENY, COOP/CORP same-origin, Permissions-Policy, HSTS (real cert).
- [ ] Browser portal documented as **lower-assurance**; master passphrase entry into portal **forbidden/discouraged**; therapist path pinned/installed where possible.

**Reverse proxy**
- [ ] Default trust-none; pin a single narrow proxy CIDR; strip inbound `X-Forwarded-*`/`Forwarded` at the proxy.
- [ ] Rate-limit/lockout/audit-IP/HTTPS-gate derive from forwarded headers **only** when the pinned proxy is trusted, else socket peer.
- [ ] RP-ID/origin/cookie-Path/base-path from **config**, never client `Host`/`X-Forwarded-Host`.

**Auth / MFA / session**
- [ ] WebAuthn primary (resident, UV required, origin-bound, config-pinned RP-ID); PRF gates key unlock.
- [ ] TOTP fallback honestly flagged; authenticating secret distinct from invite code, client-set, high-entropy, rotatable, never cleartext.
- [ ] Step-up assertions for `share.open`/`gameplan.publish`/`key.rotate`/`revoke`, **bound to the live session id**.
- [ ] Capability/inbox token bound to the authenticated credential at first fetch; no single-factor bearer fetch.
- [ ] Opaque server-side sessions; 15 min idle / 8 h absolute; per-session CSRF token; instant revocation.
- [ ] Invite redemption uses **capped backoff** (NOT burn-after-5), unguessable `inviteId`s, no-referrer enroll pages.

**Shares / DoS / metadata / integrity**
- [ ] Share = materialized curated subset (crypto-enforced minimization); PHQ-9 self-harm item structurally absent; check-ins carry scores/bands only.
- [ ] Server enforces expiry + revoke on GET (honest-server scope only; re-keying = real revocation).
- [ ] Thin in-memory therapist viewer (no default disk cache).
- [ ] Bounded server retention: TTL + hard-delete of superseded/expired blob bytes.
- [ ] **Size-bucket padding ON BY DEFAULT for all blob types**; `recipientFp`/owner fp out of query strings (opaque inbox tokens).
- [ ] Per-token storage quota + disk-full fail-closed; server-derived `blob_path` (strict charset); server-side content hashing (client hash untrusted); version/lineage caps; prune only after newer durable version confirmed.
- [ ] **Client-anchored anti-rollback:** Ed25519 hash-chained manifest verified vs local trust watermark; server chain checks are DoS hygiene only.

**Audit**
- [x] Events not content; owner-readable; opaque per-relationship token (not fp/name); IP **off by default**; short retention. — shipped (Track T1), see [§9](#9-audit-logging-posture).
- [x] Monotonic sequence / hash-chain on entries — shipped, **server-computed, not therapist-signed**; the "access cannot be hidden" claim stays dropped for withholding (see [§9](#9-audit-logging-posture)).

**Product boundary**
- [ ] Therapist viewer + game-plan UI carry the same "self-check, not a diagnosis; scores are not clinical thresholds" framing as the app.
- [ ] PRIVACY.md/SECURITY.md retract "no server, so no server-side surface" for the Sync flavor; flagship F-Droid build remains provably network-free (no `INTERNET`).

---

## 11. Out of scope / honest limits

- **Endpoint compromise is out of the server threat model.** A compromised owner phone
  or therapist device defeats all of this — endpoint security is the user's
  responsibility (same posture as the flagship).
- **No PFS on sealed boxes (R2).** One compromise of a recipient long-term X25519 key
  (therapist for shares, owner for game plans) retroactively decrypts **every** blob
  ever sealed to it. CEK rotation does **not** mitigate this — all versions are sealed
  to the same long-term key. **Bounded server retention** (TTL + hard-delete) makes the
  harvest-now-decrypt-later window finite; it does not make it zero.
- **Revocation is honest-server-scoped (R3).** Three honest guarantees: (1) future
  server-mediated fetches are blocked **on an honest server**; (2) data published
  **after** therapist re-key is unreadable to the old key; (3) already-decrypted
  plaintext is never recallable (like handing someone a PDF). A
  malicious/colluding server can still serve an already-pushed (even not-yet-decrypted)
  share to the unrotated therapist key. **Real future-data revocation requires therapist
  re-keying.**
- **The browser portal is NOT zero-knowledge against a malicious server (R5).** Anyone
  who types the passphrase (owner) or unlocks the reading key (therapist) in the
  server-served portal is exposed; the deterministic PRF-KUK makes a single tampered-JS
  capture a permanent therapist break. Native-only owner secrets, a pinned/installed or
  OOB-pinned therapist client, and rotatable `prfSalt` are the answers. SRI is **not** a
  defense here.
- **Anti-rollback is client-only (R6).** Server chain checks guarantee nothing; only the
  signed manifest + local watermark do.
- **v1 sync is single-writer last-snapshot-wins (R11).** No concurrent multi-device row
  editing; true merge is gated behind a deferred UUID+`updatedAt` migration.
- **Metadata leakage is inherent but minimized, not "harmless."** Padding +
  per-relationship inbox tokens + IP-off-by-default reduce caseload re-identification,
  acuity proxies, and withdrawal de-anonymization, but cannot fully hide the existence
  and cadence of a relationship on a self-hosted box.
- **TOTP is a weaker parallel custody path.** It places a phishable, server-stored
  (Argon2id-hashed) authenticating secret on the box, breaking "server holds nothing
  that authenticates." `signCount` regression and synced-passkey (`signCount=0`)
  clone-detection are not reliable for cloud-synced credentials.
- **Audit suppression is undetectable until the chain ships (R12).** Forgery is
  prevented; silent omission is not, absent the signed monotonic sequence.
- **No escrow / no recovery by design (O6).** Lost passphrase → blobs unrecoverable
  (phone-local fallback); lost therapist key → re-invitation + re-pair + re-wrap.
- **One therapist, one keypair, one device.** Multi-therapist / multi-device fan-out
  and a therapist dashboard are out of scope; the owner is the sole root of trust.
- **Non-diagnostic by *framing*, not by construction.** Free-text game-plan bodies can
  contain diagnostic/medication content the schema cannot constrain, so the guarantee is
  downgraded to **non-diagnostic by framing**: the therapist viewer and game-plan UI
  carry explicit "this is guidance from your real clinician; the app is not practicing
  medicine; scores are self-checks, not clinical thresholds" disclaimers. The synced
  bundle keeps item-9 / self-harm scoring **structurally absent.** The Sync flavor's
  owner→therapist plaintext egress to a third human party is stated honestly here and in
  [../PRIVACY.md](../PRIVACY.md) / [../SECURITY.md](../SECURITY.md) (R8).
- **The CI egress=0 test covers the shipped image only**, not the operator's runtime
  compose (R7).

---

## Related documents

- [COMPANION_SCOPE.md](COMPANION_SCOPE.md) — purpose, roles, in/out of scope, honest limits.
- [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md) — sync model, blob store, manifest, DB v13.
- [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) — pairing/enrollment, sharing, game plans (`game_plans` table).
- [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) — Docker/Compose, network topology, reverse-proxy config.
- [../PRIVACY.md](../PRIVACY.md) · [../SECURITY.md](../SECURITY.md) · [../HANDOFF.md](../HANDOFF.md) — flagship privacy/security posture and the non-diagnostic prime directive (§0).
