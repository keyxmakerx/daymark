# The phone and the Companion

What the Android app must do to join the Companion, and how much of it exists. The web
implementation under `companion/web/src/lib/` is the conformance oracle: the phone must produce and
accept byte-identical data, and the web tests are what it is checked against. The wire formats are
[SYNC_PROTOCOL.md](SYNC_PROTOCOL.md) and [COMPANION_PAIRING.md](COMPANION_PAIRING.md) §13.

Built today: the `sync` flavour, the Kotlin port of the sync and pairing cryptography, and its tests.
Nothing in the app talks to a server yet. The work is tracked in #138.

## 0. The default build stays offline

The flagship `foss` build declares no `INTERNET` permission and has no network code it could reach.
Everything the Companion needs lives in a separate, opt-in `sync` product flavour:

- One flavour dimension, `network`, with `foss` (the default) and `sync` (`applicationIdSuffix
  ".sync"`), in `app/build.gradle.kts`.
- `app/src/sync/AndroidManifest.xml` is the only place in the app that requests `INTERNET`.
- Network and Companion code lives under `app/src/sync/` only, so Gradle's source sets make it
  impossible for `foss` to reference it. This is structural, not a convention.
- CI dumps the permissions of the built `foss` APK and fails if `INTERNET` appears, and fails if the
  dump is empty, so the check cannot pass by seeing nothing.

## 1. Cryptography: byte-for-byte with the web

Use libsodium through lazysodium: `lazysodium-android` in the app, `lazysodium-java` in the tests. The
Android-free module `sync-crypto/` holds the port, so its tests run on a plain JVM with real
libsodium and no emulator; the `sync` flavour wires it to the Android binding
(`app/src/sync/kotlin/com/daymark/app/sync/SyncCryptoFactory.kt`, which nothing calls yet).

