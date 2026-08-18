# Companion — the next slice

Written 2026-08-16, from a working session with the maintainer after the first real deployment.
Everything factual here was checked against the tree; where something is a proposal rather than a
fact, it says so.

---

## 0. The one thing to read first

**There are two products sharing one server, and they have different threat models.** Almost every
confusing decision in the current UI comes from a screen trying to serve both at once.

| | **A — the personal system** | **B — the clinician channel** |
|---|---|---|
| Who | One person, their own data | An owner and a clinician they invited |
| Wants | Encrypted backup and a desktop view of their own records | To share a bounded slice with a professional |
| Threat | **The server itself.** "Even if the server is compromised, my info is secure" | A hostile server, and a clinician seeing more than was shared |
| Status | Crypto is built; the setup experience is not | **Server and owner half built; the therapist's own path does not exist — see §3.5.1** |

Goal A is the one that is under-served today, and it is the one the maintainer arrived for.

---

## 1. What actually exists (verified, not remembered)

### 1.1 The sync payload is genuinely end-to-end encrypted

`companion/web/src/lib/sync/crypto.ts`:

```
passphrase ──Argon2id(salt, mem≥256MiB, ops≥3)──▶ master(32)
master ──crypto_kdf(ctx="dmsync01")──┬─ SYNC_KEY (XChaCha20-Poly1305)
                                     └─ manifest seed
snapshot = MAGIC("DMS1") | FMT(1) | nonce(24) | XChaCha20Poly1305(plaintext, AAD, nonce, SYNC_KEY)
```

The key is derived **client-side from a passphrase the server never sees**. A server operator, or
someone who has stolen the disk, holds ciphertext. Goal A's core promise is real.

### 1.2 What a compromised server can still do — state this plainly, never imply otherwise

1. **See metadata, which the encryption does not cover.** Snapshot sizes, timing, source IPs, how
   often someone syncs, how many relationships exist. The payload is ciphertext; the *shape* of it
   is not, and none of this requires decrypting anything. "They logged nothing for three weeks" is
   inferable from timing alone, and "journalled daily for eight months and then stopped for nine
   days" is a clinically meaningful inference drawn without touching a single ciphertext. On this
   product that is sensitive.
   - **§3.8's heartbeat adds a second signal, and a more regular one** — when a client is awake and
     when a clinician is active, on a fixed interval rather than following anyone's own writing. It
     is listed here rather than arriving unannounced, which is what §3.8.3 asks for.
   - The known mitigations — pad blobs to size buckets, jitter upload timing, decouple writes from
     capture — are out of scope for this slice. **The requirement is disclosure, not
     implementation** (§3.9.5): a stated limit, rather than something discovered later by someone
     who trusted the words "end-to-end" to mean more than they do.
2. **Deny service.** Refuse writes, truncate the audit chain, serve a stale snapshot. The admin
   console's chain check states internal consistency and explicitly *not* completeness for exactly
   this reason.
3. **Serve malicious JavaScript.** This is the sharp one and it is not currently written down
   anywhere: **the web viewer is served BY the server it is protecting you from.** A compromised
   server can ship a modified `index.html` that keeps the passphrase. Browser-delivered E2EE is
   only as strong as the delivery of the code.
   - The **phone app does not have this weakness** — it is installed, not served.
   - Therefore: **the phone is the trustworthy client; the web viewer is a convenience.** Goal A's
     copy must not imply the browser is as safe as the app, and any "how this is secure" self-check
     must say this rather than omit it.

### 1.3 There are no accounts

Verified in `TherapistAuthRoutes.kt` / `RecoveryRoutes.kt`: the owner authenticates by presenting
the **shared bearer token** from `DAYMARK_AUTH_TOKEN` — the same secret that gates sync — via
`ownerAuthorized(ownerGuard)`. There is no owner record, no admin record, no login.

The **only** credential in the system is the therapist's (`credentialId` + TOTP + wrapped keys),
minted by an owner invite. The maintainer's reaction was correct and worth quoting:

> "shouldn't the initial account be the admin? It sounds like you're getting a therapist setup
> first."

It does, because that is the only setup flow that exists. `admin.html` additionally holds **no
credential at all** — it mounts with none (`src/admin.ts`), and the plan's "separate route,
separate credential" was never built.

### 1.4 Configuration problems are detected but not surfaced

Already implemented: startup WARN for unset `DAYMARK_AUTH_TOKEN` (sync API fail-closed) and unset
`DAYMARK_TRUSTED_PROXIES`; `/readyz` catches an unwritable `/data`. All of it visible **only in
container logs** — which is how a real deployment ran broken without the UI saying a word.

PR #72 adds the first UI for this, bounded to what is safe to show a stranger (§4.2).

### 1.5 QR and pairing pieces that already exist

- `app/.../export/QrEncoder.kt` — a real QR encoder (Nayuki, MIT, no network), currently used only
  to draw codes into PDFs. Reusable.
- `companion/web/src/lib/share/pairing.ts` — mutual out-of-band SAS: a 4–6 word BLAKE2b phrase
  derived order-independently from both parties' keys, so both screens render the identical words
  to compare. Built for owner↔therapist; **the pattern is exactly the "app confirms this is
  correct" step the maintainer asked for.**
- `DAYMARK_DOMAIN` + `DAYMARK_PUBLIC_SCHEME` already exist in config, and `publicBaseUrl` is
  already read by `Config.kt`. The server therefore already knows whether it has a public HTTPS
  origin — which §3 depends on.

---

## 2. The maintainer's feedback, verbatim, so it does not get paraphrased away

- "what is the point of the initial? i thought we were gonna have like a crypto backup, where the
  app can sync personally with the user?"
- "There is also still SOOO much text it's just so hard to get it all"
- "if what your goal here was to explain how the site is secure, then more of like a self check,
  that you can expand or hover over for details on what each check stands for, but this should be
  like tucked away not front and center"
- "i don't know what some of these fields even are, maybe you need example text (the unselectable
  kind) in the fields, and hover over i icons next to the titles of each field... I'm a server
  admin and even i have no context lol"
- "a sync but very secure option, maybe even like QR code level of easy (with some sort of 2
  factor, like the app confirming if this is correct and implications) but also things like
  expanded views and printing from the desktop their pdf info"
- "i don't expect full functionality parity but like, just things that makes sense for the user to
  use on the computer"

---

## 3. QR pairing — the design, and the transport problem

The maintainer's own question: *"idk how that would work without a reverse proxy, so that maybe a
requirement and only if they have it in the env variable?"* That instinct is right. Here is why,
and what to do.

### 3.1 The problem is not the QR, it is what the QR makes easy

A QR must encode an address the phone can reach. Three deployments:

| Deployment | Address | Transport | Verdict |
|---|---|---|---|
| LAN, no proxy | `http://192.168.1.50:8080` | **plaintext HTTP** | token readable by anything on the wire |
| Reverse proxy + TLS | `https://daymark.example.com` | TLS | safe |
| Tailscale / VPN | `http://100.x.y.z:8080` | encrypted at network layer | safe in practice |

`docker-compose.yml` already says this about the LAN case: *"the app speaks plain HTTP and
authenticates with a bearer token, so anything that can read the wire can read the token."*

**So the danger of a QR is that it makes the least safe configuration the most convenient one.**
Today pairing is tedious enough that someone thinks about it. A QR removes that friction without
removing the exposure.

### 3.2 Do not put the long-lived token in the QR

If the QR carries `DAYMARK_AUTH_TOKEN`, then a photograph of the screen — a shoulder-surfer, a
screenshare, a backup of the desktop's screenshots folder — is **permanent** access to every
future snapshot.

Proposed instead:

1. Server mints a **short-lived, single-use pairing code** (suggest ≤120s, one redemption).
2. QR carries `{ baseUrl, pairingCode }` — never the token.
3. Phone redeems the code over the transport and receives the real token.
4. **Both screens display the same SAS words** (reuse `pairing.ts`), derived from the exchange.
   The person confirms they match before the phone stores anything.
5. Server discards the code on first redemption or expiry, whichever is first.

Step 4 is the maintainer's "app confirming if this is correct" and it is what makes a
machine-in-the-middle visible rather than silent.

### 3.3 Gating recommendation

Offer the QR **only when the server knows it has a safe origin** — i.e. `publicBaseUrl` resolves to
`https://…`, which `Config.kt` already computes from `DAYMARK_DOMAIN` + `DAYMARK_PUBLIC_SCHEME`.

For LAN-only deployments, which are legitimate and probably common:

- Do **not** silently refuse; explain. "This server has no HTTPS address configured, so a code
  scanned here would send your access token over the local network in the clear."
- Allow an explicit opt-in — proposed `DAYMARK_ALLOW_INSECURE_PAIRING=1` — so it is a decision
  someone made, not a default they inherited.
- Even then, prefer §3.2's short-lived code: on a LAN the exposure window becomes ~2 minutes rather
  than forever.

### 3.4 Resolved: LAN pairing is supported, and made safe rather than warned about

The maintainer's decision, and the reasoning that follows from it:

> "a user may only have it setup at LAN which would work, but we should notify them of that fact...
> But how could we make this more secure? i want this to be easy, most users do not understand and
> i don't want them to have to learn networking and system administration to be able to use this
> app"

So: **LAN is a first-class mode, not a degraded one, and the fix is technical rather than
educational.** Making the user learn nginx is not an acceptable answer.

#### What is actually exposed on a plaintext LAN — state it precisely

Because the payload is already end-to-end encrypted (§1.1), a passive eavesdropper on the wire does
**not** obtain anyone's journal. What they obtain is:

| | Exposed on plaintext LAN? |
|---|---|
| Journal / entry content | **No** — ciphertext, key never leaves the client |
| The bearer token | **Yes**, and it is reusable forever |
| Metadata (sizes, timing, lineage ids) | Yes |

