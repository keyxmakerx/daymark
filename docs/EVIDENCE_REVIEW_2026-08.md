# Evidence review — the August 2026 clinician brainstorm

What the literature says about the suggestions captured in
[CLINICIAN_FEEDBACK.md](./CLINICIAN_FEEDBACK.md), including where it **contradicts** them.

> **This is design research, not clinical guidance.** It informs what we build, never how anyone is
> treated. The compliance gate in [PRODUCT_DIRECTION.md](./PRODUCT_DIRECTION.md) is unchanged.

## How much weight this carries

Seven parallel literature sweeps plus an adversarial refutation pass. **Read the limits first.**

- **The agents could not fetch primary sources.** The sandbox proxy refuses every scholarly host —
  PubMed, PMC, Crossref, doi.org, OpenAlex, JMIR, Nature, SAGE, Wiley. Verified directly: all return
  connection failures. The sweeps ran on search-result snippets; the refutation agents ran with the
  shared WebSearch budget already exhausted, i.e. **no source access at all**.
- **Three load-bearing citations were then spot-checked by hand** and verified to the digit:
  Klasnja et al. 2019 (+167 steps on a 253-step average, decaying 2%/day, null by day 28 —
  n=37, not the 44 the sweep reported); Bell et al. 2023 (JMIR mHealth uHealth 11:e38342, strong
  near-term effect, long-term engagement unimproved); Valentine et al. 2025 (npj Digital Medicine,
  92 RCTs, n=16,728, g=0.43, **no** association between persuasive-design principles and efficacy or
  engagement). Three for three. That is reassuring about the sweeps and is **not** a clean bill of
  health for every number below.
- **A workflow-authoring bug cost the synthesis four of seven topics.** The synthesis input was
  truncated at 55,000 characters, silently dropping the board, goals, report and low-mood sweeps. They
  survived in the run journal and are incorporated here directly. The synthesis's own hedging about
  those four should be read as an artefact of that bug, not of the evidence.

**Anything below marked load-bearing needs a human with journal access to confirm before it reaches a
public claim.**

---

## 1. Adaptive reminders — she is right about the problem and wrong about the signal

**Right, and safely so:** response to a repeating prompt decays over weeks. Assume any prompt policy
has a half-life measured in weeks and instrument for it.

**The most important correction in the review — her decision rule is inverted.** She proposed: *if the
person only ever opens the app from a notification, the timing is working.* Bell et al. found a
notification raises the probability of opening within the hour **3.66×** (95% CI 2.99–4.48) — while in
the same trial **no** notification policy lengthened time-to-disengagement over 30 days. Bidargaddi et
al. 2018 (n=1,255) found a tailored push moved 24-hour engagement by 3.9% relative (RR 1.039).

Opening-from-notification is the metric that moves most easily with nothing underneath it improving.
"They only open when prompted" is equally consistent with the prompt having become the *sole,
stimulus-bound* driver of use — the failure mode, not the success state.

**Build:** the success signal must be **person-declared, not inferred** — an explicit *this time works
/ try later / stop asking* control. Do not infer timing quality from opens.

**"Dynamic" is not an evidence-backed upgrade.** The only head-to-head (Morrison & Yardley 2017,
n=77) found no advantage for intelligent context-sensed timing over fixed daily windows — but at n=77
that is *no evidence of an effect*, not evidence of no effect. Genuine equipoise. The arguments against
building it are cost, statefulness and the no-location commitment — **not** a demonstrated null. Do not
write "the evidence says adaptive timing doesn't work"; it does not say that.

