# Daymark

**A free, open-source mood journal for Android. Private by design: everything stays on your device.**

Daymark lets you mark how each day feels. You log a mood, tag what you did, and write about *why*. It
also keeps a free-form journal, shows your history as a sky of stars and as plain statistics, tracks
sleep, and holds your goals and your own safety plan. It is stored locally and encrypted, with no
accounts, no servers and no tracking. Because there is no backend, it is free for everyone.

> **Status.** The Android app is at version 0.3.0: built, usable and sideloadable. The optional,
> self-hosted **Companion** is built too: a server plus web consoles that let a person share a chosen,
> encrypted view with a clinician. The phone's half of the Companion is not built yet, so today the
> owner uses the Companion from a browser. Everything open is tracked on GitHub; start at the
> [roadmap](https://github.com/keyxmakerx/daymark/issues/132).

> **Not a medical device.** Daymark is a self-tracking and journaling tool. Its sleep, breathing,
> self-check and support features are **non-diagnostic**. They do not detect, diagnose or treat any
> condition, and nothing in the app replaces professional advice.

Daymark is an independent project. It is **not affiliated with, or derived from, any other
mood-tracking app.**

## What it does

- **Mood and journal.** Quick entries on a five-level scale, with activities, people, a photo and a
  note. There is a separate free-form journal, search for both, and "on this day".
- **Your sky.** Each check-in, journal entry, thought record, project step, reached goal and life
  event you mark becomes a star in a sky that fills as you go. It never marks a missing day as a
  failure. See [docs/SKY.md](docs/SKY.md).
- **Insights.** Mood over time, by weekday and time of day, what goes with your mood (always
  "association, not cause"), a calendar, and a PDF report you can choose to give a clinician.
- **Goals and projects.** Weekly habits, and projects made of concrete steps. There are no streaks,
  scores or badges: continuity is shown as "12 of the last 30 days", with nothing to break.
- **Sleep, skills and support.** A sleep diary and non-diagnostic self-checks, the PHQ-9, GAD-7 and
  WHO-5 check-ins (scores only), thought records, behavioural activation, movement routines and a
  breathing pacer. "Take a moment" helps in a hard moment, alongside your own safety plan and
  offline crisis resources you can edit.
- **Everyday.** Reminders, a home-screen widget, an app lock (PIN and biometrics), and JSON, CSV and
  PDF export.

The full list, with the rules each feature keeps, is [docs/FEATURES.md](docs/FEATURES.md). How to
use each screen is in [docs/USER_GUIDE.md](docs/USER_GUIDE.md).

## Privacy

The released app has **no `INTERNET` permission** and makes no network connections. Your entries,
journal and everything else you record live in a local database that the app **encrypts for
everybody from the first run**, with the key held by the phone's hardware keystore. A few settings
and the latest sleep self-check results sit in a separate preferences file that the app does not yet
encrypt. The PIN hash sits in an encrypted preference store. Data leaves your
device only when **you** export it; exports are plain files. The full statement, with a table of
every permission, is [PRIVACY.md](PRIVACY.md).

## The Companion (optional, self-hosted)

The Companion is a small server (Docker) that a person or an office runs for itself; there is no
hosted Daymark service (#288). It comes with web consoles for the owner, a clinician and an
administrator. Everything stored on it is encrypted end to end, so the server never sees content.
The owner pairs with a clinician using a code read aloud once. The owner decides what is shared and
for how long, and can withdraw it. Operator guide:
[companion/README.md](companion/README.md). Design: [docs/COMPANION_ARCHITECTURE.md](docs/COMPANION_ARCHITECTURE.md).

## Installing

Pre-built APKs are attached to each [GitHub release](https://github.com/keyxmakerx/daymark/releases).
Daymark is not on the Play Store, so Android shows an "unknown app" warning when you sideload it.
That is expected for any app installed that way. Published checksums and an F-Droid listing are
tracked in [#137](https://github.com/keyxmakerx/daymark/issues/137).

## Building

```bash
./gradlew assembleDebug    # the foss APK (plus the opt-in sync flavour)
./gradlew test             # the app's unit tests
cd companion/web && pnpm install && pnpm test && pnpm check && pnpm build
cd companion/server && ./gradlew test
```

The app needs JDK 17 and the Android SDK (compileSdk 36); the shared `:sync-crypto` module builds on
JDK 21. How the pieces fit together is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), and how the
toolchain is pinned, and why, is in [docs/TOOLCHAIN.md](docs/TOOLCHAIN.md).

## Where things live

- **Work to do, bugs and open decisions:** [GitHub issues](https://github.com/keyxmakerx/daymark/issues),
  organised under the [roadmap](https://github.com/keyxmakerx/daymark/issues/132).
- **How the system works today:** the documents indexed in [docs/README.md](docs/README.md).
- **Why things are the way they are:** [docs/DECISIONS.md](docs/DECISIONS.md).
- **What changed:** [CHANGELOG.md](CHANGELOG.md).

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) and the
[code of conduct](CODE_OF_CONDUCT.md). Please read the clean-room rule before contributing UI or
assets. Security issues go through [SECURITY.md](SECURITY.md), not a public issue.

## License

**GNU General Public License v3.0 only.** See [LICENSE](LICENSE). All icons and illustrations are
original works created for Daymark.
