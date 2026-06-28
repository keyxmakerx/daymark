# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
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
