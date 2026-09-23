# The Sky

"Your sky" draws the person's own acts, of six kinds (§2), as stars in one night sky. It is reached
from the More hub and sits behind the app lock like every other screen. It is a *place* rather than
a chart: stable (a star never moves), inhabited (there is always more sky than data), and navigable
at more than one scale.

How to read it: where a star sits says nothing. Its colour and brightness say how long ago it was;
the spread of its glow is the mood recorded with it, if any; up close, its form says what kind of
act it was; a bigger white star is a life event the person marked. The signed-off look is
`docs/prototypes/your-sky.html`, which opens in any browser.

This is the single reference for the Sky. Code cites its section numbers.

---

## 0. What is built, and where

### 0.1 The code

Everything the Sky *decides* is in `app/src/main/java/com/daymark/app/sky/`, which imports no
Android, Room, `java.time` or clock, so every rule is a plain-JVM test. `ui/sky/` is a renderer and
decides nothing.

| File | Owns |
|---|---|
| `sky/Sky.kt` | the six kinds, the layout, the text list |
| `sky/SkyAge.kt` | colour and brightness from age |
| `sky/SkyGlyph.kt` | core, halo, temperature, the landmark, kind forms, zoom levels (`SkyDetail`), the Sky's switches (`SkyOptions`) |
| `sky/SkyPalette.kt` | the night colours, contrast, equalising mood colours |
| `sky/SkyTwinkle.kt` | each star's rhythm |
| `sky/SkyWarp.kt`, `sky/SkyField.kt` | the clumping; the decorative field |
| `sky/SkyRandom.kt`, `sky/SkyCalendar.kt` | the hash and the sky's seed; date arithmetic |
| `ui/sky/` | `SkyScreen` (controls, detail strip, list), `SkySurface` (drawing, gestures), `SkySprite`, `SkyPresentation` (zoom, hit-testing, every word a screen reader hears), `SkyViewModel` |
| `data/SkyRepository.kt` | the six projections merged, dates converted, the seed stored |

`tools/jvm-tests.sh sky` and `tools/jvm-tests.sh ui/sky sky` run `sky/` and `SkyPresentation` on a
plain JVM in seconds; the other Sky tests run in CI.

### 0.2 What has been measured

Only the layout pass, on a plain JVM: 5,393 records became 5,393 stars in 15–29 ms, measured on an
earlier version of the layout and not repeated since. `SkyTest` holds the same ten-year history under
two seconds. Every other number in §8 is a budget; measuring them on a real phone is #147.

### 0.3 Four findings that are now rules

1. **The raw mood ramp ranks moods by visibility.** On the ground `#07070A` the shipped colours
   measure awful `#AE5747` 4.08:1 (under the 4.5 floor), bad 6.00, meh 8.32, good 7.22, rad 5.08,
   and a darker ground lifts every ratio by the same factor. So the raw ramp ranks the moods by
   visibility, with the hardest one faintest, which is precisely the cruelty §1 exists to prevent.
   Stars no longer carry mood colour (§3.2). The one mood mark left, the dot in a star's detail, goes
   through `SkyPalette.equalised`, which moves every colour to *exactly* 6.5:1 (10.0:1 in the quiet
   sky), scaling in linear light so the hue survives.
2. **`NIGHT_FAINT` is not faint.** `#8E887A` measures 5.70:1: desaturated, not low-contrast. Chrome
   recedes by stroke weight, size and form, never by being fainter.
3. **A project step carries its link in its own glyph.** A thread belongs to a *pair* of stars, so
   every project's first step had nothing to draw and looked exactly like a check-in.
   `SkyGlyph.threadStubDp` draws the link as part of the step.
4. **"Quieter" is arithmetic, not intent.** §3.4 says a hard day is softer and wider, and that is
   one step from "dimmer".
   What is built holds **total light constant** — `haloPeakAlpha` is defined as `HALO_LIGHT / radius²`,
   so the product is fixed by construction and not by a table someone has to keep balanced. Mood
   redistributes light; it never changes the amount. Holding the peak and widening the halo was
   tried and rejected: hard days then emit *more* light, an inverted ranking rather than none. With
   the halo now a radial fade the check is an integral, 1.161 at every mood.

### 0.4 What the Sky reads

