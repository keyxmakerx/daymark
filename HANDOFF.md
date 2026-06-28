# Daymark — Project Handoff & Knowledge Base

> A complete brain-dump for picking this project up fresh (new chat / new repo). Read this first.
> Last updated at commit on branch `claude/open-source-daylio-alternative-3ntftx` (PR #8 merged to
> `main`). Everything described here is **in `main`** unless marked TODO.

---

## 0. The prime directive (read this twice)

**NO AI. NO ML. NO GENERATED CONTENT. ANYWHERE. EVER.**

This is a mental-health / wellbeing app. Generated text is considered *dangerous* for people with
mental concerns. Every user-facing word is a **fixed, human-written template** with the person's own
numbers slotted in — fully reviewable and deterministic. "Personalization" means **rules over the
user's own data**, never a model. If you ever feel tempted to add an LLM/ML/"smart" generated
suggestion: don't. The whole architecture (the "Signals" engine) exists to do dynamic, per-person
help **without** AI.

Corollaries that are also hard rules:
- **Non-diagnostic.** Never assert a diagnosis or a risk verdict. Screeners are "self-checks, not
  diagnoses." Describe **association, not causation**.
- **Descriptive, not interpretive.** State what the data shows ("26 days · mostly Good"), never
  narrate how the person *must have felt*.
- **Crisis resources are offline, static, user-editable.** Never auto-escalate.

---

## 1. What this is

**Daymark** — a free, open-source, **Android-only**, **local-only**, privacy-first mood tracker +
micro-journaling + wellbeing toolkit. A clean-room alternative to Daylio (no Daylio code/assets ever;
"Daylio" only as nominative comparison). Targets **F-Droid**.

- Package: `com.daymark.app` · Repo: `keyxmakerx/daymark` · License: **GPL-3.0**.
- **No `INTERNET` permission. No `RECORD_AUDIO`. `allowBackup="false"`.** (Verified in manifest.)
  The privacy claim is *verifiable* because the app literally cannot reach the network.
- 100% local: no backend, no accounts, no ads, no trackers, no telemetry. Data leaves the device
  only via user-initiated JSON/CSV/PDF export through the system file picker (SAF).

---

## 2. Tech stack & how to build

- **Kotlin 2.0.21**, **Jetpack Compose** + **Material 3**, **Room**, **Hilt**, Coroutines/Flow,
  Navigation-Compose. Single-Activity (`MainActivity : FragmentActivity`), **MVVM + repository**.
- AGP 8.7.3, **minSdk 26**, compile/target 35, JDK 17, Compose BOM 2024.12.01. Version catalog at
  `gradle/libs.versions.toml`.
- **`stats/` is a pure-JVM, Android-free domain layer** (no Room/Compose imports) so it unit-tests on
  the JVM with plain JUnit. *Keep new analytical logic here and test it.*

### Build / test commands (an Android SDK is present in-container)
```
./gradlew :app:compileDebugKotlin --offline -q          # fast compile check
./gradlew :app:testDebugUnitTest --tests "com.daymark.app.stats.*" --offline -q
./gradlew :app:assembleDebug --offline -q               # produces app/build/outputs/apk/debug/app-debug.apk
```
- CI (`.github/workflows/build.yml`) is the source of truth for "it builds": JDK 17 → unit tests →
  `assembleDebug` → uploads the APK artifact. `local.properties` is git-ignored.
- **Instrumented tests** (`androidTest`, e.g. Room `MigrationTest`) need an emulator and are **not**
  run in CI yet (TODO: add an emulator job).

### ⚠️ The no-emulator caveat (important)
This environment can compile and run JVM unit tests but **cannot render Compose / `android.graphics`
visually.** So all **Canvas / drawing code is logic-verified but NOT eyeballed**: Year-in-Stars,
the Review-my-year star clusters, the keepsake PNG, the Movement pose figures, the PDF charts. These
**need a real device/emulator pass** before you trust their appearance.

---

## 3. Repo map (where things live)

