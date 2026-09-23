# Companion pairing

Pairing is how an owner and one clinician come to hold each other's public keys without trusting
the server that carries every message between them. The owner says a short code to the clinician;
both devices turn that code into the same key through a password-authenticated key exchange
(CPace); two sealed envelopes carry each side's public keys under that key. The server relays
everything and learns neither the code nor the key.

As built, both halves run in the browser: the owner's in the owner console, the clinician's on the
acceptance page. The phone's half is specified in §14 and not built: #174. Pairing grants access
to nothing (§10).

Code: `companion/web/src/lib/pairing/`, `companion/web/src/lib/therapist/pairingAccept.ts`,
`companion/server/src/main/kotlin/com/daymark/companion/auth/PairingStore.kt`,
`companion/server/src/main/kotlin/com/daymark/companion/routes/PairingRelayRoutes.kt`, and the Kotlin
mirror `sync-crypto/src/main/kotlin/com/daymark/synccrypto/CpaceCrypto.kt`.

## 1. What pairing defends against

The server is the adversary the design excludes: it holds no code and no key, so it cannot run the
exchange itself and sit in the middle. The emailed link alone is worthless, because the code travels
by another channel.

| Threat | As built (both halves in the browser) | Owner's half on the phone (#174) |
| --- | --- | --- |
| Someone intercepts the invitation email | Prevented: the link without the code opens nothing | Prevented |
| A network attacker between the two people | Prevented | Prevented |
| The wrong person opens the link first | Prevented: their reply does not open, so nothing of theirs is approved | Prevented |
| A server that logs and relays but does not tamper | Prevented: it never sees the code | Prevented |
| A server that serves malicious JavaScript to the owner's browser | **Not prevented.** The page can read the code as it is typed | Prevented: the server cannot rewrite an installed app |

A PAKE excludes the server from the protocol, not from the code that runs the protocol when the
server is the one serving that code. That bottom row is the reason the owner's half belongs on the
phone; it is a strengthening against the worst case, not a precondition for the rest.

**Who starts a pairing.** The owner, always. The owner's device opens every run and is the CPace
initiator. Whether a clinician may start one too is an open decision: #215.

**Post-quantum.** Not now, by decision: hybrid post-quantum PAKEs are early drafts, and nothing in
this threat model justifies tracking one.

## 2. The pairing code

**Shape.** Eight symbols shown as two groups of four, `K7M4-RD96`. Seven carry entropy; the eighth
is a check symbol. The alphabet is the recovery code's 31 symbols: digits 2–9 and letters A–Z
without I, L and O. Input is case-insensitive; spaces and dashes are stripped and the code is
canonicalised to eight upper-case symbols before it becomes bytes. Words were rejected because the
key-fingerprint wordlist already means "compare this aloud".

**The check symbol** is a weighted sum mod 31 (weights 1–7, prime modulus), so it catches any single
wrong character and any two swapped characters on the clinician's device, before a run is spent.
Larger errors are caught 30 times in 31; the rest spend a run and fail silently like any wrong code
(§6). A fault message names the position and never echoes what was typed.

**One code per invitation run, never stored.** `newPairingCode()` draws the seven symbols from the
CSPRNG with rejection sampling. The owner's screen shows it; nothing persists it (§5). A pairing code
is meant to be said aloud once, to one person; a recovery code shares the alphabet and must never be
said to anyone. The copy around each says which one it is.

**Two channels.** The link goes by any route, including the server's optional invitation email. The
code goes by another: spoken in the room, on a call, by text. The Companion never offers to email the
code, and the reason is written at the call site: a "send both" convenience would collapse the
design back to "whoever holds the link wins".

**Why eight characters are enough.** Seven symbols is 7 × log2(31) ≈ 34.7 bits, absurd for a
password and sufficient here because of what CPace gives. Someone holding the link but not the code
gets at most one online test of a guess per run the owner opens and acts on, at most eight runs per
invitation, and no offline test at all (the transcript reveals nothing about the code). Eight guesses
against 31^7 ≈ 27.5 billion codes is a chance below one in three billion per invitation, and every run
is visible to the owner.

## 3. The protocol: CPace