One narrow query per kind, returning an id, a time and at most a mood, and **no text column**
(§8.2 rule 7, §4.1). The rule lives in each DAO signature, not in care at the call site, and
`SkyProjectionSourceTest` checks every query and counts them against the kinds.

| Kind | Reads | Never reads |
|---|---|---|
| Check-in | `mood_entries`: id, time, mood | the note |
| Practice | `thought_records`: id, time | anything the person wrote |
| Journal | `journal_entries`: id, time | `title`, `body` |
| Goal reached | `goals`: id, `reachedAt`, where set | the title, `archived` |
| Project step | `goal_steps`: id, `completedAt`, done steps only | the title |
| Life event | `life_events`: id, `epochDay` | `label` |

- **Goal reached** is `reachedAt`, which the person sets with a reversible switch in the goal editor,
  never derived from progress (`GoalReachedSchemaTest`). `archived` is read neither as reached (a
  star for giving up) nor as not reached (tidying a reached goal away must not delete its star).
- **Practice** is `thought_records`. Questionnaire runs (`assessment_results`) are left off: a run
  carries a score and a band, and a star for it sits close to the scoring the Sky refuses to do. The
  other practices keep no dated record, so they draw no star.

`SkyRepository` turns each timestamp into a local date **at that boundary**, in the device's zone. A
life event already stores the day the person chose.

---

## 1. The rule that decides whether this is beautiful or cruel

> **Every period has stars. Nothing is empty, and a hard stretch is never a void.**

Everything in §2–§4 serves this rule. The failure it prevents is not obvious and is easy to
reintroduce by accident; §9 names two components that still do.

### 1.1 Why a sparse sky is a cruel object

1. **A life rendered as light will be read as a judgement of that life.** That is not a risk of the
   metaphor; it is the metaphor.
2. **Depression reduces logging.** Energy, self-monitoring and shame about what the record would say
   all push the same way. It is why the product bans notifications that mention a lapse
   ([§D6](DECISIONS.md)) and why coverage reaches clinicians uninterpolated ([§D8](DECISIONS.md)).
3. **So the amount of data is inversely correlated with how hard a period was.**
4. **A sky that draws the amount of data draws an inverted map of suffering** and hands it to the
   person as a picture of themselves. The months they survived render as months that did not happen.

Two harms compound it. It is **permanent**, revisited most by someone looking for evidence about
their own worth, which is when a void is worst. And it is **retroactive punishment for not using the
app**, the streak failure mode with a longer memory: a streak forgets; a sky does not.

> The person is not the only reader. Someone shown their own sky at a bad moment is being shown an
> argument. It must not be an argument that their hardest months were empty.

### 1.2 The four mechanisms that make it structurally true

| # | Mechanism | Rule | Checked by |
|---|---|---|---|
| **M1** | **A uniform decorative field** | The sky is dense with faint specks that are not data, at identical density everywhere, declared decorative and left out of every text equivalent (§3.7). | P3, P4 |
| **M2** | **Absence has no glyph** | A day with nothing logged draws nothing: no speck, no dimmed cell, no placeholder. The layout iterates records, never dates. | P2 |
| **M3** | **No ruler, no axis** | No gridlines, rows, ticks, axis, or position that means a date (§3.1). | P5 |
| **M4** | **Equal presence across moods** | At any age, a star's core, colour, brightness and total light are identical at every mood level. | P1, P6 |

Take any one away and the void returns in a weaker form. Without M1, three stars in a month look like
a broken render. Without M2, a stretch of grey specks *is* the void, drawn politely. Without M3, the
empty slots between stars are countable, and someone will count them. Without M4, a month of hard
days is a month of dim stars.

**M1's own trap:** the field must never be denser where data is sparse. A field that fills in the
gaps encodes the gaps. Compensation is a form of measurement.

### 1.3 What this rule does *not* license

Inventing data. The Sky never draws a star for something that did not happen. The field is not
stars; it is sky, and it says so. *The sky is always full; your stars are the ones you made.*

---

## 2. What becomes a star

Six kinds, exactly: **check-in**, **practice** (a therapeutic practice, never physical exercise,
which is a goal), **journal entry**, **goal reached**, **project step**, and **life event** (§2.2).
Sources are in §0.4. The list is closed; a seventh kind is a design decision, not a feature detail.

**Every kind is an act the person performed.** Nothing on the Sky is derived, detected, scored,
inferred or synthesised. If a star is there, the person did the thing. That sentence is what makes
the surface defensible.

