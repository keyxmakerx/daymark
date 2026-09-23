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

## D1. The scheduling logic is an arbiter, not a recommender

**Decision.** One small component owns a single question — *may a feature interrupt the person right
now?* — and has no second job.

| | Recommender | **Arbiter** (built) |
|---|---|---|
| Question it answers | "What should we say to this person?" | "May I speak right now?" |
| Context it needs | goals, mood, check-ins, history — everything | the last interruption, the person's declared frequency, its own ledger |
| Coupling | every feature feeds it | features call it; it knows none of them |
| Size | unbounded | bounded |

Suggestion logic stays inside each feature, where its context already lives. Features do not push
context into the arbiter; they ask it a yes-or-no question. The moment a feature needs to hand it
domain state, that logic belongs in the feature.

**What would change this:** a real need for cross-feature reasoning ("don't ask about goals on a day
they logged a hard mood"). Even then, pass a small opaque priority tag on the request rather than
teaching the arbiter what a mood is.

**As built.** `stats/InterruptionBudget.kt` imports nothing at all, so "it knows none of them" is
enforced by the compiler. It keeps a separate budget per `Kind`. Its ledger is the `offer_records`
table (`data/entity/OfferRecord.kt`): three columns and no fourth, rows never updated, so nothing
can re-score after the fact how an offer landed.

- **The one production caller** is the entry editor. It asks whether the support space may take the
  person over after a save, and keeps its own reasons (low mood, gentle support on) to itself.
- **Reminders are recorded, not rationed.** Every firing writes a ledger line, but the arbiter does
  not gate reminders. The person chose those times, and there is no reminder-frequency setting for
  them to turn back up if an inference quietened them (`notifications/ReminderScheduler.kt`).
- `Kind.COMPANION` and `Kind.ASSIGNMENT` have budgets and are called by nothing yet. Not built:
  #272.

The invariant's tests, `InterruptionBudgetTest`, are property sweeps over kind, declared frequency,
ledger, standing stop and clock. Four sweeps, each catching what the others miss:
1. no input asks more than the person's own setting;
2. worse reception never shortens the gap;
3. worse reception never turns a no into a yes;
4. an inference may quieten the app, but only the person may silence it.

Add a fifth rather than relax any of these.

### D1a. It reads its own reception, and infers no clinical state

The proposal was to let the arbiter work out whether someone is struggling, annoyed by the app, or
fine and not in the mood. All three call for **the same action — ask less** — so the arbiter needs
one variable, how its own offers are received, and not a state classifier.

**What it may know:** its own ledger — offered, accepted, dismissed, snoozed, "not now", "stop asking".

**What it must never do: infer clinical state.**
1. It would be diagnosis by side effect, with no instrument, no validation and no consent. That is
   worse than a screen, because it is invisible.
2. The field cannot do it reliably. Inferring depression from phone behaviour replicates poorly.
3. Its best-known signal, reduced geographic mobility, uses location, which Daymark bans outright.

> **The invariant.** The arbiter's response to falling reception is monotonic and one-directional:
> it may only ever ask *less*. No signal, in any combination, may make it ask more.

That is the ethical guarantee and the engineering guarantee at once. It is also directly testable.
Escalation belongs to the person: asking for more is a setting, never an inference.

### D1b. The companion is a real presence, and a client of the arbiter

A "little guy" in the corner that expands: conversational, but every response is **premade**, it is
aware of the person's own history, and it can be hidden at will. The maintainer proposed it and it
was kept over two rounds of pushback, because a fixed-choice dialogue has no free-text failure mode.
For someone alone at 2am, a warm presence offering two or three things to try may be the most
valuable thing in the product.

The companion is a **feature that calls the arbiter**, not the arbiter itself. It owns its mood
history, content and UI. When the person opens it, nothing needs permission. When it wants to
surface itself, it must ask, and it may be told no.

It may branch on real data, remember where a conversation left off, vary its openers, and offer
concrete next steps. It may not:
- **accept free text** — fixed choices only;
- **label** — *"you logged three harder days this week"* hands someone their own data back, while
  *"you seem depressed"* is a claim about them, which D1a forbids;
- **come back uninvited** — hidden means hidden, and un-hiding is a setting the person finds;
- **become the crisis path** — the safety plan stays the person's own; the companion may point at it.

**As built:** the dialogue content, rules and web component exist. No page mounts the component
(`companion/web/src/lib/docs.test.ts` asserts that), and there is no phone surface. Not built: #272.
How it works: `docs/COMPANION_DIALOGUE.md`.

---

## D2. The arbiter gets no user-facing name

In code and docs it is a rules engine or decision engine. In the UI it has no name and no persona;
the setting that governs it reads *when Daymark asks*. It is plumbing, and its virtue is that it can
justify any decision in one sentence because it is rules. **It is never called "AI."** That word
promises opacity, and it carries a liability in mental-health software in particular.

This is about the arbiter, not the companion (D1b), which may deserve a name.

The clinician platform's name is still open: #310. "Heimdall" was rejected. A well-known
self-hosted app owns it, and Heimdall's defining attribute is seeing and hearing everything, which is
backwards for a product whose pitch is that it cannot see your data.

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

- **Implementation intentions attach to the next concrete action**, not to the project. Whether steps
  get an if-then cue is open: #184.
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
| **A free-text chat box in the companion** | See D1b. |
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
observations and questions ("Wellbeing entries were lower on the three weeks with no logged activity.
Worth asking about?"). It cites nothing and prescribes nothing. Where evidence is citable, it is
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
RBAC, key handling and recovery flows: #284. Scope, editions and hosting are open: #288. The
clinical layer's design is `docs/COMPANION_ACCESS_CONTROL.md`, and its work is tracked in #143.

Source: [the July product direction](https://github.com/keyxmakerx/daymark/blob/968638594f10f6a4424415f8a5c14fd8eb4aaa00/docs/PRODUCT_DIRECTION.md).

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

How pairing works, including those rules, is in `docs/COMPANION_PAIRING.md`.
