# Privacy

Daymark is built so that **your data never leaves your device** unless you explicitly export it.

## What we collect

Nothing. Daymark has **no analytics, no crash reporting, no advertising, and no third-party
trackers**. There are no accounts and no servers operated by this project.

## Where your data lives

- Mood entries, activities, journal entries, goals, and settings are stored in a local **SQLite
  database** in the app's private storage (`/data/data/com.daymark.app/`), which the Android
  sandbox isolates from other apps.
- Your **PIN** (if you set one) is stored only as a PBKDF2 hash with a random salt inside an
  **AES-256 `EncryptedSharedPreferences`** store — never in plaintext.
- `android:allowBackup="false"` is set, so the OS won't copy your data into cloud/adb backups.

## Network

The core app declares **no `INTERNET` permission** and makes no network connections.

## Permissions and why

| Permission | Why |
|---|---|
| `POST_NOTIFICATIONS` | Show the optional daily reminder (Android 13+). |
| `RECEIVE_BOOT_COMPLETED` | Re-arm your reminder after the device restarts. |
| `USE_BIOMETRIC` | Optional biometric unlock for the app lock. |
| `SCHEDULE_EXACT_ALARM` | Deliver the daily reminder at the exact time you chose (falls back to inexact if unavailable). |

## Backups & exports

Backups (JSON) and CSV exports are **plaintext** files written to a location **you** choose via
the system file picker. Once exported, a file is outside Daymark's protection — store it
somewhere safe and treat it as sensitive. Encrypted export is on the roadmap.

## Known limitations (being transparent)

- The on-device database is **not** separately encrypted by the app today; it relies on the
  Android app sandbox and the device's own file-based encryption. Database encryption (via a
  Keystore-wrapped key) is on the roadmap.

## Contact

For privacy questions or security reports, see [SECURITY.md](SECURITY.md).
