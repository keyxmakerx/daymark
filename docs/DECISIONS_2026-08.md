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

---

## D2. It gets no user-facing name

**Decision.** In code and docs it is a **rules engine** / **decision engine** — "logic engine" is fine
prose. In the UI it has no name and no persona. The setting that governs it is plain: *when Daymark
asks*.

**Why.** Naming it creates a character, and a character invites trust it has not earned — the same
dynamic we avoid by refusing an LLM. Its actual advantage is that it can justify any decision in one
sentence because it is rules. That is worth advertising; a mascot is not.

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
| **A user-facing AI persona** | See D2. |

---

## Open

1. **Therapist platform name.** Still unnamed. User-facing, needs a real one.
2. **Sequencing.** Proposed next sprint is D3 (compassion module) — smallest, tests the builder,
   deliverable to the clinician. The notification-outcome ledger (D1's prerequisite) and D5 follow.
3. **The notification-outcome ledger does not exist.** `Reminder` is `hour`/`minute`/`enabled`/`label`
   and nothing records whether a notification was acted on. Every adaptive idea is blocked on it.
4. **Whether to keep hand-verifying the evidence.** The sandbox cannot reach scholarly hosts; spot
   checks have caught one sample-size error and one failed replication, so the checking is earning its
   cost.
