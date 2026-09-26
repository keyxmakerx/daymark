# Daymark Companion — UX and copy rules

How the Companion's web consoles behave and what they say: the owner console, the clinician console,
the practice console and the server console, each named for who uses it (#310) and all built from
`companion/web/`. Every sentence a person reads there is fixed copy written by a person and held as
a constant — `pairing/copy.ts`, `owner/sharing.ts`, `therapist/leave.ts`, `practice/copy.ts` and
their siblings — so a test can read it and a reviewer can find it (CLAUDE.md §0). Visual rules:
[COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md). Threat model:
[COMPANION_SECURITY.md](COMPANION_SECURITY.md). Pairing:
[COMPANION_PAIRING.md](COMPANION_PAIRING.md).

Section numbers are kept from the earlier, longer design because code cites them, so the gaps are
deliberate: §2–§6, §8 and §13–§14 were screen layouts and a build list for a product that has since
been built differently. The old text is
[at the pre-consolidation commit](https://github.com/keyxmakerx/daymark/blob/968638594f10f6a4424415f8a5c14fd8eb4aaa00/docs/COMPANION_UX.md?plain=1).

## 1. Design principles

1. **A served page is the convenient path, never the strongest one.** The consoles run in a browser
   the server supplies. The owner console reads the owner's locked key from their server with their access
   token, opens it in the browser, and signs and seals shares there (`owner/OwnerUnlock.svelte`); the clinician portal makes and keeps its keys in
   the browser. So no surface claims a guarantee a page cannot make about itself
   ([COMPANION_SECURITY.md](COMPANION_SECURITY.md) R5), and the owner's unlock, sync set-up and
   pairing screens and the clinician's sign-in and invitation screens carry the fixed lower-assurance
   line.
2. **Honesty is a feature, not fine print.** Every limit the security model admits has a plain
   sentence at the moment it matters: the revoke caveat at the revoke click (§10.1), "it does not
   reach copies" when a clinician leaves (§7.7). No page polices itself. Trust in a page comes only
   from code the server does not deliver, or from the operator checking what they run against a
   signed release (not built: #241), never from what the page reports about itself — the image
   digest on the sign-in screen included (§10.4, #222).
3. **Non-diagnostic, everywhere.** Every score, band, plan and self-check surface carries fixed,
   client-rendered "a self-check, not a diagnosis" framing, and the honesty gate refuses an instrument
   definition without it. No risk verdicts, no cut-offs, no automatic escalation; a correlation is
   association, never cause.
4. **Consent is explicit, granular, time-limited and revocable.** No pre-checked boxes, no "select
   all", no dimmed "decline", no urgency, no "your clinician is waiting". The safe default is to
   share less, for less time (§9).
5. **Every surface says what it is for.** The owner console shares, pairs, opens a backup and runs
   self-checks. It is a desk-side complement to the phone (#247): printing first, then bigger
   read-only views, and no view grades a day. Writing and the Sky stay on the phone. Not built:
   printing or saving the report from the browser, #334; a month calendar of the person's own
   entries, #335; journal search, sleep trends and the dashboard's other views, #245.
6. **Nothing from anywhere else.** No CDNs, remote fonts, analytics or third-party origins; the page
   may talk only to the server that served it ([COMPANION_SECURITY.md](COMPANION_SECURITY.md) §6).

## 7. Key flows

Only flows that are built and carry fixed copy are kept here. Not built: limiting a share to a date
range, leaving out single records and previewing it before it goes: #225; receiving, checking and
accepting or declining a game plan in the owner console: #231.

### 7.6 Revoking access (owner)

1. While anything is published to a clinician, the sharing strip (§10.1) sits on every owner screen.
   Its **Revoke** button is plain: pressing it only opens a question.
2. The confirm opens under the strip: *"Revoke sharing with {name}?"*, then the standing caveat as
   its first body line — *"Revoking does not un-send what was already read."* — then *"{name} stops
   receiving new entries as soon as you confirm."* Buttons: **Keep sharing** (plain) and **Revoke**
   (the one clay element).
3. Confirming withdraws the whole share. The server marks every version revoked, deletes the stored
   copies and writes a `share.revoke` row to the relationship's audit log. If some copies could not
   be deleted, the strip is replaced by a warning that says so with the count — never a clean
   "withdrawn".
4. Turning a grant off in the grant manager carries the same caveat, as the last words of its
   paragraph. Nothing follows it: no step reaches back to what was already read (#320).
5. The cut-off for future shares that does not rely on the server's honesty is re-pairing: a fresh
   invitation and code. When a clinician comes back with new keys, the owner sees *"Replace the keys
   held for {name}"*, whose third paragraph says replacing does not reach what was already sealed to
   the old keys; the choices are **Replace and approve** and **Not now**.

### 7.7 Leaving a relationship (clinician)

The mirror of §7.6, and deliberately not its twin: the two acts differ in what they reach, and so in
what they may claim.

1. **Allowed tab → foot of the panel → "Leave this relationship"** — a plain hairline control on
   chrome. Not beside **Log out**: two adjacent controls that both end a session, one of them
   permanently, is a misclick with no recovery. No passphrase re-entry: the passphrase protects the
   key record, and the record is exactly what is being put down.
2. A confirm whose **order is the decision**:

   | # | Says | Why here |
   |---|---|---|
   | 1 | *"Your sign-in for this person closes as soon as you confirm, on every device, and it cannot be reopened. There is no way back in without a fresh invitation from them."* | The load-bearing fact, and the one a clinician may not have. |
   | 2 | *"Nothing of theirs changes. Their entries, what they shared, and their record of sharing it all stay exactly as they are."* | Reassurance, so second: no clinician expects leaving to delete a patient's journal, so leading with it answers a question they were not asking. |
   | 3 | *"It does not reach copies. Anything you exported, printed or wrote down — including a saved copy of your key record, and anything you already opened — is still wherever you put it."* | The product hands out that record on purpose, so "cannot be opened again" holds only if no copy exists. |
   | 4 | *"If they do not know yet, tell them yourself: nothing here will send them a message."* | Professional conduct. A reminder, never an instruction. |

3. **Keep access** / **Leave**. Leave is the only clay element on the surface. Nothing reads as
   failure, alarm, congratulation or punishment: putting down access is an ordinary professional act.
4. The person lands on the sign-in screen with one flat sentence saying what happened. If the
   sign-out could not be confirmed it says that instead, without alarm — the door is already shut.
5. It **names nobody**. This console does not hold the patient's name and no route would carry one;
   the confirm says "them" throughout.

**The revoke caveat is not on this surface** — the one place where turning access off does not carry
it. From this side no reader is being cut off and nothing is being un-sent; row 3 is the honest
mirror (`LEAVE_DOES_NOT_REACH_COPIES`). The exemption is checked, with its reason, in
`companion/web/src/lib/components/owner/revokeCaveat.test.ts`. Copy: `therapist/leave.ts`.

### 7.8 The owner's side of somebody else leaving

No message is sent. Three surfaces carry the fact where it can still be acted on:

| Surface | Copy | Treatment |
|---|---|---|
| Sharing strip (every owner screen) | *"{Name} ended their access on {date}. Nothing you send now would be read."* under an **ACCESS ENDED** label | Chrome with the same indigo rule as the live strip, never clay: a clinician's ordinary decision is none of clay's meanings, and an alarm would read as an accusation nobody made. It **replaces** the "Sharing real entries with…" line, because both cannot be true at once. Revoke stays while anything is still published to them. |
| Seal & publish share | *"Nothing was sealed or sent. {Name} ended their access on {date}, so nothing sent now would be read. You can revoke what is still published to them, or invite them again if they are coming back."* | A refusal, so clay. Checked before the clinician's keys are recorded or anything is sealed; it names the consequence and the two ways out, and no cause the server cannot know. A failed check is not treated as an ending. |
| Access log | *Your clinician · Ended their access to what you share* | A line like any other. Not "left" (a story about why, which nothing here knows) and not "revoked" (the owner's own word for their own act). |

### 7.9 Removing a member from a practice (admin)

The confirm corrects the assumption behind the click: *"Removal ends their standing in this practice.
It does not end any patient's relationship with them. Only the patient can do that, from their own
console — or the clinician themselves, by leaving the relationship from theirs."*

That is the fired-clinician case, and it is copy rather than mechanism on purpose: a practice has no
standing over a patient's relationship, and giving it one would be the practice reaching into the
thing the access model refuses. `docs/COMPANION_THERAPIST.md` §9a states it in full.

### 7.10 The owner's key (owner)

The owner console's door and the Recovery code screen read what this server holds, with the server
address and the access token, and act on one of three answers. Nothing: *"This server holds no key
yet. …"*, and **Make my key** makes it, with the passphrase typed twice. What the passphrase needs
and no recovery code: before anything is typed, *"Your passphrase is tried on the newest snapshot
this server stores. If the passphrase does not open that snapshot, nothing is stored."*, and **Add a
recovery code**. A locked key: open it with the passphrase or, instead, the recovery code.

The recovery code is shown once, with exactly one line directly above it, on screen only — *"Once you
leave this page, it cannot be shown again. Write it down before you go on."* — and everything else
about it after it; then two of its groups are typed back. Every message that asks for a read has
**Read what this server holds** directly under it. A key the server accepted but could not hand back
keeps its code on screen, with *"The server accepted your key, but it could not be read back to check
just now. Write your recovery code down, then use Read what this server holds to check it."* A new
passphrase set with the recovery code says, in a callout's body and never its heading, *"The key
itself did not change. This server now hands out only the lock made with the new passphrase. A backup
of the server taken before now still holds the old lock, and the old passphrase still opens that.
This console cannot yet replace the key itself."* — never "your old passphrase no longer works".

The words: key; lock, locked under (never copy, slot or key parameters); passphrase; recovery code
(bare "code" only in a tab's name); access token; this server. Tabs by their labels (*Get a code*,
*Use a code*). A wait on the key derivation ends *"— this takes a few seconds"*. Replacing a recovery
code is not built: #407.

## 9. Consent and sharing (no dark patterns)

The owner decides; the interface makes the safe choice the easy one.

### 9.1 Rules

| Rule | Why |
|---|---|
| No pre-checked share toggles and no "select all": every record type is opted into. | Consent is granular and deliberate. |
| Default to less. | Minimisation is the default, not the exception. |
| Declining is never smaller, dimmer or further away than accepting, and the keep-option names what not confirming does ("Keep sharing", "Keep access"). | No dark-pattern asymmetry. |
| No urgency and no social pressure: never "your clinician is waiting", no countdown to create a share, no nagging. | Consent must be unpressured. |
| The person sees what will go before it goes. | What I see is what they get. |
| Expiry is required and visible; nothing auto-renews or silently extends. | A time limit is real only if it cannot drift. |
| Revoke is always one click from the active share, never buried (§10.1). | Revocation is reachable. |
| Honest limits are stated at the point of consent, not in a document. | No overclaiming. |
| The self-harm item is absent by construction, never a toggle. | It cannot be shared by mistake. |

As built (`owner/ShareBuilder.svelte`): the builder opens by saying what a share is — *"A share is
access. {Name} can read what you choose here until the date you set, or until you stop it."* — where
a report says it is a copy handed over (#305, #337), then states the floor: *"Self-checks are reduced
to scores and bands only — never raw answers. Your own words go only if you switch them on below,
whole, never trimmed."* Four record types — self-checks, moods, journal, sleep — all unchecked, each
with its count; *"Include my own words (mood notes and journal text)"*, unchecked, so mood notes and
journal text go only when it is ticked, and then whole; self-checks go as scores and bands only; an
end of 1–90 days, 14 by default (decided in #228), with the date beside the field: *"Ends on {date}.
The server then deletes its copy. Anything read before then has already been seen."* The share is
signed and sealed to the clinician's pinned key in the browser. No instrument in the Companion has a
self-harm item — the honesty gate refuses one. By the same decision a new share covers only the last
30 days; the code still offers everything. Not built: the 30-day window, #225.

### 9.2 The consent screen

Not built: #225. Before anything is sealed, it states in plain words what is going (counts per type
and the window, "scores and bands only — no individual answers"), to whom, and the date access ends;
then, with **Cancel** and **Share this** equally weighted:

- *"You're sending real entries to another person. Once they open it, you can't un-see it for
  them."*
- *"You can revoke anytime. That stops future access through an honest server."*
- *"Your server can see that a share exists, roughly how big it is and when it was sent — not what is in
  it. It cannot hide that you share with someone."*

It says what a share is in the share builder's own words (#337), never by likening it to a report,
which is a copy handed over (#305).

## 10. Privacy microcopy and trust signals

### 10.1 The trust strip and the sharing strip

**A served page is never painted green, whatever it claims about itself.** Served JavaScript is
lower-assurance regardless of network state, and a page handling a passphrase cannot give a
zero-knowledge guarantee, so green would overclaim — on the offline viewer and in every other state.
[COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md) §2.3.2 generalises this into the ban on any
success colour.

**The trust strip** (`components/TrustBar.svelte`) sits on every surface of the owner shell and states
the posture of the surface you are on (`trust/posture.ts` decides which):

| Posture | Where | Says |
|---|---|---|
| `local` | every other tab: opening a backup file, self-checks, the tool builder, the practice panel | *"Meant to run offline. This tab works on what is already in this browser and sends nothing — but a page cannot prove that about itself, and nothing yet lets you check this build against a published value."* |
| `setup` | the first-run screen while it reads the server's configuration | *"This screen reads your server's configuration. It asks one thing — whether this deployment already names what this machine is for — and sends nothing about you or your journal. Once that question has an answer here, this page stops asking on load."* |
| `sync` | connecting to sync | *"This tab talks to your server. Your passphrase and the decrypted entries stay in this browser; what crosses the network is ciphertext your server cannot read. It can still see that you synced, and when."* |
| `account` | the owner console, account recovery | *"This tab sends data to your server. Account actions send identifiers, and recovery sends the email address you type. A share you seal leaves this browser too, as ciphertext sealed to one clinician's pinned key: the server can see that it exists and how big it is, not what is in it. Your passphrase stays in this browser."* |

- It is plain grey-blue chrome (`--chrome`), never a mood wash, never green, and it has no amber
  state: amber is warn severity, and on the sync tab the strip sits directly above the lower-assurance
  banner, which is a real warning and must stay louder than its frame.
- Its posture comes from the surface, never from `navigator.onLine`: being offline this second says
  nothing about whether a page would call out.
- It never claims the page "makes no network requests" or that data "never leaves this device".
- Enforced by `components/trustbar.test.ts`.
- Not built: a published value to check this build against, which the `local` sentence would then
  point at: #144.

**The sharing strip** (`owner/SharingStrip.svelte`) is the owner's standing notice that someone can
read what they share. It is rendered per relationship above the tab content, so changing tab does not
make a standing fact disappear, and it cannot be dismissed — a share is not an event that happened
once, and dismissal is forgetting.

- Chrome ground with a 3px indigo rule, the label **SHARING**, and *"Sharing real entries with {name}
  since {date}."* The ring before the label is `aria-hidden`; the word carries the meaning. A
  consented, ongoing share is none of clay's four meanings — needs a human, overdue, refused,
  destructive — and an alarm that is always on is not an alarm.
- `{date}` is the day sharing began (the earliest version in the share lineage), not the day it was
  accepted: nothing in the product records acceptance.
- **Revoke** on the strip is plain (`--border-strong`, `--ink-text`), because pressing it only opens a
  question. Clay appears exactly once in the feature: the confirm button that ends the share.
- The confirm's first body line, directly above the buttons, is `REVOKE_CAVEAT`, verbatim: **"Revoking
  does not un-send what was already read."** The keep-button says *Keep sharing*, not *Cancel*:
  cancel names the dialog, keep sharing names what not confirming does.
- After confirming, the strip collapses and nothing replaces it — unless copies could not be deleted,
  which is said with the count (§7.6). When the clinician has ended their access, the strip says that
  instead (§7.8).

Enforced by `owner/sharing.test.ts` (chrome with an indigo rule, clay spent once, the caveat directly
above the buttons, not dismissable) and by `owner/revokeCaveat.test.ts`, which requires the one shared
constant — never a paraphrase — wherever access is revoked, forgotten or turned off (the grant manager,
the pin record, `therapist/pinStore.ts`), and checks the one exemption: the clinician's own leave
(§7.7).

### 10.2 What the server can and cannot see

Not built: an owner-side panel, reachable from every owner screen, that says plainly what the server
can and cannot see and the limits that follow from it: #249. The clinician sign-in already states its
half (§10.4); the owner's panel must not contradict it, nor the honest limits in
[COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md).

### 10.3 Microcopy patterns

- **Say who cannot read it.** "Encrypted on your device" and "the server can't read this", never
  "military-grade".
- **No absolute claims.** Never "fully private", "totally secure", "cannot be hidden" or "fully
  revocable"; scope every claim to the threat model.
- **Fixed, client-rendered, never server-supplied.** The non-diagnostic and lower-assurance copy is
  baked into the bundle so a hostile server cannot strip it, and it is pinned verbatim
  (COMPANION_DESIGN_SYSTEM.md §2.3.5). Restyle the container, never the prose.
- **Association, not cause** on every correlation.
- **Nothing automatic.** Nothing renews, escalates or sends without the person pressing something.
- **No green, success, tick, score, streak or congratulation.** What "done" looks like is solid ink and
  a sentence in the past tense.
- **A refusal names a consequence, never a cause the system cannot know.** "The reply didn't match
  this code" is honest; "someone is attacking you" is a guess the code cannot support, and "you typed
  it wrong" blames the wrong person.
- **Say what and when, never why.** "{Name} ended their access on {date}": the server does not know
  why, and a console that guessed would be narrating somebody's life.
- **A failure says what did not happen**: "Nothing has changed.", "Nothing was sealed or sent."
- **Never echo a credential back** into a message.
- **Name what the button does**: *Keep sharing*, *Keep access*, *Not now* — not *Cancel*.

### 10.4 The clinician's sign-in: a contract and a digest

Signing in opens another person's record, so the sign-in screen (`therapist/SignInScreen.svelte`,
copy and checks in `therapist/signIn.ts`) is two columns: the credentials, and a contract in four fixed
sections — *What you are being trusted with*, *What the server can see*, *What the server cannot see*,
*While you are signed in*. The contract is rendered whole, with no "show more", and its tests fail if a
section loses its only clause, if a clause makes an assurance claim, or if one paraphrases the
lower-assurance banner, which renders unconditionally and is retyped nowhere.

*While you are signed in* says what the automatic lock drops and what it cannot reach (#262): the
keys go after 15 minutes without activity or 8 hours in all, but a browser extension can read what
is on screen and a screenshot can keep it, which matters more on a computer other people use. After
the automatic lock, and never after **Log out**, the sign-in screen says *"This session ended.
Sessions end after 15 minutes without activity, or 8 hours in all, and the keys are dropped from this
browser's memory. Nothing else changed."* above the fields, in plain ink, from a flag held in memory
only. The 8 hours is the server's absolute session limit, `DAYMARK_SESSION_ABSOLUTE_SECONDS` at its
default; the page locks when the expiry the server returned at sign-in passes.

The digest of the image serving the page sits above the credential fields, whole (a truncated hash
cannot be compared), in mono, copyable in one click. It is the page's own report: information for
whoever runs the server, never a control or a source of trust, because a changed page can print the
right value (#222). The page never compares it and never says it is right — a tampered page would
report that it matched. Trust in a page comes only from code the server does not deliver, or from
the operator checking what they run against a signed release (not built: #241). "Copied" is a word,
not a tick. The contract says so — *"The digest shown here is this page's own report of what it is
running; whoever runs the server can compare it against the release they pulled, and nothing on this
screen can."* — and no banner on any console asks a person to verify a digest (#320).

## 11. Empty, loading, error and refusal states

- A list with nothing in it says what would appear there and how (`ui/EmptyState.svelte`). A gap in
  someone's data is never drawn as a failure.
- A slow step says that it is slow (*"Opening your key — this takes a few seconds"*), never a bare
  spinner.
- Error bodies are generic and non-enumerating: no stack traces, no difference between "no such user"
  and "wrong password". Lockouts and rate limits are applied by the server per credential and per
  address, and never leak through the interface.
- A refusal is decided before anything goes out, so its first sentence is true.

| State | Surface | Copy |
|---|---|---|
| They left | Owner, seal & publish | *"Nothing was sealed or sent. {Name} ended their access on {date}…"* (§7.8) |
| You left this relationship | Clinician sign-in | The server answers 410 *"this relationship was ended"*, and only after a correct code; every other refusal on that route is one identical 401, so nobody learns it by guessing a username. |
| Revoke failed | Owner, sharing strip | *"Sharing could not be ended. Nothing has changed."* |
| Sharing check failed | Owner, sharing strip | *"This console could not check whether sharing is active. Nothing has changed."* A failed check never reads as "not sharing". |
| Copies left behind | Owner, after revoking | *"Sharing is ended and nothing further will be delivered. {n} copies of what was already shared could not be removed from the server, so they are still on its disk. Whoever runs the server can remove them."* |
| Leave refused | Clinician | *"Nothing has changed. Your keys are still in this browser, you are still signed in, and nothing was sent to the person who invited you. You can try again."* |
| An older copy of a share | Clinician, shared data | *"This copy was sealed before one you have already opened, so it stays closed. Ask for a fresh one."* It says nothing about the server, which the portal cannot know about. |
| An item the server no longer keeps | Owner, assignment inbox | *"Sent by {name} on {date}. The server keeps items for 90 days."* One line in ink among the rest: the normal end of an item, never an error. |
| Set-up refused | Owner, door and Get a code | Each ends *"…nothing has been stored."* (§7.10) |
| The server changed under a set-up | Owner, door and Get a code | *"What the server holds changed after it was read here, so nothing from here was stored. What it holds now is below."* |
| Read-back did not open | Owner, door and Get a code | *"The server accepted the new key, but what it handed back did not open to the same key, so the recovery code is not shown. Your snapshots are unchanged. …"* It does not say nothing was stored; the read button is under it |
| Read-back could not be read | Owner, door and Get a code | *"The server accepted your key, but it could not be read back to check just now. …"* The recovery code stays on screen |
| A missing lock | Owner, door | *"This server holds no recovery code lock for your key, so a recovery code cannot open it. Use your passphrase."*, or the same for a passphrase |
| Did not open | Owner, door | *"That did not open the key this server holds. Nothing has changed. Check what you typed and try again."* |
| Snapshots and no key | `pnpm push`, set-up | *"This server stores snapshots but not what is needed to open them. A new key would not open those snapshots, so none was made, and nothing has been stored."* |
| Key changed before upload | `pnpm push` | *"The snapshot was not sent. The key this server holds changed while the snapshot was being encrypted, and this passphrase does not open it to the key the snapshot was encrypted under."* |

## 12. Accessibility, language, motion

- **Keyboard.** Every control is a real button, link or input. The focus ring is `:focus-visible` in
  `--focus-ring` (indigo, never the mood ramp). Help beside a field is a button that toggles a panel
  (`ui/FieldHelp.svelte`), never a hover tooltip: hover does not exist on touch and cannot be reached
  from the keyboard.
- **Colour is never the only signal.** The written word is always present and marks are
  `aria-hidden`; `Callout` adds a visually hidden severity prefix, and `ProvenanceBadge` and
  `StatusPill` differ by form as well as hue.
- **Motion.** The global `prefers-reduced-motion` block in `app.css` neutralises every transition.
  Nothing on a consent or security screen moves in a way that could read as pressure.
- **Language.** English only, for now (#246). Every string is a constant in a module, not a
  catalogue. A language is added only when a named human translator and a separate named human
  reviewer, both fluent in English and that language, have checked every string. Machine
  translation is never used, and the questionnaires appear only in their official published
  translations.
- Not built: a text or table equivalent for every chart: #259; a rendered accessibility check and
  computed contrast in CI: #254; a high-contrast mode: #253.
