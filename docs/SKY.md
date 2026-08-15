# The Sky — design specification

> Status: **designed, not built.** Specifies section 2 of [PLAN_2026-08-NEXT.md](./PLAN_2026-08-NEXT.md).
> No Kotlin has been written. Nothing here has been compiled, rendered or measured — there is no
> Android SDK in the authoring environment, so every performance number below is a *budget to verify*,
> not a result.

The foundation ships today: [`YearInStarsGrid.kt`](../app/src/main/java/com/daymark/app/ui/components/YearInStarsGrid.kt)
draws a year as a night sky, and [`ReviewYearScreen.kt`](../app/src/main/java/com/daymark/app/ui/insights/ReviewYearScreen.kt)
already walks a person through one. The design system reserves the surface and its tokens
(`--c-sky-bg #16150F`, `--c-sky-ink #EBE5D8`, `--c-sky-faint #8E887A`, "night-sky parity").

**The Sky is that idea promoted from a chart to a place.** A chart is looked at; a place is entered,
moved through, and returned to. The difference is not visual polish — it is that a place is *stable*
(nothing moves once drawn), *inhabited* (it has more in it than the data), and *navigable at more
than one scale*.

§9 records where the shipped component contradicts this specification. Those are statements about
what the Sky must do, **not** instructions to change `YearInStarsGrid` — see §12.

---

## 1. The rule that decides whether this is beautiful or cruel

> **Every period has stars. Nothing is empty, and a hard stretch is never a void.**

This is the load-bearing rule, and everything in §2–§4 exists to serve it. It is worth writing the
argument out in full, because the failure it prevents is not obvious and is very easy to reintroduce
by accident — the shipped component's own docstring reintroduces it in one sentence (§9).

### 1.1 Why a sparse sky is a cruel object

Four steps, each individually reasonable, ending somewhere indefensible:

1. **A history of a life rendered as light will be read as a judgement of that life.** This is not a
   risk of the metaphor; it is the metaphor. "A beautiful unique ID of who you were" is the pitch
   precisely *because* the person will read it as a portrait of themselves.
