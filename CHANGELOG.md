# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **My safety plan**: short lists you write **while things are steady**, so a harder moment doesn't
  have to start from a blank page — *warning signs I notice*, *things that help*, *people I can
  reach*, plus an optional *reasons I want to stay* that is **offered rather than assumed** (it's
  the heaviest thing on the screen to write, and an empty one sitting there permanently would read
  as a reproach on a bad day). Reading and editing are the same screen, because in a hard moment
  nobody should have to hunt for an edit button. The crisis row shows **your own** saved resource —
  it defaults to 988 and is editable, so it's a real number wherever you are — and it hands off to
  the crisis screen rather than dialing, since Daymark isn't a crisis service and never places a
  call for you. The footer says the limit out loud: **a plan is not a person — reaching one is the
  point.** Reachable from **More**, and as a quiet row in "Take a moment" **only once a plan
  exists** (a row leading to a blank page in a hard moment is exactly what the plan is meant to
  prevent). Deliberately **never** a suggestion card: nothing infers from your mood that you need
  your safety plan. Everything is local, offline, and included in backups.
  - Written in **our own words** and labelled **Adapted**, not the Stanley-Brown Safety Planning
    Intervention — that form requires written permission to program into an electronic record.
    There is deliberately **no means-restriction prompt**, which is also a house rule under
    `PROVENANCE.md`. You can write anything you like; the app just never asks.
- **Home — the daily loop**: Home is no longer the whole archive. It now opens with a greeting
  and the date, a **one-tap check-in row** ("How are you, right now?" — tap a face and the entry
  editor opens with that mood already picked), a small **glance** (current streak, plus the last
  seven days as seven bars where an unlogged day is a faint stub), at most **one** suggestion
  card, and **today's** entries. Two quiet links lead onward: **More for you** and **All
  entries**. Follows the locked concept in
  [docs/design/app-01-home-daily-loop.png](docs/design/app-01-home-daily-loop.png).
- **All entries**: the full day-grouped timeline that used to live on Home, now its own screen
  linked from Home — nothing was removed, it just stopped being the first thing you see.
- **For you**: the ranked Signals suggestions Home has no room for, plus the richer "on this day"
  memories card, on one screen ("Gentle suggestions — never nags"). Every card is still a fixed,
  rules-based template and every one can be dismissed.
- **Suggestion controls — opt-out, granular, and remembered**: every suggestion card now carries a
  menu — **not right now** (this visit only, nothing stored), **show less like this**, **remind me
  in a few hours**, **not helpful, hide it**, and **turn this suggestion off** — and every choice
  but the first is remembered across restarts. **Settings →
  Suggestions** lists every kind of suggestion under *On* and *Off*, says when a snoozed one comes
  back ("Snoozed · back in 2h") and offers to bring it back now. Turning one back on clears
  everything holding it back, so it can't be on and still invisible. Nothing is learned from what
  you tap: "show less" subtracts a fixed amount from that suggestion's rank and snoozes it for
  three days, and the rules are unit-tested. Two things are deliberately **not** controllable:
  Home's check-in row (that's how you log, not a nudge) and the "what might help" support menu (you
  open it on purpose). Crisis resources stay reachable whatever you set.

- **Companion — tool provenance labeling**: every questionnaire/tool now declares whether it is
  **Validated**, **Adapted**, or **Custom**, enforced by the instrument honesty gate (validated
  requires a source; adapted names the method it draws from; custom must open with a
  non-diagnostic disclaimer). The questionnaire runner shows the badge and, for a custom tool, its
  "not a validated or clinical instrument" disclaimer up front. The tier is also surfaced in the
  self-check list and the therapist's assign surface. See [docs/PROVENANCE.md](docs/PROVENANCE.md).
- **Companion — no-code tool builder**: author a questionnaire/reflection tool (items, provenance
  tier, descriptive bands) with a live honesty-gate panel and a live preview; reachable from the
  owner app's "Build a tool" tab, which exports the compiled tool as a validated instrument
  definition. Publishing to the catalog/assignment channel is a later slice.
- **Insights — what affects your mood**: on-device mood↔factor correlations for activities and
  numeric trackers, ranked "lifts you up / weighs you down" (with a minimum-sample gate);
  by-day-of-week and by-time-of-day mood patterns; and a this-period-vs-last comparison that
  follows the Week / Month / Year toggle. Every surface is labeled **association, not cause**, and
  it all computes locally with no schema change.
