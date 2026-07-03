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
