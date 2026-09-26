# Features

What the Android app does today, and the rules each feature keeps. Sections are numbered so code
can cite them (`FEATURES.md §11.2`). The Sky has its own reference, [SKY.md](SKY.md). How the app
is built is in [ARCHITECTURE.md](ARCHITECTURE.md), and what is stored where is in
[PRIVACY.md](../PRIVACY.md). Where something is designed but not built, one line says so and links
the issue.

## 0. Rules that hold everywhere

- **Not a medical device.** The self-checks, sleep features, breathing check and support are
  non-diagnostic. Nothing detects, diagnoses or treats a condition, and nothing gives an all-clear
  (§9).
- **No generated content.** Every word the app shows is fixed, human-written copy with the person's
  own numbers slotted in. Personalisation means fixed rules over the person's own data.
- **Descriptive, never interpretive.** The app says what was logged. It never says how someone must
  have felt.
- **No scores, streaks, badges or congratulation.** Continuity is counted without requiring days in
  a row ("12 of the last 30") and is left out entirely at zero. A gap in someone's data is never
  drawn as a failure ([DECISIONS.md](DECISIONS.md) §D6).
- **A person's own views describe what was logged and never mark it.** No mood average as a
  headline or "out of 5", and no period judged better or worse than another (#203). Not built: the
  Insights views and suggestion cards that still do (§1.5, §3): #354, #358, #360.
- **Offers, not demands.** An offer the person can ignore for free is an offer. Anything that moves
  them somewhere they did not ask to go is a demand, and the app makes offers. The one exception is
  one the person switches on themselves (§7.2).
- **Offline.** The released `foss` build has no `INTERNET` permission, and CI fails if one appears.

## 1. Entries, Home and the entry page

### 1.1 An entry

- A mood on a five-level scale (Awful, Bad, Meh, Good, Rad), plus optional activities, the people
  it was *with* (§11), a note ("Why do you feel this way?") and one photo. Any number a day; the date
  and time can be changed.
- **Moods can be renamed and recoloured** (Settings → Customize moods). The level 1–5 is what is
  stored, so entries keep their place. Custom names and colours appear everywhere, including the
  widget and the CSV, and travel in backups. Not built: a scale with a different number of steps:
  #206.
- **Activities.** A library of 103 ready-made activities in 12 categories, plus the person's own.
  They can be added, renamed, given an icon, archived and restored. An archived activity stays on
  past entries.
- **Photos** come through the Android Photo Picker, which needs no storage permission. Each is
  downscaled and re-encoded, which drops its location and every other piece of metadata
  (`data/ImageStrip.kt`), and kept in app-private storage. The app does not encrypt photos (§16).
  Not built: telling the person, once, that a photo's content can still show where they were:
  #188.

### 1.2 Home

Home is the daily loop, not an archive. It asks one question, "How are you, right now?", over a row
of faces; tapping one opens the editor with that mood chosen. Below that: the number of entries and
the last seven days as small bars, where a day with no entry is a faint stub; at most one suggestion
card (§1.5); and today's entries. **More for you** holds the other suggestions and On this day.
**All entries** is the whole history, newest first. Home shows no count of consecutive days: it is
the screen someone lands on after time away. Home's search button searches entry notes; the journal
has its own search (§2).

### 1.3 The entry page

Tapping an entry anywhere opens a page that shows it. The editor, with its delete button, is one
deliberate tap further on, because opening the record of a hard day straight into a form is the
wrong first thing to meet.

The page is **descriptive only**: the person's own mood word, their activities, who they were with,
their note and their photo. There is no commentary of any kind: no "you seem", no comparison with
another day, no count, no encouragement. `EntryViewCopySourceTest` reads the page's strings and
fails on that vocabulary. Tapping a name opens that person's page, never a statistic about them.

**Mark this day** places a life event on the entry's date, in the person's words (§4).

### 1.4 Deleting an entry

Three guards stand in front of a delete. A swipe arms only once it has crossed most of the row, and
then reads "Release to delete". A dialog asks for confirmation. An Undo snackbar then restores the
entry with its activities and people. The swipe on its own never deletes anything. The editor's
delete button also asks, and says there is no undo behind it. [ARCHITECTURE.md](ARCHITECTURE.md) §6
has the rules for changing any of this.

### 1.5 Suggestions

Suggestion cards are chosen by fixed rules over the person's own data
([ARCHITECTURE.md](ARCHITECTURE.md) §5): an offer to take a moment after a hard day, a self-check
that is due, what tends to go with their mood, a month that differs from the last, and On this day.
Every card has a menu:

- *Not right now*: gone for this visit only. Nothing is stored.
- *Show less like this*: moves down by a fixed amount and steps back for three days.
- *Remind me in a few hours*: quiet for four hours.
- *Not helpful — hide it*: quiet for 30 days.
- *Turn this suggestion off*: off until turned back on.

Settings → Suggestions lists each kind as On or Off, says when a snoozed one returns, and can bring
it back at once; turning one on clears everything that held it back. Nothing is learned from what
the person taps. Home's check-in row, the "what might help" menu and crisis resources cannot be
switched off.

Not built: suggestion settings come back after a restore: #191. A suggestion to make a goal from a
factor opens a blank goal editor rather than one with the factor filled in: #178.

### 1.6 On this day

Entries written on today's date in earlier years, shown under More for you. It is a suggestion group,
so it can be snoozed or turned off like any other.

## 2. Journal

A diary separate from mood notes: a title and a body, not tied to a mood, with its own search. A
fresh page offers three optional starters in the app's own words: **Three good things**,
**Expressive writing** (write freely for fifteen minutes, shown with a note that it can stir up
hard feelings and a link to the crisis resources, §7.3), and **Reflect on the day**. Journal
entries are stars in the Sky (§4), and reach the PDF report only when the person includes them
(§14).

## 3. Insights

- One tab with a **Week / Month / Year** switch. Always shown: the number of entries, the average
  mood, "Days with an entry: 12 of the last 30" (days need not be in a row, and the card is absent
  at zero), mood over the last 30 days, mood distribution, and average mood by activity.
- **Week** shows this week as bars. **Month** is a calendar tinted by mood; tapping a day opens its
  entries. **Year** is either a night-card of stars or a grid of coloured squares (Year in Pixels,
  also under More), and leads to **Review my year**.
- **What goes with your mood** ranks activities that appear in at least 5 entries into "Lifts you
  up" and "Weighs you down", and lists trackers that have values on at least 14 days with a mood.
  It is computed on the phone and always labelled "association, not cause". People and communities
  never reach it (§11.2).
- **By day of week**, **by time of day**, and **this week, month or year against the last**.
- **In review** (a short recap written by fixed rules) and **Logging consistency** (a heatmap of
  entries per day). Both read as grades, and "Days with an entry" is the one figure for how often
  someone logs (#203). Not built: removing both, with nothing in their place: #354.
- Suggestion cards sit at the top (§1.5).
- **Review my year** is a full-screen walk-through: an introduction, one page per quarter, and a
  finale with at most two facts, the mood chosen most often (in the person's own word for it) and
  the day of the first entry. There is no average, no streak, no "brightest month" and no image
  export: a year of someone's moods as a single file is the most identifying thing the app could
  make.
- The year view's stars and the review's quarter pages still size a day's star by its mood and draw
  a day with no entry as a faint speck, against the Sky's rules. Not built: one year sky in place of
  both, on the Sky's rules (#148; [SKY.md](SKY.md) §9): #347, #349, #350, #351.

## 4. Your sky and life events

**Your sky** (More → Your sky) draws everything the person did as stars in a night sky: mood
entries, journal pages, thought records (the one practice that keeps its own dated record, #283),
project steps done, goals marked reached, and life events. A star's colour is its age; mood moves
only its halo; a missing day is never drawn. A **life event** is a mark on one day in the person's
own words, added from the Sky's life-events list or with Mark this day (§1.3). Every rule the Sky
keeps is in [SKY.md](SKY.md).

## 5. Goals and projects

- A goal is one of two shapes. A **weekly habit** is "do this so many times a week", counted as the
  days this week with an entry tagged with its activity ("3× per week · 2 of 3 done"). A **project**
  holds concrete steps on a three-column board, To do, Doing and Done, and states its progress as
  "3 of 7 steps done". A project needs at least one step.
- Any goal can carry an optional if-then plan: "When… (cue)", "I will… (routine)".
- **Reached is the person's to say.** A **Reached** switch in the goal editor ("Yours to say, and
  yours to take back.") records the day; the goals list then says "You marked this reached." and the
  Sky gains a star. Nothing computes it: hitting a weekly target is not reaching a goal, and neither
  is every step being done. Archiving is not reaching either. It is setting something
  aside, one neutral tap, never counted as failure and never drawn as a star
  (`goals/GoalReached.kt`).
- A project's board has no due dates, no percentage, no count of steps still to do, and no column
  that is the good end. Every move between columns is allowed in both directions
  (`goals/GoalBoard.kt`).
- Not built: an optional "When…" line on each project step, which replaces the project-level plan on
  new projects (#184): #348.

## 6. Trackers

Custom trackers record anything next to mood, as a **scale**, a **number** with a unit, or **yes /
no**. Each has a history and an average. A tracker with values on at least 14 days that also have a
mood appears in "What goes with your mood" (§3). Do one thing and Move keep their own trackers
(Enjoyment, Mastery, Movement minutes), so those show up against mood too.

## 7. Skills and "Take a moment"

### 7.1 Self-help skills

All under More. None of them is treatment.

- **Check-ins: PHQ-9, GAD-7, WHO-5.** Non-diagnostic self-checks with a history and a small trend.
  Only the **score and band** are stored, never the answers. If the PHQ-9 self-harm item is answered
  above "not at all", the result adds a gentle card that leads to the offline crisis resources; it
  never gives a risk verdict. A check-in that is due can appear as a suggestion. Licences:
  [INSTRUMENTS.md](INSTRUMENTS.md).
- **Thought records.** Situation, automatic thought, optional thinking traps (from the app's own
  list), evidence for and against, a balanced thought, and mood before and after. A reflection, not a
  verdict.
- **Do one thing** (behavioural activation). Pick something small, optionally set a reminder, then
  rate enjoyment and sense of accomplishment, which are logged to trackers.
- **Move.** Gentle stretching and bodyweight interval routines with original drawn pose figures and a
  timer that vibrates at each step, so it works with eyes closed. Each session logs Movement minutes.
- **Breathing pacer.** Slow (about 6 a minute, the default), box (4·4·4·4) or 4·7·8, with haptics
  for in and out. It runs at true speed even when animations are turned off, because the timing is
  what a person breathes along with.

### 7.2 "Take a moment"

A calm screen for a hard moment. It opens with an acknowledgement, then offers a menu: move a
little, breathe, untangle a thought, write it out, do one small thing, the person's safety plan
(only once one has something in it, §8), crisis resources, and "Not right now".

It is reached from the "Want to take a moment?" suggestion after a low-mood entry that day, and
through **Gentle support** (More → Gentle support), which can preview it and is otherwise off until
the person turns it on. When it is on:

- **A quiet corner action.** Choosing Awful or Bad in the entry editor puts a "Take a moment" action
  in the top bar. It only appears; it never moves the person or reflows what they are typing, so it
  needs no rationing. It is text, set apart from the delete button ("get help" never sits flush
  against "destroy this"), and following it keeps the half-written entry.
- **Being taken there** is separate, and is the person's choice: after saving a hard day the app may
  open the support space. The setting (`stats/SupportOffer.kt`) is Never; at most once a day (the
  default); at most once a week; or every time. The default is a judgement call, not evidence. Each
  one is recorded in the reception ledger (§13). The arbiter can only ever make them rarer than the
  setting, never more frequent ([DECISIONS.md](DECISIONS.md) §D1a). Today it never does: the support
  space cannot yet tell a dismissal, so every offer is logged as taken up and the setting is exactly
  what the person gets.

The rules it keeps, condensed from the design research:

1. Acknowledge first, without saying how the person feels or how their day was. Never cheer a low
   mood.
2. A menu the person chooses from, and "Not right now" is always the first option. No single forced
   exercise.
3. Calm and low in stimulation: muted, slow, little motion. Nothing on the screen moves on its own.
4. Offer, never force: a small action the person reaches for, never a takeover they did not choose.
5. Any default is stated plainly and can be turned off in one tap.
6. Honest about effect: general wellness support that points toward real care, never treatment.
7. A crisis floor under all of it (§7.3).

Never offered: generic affirmations, cold-water immersion, or a comparison with the person's
earlier, better days. Every default is the same for everyone. Daymark never asks what someone uses
it for, or records a condition, to set one (#166). The screen and rules 1–3 disagree today: "Not
right now" comes last, the options drift on endless animations with no reduced-motion check, and
the opening line puts a feeling into words. Not built: #352 (the order and the motion), #353 (the
opening lines).

Not built: more techniques (progressive muscle relaxation, cognitive defusion, brief mindfulness, a
self-compassion break, naming the feeling, 5-4-3-2-1 grounding, a calm game): #170. A clinician
recommending, never setting, how often the support space is offered: #172.

### 7.3 Crisis resources and the crisis floor

- **One resource, kept on the phone and edited by the person.** It starts as "Call or text 988" /
  "988 Suicide & Crisis Lifeline (US)" and is changed under Gentle support → Crisis resources, or
  with "Use a different number" on the crisis screen. The screen says Daymark cannot call for anyone
  and is not a crisis service, and to call the local emergency number in immediate danger.
- **Always reachable.** Last in every support menu, never dismissible, untouched by suggestion
  settings.
- **Tiered, not a cliff.** The support menu offers ways to cope first, then the person's own plan
  and the people in it, then the crisis resource, which is always one tap away. The handoff is warm,
  never an abrupt list of numbers.
- **No covert detection or labelling.** Nothing infers from someone's data that they are at risk or
  that they need their safety plan. The one thing that puts crisis resources in front of someone
  unasked is the PHQ-9 item they answered themselves. If detection were ever added it would confirm
  with the person before routing, and could be turned off. Nothing contacts anyone on the person's
  behalf.

## 8. The safety plan

- **More → My safety plan.** Short lists in the person's own words, written while things are steady
  so a harder moment does not start from a blank page: *Warning signs I notice*, *Things that help*,
  *People I can reach* (a name, and optionally who they are), and, only if the person adds it,
  *Reasons I want to stay*. That fourth section is offered as a quiet card ("Optional. Only if you'd
  want it there."), never shown as an empty section on a bad day.
- **Read and edit are one screen**, with no save step, so nobody has to find an edit button in a
  hard moment.
- **The crisis row** shows the person's own saved resource (§7.3) and opens the crisis screen. It
  never places a call. The footer says "A plan is not a person — reaching one is the point."
- **Where it appears:** under More, and as a quiet row in "Take a moment" once it has something in
  it. Never as a suggestion card: nothing infers from mood that someone needs it.
- **Not the Stanley–Brown form.** That form needs its authors' written permission to be programmed
  into an electronic record. This is the app's own wording, drawing on the general method of safety
  planning, and is labelled **Adapted**: "A plain safety plan in our own words... not a validated or
  clinical instrument, and not for diagnosis." Its row is in [INSTRUMENTS.md](INSTRUMENTS.md).
- **No means-restriction step.** [PROVENANCE.md](PROVENANCE.md) rule 4 (no self-harm item in any
  tier's shareable output) makes the omission a house rule. The person can write anything; the app
  does not prompt for it.
- **Stored one row per line**, never as a comma-separated column, because a person's line can
  contain a comma. It is in backups and never synced. If sharing it ever ships, it is an
  owner-created, time-boxed, revocable share and never automatic
  ([COMPANION_ACCESS_CONTROL.md](COMPANION_ACCESS_CONTROL.md)).
- Not built: a quiet link to the plan from the crisis screen, once the plan has something in it
  (#357), and a printed or saved copy behind a warning shown every time (#359), both decided in
  #175.

## 9. Sleep

More → Sleep check-ins:

- **Sleep diary.** Log last night: bedtime, wake time, time to fall asleep, time awake, quality and a
  note. The app works out time in bed, total sleep and efficiency. Averages wait until seven nights
  are logged, because single nights vary a lot.
- **Sleep and mood.** Average mood the day after better-sleep and rougher nights: "A pattern, not
  proof of cause."
- **Self-checks** for sleep apnea signs, restless legs and insomnia signs, in the app's own wording
  (the well-known screeners are licensed and not bundled; see [INSTRUMENTS.md](INSTRUMENTS.md)).
  Each result is a band and the sentence "This is a self-check, not a diagnosis — it can't rule
  anything in or out."
- **Treatments, before and since.** Mark the date something changed (CPAP, surgery, an oral
  appliance, positional therapy, medication, other), then compare average sleep, efficiency, quality
  and mood before and since. It shows what changed, not why, and is not a measure of whether a
  treatment works.
- **Sleep setup.** Saved answers about a bed partner, pets, where the phone lies, background noise
  and sleep position. Nothing reads them, and nothing will: the sensing they were for is ruled out
  (#212). Not built: removing the screen and its answers: #356.
- **The breathing check** (§10).

The rules:

- **Never give the all-clear.** The app may raise a concern. It must never say "you don't have
  apnea", "you're fine" or "you don't need testing". Not seeing a pattern means only that none was
  seen: a quiet sleeper, a bad sensor night or the wrong mode can hide a real problem, and false
  reassurance is the dangerous failure. Every result is an observation plus "not a diagnosis;
  consider a clinician".
- **General wellness only:** no AHI number, no diagnosis, no treatment claim.
- **What a phone cannot do, said plainly:** an AHI; a diagnosis; central versus obstructive apnea;
  overnight blood oxygen; sleep stages; whose snore or movement it was, from one bedside microphone;
  reliable restless-legs or limb-movement detection from a phone on the mattress.

Sleep sensing stops at the breathing check and its overnight version (§10; Not built: #218). There
is no sleep window from phone use, no microphone, no sonar and no sleep nudges (#212). Any sensing
is rules-based signal processing, never machine learning ([CLAUDE.md](../CLAUDE.md) §0).

The diary says "Around 85% efficiency or more is a common good-sleep mark — a reference, not a
verdict." Not built: saying what efficiency is instead, with no mark: #355.

## 10. The breathing check

Experimental. The person lies still with the phone flat on the chest or upper belly for one, two or
three minutes. The screen stays on only while it measures. It uses the accelerometer alone, which
needs no permission; nothing is recorded or stored, and only the result is shown.

The algorithm (`sensing/BreathingDetector.kt`, pure and unit-tested) takes the magnitude of the
accelerometer at about 50 Hz. It removes posture and gravity with a 4-second centred moving average.
The rate comes from normalised autocorrelation within 6–30 breaths a minute, and the height of that
peak is the confidence. A pause is a stretch of at least 10 seconds in which a 2-second RMS envelope
stays below a quarter of its median. It needs at least 20 seconds of data.

The result is "About N breaths/min", with a note when the signal is faint. Flagged pauses come with
"worth a clinician's look. This is an experimental reading, not a diagnosis." A failed reading says
"Couldn't get a clear reading". With no pauses it says only that none were flagged in this reading.

Not built: overnight capture: #218. It waits on the two-minute check on a real phone in #147.

## 11. People and communities

### 11.1 Pages, groups and "with"

- People and communities are a tag kind next to activities. An entry gains **with**: a picker beside
  the activity chips, where "Add someone" also makes them a page.
- **Groups** (friends, family, partners, communities, other) sort the picker and the list, and do
  nothing else. A group is not a relationship model.
- **Each has a page**: a "who (or what) is this to you" line, dated notes the person writes about
  them over time, then the entries that name them. It is all free text in the person's words. There
  is no status field, no date about the relationship, and no photo. The app stores it and shows it;
  it never reads it.
- **Archive** hides someone from the picker. Their page, notes and entries stay as they are.

### 11.2 Never in any rule that reads mood

Correlations, patterns and the cards they produce cannot receive a person or a community, groups
included. This is enforced by shape, not by convention: `EntryDao`, the one that returns mood, has
no method that touches `entry_people`; no `PersonDao` query joins `mood_entries`; and
`MoodCorrelations` takes a `FactorId` that only an activity or a tracker can make. A person's page
shows the entries that name them without their moods. `PeopleSchemaTest` and `PeopleUiSourceTest`
check this. There are no mood-with-person or mood-with-community statistics, and there never will
be.

### 11.3 Prompts about people

Prompts about people read only tags and dates, never mood, and ask the arbiter before speaking. Two
are allowed: an entry names someone who has no page, so offer one, once; someone has come up several
times and has no page, so offer once. Not built: #156.

**Never: "you haven't written about X in a while."** A gap is never a prompt. A page may state
*Last note: June* as a fact when the person opens it.

### 11.4 Sharing

- **Off by default.** One screen lists every person and community, with a default per group (all
  off) and an override per person. A quiet line at the foot of a person's page says whether they are
  shared with the clinician: present, never a nag.
- What a clinician would see is exactly the words written about someone and the entries that name
  them. Someone not shared would show as *with one person, not shared*, never as a blank. Sharing
  stays off even under an accept-all grant.
- Nothing reads these switches yet: no export, report or sync path uses them. Not built: #157
  (which also depends on #138).
- The word is **clinician**: a therapist, a doctor and a psychiatrist are one role to the app.
- People, notes, *with* links and group defaults travel in backups. A restore never turns sharing on
  for someone already on the phone.

## 12. Reminders

- Settings → Reminders holds as many as the person likes, each with a time, an optional label and
  an on/off switch. First-run setup offers one. Android 13 and later ask for notification permission
  the first time. Reminders fire at the exact time where Android allows it, and are re-armed after a
  restart.
- A reminder's notification, or its **Log now** action, opens a fresh entry.
- **Recorded, never rationed.** A reminder is at a time the person chose, so the arbiter does not
  gate it: quietening something they scheduled would override them, and there is no reminder
  setting for them to turn back up (`notifications/ReminderScheduler.kt`). Every firing still
  writes a line in the reception ledger (§13.2).
- Not built: answering a reminder with "this time works", "try later" or "stop asking": #195.

## 13. Why it asks: the arbiter, the reception ledger and the timing layer

### 13.1 The arbiter

Each feature keeps its own reason to speak. One gate (`stats/InterruptionBudget.kt`) answers only
"may I, now?", from the person's declared frequency and the gate's own history with them. It knows
nothing about moods, goals or people, and it may only ever ask less
([DECISIONS.md](DECISIONS.md) §D1, §D1a). Today its one caller is the support offer (§7.2).

### 13.2 The reception ledger

The `offer_records` table notes which feature asked (the support offer, a reminder, and two kinds
nothing uses yet: the companion and an assignment), when, the hour and weekday at the moment of
asking, whether anything came back, and what became of it (accepted, dismissed, snoozed, or stop).
It holds no free text and nothing about the person. Rows are never updated, and are deleted after
60 days.

**The reception ledger and the timing grid are never shared with a clinician.** When someone answers
is the app's business with them, and it stays on the phone. The ledger is in no backup, CSV or
report, and restoring with Replace empties it. `TimedOfferSchemaTest` asserts that. Rows written
before schema v18 have no hour or weekday, and none is ever worked out for them.

### 13.3 Placement and the phrase pool

- `stats/TimingGrid.kt` can place a feature's allowed asks into hours that have been answered before
  and out of hours that have not, with the same total as the frequency setting. It can only turn a
  yes into a no.
- "Answered" means the person was there, not that they liked it: a dismissal counts exactly as much
  as an acceptance. It never learns why an hour went unanswered. Asleep, busy and a hard week look
  identical to it. It never holds a mood trend, goals, people or communities.
- The phrase pool (`stats/PhrasePool.kt`) is a small set of fixed, human-written openers, one set
  for mornings and one for evenings. The draw is blind to mood.
- **Today placement decides nothing, and the pool is never spoken.** A reminder is at a time the
  person chose, and the support offer is made while they are already in the app, so neither is an
  ask an hour should be chosen for. No rotation is stored. Both stay dormant until Daymark starts an
  ask of its own, such as the companion surfacing itself (#272), and are never applied to a reminder
  or to the support offer (#159).

### 13.4 The debug screen: "Why it asks"

In debug builds only; the route, the Settings row and the screen each check separately. Per feature
it shows the rule, what it reads, its current values, whether it would speak now and, if not, why
(in one of a few fixed sentences), the offers made and how they were answered, and how much the
gate is holding back. It also shows the hour-by-weekday grid and the phrase pool. It computes
nothing itself: every value comes from the same engines the app uses. An unanswered hour is drawn as
an hour with nothing in it, with no red and no warning. Only the support offer has a frequency the
person set; for the others the screen says the value is a default. Not built: a history of past
decisions rather than a reading of the current moment: #162.

## 14. Backup and export

- **Backup** (Settings → Export backup) writes one JSON file: entries with their activities and
  people, the journal, goals and project steps, sleep logs, treatments, trackers and their values,
  reminders, check-in scores, thought records, the safety plan, life events, people and the notes
  about them, group sharing defaults, custom mood names and colours, and photos embedded in the
  file.
- **Not in a backup:** the reception ledger, by design (§13.2); suggestion settings (#191); the
  crisis resource, the sleep setup answers and the latest sleep self-check results; app settings and
  the PIN.
- **Restore** (Settings → Restore backup) either replaces everything or merges the file alongside
  what is there, with fresh ids. An older backup still reads. A backup from a newer version of the
  app is refused.
- **CSV** (Settings → Export as CSV): date, time, mood, activities and note for every entry.
- **PDF report** (Settings → Export a PDF report, "A printable copy to hand to a clinician. Not
  encrypted.") has four sides, each with one job ([DECISIONS.md](DECISIONS.md) §D8): the glance, the
  detail, the person's own words, and notes for the conversation. It ends with a hash of the entries
  it covers, as text and as a QR code, so a later change to them could be detected; no tool to check
  it exists yet (#377). It carries no streak, no trend line joining separate check-ins, and no
  inference about the person. The dialog opens with "A report is a copy. Once handed over, it cannot
  be taken back." — the report's own fixed copy, not a second wording — then offers a date range (90
  days by default), check-in notes (off by default), charts (on), and the journal for that range,
  all of it or none, off by default. With check-in notes off, side 2 says "Check-in notes were
  switched off for this export." and neither of its tables has a note column; the daily check-ins
  keep their activity tags under a head of their own. A screen for choosing journal entries one at a
  time exists (`ui/export/JournalPickerScreen.kt`), but nothing opens it yet. Not built: a preview
  before exporting: #198.
- Every export is a plain, unencrypted file, made by the person's own act. Not built: encrypted
  backups and exports: #236.

## 15. The widget

A home-screen widget asks "How are you?" over the five moods, in the person's own colours. Tapping a
mood opens a new entry with it chosen; nothing is saved until the person saves.

## 16. The lock and encryption at rest

- **The database is encrypted for everybody** from the first run, with a random key held by the
  phone's hardware keystore. A copy of the app's storage taken elsewhere cannot be read. An older,
  unencrypted journal is converted once. Settings → "Your entries on this device" says what is true
  on this phone, including "Not encrypted on this device" where the conversion has not succeeded yet
  or the phone cannot hold a key. If the phone ever loses the key, the app says the entries cannot
  be opened and offers to leave them or to start a new journal. It never removes them on its own.
  How it works: [ARCHITECTURE.md](ARCHITECTURE.md) §4.
- **Not covered:** photos (#239); the settings file, which also holds custom mood names, the crisis
  resource, the sleep setup answers and the latest sleep self-check results; and exports (§14).
- **The app lock** is an optional PIN of 6 to 12 digits, offered during first-run setup. A PIN set
  by an older version keeps working at its length. Only a PBKDF2 hash is kept, in an encrypted
  preference store. After five wrong tries the app makes the person wait, longer each time, up to
  five minutes. Strong (Class 3) biometrics can unlock it too. It re-locks at once when the app
  leaves the screen, or after 1, 5 or 15 minutes if the person chooses. While the lock is on,
  screenshots and the recent-apps thumbnail are blocked.
- **The PIN guards the screen, not the file.** No key is made from it. But the lock screen has no
  way past a forgotten PIN except biometrics: without them, the way back today is to reinstall,
  which erases the app's storage, and restore a backup. Settings says a forgotten PIN "does not lose"
  the entries, which is true of the file and not of the person's experience: #146. The designed way
  back, a PIN-wrapped key and a written-down recovery code, is built and tested but not switched on:
  #109.
