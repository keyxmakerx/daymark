# The Sky, as approved — October 2026

> **Status.** Approved by the maintainer on 2026-10-03. **Nothing in this document is built in the
> app.** `ui/sky/` still draws the September design, which `docs/prototypes/your-sky.html` shows.
> The reference for this one is `docs/prototypes/sky-phone.html`: open it in any browser, on a phone
> or a desktop. A final pass was made after the approval, on the maintainer's instruction to finish
> it: every sky grown from its own seed, drawable constellations, the ringed planet, favourites and
> bridges.
>
> Where this document disagrees with `docs/SKY.md` or with §1 of
> `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md`, this one governs. §9 lists exactly what it keeps from them
> and what it reverses. §10 records the maintainer's answers to the questions the approved prototype
> raised, given later on 2026-10-03, and the few that are still open. The prototype was updated to
> match those answers.

**In one paragraph.** Every star is one memory, and nothing else in the sky is a star. The
background is deep space: colour and gas, no stars of its own. The shapes come from how the person
kept their record. Months of doing their thing almost every day draw a long band, which is rare;
on-and-off weeks gather into small clusters and streams, and a weekly habit kept for years turns into a whirlpool. Life events
are giant stars. A life event the person marked as hard becomes a supernova, and what came after
gathers in its cloud. Memories the person puts away are drawn slowly into a black hole that forms
beside them; bringing them back opens a white hole that sends each one home. Memories tucked away to
find again later become dark matter, unseen except for a faint bend in the light. The person can
join stars into constellations and name them. No two skies are alike: the course of the river, the
shapes, where everything sits and every colour grow from the person's own record and a seed of their
own.

---

## 1. What the maintainer decided

In the order it was decided, across the design rounds of 2026-10-02 and 2026-10-03. The answers
given after approval are in §10.

| Decision | Detail |
|---|---|
| **Every star is a memory** | No background stars at all. The space behind the stars is colour, gas, nebulas and shadow. |
| **Zoom until a star is a sun** | Stars cover the whole space and you zoom in to any one of them. Close up, a star is big and looks like a real sun. Level of detail changes with zoom so phones are not overloaded. |
| **Shape rewards keeping at it** | Doing a daily thing every day forms a long, beautiful band. Inconsistent tracking gives small scattered clusters. Random but realistic smaller formations too, like the comet-like streak. |
| **The supernova is never a guess** | It appears only when the person marks a life event as hard. New small white stars form in its gas, and the memories after it gather there. |
| **Hiding and bringing back** | Hiding memories forms a black hole right beside them, which absorbs them slowly. Unhiding them forms a white hole. Both are temporary. Hidden memories ride in the black hole's disk and stay tappable. |
| **Every sky as unique as possible** | Nebulas and the other objects come in the full range of colours, not one fixed palette. "Even the patterns should be different." |
| **Dim, soft and real** | Everything fades into the background as you zoom into it, the black hole a bit less. Nebulas were cut by 15% and then 10%, the supernova by 25% overall. No hard edges on any glow. |
| **Motion that looks real** | Twinkle is never all stars at once. Moving objects travel in a believable direction and are tappable. |
| **The opening** | Stars twinkle in slowly, ramp up gently and burst into view, then the view flies to the closest day. If today's star is new and not yet seen, it is shown being born. |
| **Constellations** | The person draws and names their own. When stars drift apart and a constellation breaks, the outline is kept as a snapshot. A clear menu lists them. |
| **Age** | Older stars drift away and are red-shifted; newer ones are blue. Bigger stars for main events. |
| **Never** | No moon, no land. Never frame anything as depression or as memories being eaten. |
| **On a phone** | The sky is a phone screen with a bottom bar: Constellations, Explore, Today, Key. |

