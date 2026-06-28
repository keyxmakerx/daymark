# Features

A fuller tour of what Daymark does today. Everything here is **100% local** — no accounts, no
network, nothing leaves your device. See [PRIVACY.md](PRIVACY.md) for the privacy posture.

> **Not a medical device.** The sleep, self-check, breathing, and support features are
> **non-diagnostic** wellbeing aids. They do not detect, diagnose, or treat any condition, and
> the on-body breathing check is **experimental**. Nothing here replaces professional advice.

## Mood & journal

- **Mood logging** — entries on a 5-level scale (awful → rad), with activities/tags and a note
  for *why*. Multiple entries per day.
- **Photo attachments** — optionally attach a photo to a mood entry via the Android Photo Picker
  (which needs **no** storage permission). The image is downscaled and copied into the app's
  private storage, shown as a thumbnail on the Home timeline and Day Detail, and embedded in JSON
  backups so the backup stays one portable file.
- **Swipe to delete + undo** — swipe a Home-timeline entry away to delete it, with a 5-second
  **Undo** snackbar that restores the entry (and its activity links).
- **Journal** — a separate free-form diary, distinct from per-entry mood notes.
- **Journal writing templates** — optional starters on a fresh entry: **Three Good Things**
  (gratitude), a timed **Expressive Writing** prompt, and a reflect-on-the-day prompt — all
  evidence-informed methods worded in our own words. The expressive-writing starter shows a gentle
  "this may surface hard feelings" note with a link to support.
- **Global note search** — search across mood notes and journal entries from one place.
- **Tap a day to view/edit** — open any calendar day to review or edit its entries.
- **"On this day" memories** — gently resurfaces what you logged on this date in past months and
  years.

## Activity library

- Browse **100+ ready-made activities** organized by category (exercise, food, social, health,
  hobbies, chores, and more) and add the ones you use.
- Rename, reorder, and create your own activities.

## Insights

- A single **Insights** tab that merges the former Stats, Calendar, and Year-in-Pixels screens,
  with a **Week / Month / Year** scale toggle:
  - **Stats** — mood trend, current & longest streaks, mood distribution, average mood per
    activity.
  - **Month** — a calendar grid where each day is tinted by its mood.
  - **Year** — the whole year as a grid of mood-colored squares (Year in Pixels).
- **What goes with your mood** — per-factor correlations between your mood and the activities and
  numeric trackers you log, ranked into "lifts you up / weighs you down". Computed **on-device**
  from data you already have, with a minimum-sample gate (≥5 occurrences for an activity, ≥14 days
  for a tracker) to keep noise out. Always labeled **association, not cause** — never causation.
- **By day-of-week & by time-of-day** — your average mood across weekdays and across morning /
  afternoon / evening / night buckets.
- **This period vs. last** — a comparison of the current period against the previous one, which
  follows the Week / Month / Year toggle.
- **"In review"** — a short, rules-based recap (entries, average, best/worst weekday, top
  mood-lifting factor, current streak), worded as association rather than cause. The same summary
  is rendered as an "In review" section in the PDF report.
- **Logging consistency** — a GitHub-style entries-per-day heatmap (a single accent hue, distinct
  from the mood-tinted Year in Pixels) showing how consistently you've been checking in.

## Goals & custom trackers

- **Goals / habits** — weekly habit goals (e.g. "exercise 5× / week") with progress, optionally
  linked to an activity.
- **Custom trackers** — track anything alongside mood as a **scale**, a **number** (with a unit),
  or a simple **yes / no**, and review its history over time.
- **Implementation intentions** — give any goal an optional **"when [cue], I will [routine]"**
  plan, a simple, well-evidenced way to turn an intention into action. Existing goals are
  unaffected.

## Wellbeing skills (non-diagnostic)

Optional, self-help skills reached from the **More** screen. None of these is treatment or a
diagnosis, and nothing leaves your device.