### 2.1 Rules that fall out of "every star is an act"

- **A star is created with its record and destroyed with it.** No tombstone, no ghost. Deletion
  leaves no shape, not even in the background, which is why the sky's seed is never re-derived
  (§3.7).
- **No star for anything the software did**: a notification, an offer, a decision, an app open. The
  reception ledger ([§D1a](DECISIONS.md)) never surfaces here.
- **No star for a thing declined** ([§D5](DECISIONS.md)). An archived project's completed steps keep
  their stars: they happened, and abandoning does not retract them.
- **No negative star**: nothing for a missed reminder, a skipped practice, or a goal not yet reached.
- **A goal reached is one star, not a bigger one.** Reaching a goal is a kind, not a rank (§3.4).

### 2.2 Life events — the only authored kind, and the only new record

A life event is a few words and a date, written by the person, and nothing else.

- **Never inferred, never suggested, never prompted for** (§6.1).
- **Two doors, one act:** the Sky's own **Life events** control, which opens a list with an add
  button, and **Mark this day** on an entry's page, which uses that entry's date. Both ask for a few
  words; the app adds nothing.
- **No categories, no mood, no valence.** A taxonomy is the software deciding what counts as a life,
  and a life event is not good or bad.
- **Deleted at any time, leaving nothing behind.** There is no edit in place: the person deletes and
  adds again.
- **One day, not a range.** Whether ranges should exist is #155.
- **The one star allowed to be louder** (§3.4): prominence follows *authorship*, not scoring.

### 2.3 The date a star sits on

The **local date the act was recorded**, from the record's own timestamp. Never back-filled, never
"the day it was about": an entry written at 03:00 on the 4th about the 3rd is a star on the 4th. A
life event's date is the day the person chose. Time of day is nowhere on the Sky (not in position,
detail or list); drawn, it would map the person's sleep across their whole history.

---

## 3. How a star is placed and drawn

### 3.1 Placement — the sky is scattered, and time is not its geography

**A star is scattered across one open field; when it is from is carried entirely by its colour and
brightness** (§3.2, §3.5). There are no rows, no months on the surface, and no position that means a
date.

```
hx, hy = two independent hashes of (kind, anchor record id)    in [0, 1)
x, y   = SkyWarp(hx, hy, sky seed)                             clumped, still in [0, 1)
```

**Why:** a row per month draws a hard month as a visibly empty band, the exact reading §1 exists to
prevent. When position encodes nothing, no region can be empty. **The cost, accepted knowingly:** a
date cannot be found by looking; the list (§7.5) keeps month headings for that.

1. **A star never moves.** Its position is a hash of its own kind and anchor record id and nothing
   else: never its index, the count, the date or the mood. Adding today's check-in or restoring a
   thousand old records moves nothing (P5).
2. **Coordinates are normalised to `[0, 1)`**, so a five-star sky spreads across the screen and a
   ten-year sky is dense without any position changing. Zoom is a transform, never a relayout.
3. **The clumps carry nothing.** Uniform scatter reads as machine-made, so positions pass through a
   smooth value-noise field seeded by the sky's seed. `SkyWarp` takes a position and a seed and
   cannot see a record.
4. **Overlap is accepted, never resolved.** Nudging a star away from a neighbour would make its
   position depend on other records. Zoom separates them; a tap takes the nearest.
5. **A crowded day folds.** Past 16 stars in a day (`Sky.MAX_STARS_PER_DAY`), records of one kind
   share a star that keeps every id and is drawn like any other. Nothing is dropped, and rule 1 holds
   for every day under the cap.
6. **Draw order is time order**, so newer stars land on top.

**Rejected: constellations.** A grouping asserts a relationship, picked either by the software
(inference) or by the person (a feature nobody asked for). The project thread (§3.3) is the one
declared link; the warp's clumps know nothing about the stars they group.

### 3.2 Colour is age, not mood

> **A star's colour is how long ago it was: blue-white when new, through white, gold and amber, to a
> deep red after five and a half years. Red means old, never bad.**

The ramp (`SkyAge`, from the prototype) runs from `#C4DAFF` through `#FFFAEC`, `#FFE296` and
`#FFAC64` to `#FF6E58`, where it stops. Every star reddens at the same rate, so nobody's worst week is
their reddest. It is continuous and only ever reddens (`SkyAgeTest`): a step would draw a band, and a
band invites someone to read meaning into which side of it their month fell on. A record dated after
today is drawn as new.