- **Insights — "In review" + consistency heatmap**: a short, rules-based recap (entries, average,
  best/worst weekday, top mood-lifting factor, current streak), also rendered in the PDF report;
  and a GitHub-style entries-per-day **logging-consistency heatmap**.
- **Check-ins (PHQ-9 / GAD-7 / WHO-5)**: three free, widely-used wellbeing self-checks with score
  history and a trend chart (**More → Check-ins**). **Strictly non-diagnostic** — only the score
  and band are stored, never the individual item answers (the PHQ-9 self-harm item never
  persists). If that item is non-zero, PHQ-9 surfaces the offline crisis flow — never a risk
  verdict. WHO-5 is shown as a 0–100 percentage. PHQ-9 and GAD-7 are **free to reproduce
  (Pfizer)**; WHO-5 is **© WHO, free for non-commercial use** (cited in-app). See
  [docs/INSTRUMENTS.md](docs/INSTRUMENTS.md).
- **Achievements**: gentle milestones for showing up — first entry, entry counts, longest streaks,
  activity variety, first check-in (**More → Achievements**), with original hand-drawn badge art.
  No streak-shaming; earned badges are sticky.
- **Breathing presets**: pick a pacer cadence — **slow ~6/min** (gentle default), **box 4·4·4·4**,
  or **4·7·8** — with proper hold phases and the existing in/out haptics. Described generically.
- **Journal writing templates**: starters on a fresh entry — **Three Good Things** (gratitude), a
  timed **Expressive Writing** prompt (with a gentle "this may surface hard feelings" note and a
  link to support), and a reflect-on-the-day prompt.
- **Do one thing (behavioral activation)**: plan a small pleasure/mastery activity, optionally set
  a reminder, then rate **enjoyment** and **mastery**, which log to auto-created 0–10 trackers so
  they show up against mood in Insights. Reuses trackers + reminders; framed as a skill, not
  treatment.
- **Implementation intentions**: goals can carry an optional "when X, I will Y" plan. Existing
  goals are unaffected.
- **Thought records (CBT)**: a guided record (**More → Thought records**) — situation → automatic
  thought → optional thinking-trap tags → evidence for/against → balanced thought, with mood
  before/after. The cognitive-distortion list is **self-authored** (our own names/definitions).
  Framed as reflection, not a verdict or diagnosis.
- **Move**: gentle yoga/stretch and bodyweight interval routines (**More → Move**) with
  **original hand-drawn pose figures** (drawn with the same Canvas primitives as the mood faces,
  zero image assets) and a haptic-cued timer that works eyes-closed. Sequences are described in
  our own words, with no branded programs; each session logs to an auto-created "Movement minutes"
  tracker so it shows up against mood in Insights. No video, no network.
- **Photo attachments**: optionally attach a photo to a mood entry via the Android Photo Picker
  (no storage permission). Photos are downscaled and stored app-private, shown as thumbnails on the
  Home timeline and Day Detail, and embedded (base64) in JSON backups so a backup stays a single
  portable file.
- **Swipe-to-delete with undo**: swipe a Home-timeline entry to delete it, with a 5-second **Undo**
  snackbar that restores the entry (and its activity links).
- **Multiple reminders**: replace the single daily reminder with a list, each reminder having its
  own time, on/off toggle, and optional label, managed under **Settings → Reminders**.
- **Notification quick-log**: tapping a reminder notification — or its **Log now** action — opens a
  fresh entry straight away.
- **Auto-lock timeout**: when the PIN lock is on, choose to re-lock immediately (default) or after
  1 / 5 / 15 minutes in the background.
- **Customize moods**: rename and recolor any of the five mood levels (**Settings → Customize
  moods**). The 1–5 level stays the stable key, so existing entries keep their place on the scale;
  custom names/colors flow through the timeline, calendar, insights, widget, and CSV export, and
  ride along in JSON backups.
- **Journal**: a separate free-form diary, distinct from per-entry mood notes.
- **Global note search**: search across mood notes and journal entries from one place.
- **Activity library**: browse 100+ ready-made activities by category and add the ones you use.
- **Insights tab**: a single screen merging the former Stats, Calendar, and Year-in-Pixels views,
  with a **Week / Month / Year** scale toggle. (Year in Pixels shows the whole year as a grid of
  mood-colored squares.)
