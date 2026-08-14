# Clinician feedback — running log

A record of what practising clinicians say when they actually use Daymark. Their words first, our
interpretation second, clearly separated. This is the most valuable input the project gets and it
decays fast, so it is captured before it is analysed.

**Rule for this file:** never silently improve what someone said. If a suggestion conflicts with a
product invariant, record the suggestion *as given* and note the tension underneath it. The conflict
is information — it usually means either the invariant needs explaining better, or the invariant is
wrong.

---

## Session 1 — August 2026

**Who:** the maintainer's own therapist, a practising clinician, reviewing the **current shipped
build** — not the redesign renders. She had not seen `web-03` or `web-04`.
**Format:** conversation, relayed by the maintainer. Brainstorm, not a considered review — she said
herself it was not fully thought out.
**Status:** captured. Evidence review commissioned separately; nothing here is a decision yet.

### The headline

She confirmed the current UI is **not good — "way too messy", and redundant in places.** That is an
independent confirmation of the diagnosis in
[COMPANION_WEB_REDESIGN_PLAN.md](./COMPANION_WEB_REDESIGN_PLAN.md), arrived at from the opposite
direction: we found it by counting mood-token misuse, she found it by trying to read the thing.

Worth stating plainly because it is easy to lose: **she is describing the app a real person is using
right now, in real sessions.** That outranks any internal reasoning about what we think is confusing.

---

### 1. The PDF report needs to be expanded — it is *for the therapist*

**What she said.** The exported report has a lot of white space and does not earn its page count. The
dashboard part needs expanding, because that report is the artefact *she* reads.

**What exists today.** `app/src/main/java/com/daymark/app/export/PdfReportGenerator.kt`, 316 lines,
eight sections: cover header, summary strip, review, trend chart, distribution, activity table,
entries, journal, and an authenticity block carrying a SHA-256 of the content. Options live in
`PdfExportOptions` — `includeNotes` defaults **on**, `includeJournal` defaults **off**.

**The tension, and it is a useful one.** The report is generated **on the person's device, by the
person, and handed over** — a one-shot artefact they control. The Companion sync path is a *standing
grant* where the clinician can read whatever the grant permits, whenever they like. These are
different trust models and we have been sloppy about saying so. The "clinician never sees raw
entries" rule is a **Companion** rule. The PDF has always been able to include the journal, because
the person chose to include it and handed it over themselves.

That distinction has to be made explicit in the UI before this goes further, or someone will
reasonably conclude we say one thing and do another.

### 2. A horizontal timeline, with note excerpts capped at ~a sentence

**What she said.** Fill the white space with a horizontal timeline; show partial notes, capped at
something like a single sentence.

**Why she is probably right about the timeline.** A between-session report is read *chronologically*
— "what happened since I last saw you". The current report leads with aggregate summary, which
answers a different question.

**Where this needs care.** The excerpt half is the delicate part, and it is delicate in two different
ways depending on which surface it lands on:

- **In the PDF** it is defensible: the person chose to export, chose to include notes, and handed the
  file over. The control needed is *granularity* — being able to include a note's first sentence
  rather than all-or-nothing.
- **In the Companion** it would breach the standing rule that a clinician sees scores and bands only.

There is also a second-order risk that matters more than either: **if people know a sentence may be
excerpted, they may write differently.** A journal that is being partly performed is a worse
instrument than one that is private, and the clinician would be reading a distorted signal without
knowing it. This is flagged for the evidence review.

### 3. A board with columns — "like the programming tool"

**What she said.** Something like the developer tool with columns of To do / In progress / Done — but
where you can also see the person's **mood** and their **text** on the items.

**What she is describing.** A kanban board (Trello / Jira / GitHub Projects). The maintainer could not
recall the name; the pattern is unmistakable from the description.

