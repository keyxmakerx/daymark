# Daymark Phone — Milestone 2b: Sync flavor + assignment/game-plan integration (Kotlin)

> **Status: build order §6 step 1 done (sync flavor scaffold + CI + crypto conformance);
> steps 2-6 (sync push/pull, DB v13/v14 migrations, inbound assignments/game-plans +
> acceptance inbox, pairing/Grant/share) are NOT built yet.** §1's crypto now has a real
> Kotlin port (`sync-crypto/`, a pure-JVM module shared by the `sync` flavor at runtime and
> by its own host-JVM unit tests — no Android SDK or emulator needed to verify it) that
> passes both the `SYNC_PROTOCOL.md` §1.2 base64 conformance vector and a set of
> cross-language vectors independently generated from the actual `crypto.ts` reference. No
> Android SDK is available in this doc-authoring environment either, so the flavor
> scaffold/manifest/CI-workflow pieces are **CI-verified only**, per this doc's original
> design. The web reference implementations under `companion/web/src/lib/**` and the tests
> there remain the **conformance oracle**: the Kotlin here must produce/consume
> byte-identical envelopes. Authoritative crypto/API contracts:
> [SYNC_PROTOCOL.md](SYNC_PROTOCOL.md), [COMPANION_ASSIGNMENTS.md](COMPANION_ASSIGNMENTS.md),
> [COMPANION_SECURITY.md](COMPANION_SECURITY.md), [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md).

## 0. Prime constraint (unchanged)

The **default F-Droid build stays offline** — no `INTERNET` permission, no network code
reachable. Everything here lives in a **separate `sync` product flavor** ("Daymark Sync")
that adds `INTERNET` only when the user deliberately installs it. The privacy claim of the
flagship build remains verifiable.

```kotlin
// app/build.gradle.kts
android {
  flavorDimensions += "network"
  productFlavors {
    create("foss")  { dimension = "network" }          // default: NO INTERNET (unchanged)
    create("sync")  { dimension = "network" }           // adds INTERNET (+ sync/portal code)
  }
}
// src/sync/AndroidManifest.xml adds <uses-permission android:name="android.permission.INTERNET"/>
// Network + assignment code lives in src/sync/… ONLY, so foss cannot reference it.
```

## 1. Crypto — mirror the TS reference exactly (lazysodium)

Use **lazysodium-android** (libsodium JNI). Reproduce, byte-for-byte, the reference in
`companion/web/src/lib/sync/crypto.ts` + `assignments/crypto.ts` + `share/sharecrypto.ts`:

- **KDF:** Argon2id (`crypto_pwhash`, `ALG_ARGON2ID13`), `memMiB ≥ 256`, `ops ≥ 3`, 16-byte
  salt; then `crypto_kdf_derive_from_key` with context `"dmsync01"`, subkey ids 1 (SYNC_KEY),
  2 (manifest seed) — same as `SYNC_PROTOCOL.md §1`.
- **Snapshot envelope:** `MAGIC("DMS1") | FMT(1) | nonce(24) | XChaCha20Poly1305(plaintext, AAD, nonce, SYNC_KEY)`,
  `AAD = utf8("daymark.snapshot.v1|" + lineage + "|" + version)`.
- **Base64 everywhere:** RFC 4648 §5 **URL-safe, no padding** (conformance vector
  `00..0F → "AAECAwQFBgcICQoLDA0ODw"`). A standard-base64 encoder will be rejected by the reader.
- **Assignment / game-plan / share:** owner & therapist X25519 + Ed25519 keys; the write-back
  primitive is `Ed25519.sign(payload-with-context+recipientFp)` then `crypto_box_seal(recipientPub)`;
  the reader `crypto_box_seal_open`s then verifies the signature against the **pinned** sender
  fingerprint and checks `context` + `recipientOwnerFp` (see `assignments/crypto.ts`
  `ASSIGNMENT_CONTEXT` — reject wrong-context and cross-owner splices). Shares: random XChaCha20
  CEK sealed to the therapist, bundle owner-signed, AAD binds `shareId|version|recipientFp|expiry|ownerSigningFp`.
