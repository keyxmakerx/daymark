# Privacy

Daymark is built so that **your data never leaves your device** unless you explicitly export it.

## What we collect

Nothing. Daymark has **no analytics, no crash reporting, no advertising, and no third-party
trackers**. There are no accounts and no servers operated by this project.

## Network

The app declares **no `INTERNET` permission** and makes no network connections. Nothing you log —
moods, journal entries, sleep logs, trackers, self-check answers — is ever sent anywhere.

## Where your data lives

- Mood entries, activities, journal entries, goals, custom trackers, sleep logs, treatments,
  self-check results, and settings are stored in a local **SQLite (Room) database** in the app's
  private storage (`/data/data/com.daymark.app/`), which the Android sandbox isolates from other
  apps.
- This database is **not separately encrypted by the app today.** It relies on the Android app
  sandbox and the device's own file-based encryption. Database encryption (via a Keystore-wrapped
  key) is on the roadmap.
- Your **PIN** (if you set one) is the exception: it is stored only as a PBKDF2 hash with a random
  salt inside an **AES-256 `EncryptedSharedPreferences`** store — never in plaintext.
- **Photos** you attach to mood entries are copied (downscaled) into the same app-private storage
  and never leave your device. Daymark uses the **Android Photo Picker**, which lets you pick a
  single image **without granting any storage or media permission** — so adding photos requires no
  new permission. Attached photos *are* included in the JSON backups you create (embedded as
  base64), which, like all backups, are **unencrypted**.
- `android:allowBackup="false"` is set, so the OS won't copy your data into cloud/adb backups.

## Permissions and why

Daymark requests only these permissions, all for on-device features:

| Permission | Why |
|---|---|
| `POST_NOTIFICATIONS` | Show your optional daily reminders (Android 13+). |
| `RECEIVE_BOOT_COMPLETED` | Re-arm your reminders after the device restarts. |
| `USE_BIOMETRIC` | Optional biometric unlock for the app lock. |
| `SCHEDULE_EXACT_ALARM` | Deliver each reminder at the exact time you chose (falls back to inexact if unavailable). |
| `VIBRATE` | Provide haptic feedback, e.g. for the breathing pacer and reminders. |

There is no `INTERNET`, `RECORD_AUDIO`, location, or contacts permission. Recent features —
photo attachments (via the Android Photo Picker) and multiple reminders — add **no new
permission**; the reminder permissions above were already declared, and the Photo Picker needs
none.

## The breathing check

The **experimental** on-body breathing check uses the device **accelerometer** while the phone
rests on your chest. **No audio is recorded** and no raw sensor stream is stored — only the
derived breathing result is shown to you, on-device. It is a non-diagnostic wellbeing aid and is
not an apnea test.

## Backups & exports

JSON backups, CSV exports, and PDF reports are **plaintext** files written to a location **you**
choose via the system file picker. A JSON backup also embeds any **photos** you attached to
entries (base64-encoded, so the backup stays a single portable file). Once exported, a file is
outside Daymark's protection — store it somewhere safe and treat it as sensitive. Encrypted export
is on the roadmap.

## Not a medical device

Daymark is a self-tracking tool. Its sleep, self-check, breathing, and support features are
**non-diagnostic** and do not detect, diagnose, or treat any condition. Nothing in the app
replaces professional advice.

## Contact

For privacy questions or security reports, see [../SECURITY.md](../SECURITY.md).
