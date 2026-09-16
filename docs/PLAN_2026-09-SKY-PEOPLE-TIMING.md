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
- **Every star has a white heart.** The core is the same near-white for every star; the mood
  is the colour of the glow and how far it spreads. That is how a real star looks, and it makes
  equal presence structural rather than arithmetic. *Revises `docs/SKY.md` §3.4's coloured core.*
- **Colour is age, not mood: redshift.** A star's tint is how far away in time it is, blue-white
  when recent, through gold and amber to a deep red after some years, on one continuous ramp.
  Time is a fact about the star, not a reading of the person, and red stops meaning "bad".
- **Old stars recede but never vanish.** Brightness fades with age to a floor and stays there,
  so an old year is far sky rather than a void; nothing is ever dropped from the surface. Only
  the entries themselves are the record; the sky is a view of it.
- **Mood is the character of the light and nothing else.** A hard day spreads its light wide and
  soft; a good day gathers it into a sharp point; the total light is the same and so is the white
  heart. Mood never touches brightness or colour. The mood word is on the sheet when a star is
  tapped and on every row of the list.
- **A landmark is the one bright star.** A life event is a mark the person placed to be found, so
  it alone is bigger, brighter, spiked, never redshifted and never faded. Brightness may follow a
  mark the person placed, never anything the app measured: a reached goal keeps its glint at
  lean-in and a journal page stays the size of everything else.
- **Twinkle is three things, all decoration:** a slow breathe in brightness, a faint quick shimmer
  on some stars, and now and then a quarter-second prism glint, a red and a blue fringe added on
  top of the star and gone again. The star's own tint never changes; the glint passes over it.
  **Motion safety rules:** everything stops under the motion switch; at any moment no more than a
  few percent of stars are glinting; a glint is under a third of a second; nothing is ever in
  step with anything else; amplitude stays low. Landmarks glint a little more, as bright stars do.
- **Fidelity:** sprites at the device's full pixel density, glows drawn additively, the field with
  varied sizes and warmth. It is meant to be beautiful, and that is allowed as long as nothing in
  the beauty is a reading of the person.
- **A landmark can be placed from an entry**, not only from the life-events screen: a "mark this
  day" action on the entry page, so the bright stars are easy to make.
- **No marks for kind at ordinary zoom.** A journal page, a step, a goal reached and a life
  event are all just stars until the person leans in to a single day, where the glyph appears.
  The text list still names the kind. *Revises `docs/SKY.md` §3.4, which drew glyphs at a
  month.*
- **Field** gets sparser, smaller and fainter. Still uniform, still blind to data, still
  switchable off.
- **Zoom** keeps the month-row model but zooms about the pinch point; double-tap resets; a small
  "Today" control returns to now.
- The year review's separate backdrop is replaced with the same field.
- **Zoom, restated after seeing five years:** a month is the full width all the way out and
  widens only when leaning in, which is the app's existing model; pulled out to years the sky
  reads as a field, with each year carrying its own faint nebula seeded by the year and blind to
  what happened in it.
- **Rejected:** a north star that is an unreached goal (not an act, so not a star); dimming by
  mood, in every form it was asked for; dropping old stars entirely. **Deferred:** a north star
  the person names that is a value, never reached, never brighter.

## 2. People and communities

- A tag kind alongside activities. Groups for sorting the picker only: friends, family,
  partners, communities, other.
- **Each has a page**, the way the mood side of the app is about moods: a **"who (or what) is
  this to you"** line, then dated notes the person writes about them over time, then the entries
  that name them. All free text in the person's words. No status field, no dates about the
  relationship, no photos. The app stores, shows and shares it; it never reads it.
- **Archive** hides one from the picker. Entries and notes are untouched.
- An entry gains **"with"**.
- **Never in any rule that reads mood.** Correlations, patterns and the cards they produce cannot
  receive a person or a community, groups included, enforced by signature the way the Sky's field
  is kept blind to data. Activities stay in statistics; "meetup" as an activity is the person's
  choice.
- **Prompts about people are a feature of their own**, reading only tags and dates, never mood,
  and asking the gate before speaking like every other feature. Allowed: an entry names someone
  who has no page yet, so offer one, once; someone has come up several times and has no page,
  offer once. **Never:** "you haven't written about X in a while." A gap is never a prompt. A
  person's page may state *last note: June* as a fact, when opened.
- **Sharing** is one screen listing every person and community, with a default per group (all
  off) and overrides per item, off even under an accept-all grant. On a person's page the state
  is one quiet line at the bottom: present, never a nag. The clinician sees exactly the words
  written and the entries naming them. An unshared one shows as *with one person, not shared*,
  never as a blank. The word is **clinician** throughout: a therapist, a doctor and a psychiatrist
  are one role to the app.

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
- **The reception ledger and the timing grid are never shared with a clinician.** When someone
  answers the app is the app's business with them, and it stays on the phone.

## 5. Debug screen (debug builds only)

Per feature: the rule, what it reads, its current values, whether it would fire now and if not
why, offers made and how they were answered, and how much the gate is holding back. Plus the
timing grid (hour × weekday, answered or not) with the reason for each recent decision, and the
phrase pool. Could later be shown to everyone; starts debug-only.

## Not doing

A request-access flow: every connection starts with the owner's invitation, and nobody can ask
for one. A prompt about someone's absence from the record. App commentary on entries. A relationship model of any kind. Photos of other people.
Mood-with-person or mood-with-community statistics. A north star that is a goal. Colour-shifting
stars.
