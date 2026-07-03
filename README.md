# Daymark

**A free, open-source mood, journal & sleep tracker for Android. Private by design — everything stays on your device.**

Daymark lets you mark how each day feels: log your mood, tag what you did, and write about
*why* you feel that way. Keep a separate free-form journal, watch patterns emerge in the
Insights tab, track sleep, and set goals — all stored **locally**, with no accounts, no servers,
and no tracking. Because there's no backend, the app is free for everyone, forever.

> **Status:** early development (v0.1). The core app is built and usable; see the roadmap.
> Daymark is an independent project and is **not affiliated with, or derived from, any other
> mood-tracking app.**

> **Not a medical device.** Daymark is a self-tracking and journaling tool. Its sleep and
> breathing features are **non-diagnostic** wellbeing aids — they do **not** detect, diagnose,
> or treat sleep apnea, insomnia, depression, or any other condition. The on-body breathing
> check is **experimental**. Nothing in the app replaces professional advice; if you have
> concerns about your sleep, mood, or health, please talk to a clinician.

## Features

### Mood & journal
- 📝 **Mood logging** — quick entries on a 5-level scale (awful → rad) with activities/tags and
  a note for *why* you feel that way. Multiple entries per day.
- 📷 **Photo attachments** — optionally attach a photo to a mood entry via the Android Photo
  Picker (needs **no** storage permission); it's downscaled, stored app-private, shown as a
  thumbnail on Home and Day Detail, and included in JSON backups.
- ↔️ **Swipe to delete + undo** — swipe a Home-timeline entry away to delete it, with a
  5-second **Undo** snackbar.
- 📓 **Journal** — a separate free-form diary, distinct from your mood notes.
- 🔍 **Global note search** — search across mood notes and journal entries from one place.
- 🗓️ **Tap a day to view/edit** — open any day from the calendar to review or change its entries.
- 💭 **"On this day" memories** — gently resurface what you wrote on this date in past months
  and years.
- 🧰 **Activity library** — browse 100+ ready-made activities by category and add the ones you
  use; rename, reorder, and create your own.

### Insights
- 📊 **Dynamic Insights tab** — one screen that merges statistics, the mood calendar, and
  Year in Pixels, with a **Week / Month / Year** toggle. See your mood trend, current & longest
  streaks, mood distribution, and average mood per activity, plus the month grid tinted by mood
  and the whole year at a glance.
- 🔗 **What goes with your mood** — on-device correlations between your mood and the activities
  and trackers you log, ranked into "lifts you up / weighs you down". Computed locally with a
  minimum-sample gate, and always labeled **association, not cause**.
- 📅 **By day-of-week & time-of-day** — see your average mood across weekdays and across morning /
  afternoon / evening / night.
- ↔️ **This period vs. last** — a simple comparison that follows the Week / Month / Year toggle.
- 📰 **"In review" summary** — a short, rules-based recap (entries, average, best/worst weekday,
  top mood-lifting factor, current streak), also rendered in the PDF report. Worded as
  association, never cause.
- 🔥 **Logging-consistency heatmap** — a GitHub-style entries-per-day grid showing how
  consistently you've checked in.

### Tracking
- 🎯 **Goals & habits** — weekly habit goals (e.g. "exercise 5× / week") with progress,
  optionally linked to an activity.
- 🎚️ **Custom trackers** — track anything alongside mood as a **scale**, a **number** (with a
  unit), or a simple **yes / no**, and review its history.

### Sleep (non-diagnostic)
- 😴 **Sleep diary** — log nights manually with bedtime, wake time, latency, and time awake;
  Daymark derives time in bed, total sleep, and sleep efficiency.
- 🧭 **Sleep setup** — a short calibration to tailor the sleep section to your routine.
- ✅ **Self-checks** — license-clean, original questionnaires for apnea-style signs, restless
  legs, and insomnia signs. Every result is framed as "worth raising with a clinician" — never
  a diagnosis.
- 💊 **Treatments before / after** — record a treatment or change and compare how things look
  around it.
