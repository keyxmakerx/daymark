# Daymark Companion — sync protocol (v1)

The wire format of the Companion's end-to-end-encrypted snapshot sync. The reference implementation
is `companion/web/src/lib/sync/crypto.ts` and `client.ts`; the Kotlin port is
`sync-crypto/src/main/kotlin/com/daymark/synccrypto/SyncCrypto.kt`. Any other implementation must
produce byte-identical envelopes and accept theirs.

The server stores opaque ciphertext and non-secret routing metadata. It never sees the passphrase,
a key, or plaintext; all cryptography runs on the client. The threat model is
[COMPANION_SECURITY.md](COMPANION_SECURITY.md) §4.

## 1. Cryptography

| Step | Primitive | Parameters |
|---|---|---|
| KDF | **Argon2id** (`crypto_pwhash`, `ALG_ARGON2ID13`) | `256 ≤ memMiB ≤ 512`, `3 ≤ ops ≤ 8` (writers use 256 and 3), 16-byte random salt (non-secret), 32-byte master |
| Subkeys | **`crypto_kdf_derive_from_key`** | context `"dmsync01"` (8 bytes), 32 bytes each: id 1 → `SYNC_KEY`, id 2 → `MANIFEST_SEED` (Ed25519 seed), id 3 → the owner's X25519 seed, id 4 → the owner's Ed25519 seed |
| Content AEAD | **XChaCha20-Poly1305** (`crypto_aead_xchacha20poly1305_ietf`) | 24-byte random nonce per blob, 32-byte `SYNC_KEY` |
| Manifest signing | **Ed25519** (`crypto_sign_detached`) | keypair from `crypto_sign_seed_keypair(MANIFEST_SEED)` |

The passphrase **never leaves the device**. Only the salt and KDF parameters (non-secret) are
published, in `keyparams`.

Ids 3 and 4 are the owner's pairing identity (`crypto_box_seed_keypair` and
`crypto_sign_seed_keypair` over those seeds; reference `companion/web/src/lib/owner/identity.ts`).
They are reserved on every platform; the next free id is 5. A recovery code opens a master through
the wrapped key (§1.2; `companion/web/src/lib/recovery/dataKey.ts`); wrapping an existing owner's
passphrase-derived master (`companion/web/src/lib/recovery/migration.ts`) is what makes the code yield
these same subkeys. The server stores the wrapped key (§2), and every client reads it there (§3).

### 1.1 Snapshot envelope (the stored blob bytes)

```
┌────────┬─────┬───────────┬──────────────────────────────┐
│ MAGIC  │ FMT │  nonce    │  ciphertext (AEAD)           │
│ 4 B    │ 1 B │  24 B     │  variable (incl. 16 B tag)   │
└────────┴─────┴───────────┴──────────────────────────────┘
MAGIC = ASCII "DMS1" = 0x44 0x4D 0x53 0x31
FMT   = 0x02 (the only format written)
ciphertext = XChaCha20Poly1305_encrypt(pad(plaintext), AAD, nonce, SYNC_KEY)
AAD   = utf8("daymark.snapshot.v2|" + lineage + "|" + version)
pad(p) = u32 big-endian length(p) || p || zero bytes, paddedLength(4 + length(p)) in all
plaintext = the Daymark BackupData JSON, UTF-8

FMT 0x01, read and never written: no padding, AAD = utf8("daymark.snapshot.v1|" + lineage + "|" + version)
```

The AAD binds the blob to its `lineage` and `version`, so a blob served under the wrong path fails
to decrypt. Both fields are recoverable from the request path, so a reader can always reconstruct
the AAD. The AAD also names the format, so a server that changes the format byte makes the envelope
fail to open instead of opening in the wrong form. Unpadding happens only after the AEAD has
authenticated the body, and it is strict.