```
app/src/main/java/com/daymark/app/
  data/            Room entities, DAOs, repositories, prefs-backed stores, BackupManager
    entity/        MoodEntry, ActivityEntity, Tracker, TrackerLog, Goal, JournalEntry,
                   AssessmentResult, ThoughtRecord, Reminder, SleepLog, Treatment, cross-refs
    dao/           one DAO per aggregate
    *Store.kt      SharedPreferences-backed: Settings, MoodCustomization, Achievements, Screening,
                   Crisis, SleepProfile, Photo
  stats/           PURE JVM domain (unit-tested): MoodStats, MoodCorrelations, MoodPatterns,
                   PeriodReview, Achievements, GoalProgress, Signals, YearReview
  export/          PdfReportGenerator, QrEncoder, ReportData, PdfExportOptions, YearKeepsakeRenderer
  ui/<feature>/    Compose screens + HiltViewModels, grouped by feature (see list below)
  ui/components/    shared Compose: PaperSurface, MoodFaceIcon, YearInPixelsGrid, YearInStarsGrid,
                   ConsistencyHeatmap, EntryPhoto, PoseFigure, SignalCards, TextFieldDefaults …
  ui/theme/        paper palette, MoodColors (+ LocalMoodColors), MoodLabels, Type, Shape, Spacing
  ui/navigation/   Routes + TopLevelDestination
  ui/DaymarkAppScaffold.kt   the NavHost wiring (every route lives here)
  widget/          Glance home-screen widget (MoodWidget)
app/schemas/       committed Room schema JSON (exportSchema=true), versions 1..12
docs/              DESIGN, ARCHITECTURE, PRIVACY, FEATURES, USER_GUIDE, ROADMAP, INSTRUMENTS,
                   SLEEP_FEATURE_PLAN, SUPPORT_FEATURE_PLAN, DOCKER_COMPANION, FAQ, ON_BODY_BREATHING…
```
ui feature dirs: `home, entry, calendar, insights, journal, goals, activities, trackers, assessments,
cbt, activation, movement, sleep, support, achievements, settings, onboarding, search, lock, more,
icon, theme, components, navigation`.

---

## 4. Design system — "modern paper"

Warm-stationery aesthetic. Tokens (in `ui/theme/`):
- **Light:** paper bg `#F4EFE6` · sheet/surface `#FCFAF5` · ink `#2A2722` · soft `#6B655B` ·
  faint `#A49C8E` · hairline `#E7DFD1` · accent `#33302A`.
- **Dark "night paper":** bg `#1B1A17` · surface `#24221D` · ink `#EBE5D8`.
- **Mood scale (awful→rad):** `#AE5747 · #C27C46 · #C6A24E · #8FA268 · #5E8A66`. These are exposed via
  `LocalMoodColors`/`MaterialTheme.moodColors.forLevel(1..5)` and `LocalMoodLabels` so **custom mood
  palettes/labels carry through everywhere** — always use these, never hardcode mood colors in UI.
- **Mood level 1..5 is the stable key** in the DB; custom labels/colors are a presentation layer.
- **Night-sky surfaces** (Year in Stars, Review, keepsake) use a fixed dark palette regardless of
  theme: bg `#16150F`, ink `#EBE5D8`, faint `#8E887A` (see `YearInStarsGrid.kt` internals).
- **Icons & art are all original** (hand-drawn vector `res/drawable/ic_*`, Canvas `MoodFaceIcon`,
  `PoseFigure`, the star renderer) → zero licensing concerns.
- **Star treatment (locked):** a soft glowing dot for ordinary days + a cross-ray glint for the good
  ones ("mixed sky"). `drawMoodStar()` in `YearInStarsGrid.kt` is the shared renderer.
- **⚠ Fonts NOT bundled yet.** `res/font/` is empty; the app currently falls back to system
  serif/sans. The design calls for **Fraunces** (serif/display) + **Inter** (sans) as bundled OFL
  TTFs (offline, F-Droid-safe). **This is an open TODO** (see §9).

---

## 5. Data layer

- `MoodEntry(id, dateTime: Long /*epoch millis*/, moodLevel: Int 1..5, note: String = "", photoPath: String?)`.
  Time is stored as epoch-millis `Long` deliberately (no `Instant`/`LocalDate` converters → no TZ
  ambiguity, and it keeps `stats/` Android-free). The **ViewModel owns the time zone** and converts
  via `util/DateUtils` before handing primitives to `stats/`.
