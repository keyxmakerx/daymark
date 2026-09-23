# Daymark Companion — the clinician's side

A clinician is a second person whom the owner of a journal lets read a curated part of it, and who can
send back guidance. This document describes that relationship as built: who the clinician is, what a
share is, what the clinician can and cannot see, game plans, and how either side ends it.

The security reference is [COMPANION_SECURITY.md](COMPANION_SECURITY.md); pairing is
[COMPANION_PAIRING.md](COMPANION_PAIRING.md); clinicians grouped into practices are
[COMPANION_ACCESS_CONTROL.md](COMPANION_ACCESS_CONTROL.md). Code still says "therapist" in names such
as `TherapistPortal.svelte`; prose says "clinician".

---

## 1. Who the clinician is (and is not)

The owner is the only root of trust. The server never vouches for anyone.

**The clinician is:**

- a recipient the owner pinned through the pairing code (COMPANION_SECURITY.md §5.6);
- a read-only viewer of exactly what the owner sealed to them, for a bounded time;
- the author of game plans and assignments that the owner must explicitly accept.

**The clinician is not:**

- an account the server vouches for — there is no identity provider, no help desk and no password
  reset;
- a writer into the owner's own data — what a clinician sends is kept apart, signed and immutable;
- a clinician operating inside a medical device. Daymark is not one, and game plans are not diagnoses,
  prescriptions or orders (§2).

## 2. The non-diagnostic rule

The project's first rule ([CLAUDE.md](../CLAUDE.md) §0) applies on the clinician's side without
softening.

| Rule | How it is kept |
|---|---|
| The clinician's screens carry the same "a self-check, not a diagnosis; scores are not clinical thresholds" framing as the app. | Fixed copy in the components, never supplied by the server; `components/invariants.tree.test.ts` asserts the disclaimers word for word. |
| Scores and bands are never shown as clinical cut-offs. | Shares carry check-in scores and bands only, never item responses. |
| The PHQ-9 self-harm item never leaves the owner. | It is never stored ([PRIVACY.md](../PRIVACY.md)), and the share bundle type has no slot for it. |
| Free-text game plans can contain diagnostic or medication language the schema cannot stop. | So the claim is "non-diagnostic by **framing**", not by construction: the authoring screen says a plan is guidance, not treatment — not a clinical treatment plan, prescription or diagnosis — and the owner decides whether to accept it (§7). |
| No risk scores, no verdicts, no automatic actions on the owner's side. | Receiving a plan triggers no network call and no scheduling. The owner accepts explicitly, and the app's usual crisis-resources posture applies. |

## 3. Roles and keys

