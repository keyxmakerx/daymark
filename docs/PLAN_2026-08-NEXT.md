# The agreed plan — August 2026

Confirmed before implementation. Supersedes nothing; it sequences work already decided in
[DECISIONS_2026-08.md](./DECISIONS_2026-08.md) and adds two new pieces: the four-side report and the
Sky.

---

## 1. The report — four sides, each dedicated, each filled

Renders: [`design/web-05-report-projects.png`](./design/README.md). Density is wanted; the constraint
that survives is **one job per side**, not sparseness for its own sake.

**Reconciling "fill it up" with the alert-fatigue evidence.** The finding is about elements
*competing for attention in the same glance*, not about page count. So: the flag keeps its own zone at
the top of side 1 and never shares it. Everything below and after may be dense, because a clinician
reading side 2 has already chosen to go looking.

| Side | Job | Contents |
|---|---|---|
| **1 · front** | The glance | The one flag. Per-instrument plots with usual-range band and sampling density. Completion summary — which self-checks, tasks, exercises and goals ran, and what came back. |
| **2 · back** | The detail | Every entry with its check-in note. What was suggested and what happened to it. Goal/project progress. Coverage and gaps, uninterpolated. |
| **3 · front** | In their words | Journal entries the person included. **Off by default.** |
| **4 · back** | For the conversation | Discussion prompts derived from the data. Provenance of every tool. Verification (QR + hash) at the **bottom**. |

### Side 3 — filling it without the software choosing

The ask: pick worst, best and typical periods so the page is full rather than sparse.

**The rule that keeps this honest: the app proposes, the person disposes.** Software may *nominate* a
representative set — the lowest-band week, the highest, and one typical — and must then show that
selection to the person, who adds, removes and confirms before anything is exported. The page then
truthfully says *they* chose it, because they did. What the software may never do is select silently.

### Side 4 — "recommendations based on studies", carefully

**This is the one part of the plan with a real regulatory edge, and it needs a bright line.**

A report that tells a clinician what to do is **clinical decision support** — a regulated category
with a different compliance life than anything Daymark currently is. "Based on study X, consider
increasing session frequency" crosses it. That is not a style preference; it changes what the product
legally is.

**What side 4 carries instead: prompts for discussion, phrased as observations and questions.**

> *Wellbeing entries were lower on the three weeks with no logged activity. Worth asking about?*

That surfaces a pattern in the person's own data and hands the judgement to the two humans. It cites
nothing, prescribes nothing, and is useful precisely because it does not pretend to conclude.

Where the evidence base *is* citable is **psychoeducation for the person**, in the app, about a
practice they are being offered — "this exercise draws on compassion-focused methods" — not as
justification for a clinical action in a clinician's report.

---

## 2. The Sky

> *"a slow focus into a full screen starry sky … click one and you get more detail … a history of who
> you are and where you are going … a beautiful unique ID of who you were and who you are, the history
> of what you've accomplished written into the stars."*

**Foundation exists.** `app/src/main/java/com/daymark/app/ui/components/YearInStarsGrid.kt` ships
today, and the design system already reserves a fixed night-sky surface with its own tokens
(`--c-sky-bg #16150F`, `--c-sky-ink #EBE5D8`, `--c-sky-faint #8E887A`) described as "night-sky
parity". The Sky is that idea promoted from a chart to a place.

**What becomes a star:** a check-in, a completed exercise, a journal entry, a goal reached, a project
step, and a **major life event the person adds themselves**.

### The rule that decides whether this is beautiful or cruel

A history of a person's life, rendered as light, will be read as a judgement of that life. So:

> **Every period has stars. Nothing is empty, and a hard stretch is never a void.**

A sky where depressive episodes are black gaps is a cruel object — it would show someone the shape of
their worst months as absence. Instead: hard periods are *differently coloured and quieter*, not
missing. A month with two entries has two stars, and they are as real as any other month's.

Corollaries:
- **No brightness ranking of days.** Nothing "scores" a period.
- **Nothing is inferred.** Major life events are authored by the person, never detected.
- **No streaks, no completeness metric, no percentage of sky filled.** The sky is not a progress bar.
- **The sky is never shared.** It is the most identifying artefact the product could produce; it is
  local, and it does not enter the report or any grant.

### The tutorial is the sky itself

It needs teaching, and the best teacher is the object. A new person's sky has **one star** — their
first check-in — with a line naming what it is. It fills as they use the app, and each new *kind* of
star introduces itself once. No walkthrough, no carousel.

That also makes the Sky the best empty state in the product: an empty sky with one star says what the
app is for better than any onboarding copy.

---

## 3. Sequencing, and an honest constraint

**There is no Android SDK in this environment and Gradle cannot resolve offline.** Kotlin work cannot
be compiled or tested here — CI is the only oracle, so every Android change is written blind and
verified after push. TypeScript is fully verifiable locally (`pnpm check`, `pnpm test`).

That does not stop the work; it decides the order and the batching.

1. **Verifiable now (TypeScript).** The signal vocabulary as a typed closed list with the author
   partition enforced in validation; the dialogue definition type; companion dialogue content. This is
   the substrate the Android side codes against, and getting the partition wrong is the expensive
   mistake.
2. **Written carefully, verified by CI (Kotlin).** The four-side report, discussion prompts, the
   reception ledger, `SupportOffer` generalised into the arbiter.
3. **Designed, then built (Kotlin).** The Sky.

---

## 4. Standing rules for any agent doing this work

Recorded because one was violated during the Phase 2 workflow, destroying uncommitted work.

- **No `git checkout`, `git reset`, `git clean`, or `git stash`.** Ever, for any reason, including
  "restoring" after a test. Use file copies. Other agents may be working in the same tree.
- No `git commit` — the session owner commits.
- Fixed copy is fixed: banners, disclaimers, provenance text and safety-plan wording are not edited,
  reworded or reflowed.
- Every test must fail when its subject is violated, and the violation must be confirmed to have
  actually landed in the file before the test is run.