- **Each star has its own temperature**, from its identity and fixed forever (icy, white, pale gold
  or peach), mixed about a third into its age tint so the sky is varied. It means nothing.
- **The core is the same near-white for every star.** Age tints the glow around it.
- **A life event is white at every age**, with no temperature (§3.4).
- **Mood is not a colour on the sky.** It appears only as a dot beside the mood word in a star's
  detail, from the person's own palette (theme colours and their overrides; the Sky hardcodes no mood
  hue), equalised so no mood's dot is fainter than another's (§0.3). The word carries the mood.
- **No other colour**: no accent, selection tint, warning amber, success green or "all clear". Past
  the ground `#07070A`, the ink `#EBE5D8` (field, selection ring) and the faint `#8E887A` (project
  stub), the only hues are a glint's passing fringes (§3.6). Selection is a ring, never a colour or
  growth.

### 3.3 Kind — carried by form, and only up close

Kind marks appear only at **CLOSE** (§4). Further out, every kind is just a star, and the list always
names it. Marks scattered across a whole life would turn the sky into a legend and sort a person's
days into kinds of act at exactly the zoom where they see a stretch of their life at once.

| Kind | Form at CLOSE, around the shared core | In a word |
|---|---|---|
| Check-in | nothing added | a breath |
| Practice | an open ring | a circuit |
| Journal entry | a short underline | a written line |
| Goal reached | four short cross-rays | a glint |
| Project step | a hairline stub, the start of a thread | a link |
| Life event | long cross-rays and an outer ring, on its larger core | a marker |

- **Silhouette first:** every form reads in monochrome (P7). Neither colour nor motion ever tells
  kinds apart. A life event looks different at every zoom, but that is its light (§3.4), not a mark.
- **The project thread is the only line on the Sky.** Each step carries its stub (§0.3 finding 3);
  the hairline joining steps of one project is not built, as it needs a project identity the layout
  deliberately does not carry: #152.
- **A key** naming the forms is not built; whether it should exist now that colour means age is
  #154.

### 3.4 Mood is the character of the light, never its amount

"Quieter" slides into "dimmer", which slides into "worth less", without anyone deciding either. So:

> **Mood changes a star's *character*, never its *presence*.** A hard day's star is not smaller,
> fainter or a different colour. Its light spreads wider and softer; a good day's gathers tighter.
> The total is the same.

| Varies with mood | Fixed at every mood |
|---|---|
| Halo radius: 4.8 dp at level 1 to 3.6 dp at level 5; 4.2 dp with no mood | Core radius (1.9 dp), core alpha, core colour |
| Halo peak alpha, `HALO_LIGHT / radius²`, so the product cannot move | Tint and brightness (age), position, twinkle, total light (§0.3 finding 4) |

The spread is small, about 14% either way, so it reads as texture at arm's length. A star with no
mood sits in the middle, not at the bad end. Mood is drawn at all because handing a person their own
answer back is allowed where labelling them is not ([§D1b](DECISIONS.md)). Dimming by mood, in any
form, is refused.

**No ranking of days:** nothing that makes a star more present (size, core alpha, colour, brightness,
total light, rays, twinkle) is a function of mood (P1, P6). A folded star is drawn like a single one:
a bigger star for a busier day ranks days by output (§6.2).

**Prominence follows authorship, not value. A landmark is the one bright star.** A life event is
placed to be found again, so it alone is bigger (core × 1.9), brighter (the one halo that emits more
light), spiked (four soft white spikes at every zoom), never redshifted, never faded, and always
glints. Brightness may follow a mark the person placed, never anything the app measured.

### 3.5 How a star is drawn — a point, then a glow

Each distinct star is rasterised once at the device's pixel density and stamped additively, so glows
brighten where they overlap, as light does. Three layers: a **hard-edged near-white core**; a **tight
inner glow** in the tint; and a **soft outer glow** in the tint, fading to exactly nothing. The
prototype's extra floor under every halo was dropped: multiplied by a wider halo's area, it would
make hard days emit more light.

**Brightness is age.** A new star is at full brightness and fades toward a floor of 0.22 with a
2.1-year time constant: half the fall by about a year and a half, never zero. **Old stars recede but
never vanish**, so someone who comes back after five years finds everything they left. A life event
never fades.