**What exists today.** `app/src/main/java/com/daymark/app/ui/activation/BehavioralActivationScreen.kt`
already exists. Behavioural activation is *activity scheduling linked to mood* — which is remarkably
close to what she described, arrived at independently. She may be describing a better **presentation
of a feature we already have** rather than a new feature.

**The open question.** A kanban optimises for throughput and makes an unfinished backlog permanently
visible. Behavioural activation cares about the activity→mood link, not about clearing a column.
Whether a visible pile of undone things helps or harms someone in a depressive episode is exactly the
sort of thing to check before building. Flagged for the evidence review.

### 4. Repeating reminder times are unhealthy — people learn to ignore them

**What she said.** A fixed repeating time is not healthy; people will tune it out. Something dynamic
would be better. Her worked example: if a person *only* opens the app because of a notification, then
whatever we are doing is working — but if we are sending notifications and they are not coming, that
is a signal to try different times or a different frequency.

**What exists today.** The `Reminder` entity is `hour`, `minute`, `enabled`, `label`. A fixed clock
time and nothing else. **No signal is recorded about whether a notification was acted on.** We could
not build her adaptive version today even if we decided to — there is no data to adapt on.

**This is the sharpest, most actionable thing she said.** It is a concrete mechanism, it names the
signal to collect, and it identifies a real defect rather than a preference.

**The line to hold.** "Adapt the timing so the person is more likely to open the app" is one step from
engagement optimisation, which is what every attention-harvesting app does and which this product
exists to not be. The distinction to preserve: we are trying to find a time that is *useful to them*,
not a time that maximises opens. Those diverge, and the design has to be explicit about which one it
is serving.

### 5. More than one kind of check-in

**What she said.** Check-ins could vary — e.g. taking a **photo of what you are doing**, versus a
mood-only check-in.

**The maintainer's position.** This should be **configurable by the person**, probably as a choice in
the onboarding wizard, rather than the system deciding.