- Room is at **version 12**. Migrations are **non-destructive**, each with a committed schema JSON in
  `app/schemas/` and an instrumented `MigrationTest`. **Never** `fallbackToDestructiveMigration`.
- **Backup:** versioned `BackupData` JSON (`CURRENT_VERSION = 12`) via `BackupManager`
  (kotlinx.serialization). **REPLACE** and **MERGE** import modes (merge remaps ids via old→new maps
  in one transaction). Older backups still import. Also CSV export and a **PDF report**
  (`export/PdfReportGenerator`, platform `PdfDocument`+`Canvas`, selectable text, QR authenticity via
  `QrEncoder`).
- Prefs stores hold non-Room state (settings, custom moods, achievement unlock times, screening
  results, crisis resources, sleep profile). Photos live app-private via `PhotoStore` (path-traversal
  guarded).

---

## 6. ★ The "Signals" engine — the cohesion mechanism (this is the heart)

The problem it solves: the app had grown into a "toolbox" of ~16 scattered feature cards. **Signals**
meshes them into one experience **without AI**.

`stats/Signals.kt` (pure, deterministic, **15 unit tests** in `SignalsTest`):
- `Signals.build(inputs): List<Signal>` ranks candidate cards by fixed-threshold rules. Each
  `Signal` has: `kind`, `category` (Support/Celebration/Insight/Nudge/Prompt), `score`, `title`,
  `body` (fixed templated copy), optional `action` (sealed `Action`), `dismissible`, and a
  `surfaces` set.
- **Surfaces:** `Feed`, `Insights`, `Support`. `Signals.forSurface(list, surface, limit)` selects.
- **Rules (thresholds = the rules; tuned conservative):** low-mood support offer (100, Feed),
  prompt-to-log (85, Feed), achievement-unlocked (72), streak-milestone (65), month-up (58),
  check-in-due (54), on-this-day (44, Feed), lift-factor→make-a-goal (40+), month-**down** (40,
  **Insights-only**, gently worded), drag-factor (35+, Insights-only). `supportMenu(topLift)` returns
  the always-available "what might help" options (move/breathe/thought/journal/crisis), with movement
  rising + getting personalized copy when it's a known lift.
- **It feeds THREE surfaces** (the "one engine, three surfaces" design):
  1. **Insights "For you"** strip → `ui/insights/SignalCards.kt`.
  2. **Home "Quiet Feed"** (Plan A) → `ui/home/HomeScreen.kt` (excludes `on_this_day`; Home has its
     own richer memories card).
  3. **"What might help"** → `ui/support/SupportScreen.kt` (uses `supportSignals`).
- `ui/insights/SignalsViewModel.kt` derives `Signals.Inputs` from repos (reusing `MoodStats`,
  `MoodCorrelations`, `MoodPatterns`). Notes:
  - It **idempotently writes** newly-earned achievement unlock times (documented in its KDoc) — same
    sticky write the Achievements screen does.
  - **Achievement celebration only fires on a *single* fresh unlock** (`newly.size == 1`) so a
    pre-existing user's first run (which records *many* old badges at once) is never falsely
    celebrated.
  - `supportSignals` is a **separate, side-effect-free** flow, always non-empty.
- `SignalCards` **hoists dismissal state** to the caller (`SignalDismissalSaver`,
  `visibleSignalCount(...)`), so a surface can drop the whole strip cleanly when everything's
  dismissed. Dismissals survive config changes.
- Card actions route via `signalActionRoute(action)` in `DaymarkAppScaffold.kt`.

**When extending personalization, add a rule to `Signals.build` (or a new `Inputs` field + a
ViewModel derivation) — never a model.**

---

## 7. Year in Stars + "Review my year"

- `stats/YearReview.kt` (pure, **7 tests**): builds the review data + **descriptive** copy from a
  year's per-day mood means — quarter chapters, brightest month (deterministic earliest-month
  tie-break), longest streak + where it began, all as factual strings (never "you felt…").
- `ui/components/YearInStarsGrid.kt`: a dark night-card Canvas; each logged day a star
  (`drawMoodStar`); a **Stars/Grid toggle** lives in the Insights *Year* view (the dense
  `YearInPixelsGrid` stays for analysis). Has a real colour→mood legend + a summarising
  `contentDescription`.
