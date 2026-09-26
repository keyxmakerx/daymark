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
| KDF | **Argon2id** (`crypto_pwhash`, `ALG_ARGON2ID13`) | `memMiB ≥ 256`, `ops ≥ 3`, 16-byte random salt (non-secret), 32-byte master |
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
these same subkeys. The server stores the wrapped key (§2). Enrolling an existing owner that way, and
the consoles reading and writing the wrapped key, are not built: #258.

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
Readers **must reject** params below the floor (`memMiB ≥ 256`, `ops ≥ 3`, `alg = argon2id`)
to defend against a server downgrading them.

> **All base64 in this protocol is RFC 4648 §5 — URL-safe alphabet (`-`/`_`), NO padding**
> (libsodium `URLSAFE_NO_PADDING`). This applies to `saltB64`, `signatureB64`, and
> `publicKeyB64`. A client using standard base64 will be rejected. See the conformance
> vector in `companion/web/src/lib/sync/crypto.test.ts` (bytes `00..0F` → `AAECAwQFBgcICQoLDA0ODw`).

**The wrapped key (#258).** The master, locked once per secret: XChaCha20-Poly1305 under a key that
Argon2id derives from the passphrase, and again under one derived from the recovery code. Each slot
has its own salt, the same KDF floor, and `AAD = utf8("daymark.datakey.v1|" + kind)`. The reference
is `RecoverableDataKey` in `companion/web/src/lib/recovery/dataKey.ts`:

```json
{ "v": 1, "slots": [
  { "kind": "passphrase", "kdf": { "alg": "argon2id", "memMiB": 256, "ops": 3 },
    "saltB64": "<16 bytes>", "nonceB64": "<24 bytes>", "ctB64": "<the 32-byte master and its 16-byte tag>" },
  { "kind": "recovery", "kdf": { … }, "saltB64": "…", "nonceB64": "…", "ctB64": "…" } ] }
```

The server stores this document byte for byte. It cannot open it and vouches for nothing in it. It
checks only that the body is one JSON object in UTF-8, at most 16 KiB and at most 32 levels deep. A
two-slot document is about 460 bytes. Readers check the KDF floor on every slot, as they do for the
key params.

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

`lineage` ⊂ `[A-Za-z0-9_-]{1,64}` (server-validated; the blob path is server-derived).
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

**Push (writer).** Ensure keyparams (GET, or create a fresh salt and PUT; the PUT is create-only, so
of two writers publishing at once the second gets `409`) → derive keys → `version =
max(existing)+1` → pad and encrypt → `PUT` the envelope. Before it derives a key or sends anything,
the writer refuses a snapshot whose padded envelope is larger than the server accepts, and says so;
it never falls back to an unpadded write. The limit it assumes is the server's default, 26,214,400
bytes; for a server whose operator raised `DAYMARK_MAX_BLOB_BYTES`, pass the same number with
`--max-blob-bytes`. A 413 from the server is reported as the server's answer. Today's writer is the
command-line tool (`pnpm push` in `companion/web`), which reads the passphrase and the access token
from the environment. The phone's is not built: #168.

**Pull (reader — the browser).** GET keyparams → derive keys → list versions → fetch the head →
decrypt (the AEAD verifies integrity). A wrong passphrase makes decryption fail, with no oracle
beyond that.

Both flows read `/v1/keyparams`, so once a wrapped key exists they stop at its `410`. A reader that
opens the wrapped key instead is not built: #258.

Sync is single-writer and last-snapshot-wins: the newest full snapshot is authoritative, and rows are
never merged, because the app's schema has no per-row ids or timestamps. That is settled (#200): the
phone is the journal's one writer (until it syncs, the command-line tool uploads its exported
backup), and other devices read it. What the web console creates travels as new records, each with a
random id, in a separate, add-only encrypted lane, and the phone takes each record in exactly once.
No device silently replaces a copy it has not seen. Not built: the lane and its format (#345), the
phone taking records in (#346), and the refusal to replace a copy it has not seen (#344).

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
`padding.test.ts`.