**What this drags in.** A photo is not a neutral datum. It carries EXIF metadata, faces, bystanders,
and — critically — **location**, which this product has a standing, explicit prohibition on ("there
is no location sharing, do not include this, full stop"). Any photo feature must strip EXIF at
capture, and that must be verifiable, not assumed. Flagged for the evidence review, both for whether
varied modality helps and for what it costs.

### 6. The low-mood popup should come off the main button layout

**What she said.** Rather than a popup, make it something reachable from the main layout.

**VERIFIED — this is already built, and it already matches what she described.**
`app/src/main/java/com/daymark/app/stats/SupportOffer.kt` separates exactly the two things she was
reaching for:

- **The affordance** — "a quiet action that appears in the editor when a low mood is picked and simply
  sits there. Ignoring it is free, so it needs no rate limiting." That is her "off the main buttons
  layout", already shipped.
- **The interruption** — being taken somewhere you did not ask to go. Rationed by
  `SupportOfferFrequency`: Never / OncePerDay / OncePerWeek / EveryTime, defaulting to **OncePerDay**.

The old behaviour she was implicitly criticising is preserved only as an opt-in (`EveryTime`), and the
file is candid that the default "is a **judgement call, not evidence** — published JITAI dosing
figures are for other interventions and populations."

Two things follow. First, the maintainer's uncertainty resolves: **yes, it was done.** Second, and more
useful — she arrived at the same separation independently, from clinical intuition, that the code
arrived at from a maintainer's bad experience. That is the strongest signal in this session that the
affordance/interruption distinction is real and should be applied elsewhere, starting with reminders.

**The failure mode still to name.** A system that reacts badly to a low score teaches people to stop
reporting low scores. That destroys the data the clinician depends on, silently, and it would look
like improvement.

**The failure mode to name.** A system that reacts badly to a low score teaches people to stop
reporting low scores. That destroys the data the clinician depends on, silently, and it would look
like improvement.

### 7. Affirmations

**What she said.** Affirmation content is important.

**What the maintainer asked for.** An explicit check on *what that actually entails* — which is the
right instinct, because "affirmation" names at least two different things in the literature and they
do not have the same evidence base. Commissioned as the highest-stakes item in the evidence review.

**The product question attached to it.** Whether this is a good first use case for the **plugin /
puzzle-and-question builder** — something that adapts to the person dynamically rather than serving
canned text. If so, it also has to be **authorable from the therapist system**, which makes it a test
of the tool builder as much as of the content.

### 8. Goals should become projects

**What she said.** Goals are too narrow. They should cover projects, learning something, longer arcs —
with progress, and interactive.

**What exists today.** `Goal` is a **weekly habit count**: `title`, optional `activityId`,
`targetPerWeek`, plus `cue` and `routine` for an implementation intention. The implementation-intention
part is the well-evidenced piece and should survive whatever replaces this.

**The gap.** There is no notion of a project, a milestone, a multi-week arc, or a learning objective.
"Read a book on X" and "exercise 3× a week" are not the same shape and the model only expresses the
second.

### 9. The therapist system needs a name

**What the maintainer said.** They like **"Hymdoll"** but are unsure it conveys secure and private.

Open. Discussed in the reply to this session rather than decided here.

---

## Carried forward

**For the clinician, next session:**
1. When you read the report, what do you look for *first*? The question is what the top of page one
   should answer, and that should be your answer, not our guess.
2. Is the excerpt idea about *what happened* or *how they said it*? Those need different features.
3. On the board: what would you do with it in a session that you cannot do with the current screens?
4. For the adaptive reminder — what would tell you it was working, and what would tell you it had
   become nagging?
5. When you say affirmations, do you mean values work, self-compassion practice, or short positive
   statements? They are different tools and we want to build the one you meant.

**For the maintainer:**
1. ~~Verify whether the low-mood force-navigation is actually fixed.~~ **Done — it is fixed**
   (`stats/SupportOffer.kt`). Tell her; it answers her suggestion directly and she may have more to
   say about the frequency ladder, which is currently an unevidenced default.
2. Decide whether the PDF and the Companion get one disclosure model or two — and say which, in the UI.
3. Decide whether photos are in scope at all, given the location prohibition.

**The transferable idea from this session.** The **affordance vs interruption** split in
`SupportOffer.kt` is the most reusable thing the codebase has learned, and it generalises directly to
her reminder point: an affordance is free to ignore and needs no rationing; an interruption is a cost
imposed on someone and must be rationed, visible, and adjustable. Reminders are currently pure
interruption with no affordance side and no rationing beyond on/off. That framing is probably the
answer to item 4, and it came from her.

**Evidence review: landed.** See [EVIDENCE_REVIEW_2026-08.md](./EVIDENCE_REVIEW_2026-08.md). Four of
her suggestions are supported, three are contradicted, and one of her stated mechanisms is inverted:

| Her suggestion | Verdict |
|---|---|
| Fixed repeating reminders get ignored | **Supported** — assume a half-life in weeks |
| "They only open from a notification → it's working" | **Inverted** — that is a 3.66x stimulus response that says nothing about fit |
| Make timing dynamic | **Equipoise** — unproven in both directions; the *frequency* half is better supported than the *timing* half |
| Affirmations matter | **Split** — values-writing yes, positive self-statements harm this population |
| Kanban board with mood + text | **Half** — BA is strongly evidenced, but columns carry state and drop *when*, which is the load-bearing attribute |
| Fill the PDF's white space | **Contradicted** — alert fatigue is well quantified; the white space may be the feature |
| Horizontal timeline | **Neutral** — timeline vs table is close to a wash |
| Note excerpts capped at a sentence | **Contradicted, strongly** — the best-supported decision in the review is to hold the scores-and-bands line |
| Low-mood support off the main layout | **Supported** — and already built |

The single most useful thing she produced is not in any paper: the **affordance vs interruption**
framing, which she reached independently of `stats/SupportOffer.kt`.
