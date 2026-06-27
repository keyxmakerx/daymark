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

## Goals & custom trackers

- **Goals / habits** — weekly habit goals (e.g. "exercise 5× / week") with progress, optionally
  linked to an activity.
- **Custom trackers** — track anything alongside mood as a **scale**, a **number** (with a unit),
  or a simple **yes / no**, and review its history over time.

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