**The frequency half is better supported than the timing half.** In DIAMANTE (Aguilera et al. 2024,
n=276 adults with diabetes *and* elevated depressive symptoms — the closest population in this review
to Daymark's), a daily-random-message arm numerically *underperformed* near-minimal weekly contact.

**The one hard prohibition:** never reference a lapse. "You haven't written in 3 days", "keep your
streak". The only documented harm signal in the notification literature attaches to exactly that, and
there is no efficacy evidence on the other side.

---

## 2. Affirmations — three different things, and the popular one is the weakest

**CORRECTED after a second verification pass. An earlier draft of this document called the harm
finding "verified" and treated it as established. It is not — it is contested.**

- **Positive self-statements** — the consumer-app deck of "I am enough". Wood, Perunovic & Lee (2009,
  *Psychological Science* 20, 860–866) found people with **low self-esteem who repeated "I'm a lovable
  person" felt *worse*** than people who said nothing. **But a later study failed to replicate it**
  ("On the failure to replicate past findings regarding positive affirmations and self-esteem",
  *Journal of Contextual Behavioral Science*, 2019) — low-self-esteem participants in the
  positive-statement condition did *not* report worse mood or reduced self-regard. So the honest
  status is: **one striking finding, one failed replication, and no demonstrated benefit on either
  side.** Do not cite the harm as established.
- **Self-affirmation theory** — writing briefly about *your own most important values*, not about
  yourself. A 2025 meta-analysis across 129 tests / 67 studies / 17,700+ participants found
  significant but **modest** well-being gains; in education d ≈ 0.41, concentrated in
  identity-threatened groups.
- **Self-compassion** — the best-evidenced of the three, and the one nobody asked about. Meta-analytic
  effect on depression **g ≈ 0.66** (moderate), with 21 RCTs of longer-term interventions showing
  medium-to-large decreases in depression, anxiety and distress. Critically, **Compassion Focused
  Therapy (Gilbert) was developed specifically for people with shame and self-criticism** — precisely
  the population that worries us here.

### Why the replacement is structural, not a matter of gating

The mechanism that made the backfire plausible is **self-verification theory** (Swann): people seek
information confirming their existing self-view, *even when that view is negative*. That theory is
well established independently of whether Wood's specific result replicates, and it gives a principled
rule that does not depend on the contested finding:

> **Never require the person to assert a positive claim about themselves that they do not believe.**

Self-compassion content satisfies this by construction. *"This is a hard moment; hard moments happen
to people"* is not a claim about your worth, so there is nothing to contradict. Values-writing
satisfies it too — *"what matters to me"* is not a self-evaluation. **Only the statement deck fails
it**, and it is also the one with no demonstrated benefit.

**Build self-compassion and values-writing. Do not ship positive self-statements.** Not primarily
because they harm — that is contested — but because they are the only one of the three with an
unproven upside *and* a plausible downside mechanism, which is a bad trade at any threshold.

### On gating it behind a measured threshold

The proposal was to let the clinic side gate affirmation content on a self-esteem threshold. Three
reasons to prefer a different lever:

1. **The harm it guards against is contested**, so the gate would be built on the weakest finding here.
2. **Measuring self-esteem in order to gate is functionally a screen.** Every catalog instrument
   carries `nonDiagnostic: true` and `noScreeningFlag: true`. An instrument whose score changes what
   the app does to you is a screen regardless of what the flag says, and it would be the first one.
3. **An automated threshold silently classifies the person.** If someone discovers they were filtered
   out of content for scoring low, that is an inference about themselves delivered by software — the
   exact harm the product exists to avoid.

**What to build instead:** make the content structurally safe (above) so no gate is needed, and keep a
**clinician-side availability switch** — a human deciding a module is or is not right for someone.
That is the existing capability-grant model, needs no new mechanism, no new instrument, and no
threshold. Human judgement where the evidence is thin; structure where it is not.

---

## 3. The board — it drops the attribute that does the work

**Behavioural activation is strongly evidenced**, holds up in severe depression, and works when
delivered by people without specialist psychotherapy training. Building on BA is a well-founded bet.
`BehavioralActivationScreen.kt` already exists.

**But a To do / In progress / Done column carries STATE and drops TIME**, and *when it will happen* is
the load-bearing attribute in activity scheduling. **If an item can exist on the board without a
when/where, the board is not implementing BA** — it is generic task management wearing BA's evidence.

**Completion is not the therapeutic ingredient.** In a BA-delivering sample, goal achievement did not
predict symptom improvement, concurrently or lagged. So: no throughput metrics. No velocity, no
burndown, no percentage-complete rings, no count badges on pending columns. *"I planned this, I didn't
do it, here's what happened"* must be a first-class, non-failing outcome.

**Do not auto-generate inferences from the mood/activity pairing.** Show them side by side as the
person's own record. No "walking lifts your mood", no ranking activities by mood delta. The daily-diary
data does not support the causal story, and a wrong inference here is a self-blame vector.

**The Zeigarnik justification for visible backlogs does not survive meta-analysis** (near-null across
59 studies) — but the opposite has not been tested either, so hiding stale items is a *precautionary*
choice, not an evidence-based one. What is supported: cueing an **unresolved** goal triggers ruminative
self-focus, and letting the person form a specific plan removes it. **The antidote is planning, not
hiding.**

**Design the return-after-lapse state first.** Any design assuming a maintained board is wrong for most
users within a month. Returning after two weeks must not present an accusatory pile of stale items.

---

## 4. Goals as projects

- **Implementation intentions are the strongest finding here** (medium-to-large, survives in clinical
  samples). Promote `cue`/`routine` from optional to load-bearing — but **attach the if-then plan to
  the next concrete action, not to the project.** "When I sit down after dinner, I will read one
  chapter" is the evidenced unit; "when I want to learn statistics, I will learn statistics" is not.
- **Progress monitoring helps** (d ≈ 0.40) — but the evidence is for the *act of recording*, not for
  any visualisation. Build the recording affordance; treat rings and dashboards as unevidenced
  decoration.
- **Learning projects score on process, not outcome.** For novel/complex undertakings a specific
  *learning* goal beats a specific *performance* goal — the opposite of a target-and-progress-bar
  model. Let someone write a learning goal without a number. (And no growth-mindset copy; that is a
  separate, near-null intervention.)
- **Projects are containers for concrete steps, not standalone aspirations.** Abstract goals are the
  shape people with depression already over-produce, and abstractness is implicated in rumination. A
  project should not be savable in a useful state with only a title.
- **Abandonment is functional and sometimes protective**, and depressed people disengage from
  unattainable goals *faster*. Archiving must be one tap, neutrally worded, never counted as failure,
  and **never surfaced to the clinician as a negative signal.**
- **Suggest-and-accept is the mechanism, not just an ethical nicety.** Two refinements: a declined
  suggestion must be **invisible to the clinician** (visibility converts autonomous acceptance into
  introjected compliance, which predicts nothing), and suggestions must be **editable on acceptance**.

**Gamification: do not build it.** Non-significant as a moderator in the largest meta-analysis — this
is evidence of no effect, not absence of evidence. **Streaks are the wrong default**: they turn the
*log* into the goal, and the post-break motivational drop is amplified exactly when the person blames
themselves. If any continuity display ships, use a non-consecutive form — *"12 of the last 30 days"* —
which has no breakable state.

---

## 5. The clinician report — the white space may be the feature

**This contradicts her most directly, and it is well supported.** Alert fatigue is one of the
best-quantified harms in clinical informatics: as the number of things demanding attention rises,
clinicians disengage from *all* of them. **"Too much white space" is not automatically a defect.**

**Routine outcome feedback produces a small average benefit (d ≈ 0.15), and essentially all the usable
signal sits in deteriorating cases** — not in general enrichment. Meanwhile **unaided clinicians are
poor at detecting deterioration and simple algorithmic flags beat clinical judgement.** That, not
display richness, is the mechanism with the best support.

**Build:** one explicit, computed **"this has moved outside your recent range"** marker per shared
instrument. Person-relative, since Daymark has no norms or thresholds, and worded as a *change notice*,
never a severity or risk class. Optimise for being read in the 60 seconds before a session — non-use,
not insufficient detail, is the modal failure mode.

**Timeline vs table is close to a wash** (clinician interpretation accuracy 90–100% across formats).
Don't spend the redesign there. What *did* move accuracy: state each instrument's direction in words
next to the chart, label scale endpoints, and print the person's own item wording — these are
self-authored instruments.

**Do not connect sparse points with a continuous line.** Short, frequent self-report series are
dominated by measurement error and regression to the mean; a connected line renders an interpolation
the data does not contain. Plot points with a band for that person's typical variation, mark a reliable
-change threshold, and show sampling density so a clinician can see when a "trend" rests on two points.

**On note excerpts — hold the line.** Non-disclosure in psychotherapy is the *norm*, driven specifically
by anticipated consequences of being seen. **This is the design decision with the most defensible
backing in the entire review**, because it protects the one asset the product cannot regenerate: a
place where the person writes without an audience. And the counter to "it's only a sentence": self-report
scores are *already* communicative acts aimed at the clinician — patients strategically adjust
responses — so the observer effect is not hypothetical and already operates on the data we do share.
If excerpts are ever built: per-entry, explicit, opt-in, default off, never retroactive, never
auto-selected.

**The report must show the person exactly what the clinician sees, in the same rendering, before it is
sent.**

---

## 6. Low mood — keep the question, drop the interruption

- **Any rule treating "lowest mood" as a risk trigger will be wrong nearly every time it fires.**
  Individual-level suicide-risk prediction performs near chance, PPV on the order of 1% or below. The
  interruption is, in expectation, always being shown to someone not in crisis.
- **Safety-planning evidence is for a collaboratively authored plan inside a human relationship with
  follow-up** — not for software presenting a screen at a low score. Daymark's existing rule (the
  safety plan is the person's own, never offered as a suggestion) is the design closest to what was
  actually tested. **Keep it.** Automatic presentation is a different intervention and must not borrow
  the credibility of the tested one.
- **Forcing the path reduces later voluntary use** (reactance). Force-navigation is close to the
  maximal freedom threat a UI can deliver, at the moment the person is least resourced.
- **Asking is not harmful.** The iatrogenesis worry is empirically unsupported. So **do not blunt the
  instrument** — keep a true floor on the mood scale; that is what makes the shared data honest.
  Concentrate the entire change on what happens next.
- **The failure mode most worth designing against**, despite no direct test: people concealing low mood
  to avoid a system's overreaction. The clinician-facing scores are worth nothing if the low end of the
  scale is unsafe to use. Make the lowest value cost nothing, and watch for scale-floor avoidance.

---

## What the evidence will not decide

- Whether adaptive timing beats a good fixed time. Equipoise, both directions.
- Whether a visible backlog harms people in a depressive episode. Untested.
- Offer vs interruption for crisis resources. **No direct experimental comparison exists.** The design
  argument is strong and converging; the empirical argument is indirect. Make the change, but do not
  call it evidence-based — instrument it instead.

## Highest-value thing to build next

**The notification-outcome ledger, with a person-declared signal rather than an inferred one.** Every
adaptive idea in the session is blocked on it, it is small, and the evidence says the signal we would
naturally have reached for — app opens — is the wrong one. Building it wrong is worse than not building
it, and right now we cannot build it at all.