With the token, an attacker can read and write **ciphertext they still cannot decrypt** — so this
is an integrity and availability problem plus metadata leakage, not a confidentiality breach of
content. That is a much narrower hole than "your data is exposed", and naming it correctly is what
makes the fix obvious: **the problem is the reusable secret on the wire, not the plaintext
transport.**

#### The fix: sign requests instead of presenting a token

Pairing (§3.2) establishes a **shared key** rather than handing over a bearer token. Every request
then carries a signature over `method | path | BLAKE2b(body) | timestamp | nonce`, keyed with that
shared secret.

- A passive observer sees signatures. Nothing reusable — they cannot mint a request for a path they
  did not observe.
- Replay is refused: the server rejects a stale timestamp (suggest ±5 min) or a nonce it has seen.
- **The user does nothing.** No certificate, no port, no DNS, no trust decision.
- **No new dependency.** The server already does HMAC for TOTP (`auth/Totp.kt`) and carries
  BouncyCastle; the phone already has lazysodium.
- Additive: the server can accept both a bearer token and a signature during migration, preferring
  the signature, so the existing phone build is not broken by the change.

This is the AWS SigV4 model and it is the single highest-value change for goal A, because it makes
the ordinary home deployment safe **by default** instead of safe **if configured**.

#### Optional second layer: encrypt the envelope

Signing protects authenticity, not metadata. Wrapping request and response bodies in a session key
(libsodium `crypto_kx` → XChaCha20-Poly1305, both already present on both sides, and consistent
with §5.1's "one primitive set everywhere") also hides sizes and shapes from the wire. Worth doing
after signing, not instead of it.

#### Remote access without sysadmin: recommend a mesh VPN, not a reverse proxy

For someone who wants sync away from home, the documented recommendation should be **Tailscale (or
plain WireGuard)**, not nginx:

- no port forwarding, no firewall rules, no domain, no DNS, no certificates, no renewals
- the server gets a stable address that works from anywhere, encrypted at the network layer
- installable by a non-technical person in minutes

A reverse proxy with TLS stays documented for those who already run one, but it should stop being
presented as *the* way to leave the LAN. Requiring certbot to back up a diary is the wrong bar.

#### What the UI must say

LAN-only is **not** an error state and must not be styled as one:

> "This syncs while you're on your home wifi. Away from home it will catch up when you get back."

And, separately, so nobody assumes remote access works when it does not: pairing on a LAN address
must say plainly that the address only resolves at home, with a pointer to the mesh-VPN option for
people who want more. The failure to avoid is someone leaving the house believing sync is running.

**Still open:** whether to keep the QR gated on a configured HTTPS origin once request signing
exists. With signing, the original reason for the gate largely dissolves — the wire no longer
carries anything reusable — so the gate may become unnecessary rather than merely relaxed.

---

## 3.5 The therapist connection is not built, and the boot screen is still wrong

Added 2026-08-17 after the maintainer tried to use it. Two findings, one of which invalidates a
claim made earlier in this very document.

### 3.5.1 CORRECTION: the therapist channel is NOT "designed and largely built"

§0 says that. It is wrong, and I wrote it without tracing the flow end to end. The server and the
owner half are built; **the therapist's own path does not exist.** Three breaks, verified:

1. `buildInviteLink` (`TherapistAuthRoutes.kt:278`) points at `{base}/portal/invite#id=…&s=…`.
   **Nothing routes `/portal/invite`.** The only occurrence of that path in the repository is the
   line that constructs it. The link a therapist receives is a 404.
2. `PortalClient.redeemInvite()` and `enrollTotp()` exist in `therapist/session.ts`, fully written.
   **No component calls either.** There is no UI anywhere that redeems an invite.
3. Nothing generates the therapist's keypairs or produces the wrapped reading key.

So `LoginGate` asks for **nine values that no flow in the product produces**. They can only be
assembled by hand, from the database and the source, by someone who wrote it. The maintainer's
question — *"where am I supposed to get that data about inbox token relationship id and such???"* —
has no answer today.

**Consequence for §0's table: goal B is not "largely built". It is unusable.**

### 3.5.2 The nine-field form should not be improved. It should cease to exist.

The maintainer, having been shown the form with the field help added:

> "having all that text is WILD ... this isn't it chief i'm sorry"

Correct. Adding explanations to nine cryptographic fields makes a wrong screen more legible; it does
not make it right. **A therapist should type one short code, or scan one QR, and choose a
passphrase. Nothing else.** Every other value is exchanged or derived.

Target shape:

| Today | Proposed |
|---|---|
| 9 fields, 7 of them base64/JSON | 1 code (scanned or typed) + 1 passphrase they choose |
| Values obtained: nowhere | Values obtained: from redeeming the invite |

### 3.5.3 The connection flow

**Owner, in the Companion:** "Connect a therapist" → the server mints a single-use, short-lived
invite → the screen shows a **QR code and the same code in short typed form**. Both encode the
pairing code, never a long-lived secret (§3.2).

**Therapist:** scans, or opens the link, or types the code at a known URL — three ways in, one flow
behind them. The page redeems the invite, generates their keypairs in the browser, asks them to
choose a reading passphrase, and enrols their authenticator. Nothing is pasted.

**Both, before anything is trusted:** the two screens display the **same short word phrase** and
each side confirms it matches. `share/pairing.ts` already computes exactly this — an
order-independent BLAKE2b SAS over both parties' four public keys — for owner↔therapist. It is
built and unused by any screen. This is the "required confirmation" the maintainer asked for, and
it is what makes a machine-in-the-middle visible rather than silent.

**The manual fallback is the same flow, not a different one.** No camera → type the short code. The
answer to "what would a manual one look like" is: identical, minus the scan. It is emphatically not
a form of base64 fields.

### 3.5.4 A connections surface, on both the Companion and the phone

The maintainer asked for "a page in the app that reflects that connection and security (with the
ability to disconnect)".

**On the phone this is entirely new.** Verified: the only occurrence of the word "therapist" in the
whole Android app is the label `"Export PDF for therapist"` on the settings screen. The app has no
model of a relationship, no knowledge that a clinician exists, and no way to see or end one. That
is a bigger piece of work than it sounds and needs the app to read the relationship API.

What the surface must show, on either platform:

- who is connected, and since when
- their key fingerprint, so it can be compared out of band
- exactly what they can see — the capabilities, in plain words, not a scope string
- when they last opened anything
- **disconnect**

**Disconnect has to be honest about what it does, and this is the sharp part.** The existing note
in `GrantManager.svelte` is already correct and must survive into any new UI: revoking stops
*future* delivery; it does not claw back what has already been delivered. There are four distinct
actions and they should not be collapsed into one button labelled "Disconnect":

| Action | Effect | Reversible |
|---|---|---|
| Turn a capability off | They stop being able to do that thing | yes |
| Revoke a share lineage | Future fetches of that share refuse (`RelationStore.revokeLineage`, built) | no |
| Remove the credential | They cannot sign in at all | no — needs a fresh invite |
| Re-key | A true cutoff going forward | no |

None of these un-sends what a clinician has already read. A UI implying otherwise would be lying
about something that matters, and the copy must say so at the point of the click, not in a footnote.

**This table answers "what can be switched off". It does not answer "who is allowed to end this",
which is the harder question — see §3.6.1, which supersedes this table for the *who*.**

### 3.5.5 The boot screen: two doors, and about 26 words

> "the like two options at boot (which still has so much text it's wild, we need to remove like 80%
> of it i think...) 1 is for personal use, syncing, etc, and the 2 is for setting up a therapists
> portal. Maybe we could have a warning that this isn't for an everyday user and would not provide
> a user with any benefits"

Measured: 130 words visible today, after the cut from ~398. An 80% reduction is **~26 words**.

That is achievable only by removing a *door*, not by shortening sentences. The insight that allows
it: **a therapist never arrives at this screen.** They arrive by invite link, and after enrolling
they have a bookmark. Three audiences was one too many; the third was never going to read it.

Proposed:

- **Door 1 — "Use Daymark myself."** Backup, sync, self-checks, tools. The default, visually
  dominant.
- **Door 2 — "Connect a therapist."** Marked plainly as *not something most people need, and of no
  benefit on its own* — that warning is the maintainer's, and it is right: it costs nothing to
  ignore and prevents someone wandering into a clinical-sharing setup they have no use for.
- A small text link for a clinician already enrolled and returning. A link, not a door.

Everything else — what runs where, the security explanation, the probe readings — stays behind the
disclosures built in `fb23023`, or moves off this screen entirely.

### 3.5.6 Sequencing consequence

§4 is reordered by this. The invite-acceptance page is now the **first** piece of product work,
because until it exists the therapist half of the product cannot be used at all, and every hour
spent on the sign-in form's legibility is spent on a screen that should mostly disappear.

Request signing (§3.4) still precedes QR pairing, for the reason given there: pairing should
establish a signing key rather than hand over a token.

---

## 3.6 Ending a connection, and making the pairing genuinely one-time

The maintainer, after reading §3.5:

> "So what would be the correct way to handle that disconnect? like it should be doable from their
> side, say a patient doesn't show up or something? but also a person doesn't want to work with a
> therapist anymore? but at the same time that pairing shouldn't be reusable, this should be
> incredibly as secure as possible, even if a bit annoying to do so, so it like is a one time thing.
> We don't want a man in the middle attempt to even be possible basically. Also also, we definitly
> need the android app to be aware of all this.... that's scary we don't today"

Four questions. §3.5.4 gave a four-row table for disconnect and it is not wrong, but it answered
"what can be turned off" rather than "who is allowed to end this, and what happens to the other
person". Those are different questions and the second is the one asked.

### 3.6.1 Two verbs, not one, and they are deliberately asymmetric

