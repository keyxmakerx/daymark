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
> and what it reverses. §10 lists the places where the approved prototype breaks a standing rule and
> someone has to choose, each with a recommendation. Nothing in §10 is decided by this document.

**In one paragraph.** Every star is one memory, and nothing else in the sky is a star. The
background is deep space: colour and gas, no stars of its own. The shapes come from how the person
kept their record. Days in a row of doing their thing draw a long band, on-and-off weeks gather into
small clusters and streams, and a weekly habit kept for years turns into a whirlpool. Life events are
giant stars. A life event the person marked as hard becomes a supernova, and what came after gathers
in its cloud. Memories the person puts away are drawn slowly into a black hole that forms beside
them; bringing them back opens a white hole that sends each one home. The person can join stars into
constellations and name them. No two skies are alike: the course of the river, the shapes, where
everything sits and every colour grow from the person's own record and a seed of their own.

---

## 1. What the maintainer decided

In the order it was decided, across the design rounds of 2026-10-02 and 2026-10-03.

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
| Something on most days, for weeks | **A band** along the river, with knots where days bunch up, and a few strays | `daily` periods, 140 to 420 days |
| Runs of a few days with breaks between | **Small clusters** set off to one side of the river, one per run: oval, spiral or open | `onoff` periods |
| A long run inside on-and-off weeks | **A stream**: the run stretches into a thin tapering trail, like a comet | a run at least `streamAt` days long (6 to 11, per sky) |
| A single memory between runs | Scattered loosely near the river | |
| Two clusters close together | **A bridge**: the real memories between them drawn as a faint trail joining the two | at most two per sky |

The prototype makes its example person out of these periods directly. A build has to read them from
real dates, so the thresholds are a proposal to tune on real data, not a decision:

- **Band:** a run of at least 21 days in which at least 6 of every 7 days have a memory.
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

### 3.1 Stars

| Object | Made by | What it is | Card says |
|---|---|---|---|
| **Star** | every memory | One memory. White heart, a tight bright glow, a soft outer glow. Colour is age (§6.2). | Date, kind, and what it is part of |
| **Giant star** | marked: a life event | Bigger and brighter. Zoom in and it burns like a sun. | Date and the person's own words for it |
| **Supernova** | marked: a life event the person marked as hard | A glowing shell with a slowly pulsing star at its heart. A few hundred of the memories after it are drawn in: some are born as new small white stars in knots of its gas, the rest gather in a ring around it. | "A life event you marked as hard. *N* memories since have formed in and around it." |
| **Favourite** | marked: the person starred a memory | A soft ring around the star, nothing more | "A favourite." then the usual card |
| **Binary stars** | rule: two memories on the same day that both name the same person | The two turn slowly around each other | "One of a pair. Both mention *name*." |
| **Pulsar** | rule: a habit kept on a steady rhythm | Pulses on that beat | See §10, item 4 |

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
| **Meteor shower** | rule, see §10 item 1 | On the anniversary of a day, a few fast thin streaks fly out from that day's star. Each starts some way from the star, never at it, and is over in under a second; a bright one leaves a glowing train for a couple of seconds. Tappable. |
| **Ringed planet** | rule: a place the person keeps going back to | The memories logged there ride around it as moons, and the moons pass behind it. Up close it is solid: banded, lit from one side, with rings that have gaps, the rings' shadow across the planet, and the planet's shadow across the rings. See §10 item 6 on where places come from. |

### 3.4 Clouds and shadows

| Object | Made by | What it is |
|---|---|---|
| **Nebula** | rule: weeks with a lot of writing | Gas around those weeks' memories, in the sky's own colours. "Weeks with a lot of writing: *N* journal entries, *date* to *date*." |
| **Dark nebula** | marked: a stretch the person chose to dim | A dark cloud over it. Nothing is hidden or lost; tap any star in it. |
| **Dark matter** | marked: private memories | They do not shine, but the light behind them bends. See §10 item 7. |
| **Black hole** | marked: memories put away | §4. |
| **White hole** | marked: memories brought back | §4. |

### 3.5 What every card follows

The card is the only text the sky adds, and it is fixed, human-written copy with the person's own
values slotted in, like every other word in the product.

- It **names**: the date, the kind, the object, the person's own words. It never interprets.
- It never characterises the stretch a memory sits in. The prototype's "a single memory from a
  quieter stretch" and "between busier weeks" are out (§10 item 3).
