# Daymark Companion — Therapist Guide & Two-Party Design

> ## ⚠️ STATUS: DESIGN ONLY — NO CODE EXISTS YET
>
> This document describes the **therapist-facing** half of the Daymark Companion's optional,
> self-hosted, two-party (owner + therapist) model. **None of it is implemented.** There is no
> Companion container, no "Daymark Sync" flavor, no therapist portal, no invite flow, and no
> `game_plans` table in the shipping app today. Everything below is a build-ready *design
> contract* to be reviewed, sequenced, and built — not a description of working software. Where this
> document states a guarantee, read it as a **requirement on the eventual implementation**, not a
> claim about current behavior.
>
> The flagship Daymark app remains **fully offline, no `INTERNET` permission, no server** (see
> [../PRIVACY.md](../PRIVACY.md)). Everything here lives only in a **separate, opt-in** path and
> never alters that default. **Whether the owner→therapist sharing and game-plan tracks ship at all
> in the first Companion release — or whether sync lands first — is an unresolved sequencing
> decision** (see [COMPANION_SCOPE.md](COMPANION_SCOPE.md) and [§13 Open Questions](#13-open-questions-unresolved)).
>
> This document also **honors and restates the project's retractions.** Where older notes claimed
> forward secrecy for sealed-box shares, server-side revocation that defeats a colluding server, or a
> zero-knowledge browser portal, those claims are **false and are corrected here**. See
> [§11 Honest Limits](#11-honest-limits-read-this-twice).

**Sibling documents** (relative links):
[COMPANION_SCOPE.md](COMPANION_SCOPE.md) ·
[COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md) ·
[COMPANION_SECURITY.md](COMPANION_SECURITY.md) ·
[COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) ·
[COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) ·
plus the pairing protocol spec **PAIRING.md** (a named deliverable, not hand-waved).
Baseline context: [DOCKER_COMPANION.md](DOCKER_COMPANION.md),
[ARCHITECTURE.md](ARCHITECTURE.md), [PRIVACY.md](PRIVACY.md),
[INSTRUMENTS.md](INSTRUMENTS.md), [../HANDOFF.md](../HANDOFF.md).

---

## Contents

- [1. Who the therapist is (and is not)](#1-who-the-therapist-is-and-is-not)
- [2. The non-diagnostic prime directive](#2-the-non-diagnostic-prime-directive)
- [3. Roles, keys, and what each party holds](#3-roles-keys-and-what-each-party-holds)
- [4. Cryptographic contract (one primitive set)](#4-cryptographic-contract-one-primitive-set)
- [5. The share lifecycle (invite → enroll → share → read → revoke)](#5-the-share-lifecycle-invite--enroll--share--read--revoke)
- [6. What the therapist CAN and CANNOT see](#6-what-the-therapist-can-and-cannot-see)
- [7. Game plans — therapist write-back](#7-game-plans--therapist-write-back)
- [8. Authentication, sessions, and step-up sign-off](#8-authentication-sessions-and-step-up-sign-off)
- [9. Revocation, rotation, expiry — honestly scoped](#9-revocation-rotation-expiry--honestly-scoped)
- [10. The audit log (owner-readable, metadata-only)](#10-the-audit-log-owner-readable-metadata-only)
- [11. Honest limits (read this twice)](#11-honest-limits-read-this-twice)
- [12. v1 scope lock](#12-v1-scope-lock)
- [13. Open questions (unresolved)](#13-open-questions-unresolved)
- [14. Build checklist (therapist surfaces)](#14-build-checklist-therapist-surfaces)
- [15. Related documents](#15-related-documents)

---

## 1. Who the therapist is (and is not)

The **therapist** is a *second human party* the **owner** deliberately, revocably, and temporarily
grants **read access** to a **curated subset** of their journal/mood data — and from whom the owner
optionally receives non-diagnostic **game plans**. The owner is the **sole root of trust**. The
server never vouches for anyone.

**The therapist is:**

- a recipient the owner **pinned out-of-band** (the owner verified the therapist's public-key
  fingerprint by reading a short code aloud / scanning a QR in session — see [PAIRING.md](COMPANION_ARCHITECTURE.md));
- a **read-only** viewer of exactly the records the owner sealed into a share, for a **bounded** time;
- the author of **proposed** game plans the owner must **explicitly accept** before anything lands.

**The therapist is NOT:**

- an account the server authenticates on the owner's behalf — there is **no central identity
  provider**, no help desk, no admin password reset;
- a writer into the owner's own data — therapist content is **segregated, signed, and immutable**,
  and the owner's local phone DB stays authoritative for the owner's own data;
- a clinician *operating inside a medical device* — Daymark is **not** a medical device, and game
  plans are **not** diagnoses, prescriptions, or medical orders (see [§2](#2-the-non-diagnostic-prime-directive)).

```
            ┌──────────────────────────────────────────────────────────┐
            │                    OWNER  (root of trust)                 │
            │  phone Sync flavor = the ONLY secret-handling owner path  │
            └───────────────┬──────────────────────────┬───────────────┘
                            │ seals a curated SHARE     │ accepts a signed
                            │ to therapist's PINNED key │ GAME PLAN (read-only)
                            ▼                           ▲
            ┌──────────────────────────────────────────────────────────┐
            │     SERVER  (zero-knowledge, append-only blob host)        │
            │  opaque ciphertext + non-secret routing metadata only      │
            │  never holds a key, a passphrase, a CEK, or any plaintext  │
            └───────────────┬──────────────────────────┬───────────────┘
                            ▼                           │
            ┌──────────────────────────────────────────────────────────┐
            │   THERAPIST  (pinned second party; read-only viewer)       │
            │  holds X25519 (read) + Ed25519 (sign) keys, wrapped client │
            │  side; sees ONLY the curated subset, in-memory, this session│
            └──────────────────────────────────────────────────────────┘
```

---

## 2. The non-diagnostic prime directive

[../HANDOFF.md](../HANDOFF.md) §0 is the prime directive: Daymark is **non-diagnostic**, carries **no
externally-authored clinical content** as authoritative app data, and is **not practicing medicine**.
The therapist surfaces must police diagnostic-claim creep at **every new surface** — they do not get
a softer framing than the core app.

| Rule | How it is enforced |
|---|---|
| The therapist share viewer and the game-plan UI carry the **same** "self-check, not a diagnosis; scores are not clinical thresholds" framing as the app. | Fixed UI banners (not server-supplied), identical copy to the core app's instrument screens (see [INSTRUMENTS.md](INSTRUMENTS.md)). |
| Bands/scores are **never** presented as clinical cutoffs. | Shared bundles carry **scores/bands only** with the app's existing scores-only invariant; the viewer renders the same disclaimer bands, not diagnostic verdicts. |
| The PHQ-style **self-harm / item-9 question is structurally absent** from every share bundle. | It is never persisted (see [../PRIVACY.md](../PRIVACY.md)) and never materialized into a share — the scoring slot is simply not present in the wire schema. |
| Free-text game-plan bodies **can** contain diagnostic or medication content the schema cannot constrain. | **"Non-diagnostic by construction" is downgraded to "non-diagnostic by FRAMING."** Explicit UI disclaimers state this is *guidance from your real clinician*, that the owner may decline/ignore/delete it, and that **the app itself is not making a medical claim**. The product boundary is documented, not hidden. |
| No risk scoring, no diagnostic verdicts, no auto-actions on the owner side. | Receiving a plan triggers **no** network call and **no** automatic scheduling. The owner explicitly accepts; the same crisis-resources posture as the core app applies. |

> **Honest restatement (per the project decisions):** the schema *cannot* stop a clinician from
> typing medication or diagnostic language into a free-text plan body. We therefore do **not** claim
> the channel is "non-diagnostic by construction." We claim it is **non-diagnostic by framing**: the
> app frames every plan as personal guidance from the owner's own clinician, surfaces a fixed
> disclaimer the server cannot strip, and never represents plan content as Daymark's own clinical
> judgment.

---

## 3. Roles, keys, and what each party holds

| Party | Holds | NEVER holds |
|---|---|---|
| **Owner** (phone Sync flavor — the only secret-handling owner path) | Sync passphrase, owner X25519 (encryption/"inbox") + Ed25519 (signing) secret keys, plaintext; generates per-share CEKs and signs every share bundle | Therapist private keys |
| **Therapist** (pinned/installed client preferred; browser portal is a lower-assurance convenience) | Therapist X25519 (reading) + Ed25519 (signing) secret keys, wrapped at rest under a WebAuthn-PRF–derived key (or Argon2id passphrase on the TOTP path); share plaintext **in memory only** | Owner passphrase, owner private keys, the master snapshot, any other patient's data |
| **Server** (Companion container) | Opaque ciphertext blobs; **public** keys + fingerprints; capability-token *hashes*; non-secret routing metadata (opaque inbox token, sizes, timestamps, expiry, revoke flag, version) | Any private key, any unwrapped CEK, any passphrase, any plaintext — and it can never forge a signed bundle |

**Owner identity is a first-class, pinned trust anchor — symmetric with the therapist's.** The owner
Ed25519 key is used for **both** share-signing **and** game-plan-verification. The pairing is
**mutual and bidirectional**: the owner verifies the therapist's X25519+Ed25519 fingerprints, **and**
the therapist verifies the owner's X25519 (encryption) + Ed25519 (signing) fingerprints, all via a
short-authentication-string (4–6 word BLAKE2b code / QR) read **out-of-band**. See
[§5.2](#52-enroll--mutual-out-of-band-pairing-mandatory).

---

## 4. Cryptographic contract (one primitive set)

One libsodium primitive set, used **identically** on JVM (phone Sync flavor, lazysodium) and in the
browser (libsodium-wasm). The contract is defined once for shares, game plans, auth, and sync. See
[COMPANION_SECURITY.md](COMPANION_SECURITY.md) for the full rationale and threat analysis.

| Purpose | Primitive | Notes |
|---|---|---|
| Symmetric AEAD (share content, snapshots, game-plan envelope at rest) | **XChaCha20-Poly1305**, 192-bit (24-byte) **random** nonce | **MANDATORY everywhere.** AES-256-GCM is **removed** as a documented "equivalent" — 96-bit random GCM nonces under one indefinitely-reused long-lived key hit birthday-bound reuse, which is catastrophic. |
| Therapist reading keypair | **X25519** (`crypto_box`) | Encrypt-to-therapist for shares. |
| Therapist signing keypair | **Ed25519** | Signs game plans and signed access attestations. |
| Owner keypairs | **X25519** ("inbox", for game plans flowing back) + **Ed25519** (signs every share bundle) | Owner identity is pinned by the therapist out-of-band. |
| CEK wrapping to a recipient | **`crypto_box_seal`** (sealed box, ephemeral X25519 sender) | **Sender anonymity ONLY — NOT forward secrecy** (see below). |
| Key derivation | **Argon2id** (≥256 MiB, client-side only) is the **only** KDF | `crypto_kdf` purpose-separation derives content key, manifest signing key, device-label key from one master. |
| Fingerprint / SAS | **BLAKE2b** over the raw 32-byte pubkey | Rendered as a 4–6 word list / QR for out-of-band verification. |
| Capability token | 256-bit CSPRNG, stored only as a **BLAKE2b hash** | Bearer capability; never logged in plaintext; per-relationship scoped. |

> ### ⚠️ Forward-secrecy claim RETRACTED
>
> `crypto_box_seal` / X25519 sealed box provides **confidentiality + sender anonymity, no PFS.** The
> phrase "ephemeral sender = forward secrecy" is **struck**. The honest consequence, documented
> loudly:
>
> - **One compromise of the recipient's long-term X25519 key retroactively decrypts every blob ever
>   sealed to it.** For shares, the recipient is the **therapist**; for game plans, the recipient is
>   the **owner**. The zero-knowledge server retains those blobs **append-only**.
> - **CEK rotation does NOT mitigate this**, because every version is sealed to the *same* long-term
>   recipient key.
> - To bound the harvest-now-decrypt-later window, **server-side blob retention for shares and game
>   plans is BOUNDED** — a configurable TTL plus hard-delete of superseded/expired blob bytes —
>   instead of "keep forever." (The concrete default TTL is an [open question](#13-open-questions-unresolved).)

### Share integrity / owner authentication (closes the content-injection break)

Because `crypto_box_seal` is **anonymous**, encryption alone does not prove *who* built a share. A
hostile server holding the therapist's public key could otherwise fabricate a fully valid sealed
share with attacker-chosen "clinical" content. To close this:

- The **owner MUST Ed25519-sign every share bundle** with their owner signing key.
- The **therapist MUST verify** that signature against the owner's **out-of-band-pinned Ed25519
  fingerprint** before rendering any record.
- The **AAD / signed transcript binds** `shareId || version || recipientFp || expiry ||
  ownerSigningFp`, so a stolen wrapped-CEK cannot be spliced onto a different ciphertext, and a
  bundle cannot be re-pointed at a different owner identity and still verify.

---

## 5. The share lifecycle (invite → enroll → share → read → revoke)

```
  INVITE            ENROLL (MFA)         PAIR (mutual OOB)      SHARE            READ           REVOKE/EXPIRE
 ────────         ──────────────       ─────────────────    ──────────       ────────        ──────────────
 owner mints  ──► therapist opens  ──► owner verifies    ──► owner seals  ──► therapist   ──► owner revokes /
 one-time         link, registers      therapist fp AND      curated         step-up         TTL elapses;
 invite +         passkey, gens        therapist verifies    subset to       sign-off,       server stops
 OOB short        X25519+Ed25519       owner fp, both        pinned key,     fetches,        serving FUTURE
 code             keypairs in client   PIN (TOFU)            signs bundle    decrypts        fetches (honest
                                                                             in-memory        server only)
```

### 5.1 Invite (owner mints; OOB delivery)

The owner — and **only** the owner — mints a single-use invite. The invite carries **no secret the
server can use to impersonate the therapist**; it bootstraps registration only.

- The owner creates an invite record; the server stores `inviteId`, the **Argon2id hash** of a short
  out-of-band code (plaintext never stored), the share scope this therapist may be granted, a TTL
  (e.g. 72 h to complete enrollment), and `status: PENDING`.
- The owner receives back a **link** plus a **4–6 word verification code** shown locally, and
  delivers them **out of band** — read over the phone in session, handed on paper, or QR shown in
  person. **No outbound email / SMS / magic links** (that would violate the no-egress principle; see
  [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md)).
- **Invite redemption uses capped backoff**, **not** permanent burn-after-5. Burn-after-5 is a
  *denial-of-enrollment* vector (an attacker who can reach the invite endpoint locks the real
  therapist out forever). `inviteId`s are **unguessable**, and the enroll page is served with **no
  referrer**.

### 5.2 Enroll + mutual out-of-band pairing (MANDATORY)

```
THERAPIST BROWSER / CLIENT                 SERVER                         OWNER (phone Sync flavor)
 ─────────────────────────                 ──────                         ────────────────────────
 1. open invite link
 2. enter 4-6 word code ───────────────►   verify code vs Argon2id hash,
                                            invite live? → registration
                                            challenge; status REDEEMING
 3. WebAuthn passkey register
    (residentKey=required,
     userVerification=required,
     prf extension w/ per-cred salt)
 4. gen X25519 + Ed25519 keypairs
    IN-CLIENT; wrap secret keys
    under PRF-derived KUK
 5. upload PUBLIC keys + webauthn
    cred pub + wrappedPrivateBlob ─────►   store pubkeys + opaque wrapped
    + prfSalt                               blob; status PENDING_VERIFY
                                                                          6. owner fetches therapist
                                                                             fingerprints
 7. ⇄ MUTUAL OOB SAS COMPARISON ⇄  (4-6 word BLAKE2b code / QR, read aloud in session)
    therapist reads owner's X25519+Ed25519 fp  AND  owner reads therapist's X25519+Ed25519 fp
                                                                          8. on match, owner PINS
                                                                             therapist key (TOFU);
                                            status ACTIVE                    therapist PINS owner key
```

Key points:

- **Keypairs are generated in-client and never uploaded.** The server receives only the two
  **public** keys plus the WebAuthn credential public key and an opaque wrapped-private blob it
  cannot open.
- **TOFU pinning is required in BOTH directions before any payload flows.** `recipientFp` inside a
  signed payload is **necessary but not sufficient** — an attacker can set it to a chosen value, so
  the **OOB SAS comparison is the binding step**. The server NEVER vouches for keys and NEVER
  mediates the encryption key unverified.
- **No share is ever encrypted to an unpinned key, and no game plan is ever accepted from an unpinned
  key** — the UI makes verification mandatory and hard to skip. The pairing/key-exchange protocol is
  the **named deliverable [PAIRING.md](COMPANION_ARCHITECTURE.md)**, not "(out-of-band)" hand-waving.

**MFA at enrollment.** The primary credential is a **WebAuthn/passkey** (`residentKey: required`,
`userVerification: required`) — itself two factors in one ceremony (possession of the authenticator +
biometric/PIN), needing no IdP and no outbound message. The **WebAuthn-PRF** output is the
**Key-Unlock Key (KUK)** that unwraps the therapist's reading/signing keys, so **"authenticated" and
"able to decrypt" are the same client-side gate**. See [§8](#8-authentication-sessions-and-step-up-sign-off).

### 5.3 Owner shares a curated subset

Curation happens on the **trusted owner endpoint** (phone Sync flavor). The output is a
**self-contained, materialized subset** — not a pointer/query into the full snapshot — so data
minimization is enforced **at the crypto layer**, not merely in the UI. Even a buggy or hostile
portal cannot leak more than what was sealed.

Selection dimensions:

| Dimension | Control | In the bundle? |
|---|---|---|
| **Date range** | `from`/`to` inclusive, or a rolling window ("last 90 days") | Yes — as materialized records, not as a query |
| **Record types** | mood entries, journal entries, **check-in scores/bands only**, sleep logs, trackers, activities | Per-type opt-in |
| **Per-record overrides** | exclude specific entries; strip free-text notes | Materialized post-redaction |
| **Self-harm item** | — | **Structurally absent** — never persisted, never materialized |

Build + encrypt (owner device; libsodium names identical on JVM/WASM):

```text
plaintext  = serialize(ShareBundle)                       // curated, redacted subset
CEK        = randombytes_buf(32)                          // fresh random 256-bit key per share
nonce      = randombytes_buf(24)                          // XChaCha20 192-bit random nonce
aad        = shareId || version || recipientFp || expiry || ownerSigningFp
ciphertext = crypto_aead_xchacha20poly1305_ietf_encrypt(plaintext, aad, nonce, CEK)

assert pinned(therapist_x25519_pub)                       // refuse to seal to an unpinned key
wrappedCEK = crypto_box_seal(CEK, therapist_x25519_pub)   // anonymity only, NO PFS
ownerSig   = crypto_sign_detached(transcript(aad), owner_ed25519_sk)  // MANDATORY owner signature

PUT  (server-derived blob path)   body: nonce||ciphertext
     headers: X-Wrapped-CEK, X-Owner-Sig, X-Share-Meta{ version, expiry, contentHash }
```

The server stores `nonce||ciphertext`, the sealed CEK, the owner signature, and metadata — and can
decrypt **none** of it.

### 5.4 Therapist reads

```text
// step-up sign-off (fresh WebAuthn assertion bound to the live session) §8
GET  /shares/{relationship}/current        // server checks: session bound, token valid, not expired, not revoked
   → 200: nonce||ciphertext, X-Wrapped-CEK, X-Owner-Sig
   → 403 if expired/revoked (server enforces on metadata it CAN see)

// in client, after PRF unlocks the therapist secret key:
CEK       = crypto_box_seal_open(wrappedCEK, ther_pub, ther_sec)
plaintext = crypto_aead_xchacha20poly1305_ietf_decrypt(ciphertext, aad, nonce, CEK)
VERIFY crypto_sign_verify_detached(ownerSig, transcript(aad), PINNED owner_ed25519_pub)
  → fail ⇒ REFUSE to render (forged / wrong owner / spliced)
render(ShareBundle)                        // thin in-memory viewer, no default disk cache
```

The **thin in-memory viewer is the default**: it does not persist decrypted plaintext or the
unwrapped CEK to disk; it decrypts per session and re-fetches each time. This shrinks the
un-revocable residue to **at most one session's worth** of the curated subset.

### 5.5 Refresh (as the owner logs new data)

A long-running share can reflect new entries **without re-pairing**: the owner re-materializes the
subset for the current scope, generates a **new CEK**, AEAD-encrypts, seals the new CEK to the
**same pinned therapist key**, re-signs, and `PUT`s **version N+1** (append-only). The therapist
always fetches `/current`. Scope can only **narrow or shift forward** by owner action — the therapist
cannot request a wider range. Expiry extension is always an explicit owner action; there is **no
auto-renew**.

---

## 6. What the therapist CAN and CANNOT see

| The therapist CAN see | The therapist CANNOT see |
|---|---|
| Exactly the records the owner **materialized** into the share — within the chosen date range and record types, post-redaction | Anything outside the curated subset — other dates, other record types, excluded entries, stripped notes |
| Check-in **scores and bands only** | Raw self-harm / item-9 responses (**structurally absent**) |
| The share's own provenance: shareId, scope, owner fingerprint, schema version, created-at | The owner's master snapshot, the owner's sync passphrase, any of the owner's private keys |
| Game plans they themselves authored (and the owner's acceptance, if surfaced) | Any *other* patient's data; the owner's data after revocation+re-key (future versions) |

| The **server** CAN see | The **server** CANNOT see |
|---|---|
| Opaque ciphertext blobs; public keys + fingerprints; capability-token **hashes**; routing metadata (opaque inbox token, sizes, timestamps, expiry, revoke flag, version cadence) | Any private key, any unwrapped CEK, any passphrase, any plaintext; **which** individual records were viewed; a forged-but-valid bundle (owner signature prevents it) |

> ### Metadata minimization is a REQUIREMENT, not an open question
>
> `recipient_fp` is a stable cross-owner correlator that could reconstruct a therapist's **whole
> patient panel** (caseload re-identification) — a risk to the therapist's **other** patients, not
> just one owner. The threat-model docs **stop calling this "harmless routing metadata."** Required
> fixes:
>
> - **Remove `recipientFp` / owner fp from query strings**; route via **opaque per-relationship inbox
>   tokens** instead.
> - **Pad blob sizes to fixed buckets BY DEFAULT** (not optional). Per-version size deltas (an acuity
>   proxy) and `isTombstone + size` (a withdrawal de-anonymizer) are mitigated by padding. (Concrete
>   buckets and confirmation that padding is on-by-default for all blob types are an
>   [open question](#13-open-questions-unresolved).)
> - Keep the access log **owner-local** where possible; **short-retention** when server-stored.
> - Minimize `device_label` / timestamp exposure.

---

## 7. Game plans — therapist write-back

A **game plan** is therapist-authored, non-diagnostic **guidance** (goals, exercises, between-session
tasks, notes, a review cadence) that flows **one-directionally** back into the owner's app as a
**signed, encrypted, append-only inbound object** the owner must **explicitly accept**.

### 7.1 Design tenets

1. **One-directional, append-only, never a server-side mutation.** The therapist *proposes*; the
   owner *adopts*. Nothing takes effect until the owner's device verifies and imports it.
2. **Server is zero-knowledge and cannot forge a plan** — it holds no signing key; the owner verifies
   an Ed25519 signature it cannot produce.
3. **Therapist content is segregated from owner content.** Plans land in a **new `game_plans` table
   (DB v13)** — **NOT** the existing `treatments` table.
4. **Non-diagnostic by framing** (see [§2](#2-the-non-diagnostic-prime-directive)).

> ### Why a NEW `game_plans` table, not `treatments`
>
> The existing `treatments` table is confirmed in code (`Treatment.kt`) as an **owner-authored,
> explicitly non-evaluative** sleep-marker (`KINDS = CPAP/Surgery/Oral appliance/Positional/
> Medication/Other`) whose KDoc says it is recorded *"never as a measure of whether the treatment
> works."* Writing therapist-authored clinical guidance into its free-text `note` would both
> misrepresent the schema and violate [../HANDOFF.md](../HANDOFF.md) §0. So the **game-plans track
> wins**: introduce **`game_plans` + `game_plan_items` + `game_plan_progress` at DB v13**. The
> therapist body stays **immutable / read-only / append-only**; owner-authored progress is a separate
> layer keyed by `(lineageId, itemRef)`. A plan MAY *optionally* spawn an owner-authored
> `Treatment`/`Goal` marker — but **only on explicit owner accept** — so guidance never auto-lands in
> owner data.

### 7.2 Authoring → signing → encryption → display → acknowledgement → update → withdrawal

```
 THERAPIST CLIENT                       SERVER (zero-knowledge)        OWNER (Sync phone)
 ────────────────                       ───────────────────────       ──────────────────
 1. author plan (goals/exercises/
    tasks/notes/review cadence)
 2. canonicalize → bytes
 3. SIGN bytes w/ therapist Ed25519
 4. crypto_box_seal to OWNER X25519 ──► store opaque blob +      ──►  5. fetch ciphertext
    (sender anonymity, NO PFS)           routing metadata only         6. open sealed box w/ owner X25519
                                         {lineageId, version,          7. VERIFY Ed25519 sig vs PINNED
                                          supersedes, authorFp,            therapist fingerprint
                                          recipientFp(opaque inbox),    8. check context+recipientOwnerFp
                                          isTombstone, size, hash}      9. if OK → PROPOSED (read-only)
                                                                       10. owner ACCEPTS → game_plans (v13)
                                                                       11. owner tracks progress (own layer)
                                                                       12. rides owner's E2EE snapshot
```

- **Authoring.** In the therapist client: goals (optional `targetPerWeek`), exercises, between-session
  tasks (optional `dueAt`/`recurrence`), free-text notes, and an optional review cadence.
- **Signing (sign-then-encrypt with domain separation).** The therapist Ed25519-signs the
  **canonical JSON** of the payload, then `crypto_box_seal`s to the owner's X25519 key. The signed
  payload **explicitly names its recipient and context** — `recipientOwnerFp`, `lineageId`,
  `version`, and a fixed `context: "daymark.gameplan.v1"` — so a plan cannot be peeled and re-sealed
  to a different owner and still verify (defeats surreptitious forwarding).
- **Encryption to owner.** Confidentiality is `crypto_box_seal` to the owner's pinned X25519 key
  (**sender anonymity only, no PFS** — same retraction as [§4](#4-cryptographic-contract-one-primitive-set);
  one compromise of the **owner's** long-term X25519 key retroactively decrypts every plan ever sealed
  to it).
- **App display.** The owner device opens the sealed box, verifies the signature against the
  **pinned therapist fingerprint**, checks `context` + `recipientOwnerFp == self`, and only then shows
  the plan as a **read-only PROPOSED** item with the full disclaimer. A **signature-valid-but-wrong-
  fingerprint** plan is treated as **hostile** (server key substitution) and never auto-accepted.
- **Acknowledgement & progress.** Once the owner accepts, the therapist body is inserted **immutable**
  into `game_plans` / `game_plan_items`. The owner writes **only** `game_plan_progress`
  (acknowledge-the-plan; per-item `not_started / in_progress / done / skipped` + a private note),
  keyed by `(lineageId, itemRef)` so progress **carries forward** across version bumps.
- **Updates.** An update is a **new signed version** (same `lineageId`, `version=N`, `supersedes=N-1`)
  — **supersede, never mutate**. The prior version is marked `superseded`; history is preserved.
- **Withdrawal.** Withdrawal is an **authenticated signed tombstone** (`status: "withdrawn"`, same
  `lineageId`). The server cannot withdraw a plan on the therapist's behalf, nor silently un-withdraw
  one (it can't forge a higher signed version). The owner's prior progress notes are retained as the
  owner's own data; the plan becomes read-only history.

### 7.3 Inbound accept/verify state machine (owner side)

```
fetched blob
   │ open crypto_box_seal w/ owner X25519 priv ── fail ─► DISCARD (corrupt / not-for-me)
   ▼
 envelope { payload, sig, authorEd25519 }
   │ fingerprint(authorEd25519) == PINNED therapist fp ? ── no ─► REJECT "unknown author" (hostile)
   │ Ed25519_verify(authorEd25519, canonicalJson(payload), sig) ? ── no ─► REJECT (tampered)
   │ payload.context == "daymark.gameplan.v1" && recipientOwnerFp == my fp ? ── no ─► REJECT (misdirected)
   │ payload.version > highest accepted for lineageId ? ── no ─► IGNORE (stale / rollback attempt)
   ▼
 VALID ─► PROPOSED (read-only preview, full disclaimer)
            │ owner declines ─► recorded locally, never written as a plan
            │ owner accepts  ─► INSERT this version, mark prior "superseded",
            │                   carry forward progress by (lineageId, itemRef),
            └─                   if withdrawn → mark lineage withdrawn
```

The **PROPOSED gate is mandatory** — it is both the integrity boundary and the **consent / ethical**
boundary that keeps the tool non-diagnostic. The `canonicalPayloadJson` + signature + author
fingerprint are retained verbatim in the local table **and** the owner's encrypted `BackupData`
snapshot, so a plan remains **re-verifiable forever** — including after backup/restore on a new
device, independent of the server.

> **Snapshot integration does not widen readership.** Game-plan state rides the owner's existing
> symmetric-key-encrypted `BackupData` snapshot (v13, defaulted fields). Plans are already
> owner-readable after the inbound sealed-box decrypt, so embedding them adds **no new reader** and
> does **not** expose the sync passphrase to the therapist. The therapist→owner sealed-box channel and
> the owner's symmetric snapshot channel stay **independent**.

---

## 8. Authentication, sessions, and step-up sign-off

Full detail lives in [COMPANION_SECURITY.md](COMPANION_SECURITY.md); the therapist-relevant contract:

| Control | Spec |
|---|---|
| **Primary credential** | WebAuthn/passkey, `residentKey: required`, `userVerification: required`, attestation `none`. Two factors in one ceremony; phishing-resistant (origin-bound). |
| **Auth == decrypt** | The **WebAuthn-PRF** output is the **KUK** that unwraps the therapist's in-browser reading/signing keys, so the server never holds anything that decrypts. |
| **RP-ID / origin** | **Config-pinned**, never client-derived. `rp.id` and the origin allowlist come from explicit `DAYMARK_WEBAUTHN_RP_ID` / origin config, **not** from client-controllable `Host` / `X-Forwarded-Host`. Sub-path deployments re-add the prefix via `DAYMARK_BASE_PATH`; assertion origin verification is **exact**. |
| **Sessions** | Opaque **server-side** records (not JWTs), delivered as `HttpOnly; Secure; SameSite=Strict` cookies; **15-min idle / 8-h absolute**; bound to the credential id; per-session anti-CSRF token. Revocation is **instant**. |
| **Step-up sign-off** | `share.open`, `gameplan.publish`, `key.rotate`, `revoke` each require a **fresh, single-use, action-scoped (60 s)** WebAuthn assertion **bound to the active, live, non-revoked session id** — an assertion minted in one context cannot be executed from a stolen session, and the server returns blobs only to the bound session. The same biometric gesture proves intent **and** yields the PRF decryption secret. |
| **Capability tokens** | Per-relationship, stored only as a BLAKE2b hash, rate-limited, revocable, and **bound to the authenticated WebAuthn credential at first fetch** (no single-factor bearer fetch). |
| **Lockout / rate-limiting** | Per-credential **and** per-IP with exponential backoff (so one attacker IP cannot lock out the legitimate therapist); all lockouts audit-logged to the owner. |

> ### TOTP fallback is a weaker, honestly-flagged parallel custody path
>
> Therapists whose device has no platform authenticator and no security key may use **TOTP**
> (RFC 6238, 6-digit, 30 s, ±1 step). But TOTP-only therapists **cannot use PRF**, so their reading
> key is wrapped under a **separate Argon2id passphrase** — a **phishable, server-stored
> (Argon2id-hashed) authenticating secret** that breaks the "server holds nothing that authenticates"
> property. This is documented in [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §limits, **not
> glossed**. The TOTP authenticating secret is **distinct** from the single-use bootstrap invite
> code, client-set, high-entropy, rotatable, and never sent in cleartext. `signCount` regression /
> synced-passkey (`signCount=0`) clone-detection limits are documented. TOTP is **off by default and
> opt-in per relationship**, and it **never unlocks the reading key**, so even a server that knows the
> TOTP secret still cannot decrypt a share.

> ### ⚠️ The browser portal is NOT zero-knowledge against a malicious server
>
> **Stop claiming SRI/CSP make it zero-knowledge.** They are **inert** here: the same first-party
> origin serves both the SRI-referencing HTML and the assets, so a hostile operator rewrites both
> together. For anyone who types a passphrase (owner) or unlocks the reading key (therapist) **in the
> server-served portal**, a full server compromise can capture secrets. It is **worse for the
> therapist**, because the PRF-derived KUK is **deterministic**: a single tampered-JS capture yields
> **permanent offline decryption**. Resolution:
>
> 1. The **native phone Sync flavor is the ONLY secret-handling owner path**; entering the master
>    passphrase into the browser portal is **forbidden / strongly discouraged** in product copy.
> 2. The therapist decrypt path is moved toward a **pinned/installed client** OR the portal bundle is
>    **pinned out-of-band**, and **`prfSalt` is rotatable** so one capture is not forever.
> 3. **SRI is dropped** as a stated mitigation against tampered portal JS. The browser portal is
>    documented as a **lower-assurance convenience path**, not a zero-knowledge one. (Whether an
>    installed/pinned therapist client is in scope for v1 is an [open question](#13-open-questions-unresolved).)

---

## 9. Revocation, rotation, expiry — honestly scoped

A layered model. You cannot un-share a plaintext someone already read; we make the window small,
future access reliably revocable **against an honest server**, and the residue minimal.

| Mechanism | What it does | Enforced where | Against a **colluding** server? |
|---|---|---|---|
| **Expiry (TTL)** | After `expiry`, server refuses to serve the blob + wrapped CEK; session invalidated. | Server (metadata only) | **No.** Stops fetches against an **honest** server only. |
| **Revocation flag** | Owner sets `revoked=1`; honest server immediately stops serving; capability token invalidated; owner stops re-publishing. | Server + owner | **No.** Honest-server-only. |
| **CEK rotation** | New data published under a new CEK. | Crypto (owner) | Protects data published **after** rotation, **only against the old key** — not against the therapist key reading already-pushed versions. |
| **Therapist RE-KEYING** | Owner re-pairs to a new verified therapist key and re-wraps future shares to it. | Crypto + OOB re-verify | **The only real future-data revocation.** |

> ### ⚠️ Revocation does NOT defeat a colluding server (claim RETRACTED)
>
> The old "CEK rotation defeats a colluding server" claim is **retracted.** Because the wrapped CEK
> **persists on disk** and the therapist long-term key **never rotates on revoke**, an already-pushed
> (even not-yet-decrypted) share remains readable to the therapist key against a **malicious /
> colluding** server. Server-side expiry + revoke-flag stop **future fetches against an HONEST server
> only.**
>
> **Three honest guarantees** are what we actually provide:
>
> 1. Future **server-mediated fetches blocked on an honest server**.
> 2. Data published **after re-key** is unreadable to the **old** key.
> 3. Already-decrypted plaintext is **never** recallable — same as handing someone a PDF.
>
> The thin in-memory therapist viewer (no default disk cache) shrinks the residue to **one session**.
> *(Whether v1 ships therapist re-keying as the real revocation primitive, or accepts and loudly
> documents honest-server-only + future-data-via-rotation, is an [open question](#13-open-questions-unresolved).)*

Revocation flow (owner taps "Revoke share"):

```
1. (client) stop any scheduled refresh for this relationship.
2. POST .../revoke → honest server sets revoked=1, zeroes cap_token_hash, invalidates the session.
3. (optional hard delete) → remove blob bytes from the volume (metadata tombstone retained for the log).
4. Server logs a REVOKED event.
5. (real future-data revocation) → owner RE-PAIRS to a new verified therapist key; future shares wrap to it.
```

**Therapist key loss = owner re-invitation + re-pair (re-verify the new fingerprint) + re-wrap.**
There is **no escrow** by design — the owner is the sole root of trust. An optional, self-held
Argon2id-passphrase-protected recovery file at enrollment can restore a **single** lost device's same
reading key without bothering the owner; it never touches the server.

---

## 10. The audit log (owner-readable, metadata-only)

Principle: log **EVENTS, not CONTENT.** The owner's question is "did my therapist actually look, and
did anything tamper?" — answerable without a per-record surveillance trail.

| Logged | NEVER logged |
|---|---|
| timestamp, relationship/shareId, event type (`invite.redeem`, `auth.success`, `auth.fail`, `share.open`, `gameplan.publish`, `share.revoked`, `expired`, `version.published`, `lockout`), acting credential id | which individual records/moods were viewed; any plaintext; any key/CEK/PRF output; TOTP codes or passphrases |

- **Append-only, owner-readable**, short configurable retention (default **90 days**), then pruned —
  the log is itself sensitive metadata.
- **Optional IP is OFF by default** (an IP geolocates the clinic).
- `share.open` and `gameplan.publish` may carry a **therapist Ed25519-signed attestation**, verified
  against the pinned therapist fingerprint, so a hostile server **cannot forge** an access event.

> ### ⚠️ "Access cannot be hidden" is RETRACTED until a signed monotonic chain ships
>
> Signed attestations prevent **forgery** but **NOT silent suppression / reordering / truncation** by
> the server — without a hash-chain or sequence number, a hostile server can simply **never return** an
> attestation and the owner cannot detect the gap. **Fix:** add a **signed monotonic sequence number /
> hash-chain** to attestations so omitted events are detectable. **Until that ships, the "access cannot
> be hidden" claim is retracted.** *(Whether the chain lands in v1 is an
> [open question](#13-open-questions-unresolved).)*

---

## 11. Honest limits (read this twice)

| Limit | Honest posture |
|---|---|
| **No forward secrecy on sealed boxes** | One compromise of the recipient long-term X25519 key (therapist for shares, owner for game plans) retroactively decrypts **every** blob ever sealed to it. CEK rotation does not help. Mitigation: **bounded** server-side retention (TTL + hard-delete) to make the harvest window finite. |
| **Revocation is honest-server-only** | Already-pushed shares remain readable to a colluding server + therapist key. Real future-data revocation requires therapist **re-keying**. |
| **Browser portal is lower-assurance** | Same-origin SRI/CSP do not stop a malicious operator from serving tampered JS; the deterministic PRF-KUK makes one capture permanent. Native phone is the only secret-handling owner path; therapist path should be pinned/installed; `prfSalt` rotatable. |
| **MITM at pairing** | The server is not a PKI. Mitigated **only** by **mandatory mutual OOB SAS verification + TOFU pinning**. `recipientFp` alone is insufficient. |
| **Withholding / reorder** | A hostile server can withhold or reorder share/plan versions. Monotonic version chains make gaps **detectable**, not **preventable**; the phone-local copy stays authoritative. |
| **Already-decrypted plaintext** | Cannot be clawed back — like a printed PDF. Bounded to one session by the thin in-memory viewer. |
| **TOTP path** | A phishable, server-stored authenticating secret that breaks "server holds nothing that authenticates." Off by default; never unlocks the reading key. |
| **Audit suppression** | Without the signed monotonic chain, a hostile server can silently omit access events. "Access cannot be hidden" is retracted until the chain ships. |
| **Metadata / caseload re-identification** | The relationship's existence and cadence leak to the server operator; a cross-owner correlator could reconstruct a therapist's whole panel. Mitigated by opaque inbox tokens, default size padding, and minimization — but the relationship graph cannot be fully hidden on a box the owner runs. |
| **Therapist endpoint compromise** | Out of scope — malware on the therapist's machine can read what the therapist can read. Each party secures their own endpoint. |
| **Owner→therapist plaintext egress** | Sharing sends the owner's plaintext (post-decrypt on the therapist device) to a **third human party**. This is a deliberate, consent-gated egress, stated honestly — [../PRIVACY.md](../PRIVACY.md) / [COMPANION_SECURITY.md](COMPANION_SECURITY.md) **retract** "there is no server, so there is no server-side surface" (now false for the opt-in Sync flavor). |

---

## 12. v1 scope lock

**ONE therapist, ONE keypair, ONE device.** Out of scope for v1 and documented as added trust surface:

- multi-therapist or multi-device fan-out (multiple wrapped CEKs per bundle, or per-relationship
  shared keys);
- a therapist practice / dashboard;
- true concurrent multi-device row-merge of owner data (v1 sync is **single-writer, last-snapshot-wins**;
  true row-merge is gated behind a prerequisite UUID + `updatedAt` schema migration — see
  [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md)).

Therapist key loss = owner re-invitation + re-pair (re-verify new fingerprint) + re-wrap; **no
escrow**. This bounds complexity and keeps the **owner as the sole root of trust**.

---

## 13. Open questions (unresolved)

These are explicitly undecided and belong to the maintainer; nothing here is a settled guarantee.

1. **Ship the sharing + game-plan tracks at all in the first release, or land sync-only first?** The
   whole multi-party portal is a step toward the clinical posture the project disclaims; this is a
   sequencing decision.
2. **Revocation against a colluding server:** ship therapist **re-keying** as the real v1 primitive,
   or accept + loudly document honest-server-only + future-data-via-rotation?
3. **Browser portal therapist path:** is an installed/pinned therapist client (or OOB bundle pinning +
   rotatable `prfSalt`) in scope for v1, or is the deterministic-KUK tampered-JS break an accepted,
   documented v1 limitation?
4. **Server blob-retention TTL** for shares/game-plans: a concrete default (e.g. 30/90 days, or
   hard-delete on supersede/expiry) to bound the no-PFS harvest window — needs a number.
5. **Attestation hash-chain:** implement the signed monotonic sequence for suppression-detection in
   v1, or drop "access cannot be hidden" until later?
6. **Size-padding bucket scheme** (e.g. 4/16/64 KiB) and confirmation that padding is mandatory-default
   for **all** blob types including snapshots.
7. **Sub-path / RP-ID for LAN / `.local` / bare-IP self-hosters:** WebAuthn requires a real origin;
   which deployments are officially supported vs documented-as-unsupported.

---

## 14. Build checklist (therapist surfaces)

- [ ] One libsodium primitive set compiled to JVM + WASM; golden-vector tests prove byte-identical
      encrypt/decrypt/sign/verify across both. **XChaCha20-Poly1305 only**; no AES-GCM "equivalent".
- [ ] Owner-mints-invite flow; Argon2id-hashed OOB code; **capped backoff** (not burn-after-5);
      unguessable `inviteId`; no-referrer enroll page.
- [ ] WebAuthn enrollment (`residentKey/userVerification: required`, PRF); keypairs generated
      **in-client**, only public keys + opaque wrapped-private blob + `prfSalt` uploaded.
- [ ] **Mutual** OOB SAS verification + bidirectional TOFU pinning ([PAIRING.md](COMPANION_ARCHITECTURE.md));
      refuse to seal to / accept from an unpinned key.
- [ ] Owner curation UI (date range + record-type + per-record exclude + note stripping) on the phone
      Sync flavor; self-harm item structurally absent; check-ins scores/bands only.
- [ ] Per-share envelope encryption (fresh random CEK, sealed to pinned therapist key) **plus
      MANDATORY owner Ed25519 signature**; AAD binds `shareId||version||recipientFp||expiry||ownerSigningFp`.
- [ ] Therapist read path: config-pinned RP-ID/origin; step-up sign-off bound to the live session;
      **thin in-memory viewer**, no default disk cache; refuse-to-render on owner-signature failure.
- [ ] Game plans: `game_plans` + `game_plan_items` + `game_plan_progress` at **DB v13**; sign-then-encrypt
      with `context` + `recipientOwnerFp` binding; PROPOSED accept gate; append-only supersede; signed
      withdrawal tombstone; `canonicalPayloadJson` retained for forever-re-verifiability; `BackupData` v13
      defaulted fields; optional adopt-as-`Goal`/`Treatment` on explicit owner accept.
- [ ] Revocation/expiry/rotation with **honest scoping** + therapist re-keying for real future-data
      revocation; **bounded** server retention (TTL + hard-delete of superseded/expired bytes).
- [ ] Owner-readable, metadata-only audit log (90-day prune, IP off-by-default) + optional signed
      attestations; **signed monotonic chain** for suppression-detection (or retract "non-hideable").
- [ ] Metadata minimization: **opaque per-relationship inbox tokens** (no fp in query strings),
      **default** size-bucket padding, minimized device-label/timestamp exposure.
- [ ] Non-diagnostic framing on **every** therapist surface (fixed, non-server banners); the
      [INSTRUMENTS.md](INSTRUMENTS.md) / [../PRIVACY.md](../PRIVACY.md) sections updated to state the
      owner→therapist plaintext egress and the opt-in server-side surface honestly.
- [ ] Lockout/rate-limit per-credential **and** per-IP; capability token bound to credential at first
      fetch; reverse-proxy/forwarded-header hardening per [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md).

---

## 15. Related documents

- [COMPANION_SCOPE.md](COMPANION_SCOPE.md) — purpose, boundaries, what ships and what does not.
- [COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md) — the zero-knowledge spine, sync model,
  `game_plans` schema, manifest anti-rollback, and **PAIRING.md** (the named pairing deliverable).
- [COMPANION_SECURITY.md](COMPANION_SECURITY.md) — full crypto contract, threat model, auth/session
  hardening, retractions, and honest limits.
- [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) — container, egress lockdown, reverse-proxy /
  trusted-proxy hardening, WebAuthn RP-ID config.
- [DOCKER_COMPANION.md](DOCKER_COMPANION.md), [ARCHITECTURE.md](ARCHITECTURE.md),
  [PRIVACY.md](PRIVACY.md), [INSTRUMENTS.md](INSTRUMENTS.md), [../HANDOFF.md](../HANDOFF.md) —
  baseline context.

---

> **Final reminder:** This is a **design-only** document. No Companion server, Sync flavor, therapist
> portal, invite flow, or `game_plans` table exists in the shipping app today. The flagship Daymark
> app remains fully offline with **no `INTERNET` permission**. Everything above is a requirement on a
> *possible* future build, honoring the app's no-network / zero-knowledge promise and its
> non-diagnostic prime directive.