- `ui/insights/ReviewYearScreen.kt` + `ReviewYearViewModel.kt`: a full-screen `HorizontalPager`
  walkthrough (intro → quarter chapters with star clusters → finale stats), tap/swipe to advance,
  gentle per-page fade, "Skip" control. Route `REVIEW_YEAR` (year nav arg).
- `export/YearKeepsakeRenderer.kt`: renders the year as a 1080×1350 PNG keepsake via
  `android.graphics` (deterministic layout, custom mood ARGB passed in), saved via SAF from the
  finale's "Save keepsake" button.

---

## 8. Feature inventory — what's DONE (all in `main`)

- **Core:** entry logging (mood + note + activities + photo), Home timeline (swipe-to-delete + undo),
  Calendar, tap-a-day detail, Insights (stats + correlations + patterns + period-compare + heatmap +
  "in review"), Journal (separate from entry notes, with search), Year-in-Pixels.
- **Reminders:** multiple, with notification quick-log. **App-lock:** PIN + biometric, auto-lock
  timeout, re-lock on background.
- **Backup/restore:** JSON (replace/merge), CSV export, **PDF report** with QR authenticity.
- **Customization:** rename/recolor the 5 mood levels; custom activities + an **activity library**
  catalog; custom trackers (scale/numeric/boolean/choice) overlaid vs mood in stats.
- **Evidence-based modules:** PHQ-9 / GAD-7 / WHO-5 check-ins (history + trend; PHQ-9 item-9 → offline
  crisis flow; only scores stored); behavioral activation; implementation-intention (if-then) goals;
  breathing presets; journal templates; CBT thought records.
- **Gamification:** achievements (original badges), consistency heatmap.
- **Move:** gentle yoga/stretch + bodyweight routines with **original Canvas pose figures**, haptic
  timer, per-session logging to a "Movement minutes" tracker.
- **Sleep:** sleep log (Consensus-Sleep-Diary-style fields), license-clean screeners, sleep profile,
  treatments before/after, an on-body breathing-capture experiment. (Tiered sensor plan in
  `docs/SLEEP_FEATURE_PLAN.md`; advanced tiers are largely **planned**, not shipped.)
- **Signals engine + 3 surfaces; Year in Stars + Review my year + keepsake; sentence
  auto-capitalization in all free-text fields.**
- **Home-screen widget** (Glance) — `widget/MoodWidget`.
- **Onboarding** wizard; **Gentle support** ("take a moment") space.

---

## 9. TODO / roadmap (not done — roughly prioritized)

**Near-term / cheap wins**
1. **Bundle fonts** — add Fraunces + Inter OFL TTFs to `res/font/`, wire into `ui/theme/Type.kt`,
   ship `OFL.txt` + a licenses row. (Currently using system fallback; the "paper" identity wants the
   serif wordmark.) Verify they're **bundled, not Downloadable Fonts**.
2. **On-device visual pass** of all Canvas art (Year in Stars, keepsake, pose figures, PDF charts) —
   never been eyeballed here (see §2 caveat). Especially check star contrast on the dark bg.
3. **Add a CI emulator job** for the instrumented `MigrationTest` + any androidTest; add `lint` to CI.

**Release readiness (gate before a public tag)**
4. Replace the **debug-signed** `release.yml` with a **signed `assembleRelease`** (keystore from
   Actions secrets, `keystore.properties` git-ignored). Publish APK SHA-256 in release notes.
5. **F-Droid submission:** `fastlane/metadata/android/en-US/`, verify FOSS deps + reproducible build,
   confirm no `INTERNET`. Decide final app name + permanent `applicationId` before first public
   release.
6. Accessibility pass (TalkBack content descriptions everywhere, large-font layouts); i18n /
   localization scaffolding (strings are currently hardcoded English — intentional for now).

**Features (post-merge backlog)**
7. **PDF report phase 2:** optional AndroidKeystore detached signature + a hand-drawn signature pad
   (phase-1 SHA-256 + QR already ship).