**Leave** — either party ends their *own* participation. **Revoke** — the owner ends the *other
party's* access. Collapsing these into a single "Disconnect" is the mistake, because the therapist
must be able to do the first and must never be able to do the second.

The asymmetry is not politeness, it is a threat model. A therapist credential can be stolen. If
"disconnect" from the therapist's side deleted the owner's shares, then stealing a therapist
credential would buy an attacker a **data-destruction primitive** over someone else's records. It
must not. `RelationRoutes.kt:133` already reasons through exactly this shape for revoke-on-write
and reaches the same conclusion; this is the same rule applied to the connection itself.

| | Leave (therapist) | Leave (owner) | Revoke (owner) |
|---|---|---|---|
| Deletes the therapist credential | yes | — | yes |
| Kills live sessions immediately | yes | — | yes — **now**, not at next expiry |
| Destroys the therapist's local key material | yes (their browser) | — | cannot reach it |
| Touches the owner's blobs or lineages | **never** | owner's explicit choice | yes |
| Other side is told | yes — `OwnerNotifier` | yes | no channel; they learn at next request |

Two things the UI must not misrepresent.

**Revoking does not un-send what was already read.** The note already in `GrantManager.svelte` is
correct and must survive verbatim into any new surface. It belongs at the point of the click, not
in a footnote.

**A revoke is not a message.** The therapist has no push channel — email is optional and
best-effort. So the owner's screen must say *they will find out the next time they open it*, and
must not imply the other person has been notified. Overstating this is the kind of quiet false
promise §1.2 exists to prevent.

### 3.6.2 There is no "reconnect", and that is the point

Reconnecting after a disconnect is **a fresh invite and a fresh SAS ceremony from the top**. There
is no button that resumes a prior relationship.

This follows directly from "the pairing shouldn't be reusable". A reconnect button is a reusable
pairing wearing a different hat: it means some artefact survived the disconnect and can be replayed
to re-establish trust. If nothing survives, there is nothing to replay, and "one-time" is a property
of the system rather than a claim about it.

The cost is real and should be stated in the UI: **disconnecting is not reversible, and re-pairing
requires both people again.** That is the annoyance the maintainer explicitly budgeted for.

### 3.6.3 Single-use: what is already true, and the one gap that matters

Verified in the source rather than assumed — this half is genuinely built:

- `AuthStore.redeemInvite` returns `GONE` for any `status != "PENDING"` and sets `REDEEMING` on
  success, so a second redeem of the same secret cannot succeed.
- `AuthStore.enrollTotp` deletes the enrolment ticket, is **insert-only** on
  `credential_id OR rel_ref` (a second enrolment against the same relationship is refused with
  `ALREADY_ENROLLED`), and drives the invite to `CONSUMED` in the same synchronized block.
- The enrolment ticket lives `ENROLL_TICKET_TTL_MS` = 10 minutes.
- Wrong secrets never consume the invite; they take capped backoff.

Three gaps remain, and only the first is about cryptography.

**(a) Single-*redeem* is not single-*party*.** Whoever presents the secret first wins. Nothing binds
the invite to the intended human. Anyone who sees the link — a mail relay, a screenshot in a chat, a
shoulder — can redeem it, enrol their own authenticator, and *become* the therapist. The genuine
therapist then gets `GONE`. Today that confused phone call is the only detector. §3.6.4 is what
closes this.

**(b) `REDEEMING` is a silent dead end.** Redeem the invite and close the tab, or let the 10-minute
ticket expire, and the invite is stuck at `REDEEMING` forever: not `PENDING`, so it can never be
used; not `CONSUMED`, so nothing records why. No screen anywhere shows invite state. The owner has
no way to see it and no way to answer "the link says it's gone" — the only remedy is minting
another, which nobody knows to do.

Needed: invite state visible in the owner console as **waiting / in progress / finished / dead**,
plus an explicit owner **Cancel** available at any moment before confirmation. An invite that can
only be killed by waiting out its TTL is a window an attacker is free to sit in.

**(c) Refusing the confirmation must burn the invite.** *(**WRONG — corrected in §3.9.1.** As
written this is a denial-of-service primitive: with a PAKE, a wrong code and an attacker's guess are
indistinguishable by construction, so burn-on-failure lets anyone holding the link destroy every
invite the owner mints. Only an explicit human report may burn. Kept here, struck, because the
reasoning that produced it is the reasoning to watch for.)* If the two sides read different words, that
is not a UX hiccup — it *is* the attack, live. The invite must be marked dead, the owner told
loudly, and a fresh one required. Anything softer means the attacker simply retries until someone
clicks through.

### 3.6.4 Machine-in-the-middle: the mechanism exists; the part that gets missed is *which screen*

> **SUPERSEDED IN PART BY §3.7.** This section's analysis of *why* two matching browser screens
> prove nothing is still correct and still load-bearing. Its prescription — compare the phrase
> aloud, human to human — is **not** the design any more: it requires a synchronous call, which
> fails the "even if they are not in the office" case, and it leans on a human comparing carefully.
> §3.7 replaces the ritual with a PAKE, which gets the same property structurally. Read this for the
> threat model; read §3.7 for the mechanism.

`share/pairing.ts` computes an order-independent BLAKE2b SAS over both parties' four public keys,
and `PinStore.assertPinned` refuses unpinned peers. That machinery is correct and complete. Wiring
it to a screen is necessary but not sufficient — two further conditions decide whether it actually
prevents anything.

**Condition 1: the comparison must not travel over the server.**

This is the one that is easy to get wrong while looking right. §1.2 already concedes that *the web
viewer is served by the server it is protecting you from*. So if the owner's browser and the
therapist's browser each render the phrase, a compromised server computed **both** — it can sit in
the middle, hold a separate session with each party, and render each of them a phrase that matches
the other. Two matching screens prove nothing when one machine drew them both.

The words therefore have to be compared **human to human, out of band**: spoken on a call, or read
out in the room. The copy must say *"read these words aloud to each other"* and must never say
*"check that they match on screen"*. Those two sentences describe the same gesture and only one of
them is a security control.

**Condition 2: the owner's confirming device should be the phone, not the browser.**

The Companion page is delivered by the server on every load, so a compromised server can change what
it renders — including the phrase. The Android app is installed from an APK, already holds the
passphrase, and derives its keys locally; the server cannot change its code. A SAS rendered by the
phone cannot be forged by a compromised server. A SAS rendered by the browser can.

This is the strongest available answer to "we don't want a man in the middle attempt to even be
possible", and its cost should be stated plainly rather than buried: **pairing then requires the
phone in hand, so it cannot be completed from a desktop alone.** That is precisely the "even if a
bit annoying" the maintainer authorised, and it is where that budget is best spent.

It also converts §3.6.5 from a nice-to-have into a dependency: the app is not *shown* the
connection afterwards, it is the thing that **approves** it.

**Audit consequence.** `AuditAction` has eleven kinds and none of them covers this. Pairing needs
its own, at minimum `PAIR_CONFIRMED`, `PAIR_REFUSED` and `CONNECTION_ENDED` (carrying which side
ended it). A refused pairing that leaves no trace is an attack that leaves no trace.

### 3.6.5 The Android app: worse than "not aware", and it reorders the work

§3.5.4 said the phone has no relationship model. On tracing it, that understates it. Verified:

- There is **no `sync` package under `app/src/main/`** at all.
- The entire `sync` flavour source tree is one 19-line file, `SyncCryptoFactory.kt`, and **nothing
  references it.** Its own comment says so: *"Nothing calls this yet (no networking/UI has
  landed)."*
- `BackupManager.kt` is local JSON/CSV export and import. It opens no socket.

So it is not that the app is unaware of therapists. **The app does not know the Companion server
exists.** The `:sync-crypto` module is built and genuinely JVM-tested, and that is the whole of the
phone side.

Two consequences.

**A connections screen is not a screen — it is three layers, and they have an order:**

1. **Talk.** A sync client: the app can reach its own server, authenticate, and read the relationship
   API. Prerequisite for everything below; nothing about connections can be true before it.
2. **Approve.** The phone renders the SAS and is the confirming party (§3.6.4, condition 2).
3. **Observe and end.** The connections screen proper: who is connected, since when, their key
   fingerprint, what they can actually see in plain words, when they last opened anything, and
   Leave / Revoke.

**Layer 2 before layer 3, deliberately.** A screen that only *observes* is the exact false comfort
the maintainer is worried about — it looks like oversight, reads like control, and changes nothing
about who can see what. Approval is the half that carries weight, and building it first means the
app cannot quietly become unaware again, because nothing pairs without it.

**Until layer 1 lands, no Companion screen may claim the phone will show or approve anything.**
That is a copy rule, and it is checkable: the corpus test in `fieldHelp.test.ts` already reads
component markup rather than module exports, so a promise about the phone can be caught the same way
a fake "all clear" line was.

### 3.6.6 What this changes about the order of work

> **Amended by §3.7.** The two-stage split below is still right, but the thing 4.0b unlocks is no
> longer "the phone renders the phrase" — it is "the phone can run the PAKE", which is gated on the
> lazysodium binding in §3.7.6. And 4.0a's confirmation step is a typed code, not a compared phrase.

§4.0 (the invite acceptance page) still comes first — without it there is no therapist path at all.
But it now ships in two honest stages rather than one:

- **4.0a** — the page exists, redeems the invite, generates keys, takes one passphrase, enrols the
  authenticator, and shows the SAS with an out-of-band comparison instruction and a **refuse** path
  that burns the invite *(both amended: the code replaces the compared phrase per §3.7, and only an
  explicit human report burns anything per §3.9.1)*. Owner-side invite state and Cancel land here
  too (§3.6.3b).
- **4.0b** — the phone becomes the approving device (§3.6.4, condition 2), which requires the sync
  client from §3.6.5 layer 1.

