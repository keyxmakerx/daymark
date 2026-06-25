# Contributing to Daymark

Thanks for your interest in helping build a free, private mood tracker & journal! 🎉

## Getting started

1. Fork and clone the repo.
2. Open it in Android Studio, or build from the command line: `./gradlew assembleDebug`.
3. Run the unit tests: `./gradlew test`.

## Guidelines

- **Keep it local-first.** Daymark's core promise is that data stays on the device with no
  accounts or servers. Features that require a central backend won't be merged into core (an
  opt-in, self-hosted sync behind a separate build flavor could be discussed).
- **Keep dependencies FOSS.** We aim for an F-Droid release, so avoid proprietary libraries
  (e.g. Google Play Services, Firebase) and anything with trackers.
- **Clean-room rule.** Do **not** copy code, layouts, strings, icons, color values, or any other
  assets from Daylio or any other closed-source app. All UI and assets must be original or
  appropriately FOSS-licensed. Daymark is an independent project; only reference other apps
  nominatively (factual comparison), never by reusing their branding or assets.
- Match the existing code style (Kotlin official style, Compose + MVVM + repository).
- Put testable logic in plain Kotlin (see `stats/`) and add unit tests for it.
- Follow the locked design system in [docs/DESIGN.md](docs/DESIGN.md) for UI changes.
- One focused change per pull request; describe what and why; include screenshots for UI changes
  and update `CHANGELOG.md` under *Unreleased*.

## Developer Certificate of Origin

By contributing, you agree your contributions are licensed under **GPL-3.0-only** (inbound =
outbound). Sign off your commits (`git commit -s`) to certify the
[DCO](https://developercertificate.org/).

## Good first issues

- New activity icons (original vector drawables in `res/drawable/`, mapped in
  `ui/icon/ActivityIcons.kt`).
- Additional statistics (e.g. day-of-week or time-of-day mood averages).
- Translations (`res/values-<lang>/strings.xml`).
