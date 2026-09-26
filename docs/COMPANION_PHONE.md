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
- Network code, and Companion code that reaches a server, lives under `app/src/sync/` only, so
  Gradle's source sets make it impossible for `foss` to reference it. This is structural, not a
  convention. The database is one schema for both flavours, so the Companion's tables are in
  `src/main`, and so are the checks in `companion/`, which import nothing and can reach nothing
  (§3).
- CI dumps the permissions of the built `foss` APK and fails if `INTERNET` appears, and fails if the
  dump is empty, so the check cannot pass by seeing nothing.

## 1. Cryptography: byte-for-byte with the web

Use libsodium through lazysodium: `lazysodium-android` in the app, `lazysodium-java` in the tests. The
Android-free module `sync-crypto/` holds the port, so its tests run on a plain JVM with real
libsodium and no emulator; the `sync` flavour wires it to the Android binding
(`app/src/sync/kotlin/com/daymark/app/sync/SyncCryptoFactory.kt`, which nothing calls yet).

| Must match | Web reference | Kotlin | Built |
| --- | --- | --- | --- |
| Argon2id range: the floor (256 MiB, 3 passes) and the ceiling (512 MiB, 8 passes), refused outside it, the floor first, before anything is derived; 16-byte salt, 32-byte master | `sync/crypto.ts` | `SyncCrypto.kt` | Yes |
| The key document at `GET /v1/keydoc`, either kind, read strictly: `v` 1, the floor and the ceiling on every slot of any kind, URL-safe unpadded base64, exact lengths; a slot of a kind it does not know is skipped, as on the web (#403) | `sync/client.ts`, `recovery/dataKey.ts`, with the vector in `recovery/dataKeyVector.test.ts` | `KeyDocument.kt` | Yes |
| Wrapped-key slots: Argon2id per slot, XChaCha20-Poly1305 under the AAD `daymark.datakey.v1\|kind`; a passphrase opens the first passphrase slot, a code each recovery slot in turn | `recovery/dataKey.ts` | `SyncCrypto.kt` | Opening: yes. Writing: only in the tests |
| Reading a typed recovery code: JavaScript's `\s`, `-` and U+2010–U+2015 dropped, full Unicode upper case, the mod-31 check symbol checked before any derivation | `recovery/recoveryCode.ts` | `RecoveryCode.kt` | Reading: yes. Generating: no |
| Subkeys, context `dmsync01`: 1 sync key, 2 manifest seed | `sync/crypto.ts` | `SyncCrypto.kt` | Yes |
| Subkeys 3 and 4: the owner's X25519 and Ed25519 seeds | `owner/identity.ts` | — | No: #174 |
| Snapshot envelope `DMS1 \| 0x01 \| nonce \| ciphertext`, AAD `daymark.snapshot.v1\|lineage\|version`: read, never written | `sync/crypto.ts` | `SyncCrypto.kt` | Yes |
| Padding before encryption: a `u32` big-endian length, the plaintext, then zeros up to the standard size (#214) | `lib/padding.ts`, with the vector in `lib/padding.test.ts` | `Padding.kt` | Yes |
| Padded snapshot envelope, format 2, the only format written: `pad(plaintext)` under the AAD `daymark.snapshot.v2\|lineage\|version`; format 1 still read (#214) | `sync/crypto.ts`, with the vector in `sync/crypto.test.ts` | `SyncCrypto.kt` | Yes |
| The web console's lanes: lineages `lane_…`, a format-2 envelope under the AAD `daymark.lane.v1\|lineage\|version`, holding the records of SYNC_PROTOCOL.md §1.4; read, never written, by the phone | `sync/crypto.ts` (`decryptLaneVersion`), `lane/record.ts`, with the vector in `lane/laneVector.test.ts` | — | No: #346 |
| A signed request (#186): a fresh Ed25519 key for the server, never derived from the master; its id, base64url of BLAKE2b-128 of the public key; the eleven-line message and the four `X-Device-*` headers; a new 16-byte nonce for every attempt; nothing signed that the server would read differently, a signed header given twice or empty among it | the server's `auth/DeviceSignature.kt`, with the vector in `auth/DeviceSignatureVectorTest.kt` (SYNC_PROTOCOL.md §2.1) | `DeviceSignature.kt`, `DeviceKey.kt` | The crypto: yes. Sending: #432 |
| The pairing code (#189): ten symbols of the recovery code's alphabet, the last a mod-31 check symbol, read with the recovery code's normalisation and refused before anything is sent; its id and the redemption's proof | the server's `auth/DeviceSignature.kt` (SYNC_PROTOCOL.md §2.2) | `PairingCode.kt`, `DeviceKey.kt` | The crypto: yes. Redeeming: #432 |
| The QR code's text, `daymark-pair:v1?server=…&code=…`, each value spelled as `encodeURIComponent` spells it, and the https verdict: a scan decides exactly as typing its two values does, and an address that is not `https://` with a host, or that has a user name, query or fragment, is declined before anything is sent (#189) | the console's QR code (#431) | `PairingVerdict.kt`, which imports no libsodium | The verdict: yes. The scanner and the pairing screen: #432 |
| The six words of a device's key: BLAKE2b-256 over `daymark-device-words-v1`, a line feed and the key, six bytes into the 256-word list | `share/wordlist.ts`; the console's words: #431 | `DeviceWords.kt` | Yes |
| Manifest signing bytes | `sync/crypto.ts` | `SyncCrypto.kt` | Yes |
| Base64: RFC 4648 §5, URL-safe, no padding | everywhere | `SyncCrypto.kt` (plain `java.util.Base64`, because lazysodium's own helper is standard base64) | Yes |
| CPace (CPACE-RISTRETTO255-SHA512) | `pairing/cpace.ts` | `CpaceCrypto.kt` | Yes |
| Pairing channel identifier and envelopes | `pairing/relay.ts`, `pairing/envelope.ts`, `pairing/payloads.ts` | — (`lvCat` exists, no builder) | No: #174 |
| Assignment and game-plan opening: seal-open, unpad (an envelope that opens to `{` was sealed unpadded before #315 and is read as it is), then verify against the pinned clinician key, context and recipient fingerprint | `assignments/crypto.ts`, `therapist/gamePlan.ts` | — | No: #177 |
| Share sealing, format 2: padded, and signed over the transcript, the encrypted body and the sealed key, at the version the share is published as and with the time it was sealed | `share/sharecrypto.ts` | — | No: #174 |

The owner's key pair is derived from the master (subkeys 3 and 4), so the phone stores no separate
owner identity; that is what makes the phone and the browser the same owner. The vector in
`owner/identity.test.ts` (master bytes 0x00…0x1F to two fixed public keys) is the one the phone must
reproduce.

**What the tests pin** (run by `./gradlew test`, which includes `:sync-crypto`): `SyncCryptoTest`
checks round trips, AAD binding, tampering, the base64 conformance vector of SYNC_PROTOCOL.md §1.2,
and cross-language vectors generated from `crypto.ts`, including the format-2 vector, which the
Kotlin writer makes byte for byte under the vector's nonce, and a format-1 envelope that still
opens. `PaddingTest` checks the bucket rule, the boundaries, the strict refusals and the vectors of
`padding.test.ts`. `CpaceCryptoTest` checks the CFRG test vectors (generator string, generator
point, both messages, the key), a live exchange, and that a wrong code diverges silently.
`KeyDocumentVectorTest` holds the vector of `recovery/dataKeyVector.test.ts` (#403): the phone opens
the web-made key parameters and wrapped key, with the passphrase and with the recovery code as typed,
to the same master and four subkeys; its writer makes the same 463 bytes; and both sides refuse the
same twenty-six mutations before any Argon2id. `KeyDocumentTest` pins the other refusals, including where
the phone is stricter than the web, and that a slot of an unknown kind is skipped and still held to
the floor and the ceiling. `DeviceSignatureVectorTest` holds the server's vector (#432): the key from the seed 0x40..0x5f signs
both of the server's requests and the redemption of `K7M4RD96QA` to the server's bytes. `DeviceKeyTest`
pins a new nonce for every attempt, a retry included, and every request the signer refuses.
`PairingCodeTest` holds the check symbol to every single substitution and every swap of neighbours, on
the vector's code and on 300 drawn ones. `PairingVerdictTest` pins the https verdict, that typing gets
the same verdict as a scan, the encoding against Node's own, and that the verdict runs with no
libsodium on the class path. `DeviceWordsTest` holds the six-word vectors the web shares, and
`DeviceWordsDriftTest` fails when `share/wordlist.ts` and the phone's copy differ.
`RecoveryCodeTest` pins normalisation, faults and the check symbol, and `StrictJsonTest`
holds the JSON reader to `JSON.parse`. `LazySodiumParityTest` checks that the Java and Android
bindings expose the same surface, since the tests run on one and the app on the other. The same checks on a real device are not built: #192.

## 2. Sync of the owner's own data

Settings → Sync: the code the owner console shows, and the sync passphrase, and no bearer token. The
phone signs every request with a key of its own, made for this server, because the shared token stops
being anyone's sign-in (#208; not built: #324). The server's half is built (SYNC_PROTOCOL.md §2.1 and
§2.2); the phone's is not: #432.

What the phone must do, against the server as built:
- Refuse a code whose address is not `https://`, before sending anything: the phone is the one party
  the network cannot rewrite (#189).
- Make a new Ed25519 key pair for the server, in memory, and keep it only once the registration poll
  answers `registered`.
- Sign each request as SYNC_PROTOCOL.md §2.1, all five header lines included, with nothing after the
  colon for a header it does not send. Send each of those headers at most once, never empty, with no
  whitespace around the value. Always send `Content-Length`; never send a body chunked.
- Use a new nonce for every attempt, a retry included. Keep the clock within 300 seconds of the
  server's.
- Never retry a refused request in a loop: every refusal counts toward the lockout of the phone's
  whole network address, which on a home connection pauses the console too.
- Expect `403` on managing phones, making a practice, the notification settings and writing the key
  documents: those belong to the console. Push the existing backup snapshot (`BackupManager`) as the plaintext,
append-only, at `max(existing) + 1`; pull fetches the newest and decrypts it. The phone reads the
owner's key document of either kind (#403); fetching it is part of #168. Not built: #168.

Sync is single-writer, last-snapshot-wins, for good (#200): the phone is the only device that writes
the journal's encrypted copy, the schema has no per-row ids or timestamps, and rows are never
merged. What the web console creates arrives as new records in a separate, add-only encrypted lane,
which the phone takes in once each, by its id, and sync never replaces a journal without asking.
The records and their lanes are SYNC_PROTOCOL.md §1.4. Taking them in means: list `GET /v1/snapshots`;
open the newest version of every `lane_` lineage with the sync key and the lane's associated data; skip
unknown kinds; take in each record whose id it has not taken in before, exactly once, keyed by the id,
refusing anything that would change or remove a row; check each decision's signed item as the inbox
does (#177), trusting the record no further than "the owner's console added this"; keep the later of
two decisions about one item (by `createdAt`, then `id`) and both in its history; and list every id it
has taken in as `laneRecordsTakenIn` in the snapshot it uploads. The phone never writes a lane, nor a
snapshot under a `lane_` name. Not built: taking records in, #346; asking before replacing, #344. Refusing an older
snapshot presented as the newest needs a signed manifest and a watermark kept on the device: #179.

Neither the server nor anyone else can reset the app PIN or the sync passphrase. The owner's email
recovery re-issues the server's bearer token and nothing else, which disconnects every paired phone;
each pairs again with a new code.

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
- **The checks are built.** `companion/AssignmentRules.kt` ports `assignments/validate.ts`: the type
  must require the capability claimed, the owner must grant it now, the author must be the clinician
  the grant is for, and the payload must be in bounds. It imports nothing and runs with
  `tools/jvm-tests.sh companion`. `AssignmentRulesDriftTest` fails when `types.ts` or `validate.ts`
  changes what it copies. The phone carries no copy of the Companion's catalogue, so on the phone
  every self-check, task and bundle is refused as unknown: #389.
- **Schema v19.** `game_plans` and `game_plan_items` hold an accepted version exactly as signed,
  keyed by `(lineageId, version)`, with the payload and signature verbatim. `game_plan_progress` is
  the owner's mark per item, keyed by `(lineageId, itemRef)`. `assignments` holds accepted ones the
  same way. `instrument_results` and `task_results` keep scores and bands, never an answer. "Replace
  all current data" empties all six and restores nothing: the backup file carries none of them.
  Whether it should is #386.
- **Settings** apply only for the allowlisted keys (`visibleSelfChecks`, `reminderTime`,
  `reminderCadence`, `theme`), never PIN, lock, encryption or network settings.
- Not built: opening and verifying items, the inbox, and anything that writes these tables: #177.
  The owner's console on the web keeps an accept or a decline in its lane (SYNC_PROTOCOL.md §1.4);
  the phone taking it in is #346.

## 4. Pairing: the phone as the owner's device

The phone runs the owner's half of the same pairing protocol and becomes the device that approves a
clinician, because an installed app cannot have its code rewritten by the server. The bytes are in
COMPANION_PAIRING.md §13; the phone's obligations, including the rule that it raises **one local
notification per invitation** when a reply does not open (issue #112) and nothing else, are in
COMPANION_PAIRING.md §14.

It also gains a screen listing its connections: who, since when, their key fingerprints, what they
can see in plain words, and Revoke. Not built: #174. The phone as the anchor for the audit chain's
head: #182. A heartbeat between phone and server: #185. Signing requests and pairing by QR code: the
server's half and the phone's crypto are built (§1); the phone's pairing screen, registration poll and
signed push and pull are #432.

## 5. What CI checks

`.github/workflows/build.yml` runs on every push. It builds both flavours in debug and release (so R8's
shrinking, which JNA's reflection makes risky, is exercised for `sync`), runs the unit tests including
`:sync-crypto`, runs the `foss` permission check, and compiles the instrumented tests without running
them: no device runs in CI, so nothing has executed the ristretto code on a phone (#192, #147).

Only `foss` is released. The `sync` build ships first as a second file on each GitHub release,
signed with the same key, and later as its own F-Droid listing, once the offline app has one with a
reproducible build (#229); the offline app's listing never carries it (#194). Not built: #342,
#343. It needs its own privacy statement before it ships: #197.

## 6. The steps, in order

1. The `sync` flavour, the crypto port and its host-JVM conformance tests, and CI. **Built.**
2. Snapshot push and pull: #168.
3. The schema version with the game-plan, progress, assignment and result tables, and the assignment
   checks. **Built** (v19).
4. Inbound assignments and game plans, with the acceptance inbox: #177.
5. The owner's half of pairing, grants and shares from the phone, and the connections screen: #174.
6. The anti-rollback watermark (#179), the audit anchor (#182), the heartbeat (#185), and signed
   requests and QR pairing on the phone (#432; the server's half is built).
7. The crypto tests on a real device: #192.
