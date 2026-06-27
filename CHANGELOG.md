# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
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