Who holds what, and what each party never holds: [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §2
and §4.

## 4. Cryptographic contract

One primitive set: [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §4. Content keys are sealed to the
clinician with an X25519 sealed box, which gives **no forward secrecy** (R2): anyone who later obtains
the clinician's long-term private key can open every share ever sealed to it.

## 5. The share lifecycle

Everything travels on four channels of one relationship, under `/v1/rel/{relRef}/{channel}`. The
server enforces who may write each channel by role; everything it stores is opaque to it.

| Channel | Written by | Read by | Carries |
|---|---|---|---|
| `grants` | owner | clinician | the owner's permission policy — signed, not sealed |
| `shares` | owner | clinician | a curated share, sealed to the clinician and signed by the owner |
| `assignments` | clinician | owner | a proposed change to the owner's settings, signed and sealed to the owner |
| `gameplans` | clinician | owner | a game plan, signed and sealed to the owner (§7) |

A clinician's write to `assignments` or `gameplans` sends the owner a "something to review" email, if
the owner opted in to that kind of notice.

### 5.1 Invitation

- Only the owner mints an invitation (`POST /v1/invite`, bearer token): a random 256-bit invitation id
  and secret for one relationship, lasting 72 hours by default. The server stores the secret only as
  an Argon2id hash.
- The link, `{base}/portal/invite#id=…&s=…`, carries both values in the fragment, which browsers never
  send to a server — so they reach no request line, proxy log or `Referer`.
- The owner console shows the link for the owner to pass on out of band. If SMTP is configured and the
  owner gives an address, the server also emails the same link. Email is a delivery convenience, not
  an authentication channel: the link lets someone open a pairing conversation, never finish one,
  because approval needs the pairing code.
- **A wrong guess never burns an invitation.** Wrong secrets meet capped backoff; a rule that burned an
  invitation after N failures would let anyone holding the link lock the real clinician out. Only a
  human report ("I didn't expect this") burns one. The owner's console can make that report; the
  clinician's acceptance page cannot yet (#213).
- The relationship's inbox token is not in the invitation. The owner gives it out of band
  (COMPANION_SECURITY.md §4).

### 5.2 Enrolment and mutual pairing

1. The clinician opens the link; `/portal/invite` redirects to `/therapist`, and the fragment rides
   along.
2. The owner's screen shows a short pairing code, said aloud once; the clinician types it.
3. The clinician's browser then generates an X25519 and an Ed25519 key pair, wraps the private halves
   under a reading passphrase — the wrapped record stays in that browser; the server never holds it —
   and sends its public keys sealed under a key only the code produces
   ([COMPANION_PAIRING.md](COMPANION_PAIRING.md)).
4. The owner pins the clinician's keys and approves. The approval carries the owner's own keys, sealed
   back, and makes live the enrolment ticket the clinician's browser chose.
5. The clinician enrols a six-digit sign-in code (TOTP) with that ticket. From then on they sign in
   with the code, then unlock their keys with the reading passphrase.

Keys are pinned in both directions before anything is shared, and nothing is ever sealed to an unpinned
key: the share path checks the pin store (`lib/share/pairing.ts`) and refuses. At sign-in the portal
compares the owner keys the server publishes with the ones pairing pinned, and refuses on any
difference (COMPANION_SECURITY.md §4). No screen accepts a typed owner key.

### 5.3 The owner shares a curated subset

The owner console builds a self-contained bundle — a materialised copy of the chosen records, not a
query into the archive — so minimisation is enforced by what is sealed, not by the viewer. The owner
chooses the record types (check-in scores and bands, moods, journal entries, sleep) and whether to
strip notes. Choosing a date range, leaving out single records and previewing the whole bundle are not
built (#225).

Each share version gets a fresh content key. The bundle is encrypted with XChaCha20-Poly1305, the
content key is sealed to the clinician's pinned X25519 key, and the owner signs the transcript
`context|shareId|version|recipientFp|expiry|ownerSigningFp`, which is also the associated data
(`lib/share/sharecrypto.ts`). The expiry defaults to 30 days and can be set from 1 to 365 in the
console; the server refuses a missing or past expiry and clamps anything beyond 366 days. How long
shared data should live, and whether expired bytes are deleted, is open (#228).

A share can be refreshed as the owner records more: each refresh is a new version (append-only), and
the clinician always reads the newest. Its scope changes only by the owner's action, and nothing
renews itself.

### 5.4 The clinician reads

The portal fetches `/v1/rel/{relRef}/shares/{lineage}/current` with the session cookie and the inbox
token. The server checks that the session belongs to this relationship and that the share has neither
expired nor been withdrawn; if it has, the answer is **410 Gone** — expiry and withdrawal deliberately
give the same answer. In the browser, the portal unseals the content key, checks the owner's
signature against the pinned owner key, and only then decrypts; any failure refuses to render.

Responses are `Cache-Control: no-store`, and decrypted content lives in memory only, wiped on logout
and when idle. What cannot be revoked is therefore at most one session's worth of what was shared.

## 6. What the clinician can and cannot see

| The clinician can see | The clinician cannot see |
|---|---|
| Exactly the records the owner put into the share, after redaction | Anything else: other types, stripped notes, the rest of the archive |
| Check-in scores and bands | Item responses, including the self-harm item, which no share can carry |
| The share's provenance: id, scope, the owner's fingerprint, when it was made | The owner's passphrase or private keys |
| Game plans and assignments they wrote themselves | Any other patient's data; anything published after they were re-keyed out |

The server sees ciphertext, public keys and routing metadata, never a key, plaintext or which records a
clinician looked at ([COMPANION_SECURITY.md](COMPANION_SECURITY.md) §2, §3 T1). The metadata still
matters: the existence and rhythm of a relationship leak to whoever runs the server, and sizes are not
padded (#214).

## 7. Game plans — the clinician writes back

A game plan is guidance from the clinician — goals, exercises, tasks between sessions, notes, a review
cadence — that flows one way, to the owner, as a signed, sealed, append-only object.

**As built.** The clinician writes a plan in the portal (`GamePlanAuthor.svelte`, which carries the
fixed "guidance, not treatment" disclaimer). The portal signs the canonical JSON with the clinician's
Ed25519 key — the payload names its context (`daymark.gameplan.v1`), its recipient's fingerprint, its
lineage, version and the version it supersedes — then seals it to the owner's pinned X25519 key and
publishes it to the `gameplans` channel (`lib/therapist/gamePlan.ts`). An update is a new signed
version; withdrawal is a signed tombstone. The server cannot withdraw a plan, or un-withdraw one,
because it cannot sign.

**Not built: the owner's side** — opening a plan, checking it, showing it as a proposal, and accepting
or declining it — in the web console (#231) or on the phone (#138). The check itself exists as
`openGamePlan`, with no production caller. When it is built, these rules hold:

- **The proposal gate is mandatory.** Nothing takes effect until the owner accepts; it is both the
  integrity boundary and the consent boundary.
- A plan is accepted only if it opens with the owner's key, verifies against the **pinned** clinician
  key, names this owner and the right context, and is newer than any accepted version of its lineage.
  A valid signature from an unpinned key is hostile.
- **A plan never lands in the owner's own tables** — on the phone it gets its own table, never
  `treatments`, the owner's non-evaluative sleep markers (R10). It can create a goal only on the
  owner's explicit say-so.
- The signed payload and signature are kept verbatim, so a plan stays re-verifiable without the server.

## 8. Authentication and sessions

See [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §5. In short: the six-digit code is the only
sign-in; passkeys are not built (#205); the code's seed is stored on the server **in the clear**, not
hashed, because a verifier must recompute codes; the code never unlocks the reading key; and sessions
last 15 minutes idle and 8 hours at most.

## 9. Revocation, expiry and re-keying

You cannot un-share what someone has already read. What the system does is keep the window small,
make future access revocable against an honest server, and keep what remains minimal.

| Mechanism | What it does | Against a colluding server? |
|---|---|---|
| Expiry | After the deadline the server answers 410 | No — an honest server only |
| Withdrawal ("Revoke" in the owner console) | The server marks the share withdrawn, deletes its bytes, answers 410 and records `share.revoke` | No — an honest server only |
| A new content key per version | Protects later versions only from someone holding an old content key | Not against the clinician's own key |
| Re-pairing to new keys | Future shares are sealed to the new key only | **Yes — the only real revocation** (R3) |

The three honest guarantees: future fetches stop on an honest server; data published after re-keying
is unreadable to the old key; and plaintext already decrypted is never recallable, as with a printed
page. Whether these stated limits are the permanent position: #222. A clinician who loses their key
needs a fresh invitation and pairing; there is no escrow.

## 9a. The clinician's own exit, and the case it does not cover

Everything in §9 is the owner's direction. The clinician has one of their own.

### What "Leave this relationship" does

At the foot of the portal's **Allowed** tab. Confirming it does exactly three things, in order:

1. **The server records an ending** — one insert-only row keyed on the relationship — and closes the
   clinician's sign-in for it: a correct code is answered `410 Gone` from then on, and every live
   session the credential holds is cut.
2. **The browser forgets its key record** for the relationship, and re-reads to check the write took.
   A browser that refuses is told so rather than reassured.
3. **The session is signed out.**

`lib/therapist/leave.ts` is those three operations and nothing else, listed in its header so that
"this cannot destroy anything belonging to the owner" can be checked rather than trusted. The route is
`POST /v1/relations/{relRef}/ending`, in `routes/RelationshipEndingRoutes.kt`. The server side is
[COMPANION_SECURITY.md](COMPANION_SECURITY.md) §9a.

### What it deliberately does not do

- **It un-reads nothing.** What the clinician decrypted is on their machine.
- **It touches nothing of the owner's.** No share is withdrawn, no grant changed, no blob removed. If
  what was shared should stop being delivered, the owner's Revoke does that, and it is theirs to press.
- **It deletes no credential.** The closure is a row in a separate insert-only table; `totp` is never
  changed.
- **It does not reach copies.** The acceptance screen offers the key record as text to keep, on
  purpose, and the confirmation says so.

The owner's standing sentence, *"Revoking does not un-send what was already read"*, is **not** used on
this surface: it is about a reader who already holds something, and from the clinician's side nothing
is being un-sent. The screen says the honest mirror instead; the exemption is written and checked
beside the rule in `components/owner/revokeCaveat.test.ts`.

### How the owner finds out

Three places, none of them a message:

| Where | What it says |
|---|---|
| The relationship's access log | *Your therapist · Ended their access to what you share* |
| The sharing strip on every owner screen | *…ended their access on {date}. Nothing you send now would be read.* — replacing the "Sharing real entries with…" line |
| The next attempt to share with them | Refused before anything is sealed, naming the date and the two things the owner can do: withdraw what is still published, or invite them again |

There is no email and no notification: a message saying a therapy connection ended, arriving at an
inbox that may be shared or watched, is a safety trade-off about real people (#216).

### The case this does not cover: a clinician who is dismissed

A dismissed clinician does not choose to leave, so none of the above happens.

- A practice administrator removing a member deletes the membership and cuts the member's sessions —
  a sign-out, not a revocation: the person can sign straight back in to every relationship they hold.
- The practice cannot end the relationship: it is between the clinician and a **patient**, created by
  the patient's invitation, and the sign-in store holds no practice id at all.
- The practice cannot warn the patients affected, because there is deliberately no list of a
  practice's patients — it would be a register of who is in therapy where. The decision is right, and
  its cost lands here.
- A practice invoking the leave route would be a practice reaching into a patient's relationship,
  which the access-control model refuses. It is not built and should not be.

So the protection is the **patient's own Revoke**, which stops future delivery only; the sharing strip
shows who has standing access, with Revoke beside it, on every owner screen. The practice console's
remove-member confirmation says removal ends a standing in the practice and nobody's relationship.

## 10. The audit log

See [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §9: events, never content; owner-readable only;
hash-chained by the server, so tampering is detectable and withholding is not; signed clinician
attestations are not built (#217).

## 11. Honest limits

See [COMPANION_SECURITY.md](COMPANION_SECURITY.md) §11. The ones a clinician meets first: the portal
is a lower-assurance path, because the server serves the code that unwraps their keys; revocation
binds an honest server only; and the sign-in code is a phishable secret the server holds in the clear.
