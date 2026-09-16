# Plan 2026-09 — the Sky, people and communities, the timing layer

Agreed 2026-09-16. Nothing here is built. Order: **Sky → entry page with people → debug screen.**
Where this revises an earlier document it says so; otherwise `docs/SKY.md` and
`docs/DECISIONS_2026-08.md` still govern.

## 1. The Sky

- **Ground** goes near-black, not pure black. The star contrast target rises with it, so stars
  get brighter, not dimmer (they are pinned to a ratio against the ground).
- **Halo** becomes a radial fade to nothing instead of a flat translucent disc.
- **Twinkle** ships, on behind the existing motion switch (which already follows the platform's
  reduced-motion setting). Slow, low amplitude, brightness only. Each star's rhythm is a hash of
  its own identity, the way its position is: every star twinkles, each to its own beat, the same
  beat forever. Never a function of mood, kind or count. Lives in `sky/` so it is unit-tested.
  *Revises `docs/SKY.md` §7.4, which said off by default if it ships at all.*
- **Hue never shifts.** Colour is the mood the person logged.
- **Field** gets sparser, smaller and fainter. Still uniform, still blind to data, still
  switchable off.
- **Zoom** keeps the month-row model but zooms about the pinch point; double-tap resets; a small
  "Today" control returns to now.
- The year review's separate backdrop is replaced with the same field.
- **Rejected:** a north star that is an unreached goal (not an act, so not a star). **Deferred:** a
  north star the person names that is a value, never reached, never brighter.

## 2. People and communities

- A tag kind alongside activities. Groups for sorting the picker only: friends, family,
  partners, communities, other.
- Each carries one free-text line, **"who (or what) is this to you"**, in the person's words,
  editable. No status field, no dates, no photos. The app stores, shows and shares it; it never
  reads it.
- **Archive** hides one from the picker. Every entry that named them is untouched.
- An entry gains **"with"**.
- **Never in a statistic.** Excluded from correlations, patterns, prompts and cards, groups
  included, enforced by signature the way the Sky's field is kept blind to data. Activities stay in
  statistics; putting "meetup" in as an activity is the person's choice.
- **Sharing is per person and per community**, off by default even under an accept-all grant.
  The clinician sees exactly the words written and the entries naming them. An unshared one shows
  as *with one person, not shared*, never as a blank.

## 3. The entry view page

Descriptive only: the person's own mood word, activities, with, note, photo. No commentary of any
kind. Never "you seem".

## 4. The timing layer

Stays the arbiter of D1: each feature holds its own reason to speak; one gate answers *may I,
now*, and knows only its own history with the person.

- **New:** the reception ledger records the hour and weekday of every ask alongside its outcome.
  Allocation places the allowed asks into hours that have been answered before and out of hours
  that have not. Same total as the frequency setting. It never learns *why* an hour goes unanswered.
- **Phrase pool:** a small set of fixed, human-written openers, rotated. Separate morning and
  evening pools are fine (the clock is a fact). The draw is blind to mood; a phrasing is never
  chosen because of how the person seemed.
- It never holds a mood trend, goals, people or communities.

## 5. Debug screen (debug builds only)

Per feature: the rule, what it reads, its current values, whether it would fire now and if not
why, offers made and how they were answered, and how much the gate is holding back. Plus the
timing grid (hour × weekday, answered or not) with the reason for each recent decision, and the
phrase pool. Could later be shown to everyone; starts debug-only.

## Not doing

App commentary on entries. A relationship model of any kind. Photos of other people.
Mood-with-person or mood-with-community statistics. A north star that is a goal. Colour-shifting
stars.
