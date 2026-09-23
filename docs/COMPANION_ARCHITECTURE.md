# Daymark Companion — architecture

The Companion is an optional, self-hosted server and a set of web consoles that a person runs on
their own machine. It keeps an end-to-end-encrypted copy of their Daymark journal, lets them read it
on a bigger screen, and lets them show chosen slices to one clinician and receive guidance back. The
server stores ciphertext it cannot read and metadata it can. There is no Daymark-operated service.

This document describes what is built, who trusts whom, and what a compromised server can still do.
The phone app works fully without any of it: the default build has no network permission at all.

| For | Read |
| --- | --- |
| Running a server | [companion/README.md](../companion/README.md), [COMPANION_DEPLOYMENT.md](COMPANION_DEPLOYMENT.md) |
| The threat model, sign-in, hardening, audit | [COMPANION_SECURITY.md](COMPANION_SECURITY.md) |
| How an owner and a clinician pair | [COMPANION_PAIRING.md](COMPANION_PAIRING.md) |
| The clinician's side: shares, game plans, leaving | [COMPANION_THERAPIST.md](COMPANION_THERAPIST.md) |
| Practices, roles, consent | [COMPANION_ACCESS_CONTROL.md](COMPANION_ACCESS_CONTROL.md) |
| The sync wire format | [SYNC_PROTOCOL.md](SYNC_PROTOCOL.md) |
| What the phone must do to join | [COMPANION_PHONE.md](COMPANION_PHONE.md) |

## 1. What the server is for

**Disaster recovery first.** The question a person stands a machine up to answer is "my phone is
lost or stolen; how do I get years of my life back?" Everything else, including reading on a laptop,
is a side effect.

- **The passphrase is the key.** The sync key is derived from the passphrase alone (Argon2id, then
  purpose-separated subkeys; SYNC_PROTOCOL.md §1). Nothing is bound to the handset, so a new device
  with the same passphrase reads everything. The other half of the same fact: **forget the passphrase
  and the copy on the server is gone** — to the owner, to the operator, to Daymark. The server has
  never had the key. That sentence belongs where the passphrase is chosen, not in a footnote.
- **A recovery code** is the designed second way in: one data key wrapped under the passphrase and
  again under the code, so either opens it. The cryptography is built and tested
  (`companion/web/src/lib/recovery/`), and the owner console already opens with such a key file and
  either secret. Wrapping an existing owner's sync key this way, and storing the file on the server
  so a code works from a new device, are not built: #258. Until then, the sentence above stands.
- **One copy, on one disk.** The server keeps what it is sent and copies it nowhere. The phone keeps
  its own local copy, which stays the primary one.
- **Getting a copy there.** The phone cannot send one yet: #168. Today a snapshot is pushed from an
  exported backup file with the command-line writer (`pnpm push` in `companion/web`), and read back in
  the browser.

## 2. Three shapes: Solo, Paired, Practice

| Shape | Who owns the machine | Whose data is on it |
| --- | --- | --- |
| **Solo** | the person | theirs |
| **Paired** | the person | theirs, some of it shown to one clinician they invited |
| **Practice** | a clinic | many people's, as tenants |

- **Solo and Paired are one product with a flag.** Same trust model, same threat model; the
  clinician surfaces switch on with `DAYMARK_THERAPIST_AUTH=1`. With it off, every relationship route
  answers 503.
- **Practice inverts the arrangement.** In Solo and Paired the journal sits on its owner's hardware.
  In Practice the clinic owns the machine and the person is a tenant on it. That is a different
  posture, not a bigger deployment. What exists for it: the practice model on the server (three
  planes — data, control, monitoring — with the data plane declared and empty; six member roles as
  presets over capabilities; membership and a separate practice audit chain) and the practice console
  (`practice.html`). A role never carries a key: what a clinician can read comes only from a grant the
  owner signed. The model is specified in COMPANION_ACCESS_CONTROL.md, which marks what is built.
- The question that gates Practice — who may reset a forgotten passphrase — is answered: nobody
  (COMPANION_PAIRING.md §12).