**The ground is near-black `#07070A`, not pure black**, so a glow has something to fade into and an
OLED panel does not switch pixels off beneath it. The quiet sky (§7.1) draws the core alone.

### 3.6 Twinkle

Decoration only. Every value comes from the star's identity and the time, never from its mood or any
count, so each star has its own beat, forever. Kind matters only in that every life event glints.

- **A breathe**, every star: brightness dips by up to 22% over 3.5 to 8 seconds.
- **A shimmer**, a third of stars: a faint 5% flicker about every 1.6 seconds.
- **A glint**, about a fifth of stars and every life event: once every 7 to 22 seconds, for 280 ms, a
  red and a blue fringe are added either side of the star, like a prism. The star's own tint never
  changes.

The twinkle multiplies age brightness, so no star is twinkled past a younger one. There is no dial,
only the Motion switch (§7.4). Two `SkyTwinkle` functions have no caller, `scaleAt` (a 2% size
breathe, which a sub-pixel sprite would only blur) and `glintFringeScale`: #153.

### 3.7 The decorative field

`SkyField.tile(seed, tileX, tileY)` takes a seed and a tile address and nothing else (P3). Each tile
is a 12 × 12 grid with one speck jittered in each cell, so density is exact (P4). Tiles are 96 dp of
*screen*, so the field keeps one density at every zoom instead of thinning as someone leans in, which
would read as absence. Specks are 0.9 dp, smaller than any core, at 5–16% of the ink. The field
drifts at 85% of the pan while motion is on, can be switched off (Field chip, or the quiet sky), and
is invisible to assistive technology and absent from the list.

**Its seed is derived once, from the person's first record**, stored under `sky_field_seed`, and
never re-derived: a background that changed when that record was deleted would be a shape the
deletion left (§2.1). The same seed shapes the clumps (§3.1).

Not built: a sparser, smaller, fainter field when zoomed out, #150.

---

## 4. Zoom and focus

Zoom runs continuously from 1× (the whole field exactly fills the screen, never less) to 32×.
Nothing reflows, so a star can be followed from FAR to CLOSE by eye.

| Level | Zoom | Drawn |
|---|---|---|
| **FAR** | below 2.5× | Every star as point and glow. The view you leave open, and where the Sky opens. |
| **NEAR** | 2.5× to 7× | The same, closer. |
| **CLOSE** | 7× and in | Kind marks (§3.3) and project stubs as well. |

A level depends on zoom alone, never on how many stars are on screen, which would make what is drawn
depend on how much somebody logged.

- **A pinch magnifies the point under the fingers**, since with no labels to navigate by, a star that
  slid away would be lost. Zoom follows the fingers directly, with no inertia or animation.
- **"Fit the whole sky"** appears in the corner only once zoomed. It is not a double-tap, which would
  make every tap on a star wait to see whether a second was coming. There is no "Today": no part of
  the field is a date.
- **No level for one star.** A tap takes the nearest star within its 48 dp target and opens its
  detail as a strip over the bottom of the sky, which stays visible: coming close is not leaving.

### 4.1 What a star's detail shows — and what it never shows

> **The Sky never renders journal prose. It shows *that* you wrote, not *what* you wrote.**

The detail says what the star was, when, and any mood as the person's own word: *"A check-in you
logged. Mar 4, 2024. Good."* A folded star adds how many records it covers. There is no text column
anywhere in the Sky to show more (§0.4).

The Sky names the act and hands off to the feature that owns the content. **Open it** does that,
behind whatever lock the feature has: a check-in, journal entry, practice or reached goal opens its
record; a life event opens the Life events list. A project step's screen is keyed by a goal id the
layout does not carry, so a step offers no action, rather than a disabled one.

The reason is the one behind the ban on note excerpts in the companion ([§D6](DECISIONS.md)): what is
protected is a place to write without an audience. The Sky is a worse place for an excerpt, because
people show it to others and screenshot it. A shoulder-surfer must learn nothing but that something
was written.

### 4.2 Navigation invariants

- **Anywhere is one gesture from anywhere.** No wizard, no forced sequence, no progress dots.
- **No "start".** It opens on the whole sky every time; where someone last was is not kept.
- **No future is drawn**, so there are no empty forward slots to fill. A life event the person dates
  ahead is their own mark, drawn as new.