- 🔗 **Sleep ↔ mood insight** — a descriptive, never-causal observation (e.g. "on nights you
  slept better, your mood tended to be higher").
- 🫁 **On-body breathing check (experimental)** — rests the phone on your chest and uses the
  **accelerometer** to estimate your breathing rhythm and flag pauses. **No audio is recorded**;
  only the derived result is shown. This is an experimental wellbeing aid, **not** an apnea test.

### Wellbeing skills (non-diagnostic)
- 🧮 **Check-ins** — three free, widely-used wellbeing self-checks — **PHQ-9**, **GAD-7**, and
  **WHO-5** — with score history and a trend chart (**More → Check-ins**). Strictly
  **non-diagnostic**; only the score and band are stored (never the individual item answers), and
  PHQ-9 surfaces the offline crisis flow if the self-harm item is non-zero — never a verdict.
  PHQ-9/GAD-7 are free to reproduce (Pfizer); WHO-5 is © WHO, free for non-commercial use. See
  [docs/INSTRUMENTS.md](docs/INSTRUMENTS.md).
- 🏅 **Achievements** — gentle milestones for showing up (first entry, entry counts, longest
  streaks, activity variety, first check-in), **More → Achievements**. No streak-shaming; earned
  badges are sticky.
- 🧠 **Thought records (CBT)** — a guided record: situation → automatic thought → optional
  thinking-trap tags → evidence for/against → a balanced thought, with mood before/after. Framed
  as reflection, not a verdict (**More → Thought records**).
- 🌱 **Do one thing (behavioral activation)** — plan a small pleasure/mastery activity, optionally
  set a reminder, then rate **enjoyment** and **mastery** (logged to auto-created trackers so they
  show up against mood in Insights).
- 📔 **Journal writing templates** — starters on a fresh entry: **Three Good Things** (gratitude),
  a timed **Expressive Writing** prompt (with a gentle "this may surface hard feelings" note and a
  link to support), and a reflect-on-the-day prompt.
- 🤸 **Move** — gentle yoga/stretch and bodyweight interval routines with **original hand-drawn
  pose figures** and haptic-cued timers that work eyes-closed; each session logs to a "Movement
  minutes" tracker. No video, no network (**More → Move**).

### Goals & intentions
- 🪄 **Implementation intentions** — give any goal an optional "when X, I will Y" plan, a simple,
  well-evidenced way to turn an intention into action.

### Gentle support
- 🌿 **"Take a moment"** — an opt-in, validate-first support flow for hard moments, with a
  guided **breathing pacer** and **offline crisis resources**. No content is sent anywhere.
- 🫁 **Breathing presets** — choose a cadence for the pacer: **slow ~6/min** (gentle default), **box
  4·4·4·4**, or **4·7·8**, with proper hold phases and in/out haptics. Described generically — no
  brand names, no health claims.

### Everyday
- 🔔 **Reminders + quick-log** — set up multiple daily reminders, each with its own time, on/off
  toggle, and optional label. Tapping a reminder notification (or its **Log now** action) opens a
  fresh entry straight away.
- 🔒 **App lock** — protect your data with a PIN (PBKDF2, encrypted at rest, with lockout) and
  optional strong biometrics; the app hides its contents from the recents thumbnail when locked.
  Choose an **auto-lock timeout** — re-lock immediately (default) or after 1 / 5 / 15 minutes in
  the background.
- 💾 **Backup & export** — export/import everything (entry photos embedded as base64 in a single
  portable file) as JSON (with replace **or** merge), export entries as CSV, or generate a
  printable PDF report.
- 🧩 **Home-screen widget** — tap a mood to log it in one step.
- 🎨 **"Modern paper" design** — a warm, flat, paper-like Material 3 theme with hand-drawn icons,
  light & dark.

A fuller breakdown lives in [docs/FEATURES.md](docs/FEATURES.md).

## Free & private vs. paid mood trackers

Daymark is a clean-room, independent app. It deliberately covers features that comparable apps
often gate behind a subscription, with a hard privacy stance:

| | Daymark | Typical paid tracker |
|---|---|---|
| Price | Free, no ads, no IAP | Freemium / subscription |
| Data location | 100% on-device | Often cloud accounts |
| Account required | No | Often yes |
| Trackers / analytics | None | Common |
| Open source | Yes (GPL-3.0) | Usually no |
| Goals, year-in-pixels, custom trackers | Included | Often premium |

## Privacy

Daymark has **no `INTERNET` permission** and makes no network connections — nothing leaves your
device. All entries, journal, activities, goals, trackers, sleep logs, and settings live in a
local SQLite (Room) database in the app's private storage. That database is **not separately
encrypted** today (it relies on the Android sandbox and the device's own encryption); the **PIN
hash is stored in an AES-256 encrypted preference store**. The only way data leaves your device
is if **you** explicitly export a backup. The experimental breathing check uses the
accelerometer only and **records no audio**. See [docs/PRIVACY.md](docs/PRIVACY.md) for the full
statement and a table explaining every permission.

## Installing

Pre-built APKs are attached to each [GitHub Release](../../releases). Daymark is **not** on the
Play Store, so when you sideload it Android will show an "unknown app" / Play Protect warning —
that's expected for any app installed outside the Play Store. To reduce friction over time we
plan to ship **release-signed** APKs (stable signing identity), publish each APK's SHA-256 in the
release notes for verification, and submit to **F-Droid**.

## Building

```bash
./gradlew assembleDebug   # -> app/build/outputs/apk/foss/debug/app-foss-debug.apk
                          #    (+ a "sync" flavor APK — see docs/COMPANION_PHONE_2B.md)
./gradlew assembleRelease # R8-minified release build (signed if a keystore is configured)
./gradlew test            # unit tests (stats, goal progress, sleep metrics, CSV escaping)
```

Requires JDK 17 and the Android SDK (compileSdk 35). Android Studio is recommended. Release
signing reads from a git-ignored `keystore.properties` or CI secrets; without one, the release
build falls back to debug signing so it still builds.

## Project layout

```
app/src/main/java/com/daymark/app/
├── data/          # Room entities, DAOs, repositories, database (+ migrations)
├── model/         # Mood scale
├── stats/         # Pure, unit-tested logic (statistics, goal progress)
├── sensing/       # Pure breathing-signal analysis (no Android dependencies)
├── notifications/ # Daily reminder scheduling
├── security/      # PIN (PBKDF2 + EncryptedSharedPreferences) + biometric lock
├── backup/        # JSON / CSV export & import
├── export/        # PDF report generation
├── widget/        # Glance home-screen widget
└── ui/            # Compose screens + ViewModels (mood, journal, insights, sleep,
                   #   trackers, support, activities, search, …), theme, components, icons
```

More detail in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and the design system in
[docs/DESIGN.md](docs/DESIGN.md).

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md). Highlights: customizable mood scale & colors, encrypted
backups / database encryption, accessibility & localization passes, and an F-Droid release.

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) and our
[Code of Conduct](CODE_OF_CONDUCT.md). Please read the clean-room rule before contributing UI or
assets.

## License

Licensed under the **GNU General Public License v3.0 only** — see [LICENSE](LICENSE). All icons
and illustrations are original works created for Daymark.