- The first-run screen of the owner page asks which shape the machine is for and remembers the
  answer in that browser only. It changes nothing on the server.
- What the Companion is for in the long run (hosting, editions, the smallest set of roles for a
  pilot, whether the old one-clinician scope still holds) is an open decision: #288. No real
  patient's data belongs on a Practice deployment before an outside assessment: #284.

## 3. Parties and trust

| Party | Holds | Trusted for |
| --- | --- | --- |
| **Owner** (the person whose journal it is) | The passphrase and the keys derived from it; the bearer token | Everything. The owner is the only root of trust |
| **Clinician** (invited by the owner) | Their own keys, wrapped under a reading passphrase in their browser; a TOTP credential | Reading what the owner shares with them, for as long as the owner allows |
| **Server** (the Companion container) | Ciphertext, public keys, metadata, the audit log | Availability only. Never for confidentiality, never to vouch for a key |
| **Operator** (runs the container; often the owner) | The host, the environment, the logs | Keeping it running. Holding the host is not holding anyone's key |
| **Practice administrator** | Membership and roles, on the control plane | Administration. No role reaches data |

- **The owner has no account.** The owner's credential is the bearer token (`DAYMARK_AUTH_TOKEN`),
  stored on the server as a digest. An optional recovery email can rotate it
  (COMPANION_SECURITY.md §6); it recovers server access only, never the passphrase. How the owner and
  the administrator should prove who they are — first-run claim, a credential for the admin console
  (which today mounts with none), recovery as a second front door — is an open decision: #208.
- **Clinicians sign in with a six-digit TOTP code** and a session cookie. Passkeys are designed, and
  the server's WebAuthn routes answer 501: #205.
- **The owner's browser is a lower-assurance client.** The owner console, the snapshot reader and the
  pairing ceremony all run in pages the server serves (§6). The console says so on every visit.

## 4. Components

### 4.1 The server

Kotlin and Ktor, in a non-root, read-only, distroless container with no outbound network by default
(the one exception is SMTP, off unless configured). Everything it stores lives under `/data`:

| Store | Holds |
| --- | --- |
| `index.db`, `blobs/`, `keyparams.json` | Sync: append-only snapshot ciphertext per lineage, and the published KDF parameters |
| `rel-index.db`, `rel/` | Per-relationship channels — grants, assignments, shares, game plans — as opaque signed-and-sealed blobs |
| `auth.db` | Invitations, enrolment tickets, TOTP credentials, sessions, published public keys, attempt windows, relationship endings |
| `pairing.db` | Pairing runs: two CPace messages and two sealed envelopes each |
| `audit.db`, `org-audit.db` | The owner-readable access log and the practice's control-plane log, each a hash chain |
| `org.db` | Practices and memberships |
| `owner-account.db` | The owner token's digest, the notification address, recovery-link state |

Route groups: sync (`/v1/keyparams`, `/v1/snapshots`); relationship channels (`/v1/rel/…`);
invitations, pairing and sign-in (`/v1/invite…`, `/v1/relations/{relRef}/pairing…`, `/v1/totp/…`);
keys, endings and the access log (`/v1/relations/{relRef}/…`); owner notifications and access recovery
(`/v1/owner/notifications`, `/v1/recovery/…`); practices (`/v1/orgs…`); and the unauthenticated
`/healthz`, `/readyz` and `/v1/config`.

### 4.2 The web consoles

Svelte and TypeScript, built by Vite into one static bundle the server serves. Every page loads only
from its own origin; all cryptography runs in the browser (libsodium).

- **Owner page** (`index.html`): open an exported backup file; read the encrypted copy from the
  server; the self-check engine and a focus task (COMPANION_FEATURES.md); the tool builder; access
  recovery; and the **owner console** — unlock with a key file and the passphrase or recovery code,
  invite and pair a clinician, grant capabilities, build shares, review assignments, read the access
  log, set notifications.