2. **Depression reduces logging.** Energy, self-monitoring capacity, and shame about what the record
   would say all push the same direction. This is not speculation about our users; it is why the
   product bans lapse-referencing notifications ([D6](./DECISIONS_2026-08.md#d6-things-we-are-deliberately-not-building))
   and why coverage is reported to clinicians *uninterpolated* rather than smoothed
   ([PLAN §1](./PLAN_2026-08-NEXT.md), side 2).
3. **Therefore data quantity is inversely correlated with how hard the period was.** The months with
   the fewest rows are, systematically, the months that took the most to get through.
4. **So a sky that draws data quantity draws an inverted map of suffering** and hands it to the
   person as a picture of themselves. The months they survived would render as the months that did
   not happen.

Two further harms compound it:

- **It is a permanent artefact, not a passing screen.** A bad chart is closed. A place is revisited,
  and it is revisited *most* by someone looking for evidence about their own worth — which is exactly
  the moment the void is worst.
- **It is retroactive punishment for not using the app.** That converts the log into an obligation and
  the person's attendance into the score. It is the streak failure mode
  ([D6](./DECISIONS_2026-08.md#d6-things-we-are-deliberately-not-building)) with a longer memory: a
  streak forgets, a sky does not.

> The person is not the only reader. Someone who is shown their own sky at a bad moment is being
> shown an argument. It must not be an argument that their hardest months were empty.

### 1.2 The four mechanisms that make it structurally true

Not a guideline — four concrete properties, each independently checkable. Statements of intent do not
survive a redesign; these do.

| # | Mechanism | Rule | How it is checked |
|---|---|---|---|
| **M1** | **A uniform decorative field** | The sky itself is dense with faint non-data specks, at **identical density everywhere**. It is decorative, declared decorative, and excluded from every text equivalent. | The field generator takes no data argument. Fails review if it can see a timestamp. |
| **M2** | **Absence has no glyph** | A day with nothing logged draws **nothing** — not a faint speck, not a dimmed cell, not a placeholder. | No branch anywhere renders a marker for a date with no records. |
| **M3** | **No ruler** | No day gridlines, no 31-slot rows, no tick marks, no axis, no baseline of dates. The gutter carries month names and nothing else. | There is no per-day cell geometry in the layout code. |
| **M4** | **Equal presence across the ramp** | Every data star, at every mood level, meets the **same minimum contrast** against the sky ground and is drawn at the **same core size**. A hard day's star is exactly as present as a good day's. | A unit test asserting equal core radius and equal-or-better contrast for levels 1–5 (§10.2). |

**Why all four are needed.** M1 alone leaves the person's stars floating in a field of fake ones —
better, but a sparse month still reads as *less of me*. M2 removes the thing the eye counts against.
M3 removes the thing that says how many there *should* be. M4 stops the remaining stars from
whispering. Take any one away and the void comes back in a weaker form:

- Without M1, three stars in a month looks like a broken render.
- Without M2, a stretch of grey specks *is* the void, drawn politely.
- Without M3, the empty slots between stars are countable, and someone will count them.
- Without M4, a month of hard days is a month of dim stars, which is the original cruelty at lower
  amplitude.

**M1's own trap:** the decorative field must be **uniform**, never denser where data is sparse. A
field that fills in the gaps is a field that encodes the gaps. Compensation is a form of measurement.

### 1.3 What this rule does *not* license

It does not license inventing data. The Sky never draws a star for something that did not happen.
The field is not stars — it is sky, and it says so. The honest sentence is: *the sky is always full;
your stars are the ones you made.*

---

## 2. What becomes a star

Six kinds, exactly. This list is closed; adding a seventh is a design decision, not a feature detail.

| # | Kind | Source of truth | What one star means | Authored by |
|---|---|---|---|---|
| 1 | **Check-in** | `MoodEntry` | one logged check-in | the person |
| 2 | **Exercise** | completed exercise / assignment run | one completed run | the person |
| 3 | **Journal entry** | `JournalEntry` | one entry written | the person |
| 4 | **Goal reached** | `Goal` (→ project, per [D5](./DECISIONS_2026-08.md#d5-goals-become-projects--containers-for-concrete-steps)) | a goal the person marked reached | the person |
| 5 | **Project step** | a step within a project | one step completed | the person |
| 6 | **Life event** | a new Sky-owned record | a major life event | **the person, explicitly** |

**Every kind is an act the person performed.** Nothing on the Sky is derived, detected, scored,
inferred or synthesised. If a star is on the Sky, the person did the thing. This is the single
sentence that makes the whole surface defensible, and it is why kind 6 is the only new data type.

### 2.1 Rules that fall out of "every star is an act"

- **A star is created when the record is created, and destroyed when the record is deleted.** No
  tombstone, no ghost, no "an entry was here". Deletion is complete and leaves no shape.
- **No star for a thing the software did.** Not for a notification sent, not for an offer made, not
  for an arbiter decision, not for an app open. The reception ledger
  ([D1a](./DECISIONS_2026-08.md#d1a-it-reads-its-own-reception-and-infers-no-clinical-state)) is the
  arbiter's private business and never surfaces here.
- **No star for a thing declined.** A declined suggestion is invisible everywhere
  ([D5](./DECISIONS_2026-08.md#d5-goals-become-projects--containers-for-concrete-steps)), including
  here. An abandoned project's completed steps keep their stars — the steps happened; abandoning is
  not a failure and does not retract history.
- **No negative star.** There is no glyph for a missed reminder, a skipped exercise, or a broken
  intention. The Sky records what was done, never what was not.
- **A goal reached is one star, not a bigger one.** Reaching a goal is a kind, not a rank (§3.4).

### 2.2 Life events — the only authored kind, and the only new record

A life event is a person-written mark: a short title, a date (or a date range), and nothing else. It
is the only star with prose the person composed for the Sky specifically.

- **Never inferred, never suggested, never prompted for.** The app does not notice that entries
  changed and ask "did something happen?" — that is inference wearing a question mark, and it is
  [D1a](./DECISIONS_2026-08.md#d1a-it-reads-its-own-reception-and-infers-no-clinical-state) with a
  friendly face. The affordance is a plain "add a life event" control on the Sky and nowhere else.
- **No categories, no picker, no taxonomy.** No "job / relationship / loss / move" list. A taxonomy is
  the software deciding what counts as a life, and a bereavement chosen from a dropdown is worse than
  one typed. Free text, the person's own words.
- **No mood attached, no valence.** A life event is not good or bad. It has no colour (§3.2) and the
  UI never asks how it felt.
- **Editable and deletable forever**, with no record of the edit.
- **It is the one star allowed to be visually prominent** (§3.4) — because prominence follows
  *authorship*, not scoring. The person decided this mattered; the Sky agrees with them.

### 2.3 The date a star sits on

The **local date the act was recorded**, from the record's own timestamp. Never a derived date, never
a backfilled one, never "the day it was about". A journal entry written at 03:00 on the 4th about the
3rd is a star on the 4th, because that is when the person wrote it.

Time-of-day is *not* encoded in position (§3.1) — it is available in the star's detail (§5) and in the
text equivalent. Encoding time-of-day spatially would draw the person's sleep pattern across the
whole surface, which is both an inference vector and, for anyone whose nights are bad, another
picture of the thing they already know.

---

## 3. How a star is placed, and what varies

### 3.1 Placement — time is the sky's geography

The layout generalises the shipped component rather than replacing it: **one row per month**, days
running left to right within a row, months stacking downward, unbounded in both directions of the
person's history.

```
             ┌─────────────────────────────────────────────┐
     2024    │  ·   *      ·        *   ·      ·    *      │  ← a month = a row
      Nov    │      ·   *       ·          *        ·   *  │
             ├─────────────────────────────────────────────┤
      Dec    │   *      ·   *  ·      *        ·           │
             └─────────────────────────────────────────────┘
               ← earlier in the month        later in the month →
```

Two coordinates, two pure functions:

- **x = f(timestamp)** — monotonic in time within the row. Same day, same column, forever.
- **y = g(kind, record id)** — a stable hash, so several stars on one day scatter vertically instead
  of stacking, and the field looks organic rather than gridded. This is the `hash(month, day)` jitter
  in the shipped component, promoted to key off the record's identity so it is stable per *star*,
  not per *day*.

**Three properties this buys, all of which the "place" framing depends on:**

1. **A star never moves.** Position depends only on the record's own timestamp and id — not on how
   many other stars exist, not on the viewport, not on the zoom level. Adding today's check-in does
   not reflow 2019. A place whose furniture rearranges is not a place, and an artefact described as
   "a unique ID of who you were" cannot be one if it is different every time it is opened.
2. **Zoom is a transform, not a relayout.** Every zoom level is the same coordinates at a different
   scale. Nothing reflows, nothing is recomputed, and the eye can follow a star through a zoom.
3. **Visible range is a contiguous index range.** Monotonic x + time-ordered data means culling is a
   binary search, not a scan (§11).

**Rejected: constellation clustering.** Grouping related stars into figures is the prettiest version
of this and it is out, on the grounds that a cluster asserts a relationship. Either the software
picked the grouping — which is inference — or the person did, which is a feature nobody asked for.
The one linkage that survives is the project thread (§3.4, kind 5), because the person authored the
project and the linkage is therefore *declared*, not detected.

### 3.2 Colour — the mood ramp, and only the mood ramp

> **A star is coloured if and only if the person attached a mood to that record. The colour is that
> mood's ramp colour. There is no other use of colour on the Sky.**

Everything else — every kind, every state, every interface affordance — is drawn in the sky's own ink
(`#EBE5D8`) or its faint variant (`#8E887A`). Colour on the Sky means one thing: *this is a mood you
recorded, at the point on your own scale where you put it.*

This is the product invariant that the mood ramp encodes a person's data and never interface state,
applied to the surface where it is hardest to hold. It has three consequences worth stating, because
each is a rule someone will otherwise break in good faith:

- **Kind is never carried by colour** (§3.3). The obvious design — six kinds, six hues — is banned,
  because it would put interface taxonomy on the ramp.
- **Uncoloured is not lesser.** Four of the six kinds have no mood and therefore no colour. An
  ink-coloured star is a full star; the sky's ink is its brightest value, not its dimmest.
- **No colour outside the ramp.** No accent, no highlight hue, no selection tint, no warning amber, no
  `--success` green, no "all clear". Selection and focus are drawn with a ring and a change in
  stroke, never a colour.

Mood colours are theme-provided and **person-overridable** — `MoodColors.withOverrides` already
supports it — so a custom palette carries into the Sky unchanged. The Sky must not hardcode mood hues.

### 3.3 Kind — carried by form, distinguishable without colour

All six glyphs share a core disc, so the surface reads as a starfield rather than a symbol chart.
They differ in what surrounds the core:

| Kind | Form | Silhouette in one word |
|---|---|---|
| Check-in | core + soft halo | a breath |
| Exercise | core + open ring | a circuit |
| Journal entry | core + short underline beneath | a written line |
| Goal reached | core + four-point cross-rays | a glint |
| Project step | core + a hairline **thread** back to the previous step of the same project | a link |
| Life event | wide sparse diffraction cross + fine outer ring | a marker |

**The rules that keep this legible:**

- **Silhouette first.** Every glyph must be identifiable in solid monochrome, at the target size, with
  colour removed entirely. If it needs its colour, it is not a glyph.
- **Colour is never a differentiator, and motion is never a differentiator.** Motion is excluded from
  meaning outright, because motion is the first thing that goes under reduced-motion (§10.4) and the
  meaning must survive its removal.
- **Kind is not readable at the overview zoom, and that is correct.** At L0 (§4) every star is a
  point. L0 is texture — *the shape of a life at arm's length* — and reading it as information is not
  the job. Kind resolves from L2 inward.
- **The key is always one tap away**, at every zoom level, and it is not a tutorial (§6): it names the
  six forms and the five mood colours, permanently, for anyone who missed an introduction, cannot
  distinguish the hues, or simply forgot.
- **The project thread is the only line on the Sky.** No other connectors, ever. It is drawn faint,
  it does not extend past the visible steps, and it disappears entirely at L0.

### 3.4 Quietness — the character of a mood, never its rank

The plan's phrasing is that hard periods are *differently coloured and quieter*, not missing. That
word is the most dangerous one in the spec, because "quieter" slides into "dimmer" slides into
"worth less" without anyone deciding to make it so. So it is defined by what it excludes:

> **Quietness changes a star's *character*, never its *presence*.** A quiet star is not smaller, not
> fainter, and not lower-contrast. It is *softer-edged*: a wider, more diffuse halo and a gentler
> falloff, against the same core at the same size and the same contrast (M4).

| Varies with mood | Fixed across all moods |
|---|---|
| Hue (the ramp) | Core radius |
| Edge softness / halo diffusion | Contrast against the sky ground |
| — | Presence of the core at all |

So a hard day is a soft, wide, warm-rust star and a good day is a crisp, tight, sage one. **Neither is
brighter. Neither is bigger.** A person scanning a hard month sees a month full of stars with a
different quality of light, which is true, rather than a month of faint ones, which is a verdict.

**No brightness ranking of days, stated as a testable property:** there is no monotonic visual
quantity — size, opacity, glow radius, ray count, contrast — that increases with mood level. §10.2
gives the test.

**Prominence follows authorship, not value.** The one form allowed to be visually louder is the
life-event marker (kind 6), and it is louder because the person deliberately placed it, not because
the software judged it important. Nothing the software creates outranks anything else the software
creates.

---

## 4. Zoom and focus — overview to a single star

Five levels. Zoom is continuous (pinch, double-tap, or the platform accessibility zoom gesture); the
levels are thresholds at which detail appears, not discrete screens. **Nothing reflows across a zoom**
(§3.1), so a star can be followed from L0 to L4 by eye.

| | Level | Shows | Detail that appears |
|---|---|---|---|
| **L0** | **Drift** | years at once | Points only. Year marks in the gutter. This is the ambient view, the keepsake, the one you leave open. |
| **L1** | **Season** | ~3 months | Kind glyphs begin to resolve. Month names in the gutter. |
| **L2** | **Month** | one month row across the width | Full glyphs. Project threads visible. Stars become individually focusable. |
| **L3** | **Night** | one day | That day's stars spread apart, each with a leader line and a one-line name. The sky continues at the edges — this is not a modal. |
| **L4** | **Star** | one star | A sheet: what it was, when, its mood if it had one, and an action to open the underlying record. |

**The transition is slow and continuous.** The brief is "a slow focus into a full screen starry sky";
the interaction rule that follows is that zoom is *inertial and damped* rather than stepped, and that
L0→L1→L2 is a focus pull, not a page change. Under reduced motion it becomes an instant cut (§10.4)
and everything still works.

**L3 is the level that makes it a place.** A day is not a modal that covers the sky — it is a part of
the sky you have come close to. The rest of the month stays visible at the margins, dimmed but not
hidden, so there is no "back" to press and no sense of having left.

### 4.1 What L4 shows — and what it never shows

> **The Sky never renders journal prose. It shows *that* you wrote, not *what* you wrote.**

A journal star at L4 reads as a kind, a date, a time, and an action — "Open it" — that leaves the Sky
and goes to the journal, behind whatever lock the journal already has.

This extends the reasoning behind the ban on note excerpts in the companion
([D6](./DECISIONS_2026-08.md#d6-things-we-are-deliberately-not-building)): what is protected is *a
place to write without an audience*, and an excerpt surfacing somewhere the person did not go to read
it makes the writing feel watched. The Sky is a worse place for it than the companion, for two extra
reasons: it is a surface people show to other people ("look at my sky"), and it is a surface people
screenshot. A shoulder-surfer at L4 must learn nothing but that something was written.

The same restraint applies more weakly to the other kinds — an exercise star names the exercise, a
check-in star gives the mood label the person chose and their own note only if they tap through. When
in doubt, the Sky names the act and hands off to the feature that owns the content.

### 4.2 Navigation invariants

- **Anywhere is one gesture from anywhere.** No wizard, no forced sequence, no progress dots.
- **The Sky has no "start".** It opens where the person last was, or at today if they have not been.
- **Time never scrolls past the present.** There is nothing after today — no empty future to fill in,
  no forward slots. The future is not a void because it is not drawn.
- **No "jump to your best month".** There is no ranked destination, because there is no ranking (§7).
  Navigation targets are dates and nothing else.

---

## 5. The tutorial is the sky itself

No walkthrough, no carousel, no coach marks, no dismissible tooltips with a "3 of 5".

**A new sky has one star.** The person's first check-in, with a hairline leader to a short line naming
what it is. That is the entire onboarding, and it is also the best empty state in the product: a sky
with one star says what the app is for better than any onboarding copy could.

**Before the first check-in**, the Sky is the decorative field and one line. It is still a sky — M1
means it is never a blank screen — and it is honest, because there is nothing of the person's in it
yet.

### 5.1 Accretion — each *kind* introduces itself once, ever

| Rule | Detail |
|---|---|
| **Once per kind, ever** | One persisted flag per kind (six flags, local, never synced). Once set, never unset — not by reinstall-from-backup, not by a new device restoring the person's data. |
| **On first sight, not on creation** | The introduction appears the next time the Sky is *opened* after a kind's first star exists. It never interrupts the act of writing or checking in. |
| **One at a time** | Never more than one introduction per opening of the Sky. If three kinds appeared since last time, they queue across three visits. |
| **Naming, not praise** | "A goal you reached." — not "Nice work!". Congratulation is evaluation, and evaluation is the thing the Sky does not do. No exclamation marks anywhere on this surface. |
| **Dismissed by use** | Tapping anywhere, or opening the star, ends it. There is no dismiss button and no "got it". |
| **Never repeated, always recoverable** | It does not come back. The key (§3.3) carries the same information permanently for anyone who missed it. |

**The introduction copy is the only copy the Sky adds**, it is one line, and it is a noun phrase
naming the form. Proposed copy is *new copy for a new surface*, not fixed copy — but any disclaimer,
privacy or non-diagnostic sentence that appears anywhere near the Sky (§7.4) must be **reused verbatim
from the existing constants**, never re-authored to fit the tone here.

**No introduction is ever a notification.** The Sky never notifies. See §7.5.

---

## 6. What it must never do

Each of these is a thing a reasonable person will propose, with the reason it is out.

### 6.1 Never infer a life event

No "we noticed a change around March — did something happen?". Not as a prompt, not as a suggestion,
not as a gap the UI invites you to fill. Detecting a discontinuity in someone's data and asking them
to explain it is inference with a question mark on the end — the failure mode
[D1a](./DECISIONS_2026-08.md#d1a-it-reads-its-own-reception-and-infers-no-clinical-state) exists to
prevent — and it is worse here because the thing it would most reliably detect is a period of not
coping. Life events are authored (§2.2) or they do not exist.

### 6.2 Never rank days

No best day, no worst day, no brightest month, no biggest year, no "your strongest week". No sorting
by anything but time. No superlatives in any copy. No visual quantity that increases with mood (§3.4).
No leaderboard against the person's own past, which is the form this always takes.

Two things this specifically forbids that the product has shipped elsewhere: **"brightest month"** and
**"longest streak"** (§9). Streaks are out product-wide
([D6](./DECISIONS_2026-08.md#d6-things-we-are-deliberately-not-building)); on a permanent artefact
they are worse, because a broken streak drawn into a place is a scar with a date on it.

### 6.3 Never show a completeness metric

No percentage, no "N of 365", no coverage ring, no density score, no "your sky is 40% full", no count
presented as a headline. **The sky is not a progress bar**, and it cannot become one, because M3 means
there is no denominator drawn anywhere.

**The one place a count is allowed, and why.** The text equivalent (§10.5) needs list semantics —
"March, 6 items" — or it is unnavigable, and withholding that from a screen-reader user to protect
them from a number they never asked to compare is a worse harm than the number. The line:

> A count may be given **as list structure, at the point of navigation**. It may never be aggregated
> into a headline, compared across periods, trended, or shown to someone who did not need it to move
> around.

### 6.4 Never leave the device

- **Not in the clinician report.** Not on any of the four sides, not as a cover image, not as a
  thumbnail. The report carries scores and bands; the Sky is neither.
- **Not in a share, a grant, or a sync.** No capability, no scope, no access level ever includes it.
- **Not in telemetry, analytics, crash reports or diagnostic bundles** — including *derived* values.
  No star counts, no kind distribution, no "sky opened" event with a payload. The safest form of this
  rule: nothing computed from Sky data is ever written anywhere the person did not choose.
- **No share affordance on the surface.** No share icon, no "post your sky", no system share sheet.
- **If an export exists at all** it goes through the OS document picker so the person chooses the
  destination, and it must carry a one-time note that this image is unusually identifying (§6.5) —
  wording drawn from existing privacy copy, not improvised here. There is a real, specific hazard:
  an exported PNG lands in the device gallery and is then synced to a cloud photo backup by software
  the person forgot they enabled. The export is the moment the "never leaves the device" guarantee
  passes out of our hands, and it should be designed as that moment rather than as a save button.
- **Behind the app lock**, on the same terms as the journal.

### 6.5 It is the most identifying artefact the product could produce

Worth stating plainly, because it is the reason for §6.4 and it is counter-intuitive — the Sky
contains no prose (§4.1), so it *feels* less sensitive than the journal. It is not:

- It is a **multi-year, per-record behavioural timeline of one person**: when they act, how often,
  which kinds, and where the gaps are. Timing patterns alone re-identify.
- Its *shape* discloses what its content does not: a three-month gap with a dense fortnight either
  side has a shape, and someone who knows the person can read it.
- The person's **own life-event text** is on it, in their own words, at the moments that mattered most.
- **It is unique by construction.** That is the pitch. A "beautiful unique ID" is still an identifier.
- It is the artefact people **screenshot and show to someone**, which is exactly the disclosure path
  no technical control covers.

Treat it as the highest-sensitivity surface in the product, above the journal, and design every
affordance on it as if a screenshot of that affordance will exist.

### 6.6 Never notify, never nag, never congratulate

No notification about the Sky, ever. Not "your sky has grown", not "you haven't visited in a while"
(lapse-referencing, banned outright in
[D6](./DECISIONS_2026-08.md#d6-things-we-are-deliberately-not-building)), not a badge, not a dot, not
a widget. A place you are summoned to is not a place you own.

No congratulation on the surface itself (§5.1). No milestone stars, no anniversary effects, no
unlocks. **No gamification without the gate described in the plan.**

### 6.7 Never model, never predict, never explain

The Sky is rules-based, deterministic, and explainable in one sentence per element. No ML, no
clustering, no anomaly detection, no "insights", no pairing of one kind against another (D6 already
bans auto-generated mood/activity insights; the Sky is a very tempting place to reintroduce them
spatially). The Sky *shows*; the person *concludes*.

---

## 7. Accessibility — the honest section

**A starfield is a hostile surface.** Small low-contrast points on a dark ground, meaning carried by
tiny geometry, spatial layout as the primary index, and a canvas with thousands of nodes — it is close
to a worked example of what not to build. This section is written on the assumption that saying so is
more useful than a list of mitigations that imply the problem is solved.

**The commitment: if the parallel list surface (§7.5) is not built, the Sky does not ship.** It is not
a fallback and it is not an `aria-label` on a canvas. It is a peer presentation of the same data,
reachable from the same place, with the same actions.

### 7.1 Low vision

The aesthetic is the problem: the sky is beautiful *because* it is dim, sparse and low-contrast.

- **Core contrast is a hard floor, not an aspiration.** Every data star's core must meet at least
  4.5:1 against `#16150F`, at every mood level, in every person-customised palette. The sky's ink
  (`#EBE5D8` on `#16150F`) is comfortable; **the mood ramp has not been verified** — `MoodAwful
  #AE5747` against the sky ground is the one to check first, and the shipped component's 18%
  lerp-toward-white exists precisely because the raw ramp was too dark. **Nobody has measured this.**
  See §12.
- **Custom palettes break the floor.** A person can override mood colours. Either the Sky applies the
  same luminance lift the shipped component does and re-checks, or overrides that fall below the floor
  are lifted on this surface only. This must be decided, not left to chance.
- **A "quiet sky" mode**, in the Sky's own controls, not buried in Settings: suppresses the decorative
  field entirely (it is the single biggest impediment to finding real stars), raises every glyph to
  maximum contrast, thickens strokes, and drops halos. This is the low-vision presentation and it
  should be one tap from the surface. It respects the platform contrast preference as a default but
  must be independently toggleable, because the platform signal is coarse.
- **Touch targets are 48dp regardless of drawn size.** A star drawn at 4dp is still a 48dp target;
  where targets would overlap, the tap resolves to the nearest star centre rather than shrinking
  anything.
- **Nothing on the Sky is fixed-size text.** All labels scale with the platform text setting, and the
  layout must survive 200% without clipping — which mostly means labels are leader-lines into open
  sky, not boxes.

### 7.2 Colour vision

Colour never carries kind (§3.3), so no information is lost to colour blindness — *except mood*, and
mood is the one thing colour does carry.

Honest statement of the problem: the ramp runs rust → ochre → sage → green (`#AE5747` → `#5E8A66`),
which is a red-green axis, the worst available axis for deuteranopia and protanopia. It is a
product-wide token and not the Sky's to redefine.

What the Sky does about it:

- **Mood is never available *only* as colour.** It is in the star's accessible name, in its L3/L4
  detail, and in the text equivalent, always as the person's own label.
- **The key (§3.3) lists the ramp with labels**, permanently, at every zoom.
- **Person overrides already exist** and carry into the Sky unchanged.
- **The Sky adds no second colour axis** that would compound the problem.

What it does *not* do: encode mood redundantly in shape. That slot is taken by kind, and doubling it
up would make every glyph a two-variable puzzle.

### 7.3 Screen readers

A canvas of thousands of points is not navigable, and no amount of semantics on it makes it so.

- **L0/L1**: the canvas is a **single node** described by what it is, not what it contains — *"Your
  sky, 2019 to 2026"*. It does not enumerate, and it does not announce a total (§6.3).
- **L2/L3**: individual stars become focusable, in **time order**, with a real accessible name (kind,
  date, mood label if any) and the same activation action as touch.
- **Traversal is temporal, never geometric.** Next/previous moves through time. Arrow-key or
  swipe-navigation by *position* in a jittered field is chaos and must not be offered.
- **The decorative field is `clearAndSetSemantics {}`** — invisible to assistive tech, and absent from
  the text equivalent. It is sky, not data (M1).
- **A month with no stars is never announced.** No "no entries for March". Absence is not commented on
  in any modality; that is M2 in text form, and it is the rule most likely to be broken by someone
  being helpful.
- **Live regions: none.** The Sky never announces changes.

### 7.4 Motion

- Any drift, parallax, twinkle or shimmer is **decoration only** and carries no meaning (§3.3).
- Under reduced motion: **all of it stops**, zoom transitions become instant cuts, and the surface
  remains fully functional. Nothing is learned only by watching.
- **There is currently no reduced-motion handling anywhere in the Android app** — no helper, no
  preference, nothing to hook into. See §12; this is a prerequisite, not a detail.
- Twinkle is a vestibular risk on a full-screen surface. If it ships at all it is off by default, slow,
  low-amplitude, and never on more than a small fraction of the field at once.

### 7.5 The text equivalent — a peer surface, not a fallback

One toggle on the Sky switches between the field and the list. Same data, same actions, same rules.

- **Months as headings, in time order. Only months that have stars get a heading.** An empty month
  heading with no rows under it is the void, rendered in text — the list must skip from March to July
  without comment, exactly as the sky does (M2).
- **One row per star**: kind, date, time, mood label if any, and the same open action.
- **List counts are allowed as list structure** — "March, 6 items" — under the rule in §6.3, because
  without them the list is unnavigable. No totals, no comparisons, no trends.
- **The decorative field does not appear.** There is nothing to represent; it is not data.
- **No summary, no overview paragraph, no "your year in words".** The list is the data, not a reading
  of it.

The honest cost: the list is a genuinely different experience. It is navigable, complete and equal in
*information*, and it is not the same object emotionally — nobody sits and looks at a list. The
project does not have a good answer to that, and pretending the list is the same experience would be
worse than admitting it is not. What can be said is that the list is not a lesser tier: it gets the
same design attention, the same actions, and it ships at the same time or the Sky does not ship.

### 7.6 Cognitive and situational

- **No time limits, no auto-advance, no ambient animation that pulls the eye.** The Sky waits.
- **No dead ends.** Every level has a way out that does not require finding a specific small target.
- **The key is permanent** and does not depend on having seen an introduction (§5.1).

---

## 8. Performance with years of data

No measurement is possible in this environment. Everything below is a **budget and a strategy**;
§12 lists what must actually be measured.

### 8.1 The scale to design for

| Profile | Span | Stars | Notes |
|---|---|---|---|
| Typical | 2 years | ~1,200 | one check-in most days, occasional other kinds |
| Heavy | 5 years | ~6,000 | 2 check-ins/day plus exercises, journal, project steps |
| Extreme | 10 years | ~15,000 | the number the architecture must not fall over at |

Plus the decorative field, which is a fixed cost independent of data.

### 8.2 The eight rules

1. **Precompute the layout once, off the main thread.** Position is a pure function of (timestamp, id)
   (§3.1), so it is computed when the data changes, never per frame.
2. **Structure of arrays, not objects.** Packed `FloatArray`/`IntArray` of x, y, kind, level — not
   `List<Star>`. 15,000 objects is avoidable GC pressure on a surface whose whole job is smooth
   scrolling.
3. **Zero allocation in the draw phase.** The shipped component allocates an `Offset` per star per
   frame; correct for 372 stars, not for 15,000. The hot path allocates nothing.
4. **Cull by binary search, not by scanning.** Monotonic x over time-ordered data means the visible
   slice is a contiguous index range. This is the direct payoff of the layout choice in §3.1.
5. **Level of detail is the main lever.** At L0 a star is a point: one batched point-draw over a
   packed array, not 15,000 individual circle calls with halos. The shipped `drawMoodStar` issues 3–5
   draw operations per star; at 15,000 stars that is up to 75,000 operations per frame and it will not
   hold a frame budget. Full glyphs render only at L2+, where **at most a few hundred stars are on
   screen by construction** — the month-row layout bounds it.
6. **The decorative field is a static cached layer.** Generated once, drawn into a cached picture, and
   translated — never regenerated per frame. Its seed is **persisted per install**, because a sky whose
   background changes between openings is not a place (§3.1).
7. **Project the query; never load content.** One narrow query per kind returning `(id, epochMillis,
   moodLevel?)` and nothing else. **The Sky never loads journal text at all** — a privacy property
   (§4.1) that is also the single biggest memory win. 15,000 stars × 12 bytes ≈ 180 KB, which is
   nothing; 15,000 journal bodies is not.
8. **Zoom never re-queries and never re-lays-out.** Zoom is a matrix on precomputed coordinates.
   Appending a new star is O(1) amortised, because stars arrive in time order and x is monotonic.

### 8.3 Budgets to verify

- **Cold open to first paint: < 300 ms** with 10 years of data on a mid-range device.
- **Sustained 60 fps** while flinging at L0 with 15,000 stars.
- **No jank on zoom** at any level.
- **Peak heap attributable to the Sky: single-digit MB.**
- **The field's cached layer must not be a full-screen bitmap per frame** — verify what the cache
  actually costs on a large display.

If L0 cannot hold the frame budget at the extreme profile, the correct response is **more aggressive
LOD** — down to a pre-rasterised tile per month — and never sampling, decimation, or dropping stars.
Dropping stars to hit a frame budget would mean the sky is less full for the people with the most
history, which is M1 broken by a performance decision.

---

## 9. Where the shipped foundation contradicts this

`YearInStarsGrid` and `ReviewYearScreen` are the right idea and the reason this is buildable. They
also encode the exact failure §1 exists to prevent, and its own docstring says so out loud:

> *"Unlogged days are faint specks, so the **amount of twinkle** itself reads as how a stretch of life
> went."*

That sentence is the cruel object, stated as an intention. Reading it is the strongest argument that
§1's four mechanisms need to be structural rather than advisory.

| Shipped today | Rule | The Sky |
|---|---|---|
| Star radius 1.7→3.9 dp by mood level | M4, §3.4 | Constant core radius; mood never changes size |
| Cross-ray glint only for levels 4–5 | §3.4 | Rays are the *goal reached* glyph — a kind, never a mood reward |
| Glow discs added only for levels 4–5 | M4 | Halo is present at every level; only its *diffusion* varies |
| Unlogged day → `NightInk` speck at α 0.12 | M2 | Absence has no glyph |
| Fixed 31-column geometry per month row | M3 | No per-day cells, no ruler |
| Legend: *"a soft dot most days · a glint on the bright ones"* | §6.2 | No "bright ones"; the key names kinds and moods |
| Starfield seeded per screen, 70 specks | M1, §8.2 r6 | One persisted seed, uniform density, cached layer |
| `ReviewYearScreen` finale: **"longest streak"** | D6, §6.2 | No streaks |
| `ReviewYearScreen` finale: **"brightest month"** | §6.2 | No superlatives, no ranking |
| `ReviewYearScreen` finale: **"{n} stars. This was your year."** | §6.3 | No aggregate count as a headline |
| `StarCluster` is `clearAndSetSemantics {}` with summary text as the equivalent | §7.5 | A peer list surface, not a summary sentence |

**These are statements about the Sky, not a change request against those files.** They belong to other
owners and are listed here so the divergence is a decision someone makes rather than a surprise
someone discovers. See §12.

---

## 10. Checkable properties

The rules that must be unit-testable, so they survive a redesign by someone who has not read this.

| # | Property | Test |
|---|---|---|
| **P1** | No monotonic visual quantity tracks mood | For levels 1–5: core radius equal, core alpha equal, contrast-vs-ground equal-or-above floor, ray count equal. Fails if any is ordered by level. |
| **P2** | Absence has no glyph | Layout for a date range with zero records produces zero draw instructions for data stars. |
| **P3** | The field cannot see data | The field generator's signature takes a seed and a size, and no data type. Enforced at the type level. |
| **P4** | The field is uniform | Field density over any two equal-area regions is within tolerance, independent of the data in them. |
| **P5** | Star position is stable | Position for a record is identical before and after inserting 1,000 unrelated records, at every zoom level. |
| **P6** | Colour is only the ramp | Every colour used for a data star is a ramp colour or the sky's ink/faint token. No other value appears. |
| **P7** | Kind survives monochrome | Glyph rasterisation at the target size, colour stripped, is pairwise distinct across the six kinds. |
| **P8** | No content leaves the record | The Sky's query projections contain no text column. Enforced at the DAO signature. |
| **P9** | Introductions are once-only | Setting a kind's flag makes it unreachable; no path clears it. |
| **P10** | Text equivalent skips empty months | A month with zero stars produces no heading and no row. |
| **P11** | No aggregate count is rendered | No total-star count appears outside list-navigation semantics. |
| **P12** | Deletion is complete | Deleting a record removes its star and leaves no tombstone in the layout. |

Per the standing rule in [PLAN §4](./PLAN_2026-08-NEXT.md): each of these must be confirmed to
actually fail when its subject is violated, with the violation verified to have landed in the file
before the test is run.

---

## 11. Open questions

1. **Where does the Sky live in navigation?** It is a place, which argues for a top-level destination;
   it is also the most identifying surface in the product (§6.5), which argues for it being behind the
   lock and not on the tab bar. Not resolved here.
2. **Does the export exist at all?** §6.4 specifies how it must behave *if* it exists. The safer
   product may be no export. The shipped `ReviewYearScreen` already has "Save keepsake", so this is a
   live question rather than a hypothetical.
3. **Life events with a date range** — a bereavement is a day, a move is a fortnight. Ranges are
   specified as possible in §2.2 but not designed; a range star may need to be a different form.
4. **The mood-ramp contrast floor under custom palettes** (§7.1) — lift, reject, or warn.
5. **How the Sky relates to "Review my year"** — the walkthrough is a curated sequence with
   superlatives in it; the Sky forbids superlatives. Either the walkthrough changes, or the two
   surfaces coexist with different rules, which is a coherence problem worth deciding deliberately.
6. **What happens on a very small screen**, where a month row is a few hundred pixels and L2 cannot
   separate glyphs.

---

## 12. What a human must verify

Nothing in this document has been compiled, rendered, measured or tested.

1. **The contrast floor is unverified** (§7.1). `MoodAwful #AE5747` and `MoodBad #C27C46` against
   `#16150F` need actual measurement. The shipped 18% lerp-toward-white suggests the raw ramp does not
   clear it. This blocks M4 and P1.
2. **There is no reduced-motion support in the Android app** (§7.4). A grep across
   `app/src/main/java` finds no handling of the platform reduced-motion signal anywhere. Whatever the
   Sky hooks into does not yet exist.
3. **The shipped foundation contradicts the plan's invariants in nine places** (§9), most sharply in
   `YearInStarsGrid`'s own docstring ("the amount of twinkle itself reads as how a stretch of life
   went") and in `ReviewYearScreen`'s finale, which shows **longest streak** and **brightest month**.
   `stats/Achievements.kt` additionally ships a streak-based achievement catalogue
   (`streak_7`, `streak_30`, `streak_100`). All of this predates
   [D6](./DECISIONS_2026-08.md#d6-things-we-are-deliberately-not-building) and none of it was touched
   by this work — it needs an owner and a decision.
4. **Performance is budgeted, not measured** (§8.3). Every number is a target.
5. **The life-event record is new schema.** §2.2 describes it; it needs a migration and an owner.
6. **Proposed copy in §5.1 is new copy for a new surface**, not fixed copy. Any non-diagnostic,
   provenance or privacy sentence appearing near the Sky (§6.4) must be reused verbatim from existing
   constants rather than written to fit this surface's tone.