- **No "jump to your best month".** There is no ranking (§6.2); the list runs in time order.

---

## 5. The tutorial is the sky itself

No walkthrough, carousel, coach marks or "3 of 5". Before anything is logged, the Sky is the field
and one line: *"This is the sky. Nothing of yours is in it."* A sky with one star names it, such as
*"A check-in you logged."*, and that is the whole onboarding.

### 5.1 Each kind names itself

Each kind's line opens every description of its stars: *"A check-in you logged."*, *"A practice you
used."*, *"An entry you wrote."*, *"A goal you reached."*, *"A step you completed."*, *"A mark you
placed."* **Naming, not praise**: never "Nice work!", because congratulation is evaluation, and there
is no exclamation mark on this surface. Any disclaimer or privacy sentence near the Sky reuses the
existing constants verbatim.

Not built: each kind introducing itself once, ever, the first time one of its stars appears (on
opening the Sky, never mid-entry; one per visit; dismissed by use), #149.

---

## 6. What it must never do

Each is something a reasonable person will propose, with the reason it is out.

### 6.1 Never infer a life event

No "we noticed a change around March — did something happen?", as a prompt, a suggestion, or a gap
the UI invites filling. Asking someone to explain a discontinuity in their data is inference with a
question mark on the end ([§D1a](DECISIONS.md)), and what it would most reliably detect is a period
of not coping.

### 6.2 Never rank days

No best day, worst day, brightest month or "strongest week"; no sorting but by time; no superlatives;
no visual quantity that grows with mood and no dimming by it (§3.4); no leaderboard against the
person's own past. Streaks are out product-wide ([§D6](DECISIONS.md)); on a permanent artefact a
broken streak is a scar with a date on it.

### 6.3 Never show a completeness metric

No percentage, "N of 365", coverage ring, density score, or count as a headline. **The sky is not a
progress bar**, and cannot become one, because no denominator is drawn (M3). The one count allowed is
list structure, "March 2024, 6 items", because a list without it is unnavigable:

> A count may be given **as list structure, at the point of navigation**. It may never be aggregated
> into a headline, compared across periods, trended, or shown to someone who did not need it to move
> around.

### 6.4 Never leave the device

- **Not in the clinician report**, on none of its four sides ([§D8](DECISIONS.md)).
- **Not in a share, a grant or a sync.** Life events leave the phone only in the person's own backup.
- **Not in telemetry or diagnostics**, including derived values: no star counts, no kind mix, no "sky
  opened" event.
- **No share button and no export** (§11 question 2). An export, if ever built, uses the system
  document picker and carries a one-time note, worded from existing privacy copy, that the image is
  unusually identifying (§6.5): a gallery is what a forgotten cloud-photo app copies.
- **Behind the app lock**, like the journal. With the lock on, the window is marked secure, so
  screenshots and the recent-apps thumbnail do not show it.

### 6.5 It is the most identifying artefact the product can produce

It has no prose, so it *feels* less sensitive than the journal. It is not. It is a multi-year,
per-record timeline of one person's behaviour, and timing alone re-identifies; its *shape* says what
its content does not, to anyone who knows the person; its brightest stars are the moments they marked
as mattering most; it is unique by construction; and it is what people screenshot and show someone.
Treat it as the most sensitive surface in the product, and design every affordance on it as if a
screenshot will exist.

### 6.6 Never notify, never nag, never congratulate

No notification about the Sky: not "your sky has grown", not "you haven't visited in a while"
(lapse-referencing, banned in [§D6](DECISIONS.md)), no badge, dot or widget. A place you are summoned
to is not a place you own. No congratulation, milestone stars, anniversary effects or unlocks.

### 6.7 Never model, never predict, never explain

The Sky is deterministic and explainable in one sentence per element: the same history draws the
same sky, bit for bit. No ML, no grouping by meaning, no anomaly detection, no "insights", no pairing
of one kind against another ([§D6](DECISIONS.md)). The Sky *shows*; the person *concludes*.

---

## 7. Accessibility — the honest section

**A starfield is a hostile surface**: small points on a dark ground, meaning in tiny geometry, a
canvas of thousands of marks. Saying so is more useful than mitigations that imply it is solved.
**The list (§7.5) is a peer, not a fallback**: the same data and actions, one tap away. It ships with
the Sky or the Sky does not ship.

### 7.1 Low vision

