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

1. **See metadata.** Snapshot sizes, timing, source IPs, how often someone syncs, how many
   relationships exist. Not content — but "they logged nothing for three weeks" is inferable from
   timing alone, and on this product that is sensitive.
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

## 4. The work, in order

### 4.0 — The invite acceptance page *(now first: without it, goal B does not work at all)*

Per §3.5.1–3.5.3. A page at `/portal/invite` that redeems the link the owner already sends, and
replaces the nine-field sign-in with one code and one passphrase.

**Acceptance.** A therapist who is sent an invite link can reach a working portal without anyone
reading source, opening a database, or pasting base64. The SAS confirmation is shown on both sides
and refusing it aborts the pairing.

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
