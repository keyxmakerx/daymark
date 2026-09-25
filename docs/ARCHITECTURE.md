# Architecture

How the Android app is put together. What each feature does, and the rules it keeps, is in
[FEATURES.md](FEATURES.md). The Companion server and web consoles are described in
[COMPANION_ARCHITECTURE.md](COMPANION_ARCHITECTURE.md). How the toolchain is pinned is in
[TOOLCHAIN.md](TOOLCHAIN.md).

## 1. Modules and flavours

- **`:app`** is the Android app: one activity, Jetpack Compose, Hilt, Room. minSdk 26, targetSdk 35,
  compileSdk 36.
- **`:sync-crypto`** is plain Kotlin on the JVM (JDK 21 toolchain), over lazysodium: the CPace
  pairing and the snapshot encryption the phone will share with the Companion, a byte-identical
  mirror of the web implementation ([SYNC_PROTOCOL.md](SYNC_PROTOCOL.md)). Its tests run on the
  JVM, including a parity check against the Android binding. Only the `sync` flavour depends on it.

The app has one flavour dimension, `network`:

| Flavour | Application id | Network | What it is |
|---|---|---|---|
| `foss` | `com.daymark.app`; `io.github.keyxmakerx.daymark` before the first public release (#232). Not built: #369 | none | The released app. No `INTERNET` permission; CI checks the built APK for it. |
| `sync` | `com.daymark.app.sync`; `io.github.keyxmakerx.daymark.sync` likewise. Not built: #369 | `INTERNET` | Opt-in and unreleased. Adds `:sync-crypto` and lazysodium. Today it carries only a crypto factory that nothing calls; the phone's half of the Companion is #138. |

Network code may live only under `app/src/sync/`, and `INTERNET` is declared only in that source
set's manifest. Gradle source sets enforce this: `foss` cannot reference that code at all.

## 2. Layers and packages

```
Compose screen ── ViewModel (StateFlow) ── Repository ── DAO ── Room ── SQLCipher file
                        │
                        └── pure rules: stats/, sky/, goals/, sensing/ (no Android)
```

ViewModels expose `StateFlow`; DAOs return `Flow`, so screens update as data changes. Navigation
is one `NavHost` in `ui/DaymarkAppScaffold.kt`; the bottom bar is Insights, Journal, Home, Goals and
More (`ui/navigation/Destinations.kt`). Packages under `app/src/main/java/com/daymark/app/`:

| Package | Holds |
|---|---|
| `ui/` | Screens and their ViewModels, the theme, shared components, navigation |
| `data/` | Room entities, DAOs, repositories, `AppDatabase`, the preference stores, the encryption gate |
| `backup/` | `BackupManager`: JSON backup and restore, CSV export |
| `export/` | The PDF report. `ReportLayout` is pure page arithmetic |
| `security/` | The PIN, biometrics, the data key and its wraps, the recovery code |
| `notifications/` | Reminder scheduling and the boot and alarm receivers |
| `widget/` | The Glance home-screen widget |
| `stats/` | Pure rules: statistics, correlations, suggestions, the arbiter, timing, reviews |
| `sky/` | Pure model of the Sky ([SKY.md](SKY.md)) |
| `goals/` | Pure rules for goal kinds, the project board and "reached" |
| `sensing/` | The pure breathing detector |
| `model/`, `util/`, `di/` | The mood scale, date helpers, Hilt modules |

**`stats/`, `sky/`, `goals/` and `sensing/` contain no Android, by design.** No Room types, no
`Context`, and no clock: the caller passes the time in. Dependencies run one way: `data/` reads
`stats/`, never the reverse, so a pure package cannot acquire an Android dependency by being handed
an entity. `stats/InterruptionBudget.kt` and `stats/TimingGrid.kt` import nothing at all.

Time is stored as epoch milliseconds (`Long`). The ViewModel owns the time zone and converts
through `util/DateUtils.kt` before handing plain values to `stats/`.

## 3. Data and schema policy

### 3.1 The database

Room, schema **v18**, 20 entities:

- entries: `MoodEntry`, `ActivityEntity`, `EntryActivityCrossRef`, `JournalEntry`;
- goals: `Goal`, `GoalStep`;
- sleep and tracking: `SleepLog`, `Treatment`, `Tracker`, `TrackerLog`, `AssessmentResult`,
  `ThoughtRecord`;
- the safety plan and life events: `SafetyPlanItem`, `LifeEvent`;
- people: `Person`, `PersonNote`, `EntryPersonCrossRef`, `PersonGroupShare`;
- reminders and the reception ledger: `Reminder`, `OfferRecord` ([FEATURES.md](FEATURES.md) §13.2).

What each version added: v2 journal · v3 goals · v4 renamed a default activity · v5 sleep logs ·
v6 treatments · v7 trackers · v8 entry photos · v9 reminders · v10 check-in scores · v11 a goal's
if-then plan · v12 thought records · v13 the safety plan · v14 the reception ledger · v15 goal kinds
and project steps (the first foreign key) · v16 life events · v17 when a goal was marked reached ·
v18 people and communities, and the ledger's hour, weekday and "responded" columns.

Rules the code and tests hold:

- **A person never meets a mood in a query.** `EntryDao`, which returns mood, has no method that
  touches `entry_people`; no `PersonDao` query joins `mood_entries`; `MoodCorrelations` takes a
  `FactorId` that only an activity or a tracker can make. `PeopleSchemaTest` checks this.
- **No migration invents data.** v17 never reads an archived goal as reached. v18 never works out an
  old ledger row's hour or weekday from its timestamp, because the zone the person was in then is
  not knowable. `TimedOfferSchemaTest` checks the second.
- **The ledger has no free-text column**, and no migration may add one.
- `entry_people` has no foreign key, like `entry_activity`: a restore writes these rows from an
  untrusted file, and one dangling pair must not abort the whole import. `person_notes` cascades from
  `people`, and deletes also clear children by hand, because a raw database need not have foreign
  keys switched on.
- Deleting an entry clears its *with* links first. SQLite reuses row ids, so a link left behind
  would attach a named person to somebody else's future entry.

### 3.2 Outside the database

- **Preferences** (`daymark_settings`): app settings, custom mood names and colours, suggestion
  controls, the crisis resource, the sleep setup answers and the latest sleep self-check result per
  screener. These are not in the encrypted database.
- **The secure store** (`EncryptedSharedPreferences`): the PIN hash and the wrapped data key.
- **Photos**: JPEGs in `filesDir/entry_photos`, written by `data/PhotoStore.kt`, which also guards
  against path traversal. Not encrypted by the app: #239.

### 3.3 Migrations

Every schema version is exported to `app/schemas/` and committed in the same change that bumps the
version. Migrations are additive and preserve existing data; destructive fallback is never used. A
migration's SQL is written in Room's own generated form, because the migration test compares wording
as well as meaning. The next schema version is **v19**.

Known gap: `1.json` / `2.json` do not exist (export was enabled at v3), so `MIGRATION_1_2` and
`MIGRATION_2_3` cannot be validated by `MigrationTestHelper`. They are retained for correctness and
**must never be deleted**; only v1/v2 installs from the earliest pre-release builds are affected.

`app/src/androidTest/.../MigrationTest.kt` reads the current version and the migration list from
`AppDatabase` by reflection, and checks every hop from v3 plus a full chain from v3 to the latest. It
is an instrumented test: CI compiles it and never runs it (#220). The committed schemas reach it as
androidTest assets, wired by hand in `app/build.gradle.kts`.

### 3.4 The backup format

`backup/BackupManager.kt` writes a versioned JSON file (format v17) that round-trips every table a
person would miss, with photos embedded. Every field added since v1 has a default, so an older file
always reads; a file from a newer format is refused. Adding a field means bumping the version, so
that an older app refuses the file instead of silently dropping what it cannot read. The reception
ledger is deliberately absent, and a Replace restore empties it. Restoring people never turns
sharing on for someone already on the phone. What a backup leaves out is listed in
[FEATURES.md](FEATURES.md) §14.

## 4. Encryption at rest

- **One data key.** On first run, for everybody, `security/DataKeyStore.kt` makes a random 32-byte
  key and keeps it wrapped under an AES-GCM key generated in the phone's hardware keystore
  (`security/KeystoreAead.kt`). The wrap lives in the secure store. A copy of app-private storage
  holds the encrypted database and a wrap that nothing in the copy can open.
- **SQLCipher behind Room.** `di/AppModule.kt` hands Room a SQLCipher open-helper factory with the
  key in SQLCipher's raw-key form, so no key derivation runs on each open.
- **The gate.** `data/JournalEncryptionGate.kt` decides, before any DAO is touched, what the file is:
  encrypted; still plaintext, because the one-off conversion has not succeeded yet (Room opens it as
  before, and the next launch tries again); unopenable, because the keystore lost the key (the app
  offers to leave the entries or start a new journal, and never deletes on its own); or mid-swap
  (the next launch finishes). Settings reads the gate, so its sentence is the truth about this
  phone. A phone whose keystore cannot make a key stays plaintext and says so.
- **The conversion** (`data/JournalEncryptionMigration.kt`) is copy, verify, swap: export every row
  into a new encrypted file; check every table's row count, the schema version and SQLite's integrity
  check; only then delete the original and its sidecars; rename; then list the directory to prove
  no `-wal`, `-shm` or `-journal` file survived. A crash between the delete and the rename is
  detected and finished on the next start.
- **Not covered:** photos, the preferences in §3.2, and exports, which are plain files the person
  makes (#236).
- **The PIN is not a key.** `security/PinManager.kt` keeps a PBKDF2-HMAC-SHA256 hash (210,000
  iterations, random 16-byte salt) in the secure store. Five free attempts, then a wait that doubles
  from 15 seconds up to 5 minutes. The wait is timed on both the wall clock and a clock that cannot
  be set, and the longer remainder wins, so changing the date or rebooting never shortens it.
- **Built, not armed.** `security/DataKeyWraps.kt` can also wrap the data key under a PIN-derived
  key (PBKDF2, 420,000 iterations) and under a written-down recovery code
  (`security/RecoveryCode.kt`). Both are unit-tested and nothing calls them: #109. Arming them means
  the database no longer opens without the person, and the reminder receivers read it from a cold
  start, so it is a decision about reminders as well ([DECISIONS.md](DECISIONS.md) §D7).
- The conversion is tested on the JVM against fakes, and on a real database by an instrumented test
  that CI compiles and never runs.

## 5. How suggestions are chosen

`stats/Signals.kt` turns the person's own data into ranked cards with fixed rules and fixed copy.
There is no model, and nothing is learned: to change what the app suggests, add a rule, never a
model.

| Kind | Rank | Where | When |
|---|---|---|---|
| `support_offer` | 100 | Home | A mood of Awful or Bad today |
| `prompt_log_today` | 85 | Home | Nothing logged today; drawn as Home's check-in row, not a card |
| `month_up` | 58 | Home, Insights | This month's average at least 8% above last month's |
| `checkin_due` | 54 | Home, Insights | A PHQ-9, GAD-7 or WHO-5 check-in is due |
| `on_this_day` | 44 | Home | Entries on this date in earlier years; drawn by the memories card |
| `lift_factor` | 40 and up | Home, Insights | An activity that goes with better moods; offers to make a goal |
| `month_down` | 40 | Insights only | This month's average at least 15% below, worded gently |
| `drag_factor` | 35 and up | Insights only | An activity that goes with lower moods |

Home shows only the top card; More for you shows the rest; Insights has its own strip. The "what
might help" menu (`Signals.supportMenu`) is separate and always complete: move, breathe, a thought
record, journal, and crisis resources last, never dismissible. Working out the cards writes
nothing; the only write is a control the person taps.

**Suggestion controls** (what the person sees: [FEATURES.md](FEATURES.md) §1.5) are pure rules in
`stats/SuggestionControls.kt`, stored by `data/SuggestionControlsStore.kt`. They apply per group, so
a switch reads as plain English: support offers, self-check reminders, what goes with your mood,
month-to-month changes, and On this day. "Show less" subtracts a fixed 12 from the rank, at most
three times. Turning a group on clears the switch, the snooze and the damping together, so nothing
can be on and still invisible. A group exists only if its switch visibly does something, and a unit
test asserts that every dismissible kind has one. On this day draws its own card, so its screen
reads the store directly; a new self-drawn card must do the same.

## 6. Deleting and undo

- `ui/components/SwipeToDeleteRow.kt` arms only past 62% of the row (`CommitFraction`), and its
  `confirmValueChange` always returns false: the gesture opens the confirmation dialog and can never
  delete by itself. Do not "fix" that. Material's velocity shortcut can still arm on a hard flick;
  that is harmless because arming only opens the dialog, and copy must not claim that a flick does
  nothing.
- Delete and undo run in `ui/entry/EntryActionsViewModel.kt`, obtained at the scaffold so it lives
  as long as the activity. The Undo snackbar outlives the screen that raised it; a screen-scoped
  undo would be cancelled by navigating away, and silently do nothing. An entry's photo is removed
  only once the snackbar ends without an undo.

## 7. Tests

- **Unit tests** live in `app/src/test/` and run in CI with `./gradlew test`.
- **Instrumented tests** (`MigrationTest`, `JournalEncryptionMigrationInstrumentedTest`) live in
  `app/src/androidTest/`. CI compiles them and never runs them, so a green build says nothing about
  migrations or the encryption conversion on a device (#220).
- **The root `./gradlew` does not run in the development container.** Anything with an Android
  import is checked by CI only.
- **`tools/jvm-tests.sh <package>`** compiles and runs one Android-free package's tests on a plain
  JVM in seconds: `sky`, `stats`, `goals` and `sensing` all work. It names every file it skipped.
  Green there means the package is consistent, not that the app builds.
- **`tools/jvm-source-tests.sh`** runs `PeopleSchemaTest` and `TimedOfferSchemaTest`, which live in
  `data/` but read source as text and import no Room type. The list of files is kept by hand.
- Several tests read source as text to hold a rule, among them `EntryViewCopySourceTest` (no
  commentary on the entry page), `PeopleUiSourceTest` (no mood on people's screens) and
  `SkySurfaceSourceTest`.

## 8. CI

`.github/workflows/build.yml` runs on every push to every branch. It validates the Gradle wrapper,
runs a configuration smoke test, runs the unit tests, builds debug APKs of both flavours and release
APKs of both (exercising R8), compiles the instrumented tests, checks that the `foss` APK declares no
`INTERNET` permission, and regenerates the current schema with the build cache bypassed, failing if
it differs from the committed file. It uploads the regenerated schemas and both debug APKs.

It does **not** run the instrumented tests or Android lint (#220).

`.github/workflows/release.yml` runs on a pushed `v*` tag. It builds the signed `foss` release APK,
checks it for `INTERNET`, and creates the GitHub release with the APK attached. Without the signing
secrets it falls back to debug signing instead of failing: #224. Publishing the APK's SHA-256 is
#226. `.github/workflows/companion.yml` covers `companion/**`.