- **A star is not text, and its contrast is not held to a floor.** Its brightness is its age; the
  oldest star at the bottom of its breathe measures 1.53:1. What a star means (kind, date, mood) is
  written in its detail and in the list.
- **Mood colours are equalised, not lifted** (§0.3 finding 1): the shipped ramp and any colour a
  person picks go to exactly 6.5:1. One too dark or saturated to get there by scaling is blended
  toward the ink, visibly changing it; the lesser harm, since the alternative is a mood they cannot
  see. Swept over 5,832 colours, every one reaches the 4.5:1 floor.
- **The quiet sky**, one tap on the surface: no field (the biggest obstacle to finding real stars),
  the core alone and slightly larger, no glows or glints, thicker strokes, mood dots at 10.0:1. It
  defaults to the platform's high-contrast text setting and stays independently switchable, because
  that signal is coarse.
- **Touch targets are 48 dp** whatever a star's drawn size, resolving to the nearest core.
- **No fixed-size text**: nothing is written on the canvas.
- How the colours read on a real OLED panel at low brightness is for a person to judge: #147.

### 7.2 Colour vision

Colour carries neither kind nor mood on the sky, and a star's age is also written as a date, so
nothing on the canvas is lost to colour blindness. The mood dot uses the product's ramp (rust to
green, a red-green axis and not the Sky's to redefine) and always sits beside the mood word. The Sky
adds no second colour axis, and does not double mood up in shape: shape is kind.

### 7.3 Screen readers

- The canvas is **one node, described by what it is, not what it contains**: *"Your sky, 2019 to
  2026."* No enumeration, no total (§6.3). The route to the stars is the list.
- The field is `clearAndSetSemantics {}`: sky, not data (M1).
- **Absence is never announced**, in any modality: no "no entries for March". That is M2 in text,
  and the rule most likely to be broken by someone being helpful.
- **No live regions.**

Not built: stars focusable one at a time when zoomed in, in time order (never by position), with the
same name and action as a tap, #151.

### 7.4 Motion

Twinkle and drift are decoration and carry no meaning (§3.3). **Twinkle ships on**, behind the
**Motion** chip, which starts off when the platform's "Remove animations" is set
(`ANIMATOR_DURATION_SCALE` of 0, read in `SkyScreen`); the person can move it either way. The rules,
asserted as arithmetic in `SkyTwinkleTest`:

- **Everything stops under the switch**: no twinkle, no drift, no frame loop at all. Zoom has no
  animation to stop.
- **Only a few stars glint at any moment**, about four in a thousand, and none in the quiet sky.
- **A glint is under a third of a second.**
- **Nothing is ever in step with anything else.**

The frame loop runs only while the sky is on screen.

### 7.5 The text equivalent — a peer surface, not a fallback

One control in the top bar switches between the sky and the list.

- **Months as headings, in time order, and only months that have stars** (*"March 2024, 6 items"*).
  An empty heading is the void in text, so the list skips from March to July without comment (P10).
- **One row per star**: its kind's line, date, mood word if any, and how many records it covers if
  folded. A row does what tapping its star does (§4.1).
- **No field, no summary, no "your year in words".** The list is the data, not a reading of it.

The honest cost: the list is equal in information and not the same object emotionally, since nobody
sits and looks at a list. It gets the same design attention; pretending it is the same experience
would be worse than admitting it is not.

### 7.6 Cognitive and situational

No time limits, no auto-advance, nothing that pulls the eye; the Sky waits. No dead ends: Fit the
whole sky, the list and the back arrow are always one tap away. Not built: a key (§3.3), #154.

---

## 8. Performance with years of data

### 8.1 The scale to design for

| Profile | Span | Stars |
|---|---|---|
| Typical | 2 years | ~1,200 |
| Heavy | 5 years | ~6,000 |
| Extreme | 10 years | ~15,000, the number it must not fall over at |

### 8.2 The eight rules

1. **Lay out once, off the main thread**, when the data changes, never per frame.
2. **Structure of arrays, not objects.** `SkyLayout` is packed primitive arrays, not a `List<Star>`:
   fifteen thousand objects is avoidable collector pressure on a surface whose job is to be smooth.
3. **Stamp cached sprites.** Each distinct star (mood, quarter-year of age, temperature) is
   rasterised once. The cache holds 512, about 4 MB, and is dropped whole when full; a view with more
   distinct stars rebuilds them every frame, the number to watch if a five-year sky stutters.