**Rejected along the way**, so nobody proposes them again: the construction-sign entrance, three
early concepts including a spiral galaxy arm, the night scene from the September prototype ("blurry
dots"), soft bloomy stars, four-point spiked stars, separate galaxy blobs, and the September
background itself.

---

## 2. Where a star goes: the river

### 2.1 One river, measured in memories

All of a person's memories lie along **one river**, in time order, from their first memory to
today. The river's course is fixed by the sky's seed (§5). **Distance along the river is counted in
memories, not days.** A month with forty memories takes forty memories' worth of river; a month
with two takes two. A quiet stretch therefore takes up almost no room and is never drawn as an empty
reach of river. This is how the new design keeps the rule `docs/SKY.md` §1 was built around, *a hard
stretch is never a void*, now that there is no decorative field to soften it (§9).

### 2.2 How a stretch decides its shape

Each memory's place near the river comes from the rhythm of the days around it. The rhythm is read
from **dates alone**: which days have at least one memory. Never mood, never kind, never content.

| Rhythm | Shape | In the prototype |
|---|---|---|
| Something on most days, for months | **A band** along the river, with knots where days bunch up, and a few strays. **Rare**: most skies have one or two. | `daily` periods, 140 to 420 days, at most two per sky |
| Runs of a few days with breaks between | **Small clusters** set off to one side of the river, one per run: oval, spiral or open | `onoff` periods |
| A long run inside on-and-off weeks | **A stream**: the run stretches into a thin tapering trail, like a comet | a run at least `streamAt` days long (6 to 11, per sky) |
| A single memory between runs | Scattered loosely near the river | |
| Two clusters close together | **A bridge**: the real memories between them drawn as a faint trail joining the two | at most two per sky |

The prototype makes its example person out of these periods directly. A build has to read them from
real dates, so the thresholds are a proposal to tune on real data, not a decision:

- **Band:** a run of at least 8 weeks in which at least 6 of every 7 days have a memory. Bands are
  meant to be rare, something special, never one a year (the maintainer, 2026-10-03); the threshold
  is set high on purpose, and a tuning pass should keep it that way.
- **Run:** two or more consecutive days with a memory, outside a band.
- **Stream:** a run at least as long as the sky's own `streamAt` (6 to 11 days).
- **Bridge:** two clusters whose centres end up within a short distance of each other, and only if
  there are real memories between them. The prototype adds made-up "bridge" memories; a build must
  never draw a star for something that did not happen (`docs/SKY.md` §1.3).

**Shapes settle; they do not keep changing.** The newest stretch is still forming, so its shape can
change as days arrive (a run becomes a stream; a band ends). Once a stretch is over, its shape is
fixed for good.

### 2.3 Stability, restated

`docs/SKY.md` §3.1 rests on *a star never moves*. In the river, a star's place depends on the
memories before it, so the property becomes:

- **Adding today's memory moves nothing already drawn.** The river only grows at its head.
- **A late memory with an old date** (a restore, a sync, a back-dated entry) shifts every later star
  one place along the river. That is rare, and the shift is animated so it reads as the sky settling,
  never as a jump.
- **Deleting a memory** closes its place the same way and leaves no trace (§9).
- **Drift is decoration.** Older stars drift outward slowly and clusters turn slowly. That motion is a
  fixed function of each star's identity and age, stops under reduced motion, and is never a relayout.

`docs/SKY.md` P5 ("position identical after inserting 1,000 unrelated records") is therefore
replaced by: *position identical after appending 1,000 newer records*.

### 2.4 Arriving with a history

Someone who already has entries when this sky ships gets all of them at once, built by the same
rules as everyone else's. It asks them nothing: the river and every habit shape (§3.2) are worked out
from dates alone, so a year of entries simply arrives as a year of sky.

- **Life events they already placed** become giant stars straight away.
- **Everything else that is marked** (a hard event, a favourite, a named star, a place, a pulsar, a
  constellation) is absent until they mark it, because none of those marks existed before. The sky
  never guesses one, and never asks them to go back and mark their history.
- **Their stars move once**, from the old scattered sky into the river. It is the only time a whole
  sky rearranges, and the first opening of the new sky is where it happens.
- **Their seed is drawn then** (§5.4), and **their colour window starts then**: 30 days from the day
  they first open the new sky, not from their first memory, which may be years old.

---

## 3. Everything in the sky

Each object exists **only if the person's own record makes it**. A sky with no weekly habit has no
whirlpool. The showcase puts every object in every sky so they can all be seen; a real sky will
usually have fewer, and a new one has almost none.

Every object is made in one of two ways, and the difference matters:

- **Marked**: the person did something on purpose (marked an event hard, put memories away, drew a
  constellation). The sky follows them.
- **Rule**: a fixed rule over the person's own dates and tags. It reads no mood and no content, and
  its card says plainly what it is, never what it means.

A third kind, **scenery**, is placed by the seed alone and holds no memories: the background's gas
and the dark nebula.

### 3.1 Stars

| Object | Made by | What it is | Card says |
|---|---|---|---|
| **Star** | every memory | One memory. White heart, a tight bright glow, a soft outer glow. Colour is age (§6.2). | Date, kind, and what it is part of |
| **Giant star** | marked: a life event | Bigger and brighter. Zoom in and it burns like a sun. | Date and the person's own words for it |
| **Supernova** | marked: a life event the person marked as hard | A glowing shell with a slowly pulsing star at its heart. A few hundred of the memories after it are drawn in: some are born as new small white stars in knots of its gas, the rest gather in a ring around it. | "A life event you marked as hard. *N* memories since have formed in and around it." |
| **Favourite** | marked: the person starred a memory | A soft ring around the star, nothing more | "A favourite." then the usual card |
| **Binary stars** | rule: two memories on the same day that both name the same person | The two turn slowly around each other | "One of a pair. Both mention *name*." |
| **Pulsar** | marked: a habit the person set a time for | Pulses on that beat | "*habit*, a habit you set a time for. It pulses on that beat." No count of days. |
| **Named star** | marked: the person named a single memory | Looks like any other star | The person's name for it, above the usual card |

### 3.2 Shapes the habits made

| Object | Made by | What it is |
|---|---|---|
| **Long band** | rule: §2.2 | Days in a row of doing your thing. Memories line up along it and gather into knots. |
| **Small clusters** | rule: §2.2 | On-and-off weeks. Each run of days pulls together into its own little cluster. |
| **Stream** | rule: §2.2 | A couple of weeks in a row. The run stretches into a thin trail, like a comet. |
| **Bridge** | rule: §2.2 | Two runs close together, joined by the days that carried one into the next. |
| **Whirlpool** | rule: a habit goal done weekly for a long time | Its memories turn slowly around each other; each turn of the spiral is about a year. Two to four arms. |
| **Planetary nebula** | marked: a habit the person chose to end | It finishes as a glowing ring, not a gap. Its last memory sits in the middle. |

### 3.3 Events and moments

| Object | Made by | What it is |
|---|---|---|
| **Comet** | marked: a date that comes back every year, like a birthday | It travels on its own slow orbit and passes through around that date. The tail always trails behind the direction it moves. Tappable while it moves. |
| **Meteor shower** | marked: a day the person chose (a favourite, or a life event they placed and did not mark hard) | On its anniversary, a few fast thin streaks fly out from that day's star. Never for a day the app picks. Each starts some way from the star, never at it, and is over in under a second; a bright one leaves a glowing train for a couple of seconds. Tappable. |
| **Ringed planet** | marked: a place the person named themselves and keeps picking on entries | The memories logged there ride around it as moons, and the moons pass behind it. Up close it is solid: banded, lit from one side, with rings that have gaps, the rings' shadow across the planet, and the planet's shadow across the rings. Places work the way people do: named by the person, picked on an entry. Never device location. |

### 3.4 Clouds and shadows

| Object | Made by | What it is |
|---|---|---|
| **Nebula** | rule: weeks with a lot of writing | Gas around those weeks' memories, in the sky's own colours. "Weeks with a lot of writing: *N* journal entries, *date* to *date*." |
| **Dark nebula** | the seed: scenery | A cloud of cold dust, part of the sky's own scenery, placed by the seed and blind to data. It holds no memories and stars in front of it stay bright. |
| **Dark matter** | marked: memories tucked away to find again later | They do not shine and cannot be seen; only a faint bend in the light behind them gives them away. §4.2. |
| **Black hole** | marked: memories put away | §4. |
| **White hole** | marked: memories brought back | §4. |

### 3.5 What every card follows

The card is the only text the sky adds, and it is fixed, human-written copy with the person's own
values slotted in, like every other word in the product.

- It **names**: the date, the kind, the object, the person's own words. It never interprets.
- It never characterises the stretch a memory sits in. The prototype used to say "a single memory
  from a quieter stretch" and "between busier weeks"; both are gone (§10).
- It never praises: no "well done", no exclamation marks.
- Counts appear only as plain description of an object the person is looking at ("14 memories, 3 to
  19 May"), never as a headline or a comparison (`docs/SKY.md` §6.3).
- Journal prose never appears (`docs/SKY.md` §4.1).

### 3.6 The Key

The Key stays: the maintainer leaned yes on 2026-10-03, and it is recommended. In a mental-health
app an unexplained dark shape beside someone's memories can read as a judgement about them. The Key
says in fixed words what each object is and what made it, so nobody has to guess.

- It lists **only what is in this person's sky**. The prototype lists every object; the app should
  not, so it never reads as a checklist of things they have not got.
- It never opens by itself and is never shown as a lesson before the sky.

---

## 4. Setting memories aside

The design adds two ways to set memories aside. They do different jobs and look different.

| | Put away (§4.1) | Tuck away for later (§4.2) |
|---|---|---|
| What it is for | Not wanting to see them for now | Leaving something to come across again |
| What you see | A black hole beside them; they ride in its disk | Nothing, only a faint bend in the light |
| Can they be tapped | Yes, in the disk | Once found |
| Coming back | Bring back: a white hole sends them home | Found: they shine again where they were |

### 4.1 Put away: the black hole and the white hole

The maintainer decided this rule.

1. **Put away.** The person chooses memories to hide. A small black hole forms right beside them.
2. **Drawn in, slowly.** Over time it draws those memories in. A memory being drawn in travels in a
   slow spiral and still shines; once it arrives it rides in the black hole's bright disk.
3. **Hidden, not deleted.** Every memory in the disk can still be tapped and still opens its card:
   "Put away. Riding in the black hole's disk, hidden but not deleted." One being drawn in says "Put
   away recently. It's being drawn in slowly. You can bring it back any time."
4. **Brought back.** When the person brings memories back, a white hole forms. It sends each one home
   to where it was, one after another, then fades away.
5. **Both are temporary.** A white hole lasts only while it is sending memories home. A black hole
   lasts while anything is inside it, and fades away once nothing is left.

What it is not: never framed as depression, never as memories being lost or eaten, never a
punishment. The card copy above is the whole of what the sky says about it. Hiding is not deletion,
so it leaves a point in any constellation the memory belongs to (§7); deletion is still complete and
leaves no trace anywhere (`docs/SKY.md` §2.1).

The black hole bends the light behind it like a real one: a dark centre, a thin bright ring, a tilted
glowing disk that passes in front of the centre on the near side and behind it on the far side, and
the stars around it lensed outward. Each sky tilts and flattens its disk differently.

### 4.2 Tuck away for later: dark matter

From the maintainer's note on 2026-10-03: *"a way to hide stuff intentionally for you to come back
later, it's gone, and you can find stuff you may have forgotten."* Proposed shape, not yet seen by
the maintainer:

1. The person tucks memories away. They stop shining and cannot be seen at all.
2. Where they are, the light of the stars behind bends very slightly, the way real dark matter
   gives itself away. Nothing else marks the place and nothing ever points to it.
3. Coming across that bend and tapping it is finding them again: they shine where they were, and
   the card says "Tucked away on *date*. Found again."
4. Nothing ever brings them back on its own and nothing ever reminds anyone about them: no
   notification, no "on this day" (`docs/SKY.md` §6.6).
5. They are never lost. The text list (`docs/SKY.md` §7.5) has them under their own heading, so
   anyone can reach them on purpose, and that is also the accessible way to find them.

---

## 5. No two skies alike

### 5.1 What decides a sky

| Comes from the person's record | Comes from the sky's seed |
|---|---|
| Every star, and where it lies along the river | The river's course |
| Which shapes appear, and how big | How this sky draws them: band width, how knotty, which cluster shapes, how far clusters sit from the river, when a run becomes a stream |
| Which objects exist (§3) | Where each object sits, and its size, tilt and spin |
| | Every colour (§5.3) |

### 5.2 The river's course

One of six, each mirrored either way across both axes:

- **Spiral**: a squared spiral, winding in.
- **Columns**: up and down the screen, three or four times.
- **Rows**: side to side, six to eight rows down the screen.
- **Wander**: a river that wanders and keeps away from where it has been. Twice as likely as the
  others.
- **Loops**: a looping line, like handwriting, four to six loops down the screen.
- **Zigzag**: corner to corner, back and forth, four to six times.

### 5.3 The palette

Every sky gets one palette, and every object takes its colours from it.

- A base hue anywhere on the wheel, and one of four schemes: close hues, opposites, three spread
  apart, or split.
- **Nebulas**: three, each a three-colour gradient with its own scale, warp, filament sharpness and
  angle.
- **Background**: three deep tones of one hue and an accent.
- Its own colours for the supernova (a shell and two warm tones), the planetary nebula, the comet's
  ion and dust tails, the black hole's disk (hot and warm), the white hole, dark matter and the
  planet's bands.
- **No greens, anywhere.** Hues from 45° to 195°, olive and teal included, are skipped, under the
  product-wide rule.
- **The palette is blind to data.** It is a function of the seed alone, the same discipline
  `docs/SKY.md` §1.2 holds the decorative field to. Mood never changes a colour.
- **Star colour is not part of the palette.** Age decides it, the same way in every sky (§6.2), so the
  meaning of a star's colour never changes from one person to another.

### 5.4 The seed

A person has exactly one sky, and nothing changes it on its own, because a place whose layout and
colours change between openings is not a place. "Someone else's sky" and "New colours" in the
prototype are showcase controls; neither exists in the app as it is. What the person can do instead,
from the maintainer's answer on 2026-10-03:

- **Colours, chosen up front.** When the sky is first made, the person sees it in its own colours
  and can try others before keeping one. Trying others changes the colours only; the river's course
  and the shapes are never chosen.
- **Then the colours settle, and the person is told so at the start.** Proposed wording: *"You can
  change these colours until 2 November. After that they stay, as part of your sky."* The date is
  the end of the window.
- **The window: 30 days from the first memory** (or, for someone who already has entries when the
  sky ships, from the day they first open it, §2.4), chosen by the maintainer on 2026-10-03. A month is
  long enough to live with the colours through good and bad light and a few different moods, and
  short enough that the sky has settled before there is much history in it.
- **Reset my sky**, always available, behind a confirmation that says what it does: it grows the sky
  again from a new seed, with a new course, new shapes and new colours, and a new window to choose
  colours in. It never deletes or changes a memory, a name or a constellation. Constellations are
  drawn between the same memories in their new places, and each keeps its replica as drawn (§7.6).

**The seed as built would undercut "no two alike".** `SkySeed.forFirstRecord` in `sky/SkyRandom.kt`
derives the seed from the first record's id, date and kind. Record ids start at 1 on every install,
so two people whose first memory was a check-in on the same day get the **same seed**, and with it
the same river course and the same palette. Recommended: draw the seed from the platform's secure
random source when the first memory is made, persist it as now, and never re-derive it. The
September objection to randomness ("inventing uniqueness for someone with no history") still holds
before the first memory, which is why `SkySeed.EMPTY_SKY` should stay as it is.

---

## 6. How it looks

### 6.1 The space

- No stars in the background. Deep colour in the sky's own tones, with slow faint gas.
- Nebulas are soft clouds with fine filaments and dark lanes, and they drift very slowly.
- **Everything but the stars fades as you zoom into it.** In the prototype: nebulas fade to 10% from
  1.2× to 7×; the supernova fades as it fills the screen; the planetary nebula to 20%; the black hole
  only to 45%.
- No hard edge on any glow, anywhere.

### 6.2 Stars

- A hard white heart, a tight bright glow, and a soft faint outer glow. Close up, a star becomes a
  sun: limb darkening and a granulated surface.
- **Colour is age**, on one continuous ramp, the same in every sky:

  | Age | 0 | 0.4 years | 1.3 years | 2.6 years | 4 years | 5.5 years | 7 years |
  |---|---|---|---|---|---|---|---|
  | Tint | `#9DB8FF` | `#CDDCFF` | `#FFF6EA` | `#FFDEA8` | `#FFB476` | `#FF8060` | `#F06054` |

  Each star also carries a small temperature tint from its own identity, so the sky is varied while
  the age still reads. `sky/SkyAge.kt` carries different stops today (five, ending at 5.5 years); the
  approved values are these.
- **Size and brightness vary by identity only**: most stars small, a few bright. Never by mood,
  never by kind (§10).
- Giants and the supernova's heart are the only stars made bigger on purpose, because the person
  marked them.

### 6.3 Motion

- **Twinkle**: every star on its own rhythm, never in step with another, never all at once.
- Older stars drift outward slowly; clusters, the whirlpool, moons and the black hole's disk turn.
- The comet hangs nearly still on a slow orbit. Meteors are fast, thin and straight.
- **Under reduced motion all of it stops**, and the opening (§8) becomes an instant view of today.
  The prototype also has a pause button on the screen.

### 6.4 Level of detail, and what a phone must hold

What the prototype does, as the starting point for a build:

- Stars are drawn as instanced sprites; a star's surface detail is computed only when it is large on
  screen.
- The glow is drawn at half resolution and added back.
- Gas detail rises with zoom, from 4 to 9 octaves of noise, so far away is cheap.
- Pixel density is capped at 2, and the render scale adapts to the measured frame time.
- Large objects far off screen (the black hole, dark matter, the planet) are skipped.

The prototype's drawing is OpenGL ES 3.0 shading (WebGL2). The app's minimum is Android 8 (API 26),
which rules out Android's own shader language (API 33), so a build would draw the sky with
OpenGL ES 3.0, where these shaders carry over almost unchanged. The budgets in `docs/SKY.md` §8.3
still apply and are still unmeasured.

---

## 7. Constellations

### 7.1 Drawing one

1. Tap **Constellations** on the bottom bar, then **Draw a new constellation**.
2. If the sky is zoomed out, the view flies to today so the stars are far enough apart to tap.
3. Tap stars in order. Each one is joined to the last, with a ring on every chosen star. **Undo**
   takes the last one back; **Cancel** leaves without saving. While drawing, a tap only ever picks a
   star; nothing else opens.
4. After two stars, **Name it** opens a sheet. Type a name (up to 28 characters) and **Save**.
5. The view flies to the new constellation and opens its card, and it is in the list from then on.

### 7.2 The list

"Your constellations" opens from the bottom bar. Each row has a small drawing of the shape, the
name, the date it was drawn and how many stars it has. Tapping a row flies there and opens its card.
The card has **Remove this constellation**.

### 7.3 The snapshot

A constellation keeps its outline **as it was when it was drawn**. Over the years its stars drift
apart, which is what makes an old one memorable, and the outline stays.

- **A star put away or tucked away** (§4) keeps its point, drawn as a dashed ring, with the card
  line "One star has since been put away. The outline is kept as it was when you drew it."
- **A memory deleted** takes its point out of the outline, because deletion leaves no trace
  (`docs/SKY.md` §2.1). Proposed: a constellation left with fewer than two stars is removed, without
  comment.

### 7.4 What the software never does

The app never makes a constellation, suggests one, or groups stars by meaning. The prototype comes
with up to four made-up example constellations so the list is not empty; a real sky starts with
none. The September rejection of software clustering (`docs/SKY.md` §3.1) stands. What changed is
only that the person can now draw their own.

### 7.5 Where they are kept

In the app's own database, in the person's own backup, and nowhere else: never synced, shared,
reported or sent (`docs/SKY.md` §6.4). The prototype keeps them in the browser, per sky.

### 7.6 Going back to one as you drew it

From the maintainer's answers on 2026-10-03: there is no way to see the whole sky as it was on an
earlier date, *"only constellations allowed that"*, and a constellation should be *"a way you can go
back and see a replica of the constellation and to a lesser and shadowed portion the stars around
it."*

- A constellation's card has **See it as you drew it**.
- That opens a replica: the constellation's stars exactly where they were on the day it was drawn,
  with the lines joining them, and around it, fainter and in shadow, the stars that were near it
  then, in the colours they had then.
- It is a view, not a place to work: nothing can be tapped open or changed in it, and one tap takes
  the person back to the sky as it is now.
- A memory put away or tucked away since shows in the replica as the dashed ring. A memory deleted
  since is not in the replica at all (`docs/SKY.md` §2.1).
- This is the only way back in time anywhere in the sky.

To build it, the app keeps, for each constellation, its stars' positions on the day it was drawn and
the date itself; the shadowed stars around it are worked out again from the river as it stood on that
date, so nothing else needs storing.

---

## 8. The opening

1. Stars twinkle in slowly, for about three seconds, barely there.
2. They ramp up gradually into a burst that builds over about three and a half seconds, then settle
   over about two more as the last ones arrive. The pace is one continuous curve, never a switch
   from slow to fast; at its busiest it is about half the pace of the first version, which the
   maintainer found too fast at the end.
3. The view flies to today's star.
4. **If today's star is new and has not been seen yet**, it is shown being born, over about six
   seconds: a cloud collapses, the core heats, and the star switches on. Its card then opens. Each
   star is born on screen once, ever.

Under reduced motion none of this plays: the sky opens on today. It never notifies anyone that a star
was born (`docs/SKY.md` §6.6).

---

## 9. What this keeps from the earlier sky documents, and what it reverses

### 9.1 Kept, unchanged

From `docs/SKY.md`:

- **§1's rule**: a hard stretch is never a void. It is now kept by the river's memory-count distance
  (§2.1) rather than by a decorative field.
- **M2**: absence has no glyph. **M4**: mood never changes a star's size or brightness.
- **§2.1**: every star is an act the person performed; deletion is complete and leaves no shape.
- **§2.3**: the date a star sits on is the date it was recorded.
- **§4.1**: journal prose never appears.
- **§6.1 to §6.7**: never infer a life event, never rank days, never show a completeness metric,
  never leave the device, treated as the most identifying artefact the product has, never notify,
  never model.
- **§7**: the text list ships with the sky or the sky does not ship, and every object in §3 needs a
  row in it. Reduced motion stops everything.
- **§8**: the performance budgets.

From the September plan: colour is age, every star has a white heart, a life event is the bright
star, twinkle ships behind the motion switch.

### 9.2 Reversed, by the maintainer's decision

| Was | Now | Source |
|---|---|---|
| `docs/SKY.md` M1: a uniform decorative field of faint non-data specks | No background stars; every star is a memory. The field's job, keeping a sparse stretch from reading as a void, moves to §2.1. | 2026-10-03 |
| September plan §1.0: placement is random; time is not the sky's geography | The river: time runs along it, measured in memories. | 2026-10-03 |
| `docs/SKY.md` §3.1: constellations rejected | The person draws their own. The software still never groups stars. | 2026-10-03 |
| `docs/SKY.md` §2.2: a life event has no valence | The person may mark one as hard. Never asked, never suggested, never inferred. | 2026-10-03 |
| `docs/SKY.md` §2.1: no ghost of any kind | A memory put away keeps a dashed point in a constellation. A deleted one still leaves nothing. | 2026-10-03 |
| `docs/SKY.md` P5: a star never moves | Appending never moves a star; a back-dated memory shifts later ones one place (§2.3). | follows from the river |

### 9.3 What in the code this replaces

`sky/` is import-free and tested on a plain JVM (`tools/jvm-tests.sh sky`). The redesign touches it
as follows. This is a map, not a plan of work:

- **`sky/SkyField.kt`**: retired. There is no decorative field.
- **`sky/SkyWarp.kt`** and the placement in **`sky/Sky.kt`**: replaced by the river (§2), which would
  live in new import-free files in the same package so it can be tested the same way.
- **`sky/SkyAge.kt`**: stays; its stops change to §6.2's.
- **`sky/SkyTwinkle.kt`**: stays.
- **`sky/SkyRandom.kt`**: `SkySeed.forFirstRecord` changes (§5.4).
- **`ui/sky/SkySurface.kt`**: replaced by an OpenGL ES 3.0 surface (§6.4).
- **New records** the build needs: a "marked hard" flag on a life event; put-away, tucked-away and
  favourite flags and an optional name on a memory; constellations, with their stars' positions on
  the day they were drawn; a yearly date for comets; places, named by the person and picked on an
  entry; and the sky's chosen colours with the date the colour window closes. The pulsar reads a time
  the person set on a habit, and does not exist for a habit without one.

---

## 10. Settled after approval, and what is still open

The approved prototype broke a few standing rules and needed some data the app does not have. The
maintainer answered each question on 2026-10-03, and the prototype now follows every answer that
changes what it shows.

| # | Question | The maintainer's answer | Where it lands |
|---|---|---|---|
| 1 | Which days get a meteor shower | Only days the person chose | §3.3. The prototype's shower now falls from a favourite. |
| 2 | The total count in the header | Dropped; the header says "Your sky" | Done in the prototype. |
| 3 | The pulsar reads time of day | Only for a habit the person set a time for | §3.1, with no count of days on its card. |
| 4 | Where places come from | Places the person names themselves | §3.3. Never device location. |
| 5 | Dark matter | Keep it | §4.2, as memories tucked away to find again later. |
| 6 | Dark nebula ("dim a stretch") | Needs changes: "maybe this or dark matter is a way to hide stuff intentionally for you to come back later" | That job went to dark matter (§4.2). The dark nebula is now scenery (§3.4). |
| 7 | May a person change their colours | Needs changes: colours up front, a warning that the sky becomes permanent over time, and a way to reset the sky. Then: 30 days | §5.4. |
| 8 | A slider to see the sky on an earlier date | No: "only constellations allowed that" | §7.6. |
| 9 | Name a single star | Yes | §3.1. |
| 10 | Review a year as a flight along its river | No: bands should be rare, never one a year | §2.2: bands are rare by rule, at most two in the prototype. |
| 11 | A constellation's then and now | No, but see it again as a replica with its surroundings in shadow | §7.6. |

Two more follow from standing rules rather than a new answer, and the prototype now follows both:
a card never describes the stretch a memory sits in (it used to say "from a quieter stretch"), and
brightness never follows kind (it used to make a journal entry slightly brighter).

**Still open**, each with the default the build should take unless the maintainer says otherwise:

1. **The proposed shape of "tuck away for later"** (§4.2) has not been seen by the maintainer.
   Default: as written.
2. **Giants and age colour.** The September plan says a life event never red-shifts; the approved
   prototype red-shifts giants like every other star. Default: follow the prototype; the giant still
   stands out by size.
3. **The rhythm thresholds in §2.2** are proposals and need tuning on real data, keeping bands rare.

---

## 11. Ideas, and what became of them

| Idea | Outcome |
|---|---|
| See the whole sky as it was on an earlier date | Rejected; only constellations go back in time (§7.6) |
| Name a single star | Approved (§3.1) |
| "Review my year" as a flight along that year's river | Rejected; bands are rare and never per year (§2.2) |
| A constellation's then and now on its card | Replaced by the replica (§7.6) |
