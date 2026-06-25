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

The schema is versioned and **exported** to `app/schemas/`. Migrations are additive and
non-destructive (`MIGRATION_1_2` added journal, `MIGRATION_2_3` added goals); destructive
fallback is never used. A `MigrationTestHelper` instrumented test is planned (requires an
emulator).

## Reactive & async

Kotlin Coroutines + Flow throughout. ViewModels expose `StateFlow` via `stateIn(WhileSubscribed)`
or `MutableStateFlow` for editor screens.

## Testing

Pure logic in `stats/` is unit-tested (`MoodStatsTest`, `GoalProgressTest`); backup CSV escaping
in `CsvFieldTest`. CI runs `test` + `assembleDebug` and validates the Gradle wrapper.