CPACE-RISTRETTO255-SHA512 from `draft-irtf-cfrg-cpace` (an Informational-track CFRG draft, not an
RFC). Two independent implementations exist: `cpace.ts` (libsodium-wrappers-sumo) and
`CpaceCrypto.kt` (lazysodium-java). Each is pinned byte-for-byte to the working group's published test
vectors, so they agree through the standard rather than through each other. A balanced PAKE fits
because both people genuinely hold the same short secret; an augmented one (OPAQUE) is for a server
that stores a verifier, and this server is the party being excluded.

One round trip, relayed store-and-forward: the owner starts, the clinician responds whenever they
open the link, and the owner finishes the next time they look. No two touches need to happen at
once, and no call needs to be scheduled.

- Roles are fixed: the owner is the initiator, so the initiator/responder transcript is used and the
  parallel ("oc") variant is not implemented.
- The draft's mandatory abort on an invalid or identity point is kept.
- A mismatched code yields two different 64-byte keys and no signal. That is the property §6 and
  §7 rest on.

## 4. The ceremony, step by step

| # | Who | What happens | Request |
| --- | --- | --- | --- |
| 0 | Owner | Mints an invitation for a relationship; gets the link. Optionally asks the server to email it | `POST /v1/invite` |
| 1 | Owner | Draws a code, derives MSGa, posts it with a fresh 16-byte session id | `POST /v1/relations/{relRef}/pairing` |
| 2 | Clinician | Types the code and a name; fetches MSGa, which also tells the browser the relationship reference | `POST /v1/invite/{inviteId}/pairing/fetch` |
| 3 | Clinician | Generates keys (only now, once the relationship is known), wraps them under a reading passphrase, derives MSGb and the key, seals **E1** (their public keys, the name, an enrolment ticket they chose), posts MSGb and E1 once | `…/pairing/{exchangeId}/respond` |
| 4 | Owner | Reads the reply, derives the same key, opens E1 or gets nothing | `GET /v1/relations/{relRef}/pairing/{exchangeId}` |
| 5 | Owner | Sees the name and fingerprints; approves. Seals **E2** (the owner's two public keys) and forwards the clinician's ticket | `…/pairing/{exchangeId}/approve` |
| 6 | Clinician | Polls until approved; receives E2 and pins the owner's keys before enrolling | `…/pairing/{exchangeId}/status` |
| 7 | Clinician | Enrols a TOTP credential with the ticket, signs in, registers public keys | `POST /v1/totp/enroll`, `POST /v1/totp/verify`, `/v1/relations/{relRef}/therapist-keys` |

**Nothing retries by itself.** A retry after a reply that did not open is a person's decision: "New
code" on the owner's side (same link, fresh run, one of the eight spent), and on the clinician's,
asking for that new code and typing it. An automatic retry would spend the invitation's runs on a bad
phone line.

**One run, one key.** The owner's state pins the reply that produced its key; a later read showing a
different reply is refused, never re-derived. The pin survives a reload.

**What each side keeps across the gap.** Both keep their CPace scalar and the public messages in
`sessionStorage` (per tab, gone when the tab closes): `pairing/ownerRunStore.ts` on the owner's side,
the run record in `therapist/pairingAccept.ts` on the clinician's. Neither keeps the code or the
derived key; the key is re-derived from the scalar. A run that outlives its tab is handled by giving a
new code.

**No enrolment without approval.** There is no route that trades the invitation secret for an
enrolment ticket. A ticket is minted in exactly one place, the owner's approve, and it is the one the
clinician chose and sealed under the pairing key. A link-holder can open a conversation and answer
once; what they seal cannot be opened, so it is never approved.

**The inbox token is not part of pairing.** Each relationship has a 256-bit inbox token that content
requests carry in `X-Rel-Token`. The server stores only its digest, and the digest is the
relationship reference, so the server cannot hand it back. The owner console mints it when the owner
adds a clinician and shows it once; the owner gives it to the clinician directly, and the clinician's
sign-in asks for it (remembered for the tab).

## 5. The server never learns the code

This is the invariant everything else rests on: **the pairing code is generated on the owner's
device, typed on the clinician's, and never sent to the server by either side, in any form.** A server
holding the code could run the exchange itself.

It is structural in `relay.ts`: the code enters CPace as the password string and nothing else reads
it; every request body is built from CPace outputs (group elements the code cannot be recovered from)
or from ciphertext under the derived key. `relay.test.ts` drives a whole pairing through a recording
transport and searches every URL, header and body for the code in five encodings (verbatim, base64url,
standard base64, hex, URL-encoded), with planted examples proving the search can find one. Separate
tests serialise both sides' stored records and search them for the code. The server has no field, log
line or table that could hold it.

## 6. A wrong code is not an error

Mismatched codes produce two different keys and silence: no error on the wire, no event the server can
see, no burn. The mismatch surfaces in exactly one place, when E1 does not open on the owner's side,
and it surfaces as a null: one bit, no diagnosis. A typo and a stranger holding the link produce the
same null, so the screens ask rather than conclude.

- **Owner:** "A reply did not open with your code", then a question — keep the invitation open and ask
  the person whether they answered, or stop it and send a new link. One notice on the invitation
  screen, and no notification (a browser tab cannot raise one honestly). The count of runs left
  stays a count ("6 of 8 tries left on this invitation"), never a warning.
- **Clinician:** "That code is no longer open" and a request for a new code. The status poll cannot
  distinguish a wrong code from a cancelled run, by design.
- Nothing about a reply that did not open changes how often anything is asked of the server.

## 7. Guess versus report

A wrong code is invisible to the server. What the server can see is a wrong **invitation secret**
(the `s=` part of the link) presented on a pairing route. That is a guess; a person saying "this was
not me" is a report. Only a report ends an invitation.

| Event | What it means | What happens |
| --- | --- | --- |
| Wrong invitation secret on fetch or respond | Unknowable: a stale link or someone probing | 401. One `pair.guess_failed` audit row. After 5 failures (`DAYMARK_TOTP_LOCKOUT_FAILS`) the invitation's secret locks for 300 seconds (`DAYMARK_TOTP_LOCKOUT_SECONDS`), and the count starts again once a lockout has been served; one `lockout` row when a lockout arms, none per probe while locked. **Never ends the invitation.** |
| A person reports it ("Stop this invitation", or the clinician's "I didn't expect this") | Unambiguous | The invitation goes to `REPORTED` at once and its ticket is deleted. Idempotent. Honoured even while the invitation is locked. |

- Fetch, respond and report share one fail counter per invitation, so alternating routes buys no
  extra guesses.
- Fetch and respond also share one per-address window (20 per 5 minutes), kept in the database so a
  restart does not reset it.
- The status poll is metered per run rather than per address (a burst of 6, then one per 10 seconds),
  so one person's waiting never spends another's on the same clinic network.
- The anonymous report route has no attempt budget, only a flood guard (30 per minute per address),
  and answers 204 to every anonymous call whatever happened, so it cannot be used to learn whether an
  invitation exists or is live. The owner's report (with the bearer token) answers plainly.
- The owner sees wrong-secret attempts counted on the invitation and, one row each, in the access
  log. No alert is sent when they pass a threshold: not built: #190.
- The clinician's "I didn't expect this" is accepted by the server but no clinician screen offers it
  yet: #213.

## 8. The invitation and its runs

An invitation is minted by the owner with a scope and a lifetime (default 72 hours,
`DAYMARK_INVITE_TTL_SECONDS`). Its secret is 32 random bytes, stored only as an Argon2id hash.

| Invitation state | Meaning |
| --- | --- |
| `PENDING` | Open. Runs can be opened and answered |
| `REDEEMING` | The owner approved a run; the clinician's ticket is live until the invitation expires |
| `CONSUMED` | The clinician enrolled with the ticket |
| `EXPIRED` | Past its lifetime (applied lazily; the owner's list computes it without writing) |
| `REPORTED` | Ended by a person's report |

Runs (the table `pairing_exchanges` in `pairing.db`) move only forward:

- `OPEN` → `RESPONDED` (the one reply) → `CLOSED` (the owner's approve, with E2 in the same write).
- `OPEN` or `RESPONDED` → `CANCELLED` (the owner's Cancel; the invitation stays `PENDING`).
- `CLOSED` → `CANCELLED` is an **abandon**: an approval nobody finished. The invitation returns to
  `PENDING`, its ticket is deleted, and the owner can start over with a new code.
- `OPEN` → `SUPERSEDED` when the owner opens a fresh run. At most one run per invitation is open.

Rules: runs can be opened and answered only while the invitation is `PENDING`; the status poll is the
one touch allowed on a `REDEEMING` invitation, and it returns a state word. An invitation allows **8
runs**; past that the owner mints a fresh invitation, which also rotates the link. A run row is
never rewritten: after its insert only its state moves, and the reply and each envelope are written
once. The owner's list
(`GET /v1/relations/{relRef}/invites`) shows each invitation's state, wrong-secret count, runs used,
and the newest run's state. The owner's read of a single run applies no expiry, so the console shows
the invitation's state beside it.

A link-holder can answer every run first. That denies the ceremony, never the code; the owner sees
replies that did not open and can stop the invitation.

## 9. After pairing: keys, pins, and replacing them

| Direction | How the key is learned | What it rests on |
| --- | --- | --- |
| Owner learns the clinician's keys | E1, opened only by deriving the key from the code | The code |
| Clinician learns the owner's keys | E2, sealed at approval under the same run's key in the other direction | The code |

The two envelopes use different keys derived from the same ISK, so neither can be reflected back as
the other, and each decoder refuses the other's shape. An approval without E2 is refused (400) and
spends nothing, because a clinician enrolled with no owner keys proved to them could verify nothing
the owner signs.

- **The server vouches for no key it relays.** Both sides also publish public keys to the server
  (`/v1/relations/{relRef}/therapist-keys`, `/v1/relations/{relRef}/owner-keys`, insert-only by
  primary key). Those copies are a cross-check, never the source: a clinician's sign-in compares the
  published owner key with the pinned one and refuses on disagreement. Keys taken from the server
  alone must have their fingerprints read aloud on another channel before the owner console pins
  them.
- **Records that predate E2** have no ceremony pin. They sign in on the published copy behind a
  caveat saying nothing proved these keys, and a fresh invitation replaces them. There is no field
  anywhere for a clinician to type an owner key.
- **Replacing a pinned key.** A matching code on a fresh invitation is enough to replace the keys the
  console holds for that clinician, because the original pin rested on the same proof. It always costs
  a fresh invitation; it reaches nothing already sealed ("Anyone holding the device that carried them
  can still open every share sent to them before now" is said at the click); and the pin record is
  insert-only, so a replacement is a new row beside the old. The console still refuses keys it holds
  for a *different* clinician, because then a share could be opened by the wrong person.
- **Fingerprints are demoted, not deleted.** Both screens show the key fingerprints for anyone who
  wants to compare them later, with the note that the code already did that job. Where no code was
  involved, the comparison aloud is still required: the fingerprints for keys taken from the server,
  and the word phrase on the manual key-rotation screen.

## 10. Connecting is not sharing

Pairing establishes who someone is and grants access to nothing. Sharing is a later, separate act on
a screen that states what becomes visible, and every capability starts off. A perfectly executed
attack on the pairing yields an empty connection.

| Layer | Answers |
| --- | --- |
| The link (and, when built, a QR code) | Where to go. Public, forgeable, trusted for nothing |
| The PAKE | Both ends hold the same code; one guess per run for anyone in the middle |
| The owner's approval | Do I want this person |
| Sharing, later | What they may actually see |

A QR code would carry the address and invitation id only, never a secret, and the typed path must stay
able to do everything the scan does: not built, #189.

## 11. Ending a connection: Leave and Revoke

Two verbs, deliberately asymmetric. A clinician may end their own participation; only the owner may
end the clinician's access to the owner's data. If a clinician's "disconnect" could touch the owner's
material, a stolen clinician credential would be a way to destroy someone else's records.

- **Leave (clinician), built.** `POST /v1/relations/{relRef}/ending` writes one insert-only ending
  row, cuts every live session of that credential, makes TOTP refuse it from then on, and appends one
  audit line the owner reads. It withdraws no share, deletes no blob, alters no grant, rotates no key.
  It cannot reach what the clinician already decrypted. The owner is not emailed: #216.
- **Revoke (owner).** The owner withdraws a share lineage (future fetches answer 410) or turns
  capabilities off in a re-signed grant, and can stop an invitation. The owner cannot yet end a
  clinician's sign-in or live sessions: #210.
- **"Revoking does not un-send what was already read."** That sentence appears verbatim at the point
  of every revoke.
- **A revoke is not a message.** The clinician has no push channel, and the owner's screen says "the
  other person is not told" rather than implying they were notified.
- **There is no reconnect.** Coming back is a fresh invitation and a fresh code. Nothing survives an
  ending that could be replayed to restore trust, which is what makes "one-time" a property rather than
  a promise. The cost is stated: ending is not reversible, and re-pairing needs both people.

## 12. Nobody resets a passphrase

Not the clinician, not a practice administrator, not whoever runs the server, not Daymark (issue
#100). A clinician who forgets their reading passphrase has lost their keys, and every relationship
they hold is re-paired with a fresh invitation from the owner. An escrow that let a practice recover a
clinician's keys would let the practice read its patients' journals, and the clinical deployment is
the most sensitive one. "Administrator reset, with an audit row" was considered and refused: an audit
row is a receipt, not a lock. This is the same price the Solo shape already charges.

Enforced rather than remembered: no practice role or action names a key, passphrase, reset, recovery
or escrow (`OrgModelTest`, with planted entries proving the detector sees them); the data plane is
declared and empty; a membership has no field a key could be written into; and the clinician's
passphrase screen names the three people who cannot reset it (`PASSPHRASE_NO_RESET` in
`companion/web/src/lib/therapist/inviteAccept.ts`).

The owner's side is the same: the passphrase is the key, and nobody can reset it
(COMPANION_ARCHITECTURE.md §1). The recovery code is the owner's own second way in, never someone
else's reset; what it can recover today is limited, and the rest is #258.

## 13. The wire contract

The bytes a second implementation (the phone, §14) must reproduce. References:
`companion/web/src/lib/pairing/relay.ts`, `envelope.ts`, `payloads.ts`, and `relay.test.ts`.

### 13.1 Encodings and identifiers

- Every binary value on the wire is base64url without padding (RFC 4648 §5).
- The invitation link is `{publicBase}/portal/invite#id=<inviteId>&s=<secret>`. The fragment never
  reaches the server; `/portal/invite` answers with a redirect to the clinician's page, and the
  browser carries the fragment across it.
- **sid**: 16 random bytes chosen by the owner for each run.
- **Channel identifier (CI)**: `lv_cat("daymark/cpace/v2", relRef, inviteId)`, the draft's
  length-prefixed concatenation (LEB128 length, then bytes; UTF-8 strings). The invitation id comes
  from the invitation the device holds, never from a response: the clinician learns `relRef` only from
  the server, so the invitation id is the value that binds a run to an invitation.
- **Associated data**: ASCII `owner` for MSGa, `therapist` for MSGb.
- **Password (PRS)**: the canonical eight-symbol code as UTF-8, checked again for canonical form
  before it becomes bytes.

### 13.2 Messages and key

MSGa = `lv_cat(Ya, "owner")`, MSGb = `lv_cat(Yb, "therapist")`, each with a 32-byte ristretto255
point. The server accepts 34–200 decoded bytes per message. The key is the 64-byte ISK:
`SHA-512(lv_cat("CPaceRistretto255_ISK", sid, K) || MSGa || MSGb)`. The clinician can re-derive it after
a reload from their scalar and the three public messages, without the code.

### 13.3 Envelopes

- Sealing key: `crypto_generichash(32, utf8("daymark/pairing/key/v1|" + direction), key = ISK)`, the
  whole 64-byte ISK as the BLAKE2b key. Direction is `therapist-to-owner` (E1) or
  `owner-to-therapist` (E2).
- Wire: `0x01 | nonce(24) | XChaCha20-Poly1305(payload, AAD, nonce, sealing key)`, a fresh random
  nonce per seal.
- AAD: `utf8("daymark/pairing/env/v1|" + sidB64 + "|" + direction)`, with the sid exactly as the owner
  posted it.
- The server accepts 41–4096 decoded bytes per envelope and never parses one. Opening fails as a null
  for every cause.

### 13.4 Payloads

UTF-8 JSON, keys in this order, strict on the way in: an unknown `v`, a missing or extra field, or a
wrong length is refused as a whole, never repaired.

- **E1 (offer)**: `{"v":1,"boxPubB64","signPubB64","displayName","enrolTicketB64"}` — X25519 and
  Ed25519 public keys (32 bytes each), a name of at most 64 code points with no control, format or
  bidi characters, and a 32-byte ticket the clinician drew.
- **E2 (owner keys)**: `{"v":1,"boxPubB64","signPubB64"}` and nothing else.

An envelope is authenticated by opening: only someone holding the ISK, which means someone who typed
the code, can seal one that opens. No signature inside would add to that.

### 13.5 Endpoints

Owner routes carry the bearer token; clinician routes carry the invitation secret in the body. Every
route here answers 503 unless the operator set `DAYMARK_THERAPIST_AUTH=1`.

| Request | Body | Answers |
| --- | --- | --- |
| `POST /v1/invite` | `relRef`, `scope`, optional `email`, optional `ttlSeconds` | 201 `{inviteId, link, expiresAt}` |
| `POST /v1/relations/{relRef}/pairing` | `inviteId`, `sidB64`, `msgAB64` | 201 `{exchangeId}`; 404 no open invitation; 409 eight runs used |
| `GET /v1/relations/{relRef}/pairing/{exchangeId}` | — | `{exchangeId, state, msgBB64?, envB64?}` (E1 beside the reply) |
| `POST /v1/relations/{relRef}/pairing/{exchangeId}/approve` | `enrolTicketB64`, `envB64` (E2, required) | 204; 400 malformed; 410 unavailable; 409 the run changed, read it again |
| `POST /v1/relations/{relRef}/pairing/{exchangeId}/cancel` | — | 204; 410 nothing to cancel |
| `GET /v1/relations/{relRef}/invites` | — | the owner's invitation list (§8) |
| `POST /v1/invite/{inviteId}/pairing/fetch` | `secret` | 200 `{exchangeId, relRef, sidB64, msgAB64}`; 410 nothing open |
| `POST /v1/invite/{inviteId}/pairing/{exchangeId}/respond` | `secret`, `msgBB64`, `envB64` (E1) | 204; 410 |
| `POST /v1/invite/{inviteId}/pairing/{exchangeId}/status` | `secret` | `{"state":"WAITING"}`, `{"state":"APPROVED","scope":[…],"envB64":"…"}`, or a flat 410 for everything else |
| `POST /v1/invite/{inviteId}/report` | `secret` (or the owner's bearer token) | 204 (owner: 204 or 410) |

On the clinician's routes a wrong secret is 401 and a locked invitation is 429 without `Retry-After`;
a throttled connection is 429 **with** `Retry-After`, and the screen names the time. Failure answers
are flat on purpose, so no route tells a caller whether an invitation exists or how far a ceremony
has gone. E2 is served only on the status poll of a `CLOSED` run, never earlier.

### 13.6 Polling

The clinician polls status every 45 seconds (`PAIRING_STATUS_POLL_MS` on the web,
`PAIRING_STATUS_POLL_SECONDS` on the server), far below the per-run allowance, and treats any 429 as
"still waiting". The approval may come a day later; nothing gives up on its own.

## 14. The phone's half

Not built: #174. The protocol does not change; only the device running the owner's half does. Until
the phone can talk to the server at all (#168), no Companion screen may claim the phone will show or
approve anything.

- **Identity is derived, not generated.** `crypto_kdf_derive_from_key` over the owner's master with
  context `"dmsync01"`: subkey id 3 is the X25519 seed, id 4 the Ed25519 seed, 32 bytes each, then
  `crypto_box_seed_keypair` and `crypto_sign_seed_keypair`. Ids 1 and 2 belong to sync
  (SYNC_PROTOCOL.md §1). The reference is `companion/web/src/lib/owner/identity.ts`; `identity.test.ts`
  pins the master bytes 0x00…0x1F to fixed public keys so the phone can be tested against the same
  vector. Deriving is what makes the phone and the browser the same owner.
- **Same bytes.** The phone builds the §13 channel identifier (`CpaceCrypto.kt` has `lvCat` and no
  builder yet; the builder and a test against the same structure `relay.test.ts` checks come first),
  opens E1, and seals E2 at approval and not before, so "the owner said yes" and "the clinician learned
  the owner's keys" stay one event.
- **When a reply does not open (issue #112).** The phone may raise **one local notification per
  invitation**, reading *"A reply to your invitation needs a look."* It is visible on the lock screen,
  names nobody and counts nothing. It is raised from the device's own knowledge when the envelope fails
  to open, once per invitation (a second failed reply adds nothing). It burns nothing, sends nothing to
  the server, and must not change the polling cadence on either side: a device that polled faster after
  a failure would have told the server, in traffic, that the code was wrong.