- It never praises: no "well done", no exclamation marks.
- Counts appear only as plain description of an object the person is looking at ("14 memories, 3 to
  19 May"), never as a headline or a comparison (`docs/SKY.md` §6.3).
- Journal prose never appears (`docs/SKY.md` §4.1).

---

## 4. Putting memories away: the black hole and the white hole

This is the one new action the design adds to the product, and the maintainer decided its rule.

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

A person has exactly one sky, and its seed never changes once set, because a place whose layout and
colours change between openings is not a place. "Someone else's sky" and "New colours" in the
prototype are showcase controls; neither exists in the app (see §10 item 10 for whether a person may
change their own colours).

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
  never by kind (§10 item 5).
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

- **A star put away** (§4) keeps its point, drawn as a dashed ring, with the card line "One star has
  since been put away. The outline is kept as it was when you drew it."
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

---

## 8. The opening

1. Stars twinkle in slowly, for about three seconds, barely there.
2. They ramp up gently and burst into view over about two and a half seconds.
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
- **New records** the build needs: a "marked hard" flag on a life event; hidden and favourite flags
  on a memory; constellations; a yearly date for comets; and the sources §10 asks about.

---

## 10. To settle before building

Each of these is a place where the approved prototype breaks a standing rule or needs data the app
does not have. The prototype shows the object; the build needs a decision.

1. **The meteor shower picks a day for the person.** It falls on the anniversary of a memory the
   prototype chose because it was about a year old. `docs/SKY.md` §6.6 bans anniversary effects, and
   a day the software picks could be the worst day of someone's year.
   *Recommended:* only on the anniversary of a memory the person starred or a life event they placed
   and did not mark hard. Never on one the app chose.
2. **The header shows a total count** ("14,742 memories, one star each"). `docs/SKY.md` §6.3 forbids
   a count as a headline. *Recommended:* drop it; the header says "Your sky".
3. **Two card lines describe the stretch** ("from a quieter stretch", "between busier weeks"). That
   comments on a gap. *Recommended:* the card says the date and the kind and nothing about the
   stretch.
4. **The pulsar reads time of day** ("done at the same time for 214 days"). `docs/SKY.md` §2.3 keeps
   time of day out of the sky's shapes, because it draws a person's sleep across the surface.
   *Recommended:* a pulsar is a habit with a reminder time the person set themselves, which is their
   choice rather than something read from timestamps. Otherwise drop it.
5. **The prototype makes some kinds brighter** (a journal entry slightly brighter than a check-in).
   The September plan says a journal page stays the size of everything else. *Recommended:*
   brightness from identity only.
6. **The ringed planet needs places.** The app has no places, and must never use device location.
   *Recommended:* places the person names themselves, the same way people work, picked on an entry.
   Otherwise drop it.
7. **Dark matter needs a meaning for "private".** The app has no private flag separate from hiding.
   *Recommended:* drop it, or define it as journal entries kept behind the journal's own lock.
8. **"Dim a stretch" (the dark nebula) is a second hiding action** next to putting memories away.
   *Recommended:* keep only putting away (§4), the action the maintainer designed; drop dimming.
9. **Giants and age colour.** The September plan says a life event never red-shifts; the approved
   prototype red-shifts giants like every other star. *Recommended:* follow the prototype, since it
   was approved; the giant still stands out by size.
10. **May a person change their own colours?** The showcase has "New colours". *Recommended:* yes,
    as a setting the person chooses, kept until they choose again, and never changed by anything
    else.
11. **The rhythm thresholds in §2.2** are proposals; they need tuning on real data before they are
    called right.

---

## 11. Ideas not yet shown to the maintainer

None of these is approved. Each fits the rules above.

- **Sky as it was.** A slider that shows the sky on an earlier date: the river shorter, every star
  bluer, because age is measured from that date. It ranks nothing and shows nothing the person has
  not already seen.
- **Name a single star**, the way a constellation is named, shown on its card.
- **The year review as a flight.** "Review my year" flies slowly along that year's stretch of the
  river and stops only at things the person marked: life events, constellations, favourites. Never
  at anything the app picked.
- **Constellation then and now.** On a constellation's card, switch between the outline as drawn and
  where its stars are today.