- **Clinician page** (`therapist.html`, at `/therapist`; invitation links arrive at `/portal/invite`
  and are redirected there): accept an invitation, sign in, a dashboard of what was shared, and a
  today view, client record and calendar built from it; assign from the catalogue, author game
  plans, see what the owner allowed, leave.
- **Server console** (`admin.html`): what an operator can check from a browser — health, readiness,
  sign-in pressure, and audit-chain checks. It holds no credential of its own (#208).
- **Practice console** (`practice.html`): membership, roles, removal, the practice log.

### 4.3 The phone

The Android app has two build flavours. `foss`, the default and the one released, declares no
`INTERNET` permission, and CI fails if it ever does. `sync` adds the permission and the Kotlin port of
the sync and pairing cryptography (`sync-crypto/`, tested on a plain JVM). Nothing in `sync` talks to a
server yet; COMPANION_PHONE.md lists what remains, starting with #168.

## 5. The cryptography

One primitive set, the same on every client (libsodium in the browser, lazysodium on the JVM):

| Purpose | Primitive |
| --- | --- |
| Passphrase to key (client-side only) | Argon2id, 256 MiB, 3 passes floor; readers refuse weaker parameters |
| Subkeys from the master | `crypto_kdf`, context `dmsync01`: 1 sync key, 2 manifest signing seed, 3 and 4 the owner's pairing identity |
| Symmetric encryption | XChaCha20-Poly1305, random 24-byte nonce |
| Keys per party | X25519 (encryption) and Ed25519 (signing) |
| Sealing to a recipient | `crypto_box_seal` (anonymous sender, no forward secrecy) |
| Fingerprints | BLAKE2b (16 bytes) over the public key, base64url |
| Pairing | CPace over ristretto255 (COMPANION_PAIRING.md) |

AES-GCM is not used anywhere: random 96-bit nonces under one long-lived key are not safe. On the
server, secrets it only has to check are stored as hashes (invitation secrets with Argon2id, tokens
and tickets as BLAKE2b digests); a clinician's TOTP seed is necessarily kept as it is, because the
server computes codes from it. A startup self-test of the cryptography is not built: #201.

**The flows.**

- **Sync.** A client encrypts a whole backup snapshot under the sync key and uploads it append-only
  under a lineage and version; the version is bound into the encryption, so a blob moved to another
  path does not decrypt. Readers check integrity with the AEAD only. The signed manifest and the
  device-side watermark that would stop a server passing off an older snapshot as the newest are not
  built: #179.
- **Grant (owner to clinician).** The owner's capability policy, signed and published. Every
  capability starts off (COMPANION_ASSIGNMENTS.md).
- **Share (owner to clinician).** A curated bundle — check-in scores and bands, moods, journal, sleep,
  with notes stripped by default and never a raw answer or a self-harm item — encrypted under a fresh
  key that is sealed to the pinned clinician. The owner signs the share's header (share id, version,
  recipient, expiry, owner fingerprint), and the clinician refuses to open anything whose signature
  does not verify against the pinned owner key. The server refuses reads after expiry or withdrawal.
- **Assignments and game plans (clinician to owner).** The clinician signs the payload, bound to a
  context string and the owner's fingerprint, then seals it to the owner. The owner's console opens
  assignments, verifies them against the pinned clinician key and checks them against the current
  grant before anything applies. Receiving game plans in the owner's console is not built: #231; on
  the phone: #177. Game plans never land in the app's `treatments` table; they are the clinician's
  words, kept separate and read-only.

## 6. Honest limits

What a compromised or dishonest server can still do. Each of these is stated to the people it affects
rather than left to be discovered.

