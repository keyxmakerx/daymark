# Privacy

Daymark is built so that **your data never leaves your device** unless you export it yourself.

This describes the `foss` build of the Android app, which is the only build released. A separate,
opt-in `sync` build can share data with a self-hosted Companion server. It is unreleased and has its
own application id, so updating the `foss` build never installs it. It will get its own privacy
statement before it is released (#{F11}).

## What we collect

Nothing. Daymark has **no analytics, no crash reporting, no advertising and no third-party
trackers**. There are no accounts, and the `foss` build talks to no servers.

## Network

The `foss` build declares **no `INTERNET` permission** and makes no network connections. Nothing you
log is ever sent anywhere by it.

## Where your data lives

- Everything you record is stored in a local SQLite (Room) database in the app's private storage,
  which the Android sandbox isolates from other apps. That covers:
  - mood entries and activities, and journal entries;
  - goals, projects and their steps;
  - custom trackers, sleep logs and treatments;
  - self-check results, wellbeing check-in scores, thought records, and movement and
    behavioural-activation logs;
  - your safety plan and life events;
  - the people and communities you name, and your notes about them;
  - your settings.
- **Other people's names.** People and communities you add are your own notes about them. They stay
  on the device like everything else, and sharing them is off by default.
- **Check-ins store scores only.** For the PHQ-9, GAD-7 and WHO-5 check-ins only the **score and
  band** are saved, never your individual answers. The PHQ-9 self-harm item is never stored. These
  self-checks are **non-diagnostic**, and the app makes no risk verdict about you.
- **The app keeps a record of when it asked you something and how you answered.** This includes
  reminders and the support offer. The record is used only on the device, to make the app ask
  *less*, never more. It is not included in backups and is never shared.
- **The database is encrypted by the app, for everybody, from the first run.** A random 32-byte key
  encrypts the whole file (SQLCipher, behind Room). You do not turn this on and there is nothing to
  remember. It adds to the Android sandbox and the device's own file encryption; it does not replace
  them.
- **The key is held by the phone, not by you.** It is wrapped under an AES-256 key generated inside
  the phone's hardware keystore, which cannot be copied off the device. A copy of the app's storage
  taken elsewhere contains an unreadable database and a wrapped key that nothing in the copy can
  open. The trade, stated plainly: the journal is bound to this phone. It already was, because
  `android:allowBackup="false"` and the `foss` build does not sync.
- **A journal from an older version is migrated once**, on the next launch. It is copied into a new
  encrypted file, and every table's row count, the schema version and SQLite's own integrity check
  are verified before the original is replaced. The `-wal`, `-shm` and `-journal` files are then
  checked to be gone. If anything fails, the original stays exactly where it was and Daymark tries
  again next time.
- **If the phone loses that key**, the entries cannot be read by anything or anybody. This is rare;
  it can happen after some firmware updates or a security reset. Daymark says so, offers to leave
  the entries alone first, and removes them only if you choose to.
- **Photos you attach to entries are not encrypted by the app** (#{R8}). They are downscaled copies
  in the same app-private storage and never leave your device on their own. The Android Photo Picker
  lets you choose one image **without granting any storage or media permission**.
- `android:allowBackup="false"` is set, so the system does not copy your data into cloud or adb
  backups.

## The PIN

- If you set a PIN, it is stored only as a PBKDF2 hash with a random salt, inside an AES-256
  `EncryptedSharedPreferences` store. It is never stored in plaintext. A PIN chosen now is 6–12
  digits; one set by an older version keeps working at whatever length it has.
- **The PIN guards the screen, not the file.** It is checked against that hash and then discarded;
  no key is made from it. So forgetting it does not destroy your entries.
- **But the lock screen has no way past a forgotten PIN except biometric unlock.** Without biometrics,
  the way back today is to reinstall Daymark, which erases the app's storage, and then restore a
  backup (#146). A written-down recovery code is designed and built, but not switched on (#109).

## Permissions, and why

| Permission | Why |
|---|---|
| `POST_NOTIFICATIONS` | Show your optional reminders (Android 13 and later). |
| `RECEIVE_BOOT_COMPLETED` | Re-arm your reminders after the device restarts. |
| `USE_BIOMETRIC` | Optional biometric unlock for the app lock. |
| `SCHEDULE_EXACT_ALARM` | Deliver each reminder at the time you chose; falls back to inexact if unavailable. |
| `VIBRATE` | Haptic feedback, for example for the breathing pacer and reminders. |

There is no `INTERNET`, `RECORD_AUDIO`, location or contacts permission.

## The breathing check

The **experimental** on-body breathing check uses the phone's **accelerometer** while it rests on your
chest. **No audio is recorded** and no raw sensor stream is stored. Only the derived result is shown
to you, on the device. It is a non-diagnostic wellbeing aid, not an apnea test.

## Backups and exports

JSON backups, CSV exports and PDF reports are **plaintext** files, written wherever **you** choose
through the system file picker.

A JSON backup contains every table you would miss, including your photos (embedded, so the backup
stays one portable file). It leaves out the record of when the app asked you something.

Once exported, a file is outside Daymark's protection. Store it somewhere safe and treat it as
sensitive. Encrypted backups and exports are not built (#{R7}).

## Not a medical device

Daymark is a self-tracking tool. Its sleep, self-check, check-in, breathing, thought-record,
behavioural-activation, movement and support features are **non-diagnostic**. They do not detect,
diagnose or treat any condition, and nothing in the app replaces professional advice. For the
licensing of the bundled questionnaires, see [docs/INSTRUMENTS.md](docs/INSTRUMENTS.md).

## Contact

For privacy questions or security reports, see [SECURITY.md](SECURITY.md).
