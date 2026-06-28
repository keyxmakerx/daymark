# Architecture

Daymark is a single-module, single-Activity Android app using **MVVM + a repository layer**.

```
Compose screen ── ViewModel (StateFlow) ── Repository ── Room DAO ── SQLite
                                         └─ platform services (alarms, prefs, biometrics)
```

## Layers

- **`ui/`** — Jetpack Compose screens, each with a `*ViewModel` and a `*UiState`. Theme,
  reusable components, and the icon registry live here. Navigation is a single
  `NavHost` in `DaymarkAppScaffold`.
- **`data/`** — Room `@Entity` classes, DAOs, repositories, and `AppDatabase`. DAOs return
  `Flow` so the UI updates reactively.
- **`model/`** — the domain `Mood` scale.
- **`stats/`** — **pure, Android-free** logic (`MoodStats`, `GoalProgress`) so it is fast to
  unit-test on the JVM.
- **`notifications/`** — `ReminderScheduler` (AlarmManager) + boot/alarm receivers.
- **`security/`** — `PinManager` (PBKDF2 + `EncryptedSharedPreferences`) and `BiometricHelper`.
- **`backup/`** — `BackupManager` (JSON import/export with replace & merge, plus CSV export).
- **`widget/`** — a Glance home-screen widget.
- **`di/`** — Hilt module wiring (`SingletonComponent`).

## Data model (Room)

- `MoodEntry(id, dateTime, moodLevel, note)` — multiple per day; `note` captures *why*.
- `ActivityEntity`, `EntryActivityCrossRef`, `EntryWithActivities` — taggable activities (M:N).
- `JournalEntry` — standalone diary, separate from mood notes.
- `Goal` — weekly habit goal, optionally linked to an activity.

Timestamps are stored as epoch-millis `Long` (no timezone ambiguity; keeps `stats/` Android-free).

## Migrations

The schema is versioned (currently **v9**) and **exported** to `app/schemas/`. Migrations are
additive and non-destructive (e.g. journal, goals, sleep logs, treatments, trackers, the
`mood_entries.photoPath` column in v8, the `reminders` table in v9); destructive fallback is
never used.

`app/src/androidTest/.../MigrationTest.kt` validates every hop with an exported start schema
(**3 → 9**) plus a full 3→latest chain, using `MigrationTestHelper`. These are **instrumented**
tests — they run on a device/emulator (and CI), not in the local JVM unit-test run.

Known gap: `1.json` / `2.json` do not exist (export was enabled at v3), so `MIGRATION_1_2` and
`MIGRATION_2_3` cannot be validated by `MigrationTestHelper`. They are retained for correctness
and **must never be deleted**; only v1/v2 installs from the earliest pre-release builds are
affected.

## Reactive & async

Kotlin Coroutines + Flow throughout. ViewModels expose `StateFlow` via `stateIn(WhileSubscribed)`
or `MutableStateFlow` for editor screens.

## Testing

Pure logic in `stats/` is unit-tested (`MoodStatsTest`, `GoalProgressTest`); backup CSV escaping
in `CsvFieldTest`. CI runs `test` + `assembleDebug` and validates the Gradle wrapper.
