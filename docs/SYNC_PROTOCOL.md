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
a wrapped-key file (`companion/web/src/lib/recovery/dataKey.ts`); wrapping an existing owner's
passphrase-derived master (`companion/web/src/lib/recovery/migration.ts`) is what makes the code yield
these same subkeys. Enrolling an existing owner that way, and a server route to store the file, are
not built: #{W14}.

### 1.1 Snapshot envelope (the stored blob bytes)

```
┌────────┬─────┬───────────┬──────────────────────────────┐
│ MAGIC  │ FMT │  nonce    │  ciphertext (AEAD)           │
│ 4 B    │ 1 B │  24 B     │  variable (incl. 16 B tag)   │
└────────┴─────┴───────────┴──────────────────────────────┘
MAGIC = ASCII "DMS1" = 0x44 0x4D 0x53 0x31
FMT   = 0x01
ciphertext = XChaCha20Poly1305_encrypt(plaintext, AAD, nonce, SYNC_KEY)
AAD   = utf8("daymark.snapshot.v1|" + lineage + "|" + version)
plaintext = the Daymark BackupData JSON, UTF-8
```

The AAD binds the blob to its `lineage` and `version`, so a blob served under the wrong
path fails to decrypt. Both fields are recoverable from the request path, so a reader can
always reconstruct the AAD.

### 1.2 Key params (non-secret, published)

`PUT /v1/keyparams` stores this JSON verbatim; readers `GET` it to learn the salt:

```json
{ "v": 1, "alg": "xchacha20poly1305",
  "kdf": { "alg": "argon2id", "memMiB": 256, "ops": 3 },
  "saltB64": "<base64 of the 16-byte salt>" }
```

A reader: fetch keyparams → `Argon2id(passphrase, salt, params)` → subkeys → decrypt.
Readers **must reject** params below the floor (`memMiB ≥ 256`, `ops ≥ 3`, `alg = argon2id`)
to defend against a server downgrading them.

> **All base64 in this protocol is RFC 4648 §5 — URL-safe alphabet (`-`/`_`), NO padding**
> (libsodium `URLSAFE_NO_PADDING`). This applies to `saltB64`, `signatureB64`, and
> `publicKeyB64`. A client using standard base64 will be rejected. See the conformance
> vector in `companion/web/src/lib/sync/crypto.test.ts` (bytes `00..0F` → `AAECAwQFBgcICQoLDA0ODw`).

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
present an older version as the newest. Not built: #{F4}.

## 2. HTTP API (`/v1`)

Every route requires `Authorization: Bearer <token>`, where the token is the owner's. At first boot
it is `DAYMARK_AUTH_TOKEN`; after an access-recovery reissue (`POST /v1/recovery/confirm`) it is the
rotated token, and the old one stops working at once. If the operator later changes
`DAYMARK_AUTH_TOKEN`, the new environment value is accepted from the next start. The server stores
only a digest of the accepted token.

Rate limiting and lockout key on the client address: the socket peer, unless that peer is a proxy
named in `DAYMARK_TRUSTED_PROXIES`, in which case the nearest `X-Forwarded-For` entry that is not
itself a trusted proxy. With the setting empty (the default) behind a proxy, every client shares one
bucket; see [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md).

| Method · Path | Body | Success | Notes |
|---|---|---|---|
| `PUT /v1/keyparams` | JSON (§1.2), ≤ 4 KiB | `204` | Overwrite allowed |
| `GET /v1/keyparams` | — | `200` JSON · `404` if unset | |
| `GET /v1/snapshots` | — | `200 {"lineages":[…]}` | |
| `GET /v1/snapshots/{lineage}` | — | `200 {"lineage","versions":[{version,size,contentHash,createdAt}]}` | |
| `PUT /v1/snapshots/{lineage}/{version}` | raw bytes (the envelope) | `201 {lineage,version,size,contentHash}` + `X-Content-Hash` | **Append-only**: `409` if the version exists |
| `GET /v1/snapshots/{lineage}/{version}` | — | `200` octet-stream + `X-Content-Hash` | |

`lineage` ⊂ `[A-Za-z0-9_-]{1,64}` (server-validated; the blob path is server-derived).
`version` is a non-negative integer (monotonic per device; pick `max(existing)+1`).
`X-Content-Hash` is the server's own SHA-256 over the stored bytes; a client-supplied hash is never
trusted.

### Status codes

`401` bad or missing token · `429` rate-limited or locked out · `400` bad lineage or version ·
`409` the version exists, or is older than the retention window would keep · `413` over
`MAX_BLOB_BYTES` or the request cap · `507` quota or disk full · `503` sync API not configured (no
token) · `404` not found.

### Caps & retention (server config, `DAYMARK_*`)

`MAX_BLOB_BYTES` (25 MiB), `MAX_VERSIONS` (200, keep-last-N prune, older blob bytes
hard-deleted), `PER_TOKEN_QUOTA_BYTES` (5 GiB, fail-closed), `RATE_LIMIT_RPS` (5),
`AUTH_LOCKOUT_FAILS` (8) / `AUTH_LOCKOUT_SECONDS` (900).

## 3. Client flows

**Push (writer).** Ensure keyparams (GET, or create a fresh salt and PUT) → derive keys →
`version = max(existing)+1` → encrypt → `PUT` the envelope. Today's writer is the command-line tool
(`pnpm push` in `companion/web`); the phone's is not built: #{F1}.

**Pull (reader — the browser, or the CLI).** GET keyparams → derive keys → list versions → fetch the
head → decrypt (the AEAD verifies integrity). A wrong passphrase makes decryption fail, with no
oracle beyond that.

Sync is single-writer and last-snapshot-wins: the newest full snapshot is authoritative, and rows are
never merged, because the app's schema has no per-row ids or timestamps. Whether that stays so is a
decision: #{F12}.

## 4. Conformance

A second implementation reproduces the Argon2id parameters, the `crypto_kdf` context and subkey ids,
the exact envelope layout and AAD string, the keyparams JSON, and the base64 variant. The crypto and
integration tests in `companion/web/src/lib/sync/` are the oracle: an envelope made elsewhere must
decrypt there, and the other way round. The Kotlin port is held to it by `SyncCryptoTest`, which
includes cross-language vectors generated from `crypto.ts`; see [COMPANION_PHONE.md](COMPANION_PHONE.md) §1.