8. **Full custom mood scale** (variable number of levels via `MoodGroup/MoodLevel` entities) — high
   blast radius; replaces the fixed 1..5 `Mood` enum. The current "customize moods" only relabels/
   recolors the 5 levels.
9. **Sleep sensor tiers** (Tier-1 sleep-window estimator → Tier-2 motion → Tier-3 mic events →
   Tier-4 sonar) per `docs/SLEEP_FEATURE_PLAN.md`. Each tier independently shippable & honest; all
   would require the **separate sync/permission story** if anything ever needs more than local
   compute. (Mic/sensors would add permissions → keep behind explicit opt-in; never in the core
   no-network promise.)
10. Deepen the **Signals** engine: more rules, "memories"/"on this day" variety, per-person nudges —
    all still rules-based, no AI.

**Self-hosted companion (designed, not built)** — see `docs/DOCKER_COMPANION.md`
11. **Phase 0:** offline drag-in **report viewer** (static local web app; zero phone changes, zero
    network). *Recommended first.*
12. **Phase 1:** E2EE snapshot **sync** — a **separate "Daymark Sync" build flavor** that adds
    `INTERNET` *only* in that flavor (core build stays network-free), zero-knowledge server (Kotlin/
    Ktor), client-side decryption (Argon2id + XChaCha20/AES-GCM AEAD), append-only versions.
13. **Phase 2:** companion-only sit-down assessments (an **original** attention/CPT-style task +
    license-clean self-reports like ASRS v1.1 / IPIP). Non-diagnostic, license-clean only — **not**
    TOVA/Conners/CAARS.
    **Open questions for the maintainer are listed at the end of `docs/DOCKER_COMPANION.md`.**

---

## 10. Conventions & workflow

- **Branch:** all dev happened on `claude/open-source-daylio-alternative-3ntftx` → merged to `main`
  via PR #8. For new work, branch fresh from `main`.
- **Commits:** small, reviewable, each green-building. Trailers used:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` and a `Claude-Session:` line. (Do **not**
  put any model identifier in code/PR/commit *content*.)
- **Push:** `git push -u origin <branch>` with exponential-backoff retry on network errors. Never
  disable TLS / unset `HTTPS_PROXY`.
- **PRs:** only when asked. There's a PR template at `.github/` — mirror its checklist.
- **Audit pattern (worked well):** after a substantial feature, run independent "finder" passes
  (bugs / UI+animation / security+docs), then **adversarially verify every finding** before acting
  (kills false positives). Fix confirmed, note rejected. This session caught: a false-celebration
  bug, an ungated month card, a dead dismiss-animation, a stray-gap layout bug, a brightest-month
  tie nondeterminism, plus a11y gaps — all fixed.
- **Licensing discipline:** keep `docs/INSTRUMENTS.md` as the questionnaire license ledger; never
  alter validated instrument wording; only bundle public-domain/free instruments.

---

## 11. Known gaps / risks to watch

- **Canvas art unverified visually** (fonts, stars, keepsake, poses, PDF) — do an on-device pass.
- **Fonts not bundled** — app looks more generic than the locked "paper" design until added.
- **Release is still debug-signed** — must fix before any public distribution.
- **Instrumented tests not in CI** — migrations are only locally/emulator-verified.
- **Strings are hardcoded English** — i18n is deferred but on the roadmap.
- **Sleep advanced tiers + companion are designs, not code** — don't assume they exist.

---

## 12. Existing docs to read next

`README.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `SECURITY.md`, `PRIVACY.md`, `CODE_OF_CONDUCT.md`,
and under `docs/`: `DESIGN.md`, `ARCHITECTURE.md`, `ROADMAP.md`, `FEATURES.md`, `USER_GUIDE.md`,
`INSTRUMENTS.md`, `SLEEP_FEATURE_PLAN.md`, `SUPPORT_FEATURE_PLAN.md`, `ON_BODY_BREATHING_DEV_NOTES.md`,
`FAQ.md`, and **`DOCKER_COMPANION.md`** (the self-hosted companion plan/scope/security).

---

*If you take one thing from this file: the product's whole identity is **honest, local, rules-based
help with no AI**. Protect that, keep `stats/` pure and tested, and keep every user-facing string
fixed and reviewable.*