| Limit | What it means | Where it stands |
| --- | --- | --- |
| **Metadata is visible** | Snapshot sizes, timing, how often someone syncs, how many relationships exist and how active each is, and the addresses requests come from. "Journalled daily for eight months, then nothing for nine days" is readable without decrypting anything | Disclosed, not mitigated. Padding sizes is an open decision: #214 |
| **It can deny service** | Refuse writes, withhold or truncate the audit log, serve an older snapshot | Readers verify each blob, not freshness: #179 |
| **It serves the web pages** | A compromised server can ship JavaScript that keeps a passphrase or a pairing code. Browser-delivered encryption is only as strong as the delivery of the code. The installed phone app does not have this weakness | Stated on every console. The owner's half of pairing moves to the phone with #174 |
| **It writes the audit log about itself** | The hash chain shows internal consistency, never completeness: whoever can rewrite the entries can recompute the chain, and withholding an event is undetectable | The chain head is evidence only when anchored outside the server — a person's note today, the phone later: #182. Signed attestations are an open decision: #217 |
| **No forward secrecy for sealed items** | Anyone who later obtains a clinician's long-term key can open every share ever sealed to it that is still stored | Shares require an expiry (at most 366 days), each lineage keeps at most 50 versions, and withdrawal deletes the bytes; expiry alone does not. How long shared data should live is open: #228 |
| **Revocation binds an honest server** | Expiry and withdrawal stop future fetches on an honest server. They do not un-send what was read, and a colluding server can keep serving what it holds | The real remedy is re-pairing with new keys. Whether to accept this as a permanent limit is open: #222 |
| **The bearer token travels on every request** | On plain HTTP anyone on the wire can replay it (never the content: that is encrypted) | Signed requests replace it: #186 |
| **The clinician's browser holds plaintext** | Keys are wrapped at rest and wiped when idle; extensions and screenshots are beyond any control | Telling clinicians plainly: #262. A pinned client instead of a served page: #222 |

## 7. Rules that hold everywhere

- **The server never sees content, and nothing it logs carries content.**
- **The server vouches for no key it relays.** Keys are pinned from the pairing, never taken from a
  server response on its word.
- **Only a human report ends an invitation.** A wrong pairing code is silent key divergence, never an
  error and never a burn.
- **No green, no score, no streak, no success state** in any console; a gap in someone's data is
  never drawn as a failure (COMPANION_DESIGN_SYSTEM.md).
- **Non-diagnostic by framing.** Every result and every assignment or share screen carries a fixed
  statement that it is not a diagnosis. Free-text guidance from a clinician cannot be constrained by a
  schema, so this is a rule about framing, not a structural guarantee.
- **No generated content.** Every word is fixed, human-written text with the person's own numbers.

## 8. What the Companion will never do

Excluded on principle, not deferred.

- **Share location, presence or "where is the patient"** at any resolution. Coarse categories are
  only vague to a stranger, and the one person reading them knows the patient's life well enough to
  resolve them; producing a coarse category still means collecting fine location in the background;
  and there is no safe off-switch — a visible one becomes an accusation, an invisible one makes the
  product lie to the viewer. Consent given to someone who holds power over your care is not freely
  given. What the rule permits instead: tags the person writes on their own entries (who they were
  with, where in their own words), with any pattern shown only to them.
- **Run timed or reaction-time tests on the phone.** Unsupervised timing on unknown handsets measures
  the hardware and the room, not the person, and would be the app's largest untrusted-media surface.
  Timed tasks live only on the Companion's sit-down page, and even there they report within-person
  counts, not norms (COMPANION_FEATURES.md §3).
- **Fetch tools, questionnaires or code from anywhere.** The catalogue ships inside the app and the
  consoles; anything else is authored locally. There is no store, no feed and no tool pack, which
  keeps the no-network and reproducible-build claims checkable and adds no parser for untrusted
  input. The only content designed to arrive from outside is what the owner's own paired clinician
  signs, inside a capability the owner granted: assignments that name catalogue items, and, not yet
  built, dialogue that the sandboxed evaluator reads as data (COMPANION_DIALOGUE.md, Findings 4
  and 5).
- **Be a Daymark-run service**, hold a key in escrow, reset a passphrase, phone home, or carry
  telemetry, analytics or third-party origins.
- **Diagnose, score risk, or carry a self-harm answer.** The phone stores check-ins as scores only,
  so the answer to the PHQ-9 self-harm item is never kept; share bundles and catalogue tools have no
  slot for one, and the catalogue's validator rejects any definition that tries.
- **Become the source of truth.** It is a replica; the phone keeps its own copy.