Padding (decided in #214; `companion/web/src/lib/padding.ts`; Kotlin port
`sync-crypto/src/main/kotlin/com/daymark/synccrypto/Padding.kt`): `paddedLength(x)` is 4 KiB up to 4
KiB, then the next power of two up to 1 MiB, then the Padmé length, which is never more than about
12% larger. A snapshot of n bytes is stored as 45 + `paddedLength(4 + n)` bytes, so anything up to
4,092 bytes is stored as 4,141. Padding hides how much was written, never when: the server still
sees when each version arrives.

### 1.2 Key params (non-secret, published)

`PUT /v1/keyparams` stores this JSON verbatim; readers `GET` it to learn the salt:

```json
{ "v": 1, "alg": "xchacha20poly1305",
  "kdf": { "alg": "argon2id", "memMiB": 256, "ops": 3 },
  "saltB64": "<base64 of the 16-byte salt>" }
```

The key params are written once: a second `PUT` answers `409`, because a new salt would strand
everything already written under the old one.

A reader: fetch keyparams → `Argon2id(passphrase, salt, params)` → subkeys → decrypt.
Readers **must reject**, before deriving anything, params below the floor (`memMiB ≥ 256`,
`ops ≥ 3`, `alg = argon2id`), to defend against a server downgrading them, and params above the
ceiling (`memMiB ≤ 512`, `ops ≤ 8`), so that no document sets how much memory and time a reader spends
before anything can refuse it. The floor is checked first. Writers use the floor. The range is
`kdfRange` in `companion/web/src/lib/sync/crypto.ts`, and `SyncCrypto.KdfParams` on the phone.

> **All base64 in this protocol is RFC 4648 §5 — URL-safe alphabet (`-`/`_`), NO padding**
> (libsodium `URLSAFE_NO_PADDING`). This applies to `saltB64`, `signatureB64`, and
> `publicKeyB64`. A client using standard base64 will be rejected. See the conformance
> vector in `companion/web/src/lib/sync/crypto.test.ts` (bytes `00..0F` → `AAECAwQFBgcICQoLDA0ODw`).