- **Owner keys at rest:** wrap the owner X25519/Ed25519 private keys in the existing
  `EncryptedSharedPreferences` / Keystore store (mirrors `keyStore.ts`); never uploaded.

**Conformance test (CI):** an instrumented test that encrypts on-device and asserts the web
`decryptSnapshot`/`openAssignment` accepts it (and vice-versa) using shared golden vectors.

## 2. Sync (owner's own data)

Settings → **Sync**: pair with a server URL + access token + sync passphrase; derive keys;
`PUT /v1/snapshots/{lineage}/{version}` the encrypted `BackupData` (append-only, `max(existing)+1`);
pull = fetch head + decrypt. v1 is **single-writer, last-snapshot-wins** (the schema has no
per-row UUID/updatedAt — do NOT attempt row-merge; see `SYNC_PROTOCOL.md §Sync`). Reuse the
existing `BackupManager` snapshot as the plaintext.

## 3. Therapist assignments + game plans (inbound)

The `sync` flavor adds a **relationship client** that, when the owner has paired with a
therapist (§4), polls the per-relationship inbox (`RelationRoutes` on the server), fetches
opaque blobs, and `openAssignment` / opens game plans, **verifying against the pinned
therapist fingerprint**. Then:

- **Assignments** are checked against the local **Grant** (`assignments/validate.ts` logic,
  ported): capability granted? type↔capability match? catalog item passes the non-diagnostic
  gate? setting key on the allowlist? Valid + `apply=auto` (never for settings) → apply;
  else → **acceptance inbox** for the owner to accept/decline.
- **Game plans** land in a **new segregated `game_plans` table (DB v13)** — never the existing
  `treatments` table. Therapist body is immutable/append-only; owner-authored progress is a
  separate layer keyed by `(lineageId, itemRef)` (`game_plan_progress`).

### 3.1 Room schema (new)

| Table (DB v13/v14) | Purpose | Writer |
|---|---|---|
| `game_plans`, `game_plan_items` | therapist-authored plan body (signed, verified) | inbound only (read-only to owner) |
| `game_plan_progress` (`lineageId,itemRef`) | owner's progress/reactions | owner |
| `assignments` | accepted assignments (questionnaire/task/large/reminder/goal refs) | inbound + owner-accept |
| `instrument_results`, `task_results` (v14) | self-check + attention-task outputs (from COMPANION_FEATURES.md §5) | owner |

