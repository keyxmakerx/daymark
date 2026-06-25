# Daymark

**A free, open-source mood tracker & journal for Android. Private by design — everything stays on your device.**

Daymark lets you mark how each day feels: log your mood, tag what you did, and write about
*why* you feel that way. Keep a separate free-form journal, watch patterns emerge in the
calendar and stats, and set weekly goals — all stored **locally**, with no accounts, no servers,
and no tracking. Because there's no backend, the app is free for everyone, forever.

> **Status:** early development (v0.1). The core app is built and usable; see the roadmap.
> Daymark is an independent project and is **not affiliated with, or derived from, any other
> mood-tracking app.**

## Features

- 📝 **Mood logging** — quick entries on a 5-level scale (awful → rad) with activities/tags and
  a note for *why* you feel that way. Multiple entries per day.
- 📓 **Journal** — a separate, searchable free-form diary, distinct from your mood notes.
- 📅 **Calendar** — a month view where each day is tinted by its mood; jump to **Year in Pixels**
  to see the whole year at a glance.
- 📈 **Statistics** — mood trend, current & longest streaks, mood distribution, and average mood
  per activity.
- 🎯 **Goals** — weekly habit goals (e.g. "exercise 5× / week") with progress.
- 🔔 **Daily reminder** — an optional notification at a time you choose.
- 🔒 **App lock** — protect your data with a PIN (PBKDF2, encrypted at rest, with lockout) and
  optional strong biometrics; the app hides its contents from the recents thumbnail when locked.
- 💾 **Backup & export** — export/import everything as JSON (with replace **or** merge), or
  export your entries as CSV.
- 🧩 **Home-screen widget** — tap a mood to log it in one step.
- 🎨 **"Modern paper" design** — a warm, flat, paper-like Material 3 theme with hand-drawn icons,
  light & dark.

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
| Goals, year-in-pixels, stats | Included | Often premium |

## Privacy

Daymark has **no `INTERNET` permission** in its core. All entries, journal, activities, goals,
and settings live in a local SQLite database in the app's private storage; the PIN is stored in
an AES-256 encrypted preference store. The only way data leaves your device is if **you**
explicitly export a backup. See [PRIVACY.md](PRIVACY.md) for the full statement and the list of
permissions.

## Installing

Pre-built APKs are attached to each [GitHub Release](../../releases). Daymark is **not** on the
Play Store, so when you sideload it Android will show an "unknown app" / Play Protect warning —
that's expected for any app installed outside the Play Store. To reduce friction over time we
plan to ship **release-signed** APKs (stable signing identity), publish each APK's SHA-256 in the
release notes for verification, and submit to **F-Droid**.

## Building

```bash
./gradlew assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease # R8-minified release build (signed if a keystore is configured)
./gradlew test            # unit tests (stats, goal progress, CSV escaping)
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
├── notifications/ # Daily reminder scheduling
├── security/      # PIN (PBKDF2 + EncryptedSharedPreferences) + biometric lock
├── backup/        # JSON / CSV export & import
├── widget/        # Glance home-screen widget
└── ui/            # Compose screens + ViewModels, theme, components, icons
```

More detail in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and the design system in
[docs/DESIGN.md](docs/DESIGN.md).

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md). Highlights: first-run onboarding wizard, customizable
mood scale & colors, encrypted backups / database encryption, accessibility & localization
passes, and an F-Droid release.

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) and our
[Code of Conduct](CODE_OF_CONDUCT.md). Please read the clean-room rule before contributing UI or
assets.

## License

Licensed under the **GNU General Public License v3.0 only** — see [LICENSE](LICENSE). All icons
and illustrations are original works created for Daymark.
