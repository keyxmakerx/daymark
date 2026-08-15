# Decisions — August 2026

Decisions taken after the [clinician feedback session](./CLINICIAN_FEEDBACK.md) and the
[evidence review](./EVIDENCE_REVIEW_2026-08.md). Each records what was decided, why, and what would
change it. Written down so none of it has to be re-derived from a chat log.

---

## D1. The scheduling logic is an **arbiter**, not a recommender

**Decision.** Build one small component that owns a single question — *may a feature interrupt the
person right now?* — and refuse to give it any second job.

**The concern that prompted this** (maintainer, verbatim): *"it sounds like one of those things that
will have so many hands in everything that it, mostly in terms of context, is gonna be ruinous."*

That concern is **correct, and it is correct about a different component than the one we are
building.** There are two things one could build here and only one of them is a beast:

| | **Recommender** | **Arbiter** |
|---|---|---|
| Question it answers | "What should we say to this person?" | "May I speak right now?" |
| Context it needs | goals, mood, check-ins, assignments, history — everything | last interruption time, the person's declared frequency |
| Coupling | every feature feeds it; changing any feature touches it | features call it; it knows none of them |
| Size | unbounded | bounded |

**We are building the arbiter.** Per-feature suggestion logic stays inside each feature, where the
context it needs already lives. Goals decide what goal to suggest. The check-in decides what to ask.
Neither needs a central brain, and the arbiter never needs to know what a goal *is*.

**This is not speculative — the prototype already ships.**
`app/src/main/java/com/daymark/app/stats/SupportOffer.kt` is **84 lines including a 25-line comment**,
and its whole interface is:

```kotlin
fun shouldInterrupt(frequency: SupportOfferFrequency, lastOfferedAt: Long, nowMillis: Long): Boolean
```

Three arguments. No knowledge of mood, entries, goals or check-ins. Pure, Android-free, caller owns
persistence and the clock. Generalising it means adding a *kind* parameter so each caller has its own
budget, and persisting one timestamp per kind. That is the entire change.

**The rule that keeps it small:** features do not push context *into* the arbiter; they ask it a
yes/no question. The moment a feature needs to hand it domain state, that logic belongs in the
feature, not here.