- **Check-ins (PHQ-9 / GAD-7 / WHO-5)** — three free, widely-used wellbeing self-checks, with
  score history and a small trend chart (**More → Check-ins**). Each is **strictly
  non-diagnostic**. Only the **score and band** are stored — never the individual item answers
  (the PHQ-9 self-harm item never persists). If the PHQ-9 self-harm item is non-zero, the app
  gently surfaces the offline crisis flow — never a risk verdict. WHO-5 is shown as a 0–100
  percentage. PHQ-9 and GAD-7 are **free to reproduce (Pfizer)**; WHO-5 is **© WHO, free for
  non-commercial use** (cited in-app). See [INSTRUMENTS.md](INSTRUMENTS.md) for the license
  ledger.
- **Achievements** — gentle milestones for showing up: first entry, entry counts, longest
  streaks, activity variety, first check-in (**More → Achievements**), shown with original
  hand-drawn badge art. Gentle by design — no streak-shaming, and earned badges are sticky.
- **Thought records (CBT)** — a guided record (**More → Thought records**): situation → automatic
  thought → optional **thinking-trap** tags → evidence for/against → a **balanced thought**, with
  **mood before/after** to gauge any shift. The cognitive-distortion list is **self-authored** (our
  own names and definitions). Framed as reflection, not a verdict or diagnosis.
- **Do one thing (behavioral activation)** — plan a small pleasure/mastery activity (from
  self-authored suggestions or your own), optionally set a reminder to do it, then rate
  **enjoyment** and a sense of accomplishment (**mastery**). Both log to auto-created 0–10
  trackers, so they show up against your mood in Insights. Framed as a skill, not treatment.
- **Move** — gentle yoga/stretch and bodyweight interval routines (**More → Move**) with
  **original hand-drawn pose figures** and a haptic-cued timer (a pulse on each step, a double
  pulse to finish) so it works even with your eyes closed. The sequences are described in our own
  words, with no branded programs and no video; each session logs to an auto-created "Movement
  minutes" tracker so it shows up against mood in Insights.

## Sleep (non-diagnostic)

- **Sleep diary** — log nights manually with bedtime, wake time, sleep latency, and time awake;
  Daymark derives time in bed, total sleep time, and sleep efficiency.
- **Sleep setup** — a short calibration to tailor the sleep section to your routine.
- **Self-checks** — license-clean, original questionnaires for apnea-style signs, restless legs,
  and insomnia signs. Every result is framed as "worth raising with a clinician" — never a
  diagnosis.
- **Treatments before / after** — record a treatment or change and compare how things look around
  it.
- **Sleep ↔ mood insight** — a descriptive, never-causal observation, e.g. "on nights you slept
  better, your mood tended to be higher."
- **On-body breathing check (experimental)** — rest the phone on your chest; the
  **accelerometer** estimates your breathing rhythm and flags pauses. **No audio is recorded** —
  only the derived result is shown. An experimental wellbeing aid, **not** an apnea test.

## Gentle support

- **"Take a moment"** — an opt-in, validate-first flow for hard moments, with a guided
  **breathing pacer** and **offline crisis resources**. No content is sent anywhere.
- **Breathing presets** — choose the pacer cadence: **slow ~6/min** (gentle default), **box
  4·4·4·4**, or **4·7·8**, with proper hold phases and in/out haptics. Described generically — no
  brand names, no health claims.

## Everyday

- **Reminders + quick-log** — set up multiple daily reminders, each with its own time, on/off
  toggle, and optional label, managed under **Settings → Reminders**. Tapping a reminder
  notification (or its **Log now** action) opens a fresh entry straight away.
- **App lock** — a PIN (PBKDF2, encrypted at rest, with failed-attempt lockout) plus optional
  strong (Class 3) biometrics; contents are hidden from the recents thumbnail when locked. An
  **auto-lock timeout** lets you re-lock immediately (default) or after 1 / 5 / 15 minutes in the
  background.
- **Backup & export** — JSON export/import (replace **or** merge, with entry photos embedded as
  base64 in a single portable file), CSV export of entries, and a printable PDF report.
- **Home-screen widget** — tap a mood to log it in one step.
- **Customize moods** — rename and recolor any of the five mood levels (Settings → Customize
  moods). The 1–5 level stays the stable key, so existing entries keep their place; custom
  names/colors appear everywhere (timeline, calendar, insights, widget, CSV) and ride along in
  backups.
- **Modern paper design** — a warm, flat, paper-like Material 3 theme with original hand-drawn
  icons, in light & dark.