**The wrapped key (#258).** The master, locked once per secret: XChaCha20-Poly1305 under a key that
Argon2id derives from the passphrase, and again under one derived from the recovery code. Each slot
has its own salt, the same KDF range, and `AAD = utf8("daymark.datakey.v1|" + kind)`. The reference
is `RecoverableDataKey` in `companion/web/src/lib/recovery/dataKey.ts`:

```json
{ "v": 1, "slots": [
  { "kind": "passphrase", "kdf": { "alg": "argon2id", "memMiB": 256, "ops": 3 },
    "saltB64": "<16 bytes>", "nonceB64": "<24 bytes>", "ctB64": "<the 32-byte master and its 16-byte tag>" },
  { "kind": "recovery", "kdf": { … }, "saltB64": "…", "nonceB64": "…", "ctB64": "…" } ] }
```

The server stores this document byte for byte. It cannot open it and vouches for nothing in it. It
checks only that the body is one JSON object in UTF-8, at most 16 KiB and at most 32 levels deep. A
two-slot document is about 460 bytes. Readers check the KDF floor and ceiling on every slot, whatever its
kind, as they do for the key params. A slot of a kind a reader does not open is otherwise not read, by
the consoles, the sync card, `pnpm push` and the phone alike (#403, #419).

The first version is created against the state its writer read (§2), so two devices never both mint
a master. A first run mints a random master only while no key document of either kind exists. An
enrolment wraps the master of the key params only while they are the bytes it read, named by their
`ETag`. Each later change, a new passphrase or a new recovery code, is the next version. Versions are
numbered from 1 and never changed or deleted, and the server serves only the newest. Older versions
stay on the server's volume and in its backups. They are never served, so a changed secret opens
nothing the server hands out, but it still opens the older versions in any copy of the volume.

**The wrapped key supersedes the key params by presence.** From the moment any version exists, the
server stops serving the key params and takes no new ones (`410`, §2), and it keeps the file.
Otherwise a passphrase change would retire nothing, because the old passphrase and the
still-published salt would reproduce the master. The server decides this from what it holds. No
client deletes anything.

### 1.3 Signed manifest (client-anchored integrity)

A manifest lists `{version, sha256(envelope)}` for a lineage and is Ed25519-signed with
`MANIFEST_SEED`. Canonical signing bytes:

```
utf8(JSON.stringify({ lineage, head, entries: [{version, hash}, …] }))   // stable key order
```

The only real anti-rollback is this signed manifest checked against a **local trust watermark**
the writer keeps and refuses to regress past ([COMPANION_SECURITY.md](COMPANION_SECURITY.md) §8).
Server-side version and hash checks are denial-of-service hygiene only.

The signing and verifying primitives exist on both platforms and are tested. Nothing uses them yet:
readers check each blob with the AEAD tag only, the server stores no manifest, and no client keeps a
watermark. So a tampered or substituted blob fails to decrypt, but a malicious server can still
present an older version as the newest. Not built: #179.

### 1.4 The web console's lane

What the owner's web console adds to the person's record travels as records in lanes: lineages of the
owner's sync API (§2), beside the snapshots. The console never uploads a journal copy; the phone is
the journal's only writer (#200) and takes each record in once, by its id (not built: #346).
Reference: `companion/web/src/lib/lane/`.

*Names.* A lane is a lineage whose name begins `lane_`; a console names its lane `lane_` and the
URL-safe unpadded base64 of 16 random bytes (27 characters in all). Every such lineage is read as a
lane and never as a snapshot, and no snapshot is written under such a name (`pnpm push` refuses one,
and so must the phone's writer). Each browser writes one lane of its own and keeps its name in its own
storage (`daymark.lane.v1`); without storage it makes a new one, and the old one is still read.
Readers list `GET /v1/snapshots` and keep the names with the prefix.

*Envelope.* A format-2 snapshot envelope (§1.1) in every byte, under the same `SYNC_KEY`, with
`AAD = utf8("daymark.lane.v1|" + lineage + "|" + version)`. A lane version never opens as a snapshot,
nor a snapshot as a lane version. Lanes exist only padded.

*Plaintext.* UTF-8 JSON with no spaces: `{"v":1,"records":[{"id","kind","createdAt","payload"},…]}`.
`id` is the canonical unpadded URL-safe base64 of 16 random bytes (22 characters, the last one of A,
Q, g or w); `createdAt` is epoch milliseconds, a whole number of at least 0. A payload has exactly
these members:
- `assignmentDecision` and `gamePlanDecision`: `decision` (`accepted` or `declined`), `payloadJson`
  and `sigB64`, the clinician's signed item verbatim, exactly as the signature covers it;
- `instrumentResult`: `instrumentId`, `instrumentVersion`, `takenAt`, and `scales`, a list of
  `{scaleId, score, bandLabel}`;
- `taskResult`: `taskId`, `taskVersion`, `takenAt`, `timing` (`{flag, frameJitterMs, droppedFrames,
  refreshMs}`) and `metrics` (`{name: number}`).

A result carries scores and bands only: no member can hold an answer. A reader refuses a whole version
for another `v`, a malformed record or a repeated id, and skips other kinds and unknown members.

*Add only.* No record changes or removes anything. Of two decisions about one signed item, the later
`createdAt` stands, then the higher `id`.

*Carry-forward.* The server keeps only the newest `MAX_VERSIONS`. A record is added by writing the
browser's lane at head + 1 (0 for a new lane) holding every record of the head the phone has not taken
in, then the new one. Only heads are read, so pruning loses nothing.

*Taken in.* The phone lists every lane record id it has taken in as `laneRecordsTakenIn`, an array of
strings at the top of its snapshot's backup JSON; absent means none (not built: #346).

*The writer.* It holds the `SYNC_KEY` of the master its unlock opened (§1.2), and the `ETag` of that
key document. Right before every upload it reads `GET /v1/keydoc` again and sends nothing unless the
`ETag` is unchanged. A `409` reads again and writes after it. With no answer, or a `500`, `502` or
`504`, the next read says whether it landed; a record already there, by id, is not written again.

*Trust.* A version that opens says only that one of the owner's consoles wrote it, and the console is
served by the server ([COMPANION_SECURITY.md](COMPANION_SECURITY.md) §3 T3). The phone checks every
decision's signed item itself, against the pinned clinician key, its context, its recipient and the
grant, before taking it in.

## 2. HTTP API (`/v1`)

Every route requires `Authorization: Bearer <token>`, where the token is the owner's. At first boot
it is `DAYMARK_AUTH_TOKEN`; after an access-recovery reissue (`POST /v1/recovery/confirm`) it is the
rotated token, and the old one stops working at once. If the operator later changes
`DAYMARK_AUTH_TOKEN`, the new environment value is accepted from the next start. The server stores
only a digest of the accepted token.

A server serves one owner: the token belongs to that owner, and whoever holds it reaches every
lineage, the one `keyparams` and the one wrapped key on the server. Each stored journal belongs to exactly one owner,
with its own key parameters, by decision (#219). Not built: #318.

Rate limiting and lockout key on the client address: the socket peer, unless that peer is a proxy
named in `DAYMARK_TRUSTED_PROXIES`, in which case the nearest `X-Forwarded-For` entry that is not
itself a trusted proxy. With the setting empty (the default) behind a proxy, every client shares one
bucket; see [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md).

| Method · Path | Body | Success | Notes |
|---|---|---|---|
| `PUT /v1/keyparams` | JSON (§1.2), ≤ 4 KiB | `204` | **Create-only**: `409` if key params exist; `410` once a wrapped key exists |
| `GET /v1/keyparams` | — | `200` JSON + `ETag` · `404` if unset | `410` once a wrapped key exists |
| `GET /v1/keydoc` | — | `200` JSON + `ETag`, `Cache-Control: no-store` · `404` if neither document exists | The newest wrapped key (`X-Key-Document: wrapped`, `X-Key-Document-Version: n`), otherwise the key params (`X-Key-Document: keyparams`, no version) |
| `POST /v1/keydoc` | the wrapped key (§1.2), ≤ 16 KiB, and `If-None-Match: *` or `If-Match: <ETag>` | `201 {"version":1}` | **Create-only, against the state read.** `If-None-Match: *` (a first run) is taken only while no key document of either kind exists. `If-Match` with the key params' `ETag` (an enrolment) is taken only while they are those bytes and no wrapped key exists. `412` otherwise; `428` without exactly one of the two |
| `PUT /v1/keydoc/{version}` | the wrapped key, ≤ 16 KiB | `201 {"version":n}` | A new version, `version ≥ 2`, taken only when `version − 1` is the newest, otherwise `409`. **Insert-only** |
| `GET /v1/snapshots` | — | `200 {"lineages":[…]}` | |
| `GET /v1/snapshots/{lineage}` | — | `200 {"lineage","versions":[{version,size,contentHash,createdAt}]}` | |
| `PUT /v1/snapshots/{lineage}/{version}` | raw bytes (the envelope) | `201 {lineage,version,size,contentHash}` + `X-Content-Hash` | **Append-only**: `409` if the version exists |
| `GET /v1/snapshots/{lineage}/{version}` | — | `200` octet-stream + `X-Content-Hash` | |

`lineage` ⊂ `[A-Za-z0-9_-]{1,64}` (server-validated; the blob path is server-derived). A lineage
beginning `lane_` is a web console's lane (§1.4).
`version` is a non-negative integer (monotonic per device; pick `max(existing)+1`).
`X-Content-Hash` is the server's own SHA-256 over the stored bytes; a client-supplied hash is never
trusted. `ETag` is the SHA-256 of a key document's bytes, as 64 hex digits in quotes; a create sends
it back exactly as received.

### Status codes

`401` bad or missing token · `429` rate-limited or locked out · `400` bad lineage or version, a
version below 2 on `PUT /v1/keydoc/{version}`, or a wrapped key that is not one JSON object or nests
deeper than 32 · `409` the version exists, or is older than the retention window would keep; not the
next version (`PUT /v1/keydoc/{version}`); the key params already exist (`PUT /v1/keyparams`) ·
`410` the key params, once a wrapped key exists · `412` the key documents are not in the state a
create named (`POST /v1/keydoc`): read them again · `413` over `MAX_BLOB_BYTES`, the request cap,
4 KiB of key params or 16 KiB of wrapped key · `428` a create that names no state it read, or names
it in any other form · `507` quota or disk full, or the wrapped key already has 1,000 versions ·
`503` sync API not configured (no token) · `404` not found.

### Caps & retention (server config, `DAYMARK_*`)

`MAX_BLOB_BYTES` (25 MiB), `MAX_VERSIONS` (200, keep-last-N prune, older blob bytes
hard-deleted), `PER_TOKEN_QUOTA_BYTES` (5 GiB, fail-closed), `RATE_LIMIT_RPS` (5),
`AUTH_LOCKOUT_FAILS` (8) / `AUTH_LOCKOUT_SECONDS` (900). Fixed, not configurable: key params
≤ 4 KiB; a wrapped key ≤ 16 KiB and 32 levels deep, and at most 1,000 versions of it.

## 3. Client flows

**Push (writer).** Read the key document (`GET /v1/keydoc`). A wrapped key: open its passphrase slot
→ master → subkeys. Key params: derive as before. Nothing (`404`): list the stored lineages first,
and if the server stores any snapshot, refuse in fixed words having sent nothing, because a fresh salt
would open none of them; otherwise make a fresh salt and `PUT /v1/keyparams`, which is create-only,
so a writer that gets `409` (another writer's key params) or `410` (a wrapped key got there first)
reads the key document again and uses what is there. Then `version = max(existing)+1` → pad and
encrypt → read the key document again, and go on only if what it holds now opens, with the same
passphrase, to the key the snapshot was encrypted under (an enrolment or a first run can land between
the two reads, after which the snapshot would open with nothing the server serves); otherwise refuse
in fixed words and send nothing → `PUT` the envelope. Before it derives a key or sends anything,
the writer refuses a snapshot whose padded envelope is larger than the server accepts, and says so;
it never falls back to an unpadded write. The limit it assumes is the server's default, 26,214,400
bytes; for a server whose operator raised `DAYMARK_MAX_BLOB_BYTES`, pass the same number with
`--max-blob-bytes`. A 413 from the server is reported as the server's answer. Today's writer is the
command-line tool (`pnpm push` in `companion/web`), which reads the passphrase and the access token
from the environment. The phone's is not built: #168.

**Pull (reader — the browser).** Read the key document → open its passphrase slot, or derive from
the key params → list versions → fetch the head → decrypt (the AEAD verifies integrity). A wrong
passphrase makes the slot or the decryption fail, with no oracle beyond that. A server with no key
document has had nothing synced to it, and the reader says so.

Neither flow reads `/v1/keyparams`; the writer's create-only `PUT` is the one request left there
(`companion/web/src/lib/sync/client.ts`). The phone's crypto opens either key document (#403); the
phone fetching it is #168.

**The owner's key (the consoles).** The owner console and the Recovery code screen read the key
document with the owner's access token (`companion/web/src/lib/recovery/serverKey.ts`). Nothing: a
first run — a random master is locked under the passphrase (typed twice) and a new recovery code,
both locks are opened locally to that master, and the document is created with `If-None-Match: *`;
refused where the server stores snapshots. Key params only: an enrolment — subkey 1 of the
passphrase's master must open the newest stored snapshot (by `createdAt`, across lineages, lanes
aside), or with
none stored the passphrase is typed twice; both locks must open locally to the directly derived
master, and the create names the key params' `ETag` in `If-Match`, exactly as received. A wrapped key:
an unlock, with either secret. After a create the document is read back and must open with the
passphrase to the same master, not merely open, since a lock made under the same passphrase over
another master opens too; only then is the pairing identity derived. `412` reads again and acts on
what is there; `428` cannot arise, because the client always names one state. A create with no
answer the client can trust (the request failed, or a proxy answered `502` or `504` after passing it
on) is followed by a read, and a document that opens with the passphrase to the master that was sent
means it landed. A create the server took (`201`) whose read-back cannot be read, after asking again
(a `429`, a `5xx` or no answer, paced, at most five times), still shows the recovery code, and a later
read is compared byte for byte with the document sent. The recovery code is shown once, only when the
server may hold its lock, and checked as written down. A new passphrase set with the recovery code is
`PUT /v1/keydoc/{n+1}`, read back and opened to the same master; `409` reads again; a version whose
answer was lost or whose read-back cannot be read is compared by a later read with the version sent.
Replacing a recovery code is not built: #407.

Sync is single-writer and last-snapshot-wins: the newest full snapshot is authoritative, and rows are
never merged, because the app's schema has no per-row ids or timestamps. That is settled (#200): the
phone is the journal's one writer (until it syncs, the command-line tool uploads its exported
backup), and other devices read it. What the web console creates travels as new records, each with a
random id, in the web console's lanes (§1.4), and the phone takes each record in exactly once. The
owner console keeps its accept or decline of an assignment there. No device silently replaces a copy
it has not seen. Not built: the phone taking records in (#346), and the refusal to replace a copy it
has not seen (#344).

## 4. Conformance

A second implementation reproduces the Argon2id parameters, the `crypto_kdf` context and subkey ids,
the exact envelope layout and AAD string, the padding, the keyparams JSON, and the base64 variant.
The crypto and integration tests in `companion/web/src/lib/sync/` are the oracle: an envelope made
elsewhere must decrypt there, and the other way round. The Kotlin port is held to it by
`SyncCryptoTest`, which includes cross-language vectors generated from `crypto.ts`; see
[COMPANION_PHONE.md](COMPANION_PHONE.md) §1. `SyncCryptoTest` also holds the format-2 vector in
`sync/crypto.test.ts` (key from the passphrase `conformance-vector`, salt 0x00..0x0f, 8 MiB and 2
passes; nonce 0x01..0x18; lineage `devA`, version 7; plaintext `{"hello":"daymark"}`; 4,141 bytes):
the Kotlin writer makes exactly those bytes under that nonce, and the Kotlin reader opens them, and
the format-1 envelope of the same inputs. `PaddingTest` holds the padding vector and length table of
`padding.test.ts`. The key documents are held the same way (#403).
`companion/web/src/lib/recovery/dataKeyVector.test.ts` makes a wrapped key with `wrapDataKey`, with
only its random draws fixed: the passphrase `wrapped-key vector: café, 日記, 🌿`, salts 0x20..0x2f and
0x50..0x5f, nonces 0x30..0x47 and 0x60..0x77, and the recovery code
`K7M2Q-XR9CT-4HWAZ-P3NE8-GUV6D-YJF59`. It wraps the master the key params give (salt 0x10..0x1f,
256 MiB, 3 passes), and the result is 463 bytes. `KeyDocumentVectorTest` opens both documents to that
master and its four subkeys, the Kotlin writer makes the same 463 bytes, and both sides refuse the
same twenty-six mutations before deriving anything (seventeen of the wrapped key, seven of a slot of a
kind neither side opens, two of the key params); at 512 MiB and 8 passes each reaches Argon2id.
The lane has its own vector, `companion/web/src/lib/lane/laneVector.test.ts`: the snapshot vector's
key, the lane `lane_AAECAwQFBgcICQoLDA0ODw` at version 3, nonce 0x01..0x18, and two records stated in
full (a 468-byte plaintext); sealed, it is 4,141 bytes, and it is refused as a snapshot. No Kotlin
reader holds it yet (#346).