4.0a is genuinely useful on its own and is not throwaway: the SAS, the refusal path, the invite
lifecycle and the Leave/Revoke verbs are all identical in both stages. Only the device that renders
the owner's half of the phrase changes.

---

## 3.7 Remote pairing: a link plus a code, and a PAKE behind it

The maintainer, on reading §3.6:

> "I mean there should be a way a user to connect to the therapist, even if they are not in the
> office. IF it's a string they can copy and paste from an email, or something, maybe a URL + soft
> password, that follows up into a 2factor, which then has an encrypted negotiation for the rest of
> the details?"

**This is a better design than §3.6.4's and it should replace it.** What is described there is, almost
exactly, a **balanced password-authenticated key exchange**. It is worth naming that, because it
means the hard part is a solved problem with a specification and reference code rather than
something to invent here.

### 3.7.1 Why the §3.6.4 prescription was the weaker answer

§3.6.4 was right that a compromised server can draw two matching phrases, and right that the
comparison therefore cannot travel over the server. Its conclusion — read the words aloud on a call
— is the weakest available fix for three reasons:

1. **It requires simultaneity.** Both people, on a call, at once. That is precisely the case the
   maintainer says has to work without: *"even if they are not in the office."*
2. **It depends on a human comparing carefully.** People confirm dialogs. A control whose failure
   mode is "the user clicked yes" is a control that degrades to nothing under exactly the pressure
   it exists for.
3. **It is a ritual, not a mechanism.** Nothing enforces it. There is no test that can assert people
   read the words to each other.

A PAKE has none of those properties. It is asynchronous, it needs no human comparison, and the
exclusion of the server is structural.

### 3.7.2 The recommendation: CPace

Checked against the current state of the CFRG process rather than recalled — see §3.7.6 for
citations.

- The CFRG PAKE selection produced **CPace** (balanced) and **OPAQUE** (augmented).
- **OPAQUE is RFC 9807.** It is the wrong shape here: augmented PAKEs are for a *server* that stores
  a password verifier. Our server is the adversary being excluded, and it stores nothing.
- **CPace is the balanced one and the right one**, because both humans genuinely share the same short
  code. It is still an Informational-track draft (`draft-irtf-cfrg-cpace`, rev 21 as of April 2026),
  which is worth stating plainly rather than implying RFC status.