| Must match | Web reference | Kotlin | Built |
| --- | --- | --- | --- |
| Argon2id floor (256 MiB, 3 passes), 16-byte salt, 32-byte master | `sync/crypto.ts` | `SyncCrypto.kt` | Yes |
| Subkeys, context `dmsync01`: 1 sync key, 2 manifest seed | `sync/crypto.ts` | `SyncCrypto.kt` | Yes |
| Subkeys 3 and 4: the owner's X25519 and Ed25519 seeds | `owner/identity.ts` | — | No: #{F2} |
| Snapshot envelope `DMS1 \| 0x01 \| nonce \| ciphertext`, AAD `daymark.snapshot.v1\|lineage\|version` | `sync/crypto.ts` | `SyncCrypto.kt` | Yes |
| Manifest signing bytes | `sync/crypto.ts` | `SyncCrypto.kt` | Yes |
| Base64: RFC 4648 §5, URL-safe, no padding | everywhere | `SyncCrypto.kt` (plain `java.util.Base64`, because lazysodium's own helper is standard base64) | Yes |
| CPace (CPACE-RISTRETTO255-SHA512) | `pairing/cpace.ts` | `CpaceCrypto.kt` | Yes |
| Pairing channel identifier and envelopes | `pairing/relay.ts`, `pairing/envelope.ts`, `pairing/payloads.ts` | — (`lvCat` exists, no builder) | No: #{F2} |
| Assignment and game-plan opening: seal-open, then verify against the pinned clinician key, context and recipient fingerprint | `assignments/crypto.ts`, `therapist/gamePlan.ts` | — | No: #{F3} |
| Share sealing | `share/sharecrypto.ts` | — | No: #{F2} |

The owner's key pair is derived from the master (subkeys 3 and 4), so the phone stores no separate
owner identity; that is what makes the phone and the browser the same owner. The vector in
`owner/identity.test.ts` (master bytes 0x00…0x1F to two fixed public keys) is the one the phone must
reproduce.

**What the tests pin** (run by `./gradlew test`, which includes `:sync-crypto`): `SyncCryptoTest`
checks round trips, AAD binding, tampering, the base64 conformance vector of SYNC_PROTOCOL.md §1.2,
and cross-language vectors generated from `crypto.ts`. `CpaceCryptoTest` checks the CFRG test vectors
(generator string, generator point, both messages, the key), a live exchange, and that a wrong code
diverges silently. `LazySodiumParityTest` checks that the Java and Android bindings expose the same
surface, since the tests run on one and the app on the other. The same checks on a real device are
not built: #{F9}.

## 2. Sync of the owner's own data

Settings → Sync: a server address, the bearer token and the sync passphrase; push the existing backup
snapshot (`BackupManager`) as the plaintext, append-only, at `max(existing) + 1`; pull fetches the
newest and decrypts it. Sync is single-writer, last-snapshot-wins: the schema has no per-row ids or
timestamps, so rows are never merged. Not built: #{F1}. Whether it stays single-writer is a decision:
#{F12}. Refusing an older snapshot presented as the newest needs a signed manifest and a watermark
kept on the device: #{F4}.

Neither the server nor anyone else can reset the app PIN or the sync passphrase. The owner's email
recovery re-issues the server's bearer token and nothing else.

## 3. Assignments and game plans, inbound

Once the owner has paired with a clinician (§4), a relationship client reads the per-relationship
channels, opens each item and verifies it against the pinned clinician key, then checks it against
the owner's grant exactly as `assignments/validate.ts` does: capability granted, type matching the
capability, catalogue item known, setting key on the allowlist. An item applies automatically only if
its capability is granted in `auto` mode, and never for a setting; everything else goes to an inbox to
accept or decline.

- **Game plans go in their own tables, never in `treatments`.** The clinician's body is immutable and
  append-only (`game_plans`, `game_plan_items`); the owner's own progress is a separate table keyed by
  `(lineageId, itemRef)` (`game_plan_progress`). Accepted assignments go in `assignments`.
- **Schema.** The app is at schema v18; these tables arrive in a new version, v19 or later, with a
  committed schema export and a `MigrationTest` hop, never a destructive fallback.
- **Settings** apply only for the allowlisted keys (`visibleSelfChecks`, `reminderTime`,
  `reminderCadence`, `theme`), never PIN, lock, encryption or network settings.
- Not built: #{F3}. The owner's console on the web has an assignment inbox, but it does not yet save a
  decision: #{W4}.

## 4. Pairing: the phone as the owner's device

The phone runs the owner's half of the same pairing protocol and becomes the device that approves a
clinician, because an installed app cannot have its code rewritten by the server. The bytes are in
COMPANION_PAIRING.md §13; the phone's obligations, including the rule that it raises **one local
notification per invitation** when a reply does not open (issue #112) and nothing else, are in
COMPANION_PAIRING.md §14.

It also gains a screen listing its connections: who, since when, their key fingerprints, what they
can see in plain words, and Revoke. Not built: #{F2}. The phone as the anchor for the audit chain's
head: #{F5}. A heartbeat between phone and server: #{F6}. Signed requests instead of the bearer token:
#{F7}. Pairing the phone with the server by QR code: #{F8}.

## 5. What CI checks

`.github/workflows/build.yml` runs on every push. It builds both flavours in debug and release (so R8's
shrinking, which JNA's reflection makes risky, is exercised for `sync`), runs the unit tests including
`:sync-crypto`, runs the `foss` permission check, and compiles the instrumented tests without running
them: no device runs in CI, so nothing has executed the ristretto code on a phone (#{F9}, #147).

Only `foss` is released. How the `sync` build would be distributed is a decision: #{F10}. It needs its
own privacy statement before it ships: #{F11}.

## 6. The steps, in order

1. The `sync` flavour, the crypto port and its host-JVM conformance tests, and CI. **Built.**
2. Snapshot push and pull: #{F1}.
3. The schema version with the game-plan, progress and assignment tables: #{F3}.
4. Inbound assignments and game plans, with the acceptance inbox: #{F3}.
5. The owner's half of pairing, grants and shares from the phone, and the connections screen: #{F2}.
6. The anti-rollback watermark (#{F4}), the audit anchor (#{F5}), the heartbeat (#{F6}), signed
   requests (#{F7}), QR pairing (#{F8}).
7. The crypto tests on a real device: #{F9}.
