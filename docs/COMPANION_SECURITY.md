# Daymark Companion — security model

The Companion is the optional, self-hosted server (`companion/server`) and its browser consoles
(`companion/web`): the owner console, the clinician portal, the practice console and the admin page.
This document is its security reference, as built. Where it and the code disagree, the code wins and
this document has a bug.

The flagship phone app (the `foss` build) has no network access at all; see
[SECURITY.md](../SECURITY.md) and [PRIVACY.md](../PRIVACY.md). The phone's `sync` build does not
talk to the Companion yet (Not built: #138), so today the owner uses the browser console.

Related: pairing is specified in [COMPANION_PAIRING.md](COMPANION_PAIRING.md); running a server in
[COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) and
[COMPANION_OBSERVABILITY.md](COMPANION_OBSERVABILITY.md); practices and roles in
[COMPANION_ACCESS_CONTROL.md](COMPANION_ACCESS_CONTROL.md). Appendix R lists twelve claims earlier
drafts made that were false (R1–R12); code cites them by number.

---

## 1. Security objectives (what must hold)

| # | Objective | Why |
|---|---|---|
| O1 | A full compromise of the server and its disk yields ciphertext, routing metadata and some sign-in secrets (§5.2) — never anything that decrypts a record. | The zero-knowledge promise, honestly bounded. |
| O2 | The owner's passphrase is never shared with the clinician or the server. Giving a clinician access never widens who can read the whole archive. | Least authority. |
| O3 | A clinician reads only the subset sealed to them, only while signed in, only until it expires; the owner can stop future fetches from an honest server at once. Not retroactive (R3). | Owner control, honestly scoped. |
| O4 | Shares are owner-signed and clinician-verified; game plans and assignments are clinician-signed and owner-verified. A hostile server can forge neither. | Integrity both ways (R4). |
| O5 | The flagship build makes no network calls. The Companion container makes none except the operator's own SMTP, which is off by default (§6). | No telemetry. |
| O6 | No key escrow anywhere. A lost passphrase is unrecoverable by design. | Nothing on the server can open a record. |
| O7 | The server is a replica. It can withhold data; it can never read or author it. | The owner's device holds the journal. |

## 2. Parties, assets and trust boundaries

| Party | Holds | Never holds |
|---|---|---|
| Owner, in the browser console (the phone later: #138) | The passphrase, the master key and the owner's X25519 and Ed25519 private keys (derived, §4), and plaintext — in memory while unlocked | The clinician's private keys |
| Clinician, in the browser portal | Their X25519 and Ed25519 private keys, wrapped under a reading passphrase in their own browser; share plaintext in memory only | The owner's passphrase or keys; any other patient's data |
| Server | Ciphertext, sealed content keys, signatures, public keys, token digests, sign-in code seeds (§5.2), routing metadata, audit chains | Any private key, unwrapped content key, passphrase or plaintext |
| Practice administrator | Membership and roles. Every administrator, of a practice or of the server, signs in with an account of their own that holds no key (#208; not built: #314, #322) | Any key or content ([COMPANION_ACCESS_CONTROL.md](COMPANION_ACCESS_CONTROL.md)) |
| Operator | The container, its volume, its logs | Nothing beyond what the server holds |

**Assets, most sensitive first:** the owner's passphrase and private keys; the owner's plaintext; the
clinician's private keys; per-share content keys (CEKs); plaintext on either endpoint; routing
metadata (it can re-identify a caseload and signal acuity); sign-in secrets.

**Trust boundaries:** browser to server (TLS ends at the operator's proxy; payloads are already
end-to-end encrypted); the server's disk; the server to the outside (nothing, except SMTP); and the
owner-to-clinician key pairing, which is the only trust anchor. The server never vouches for a key.

## 3. Multi-party threat model

Each adversary: what it can do, what it cannot while the defences hold, and the defences as built.

### T1 — Stolen server or stolen disk

- **Can:** read the whole volume: every blob, every lineage id and version, sizes rounded to a
  bucket, exact timestamps (so journalling cadence, gaps and bursts), the relationship graph, which
  channel each write went to, cleartext `X-Setting-Key` tags, 90 days of audit entries, the
  notification email, and the sign-in code seeds — enough to sign in as any enrolled clinician
  (§5.2).
- **Cannot:** read a record, share or game plan; derive a key; recover a passphrase (Argon2id,
  client-side, §4).
- **Defences:** every stored blob is XChaCha20-Poly1305 ciphertext or a sealed box, and the indexes
  have no plaintext columns. Relationships are routed by an opaque per-relationship inbox token
  whose BLAKE2b digest is the `relRef`; no fingerprint appears in any URL. The owner bearer token,
  session ids and inbox tokens are stored as digests and invitation secrets as Argon2id hashes. The
  audit log's source address is off by default. Every encrypted item (snapshots, shares, game plans
  and assignments) is padded on the device before it is encrypted (`lib/padding.ts`, decided in
  #214), so the server stores a size rounded to a bucket, never an exact one.
- **Not built:** decided in #228, ending every shared item within 90 days, with a newer share ending
  the one before it (#332), and deleting the bytes of whatever has ended (#338). Withdrawing a share
  deletes its bytes today; expiry only blocks reads.

Mood-tracking cadence is mental-health data. Timing is the leak that remains, and size to the
nearest bucket.

### T2 — Network attacker (LAN, Wi-Fi, on-path)

Payloads are encrypted before TLS, so a broken TLS layer leaks only ciphertext; TLS and HSTS are the
operator's proxy's job ([COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) §3). AEAD tags and
signatures stop forgery. Rollback protection is not built (§8).

### T3 — Malicious or compromised server (the hardest party)

- **Can:** withhold, replay or serve stale blobs; serve tampered console JavaScript; substitute a key
  in anything it relays; ignore its own expiry and withdrawal flags; observe metadata.
- **Cannot, while the defences hold:** read plaintext; forge a share (the owner signs it, R4) or a game
  plan (the clinician signs it); pass off a substituted key once pairing has bound the real one
  (§5.6).
- **Defences:** the owner Ed25519-signs every share over the transcript
  `context|shareId|version|recipientFp|createdAt|expiry|ownerSigningFp` together with the encrypted
  body and the sealed content key; the transcript is also the AEAD's associated data. The whole
  envelope is signed because a sealed box is anonymous: anyone holding the clinician's public key
  can seal a content key to it, so a signature over the transcript alone would vouch for whatever
  body and key sat beside it. As signed, no part of a share can be replaced, a sealed content key
  cannot be spliced onto another ciphertext, and a bundle cannot be re-pointed at another owner. The
  clinician verifies against the owner key pinned at pairing before opening anything, refuses a
  share whose signed version is not the version it was served as, and refuses format 1, which signed
  the transcript alone (`lib/share/sharecrypto.ts`). It also refuses a copy sealed before the newest
  share that browser has opened (`lib/therapist/shareSeen.ts`), which catches an older share served
  as the current one, though not from a server that also changes the page (R5). Game plans and
  assignments name their recipient and context inside the signed payload, and the owner refuses an
  assignment the server files under a lineage or version other than the ones signed inside it. A
  grant names the clinician it is for, and the portal refuses one written for another key
  (`lib/therapist/grant.ts`). Pinned keys are insert-only on both sides (§4).
- **The hole a browser cannot close (R5).** The server serves the page that holds the keys, so CSP and
  SRI protect against third parties and never against the origin itself. Every console that handles
  keys therefore shows a fixed lower-assurance banner; its wording is asserted character for
  character by `components/invariants.tree.test.ts`. The answers are not built: the phone as the
  owner's secret-handling path (#138), a clinician client whose code the server cannot change
  (#319, decided in #222), and a published hash of each release's web bundle (#241).
- The owner-side check of a game plan exists (`openGamePlan` in `lib/therapist/gamePlan.ts`), but no
  screen calls it yet (#231).

### T4 — Malicious clinician, or a stolen clinician device

- **Can:** read what was shared while it is live; copy or photograph it; author game plans and
  assignments. A stolen device holds the wrapped key record, and an open tab holds unwrapped keys until
  the idle wipe.
- **Cannot:** read the owner's archive or anything not sealed to them; reach another patient's
  relationship (each session is bound to one `relRef`, and content routes also demand that
  relationship's inbox token); forge the owner's data; keep decrypted plaintext by default (reads
  are `Cache-Control: no-store`, and the portal holds plaintext in memory only); or fill the owner's
  storage. What a clinician writes has its own quarter of the relationship's quota, so nothing they
  write can stop the owner publishing a grant that narrows what they may do, or a new share
  (`storage/RelationStore.kt`).
- **Honest limit:** decrypted plaintext is never recallable. Real future revocation is re-pairing to
  new keys (R3).

### T5 — Malicious owner or bearer-token holder (against the operator or the clinician)

A bearer-token holder needs no key to fill the disk or push versions. Defences: per-token and
per-relationship storage quotas; keep-last-N retention per lineage; append-only storage (a version is
never overwritten, and a version that retention would delete at once is refused); server-derived blob
paths from `^[A-Za-z0-9_-]{1,64}$` plus an integer version; a server-computed SHA-256 (a
client-supplied hash is never trusted); a full disk fails closed with 507. There is no decrypt
endpoint to coerce. The plain sync API has one bearer token and lists every lineage, which is right
for one owner per server, so a server serves one owner. Each stored journal belongs to exactly one
owner, and no other credential can reach it (#219). Not built: #318.

### T6 — Supply chain

See §10.

### T7 — Brute force and enumeration

Argon2id makes an offline attack on a strong passphrase infeasible even with the disk. Online:
per-address limits keyed on the trusted client address (§7; the table is
[COMPANION_OBSERVABILITY.md](COMPANION_OBSERVABILITY.md) §1.1), a per-credential lockout on sign-in
codes, and capped backoff on invitations — a wrong guess never burns one; only a human report does.
Comparisons are constant-time, refusals are identical whichever check failed, the anonymous report
route always answers 204, and every token and invitation id is 256 random bits.

## 4. Cryptography and key hierarchy

One primitive set for end-to-end encryption, from libsodium, used identically in the browser and on
the JVM (lazysodium). No custom crypto. The server's own hashing, in the last row, uses Bouncy Castle.

| Purpose | Primitive | As built |
|---|---|---|
| Passphrase to master key | Argon2id, the only KDF | 256 MiB, 3 passes, random 16-byte salt, 32-byte output, client-side only. The salt and cost live in the owner's public `keyparams.json`. |
| Purpose separation | `crypto_kdf`, context `dmsync01` | Subkey 1: sync key. 2: manifest signing seed. 3 and 4: the owner's X25519 and Ed25519 seeds. |
| Content encryption | XChaCha20-Poly1305, random 192-bit nonce | Everywhere. AES-256-GCM is not an accepted equivalent (R1). |
| Per-share key | Random 256-bit CEK | Fresh for every share version. |
| Encrypting to a recipient | X25519 sealed box | Confidentiality and sender anonymity; no forward secrecy (R2). |
| Signing | Ed25519 | The owner signs shares and grants; the clinician signs game plans and assignments. |
| Fingerprints | BLAKE2b over the raw public key | Shown as words for comparison. |
| Clinician key custody | Argon2id-wrapped under a reading passphrase that is not the sign-in code | Stored only in the clinician's own browser; the server never holds it, so a cleared browser loses the keys. The reading passphrase is the only wrap, by decision: a passkey signs in and never unwraps keys (#205). |
| Inbox token | 256-bit random, base64url | Minted by the owner console when a clinician is added (`lib/owner/inboxToken.ts`), shown once, delivered out of band. The invitation cannot carry it: the server only ever sees its digest. |
| Server-side hashing | Argon2id (invitation secrets); BLAKE2b-256 (session ids, inbox tokens, the owner bearer token) | `auth/Secrets.kt`. Constant-time comparisons. |

```
OWNER
  passphrase ──Argon2id(salt)──▶ master ──crypto_kdf("dmsync01")──┬─ 1 ▶ sync key ──XChaCha20-Poly1305──▶ snapshots
                                                                  ├─ 2 ▶ manifest signing seed (Ed25519)
                                                                  ├─ 3 ▶ owner X25519 seed  ┐ the pairing identity
                                                                  └─ 4 ▶ owner Ed25519 seed ┘ (lib/owner/identity.ts)
  owner public keys ──▶ published to the server; the clinician pins them at pairing

CLINICIAN
  X25519 + Ed25519 private keys, wrapped in their browser ──▶ public halves pinned by the owner at pairing

SHARE (owner → clinician)
  subset ──padded──▶ XChaCha20-Poly1305(CEK, AAD = transcript) ──▶ ciphertext
  CEK ──sealed box(clinician X25519)──▶ wrapped CEK
  transcript + ciphertext + wrapped CEK ──Ed25519(owner)──▶ signature
                                           (verified against the PINNED owner key before anything opens)

GAME PLAN / ASSIGNMENT (clinician → owner)
  payload ──Ed25519(clinician)──▶ signed ──sealed box(owner X25519)──▶ blob

SERVER holds ciphertext, sealed CEKs, signatures, public keys, token digests and metadata —
nothing that decrypts a record or authors content.
```

- **The owner's pairing identity is derived, not generated** (subkeys 3 and 4). It exists exactly
  when the journal is readable, and neither a passphrase change nor a new recovery code disturbs it —
  otherwise a clinician could not tell the owner rotating a key from a server substituting one. The
  price: whoever holds the master can sign as the owner, so a recovery code on paper is not read-only.
- **`owner_keys` and `therapist_keys` are insert-only.** `rel_ref` is the primary key and the only
  writer is `INSERT OR IGNORE`, so the first key published for a relationship stays; a second publish
  answers 409, and the owner may read their own published keys back to compare. This is a property of
  the application, not the file: anyone who can write to the database can change a row.
- **The published owner key is a cross-check, never a source.** At sign-in the clinician's portal
  compares it with the key pairing pinned; a disagreement on either half refuses the sign-in and names
  no cause, because a typo, a re-key and a substituting server look the same from there. A record made
  before sealed approvals existed signs in on the published copy behind a caveat
  (`OWNER_KEY_UNPINNED_CAVEAT`, `lib/therapist/inviteAccept.ts`).
- **No escrow (O6).** Forget the passphrase and have no recovery code, and the data is gone: nobody —
  not the maintainer, not the operator — can get it back. That is what makes it safe and what makes it
  unforgiving. A recovery code can wrap the master in the browser (`lib/recovery/`), but the wrapped
  key has nowhere to live yet, so the code cannot be used from another device (#258); the format has
  room for more slots, though a passkey is not one, because it only signs in (#205). A lost
  clinician key means a fresh invitation and re-pairing.
- **The browser consoles are the convenience path.** The phone is meant to become the secret-handling
  path (#138); until then the lower-assurance banner says so wherever keys are handled.

## 5. Sign-in, key custody and sessions

Each person who uses a server signs in with an account of their own — the owner, an administrator,
each clinician and each member of the front desk — and the shared `DAYMARK_AUTH_TOKEN` is nobody's
sign-in (#208). Not built: #314 for staff, #324 for owners. As built, a clinician signs in with a
code per relationship (§5.2), and the owner presents the bearer token (§6).

### 5.1 Passkeys (WebAuthn)

A passkey signs a person in and never unlocks a key: a clinician still unwraps their keys with the
reading passphrase (§4). Six-digit codes stay everywhere as the fallback, and are the only way in on
a server reached by an IP address or by a name the browser does not trust, because a passkey needs
`https` and a hostname (#205). Not built: #326. The four `/v1/webauthn/*` routes answer 501.
`DAYMARK_WEBAUTHN_RP_ID` and `DAYMARK_WEBAUTHN_ORIGINS` are read from configuration now, so a later
implementation cannot fall back to a client-supplied `Host` header.

### 5.2 Six-digit sign-in codes (TOTP)

The only sign-in a clinician has. RFC 6238: HMAC-SHA1, six digits, 30-second steps, one step of
drift, compared in constant time. Each code is accepted at most once (the used step is recorded, so a
shoulder-surfed code cannot be replayed). Five wrong codes lock the credential for 300 s, and a
per-address budget sits in front.

- The seed is generated in the clinician's browser (at least 16 bytes), is distinct from the
  invitation secret, and is sent once, at enrolment.
- **The server stores the seed in the clear** (`auth.db`, table `totp`, column `secret_b64`), because a
  verifier must recompute codes and a hash cannot. Anyone who reads `/data` or a backup of it can mint
  valid codes for every enrolled clinician without anyone noticing. It opens no content: the code
  never unlocks a reading key.
- **There is no way to replace a seed.** `totp` is insert-only, with one credential per relationship
  (`idx_totp_rel_ref`). If a seed may have leaked, withdraw what is shared and start a new relationship
  with a fresh invitation. The clinician can close the old credential themselves (§9a); the owner
  cannot yet (#210).

### 5.3 Step-up for sensitive actions

Step-up is charged only where the annoyance budget charges it
([COMPANION_ACCESS_CONTROL.md](COMPANION_ACCESS_CONTROL.md), The annoyance budget; #205). Adding a
practice member and changing a role need a fresh, unspent six-digit code, which the server checks
(`routes/OrgRoutes.kt`); admitting someone to a care team is charged the same (not built: #289).
Opening a share and publishing a game plan or an assignment need only the session, by design, and
revoking never needs more, because the safe direction stays cheap. `StepUpDialog.svelte` is a
confirmation in the browser, not a step-up. A fresh passkey as step-up: not built, #326.

### 5.4 Sessions

| Control | As built |
|---|---|
| Token | An opaque 256-bit session id in the cookie `daymark_session`, `HttpOnly; Secure; SameSite=Strict; Path=/`. The server stores only its digest. `DAYMARK_COOKIE_INSECURE` drops `Secure`, for plain-HTTP testing only (a startup refusal alongside an https address: #181). |
| Lifetime | 15 minutes idle, 8 hours absolute. |
| Binding | Each session belongs to one credential and one relationship; every request re-checks both, and a session presented for another relationship is refused. |
| CSRF | `SameSite=Strict` plus a per-session token, required as `X-CSRF-Token` on every state-changing request. |
| End | Logout deletes the session on the server; the portal wipes keys and plaintext from memory on logout and when idle. |

### 5.5 Relying party, origins and links

- The passkey relying-party id and origins come only from configuration. The app never reads
  `X-Forwarded-Host`, `X-Forwarded-Proto`, `X-Forwarded-Prefix` or `Forwarded`.
- Links in email are built from `DAYMARK_PUBLIC_BASE_URL`, falling back to the first
  `DAYMARK_WEBAUTHN_ORIGINS` entry. The unauthenticated recovery route goes no further. Routes that
  need an owner token or a clinician session fall back to the request's `Host` as a last resort, which
  compose makes unreachable by always setting the base URL; refusing to start without it is #180.
- Serving under a sub-path (`DAYMARK_BASE_PATH` other than `/`) is not supported: pages move under the
  prefix, but the API stays at `/v1` on the root and the consoles call it there (#176).

### 5.6 Pairing

The protocol is [COMPANION_PAIRING.md](COMPANION_PAIRING.md). The properties the rest of this document
relies on:

- Keys are pinned in both directions before anything is shared. The binding step is a short pairing
  code the owner's device shows and the clinician types; the server relays sealed messages and holds a
  key for neither direction.
- **The pairing code never leaves the device it was typed on** — not in a request body, header, query
  string or log line, on either side. A wrong code is silent key divergence: never an error, never a
  burned invitation. Only a human report burns an invitation.
- An approval that carries no sealed owner keys is refused, so no clinician is enrolled without the
  owner's keys proved to them.
- A matching code on a **fresh** invitation may replace a pinned key, under three conditions: the old
  invitation is already dead (every pairing route demands a pending invitation; see
  `PairingRelayRoutesTest.kt`); the replacement reaches nothing already sealed, and the screen says so
  at the click; and the pin record is insert-only — a new row, with the old one kept as history.
- Keys the server relays outside a pairing (`owner/therapistKeys.ts`) and the manual key-change screen
  (`lib/therapist/pinStore.ts`) still require the fingerprint words read aloud, because nothing has
  replaced them there. A key the console already holds for a *different* relationship is refused.

### 5.7 Recovery and revocation

- No escrow on the server. Server-access recovery (§6) restores the bearer token and nothing else.
- The owner can withdraw a share: the server marks it withdrawn, deletes its bytes, records
  `share.revoke`, and answers 410 to every later read. This binds an honest server only, a
  permanent and stated limit (R3, #222).
- A clinician can end their own relationship (§9a). The owner cannot yet end a clinician's sign-in
  (#210). Rotating the owner's data key for whoever remains authorised is not built (#297).
- One sign-in credential per relationship, as built. An owner may hold several relationships, one
  per clinician, each with its own inbox token. Practices add a control plane and never a key. An
  office needs one sign-in per clinician, however many relationships they hold (#288). Not built:
  #314.

## 6. Server hardening defaults

### Container and runtime

- A distroless Java 21 image pinned by digest: no shell, no package manager, no `curl`. It runs as
  UID 65532, distroless's own non-root user. Health is a static Go binary probing `/readyz`.
- The shipped compose file adds a read-only root filesystem, `/tmp` as a `noexec` tmpfs,
  `cap_drop: ALL`, `no-new-privileges`, AppArmor `docker-default`, an init process, and memory, CPU
  and process limits. Only `/data` is writable.
- **Egress (R7).** By default the network has IP masquerading off, so outbound packets get no reply;
  the opt-in no-egress override removes the gateway entirely. CI boots both and proves egress fails.
  The one deliberate outbound path is SMTP, off by default
  ([COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) §8).
- `/healthz` and `/readyz` are unauthenticated and content-free; `/v1/config` returns only whether
  SMTP is on.

### HTTP

Every response carries these headers (`SecurityHeaders.kt`). This is the only copy of the policy in
the documentation; if it differs from the code, the code is right.

```
Content-Security-Policy: default-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'; object-src 'none'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self'; img-src 'self' blob: data:; font-src 'self'; connect-src 'self'; manifest-src 'self'
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: no-referrer
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Resource-Policy: same-origin
Permissions-Policy: geolocation=(), camera=(), microphone=(), payment=(), usb=(), magnetometer=(), accelerometer=()
```

`'wasm-unsafe-eval'` is the one relaxation, for libsodium's WebAssembly; `blob:` and `data:` images
let the browser show images it decrypted. There is no `unsafe-inline`, no `unsafe-eval` and no
third-party origin. The app sends no `Strict-Transport-Security`, because it cannot know it is behind
TLS; the proxy must add it, and must not add a second CSP, which the browser would intersect with this
one ([COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) §3). Error responses are generic and carry no
stack trace; the server's own log line for an unhandled error does not yet meet that standard
(#160).

### Deliberately not changed

- **`connect-src 'self'` stays.** Relaxing it would not make the consoles' absolute "Server URL"
  fields work across origins — the server has no CORS support, so every authenticated cross-origin
  call fails its preflight anyway — and it is the exfiltration boundary for a page holding a decrypted
  journal and unwrapped keys. The fix belongs in the consoles: stop offering another origin, and stop
  reporting the browser's refusal as an unreachable server (#250).
- **`X-Setting-Key` is not a control.** The server's allowlist (`SETTING_ALLOWLIST` in
  `storage/RelationStore.kt`) constrains a cleartext routing tag the clinician chooses. The setting an
  assignment changes is inside the sealed body, and the shipping clinician client never sends the
  header. Making it mandatory would change nothing, because the same party writes both halves. The
  check that binds is the owner's, on the plaintext, after decrypting (`lib/assignments/inbox.ts`).

### Authentication, limits and denial of service

- The owner bearer token is the server's access credential. It is separate from the passphrase and
  decrypts nothing.
- Per-address limits, keyed on the trusted client address (§7), are tabled in
  [COMPANION_OBSERVABILITY.md](COMPANION_OBSERVABILITY.md) §1.1. Comparisons are constant-time.
- Caps: blobs of at most 25 MiB, upload bodies of at most 26 MiB, JSON bodies of at most 64 KiB, and
  a 120-second limit on reading a request; the newest 200 versions kept per snapshot lineage and 50
  per relationship lineage; 5 GiB of snapshots per token and 256 MiB per relationship, of which what
  the clinician writes may use a quarter and what the owner writes the rest (§3 T4). All are
  configurable ([COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) §5).

### Owner notifications and server-access recovery

- There are no owner accounts or passwords, and the server can never reset a passphrase or PIN.
- **The notification email is stored in plaintext**, because the server must read it to send. A leak
  shows that an owner exists at that address, not any content.
- **The owner bearer token is stored as a BLAKE2b digest** in `owner-account.db`, never as the token.
  (`DAYMARK_AUTH_TOKEN` itself remains the operator's secret file.) The token alone can approve a
  pairing and so enrol a clinician, which is why it may not sit in the clear. Content routes also
  demand the relationship's inbox token, whose digest is all the server holds.
- **Access comes back by proving the owner's own key, never by email** (#208): a key derived from
  the passphrase or recovery code signs a fresh challenge, so control of the registered mailbox
  opens nothing. Not built: #325. Until it is, the emailed re-issue below is how access comes back.
- `POST /v1/recovery/request` is unauthenticated by necessity. It is limited per address (3 an hour),
  always answers 202, and sends mail from a background task, so a matching and a non-matching address
  take the same time. Addresses compare case-insensitively.
- The recovery link is built only from `DAYMARK_PUBLIC_BASE_URL` (or `DAYMARK_WEBAUTHN_ORIGINS`) and
  never from the request's `Host`; with neither set, the request is accepted and nothing is sent. The
  link points at `/recover#t=…`, which no route serves yet (#173).
- `POST /v1/recovery/confirm` replaces the token at once, with no overlap, and returns the new one in
  the response — once, never by mail. A "your access token was re-issued" notice then goes to the
  registered address. The confirm writes no audit entry and no log line and has no rate limit
  (#163).
- An email when a lockout starts: not built (#190).

## 7. Reverse proxy and trusted proxies

- **Default: trust no forwarded header.** Every per-address control keys on the socket peer. There is
  no broad default such as `172.16.0.0/12` (R9).
- `DAYMARK_TRUSTED_PROXIES` names the proxy, narrowly (a `/32`). Only from a trusted peer does the app
  read `X-Forwarded-For`, and it reads it right to left, skipping trusted hops
  (`ClientAddress.resolve`), because the leftmost entry is whatever the client wrote. It reads no other
  forwarded header.
- A proxy may replace `X-Forwarded-For` or append to it; passing the client's header through
  unchanged is the one configuration that breaks every lockout.
- A misconfiguration fails quiet: with an empty or wrong list behind a proxy, all clients share one
  bucket. The app warns once when a forwarded header arrives while the list is empty; a wrong
  non-empty list produces no warning (#167).
- The operator's side: [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) §3 and §4.0. Symptoms and
  the test: [COMPANION_OBSERVABILITY.md](COMPANION_OBSERVABILITY.md) §1. The example nginx config still
  forwards the client's `Host` and has no catch-all server (#209).
- **The admin boundary does not depend on `Host`.** By decision, the admin console and every route
  only an administrator may call can be served on a second port, which the operator's proxy
  publishes only on an admin hostname or over a VPN. The server tells the two apart by listener,
  never by `Host` or `X-Forwarded-Host` (#208). It is a second lock beside the administrator's own
  sign-in, never a replacement for it. Not built: #323.

## 8. Anti-rollback and integrity (client-anchored)

- **Server-side version chains give no integrity (R6).** A hostile server can forge a consistent
  chain over metadata the client supplied. A client must never trust the server's listing on its own.
- The only real anti-rollback is an Ed25519-signed, hash-chained manifest (signed with subkey 2),
  checked against a local watermark the client refuses to go back past. The key and the sign and
  verify functions exist (`lib/sync/crypto.ts`); no client signs or checks a manifest yet, and the
  server stores none. Not built: #138.
- **The manifest public key never changes** — not on upgrade, not when a recovery code is added. It is
  the trust anchor, and a change is indistinguishable from a server swapping the signing identity.
- **Sync is single-writer, last-snapshot-wins (R11).** The newest full snapshot is authoritative.
  There is no row-level merge: no synced table has stable cross-device ids or per-row timestamps.
  That is settled (#200): the phone is the journal's only writer, what another device creates
  travels as new records in a separate, add-only lane, and no device silently replaces a copy it has
  not seen. Not built: #344, #345, #346.

## 9. Audit-logging posture

**Log events, not content.**

- Each relationship has an append-only, metadata-only, hash-chained log in `audit.db`. Each practice
  has its own in `org-audit.db`, a separate file so the two identifier spaces can never meet.
- Entries are written by the server on the real access paths — sign-in, lockout, enrolment, share and
  game-plan reads, assignment and game-plan publishing, share withdrawal, session expiry, pairing
  steps, invitation reports, key registration and fetches, relationship endings, practice membership —
  and never supplied by a client. The closed list is `AuditAction` in `storage/AuditStore.kt`.
- An entry is `seq` (monotonic per relationship), `ts`, `actor`, `action`, `objectRef` (an opaque
  lineage and version) and `meta` (small fixed annotations: a credential id; the source address only if
  enabled), with `entryHash = SHA-256(prevHash ‖ seq ‖ ts ‖ relRef ‖ actor ‖ action ‖ objectRef ‖ meta)`.
- **Never logged:** which records were viewed, any plaintext, keys, content keys, codes or passphrases.
- **The source address is off by default** (`DAYMARK_ACCESS_LOG_SOURCE_IP`): an address geolocates the
  clinic.
- **Retention** is `DAYMARK_ACCESS_LOG_RETENTION_DAYS` (90), applied only on a relationship's next
  append — so a quiet relationship is never pruned — and pruning leaves no marker (#165).
- **Reading.** The owner reads a relationship's log at `GET /v1/rel/{relRef}/audit` with both the inbox
  token (`X-Rel-Token`) and the bearer token. A clinician can neither read nor write it; an operator
  holds neither credential. `GET /v1/relations/{relRef}/audit-chain` (bearer token) recomputes the
  chain (`AuditStore.verifyChain`) and returns the entry count, the sequence extent, the head hash and
  the first break, if any. It writes nothing and logs nothing.
- **What the chain proves (R12).** A stored entry cannot be altered or reordered without breaking
  every later hash. The chain is computed by the server and signed by no one, so a server that never
  appends an event, or cuts the tail off, leaves a chain that checks out. "Access cannot be hidden"
  holds for tampering, not for withholding, and every surface that shows a verdict says so (the owner's
  audit caveat; `CHAIN_CAVEAT` in the admin console). What outlives a lying server is the head hash,
  written down somewhere it cannot reach. Signed clinician attestations were weighed and not adopted
  (#217). The phone keeping its own copy of the head: #138.
- **Missing events:** a refused read of an expired or withdrawn share (`SHARE_DENIED` is declared and
  never written: #164); the token re-issue, invitations minted or expired, reads of the log itself,
  bulk reads, and changes to logging policy (#187).

## 9a. Closing a credential without deleting one

A clinician can end their own access (issue #91). The security-relevant half is one row:

```sql
CREATE TABLE IF NOT EXISTS relationship_endings (
    rel_ref       TEXT    NOT NULL PRIMARY KEY,
    credential_id TEXT    NOT NULL,
    ended_at      INTEGER NOT NULL
)
```

It is written by `POST /v1/relations/{relRef}/ending`, which needs the clinician's session cookie and
`X-CSRF-Token`, with the session bound to that exact relationship (a session for another one is
refused 403). `POST /v1/totp/verify` consults it after the code verifies and answers `410 Gone`
instead of issuing a session; the owner's next share publish to that relationship is refused 410
before the body is read.

**Why a separate table, not a flag or a delete.** `totp` is insert-only, and both halves of that
matter. A DELETE would be worse than nothing: the unique index `idx_totp_rel_ref` is what stops a
second enrolment, so removing the row would let anyone still holding the invitation link enrol a fresh
credential against a relationship somebody has just left — an exit turned into an entrance. An UPDATE
(`disabled=1`) would be a write path into the one table whose safety is that it has none. So the
closure sits beside the credential, and `totp` is never touched. `rel_ref` is the primary key, which
makes the write insert-only and idempotent in the same stroke, as in `therapist_keys` and
`owner_keys`.

**Keyed on the relationship.** One credential per relationship means closing it ends exactly one
relationship and reaches no other patient's work, and an ended relationship stays ended: the way back
is a fresh invitation, which is a fresh relationship.

**What it discloses.** The 410 is reachable only behind a correct code; every earlier refusal on that
route is an identical 401, so someone holding only a credential id — a username, not a secret — learns
nothing. `GET /v1/relations/{relRef}/ending` answers the owner's bearer token with a timestamp, or 404
while the relationship is live, and never echoes the credential id. The audit entry is appended once,
when the ending is recorded, never per attempt — the same rule as a lockout, because a log that grows
a row per retry buries the row that matters.

**Ordering.** The row is written before the sessions are cut. A crash in between leaves "ended, with a
session alive until it times out" — bounded, and already closed to new sign-ins. The other order would
leave "signed out, not ended", which looks like a completed exit and is not one.

## 10. Supply chain and build

- Every `FROM` in `companion/Dockerfile` is pinned by multi-arch digest, and Dependabot proposes the
  bumps (`.github/dependabot.yml`). A digest never changes, so a running server keeps the operating
  system and Java runtime it was built with until it is rebuilt or pulled again.
- The web bundle is built from the lockfile and ships inside the image; nothing is fetched from a CDN
  at run time.
- CI validates the Gradle wrapper, fails if the resolved Netty is below the request-smuggling fixes,
  boots both compose topologies and proves egress fails. The image it pushes to GHCR is the image it
  tested, tagged `sha-<commit>`. The CI egress test covers the shipped topologies, not an operator's
  own compose changes (R7).
- The server is built with the validated wrapper (`./gradlew`), not the builder image's Gradle.
- Not built: an SBOM, provenance and a signature for the image, a published hash of the web bundle, and
  an arm64 image (#241); a checksum for the Gradle wrapper, dependency verification, pnpm's minimum
  release age and actions pinned by commit (#244); dependency audits and lints in CI (#257).

## 11. Out of scope and honest limits

- **Endpoint compromise is out of scope.** A compromised phone, laptop or clinician machine defeats
  everything here, as it does for the flagship app.
- **No forward secrecy on sealed boxes (R2).** A compromise of a recipient's long-term X25519 key —
  the clinician's for shares, the owner's for game plans — decrypts everything ever sealed to it.
  Rotating CEKs does not help. Keeping less, for less time, is what limits the window: no share,
  game plan or assignment is served past 90 days, and the server deletes the bytes of whatever has
  ended (#228). Withdrawing a share deletes its bytes today; the 90-day limit and deletion on expiry
  or replacement are not built: #332, #338.
- **Revocation binds an honest server only (R3).** Honestly: future fetches stop on an honest server;
  data published after re-keying is unreadable to the old key; plaintext already decrypted is never
  recallable. Real revocation is re-pairing to new keys. The limit is permanent and stated, not
  solved (#222): no software can make a server delete what it chose to keep.
- **The browser consoles are not zero-knowledge against a malicious server (R5)**, because the server
  serves the code that holds the keys (§3 T3).
- **Anti-rollback is client-side and not built** (§8). **Sync is single-writer** (R11).
- **Metadata leaks.** The existence, cadence and size of relationships and snapshots are visible to the
  server (§3 T1). Padding, decided in #214, rounds each item's size on the device and leaves timing
  visible. Every encrypted item is padded (#315).
- **Sign-in codes are phishable and stored in the clear on the server** (§5.2). A breach lets an
  attacker sign in as a clinician. It never lets them decrypt. A passkey cannot be phished, and an
  account that closes its code leaves nothing on the server that signs it in (#205). Not built:
  #326.
- **Withholding audit events is undetectable** (R12, §9).
- **No escrow and no recovery by the server**, by design (O6).
- **Non-diagnostic by framing, not by construction.** Game-plan bodies are free text the schema cannot
  constrain, so the authoring screen carries fixed "guidance, not treatment" copy. Share bundles have
  no slot for the PHQ-9 self-harm item and carry check-in scores and bands only. A share is a
  deliberate, consented disclosure to a third person (R8).
- **Availability.** The operator can delete everything. The server is never the source of truth; the
  journal lives on the owner's phone.

## Appendix R — Corrections to earlier drafts

| # | Earlier claim | What is true |
|---|---|---|
| R1 | AES-256-GCM is an equivalent for sync | Removed: random 96-bit nonces under one long-lived key risk reuse. XChaCha20-Poly1305 everywhere. |
| R2 | A sealed box gives forward secrecy | False: it gives sender anonymity only (§11). |
| R3 | Rotating content keys defeats a colluding server's revocation | Retracted: revocation binds an honest server only; re-keying is the real revocation. |
| R4 | A sealed box authenticates the owner | False: a sealed box is anonymous, so the owner signs every share over its transcript, its encrypted body and its sealed key together, and the clinician verifies against the pinned key before opening anything. |
| R5 | SRI and CSP make the browser consoles zero-knowledge | False: they are a lower-assurance path (§3 T3). |
| R6 | Server-side version chains give anti-rollback | Retracted: only a signed manifest checked against a local watermark does (§8). |
| R7 | `internal: true` enforced "no egress" in the original topology | False then: the app shared an egress-capable network with the proxy. What holds now is in §6. |
| R8 | "No server, so no server-side surface" | False for the `sync` build and the Companion. |
| R9 | Trust `172.16.0.0/12` as the default proxy range | Removed: the default trusts nothing (§7). |
| R10 | Game plans land in the phone's `treatments` table | Removed: `treatments` is owner-authored and non-evaluative. Game plans get their own table when the phone side is built (#138). |
| R11 | A three-way, row-level merge with per-row timestamps | Not implementable: sync is single-writer, last-snapshot-wins (§8). |
| R12 | Clinician-signed attestations make access impossible to hide | Partly retracted: a server-computed chain makes tampering detectable, not withholding (§9). Signed clinician attestations were weighed and not adopted (#217). |
