# Daylie

**A free, open-source mood tracker & micro-journal for Android — a privacy-first alternative to Daylio.**

Daylie lets you log how you feel, tag what you did, and see patterns over time.
Everything is stored **locally on your device** — there are no accounts, no servers, and no
tracking. Because there's no backend, the app is free for everyone, forever.

> Status: early development (v0.1). Core features are implemented; see the roadmap below.

## Features

- 📝 **Mood logging** — quick entries on a 5-level scale (awful → rad) with customizable
  activities/tags and a free-text note.
- 📅 **Calendar** — a month view where each day is tinted by its average mood.
- 📈 **Statistics** — mood trend over the last 30 days, current & longest streaks, mood
  distribution, and average mood per activity.
- 🔔 **Daily reminder** — an optional notification at a time you choose.
- 🔒 **App lock** — protect your journal with a PIN and (optionally) biometrics.
- 💾 **Backup & restore** — export/import all your data as a JSON file. Your data is yours.

## Tech stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Room** for local storage · **Hilt** for dependency injection
- **Coroutines / Flow** for reactive state
- `minSdk 26`, single-Activity architecture (MVVM + repository)

## Building

```bash
./gradlew assembleDebug      # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew test               # runs unit tests (statistics logic)
```

You need JDK 17+ and the Android SDK (compileSdk 35). Android Studio is recommended.

## Project layout

```
app/src/main/java/com/daylie/app/
├── data/          # Room entities, DAOs, repositories, database
├── model/         # Mood scale
├── stats/         # Pure, unit-tested statistics functions
├── notifications/ # Daily reminder scheduling
├── security/      # PIN + biometric app lock
├── backup/        # JSON export / import
└── ui/            # Compose screens + ViewModels (home, entry, calendar, stats, settings…)
```

## Roadmap

- [ ] User-customizable mood scale & colors, custom activity icons
- [ ] Optional self-hosted / cloud sync (Google Drive, Nextcloud) — still no central server
- [ ] Home-screen widget, photo attachments
- [ ] F-Droid release (all dependencies are already FOSS-compatible)

## Privacy

Daylie requests no internet permission for its core functionality. All entries, activities,
and settings live in a local SQLite database in the app's private storage. The only way data
leaves the device is if **you** explicitly export a backup file.

## License

Licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE).
Contributions are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md).
