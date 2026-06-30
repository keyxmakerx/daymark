# Daymark Companion — Sync Protocol (v1)

> **Status: implemented (Milestone 2) for the server + web/CLI clients.** The phone
> "Daymark Sync" flavor (Milestone 2b) MUST implement this byte-for-byte to interoperate.
> This is the normative wire spec; the reference implementation is
> `companion/web/src/lib/sync/crypto.ts` + `client.ts`, exercised by the crypto and
> integration tests.

The server is **zero-knowledge**: it stores opaque ciphertext blobs + non-secret routing
metadata and never sees the passphrase, keys, or plaintext. All cryptography is
client-side. See [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §4 for the threat model.

## 1. Cryptography

| Step | Primitive | Parameters |
|---|---|---|
| KDF | **Argon2id** (`crypto_pwhash`, `ALG_ARGON2ID13`) | `memMiB ≥ 256`, `ops ≥ 3`, 16-byte random salt (non-secret), 32-byte master |
| Subkeys | **`crypto_kdf_derive_from_key`** | context `"dmsync01"` (8 bytes); id 1 → `SYNC_KEY` (32B), id 2 → `MANIFEST_SEED` (32B Ed25519 seed) |
| Content AEAD | **XChaCha20-Poly1305** (`crypto_aead_xchacha20poly1305_ietf`) | 24-byte random nonce per blob, 32-byte `SYNC_KEY` |
| Manifest signing | **Ed25519** (`crypto_sign_detached`) | keypair from `crypto_sign_seed_keypair(MANIFEST_SEED)` |

The passphrase **never leaves the device**. Only the salt + KDF params (non-secret) are
published, in `keyparams`.

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

Per [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §8, the **only real anti-rollback** is
this signed manifest checked against a **local trust watermark** the writer keeps and
refuses to regress past. Server-side version/hash checks are **DoS hygiene only**.

> **Milestone 2 scope (honest):** the browser and CLI readers perform **AEAD integrity
> only** — the XChaCha20-Poly1305 tag authenticates each blob's contents under the owner
> key, so a tampered or substituted blob fails to decrypt. They do **not** verify a signed
> manifest and hold **no watermark**, so a malicious server can still present an older
> version as "head" (rollback) undetected. Manifest signing/verification + the persistent
> watermark ship with the **phone client (2b)**; the manifest primitives here are the
> tested building blocks for it, and **no manifest is stored on the server in M2.**

## 2. HTTP API (`/v1`)

All routes require `Authorization: Bearer <DAYMARK_AUTH_TOKEN>`. Bodies/responses below.
Rate-limit + lockout identity is the **socket peer** (no forwarded headers trusted by
default; see [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §7).

> **M2 limitation:** because identity is the socket peer and there is not yet a
> trusted-proxy config, **behind a reverse proxy all clients share one rate-limit/lockout
> bucket** (the proxy is the only peer). Per-client limiting behind a proxy — via a pinned
> trusted-proxy CIDR that lets `X-Forwarded-For` be honored *only* from that proxy — is a
> follow-up. Direct/LAN deployments already get per-client limiting.

| Method · Path | Body | Success | Notes |
|---|---|---|---|
| `PUT /v1/keyparams` | JSON (§1.2), ≤ 4 KiB | `204` | Overwrite-allowed |
| `GET /v1/keyparams` | — | `200` JSON · `404` if unset | |
| `GET /v1/snapshots` | — | `200 {"lineages":[…]}` | |
| `GET /v1/snapshots/{lineage}` | — | `200 {"lineage","versions":[{version,size,contentHash,createdAt}]}` | |
| `PUT /v1/snapshots/{lineage}/{version}` | raw bytes (the envelope) | `201 {lineage,version,size,contentHash}` + `X-Content-Hash` | **Append-only**: `409` if the version exists |
| `GET /v1/snapshots/{lineage}/{version}` | — | `200` octet-stream + `X-Content-Hash` | |

`lineage` ⊂ `[A-Za-z0-9_-]{1,64}` (server-validated; the blob path is server-derived).
`version` is a non-negative integer (monotonic per device; pick `max(existing)+1`).

### Status codes

`401` bad/missing token · `429` rate-limited or locked out · `400` bad lineage/version ·
`409` append-only conflict · `413` over `MAX_BLOB_BYTES`/request cap · `507` quota or
disk-full · `503` sync API not configured (no token) · `404` not found.

### Caps & retention (server config, `DAYMARK_*`)

`MAX_BLOB_BYTES` (25 MiB), `MAX_VERSIONS` (200, keep-last-N prune, older blob bytes
hard-deleted), `PER_TOKEN_QUOTA_BYTES` (5 GiB, fail-closed), `RATE_LIMIT_RPS` (5),
`AUTH_LOCKOUT_FAILS` (8) / `AUTH_LOCKOUT_SECONDS` (900).

## 3. Client flows

**Push (writer — CLI today, phone tomorrow):** ensure keyparams (GET, or create+PUT a
fresh salt) → derive keys → `version = max(existing)+1` → encrypt → `PUT` envelope.

**Pull (reader — browser/CLI):** GET keyparams → derive keys → list versions → fetch the
head → decrypt (AEAD verifies integrity). Wrong passphrase ⇒ decrypt fails (no oracle).

## 4. Conformance for the phone (Milestone 2b)

Use a libsodium binding (e.g. lazysodium on JVM). Reproduce: Argon2id params, the
`crypto_kdf` context `"dmsync01"` + subkey ids, the exact envelope layout and AAD string,
and the keyparams JSON. The crypto + integration tests in `companion/web/src/lib/sync/`
are the conformance oracle — a phone-produced envelope must decrypt there and vice-versa.