4. **Cull by a bounds check per star.** No array order matches screen order, so every star is visited
   each frame: the price of a field with no empty regions.
5. **Level of detail.** Below CLOSE a star is one stamp; kind marks are stroked only at CLOSE, where
   the screen shows at most a forty-ninth of the field.
6. **The field costs screen area, not history.** Tiles come from `(seed, tile)` and are cached; the
   cache is cleared, never grown.
7. **Project the query; never load content.** One narrow query per kind returning
   `(id, epochMillis, moodLevel?)` and nothing else (§0.4). **The Sky never loads journal text at
   all**: a privacy property (§4.1) and the biggest memory win. Fifteen thousand stars in packed
   arrays is about half a megabyte; fifteen thousand journal bodies is not.
8. **Zoom never re-queries and never re-lays-out.** It is a transform on laid-out coordinates.

### 8.3 Budgets

Cold open to first paint under 300 ms with ten years of data on a mid-range phone; a steady 60 fps
panning at FAR with 15,000 stars and the twinkle running; no jank on zoom; peak memory in single-digit
megabytes. None is measured on a phone (§0.2): #147. If FAR cannot hold the frame budget, the answer
is more aggressive level of detail and **never** sampling or dropping stars: a sky that thinned for
the people with the most history would break M1 by way of a performance decision.

---

## 9. The year views are not the Sky

Two older components draw a year as a night sky on their own rules, which break this document's: the
**Stars** view of the year in Insights (`ui/components/YearInStarsGrid.kt`) and the year review's
quarter pages (`ui/insights/ReviewYearScreen.kt`). There, star size and glints follow mood, a day with
no entry is a faint speck, and the ground is still `#16150F`. The year review borrows `SkyField` for
its backdrop and nothing else. What to do about them is #148.

---

## 10. Checkable properties

Each is a unit test, so it survives a redesign by someone who has not read this, and every absence
check is first shown to catch a planted violation (`CLAUDE.md` §5).

| # | Property | Status |
|---|---|---|
| **P1** | Mood never changes a star's presence: core and total light are identical at every level | Built: `SkyGlyphTest`; `SkySurfaceSourceTest` pins the renderer to the halo as mood's only sink |
| **P2** | Absence has no glyph | Built: `SkyTest` |
| **P3** | The field and the clumps cannot see data | Built at the type level (`SkyField.tile` has no data parameter); `SkyWarpTest` |
| **P4** | The field is uniform | Built: `SkyFieldTest` |
| **P5** | A star never moves, and its date decides nothing about where it is | Built: `SkyTest`, with a thousand records inserted on both sides |
| **P6** | Colour and brightness are age and identity, never mood | Built: `SkyGlyphTest`, `SkySurfaceSourceTest` |
| **P7** | Kind survives monochrome | Built as geometry (`SkyGlyphTest`); drawn pixels are not compared |
| **P8** | No content leaves the record | Built: `SkyProjectionSourceTest` |
| **P9** | Each kind introduces itself once, ever | Not built: #149 |
| **P10** | The list skips empty months | Built: `SkyTest` |
| **P11** | No aggregate count is rendered | Built: the only count is a month heading's (`SkyPresentationTest`) |
| **P12** | Deletion is complete | Built: `SkyTest`, against a layout computed as if the record never existed |
| **P13** | Old stars never vanish | Built: `SkyAgeTest` |
| **P14** | Twinkle is identity and time only, and stops under the switch | Built: `SkyTwinkleTest` |
| **P15** | Only a mark the person placed is louder | Built: `SkyGlyphTest` |

---

## 11. Questions, and where they stand

1. **Where the Sky lives:** the More hub, not a tab. It is the most identifying surface in the
   product (§6.5), so reaching it is a deliberate act.
2. **An export:** none. The year review's "Save keepsake" was removed; §6.4 governs any future one.
3. **Life events spanning dates:** open, #155.
4. **Mood colours in a custom palette:** equalised (§7.1).
5. **The year review against these rules:** it changed; its finale shows two facts that rank nothing,
   the mood chosen most often and the date the person started. Its quarter pages are §9.
6. **Very small screens:** asked about month rows, which no longer exist; restate or close, #155.

A north star, a value the person names that is never reached and never brighter, is deferred: #155.
