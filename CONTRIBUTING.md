# Contributing to Daylie

Thanks for your interest in helping build a free, private mood tracker! 🎉

## Getting started

1. Fork and clone the repo.
2. Open it in Android Studio (or build from the command line with `./gradlew assembleDebug`).
3. Run the unit tests with `./gradlew test`.

## Guidelines

- **Keep it local-first.** Daylie's core promise is that data stays on the device with no
  accounts or servers. Features that require a central backend won't be merged into core.
- **Keep dependencies FOSS.** We aim for an F-Droid release, so avoid proprietary libraries
  (e.g. Google Play Services) in the main flavor.
- Match the existing code style (Kotlin official style, Compose + MVVM).
- Put testable logic in plain Kotlin (see `stats/`) and add unit tests for it.
- One focused change per pull request; describe what and why.

## Good first issues

- New activity icons in `ui/icon/ActivityIcons.kt`
- Additional statistics (e.g. day-of-week mood averages)
- Translations (`res/values-<lang>/strings.xml`)

By contributing, you agree that your contributions are licensed under the GPLv3.