Migrations are **additive/non-destructive** with exported schemas + a `MigrationTest` hop
(matches the app's existing migration discipline). Accepted **settings** apply only to the
**allowlist** (`visibleSelfChecks`, `reminderTime`, `reminderCadence`, `theme`) — never
PIN/lock/crypto/network keys.

## 4. Pairing (owner side)

Owner generates X25519+Ed25519 keypairs (wrapped at rest), exchanges fingerprints with the
therapist via a **mutual out-of-band short-authentication-string** (the `share/pairing.ts`
wordlist + SAS), and pins the therapist's keys. The owner then issues the signed **Grant**
(capabilities + apply modes) and can build/send **shares** (curated, scores/bands only).

### 4.1 The code-based ceremony, and both of its envelopes

The web's pairing is the contract the phone has to meet, not a different design. The owner speaks
an eight-character code, the clinician types it, CPace turns that into a 64-byte ISK on both sides,
and **two** sealed envelopes travel through the server, which holds a key for neither:

- **E1, clinician → owner**, on the reply: their two public keys, a display name, and the enrolment
  ticket they chose. Sealed with the ISK under direction `therapist-to-owner`.
- **E2, owner → clinician**, on the approval: `{ v: 1, boxPubB64, signPubB64 }` — the owner's two
  public keys and nothing else. Sealed with the same ISK under direction `owner-to-therapist`.

The phone's owner side must produce E2 at approve, byte-compatibly:

- **The identity is DERIVED, not generated.** `crypto_kdf_derive_from_key` over the same master with
  context `"dmsync01"`, **subkey id 3 → the X25519 seed, subkey id 4 → the Ed25519 seed**, each
  32 bytes, then `crypto_box_seed_keypair` / `crypto_sign_seed_keypair`. Ids 1 and 2 are the sync
  layer's (§1) and are not free; 3 and 4 are reserved for this on both platforms. The reference is
  `companion/web/src/lib/owner/identity.ts`, and `identity.test.ts` pins a fixed master to fixed
  public keys precisely so the Android side can be tested against the same vector. Deriving is what
  makes the phone and the browser the same owner rather than two: nothing migrates.
- **The envelope.** Sealing key `crypto_generichash(32, utf8("daymark/pairing/key/v1|" + direction),
  key = ISK)` — the whole 64-byte ISK as the BLAKE2b key. Wire shape `version(1) | nonce(24) |
  XChaCha20Poly1305(payload, AAD, nonce, key)`, AAD `utf8("daymark/pairing/env/v1|" + sidB64 + "|" +
  direction)`. The sid is exactly the base64url string the owner posted. Reference:
  `companion/web/src/lib/pairing/envelope.ts`.
- **The payload.** UTF-8 JSON, keys in the order `v, boxPubB64, signPubB64`, base64url with no
  padding, and strict on the way in: unknown `v`, a missing or extra field, or a key that is not
  32 bytes is refused as a whole rather than repaired. Reference:
  `companion/web/src/lib/pairing/payloads.ts`.
- **When.** At approve and not before: a run the owner abandons after a mismatch must never carry
  their keys anywhere, so "the owner said yes" and "the clinician learned the owner's keys" stay one
  event. The approve request carries `{ enrolTicketB64, envB64 }` and the server refuses it without
  both.

A phone that skips E2 would enrol clinicians who can verify nothing the owner later signs — the
defect issue #101 was about. There is no fallback path for them to type the keys in instead; the web
deleted those fields.

**When a reply does not open (4.0b contract, issue #112).** The web console shows one notice on the
invitation screen and raises no notification, because the owner's half is a browser tab there and a
closed tab cannot raise one honestly (`companion/web/src/lib/pairing/copy.ts`, `mismatchTitle` /
`mismatchBody`). The phone can, and this is the whole of what it may do: **one local notification
per invitation**, reading *"A reply to your invitation needs a look."* — lock-screen visible, naming
nobody and counting nothing, so a phone on a table says only that the owner has something to open.
It is raised from the device's own knowledge, at the moment the envelope fails to open on the
device, and it is **raised once per invitation**: a second failed reply against the same invitation
adds nothing, because the thing being reported is "go and look", which does not become truer twice.
It **burns nothing and writes nothing to the server** — no report, no counter, no request of any
kind — and it must not change the polling cadence, on either side of the failure. A device that
started polling faster after a reply failed to open would have told the server, in traffic, that the
code was wrong; the server is the one party the pairing design refuses to tell. The notification is
a local read of a local fact, and nothing about it is visible from outside the phone.

## 5. What is CI-only verifiable / open

- Everything here needs an **Android build + instrumented tests** (emulator) — add a
  `companion`/app CI job (`android-actions/setup-android`, the existing `build.yml` pattern)
  running the conformance + migration tests. Cannot be verified in the headless doc-authoring env.
- **WebAuthn on mobile** is out of scope (TOTP is the therapist path); the owner never needs
  therapist auth.
- **PIN / passphrase recovery via the server is NOT possible** by design (zero-knowledge, no
  escrow): the server never has the PIN hash or the sync passphrase. See the "app ⇄ server
  email flows" open question in [COMPANION_SECURITY.md](COMPANION_SECURITY.md) — only the
  server **access token** (not the E2EE keys) could be re-issued by email.

## 6. Build order

1. `sync` flavor scaffold (+INTERNET, DI split) and the lazysodium crypto with golden-vector
   conformance tests. 2. Snapshot sync (push/pull). 3. DB v13/v14 migrations + the new tables.
4. Inbound assignments/game-plans + the acceptance inbox UI. 5. Pairing + Grant issuance +
   share building. 6. Android CI job.