- **Tap a day to view/edit** its entries from the calendar.
- **"On this day" memories**: gently resurface what you logged on this date in past months/years.
- **Goals**: weekly habit goals with progress, optionally linked to an activity.
- **Custom trackers**: track anything alongside mood as a scale, a number (with a unit), or a
  simple yes / no, with history.
- **Sleep suite (non-diagnostic)**: a manual sleep diary with derived metrics (time in bed,
  total sleep, sleep efficiency); sleep-setup calibration; license-clean, original self-checks
  for apnea-style signs, restless legs, and insomnia signs; treatments before/after comparison;
  a descriptive (never causal) sleep ↔ mood insight; and an **experimental** on-body breathing
  check that uses the accelerometer (no audio recorded, only the result shown).
- **Gentle support ("Take a moment")**: an opt-in, validate-first flow with a breathing pacer and
  offline crisis resources; nothing is sent anywhere.
- **CSV export** of mood entries; **merge** option when restoring a JSON backup.
- **Home-screen widget** (Glance) to quick-log a mood.
- **Export PDF report** — a printable report with a SHA-256 + QR authenticity stamp.
- **First-run onboarding wizard** (skippable): daily-reminder setup, optional PIN lock.

### Changed
- **Room schemas are now exported through Room's own Gradle plugin** (`room { schemaDirectory(…) }`)
  instead of the raw `ksp { arg("room.schemaLocation", …) }`, so the directory is a declared Gradle
  task input/output rather than a path the processor writes to blind. CI additionally fails if the
  exported schemas drift from what is committed — Room's own equality check compares only entities
  and views, so a wrong `identityHash` in a committed schema would otherwise go unnoticed. No
  dependency versions changed, and the existing flat schema layout is unchanged. Build-only; no
  behaviour change in the app.
- **Deleting an entry now asks first.** The swipe itself never deletes — a confirmation dialog
  does, with the 5-second **Undo** snackbar still behind it. A steady drag has to cross most of the
  row before the background turns from "Keep swiping" to "Release to delete", so a stray thumb
  costs a tap on "Keep it" rather than a day's log. The entry editor's delete button also confirms
  now (and says plainly that there's no undo behind that one).
- The **"on this day" memories** card and the extra suggestion cards moved off Home to the new
  **For you** screen, and the day-grouped timeline moved to **All entries** — Home was doing too
  many jobs at once.
- Renamed the app from "Daylie" to **Daymark** (package `com.daymark.app`).
- Adopted the "modern paper" design system: paper palette, serif/sans type, and original
  hand-drawn mood + activity icons (replacing emoji and Material icons).
- Consolidated navigation around the unified Insights tab.
- Snappier, directional navigation transitions; larger mood-picker tap targets.
- Database schema is now **v12** with Room migrations, adding `assessment_results` (v10, check-in
  scores), `cue`/`routine` on goals (v11, implementation intentions), and `thought_records` (v12,
  CBT). The backup format is now **v12**: check-in score history, achievement unlock times,
  goal cue/routine, and thought records all round-trip (replace **and** merge). Older backups
  still import, and an existing single reminder is migrated automatically on upgrade.
- The bundled wellbeing check-ins use the **exact wording** of PHQ-9, GAD-7 (free to reproduce,
  Pfizer) and WHO-5 (© WHO, free for non-commercial use, attributed in-app). No licensed
  instruments are bundled — see [docs/INSTRUMENTS.md](docs/INSTRUMENTS.md) for the full ledger.
- **No new permission** was added for any of these features — the app still has no `INTERNET`
  permission and makes no network connections.

### Security
- PIN moved to PBKDF2 (210k iterations, random salt) in AES-256 `EncryptedSharedPreferences`,
  with failed-attempt lockout/backoff; transparent upgrade from the old hash.
- Re-lock on background; `FLAG_SECURE` when locked; strong (Class 3) biometrics only.
- Hardened backup import (version gate, malformed-file guard).
- Release build: R8 minification, real release signing config, Gradle wrapper validation and
  Dependabot in CI.

## [0.1.0]

### Added
- Initial release: mood logging (5-level scale, activities, notes), calendar with mood tinting,
  statistics (trend, streaks, distribution, per-activity averages), daily reminder, PIN +
  biometric app lock, and JSON backup/restore.