- There is a **CPace implementation over ristretto255 + SHA-512 built directly on libsodium**
  (`jedisct1/cpace`, BSD-2-Clause, by libsodium's own author). Its stated purpose is *"pairing IoT
  and mobile applications using ephemeral pin codes, QR-codes, serial numbers"* — this exact use
  case. Single round trip; `crypto_cpace_step1/2/3`.

The defining property, and the reason a six-character code suffices where it would be absurd as a
password: **a man-in-the-middle gets one online guess per attempt and no offline dictionary attack.**
Rate-limit attempts to a handful and a short code is genuinely enough.

### 3.7.3 The flow

1. **Owner creates the invitation.** The server mints the invite id and link as it does today. The
   owner's *device* — not the server — generates a short, human-typable code.
2. **Two channels, and this is the mechanism, not the friction.** The link goes by email. The code
   goes by anything else: read over the phone, an SMS, a message in whatever the two people already
   use. They must never travel together. This is what kills §3.6.3(a) — "bearer of the link wins" —
   because the link alone is now worthless.
3. **Therapist opens the link, types the code.**
4. **CPace runs between the therapist's browser and the owner's device**, relayed through the server
   as opaque blobs. The server cannot participate: it does not have the code.
5. **The derived key encrypts the rest of the negotiation** — the therapist's public keys, the
   wrapped reading key, the capability set. This is the maintainer's "encrypted negotiation for the
   rest of the details", and it is the right instinct: today every one of those values is either
   pasted by hand or sent in the clear.
6. **TOTP enrolment happens inside that encrypted channel.** Also the maintainer's ordering, also
   right. Today `POST /totp/enroll` carries the client-chosen secret over plain TLS to a server that
   is explicitly not trusted with content.
7. Invite → `CONSUMED`, exactly as now.

**It is store-and-forward, not a phone call.** CPace is one round trip, so through a relay it is:
owner posts, therapist fetches and responds, owner finishes next time they open the app. Three
touches, none simultaneous. That is the whole of the maintainer's requirement, met.

### 3.7.4 The hard invariant: the server never learns the code

If the server had the code it could run the exchange itself and sit in the middle. So:

**The pairing code is generated on the owner's device, displayed there, typed by the therapist, and
never transmitted to the server in any form.**

Two consequences worth stating because they are the point:

- The Companion **must not offer to email the code**. It emails the link only. A "send both by
  email" convenience would silently collapse the whole design back to bearer-wins, and it is exactly
  the sort of helpful affordance that gets added later by someone who does not know why it is
  absent. Write the reason at the call site.
- The server cannot be compelled to produce what it never received.

This is testable in the same style as the existing "logging must never carry content" invariant: the
code must not appear in any request body, header, query string, or log line the server can observe.

### 3.7.5 The limit of a browser-side PAKE, and why §3.6.4 condition 2 still stands

**Correction to an overreach in the first draft of this section**, which claimed a PAKE excludes the
server "structurally" and therefore dissolves the phone question. It does not, and the distinction
matters enough to spell out.

A PAKE excludes the server from the **protocol**. It does not exclude the server from the **code
that runs the protocol**, when the server is the thing serving that code. A fully compromised
Companion can ship JavaScript to the owner's browser that simply reads the code out of the input box
and phones home. No amount of protocol design survives an attacker who controls the client.

So the honest scoreboard:

| Threat | SAS in browser | PAKE in browser | PAKE with owner's half on the phone |
|---|---|---|---|
| Someone intercepts the invite email | no help | **prevented** | prevented |
| Network attacker between the parties | prevented *if* read aloud | **prevented** | prevented |
| Wrong person redeems the link first | no help | **prevented** | prevented |
| Honest-but-curious server (logs, relays, does not tamper) | prevented *if* read aloud | **prevented** | prevented |
| Server actively serves malicious JS | **no help** | **no help** | **prevented** |

The PAKE wins every realistic row and wins them without a scheduled phone call — which is why it is
still the right mechanism. But the bottom row is untouched by the choice of mechanism, because a
server drawing both browsers can forge a compared phrase just as easily as it can steal a typed
code. **§3.6.4 condition 2 is therefore unaffected by §3.7 and remains the answer to the strongest
threat: the owner's half belongs on the phone, whose code the server cannot rewrite.**

What §3.7 changes is the *reason* the phone helps — not "so the phrase cannot be forged" but "so the
code cannot be exfiltrated by served JavaScript" — and its *urgency*: the phone is now a
strengthening against the worst case rather than a precondition for any safety at all. That is what
makes 4.0a shippable on its own.

### 3.7.5.1 What survives from §3.6.4

The SAS is **demoted, not deleted**. With a PAKE it is no longer the control that prevents a
machine-in-the-middle — the PAKE is. But a key fingerprint remains genuinely useful on the
connections surface (§3.6.5 layer 3) for anyone who wants to verify out of band later. So:

- `share/pairing.ts` stays, shown as a fingerprint on the connections screen.
- It stops being a blocking confirmation step in the pairing flow.
- The "read these aloud" copy requirement goes with it.

The §3.6.4 **audit** consequence stands unchanged: `PAIR_CONFIRMED`, `PAIR_REFUSED`,
`CONNECTION_ENDED`. A failed PAKE attempt is now a much more interesting event than a refused SAS
ever was — it is someone guessing — and it needs a distinct action and a threshold that alerts.

### 3.7.6 A verified blocker on the Android side — **RESOLVED, and it was not where this said**

> **ANSWERED 2026-08-17 (gate 0.1). This section's diagnosis was right about the symptom and wrong
> about the patient, and the correction is good news.**
>
> - `lazysodium-java` **5.1.0** — the pinned version — has no Ristretto255 binding at all. Not a
>   missing method; the class is absent. Verified by downloading the Maven Central jar: **0**
>   `Ristretto255` entries.
> - `lazysodium-**android**` **5.1.0** — the artifact **already shipping on the phone** — has the
>   **full** Ristretto surface. Verified the same way from the published `.aar`: **5** entries.
> - So **the phone was never blocked.** What is blocked is the *host-JVM compile and test* path,
>   because `:sync-crypto` compiles against `lazysodium-java`. That is a much better problem: it
>   stops us testing, not shipping.
> - `lazysodium-java` **5.1.4** has the full binding and stays on Java 8 bytecode (major 52).
>   **5.2.0 must be avoided** — class major 65, `org.gradle.jvm.version=21` — as the existing comment
>   in `libs.versions.toml` already warns.
>
> **The fix is roughly four lines in `gradle/libs.versions.toml`:** split the shared `lazysodium`
> version into `lazysodiumJava = "5.1.4"` and `lazysodiumAndroid = "5.1.0"`. No change to
> `SyncCrypto.kt`, `SyncCryptoFactory.kt`, or any existing test.
>
> **A side-finding worth more than the fix.** The repo pins both artifacts to one version string and
> a comment asserts that this guarantees cross-artifact API parity. That invariant is **false today**:
> at 5.1.0 the android `LazySodium` exposes 328 public methods and the java one 259, and the
> 69-method gap *is* the Ristretto surface. The split pin above does not break the invariant — it
> **restores** it. Replace the comment with a reflection test that actually compares the two method
> sets; a comment cannot enforce a property, and this one silently stopped being true.
>
> **Residual risk, stated rather than waved away:** nobody has executed the ristretto path on a real
> device. The symbols are present in all five ABIs, but "symbol present" is not "test passed" —
> budget one instrumentation test before depending on it. This joins §6's list of things only a
> device can settle.
>
> The original analysis follows, kept because the reasoning about *why* the phone matters is
> unaffected — only the cost of getting there changed.

### 3.7.6a The original (superseded) analysis

The browser half is unblocked: `libsodium-wrappers-sumo` is already a dependency of
`companion/web` and the sumo build carries the ristretto255 operations CPace needs.

**The phone half is blocked on one missing binding.** CPace derives its generator with
`crypto_core_ristretto255_from_hash`, and **lazysodium-java does not bind that function.** The app
reaches libsodium exclusively through lazysodium (`sync-crypto` compiles against lazysodium-java and
runs on lazysodium-android — see `SyncCrypto`'s KDoc), so CPace cannot run on the phone until that
binding exists.

It is bounded work — lazysodium is JNA-based and declaring one additional native function is its
documented extension path — but it is a gate on §3.6.5 layer 2, not a detail to discover during
implementation. **Verify this first, before any of §3.7 is committed to.** If it turns out worse
than it looks, the fallback is to run the PAKE browser-to-browser and accept the weaker property
from §3.6.4 condition 2, which should be labelled as weaker rather than presented as equivalent.

Sources:
- <https://datatracker.ietf.org/doc/draft-irtf-cfrg-cpace/>
- <https://datatracker.ietf.org/doc/rfc9807/>
- <https://github.com/cfrg/pake-selection/blob/master/README.md>
- <https://github.com/jedisct1/cpace>

---

## 3.8 The handshake: what a heartbeat fixes, and the three things it does not

The maintainer, on §3.6.3:

> "is there not like a ping out to each other, like occasional hand shake?"

Yes, and it should exist. It is worth being exact about which problem it solves, because it is not
quite the one it appears to solve.

### 3.8.1 Prevention and visibility are different jobs

§3.6.3(a) said the genuine therapist gets `GONE` and telephones the owner, and that call is the only
detector. A handshake improves that — but the PAKE removes the need for it.

| | Fixed by |
|---|---|
| An attacker with the link cannot complete the pairing | **§3.7 — the PAKE.** Prevention. |
| The owner can *see* pairing state as it happens | **The heartbeat.** Visibility. |

Both are wanted, and the order matters: visibility without prevention is a faster way to learn you
have been had. With §3.7 in place, the heartbeat stops being a security control and becomes what it
should be — an operational one.

### 3.8.2 What it actually buys

**Live pairing state, which is the direct fix for §3.6.3(b).** The silent `REDEEMING` dead end
becomes a visible sequence on the owner's screen: *link opened · waiting for the code · abandoned 12
minutes ago*. That converts "the link says it's gone" from a mystery into something the owner can
see and cancel.

**Revocation propagates in one interval rather than at next request.** This is the §3.6.1 point that
"a revoke is not a message". With a heartbeat it becomes closer to one.

**A real "last seen" on the connections surface** (§3.6.5 layer 3), which is otherwise guesswork.

### 3.8.3 Three limits that must reach the copy

1. **It is not a remote wipe, and must never be described as one.** A revocation ping tells an
   *honest* client to stop and discard. A modified client ignores it and keeps whatever plaintext it
   has already cached. The heartbeat makes revocation fast for honest clients; it makes it
   enforceable against nobody. This is the same honesty §3.6.1 demands of revoke, and the same
   caveat `GrantManager.svelte` already carries.
2. **It is metadata the server can see** — when a clinician is active, and how often. Small, real,
   and §1.2's list of "what a compromised server can still do" needs a line for it rather than
   letting it arrive unannounced. On the phone it is also battery and radio.
3. **A missing heartbeat is ambiguous.** Offline, asleep, on a plane, or removed — indistinguishable.
   It must never be rendered as an accusation or a fault. This is the standing constraint that *a
   gap in someone's data is never drawn as a failure*, applied to a person instead of a chart, and
   the invariant suite should be able to catch a regression here.

### 3.8.4 Shape

An interval measured in minutes, not seconds; jittered so every client does not wake together;
carried on a **signed** request once §4.3 lands, so a heartbeat cannot be forged or replayed to fake
liveness. During an active pairing the owner's screen may poll faster — that window is short and
bounded by the invite TTL.

---

## 3.9 A wider look: what we walked past while focused on the pairing

Requested directly:

> "you said you overreached, please verify, look for any potential security principles and avenues
> we maybe missing by being too focused please"

Fair challenge. §§3.5–3.8 spent their entire length on one threat — a machine in the middle of one
ceremony that happens once per relationship. Below is what that focus cost. Everything here was
checked in the source, and the first finding is a mistake in this document rather than in the code.

### 3.9.1 FINDING (self-inflicted): "burn the invite on refusal" is a denial-of-service primitive

§3.6.3(c) says a refused confirmation must kill the invite. With §3.7's PAKE in place that is
**wrong, and dangerously so.**

A wrong PAKE code and an attacker's guess are *the same event*. They are indistinguishable by
construction — that is what a PAKE is. So "burn on a failed confirmation" means **anyone holding the
invite link can permanently destroy every invitation the owner ever mints, by typing one wrong
code.** The owner re-mints; the attacker burns it again; the therapist never gets in. An
"incredibly secure" framing produced a control that hands an attacker a reliable, cheap, repeatable
veto over the product's core flow.

It is also hostile to the honest case: the legitimate therapist mistypes a code read to them over a
phone line, and their invitation dies.

**The existing code already gets this right, and §3.6.3(c) would have regressed it.** `redeemInvite`
applies capped backoff and carries the comment *"Wrong secret: bump fail count, apply capped
backoff. Never consume the invite."* That decision was correct and is hereby restated rather than
overturned.

The fix is to separate a *guess* from a *report*:

| Event | Meaning | Response |
|---|---|---|
| Wrong code | Could be a typo, could be an attacker. **Unknowable.** | Capped backoff, per-source *and* per-invite. Audit. Alert the owner past a threshold. **Never burn.** |
| Human says "this wasn't me" / "I didn't expect this" | Unambiguous. A person is reporting. | Burn immediately, alert loudly, require a fresh invite. |

Only the second is a signal. §3.6.3(c) conflated them, and the correction is that **only an explicit
human report may destroy an invitation.**

### 3.9.2 FINDING: the rate limiter forgets, and §3.7's security now rests on it

`AttemptLimiter` holds a `HashMap<String, Window>` in process memory. Verified — there is no
persistence.

That was acceptable when rate limiting was hardening. **§3.7 changes its status.** A PAKE's entire
security argument is *one online guess per attempt, and no offline attack* — which is only as strong
as the thing counting attempts. Today:

- A server restart — a crash, a deploy, an OOM, a `docker compose up -d` — **resets every lockout.**
  An attacker who can provoke or simply wait for a restart gets a fresh budget.
- Two instances behind a load balancer silently grant **twice** the budget, with no signal that the
  limit has been halved in effectiveness.

Pairing attempt counters must be **persisted** (the same SQLite the invite lives in) before the PAKE
can be claimed to do what §3.7 says it does. This is a prerequisite, not a follow-up.

### 3.9.3 FINDING: the journal database is not encrypted at rest

This is the one that most deserved the wider look, because it affects **every user**, including
everyone who never has a therapist.

Verified: `Room.databaseBuilder` in the DI module sets no `openHelperFactory`. There is no SQLCipher
dependency anywhere in the build. The journal, the assessments, the safety plan and the thought
records sit in a plaintext SQLite file.

`PinManager` is genuinely well built — PBKDF2-SHA256 with a per-PIN random salt in an AES-256
`EncryptedSharedPreferences`, constant-time compare, and a migration off a legacy static-salt
SHA-256. But it **verifies** a PIN; it does not **derive a key**. The app lock is a door in front of
the UI, not a lock on the data.

What genuinely mitigates it, and should be said rather than assumed: `android:allowBackup="false"`
is set, which closes the classic ADB/cloud-backup exfiltration route, and Android's file-based
encryption plus app sandboxing protect app-private storage on a healthy device with a locked
bootloader.

What does not: root, an unlocked bootloader, a forensic extraction, or a malicious app running with
elevated privilege. Against any of those the app lock is decorative.

**The copy currently claims nothing false** — "App lock (PIN)" and "Daymark is locked" promise
exactly what they deliver. But a person reading "App lock" on a mental-health journal will infer
more than is true, and the gap between the inference and the reality is the product's largest
undisclosed weakness. Two honest resolutions, and they are not exclusive: key the database from the
PIN or the sync passphrase (SQLCipher), and/or state the limit plainly where the lock is enabled.

**Priority tension, stated rather than silently resolved:** this affects every user of the app, and
the therapist channel affects those who have a therapist. On reach alone this outranks everything in
§§3.5–3.8. The maintainer's stated priority is the therapist connection, so it is not being
reordered unilaterally — but it should be a deliberate choice, not an oversight. See §4 step 1.

### 3.9.4 FINDING: email is now load-bearing in two places

`RecoveryRoutes.kt` recovers the owner's access token by email. §3.7 sends the invite link by email.
A compromised mailbox therefore yields **owner takeover** by the first route and **invite
interception** by the second.

§3.7 already fixes the second — the code never travels by email, so an intercepted link is worthless.
The first is untouched and is now the softest door in the system: it is easier to compromise a
mailbox than to break a PAKE. §4.2's owner-identity work must treat recovery as a **second front
door with equal standing**, not as an edge case bolted on at the end.

### 3.9.5 FINDING: metadata survives the encryption, and the heartbeat adds to it

The payload is genuinely E2EE (§1.1). The *shape* of it is not. A server that decrypts nothing still
observes upload cadence, blob sizes, and timing — and "this person journalled daily for eight months
and then stopped for nine days" is a clinically meaningful inference drawn without touching a
ciphertext. §3.8's heartbeat adds a second, more regular signal.

This interacts directly with §3.9.6: a person can disengage invisibly from their *therapist* while
remaining perfectly visible to the *server*.

Mitigations are known — pad blobs to size buckets, jitter upload timing, decouple writes from
capture — and are probably out of scope for this slice. **The requirement here is disclosure, not
implementation:** §1.2's list of what a compromised server can still do must carry this line, so it
is a stated limit rather than something discovered later by someone who trusted the word
"end-to-end" to mean more than it does.

### 3.9.6 PRINCIPLE: disengagement must not require a declaration

The maintainer, on the ambiguity of a missing heartbeat:

> "especially for the 'are they just offline, or are they no longer with us.' because it makes a
> soft 'i don't want to see this therapist' that much easier"

This is a better reading than §3.8.3's, which treated the ambiguity as a hazard to be rendered
carefully. It is not a hazard. **It is the feature**, and it should be designed for deliberately.

Ending a clinical relationship by announcement is not always safe or possible. A person may not be
able to say it, may not want a conversation about it, may fear a reaction. In a mental-health
product, the ability to simply stop — without a declaration, a form, or a notification firing on the
other end — is a **safety property**, not a UX convenience.

Two design consequences, both binding:

1. **The surfaces are asymmetric.** The owner sees the therapist's activity in useful detail. The
   therapist sees far less of the owner's — coarse at most, and never a live "last seen". A screen
   telling a clinician *"last opened 3 minutes ago"* converts a quiet exit into a confrontation, and
   hands a pushy one a tool.
2. **Absence is never rendered as a fault, an alert, or a prompt.** No "your client hasn't checked in
   — send a reminder?". That is the standing constraint *a gap in someone's data is never drawn as a
   failure*, applied to a person rather than a chart, and it should be enforceable by the invariant
   suite in the same way.

Note the tension this creates with §3.9.5 and resolve it honestly: soft disengagement is invisible
to the therapist by design, but **not** invisible to the server. Do not imply otherwise.

### 3.9.7 FINDING: the audit log is written by the party it would incriminate

`AuditStore` records `AUTH_FAIL`, `SHARE_OPEN`, `LOCKOUT` and the rest — and the server writes all of
it. A compromised server can forge entries, drop them, or rewrite the sequence end to end. Task #16
("give the admin chain check a real digest") is open on exactly this, and a hash chain alone does not
fix it: whoever can rewrite the entries can recompute the chain over them.

A chain is only evidence if its head is **anchored somewhere the server cannot reach.**

Which points at the same conclusion §3.7.5 reached by a different road: **the phone is the only
component in this system whose code and storage the server does not control.** That makes it the
natural anchor for three separate problems — running the owner's half of the pairing, holding key
custody, and periodically recording the audit head. Three arguments converging on one piece of work
is a strong signal about what to build.

### 3.9.8 SMALLER: `credential_id` collides across relationships

`enrollTotp` is insert-only on `credential_id OR rel_ref`. A clinician who legitimately sees two
people on the same self-hosted server, and who reuses a credential id, gets `ALREADY_ENROLLED` with
no explanation of why. Fail-closed is right; the opacity is not. Either scope the id per relationship
or return an error that says what to do.

### 3.9.9 SMALLER: the therapist's browser is where the plaintext lands

Today `localStorage` holds only key pins and onboarding state — no key material — which is
defensible. §3.7 changes that: something has to persist across sessions or the therapist re-does the
ceremony every visit. Decide deliberately rather than by default:

- Persist the key **wrapped**, never unwrapped. Passphrase-derived, as now.
- Drop the unwrapped key from memory on an idle timeout.
- Browser extensions can read the DOM, and screenshots exist. Neither is preventable. **Say so** —
  a clinician on a shared workstation is a realistic reader of this product, and nothing in the
  system addresses that today.

---

## 3.10 The clinician starts it, the patient authorises it

The maintainer, after §3.7:

> "a clinican will have to be able to initiate it, the average person won't know how to, or really
> even care" — and, on their own provider: "my current provider just needed the last four of my
> social and bday and bam i have my own doctor portal... lol."

Both are right, and the second names the industry baseline this has to beat.

### 3.10.1 Separate who STARTS it from who AUTHORISES it

These are different questions and they get different answers. The clinician starting the pairing is a
**workflow** fact — patients will not do it, and a design that requires them to is a design nobody
uses. The patient's device completing it is a **security** fact.

**§3.7 already supports this and no new cryptography is needed.** CPace is a *balanced* PAKE: both
parties are peers holding the same low-entropy secret, with no privileged role. Direction of
initiation is invisible to the protocol. Clinician-initiated and owner-initiated differ only in who
clicks first and who reads the code out.

### 3.10.2 The one swap that beats last-four-and-birthday

Identity facts are **identifiers, not secrets**. A last-four and a date of birth are on insurance
cards, in breach dumps, and often on social media. Any scheme authenticating with them proves only
that the asker knew public facts about the patient.

Replace them with a **freshly generated random code for this one pairing**. The counterintuitive
part, and the reason this satisfies "not too much but not too little": it is simultaneously *more
secure and less work*. The patient types six random characters instead of recalling and entering
sensitive personal data — nothing to remember, nothing sensitive rendered on screen.

| | Typical patient portal | This |
|---|---|---|
| Secret | SSN4 + DOB — public, permanent, breached | Random, single-use, ~10 minutes |
| Guessing | ~10,000 options, often unmetered | One online guess per attempt, rate-limited |
| Channel | Same form as everything else | Spoken in the room, or on the call |
| What success grants | The whole record | An empty connection (§3.10.4) |

**Do not add an identity check as a second factor.** It costs a step, proves nothing, and actively
teaches people that seeing their correct details means safe — the exact reflex phishing relies on.

### 3.10.3 The flow

1. Clinician clicks "connect a patient"; the server mints a pairing.
2. Their screen shows a QR **and** a short code.
3. Patient scans with the app, or types the address and code by hand (§3.10.5).
4. The app names who is asking before anything is agreed to.
5. Patient enters the code the clinician **says aloud or hands over on paper**.
6. CPace runs. Connected.

The QR carries **address and pairing id only** — public routing information. The rule from §3.7.4
holds unchanged and is what keeps the QR safe: *nothing goes in it that you would not be willing to
read aloud.* The code travels by a channel the internet cannot reach.

### 3.10.4 Connecting and sharing are separate acts

Connecting establishes **who someone is**. It grants access to **nothing**.

This is the cheapest defence in the design. A perfectly executed attack — forged QR, patient scans,
attacker somehow obtains the code — yields an empty connection. Sharing is a later, separate,
deliberate act on a screen that states exactly what becomes visible. It costs nothing in the common
case, because connecting happens in the room and sharing happens at leisure, and it means the
low-deliberation step (scanning) is not the dangerous one.

Stated as a layering, since each layer catches what the one below cannot:

| Layer | Answers |
|---|---|
| QR | where to go — public, forgeable, trusted for nothing |
| PAKE | both ends hold the same code, one guess for a middleman |
| Human tap | do I *want* this person |
| Sharing, later | what may they actually see |

A PAKE proves identity, never entitlement. That distinction is why the last row exists.

### 3.10.5 Concerns

- **Rate limiting is load-bearing.** A six-character code with unmetered attempts is weak. The
  persisted limiter from PR #75 is the prerequisite — that work was not a side quest.
- **The patient may not have the app.** A clinician-initiated QR must lead somewhere sensible when
  nothing is installed, rather than dead-ending. Easy to overlook, and it is the modal first contact.
- **Coercion has no technical fix.** A clinician can apply pressure in the room. What helps is that
  leaving is unilateral and needs no cooperation from them (§3.6.1).
- **The typed path is the specification, not the fallback.** Address plus code must do everything the
  scan does. If it does, the QR is pure convenience and carries no extra risk; if it ever does more,
  the QR has become a bearer token in a picture.

---

## 3.11 Three deployment shapes, and what the server is actually for

### 3.11.1 The server's real job, restated by the maintainer

> "it's a 'what if my phone is lost or stolen, how am i getting the past years of my lifes history
> back'" — and, on pairing: "this sounds more like it should be a config file not a backup, and that
> the config file then securely binds to your phone somehow. Its like how i handle vpn connections?"

Both are better framings than the ones in this document. The server's primary purpose is **disaster
recovery**, not a bigger screen; reading on a laptop is a pleasant side effect. And pairing really is
closer to installing a VPN profile than to running a backup wizard. The first-run copy should say the
first thing, because it is the reason someone stands up a machine at all.

**Recovery works, and the mechanism was checked rather than assumed** (`SyncCrypto.kt`):

```
passphrase --Argon2id(salt, mem>=256MiB, ops>=3)--> master --crypto_kdf--> SYNC_KEY --> XChaCha20-Poly1305
```

The key derives from the passphrase alone. **No device key, nothing bound to the handset.** A new
phone, the app installed, the same passphrase, pointed at the server, decrypts everything. That is
exactly the property the disaster-recovery framing needs, and it is already true.

The cost is the other half of the same fact and must be stated where the passphrase is chosen, not
in a footnote: **forget it and the data is gone.** Not recoverable by the owner, the maintainer, or
the server, which holds ciphertext and has never had the key. This is what makes it safe and what
makes it unforgiving.

### 3.11.2 Solo, Paired, Practice

Naming, because A/B/C was used for two different things in one conversation and immediately confused
everybody:

| Shape | Who owns the machine | Whose data is on it |
|---|---|---|
| **Solo** | the person | theirs |
| **Paired** | the person | theirs, some of it shown to one clinician |
| **Practice** | the clinic | many people's, as tenants |

**Solo and Paired are one product with a flag.** Same trust model, same threat model, clinician
features switched on. Cheap, and the clinician-facing documentation the maintainer wants — notes,
history, results, calendar, the things a therapist cannot be expected to remember per person —
belongs here.

### 3.11.3 Practice is a different product, not a bigger one

**It inverts the arrangement this product exists to offer.** In Solo and Paired the person owns the
machine and the journal sits on their own hardware. In Practice the clinic owns the machine, the
person is a tenant, and their journal lives on their clinic's server administered by clinic staff.

That is not a scaling problem, it is the opposite posture, and it makes one question load-bearing
that Solo and Paired never have to answer:

**Who can reset a forgotten passphrase?**

Today the honest answer is nobody (§3.11.1), and that is what keeps the server ignorant. Every
convenient Practice answer — an administrator resets it, a recovery key is escrowed, staff can
re-issue access — means **the practice can read the journals.** It may still be the right trade for
a clinic that needs it, but it has to be a decision made in the open, with the copy changed to match,
rather than a consequence discovered after the screens are drawn.

Recommendation: **build Solo and Paired first; treat Practice as a separate decision whose gate is
that question**, answered before any of its screens exist.

### 3.11.4 Roles are capabilities with presets, and only Practice needs them

Neither hardcoded roles nor group permissions. **Capabilities**, with roles as named presets over
them. The repository already has the shape — `scope` on invites and grants, `CapabilityRow.svelte`,
`GrantManager.svelte` — so this extends what exists rather than introducing a parallel system.

Roles are meaningless in Solo and Paired: there is exactly one clinician. The receptionist case is
the one that proves the model — they need scheduling and nothing clinical, which a capability set
expresses exactly and a role enum fudges.

The distinctions the maintainer raised (psychologist, psychiatrist, general therapist, reception)
are **presets**, not types. Whether a prescriber sees something different from a talking therapist is
a capability question, answered per relationship by the person whose data it is.

### 3.11.5 Local by default, and why a port is not a control

Deliberate friction before a machine is reachable from the wider internet is right: a setting that
must be acknowledged, plus a configuration value, so nobody arrives there by accident.

Two corrections. **A non-standard port is not a security control** — everything gets scanned; it
reduces drive-by noise and nothing else. And the friction is a *deliberateness* gate, not a boundary;
it should be described that way rather than implying it protects anything by itself.

The real answer for remote access without sysadmin work is §3.4's: a mesh VPN. And the server **can**
know honestly which case it is in — the interface and address it is bound to are facts it can read,
so "reachable only from your own network" can be a statement rather than a guess. Putting a port in
the QR is fine; it is public routing, same rule as the address (§3.10.3).

### 3.11.6 CORRECTION: the access-control model is specified, not built

The maintainer believed the Practice features were "mostly built". Checked:
`docs/COMPANION_ACCESS_CONTROL.md` is **307 lines** of organizations, roles and permissions, and
there is **no corresponding code** — no org, no tenant, no role, no account provisioning anywhere in
the server, web or app source.

Worth recording *why* the mistake was reasonable: a thorough specification reads exactly like a
description of a working system. That document should carry a header saying it is a design, not a
description, because this will otherwise recur.

### 3.11.7 The demonstration deadline

> "i'm hoping to show my therapist the power of this tool by the week after next"

**That demonstration needs Paired, not Practice**, and the distinction is what makes the deadline
plausible. A demonstration is one clinician looking at one person's shared slice — which is Paired
exactly, and none of Practice's multi-tenancy, account provisioning or roles appears in it.

What already exists for it: the relationship and share routes, `ShareBuilder`, `GrantManager`, and
the therapist screens from Phase 3. What is missing is the acceptance page (§4.0a) — `/portal/invite`
is still a 404, and it is the single break between a therapist receiving a link and seeing anything.

Note also that the demonstration does **not** require the phone sync client (§3.6.5 layer 1): the
Companion already opens an exported backup file, so a slice can be shared from a file-loaded session.
That removes the largest piece of work from the critical path.

**Practice cannot responsibly be built to this deadline**, and the reason is §3.11.3 rather than
effort: it turns on a question about passphrase reset that decides whether clinics can read their
patients' journals. A demonstration deadline is the worst possible reason to answer that quickly.

---

## 4. The work, in order

**Read §4.A first.** The steps below are unchanged in content but are now sequenced by §4.A, which
folds in the §3.9 findings and puts two cheap gates ahead of everything.

### 4.A The implementation plan

Requested: *"then we need a plan to start implementing all this in proper."*

Six steps. The ordering rule throughout is **answer the cheap questions that can invalidate the
expensive work before doing the expensive work** — the same rule that would have caught the
`identityHash` and sleep-log failures earlier than it did.

---

#### Step 0 — Two gates. Days, not weeks. Nothing else starts until both are answered.

| Gate | Question | If the answer is no |
|---|---|---|
| **0.1** | Can `crypto_core_ristretto255_from_hash` be bound through lazysodium at acceptable cost? (§3.7.6 — verified missing) | The phone cannot run CPace. 4.0b becomes a fallback, and §3.7.5's bottom row stays unaddressed. Say so rather than quietly shipping the weaker thing. |
| **0.2** | Can a CPace exchange be driven **JVM ↔ browser** in an automated test, on the host, with no device? | The crypto has no oracle. Everything downstream is unverifiable and the design needs rethinking before it is built, not after. |

0.2 is the more important of the two and the easier to skip. `sync-crypto` already proves the
pattern works — Android-free logic, real libsodium, host JVM tests — so this is an extension of an
established approach rather than a new one.

**Deliverable:** a written answer to both, in this document, with the test committed if 0.2 passes.

---

#### Step 1 — The decision §3.9.3 forces

The journal database is unencrypted at rest, and that affects **every user**. The therapist channel
affects those who have a therapist. On reach, §3.9.3 outranks all of §§3.5–3.8.

This is not being reordered unilaterally — the maintainer's stated priority is the therapist
connection, and priorities are theirs. But it must be a **choice**, and there are only three honest
ones:

| Option | What it costs | What it buys |
|---|---|---|
| **A.** Key the database (SQLCipher, from the PIN or sync passphrase) before the therapist work | Delays goal B. Migration for existing installs. Lost-PIN becomes lost-data — a real product decision, not just an engineering one. | The largest undisclosed weakness closed, for everyone |
| **B.** Disclose it now, encrypt later | Hours | Honesty immediately; the gap stays open |
| **C.** Do the therapist work first, revisit after | Nothing now | The gap stays open *and* undisclosed — the only option that is hard to defend |

**Recommendation: B now, A scheduled.** A sentence where the app lock is enabled, saying what it does
and does not protect, costs almost nothing and removes the false inference today. Option A is real
work with a genuine product decision inside it (what happens when someone forgets the PIN) and
deserves its own design pass rather than being wedged in here.

---

#### Step 2 — Prerequisites that §3.9 turned into blockers

Both are small, both are server-side, and **§3.7 cannot honestly ship without them.**

1. **Persist the attempt limiter** (§3.9.2). Pairing counters move into the SQLite that already holds
   the invite. A PAKE's whole claim is "one online guess per attempt"; an in-memory `HashMap` that a
   restart clears is not a limiter, and today a `docker compose up -d` resets every lockout.
   *Acceptance:* a test restarts the store and shows the count survived.
2. **Correct the burn rule** (§3.9.1). Wrong code → capped backoff, per-source *and* per-invite,
   audited, owner alerted past a threshold. Only an explicit human report burns an invite.
   *Acceptance:* a test proves repeated wrong codes never consume the invite, and that a reported
   invite dies at once.

Also here, because it is a documentation fix with no dependencies: ~~**add the metadata line to
§1.2** (§3.9.5). Cadence, size and timing survive the encryption, and the heartbeat adds to them.~~
*(DONE — §1.2 item 1 now says the encryption does not cover the shape of the traffic, and carries
the heartbeat §3.8.3 asked to have listed rather than arriving unannounced.)*

---

#### Step 3 — 4.0a: the therapist path, browser-side

The bulk of the work, and shippable on its own (§3.7.5).

*Server:* pairing relay as an opaque-blob mailbox; invite state machine with the four visible states
and Cancel; new audit actions (`PAIR_CONFIRMED`, `PAIR_REFUSED`, `PAIR_GUESS_FAILED`,
`CONNECTION_ENDED`); Leave and Revoke endpoints per §3.6.1, with the therapist's Leave structurally
unable to touch owner data; **route `/portal/invite`**, which is a 404 today.

*Web:* the acceptance page — one code, one passphrase; CPace via `libsodium-wrappers-sumo`; the
encrypted negotiation of keys and capabilities; TOTP enrolment moved inside that channel; the owner's
pairing screen with live state; the connections surface with the honest revoke copy and the
asymmetry §3.9.6 requires.

*Then:* delete most of `LoginGate`.

**Acceptance.** A therapist sent a link reaches a working portal without reading source, opening a
database, pasting base64, or scheduling a phone call. The pairing code appears in no request body,
header, query string or log line, and a test asserts it. Wrong codes are limited and audited and
never destroy the invite. An abandoned redeem is visible and cancellable. A therapist can leave; it
cannot delete the owner's data.

---

#### Step 4 — The phone learns to talk

§3.6.5 layer 1 — the sync client. The single largest piece in this plan and a hard prerequisite for
everything phone-side. Nothing about connections can be true on the phone before it exists, and no
Companion screen may claim otherwise until it does (§3.6.5).

---

#### Step 5 — The phone as root of trust

Three problems, one answer, which is why this is one step (§3.9.7):

1. **Pairing** — the owner's half moves to the APK (§3.7.5), closing the malicious-JS row.
2. **Audit anchoring** — the phone periodically records the audit head, so the chain is evidence
   rather than a story the server tells about itself. Closes task #16 properly.
3. **Connections screen** — §3.6.5 layer 3, with the §3.9.6 asymmetry.

Gated on Step 0.1 and Step 4.

---

#### Step 6 — The heartbeat

§3.8, last on purpose. It is an operational improvement once §3.7 provides prevention, and building
it earlier would be building visibility into a system that cannot yet stop the thing being watched
for. Signed per §4.3 when that lands. Carries the three limits of §3.8.3 and the §3.9.6 principle in
its copy.

---

**Unchanged and still ahead of QR pairing:** §4.3 request signing, for the reason §3.4 gives.
§4.2 owner identity now also carries §3.9.4 — recovery is a second front door with equal standing.

---

### 4.0 — The invite acceptance page *(now first: without it, goal B does not work at all)*

Per §3.5.1–3.5.3, split into two stages by §3.6.6. Sequenced by §4.A steps 3 and 5.

**4.0a — the therapist path exists.** A page at `/portal/invite` that redeems the link the owner
already sends, and replaces the nine-field sign-in with **one code and one passphrase**. The code is
the PAKE password (§3.7.3), so this stage carries: the CPace exchange relayed as opaque blobs, the
encrypted negotiation of keys and capabilities behind it, TOTP enrolment moved *inside* that channel,
owner-visible invite state (waiting / in progress / finished / dead), an owner **Cancel**, the
Leave / Revoke verbs of §3.6.1, and the new `AuditAction` kinds — including one for a failed PAKE
attempt, with a threshold that alerts (§3.7.5).

**4.0b — the phone becomes the owner's pairing device.** §3.7.6 + §3.6.5 layer 1. A separate stage,
not a separate design: the protocol is identical, only the device running the owner's half changes.

**Acceptance (4.0a).** A therapist who is sent an invite link can reach a working portal without
anyone reading source, opening a database, or pasting base64 — and **without a scheduled phone
call**. The pairing code never appears in any request body, header, query string, or log line
(§3.7.4), and there is a test that says so. Wrong codes are rate-limited to a handful of attempts and
raise an audit event. An abandoned redeem is visible to the owner and cancellable rather than a
silent dead end. A therapist can end their own participation; doing so cannot delete any of the
owner's data.

**Note on §4.1, which is done:** cutting the sign-in form's text was worth doing and is not wasted,
but most of that form should disappear in this step. Do not extend it further.

### 4.1 — ~~Cut the text; make the fields explicable~~ *(DONE — fb23023, cf63455)*

**Problem.** PR #72's orientation is correct and far too long. The therapist sign-in asks for nine
values (`serverUrl`, `inboxToken`, `relRef`, `credentialId`, `pinnedOwnerSignPubB64`,
`ownerBoxPubB64`, `wrappedKeyJson`, `totpCode`, `readingPassphrase`) with no examples and no
explanation. A server admin could not tell what they were.

**Do.**
1. Reduce each orientation destination to **one line**. Move every "why" sentence into the
   collapsed self-check below.
2. Build the self-check the maintainer described: a short list of checks, **collapsed by default**,
   each expandable for detail. It states observations, never a verdict — no green, no "secure ✓"
   (the invariant suite forbids it, and §1.2 means the browser cannot honestly claim it anyway).
   It must include the §1.2 point that a compromised server serves this page.
3. Every field in `LoginGate.svelte`, `SyncPanel.svelte`, `OwnerConsole.svelte` and
   `RecoverAccess.svelte` gets: a `placeholder` with a realistic **example** value, and an `i`
   affordance explaining what it is and where it comes from.
   - Not a `title=` tooltip: invisible on touch, unreachable by keyboard, and unreadable by screen
     readers. Use a real disclosure button with `aria-expanded` + `aria-controls`, or a popover.
   - Placeholders are never the only label — the visible `<label>` stays.

**Acceptance.** Orientation prose under ~120 words in the default view. Every input has a
placeholder and a help affordance. Existing invariant + copy-register tests still pass, including
the corpus that now reads component markup.

### 4.2 — Owner/admin identity *(design first, review, then build)*

**Problem.** §1.3. The owner is a string in an env var; the admin console has no credential.

**Design to write and get reviewed before any code:**

- **First-run claim.** A fresh server has no owner. The first person to reach it sets an owner
  credential. Must resist the obvious race: anyone who finds the server before the operator does
  becomes the owner. Mitigations to weigh: bind claiming to a token the operator already has
  (`DAYMARK_AUTH_TOKEN`), require loopback for the claim, or a one-time code printed to the
  container log — the log being the thing only the operator can read.
- **Relationship to the existing bearer token.** Does the token remain as the sync credential with
  the account layered above, or does the account replace it? Replacing it is a breaking change for
  the phone.
- **What gates `admin.html`.** Operator and owner may be the same person on a self-hosted box and
  are *not* the same role. §1.3 of the redesign plan already says the relationship audit log is
  owner-readable and not the operator's.
- **Recovery.** An owner who loses the credential must not be locked out of their own server, and
  the recovery path must not become a second way in. `RecoveryRoutes.kt` exists; reuse rather than
  invent.

**Explicitly not in scope:** orgs, roles, RBAC, multi-tenant. `COMPANION_ACCESS_CONTROL.md` is a
specification, not the running system, and building the org model by drawing its screens is the
mistake that doc already warns against.

### 4.3 — Signed requests *(the highest-value change for goal A)*

Per §3.4. Replaces the reusable bearer token on the wire with a per-request signature, which makes
the ordinary home LAN deployment safe by default rather than safe-if-configured. Additive, so the
existing phone build keeps working during migration. No new dependency on either side.

Do this **before** QR pairing: pairing should establish a signing key, not hand over a token, and
building the QR first would mean building the wrong thing and then changing it.

**Acceptance.** A captured request cannot be replayed or modified. A wire observer holding a full
capture cannot mint a request for an unobserved path. Bearer-token auth still works until the
phone-side change ships.

### 4.4 — QR pairing *(after 4.2 and 4.3)*

Per §3.2. Reuses `QrEncoder.kt` and `pairing.ts`. Needs a phone-side scanner — a camera permission
the foss flavour must be able to decline, so check that flavour's permission set before designing
it, since "no INTERNET in foss" is already a CI-enforced property and camera should get the same
scrutiny.

### 4.5 — Desktop-strength features

The maintainer: *"things that make sense for the user to use on the computer"* — not parity.

Candidates, in rough value order:
1. **Print / save the PDF report from the browser.** The generator today is Android-only
   (`PdfReportGenerator.kt`). The desktop has the big screen and the printer. This is also the one
   thing nobody has ever verified visually — see §6.
2. **Expanded views** — the Sky, the calendar and the client record on a large screen, where they
   have room to be legible.
3. Anything else that is genuinely better with a keyboard and a monitor.

---

## 5. Standing constraints (do not re-litigate)

- **No location features of any kind.** Not geo-IP, not a map, not country lookup. Absolute.
- **No LLM/AI inference.** Every prompt is a premade constant written by a human.
- **No green, no success token, no compliance score, no percentage, no streak, no grade.** Enforced
  tree-wide by `invariants.tree.test.ts`.
- **A gap in someone's data is never drawn as a failure.**
- **Photos are stripped of everything but the picture.**
- **Only semantic design tokens**; never a `--c-*` primitive; no hardcoded colour.
- **Logging must never carry content.** The server is a zero-knowledge relay; `detail` is a closed
  set of typed values so `detail["body"] = requestBody` does not compile.

---

## 6. Only a device can settle these

Unchanged and still open:

1. **No PDF has ever been rendered and looked at.** §4.4 item 1 would finally exercise it.
2. **No device has run the migration tests.** CI compiles the instrumented tests and never runs
   them. The chain is now 1 → 17 and includes the first `ON DELETE CASCADE` this database has had.
3. **No one has seen the Sky render.** Its geometry is arithmetic that has only ever been tested as
   arithmetic.

---

## 7. Open questions for the maintainer

1. ~~**§3.3** — HTTPS required for QR pairing, or LAN-with-warning by default?~~ **Resolved:** LAN
   is first-class; §3.4 makes it safe with request signing rather than warning about it. The
   remaining sub-question is whether the HTTPS gate is still needed at all once signing exists.
2. **§4.2** — does the owner account *replace* the bearer token (breaking change for the phone) or
   sit above it?
3. **§4.4** — is browser PDF printing the right first desktop feature, or is there something you
   reach for more often?
4. Should the personal (A) and clinician (B) products eventually be **separate deployments**, not
   just separate pages? A person who never has a therapist currently runs all of B's code.
5. **§3.6.4 / §3.7.5** — the phone question narrows but does not go away. A PAKE beats the
   read-aloud SAS on every realistic threat, but neither survives a server that serves malicious
   JavaScript to the owner's browser, and only moving the owner's half to the APK does. So 4.0a is
   shippable without the phone, and 4.0b is the answer to the worst case. Is that split accepted, or
   should the phone gate pairing from the start? The gating question underneath is §3.7.6 — whether
   the missing lazysodium binding is cheap.
6. **§3.6.1** — when a therapist Leaves, should their previously delivered shares be revoked
   automatically, or left alone until the owner decides? Auto-revoking is tidier; it also lets a
   stolen therapist credential trigger revocation of the owner's own material, which is why the
   table currently says *never*. Leaving them is the safer default and the messier one.
7. **§3.7.3** — what shape is the pairing code? It has to be read over a phone line without
   ambiguity and typed without a keyboard fight. Candidates: four words from a short list (longest
   to say, easiest to get right, no case or digit confusion), or 8–10 characters from a
   confusable-free alphabet like Crockford base32. The PAKE makes either strong enough; this is
   purely a question about the human moment, and the maintainer is better placed to answer it than
   the threat model is.
8. **Post-quantum** is explicitly *not now.* There is early CFRG work on hybrid PQ PAKEs
   (`draft-vos-cfrg-pqpake`), but it is a long way from settled and nothing in this threat model
   justifies tracking a moving draft. Noted so the omission is a decision rather than an oversight.