**What would change this:** a genuine need for cross-feature reasoning ("don't ask about goals on a
day they logged a hard mood"). If that arrives, prefer passing a small opaque *priority* or
*sensitivity* tag on the request over teaching the arbiter what a mood is.

### Built — the arbiter is wired, and where

Recorded so this decision can be checked against the tree rather than taken on trust. Status detail
and the one item that still needs a real Android build are in
[PLAN_2026-08-NEXT.md](./PLAN_2026-08-NEXT.md) §5.

**The component.** `app/src/main/java/com/daymark/app/stats/InterruptionBudget.kt` — the prototype
generalised exactly as this section said it would be: a `Kind` per caller so budgets do not pool,
plus the engine's own reception. It **imports nothing at all**, so §D1's "it knows none of them"
is enforced by the compiler and not only by intent. The ledger arrives as `InterruptionBudget.Offer`
— three plain fields — with `data/OfferLedgerRepository.kt` mapping Room rows onto it, mirroring
what `DiscussionPrompts.Inputs` does for the report.

**The two production call sites.** Both ask a yes/no question and keep their own domain logic:

| Site | What it asks | What stayed in the feature |
|---|---|---|
| `ui/entry/EntryEditorViewModel.kt:196` | may the support space take the person over after this save? | `moodLevel <= LOW_MOOD_MAX` and `gentleSupportOn` — the arbiter is never told *what about* |
| `notifications/ReminderScheduler.kt:104` | may this fired reminder actually post? | the schedule itself; `schedule()` is untouched and still arms every day at the person's chosen time |

The editor's call **replaced** `SupportOffer.shouldInterrupt`, which now has no production caller;
`SupportOffer` survives for its `summary(frequency)` settings copy. `Kind.COMPANION` and
`Kind.ASSIGNMENT` exist with budgets and ledger keys and are **not yet called by anything**.

**The ledger.** `data/entity/OfferRecord.kt`, table `offer_records`, schema v14 — the three columns
§D1a specifies and no fourth, ever. Rows are immutable (no DAO update path), so nothing can
retroactively re-score how an offer landed, and `kind`/`outcome` are stored as text keys so a row
from another version always reads back.

**The monotonic invariant's tests.**
`app/src/test/java/com/daymark/app/stats/InterruptionBudgetTest.kt` — property sweeps over
kind × declared frequency × ledger × standing stop × clock, not chosen examples. Four sweeps, kept
separate because each fails independently and none catches the others:

1. *"no input path asks more than the person's own setting"* — the ceiling. Catches the
   engagement-optimiser shape, rewarding acceptance with more offers; the pairwise sweeps are blind
   to it, because making the good case louder leaves "worse is never louder" perfectly true.
2. *"worse reception never shortens the permitted gap"* — catches a signal wired backwards, and
   version drift that fails open.
3. *"worse reception never turns a no into a yes"* — the same rule at the level callers see, across
   elapsed time, so a threshold applied the wrong way round at the boundary cannot hide.
4. *"an inference may quiet the app, but only the person may silence it"* — the floor. Reception
   alone bottoms out at once per week; reaching *never* takes the person's own setting or their own
   "stop asking".

A fifth should be added rather than any of these relaxed. The sweeps state what "worse" means
independently of the engine, so they test it against this decision rather than against its own
weights.

### D1a. It reads its own reception, and infers no clinical state

**The proposal.** Let the arbiter see across features so it can work out *"maybe the user is
depressed, maybe the user is annoyed by the app, maybe the user is just happy and doesn't want to
talk about their feelings because they're good right now."*

**The observation that resolves it.** Those three states take **the same action**:

| Inferred state | Right response |
|---|---|
| Struggling | be gentler, ask less |
| Annoyed by the app | ask less |
| Doing fine, not in the mood | ask less |

There is no branch where the answer differs, so the arbiter does not need to distinguish them. It
needs **one variable — how its own offers are being received — not a state classifier.** That is the
whole difference between a small component and a beast, and it falls out of the problem rather than
being imposed on it.

**What the arbiter may know: its own ledger.** Offered, accepted, dismissed, snoozed, "not now",
"stop asking". This is not cross-feature context — it is the arbiter's own interaction history, one
table it owns. It stays small because no feature has to feed it anything beyond the tag on the
request it already makes.

**What it must never do: infer clinical state.**

1. **It would be diagnosis by side-effect.** Daymark declares `nonDiagnostic: true` on every
   instrument. An engine that concludes "this person is depressed" and changes its behaviour has made
   a clinical judgement with no instrument, no validation, and no consent — worse than a screen,
   because it is invisible.
2. **The field cannot do it reliably.** Digital-phenotyping work on inferring depression from phone
   behaviour has **poor replicability, often fails to detect clinically relevant events, and validates
   against unsuitable depression measures**. Reported accuracies (e.g. 76.4% for treatment response)
   come with documented replication problems.
3. **Its best signal is one we have banned.** The most consistent markers in that literature are
   reduced **geographic mobility**, social-app usage and sleep. Location is prohibited outright. We
   would be attempting the hard version of an unreliable inference with its strongest feature removed.

**The invariant that makes this safe and testable:**

> The arbiter's response to falling reception is **monotonic and one-directional**: it may only ever
> ask *less*. No signal, in any combination, may cause it to ask more.

This is the ethical guarantee and the engineering guarantee at once. A component that cannot escalate
cannot become an engagement optimiser by accident, cannot nag, and cannot be quietly retuned into one
later. It is also directly checkable in a unit test, unlike a policy expressed as intent.

Escalation stays where it belongs: **the person asks for more.** That is a setting, not an inference.

### D1b. The companion — a real presence, and a *client* of the arbiter

**Decided in favour of the maintainer's proposal, over two rounds of pushback from me. Recording the
reversal because the reasoning matters more than the conclusion.**

**The proposal.** A "little guy" in the corner that expands — interactive, conversational but with
**premade** responses, aware of the person's mood history and how often they have been around,
hideable at will. *"What if they like the arbiter?"*

**Why I was wrong twice.**

1. I argued a chat-like agent implies it will answer free text, and someone in a bad moment would type
   something real into a box that cannot respond. **But the proposal was never a text field.** A
   dialogue of fixed choices has no free-text failure mode — and it is *more* honest than a text box,
   because it never pretends to parse arbitrary input. I argued against a design nobody proposed.
2. I collapsed "sees mood history" into "infers clinical state". They are not the same, and the app
   already shows people their own history. The real line is narrower and easy to hold:

   > **Reflect, never label.** *"You logged three harder days this week"* hands someone their own data
   > back. *"You seem depressed"* is a claim about them. The first is fine. The second is D1a.

3. *"What if they like it?"* is the strongest argument in the thread and I did not engage with it. For
   someone alone at 2am, a warm presence offering two or three things to try may be the most valuable
   thing in the product — and warmth is **consonant** with the self-compassion content in D3, not
   opposed to it. A tray with a badge count is not the same object and would not do that job.

**The architecture, which dissolves the conflict.** The companion and the small arbiter are not in
tension, because **the companion is a feature that calls the arbiter, not the arbiter itself**:

| | **Arbiter** | **Companion** |
|---|---|---|
| Owns | may anything interrupt right now | mood history, dialogue content, its own UI |
| Size | ~100 lines, no domain knowledge | a full feature, like Goals or Check-in |
| When the person **opens it** | not consulted — no permission needed to answer someone | free to use everything it knows |
| When it wants to **surface itself** | must ask, and may be told no | respects the answer |

So the context that worried us stays *inside the companion*, where it already lives, and never leaks
into the permission gate. Every other feature keeps calling the same three-argument function.

**What the companion may do:** branch on real data (streak of hard days, time since last visit,
whether a compassion module is prescribed, whether a safety plan exists), remember where a
conversation left off, vary its openers so it does not feel canned, and offer concrete next steps.

**What it may not do**, none of which conflicts with the proposal:

- **No free-text input.** Fixed choices only. This is the maintainer's own design, recorded so it is
  not "improved" into a chat box later by someone who thinks that is friendlier.
- **No labels.** It reflects what was logged; it never tells someone what they are or what they feel.
- **Hidden means hidden.** Dismissing it is one tap, it does not come back on its own, and it never
  reappears to say it misses you. Un-hiding is a setting the person finds.
- **Not the crisis path.** The safety plan stays the person's own, authored in advance. The companion
  may point at it if the person has one; it never becomes it.

**Illustrative dialogue** — every line premade, every branch keyed to logged data:

> **"It's been about a week. No agenda."**
> · I'm doing alright · It's been rough · Just looking around · Not now
>
> → *(It's been rough)* **"That tracks — three of your last seven check-ins were on the harder end."**
> · Want something to try? · I just wanted to say it out loud · Show me what I wrote
>
> → *(Want something to try?)* offers what is actually available to that person: a prescribed
> compassion exercise, an activity from their own list, or their safety plan if they made one.

"I just wanted to say it out loud" is a first-class ending. Not every branch has to lead somewhere.

---

## D2. It gets no user-facing name

**Decision.** In code and docs it is a **rules engine** / **decision engine** — "logic engine" is fine
prose. In the UI it has no name and no persona. The setting that governs it is plain: *when Daymark
asks*.

**Why.** The arbiter is plumbing — a permission gate features call. Plumbing does not need a name, and
its advantage is that it can justify any decision in one sentence because it is rules.

**This is about the arbiter, not the companion.** D1b builds a companion with a real presence, and
*that* may well deserve a name and a character — it is a surface a person chooses to open and talk
with. The thing that must not become a persona is the invisible permission gate underneath it.

**Explicitly not "AI".** Not because it is not useful, but because the word promises opacity and
currently carries a liability in mental-health software specifically.

**Still open:** the therapist platform's name. That surface *is* user-facing and does deserve one.
"Hymdoll" was a typo for Heimdall; rejected on two grounds — a well-known self-hosted app already owns
the name in this exact audience, and Heimdall's defining attribute is *seeing and hearing everything*,
which is precisely backwards for a product whose pitch is that it cannot see your data.

---

## D3. Affirmations ship as a **therapist-prescribed compassion module**

**Decision.** Build what the clinician asked for, as a module a clinician prescribes rather than a
feature that switches itself on. Content is **self-compassion and values-writing**, not positive
self-statements.

**Why this shape.** It resolves four things at once:

- It is what she asked for.
- Routing it through a specialist puts the judgement where it belongs, which was the maintainer's own
  proposal and is the right one.
- It is the **first end-to-end test of the instrument builder**: author → `validateDraft` → publish →
  assignment → accept → run.
- The content is the best-evidenced of the three options (self-compassion g ≈ 0.66 on depression;
  Compassion Focused Therapy was developed specifically for shame and self-criticism).

**Why not positive self-statements.** Not because harm is proven — [that finding failed to
replicate](./EVIDENCE_REVIEW_2026-08.md#2-affirmations--three-different-things-and-the-popular-one-is-the-weakest)
— but because it is the only one of the three with **no demonstrated benefit** *and* a plausible harm
mechanism. Bad trade at any threshold.

**The structural rule, which survives the replication failure** because it rests on self-verification
theory rather than on any single result:

> Never require the person to assert a positive claim about themselves that they do not believe.

Self-compassion satisfies this by construction — *"this is a hard moment; hard moments happen to
people"* is not a claim about your worth, so there is nothing to contradict. Values-writing satisfies
it. Only the statement deck fails it.

**No threshold gate.** An earlier proposal was to gate this on a measured self-esteem score. Rejected:
the harm it would guard against is the weakest finding in the review; measuring self-esteem *in order
to change what the app does* makes that instrument a screen, in a catalog where every tool declares
`noScreeningFlag: true`; and an automated threshold silently classifies the person, which is an
inference about themselves delivered by software. The clinician-side availability switch — a human
deciding — is the existing capability-grant model and needs no new mechanism.

**This is not a crisis tool.** The safety plan is the person's own, authored in advance, never
suggested. A compassion exercise is for an ordinary hard day. They stay visibly separate surfaces; if
they blur, the safety plan loses what makes it work.

### Verified: the existing builder can already express this

A guided, unscored exercise — `info` framing + `freeText` prompts, `scoring: { scales: [] }` —
**passes `validateDefinition()`**. Probed directly. The only initial failure was framing copy missing
the required non-diagnostic disclaimer, which is a content fix.

**So this is a small sprint, not a new subsystem.** No new content type, no new entity, no schema
migration.

---

## D4. Photos are in scope, stripped by re-encoding

**Decision.** Support a photo check-in. Strip everything but the image itself, at a capped resolution.

**Implementation note that matters.** Deleting EXIF tags is *not* sufficient — metadata also hides in
EXIF thumbnails, XMP, IPTC and maker notes. The reliable method is **decode to a bitmap and
re-encode**, which structurally cannot carry any of it. The resolution cap fits naturally into the
same pass.

**Residual risk with no software fix:** image *content* can reveal location — a street sign, a house
number. That is a one-time sentence to the person, not a setting.

**Unchanged:** the standing prohibition on location data. This decision does not soften it; it is the
reason the stripping must be verifiable rather than assumed.

---

## D5. Goals become projects — containers for concrete steps

**Decision.** Replace the weekly-count `Goal` with a model covering one-time tasks, repeatable counts,
projects with milestones, and learning goals. Direction set by the evidence:

- **Implementation intentions attach to the next concrete action, not the project.** "When I sit down
  after dinner, I will read one chapter" is the evidenced unit; "when I want to learn statistics, I
  will learn statistics" is not. Promote `cue`/`routine` from optional fields to load-bearing.
- **Learning projects score on process, not completion.** No target, no percentage. For novel or
  complex undertakings a specific *learning* goal beats a specific *performance* goal.
- **A project is a folder for steps, not a standalone aspiration.** It should not be savable in a
  useful state with only a title — abstract goals are the shape people with depression already
  over-produce, and abstractness is implicated in rumination.
- **Abandoning is one tap, neutrally worded, never counted as failure, and never surfaced to the
  clinician as a negative signal.** Depressed people disengage from unattainable goals *faster*; a
  design that penalises quitting fights a process the person's own system is already running.
- **A declined suggestion must be invisible to the clinician.** Visibility converts autonomous
  acceptance into introjected compliance, which predicts nothing. Suggestions must be **editable on
  acceptance**.

---

## D6. Things we are deliberately not building

Recorded so they do not get re-proposed as obvious wins.

| Not building | Why |
|---|---|
| **Streaks, points, badges, levels** | Gamification was non-significant as a moderator in the largest meta-analysis — evidence of no effect, not absence of it. Streaks turn *the log* into the goal and amplify self-blame after a break. If continuity is shown, use a non-consecutive form ("12 of the last 30 days") with no breakable state. |
| **Throughput metrics on the activity board** | Completion does not predict symptom improvement. No velocity, burndown, percentage rings, or count badges on pending columns. |
| **Auto-generated insights from mood/activity pairing** | "Walking lifts your mood" is not supported by daily-diary data and a wrong inference here is a self-blame vector. Show them side by side; let the person conclude. |
| **Note excerpts in Companion** | Non-disclosure in therapy is the norm and is driven by anticipated consequences of being seen. This protects the one asset the product cannot regenerate: a place to write without an audience. |
| **Inferring reminder quality from app opens** | A notification raises the probability of opening within the hour ~3.66×. It is the metric that moves most easily with nothing underneath improving. The signal must be person-declared. |
| **Lapse-referencing notifications** | "You haven't written in 3 days" — the only documented harm signal in the notification literature, with no efficacy evidence on the other side. |
| **A free-text chat box in the companion** | See D1b. Fixed choices have no "typed something real into a box that cannot answer" failure. The companion itself is being built. |
| **Inferring clinical state from usage** | See D1a. Diagnosis by side-effect, on an inference the field cannot replicate, whose best feature (location) we have banned. |
| **Any signal that makes the arbiter ask *more*** | See D1a. The response function is monotonic and one-directional by design, so the component cannot be retuned into an engagement optimiser. |

---

## Open

1. **Therapist platform name.** Still unnamed. User-facing, needs a real one.
2. **Sequencing.** Proposed next sprint is D3 (compassion module) — smallest, tests the builder,
   deliverable to the clinician. The notification-outcome ledger (D1's prerequisite) and D5 follow.
3. **~~The notification-outcome ledger does not exist.~~ Built — see the "Built" section under D1.**
   `offer_records` (schema v14) records that the app asked and how that landed, and
   `ReminderScheduler` now writes a line on every notification it posts. `Reminder` itself is
   unchanged — still `hour`/`minute`/`enabled`/`label` — because the outcome belongs to the ledger
   and not to the schedule. One thing remains before this can be called verified: the exported
   schema JSON was hand-written and its `identityHash` still needs to be regenerated by a real
   Android build ([PLAN_2026-08-NEXT.md](./PLAN_2026-08-NEXT.md) §5).
4. **Whether to keep hand-verifying the evidence.** The sandbox cannot reach scholarly hosts; spot
   checks have caught one sample-size error and one failed replication, so the checking is earning its
   cost.
