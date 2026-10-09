# Decisions

The product decisions that shape Daymark: what was decided, why, and what would change it. Code
cites these by number (`DECISIONS.md §D6`), so the numbers are permanent. A decision that is
revisited keeps its number and gains a paragraph saying what changed.

**How a new decision gets made.** A choice that belongs to the maintainer is a GitHub issue
labelled `needs-decision` ([open ones](https://github.com/keyxmakerx/daymark/issues?q=is%3Aopen+label%3Aneeds-decision)).
The answer is the closing comment. If the answer sets a lasting rule for the product, it is added
here as the next D-number, linking the issue.

The evidence behind D1–D6 came from a clinician's feedback session and a literature review in
August 2026. Both documents were retired in the 2026-09-23 consolidation and are kept in git:
[clinician feedback](https://github.com/keyxmakerx/daymark/blob/968638594f10f6a4424415f8a5c14fd8eb4aaa00/docs/CLINICIAN_FEEDBACK.md),
[evidence review](https://github.com/keyxmakerx/daymark/blob/968638594f10f6a4424415f8a5c14fd8eb4aaa00/docs/EVIDENCE_REVIEW_2026-08.md).

---

## D1. A rules engine runs every check-in, and it is never a recommender

**Revisited 2026-10-02 (#159 reopened and reversed).** D1 first made the scheduling logic a narrow
arbiter that answered one question, *may a feature interrupt right now?*, and gated almost nothing.
The maintainer's decision is that the engine should be dynamic and interactive and should **run all
of the app's check-ins**: the dailies, the reminders and the notifications. It decides which ones
happen, when, how often, and which premade line each one uses. The number D1 and the rules below
stay; what changed is the size of the job.

**Decision.**
- **Rules and fixed human-written lines, no AI.** Every line the engine can say was written by a
  person ahead of time, with the person's own numbers slotted in. "Smart" means rules over the
  person's own data and nothing else.
- **It is not a therapist and never provides support itself.** It exists to make the app more
  welcoming and easier to stick with, including for people who forget or lose track of time. Support
  stays where it already lives: the safety plan, the offline resources, a person's own clinician.
- **Dynamic by default, settings never required.** The engine works out a sensible rhythm on its own.
  Every choice it makes also exists as a setting, and nobody has to open them.
- **The rhythm fits the thing tracked.** Once a day for mood. "Log it when it happens" for things
  like anger. Several quick check-ins through the day for attention-related tracking. Or a rhythm a
  therapist sets in the creator.
- **It may move a check-in by itself, and says so.** When it does, it sends a notification saying
  what changed and why, with **Put it back**. A move is never silent and never final.
- **Suggestion logic stays inside each feature.** Features hand the engine a small, opaque
  description of a check-in (its kind, the rhythm it wants, the lines it may use). They do not push
  domain state into it, and it does not learn what a mood is. If a rule needs a feature's data, the
  rule belongs in the feature.

**What stays true from the first D1.** `stats/InterruptionBudget.kt` imports nothing at all, so "it
knows none of the features" is enforced by the compiler. It keeps a separate budget per `Kind`. Its
ledger is the `offer_records` table (`data/entity/OfferRecord.kt`): three columns and no fourth, rows
never updated, so nothing can re-score after the fact how an offer landed. The entry editor still asks
it whether the support space may take the person over after a save. `Kind.COMPANION` and
`Kind.ASSIGNMENT` have budgets and are called by nothing yet. Not built: #272.

**What changes in the code.** Reminders were *recorded, not rationed*: every firing writes a ledger
line and `notifications/ReminderScheduler.kt` never consults the budget, because an earlier version
that rationed them collapsed a three-a-day schedule to once a week with no setting to turn it back
up. Under this decision the engine may move and space reminders, but only under the rules in D1a
(easing off, an explicit opt-in to repeat, and an announced, reversible move). That is also what
makes it safe: the old failure was a silent, one-way reduction, and this is neither silent nor
one-way. The timing rule `stats/TimingGrid.kt` and the twelve openers in `stats/PhrasePool.kt`, which
nothing called, are no longer dormant. They are the first parts the engine uses.

The invariant's tests, `InterruptionBudgetTest`, are property sweeps over kind, declared frequency,
ledger, standing stop and clock. Four sweeps, each catching what the others miss:
1. no input asks more than the person's own setting;
2. worse reception never shortens the gap;
3. worse reception never turns a no into a yes;
4. an inference may quieten the app, but only the person may silence it.

Add a fifth rather than relax any of these. The engine's own moves get the same treatment: a test
that no sequence of missed check-ins ever produces a more frequent or louder schedule.

### D1a. It reads its own reception, eases off when the person goes quiet, and infers no clinical state

The proposal was to let the engine work out whether someone is struggling, annoyed by the app, or
fine and not in the mood. All three call for **the same action, ask less**, so the engine needs one
variable, how its own check-ins are received, and not a state classifier.

**What it may know:** its own ledger (offered, accepted, dismissed, snoozed, "not now", "stop
asking") and what the person set up.

**What it must never do.**
1. **Infer clinical state.** It would be diagnosis by side effect, with no instrument, no validation
   and no consent, and worse than a screen because it is invisible. The field cannot do it reliably,
   and its best-known signal, reduced geographic mobility, uses location, which Daymark bans.
2. **Label or guess how the person feels.** Lines describe the app and the person's own entries,
   never a feeling.
3. **Mention a gap.** No line says how long it has been, or that anything was missed.
4. **Trigger crisis resources by itself.** The offline, person-editable resources are opened by the
   person. The engine never opens them and never decides someone needs them.

> **The invariant.** Going quiet never makes the app louder. Missed check-ins make the engine ease
> off: further and further apart, with no fixed limit, but never off by itself; only the person's
> own Stop asking turns a check-in off. No signal, in any combination, may make it ask more.

**Easing off is the person's to refuse.** For a check-in about the day, fewer reminders as answers
stop is right. For a medication it is backwards: the reminder would fade exactly when doses are
being missed. The engine cannot tell the two apart without reading what the person wrote, so when
someone turns check-ins on, a reminder or a tracker, Daymark asks once: *"Ease off if I'm not
answering"* or *"Keep reminding me at these times"*. Kept, the check-in never eases and never tries
a longer wait; it is never more than the person set either, so the invariant holds both ways. The
answer can be changed wherever the check-ins are set (`ui/components/KeepTimesChoice.kt`).

**Asking for more is the person's, never an inference.** The one way to get repeat reminders is an
explicit setting, *"nudge me again if I miss one"*, off until the person turns it on. The engine
may then repeat a check-in within the limits that setting states, and no further. It may quieten
itself, with no fixed limit; only the person may silence it, or turn it up.

That is the ethical guarantee and the engineering guarantee at once, and it is directly testable.

### D1b. There is no helper character: the engine has no persona

Daymark has no character, mascot or "presence" that talks to the person. Whatever Daymark says, it
says through the rules engine (D1, D1a), in fixed lines, as the app and not as someone. A personality
would invite the person to treat a set of rules as a relationship, and the rules are not one. An
earlier proposal for a conversational "little guy" was dropped by the maintainer on 2026-10-03.

The rules that proposal carried still bind every line the engine shows:
- **No free text** — the person picks from fixed choices, or writes in their own journal.
- **Reflect, never label** — *"you logged three harder days this week"* hands someone their own data
  back, while *"you seem depressed"* is a claim about them, which D1a forbids.
- **Never uninvited** — a line the person has turned off stays off, and turning it back on is a
  setting they find.
- **Never the crisis path** — the safety plan stays the person's own. Wherever the engine offers a
  set of choices about how someone is doing, the safety plan is one of them, offered and never
  opened on the person's behalf (D1a).

**As built:** a dialogue component and its content exist from the earlier proposal. No page mounts
it (`companion/web/src/lib/docs.test.ts` asserts that), and there is no phone surface. Whether it is
removed or its fixed lines are reused by the engine is #272. How it works:
`docs/COMPANION_DIALOGUE.md`.

---

## D2. The arbiter gets no user-facing name

In code and docs it is a rules engine or decision engine. In the UI it has no name and no persona;
the setting that governs it reads *when Daymark asks*. It is plumbing, and its virtue is that it can
justify any decision in one sentence because it is rules. **It is never called "AI."** That word
promises opacity, and it carries a liability in mental-health software in particular.

Nothing else in the app has a persona either (D1b).

The clinician platform has no separate brand (#310). It is Daymark Companion, and each of its four
pages is named for who uses it: the owner console, the clinician console, the practice console and
the server console. "Heimdall" was rejected. A well-known self-hosted app owns it, and Heimdall's
defining attribute is seeing and hearing everything, which is backwards for a product whose pitch is
that it cannot see your data.

---

## D3. Affirmations ship as a clinician-prescribed compassion module

A clinician prescribes the module; it never switches itself on. Its content is **self-compassion and
values writing**, not positive self-statements. Self-compassion has the best evidence of the three,
and statement decks are the one option with no demonstrated benefit and a plausible harm mechanism.

**The rule that survives any single study:**

> Never require the person to assert a positive claim about themselves that they do not believe.

There is **no threshold gate**. Measuring self-esteem in order to change what the app does would turn
the instrument into a screen, and every catalogue tool declares `noScreeningFlag: true`. The switch is
a human's: the clinician's capability grant. This is not a crisis tool; the safety plan stays a
separate surface.

**As built:** two unscored, assignable catalogue exercises (`catalog/compassion.ts` in
`companion/web`).

---

## D4. Photos are in scope, stripped by re-encoding

Deleting EXIF tags is not enough: metadata also hides in thumbnails, XMP, IPTC and maker notes. So a
photo is decoded to a bitmap and re-encoded at a capped resolution. That structurally cannot carry any
of it. The ban on location data is unchanged; it is why the stripping must be verifiable.

**As built:** `data/ImageStrip.kt` and `data/PhotoStore.kt`; photos attach to entries.

The picture itself can still show a place (a street sign, a house number). That needs a one-time
sentence to the person, and it is not built yet: #188. The two device checks are in #147.

---

## D5. Goals become projects: containers for concrete steps

Direction, from the evidence:

- **Implementation intentions attach to the next concrete action**, not to the project. Each step
  can carry an optional "When…" line in the person's own words, and the step itself is the action
  (#184). The line is never a reminder or a deadline. Not built: #348.
- **Learning projects score on process, not completion.** No target, no percentage.
- **A project is a folder for steps**, not a standalone aspiration. Abstract goals are what people
  with depression already over-produce.
- **Abandoning is one tap, neutrally worded, never counted as failure,** and never shown to a
  clinician as a negative signal.
- **A declined suggestion is invisible to the clinician,** and suggestions are editable on acceptance.

**As built:** goals are habits (a weekly count) or projects with a steps board, "I reached this" and
archiving (`data/entity/Goal.kt`, `GoalStep.kt`). Not built: one-time and learning kinds (#285),
and clinician-suggested goals (#286).

---

## D6. Things we are deliberately not building

Recorded so they are not re-proposed as obvious wins.

| Not building | Why |
|---|---|
| **Streaks, points, badges, levels** | Gamification showed no effect as a moderator in the largest meta-analysis. Streaks turn the log itself into the goal and amplify self-blame after a break. Continuity, where shown, is non-consecutive ("12 of the last 30 days") with nothing to break. Done everywhere: the achievements screen, its badges and the streak rules are deleted (#107). |
| **Throughput metrics on the activity board** | Completion does not predict symptom improvement. No velocity, burndown, percentage rings or count badges. |
| **Auto-generated insights from mood and activity pairing** | "Walking lifts your mood" is not supported by daily-diary data, and a wrong inference is a self-blame vector. Show them side by side and let the person conclude. |
| **Note excerpts in the Companion** | Non-disclosure in therapy is normal and driven by the fear of being seen. This protects a place to write without an audience. |
| **Inferring reminder quality from app opens** | A notification alone makes opening far likelier. That metric moves with nothing underneath improving. The signal must be declared by the person. |
| **Lapse-referencing notifications** | "You haven't written in 3 days" is the only documented harm signal in the notification literature. |
| **Sleep sensing by microphone, sonar or phone use** | An all-night microphone asks for more trust than a mood journal should. Snore detection misses quiet sleepers, and the classifier proposed for it was machine learning. A sleep window guessed from phone use is inferred, where the diary is declared (#212). |
| **A free-text chat box, or a helper character** | See D1b. |
| **Inferring clinical state from usage** | See D1a. |
| **Any signal that makes the arbiter ask more** | See D1a. |

---

## D7. The journal is encrypted at rest, with the key held by the phone

A random 32-byte data key encrypts the database **from first run, for everybody**, whether or not
they set a PIN. It is wrapped under a key in the phone's hardware keystore, so the file is bound to
the device. Nothing is asked of the person, and nothing can be forgotten.

**Why the key is not made from the PIN.** Changing a PIN would mean re-encrypting a journal while
the person stands in a settings screen. A forgotten PIN would also mean the entries are gone for good.
A person in distress forgetting six digits and losing a year of their journal is a harm the product
would be creating. So there is one key, made once, and what changes is the set of wrapped copies of it.

**Why the migration is copy-verify-swap.** The plaintext file was the only copy
(`android:allowBackup="false"`). The migration exports to a new file, then verifies row counts,
schema version and SQLite's integrity check, and only then deletes. A crash between the delete and
the rename is recognised and finished on the next launch. The final check lists the directory,
because `-wal`, `-shm` and `-journal` files hold rows in the clear.

| Rejected | Why |
|---|---|
| Keying SQLCipher from the PIN | A forgotten PIN becomes lost data. |
| Encrypting only once a PIN is set | It forces a migration at an arbitrary moment, and leaves most people with a plaintext file. |
| Recovery through a server or email | An escrow that can restore a journal is an escrow that can read one. |
| `setUserAuthenticationRequired(true)` | Changing the phone's screen lock would make the journal unopenable forever. |
| `setIsStrongBoxBacked(true)` | It throws on most devices, and it is no stronger against a storage image. |
| Refusing to open when migration fails | The original is intact at that point; stopping someone's journal is the worse outcome. |

**As built:** the data key, the keystore wrap, SQLCipher behind Room, the migration, and the screens
for a phone whose keystore lost the key. Those screens say what cannot be done, and destroy something
only if a person chooses to (`security/DataKeyStore.kt`, `data/JournalEncryptionMigration.kt`).

**Not covered:**
- Entry photos: #239.
- Exports: a backup, CSV or PDF is a plain file the person asked for (#236).

**Open, in #109:** the PIN wrap and the written-down recovery code are built and tested
(`security/DataKeyWraps.kt`, `security/RecoveryCode.kt`) but not armed. Arming them is a decision that
costs something. Reminders are read from the database by a cold process after a restart, and a journal
locked behind a PIN cannot be read that way. The PIN wrap's 420,000 PBKDF2 rounds have never been
timed on a phone (#147).

---

## D8. The report has four sides, each with one job

Density is wanted. The constraint is one job per side. The alert-fatigue finding is about elements
competing in the same glance, not about page count, so the flag keeps its own zone at the top of side 1.

| Side | Job | Contents |
|---|---|---|
| 1 · front | The glance | The one flag. Per-instrument plots with a usual-range band and sampling density. What ran, and what came back. |
| 2 · back | The detail | Every entry with its note. What was suggested and what happened to it. Goal and project progress. Coverage and gaps, never interpolated. |
| 3 · front | In their words | Journal entries the person included. **Off by default.** |
| 4 · back | For the conversation | Discussion prompts from the data. The provenance of every tool. Verification (QR and hash) at the bottom. |

**Side 3: the app proposes, the person disposes.** Software may nominate a representative set, and
must show it to the person, who adds, removes and confirms before anything is exported. It never
selects silently.

**Side 4: prompts, never recommendations.** A report that tells a clinician what to do is clinical
decision support, a regulated category that would change what the product legally is. Side 4 carries
observations and questions about one measure at a time ('All 6 "PHQ-9" results in this range came back in
the same band ("Mild"). Does that match how the weeks felt?'). It never sets one measure beside another: mood beside activity is
the insight D6 does not build, and the person reads the report too. It cites nothing and prescribes
nothing. Where evidence is citable, it is
psychoeducation for the person, in the app.

**As built:** `export/PdfReportGenerator.kt`, `export/ReportLayout.kt`, `stats/DiscussionPrompts.kt`.
Seeing the report before export: #198.
Source: [the August plan §1](https://github.com/keyxmakerx/daymark/blob/968638594f10f6a4424415f8a5c14fd8eb4aaa00/docs/PLAN_2026-08-NEXT.md?plain=1#L9-L58).

---

## D9. Daymark may grow into a clinical platform, in phases, behind a compliance gate

The personal app and the Companion are the foundation. Multi-role access, care teams and
HIPAA-readiness are a layer on top, never a rewrite. **Every tool declares what it is** — Validated,
Adapted, or Custom — so nobody is misled about which is which (`docs/PROVENANCE.md`).

The principles every clinical feature is judged against:
1. **The patient owns the keys.** The server cannot read plaintext.
2. **Roles gate actions; cryptography gates reading.** A hijacked admin account still cannot read.
3. **Minimum necessary** for every role.
4. **Consent roots at the patient.**
5. **Provenance, not authority.** A tool is trusted because it is labelled honestly.
6. **Usability is a security property.**
7. **HIPAA-ready is not "compliant."** A deployment plus an organisation is what is compliant.

**The compliance gate is non-negotiable.** Before any real patient's data is handled by a clinician
using Daymark, it needs an external HIPAA Security-Rule assessment and an independent audit of the
RBAC, key handling and recovery flows: #284. It also needs a clinician client the office's server
cannot change, and no patient typing their passphrase into a page the office serves (#222). Not
built: the clinician client (#319), and pairing and sharing from the phone (#174, #321). The
clinical layer's design is `docs/COMPANION_ACCESS_CONTROL.md`, and its work is tracked in #143.

**What the Companion is for (#288).** It is self-hosted: a person may run their own, and each office
runs its own behind its own reverse proxy. Daymark runs no service; a hosted one would need a
decision of its own. It is one product in three shapes (Solo, Paired and Practice), and each
server's shape is a setting that switches on only what that shape needs. An office may be one
clinician, or several clinicians with receptionists, an administrator, and doctors who assess and
refer. Every referral is a person's decision, never software's. No role, the administrator's
included, reaches anyone's credentials, keys or content. The old one-clinician scope lock is
retired, and the gate above still stands. Not built: each person's own credential on an office
server (#331), one sign-in per clinician (#314), and the admin console on an address of its own
(#323).

Source: [the July product direction](https://github.com/keyxmakerx/daymark/blob/968638594f10f6a4424415f8a5c14fd8eb4aaa00/docs/PRODUCT_DIRECTION.md).

## D10. A paired phone is the owner's journal device, not the operator's console

A phone never holds the owner's bearer token. It pairs through the owner console and signs each
request with a key of its own (#186, #189; SYNC_PROTOCOL.md §2.1 and §2.2). What it may do follows
one rule: **a phone reaches every owner route the token reaches, except the routes that manage phones,
make a practice, or change how the owner recovers**, the notification address and the key documents
among them. Those answer it 403 from one list in the code, and a route the gate cannot name is refused
to a phone, never opened to it. A phone that could add a phone would survive its own revocation; one
that could set the recovery address could have the console's token re-issued to whoever holds it; one
that could write the key document could leave the owner's passphrase opening nothing.

Settled with it (2026-09-26, on #186 and #189):
- **Device keys come before owner accounts**, each tied to the owner's one id from the start, so
  accounts (#324) take that id and no device row moves.
- **The key is made on the phone, not derived from the master**, so a stolen master is not also access
  to the server, and revoking a phone never touches the owner's pairing identity.
- **Only the phone signs.** The console keeps the token until #324 gives it a session.
- **Re-issuing the token disconnects every phone**, in the same transaction.
- **Pairing needs an https address**, and the phone enforces it. There is no setting to turn this off,
  because the phone cannot see a server setting and a switch set once for setup stays on for good.

## D11. Every star in the sky is a memory, and nothing in it is a reward or a verdict

The maintainer approved a redesign of the Sky on 2026-10-03; `docs/prototypes/sky-phone.html` shows
it and `docs/SKY.md` describes what is built. Built: every star a memory, the three forms, bands and
clusters, the opening and a new star's birth, zoom down to suns, constellations and their photo, and
the Key. Not built: the supernova, black and white holes, dark matter, the other objects, changing
the colours, "Reset my sky" and tracker objects, #449. Where the prototype and this decision
disagree, this decision governs the build.

**Decision.**
- **Every star is one memory.** There are no decorative stars; the space behind them is colour, gas
  and shadow. This reverses `docs/SKY.md` M1. A sparse stretch still never reads as a void, because
  time runs along a guide measured in memories, not days.
- **Not every sky has a river.** The seed picks the sky's form: a river on one of six courses, a run
  of galaxies (spiral, barred, elliptical, ring or irregular, each its own size), or an open sky with
  no line to follow. No form is the default, and skies come in different sizes.
- **Shape follows how the record was kept, and no shape is a reward.** Steady months can form a band,
  rarely, and at most two in a sky; on-and-off weeks gather into clusters. No card says "in a row".
- **A life event can be marked as hard, by the person only.** It is never asked, suggested or
  inferred. The star becomes a supernova that marks that day and nothing more; "hard" can be
  unmarked, and the opening never flies to it. This reverses `docs/SKY.md` §2.2.
- **Putting memories away is a passing event.** A black hole forms beside them, takes them in, closes
  and is gone; nothing marks where they were. Bringing them back opens a white hole. Memories tucked
  away to find again later become dark matter (#450).
- **Constellations are the person's own.** They draw and name them; the software still never groups
  stars (`docs/SKY.md` §3.1, reversed for drawing only). As their stars drift apart over the years,
  every constellation falls out of the live sky. Each is kept as a photo of the day it was drawn,
  which is the only way the sky goes back in time. A memory put away keeps its point only in that
  photo; a deleted one leaves nothing anywhere.
- **No two skies are alike.** A seed of the person's own shapes the form, the patterns, where
  everything sits and every colour. Colours can be changed for 30 days from first opening the sky,
  then settle. "Reset my sky" regrows it without touching memories or constellations. A backup
  carries the seed, the colours and that date.
- **Trackers appear only if the person chooses.** Each tracker has a "Show in my sky" switch. A shown
  tracker is an object of its own beside the memories, never among them, and more logs make it
  denser, never brighter.
- **The Key stays, and leads with "red only means old".** It lists only what is in that person's
  sky.
- **Any tap skips the opening.** With reduced motion, the sky opens still, on today.
- **Never**: a moon or land; anything framed as depression or as memories being eaten; a meteor
  shower on a day the person did not choose; a pulsar for a habit without a set time; device location
  for a place.

**Why.** It is the maintainer's design. The safety review of 2026-10-03 added the parts about
rewards, the supernova, the black hole closing, skipping the opening and the Key's first line. The
maintainer added the forms on 2026-10-04: a river in every sky would make them all alike.

---

## Decisions recorded in closed issues

Smaller decisions were made in GitHub issues and are recorded there:
- **#99:** the one sentence at every revoke click.
- **#100:** nobody resets a clinician's passphrase.
- **#101:** the clinician pins the owner on the same authority.
- **#103:** "yet" is banned from empty states.
- **#105:** how an active share is drawn.
- **#107:** streaks become "12 of the last 30 days".
- **#108:** the year review's two tiles.
- **#110:** the pairing rate limit is split.
- **#111:** a matching code on a fresh invitation can replace a pinned key.
- **#112:** one local notice per invitation.
- **#148:** the year view and Review my year become one year sky on the Sky's rules.
- **#154:** the Sky gets a key one tap away, which says red means old, never bad.
- **#155:** a life event stays one day, small screens need nothing new, and there is no north star.
- **#159:** placement and the phrase pool stay dormant until Daymark starts an ask of its own.
- **#166:** "Take a moment" lists "Not right now" first, stays still, and never says how the
  person's day was.
- **#171:** the Companion logs at `info` by default.
- **#175:** the crisis screen links to the safety plan, and the plan can be printed behind a
  warning.
- **#184:** a project step can carry an optional "When…" line in the person's own words, never a
  reminder.
- **#194:** the sync build ships on GitHub releases first, then as its own F-Droid listing.
- **#200:** sync stays single-writer, and other devices only add records through an add-only lane.
- **#203:** a person's own views describe what was logged and never mark it.
- **#205:** passkeys sign people in, with codes as the fallback, and never unlock keys.
- **#208:** everyone signs in with their own account, and access comes back through the person's own
  key, never by email.
- **#212:** sleep sensing stops at the on-body breathing check and its overnight version.
- **#214:** every encrypted item is padded on the device, so the server learns only a rounded size.
- **#215:** a clinician may start a connection, and the owner's device always approves it.
- **#216:** a clinician's ending is emailed only on opt-in, and the email names no event.
- **#217:** no signed clinician attestations, and every surface says the access log proves
  tampering, never completeness.
- **#219:** each stored journal belongs to exactly one owner, and no other credential can reach it.
- **#222:** revoking binds only an honest server, for good, and real patients wait for clients the
  office's server cannot change.
- **#228:** a share lasts 14 days by default and at most 90, and the server deletes what has ended.
- **#232:** the name stays Daymark, and the application id becomes `io.github.keyxmakerx.daymark`
  before the first public release.
- **#246:** English only, until a named human translator and a separate human reviewer take on a
  language.
- **#247:** the owner's web console adds printing and bigger read-only views, never writing or the
  Sky.
- **#251:** titles use a bundled Fraunces, and all other text keeps the platform's own sans.
- **#263:** the consoles get a type scale and motion tokens, and no icon set or global search.
- **#264:** the Companion offers only self-written questionnaires and names the phone's published
  ones when shared.
- **#283:** only a practice that keeps its own dated record becomes a star, which today means
  thought records.
- **#288:** the Companion is self-hosted, one product in three shapes, and no office role reaches
  anyone's credentials, keys or content.
- **#294:** conduct reports use GitHub's own tools, and no email address is published.
- **#296:** outside contributors sign off their commits, and automated sessions never do.
- **#301:** the one group smaller than a practice is a person's own care team.
- **#305:** a report is a copy you hand over, and a share is access you can end.
- **#310:** it stays Daymark Companion, with each page named for who uses it.

How pairing works, including the pairing rules above, is in `docs/COMPANION_PAIRING.md`.
