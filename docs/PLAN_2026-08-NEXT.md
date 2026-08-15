# The agreed plan — August 2026

Confirmed before implementation. Supersedes nothing; it sequences work already decided in
[DECISIONS_2026-08.md](./DECISIONS_2026-08.md) and adds two new pieces: the four-side report and the
Sky.

---

## 1. The report — four sides, each dedicated, each filled

Renders: [`design/web-06-report-projects.html`](./design/web-06-report-projects.html) — corrected;
this line named `web-05-report-projects.png`, which does not exist. `web-05` is the companion deck,
and neither `web-05` nor `web-06` has been re-rendered to PNG yet, so the HTML is the artefact.
Density is wanted; the constraint
that survives is **one job per side**, not sparseness for its own sake.

**Reconciling "fill it up" with the alert-fatigue evidence.** The finding is about elements
*competing for attention in the same glance*, not about page count. So: the flag keeps its own zone at
the top of side 1 and never shares it. Everything below and after may be dense, because a clinician
reading side 2 has already chosen to go looking.

| Side | Job | Contents |
|---|---|---|
| **1 · front** | The glance | The one flag. Per-instrument plots with usual-range band and sampling density. Completion summary — which self-checks, tasks, exercises and goals ran, and what came back. |
| **2 · back** | The detail | Every entry with its check-in note. What was suggested and what happened to it. Goal/project progress. Coverage and gaps, uninterpolated. |
| **3 · front** | In their words | Journal entries the person included. **Off by default.** |
| **4 · back** | For the conversation | Discussion prompts derived from the data. Provenance of every tool. Verification (QR + hash) at the **bottom**. |

### Side 3 — filling it without the software choosing

The ask: pick worst, best and typical periods so the page is full rather than sparse.

**The rule that keeps this honest: the app proposes, the person disposes.** Software may *nominate* a
representative set — the lowest-band week, the highest, and one typical — and must then show that
selection to the person, who adds, removes and confirms before anything is exported. The page then
truthfully says *they* chose it, because they did. What the software may never do is select silently.

### Side 4 — "recommendations based on studies", carefully

**This is the one part of the plan with a real regulatory edge, and it needs a bright line.**

A report that tells a clinician what to do is **clinical decision support** — a regulated category
with a different compliance life than anything Daymark currently is. "Based on study X, consider
increasing session frequency" crosses it. That is not a style preference; it changes what the product
legally is.

**What side 4 carries instead: prompts for discussion, phrased as observations and questions.**

> *Wellbeing entries were lower on the three weeks with no logged activity. Worth asking about?*

That surfaces a pattern in the person's own data and hands the judgement to the two humans. It cites
nothing, prescribes nothing, and is useful precisely because it does not pretend to conclude.

Where the evidence base *is* citable is **psychoeducation for the person**, in the app, about a
practice they are being offered — "this exercise draws on compassion-focused methods" — not as
justification for a clinical action in a clinician's report.

---

## 2. The Sky

> *"a slow focus into a full screen starry sky … click one and you get more detail … a history of who
> you are and where you are going … a beautiful unique ID of who you were and who you are, the history
> of what you've accomplished written into the stars."*

**Foundation exists — in Kotlin only.**
`app/src/main/java/com/daymark/app/ui/components/YearInStarsGrid.kt` ships today and draws a year as
a night sky on a fixed dark palette, held as three `internal val`s at
[`YearInStarsGrid.kt:39-41`](../app/src/main/java/com/daymark/app/ui/components/YearInStarsGrid.kt):
`NightBg #16150F`, `NightInk #EBE5D8`, `NightFaint #8E887A`. The same three values are repeated as
canvas ints in [`YearKeepsakeRenderer.kt:126-128`](../app/src/main/java/com/daymark/app/export/YearKeepsakeRenderer.kt).
The Sky is that idea promoted from a chart to a place.

> **Correction.** An earlier version of this paragraph said the design system "reserves a fixed
> night-sky surface with its own tokens `--c-sky-bg` / `--c-sky-ink` / `--c-sky-faint`". **It does
> not, and it never did.** Those token names appear nowhere in `companion/web/src/app.css` or
> anywhere else in the tree — the only surviving mentions were in this document and in
> [SKY.md](./SKY.md), both of which are corrected. `COMPANION_DESIGN_SYSTEM.md` §2.3 did once name
> `--c-sky-*` among its tokens; that was removed as part of the same audit that struck several other
> never-built token families from that spec, and it is recorded in
> [COMPANION_WEB_REDESIGN_PLAN.md](./COMPANION_WEB_REDESIGN_PLAN.md), "Spec corrections beyond §2.3".
> The phrase "night-sky parity" survives in the design system only as a line in the §4.6 chart
> inventory describing a `YearInStars` web component that has not been built.
>
> The three colours are real; the tokens are not. A web Sky would be **introducing** these values to
> the design system, not consuming something already reserved for it — and it would need a decision
> about a surface whose palette is fixed against both themes, which the token layer has no
> precedent for.

**What becomes a star:** a check-in, a completed exercise, a journal entry, a goal reached, a project
step, and a **major life event the person adds themselves**.

### The rule that decides whether this is beautiful or cruel

A history of a person's life, rendered as light, will be read as a judgement of that life. So:

> **Every period has stars. Nothing is empty, and a hard stretch is never a void.**

A sky where depressive episodes are black gaps is a cruel object — it would show someone the shape of
their worst months as absence. Instead: hard periods are *differently coloured and quieter*, not
missing. A month with two entries has two stars, and they are as real as any other month's.

Corollaries:
- **No brightness ranking of days.** Nothing "scores" a period.
- **Nothing is inferred.** Major life events are authored by the person, never detected.
- **No streaks, no completeness metric, no percentage of sky filled.** The sky is not a progress bar.
- **The sky is never shared.** It is the most identifying artefact the product could produce; it is
  local, and it does not enter the report or any grant.

### The tutorial is the sky itself

It needs teaching, and the best teacher is the object. A new person's sky has **one star** — their
first check-in — with a line naming what it is. It fills as they use the app, and each new *kind* of
star introduces itself once. No walkthrough, no carousel.

That also makes the Sky the best empty state in the product: an empty sky with one star says what the
app is for better than any onboarding copy.

---

## 3. Sequencing, and an honest constraint

**There is no Android SDK in this environment and Gradle cannot resolve offline.** Kotlin work cannot
be compiled or tested here — CI is the only oracle, so every Android change is written blind and
verified after push. TypeScript is fully verifiable locally (`pnpm check`, `pnpm test`).

That does not stop the work; it decides the order and the batching.

### 3a. Things a device would prove that CI here cannot

Listed so that "the tests pass" is never read as "this was observed working". Each is a real check
with a real subject, not a caveat.

| Needs a device | Standing in for it now | Why the substitute is weaker |
|---|---|---|
| **Import a JPEG carrying GPS tags and assert the stored file has none.** Needs a real `BitmapFactory` and `ExifInterface`. | `PhotoStoreSourceTest` asserts the structural precondition: no path into storage copies source bytes, both entry points re-encode, exactly one tag is read and none is written. | It proves the pipeline *decodes and re-encodes*, not that the output is clean. If a platform `Bitmap.compress` ever emitted a tag, this would not notice. |
| **Photograph something in portrait, import it, look at it.** | `ImageStripTest` proves all eight EXIF orientations map to eight distinct transforms and that the mirrored four are mirrored. | It proves the decision table is right, not that the `Matrix` composes in the order the decision assumes. Mirror-then-rotate is asserted in prose and in the source, and executed nowhere. |
| **Render one PDF and look at it.** | `ReportLayoutTest` runs the page arithmetic. | Layout is not typography. Nothing has confirmed a glyph lands where the arithmetic says. |
| **Confirm `app/schemas/…/14.json`.** | CI's schema-drift check passes. | Its `identityHash` matches 13.json's, which should not be possible after adding an entity — so either the guard is ineffective or the hash is right for a reason nobody has verified. |

1. **Verifiable now (TypeScript).** The signal vocabulary as a typed closed list with the author
   partition enforced in validation; the dialogue definition type; companion dialogue content. This is
   the substrate the Android side codes against, and getting the partition wrong is the expensive
   mistake.
2. **Written carefully, verified by CI (Kotlin).** The four-side report, discussion prompts, the
   reception ledger, `SupportOffer` generalised into the arbiter.
3. **Designed, then built (Kotlin).** The Sky.

---

## 4. Standing rules for any agent doing this work

Recorded because one was violated during the Phase 2 workflow, destroying uncommitted work.

- **No `git checkout`, `git reset`, `git clean`, or `git stash`.** Ever, for any reason, including
  "restoring" after a test. Use file copies. Other agents may be working in the same tree.
- No `git commit` — the session owner commits.
- Fixed copy is fixed: banners, disclaimers, provenance text and safety-plan wording are not edited,
  reworded or reflowed.
- Every test must fail when its subject is violated, and the violation must be confirmed to have
  actually landed in the file before the test is run.

---

## 5. Landed, and what is NOT wired (read before trusting green CI)

**Green CI is the weaker claim.** For the web it means both oracles ran; for Android it means the
code compiled and the JVM unit tests passed. It does not mean a migration was ever executed, a
notification ever posted, or a schema ever generated. This section exists so a passing build is not
read as more than that.

### Live — a real code path reaches it

- **The arbiter, generalised, with two production callers.**
  `app/src/main/java/com/daymark/app/stats/InterruptionBudget.kt` now takes a `Kind` and the
  engine's own reception, and is called from:
  - `ui/entry/EntryEditorViewModel.kt:196` — the support-space offer after a hard day. This
    **replaced** the `SupportOffer.shouldInterrupt` call; `SupportOffer` survives only for its
    `summary(frequency)` settings copy, used by `ui/support/GentleSupportScreen.kt:108`.
  - `notifications/ReminderScheduler.kt:104` — whether a fired reminder actually posts. The alarm
    itself is untouched: `schedule()` still arms every day at the time the person set, because a
    reminder that stopped scheduling itself would not be "asking less", it would be broken.
  - The two conditions in front of the editor's gate (`moodLevel <= LOW_MOOD_MAX`,
    `gentleSupportOn`) stayed in the feature. The arbiter is asked *whether* it may interrupt,
    never *what about* — §D1's rule, kept.
- **The reception ledger, schema v14.** `data/entity/OfferRecord.kt` is registered in
  `AppDatabase` (`@Database` entity list, `abstract fun offerRecordDao()`, `version = 14`),
  `MIGRATION_13_14` exists and is in both `AppModule`'s builder and
  `MigrationTest.allMigrations`, and `data/OfferLedgerRepository.kt` is the seam that maps rows onto
  the arbiter's plain `Offer` type. Both readers write their own lines: the editor on making an
  offer, the scheduler on posting a notification (and nothing at all on a suppressed one — a line
  means the app asked, and it did not).
- **`stats/` is Android-free again.** The previously-deferred leak is closed:
  `InterruptionBudget.kt` now has **zero imports**, taking `Offer` — three plain fields — instead of
  a Room entity, exactly as `DiscussionPrompts.Inputs` does. The mapping lives in
  `OfferLedgerRepository.budgetKind(...)`.
- **The monotonic invariant is executed, not asserted in prose.**
  `app/src/test/java/com/daymark/app/stats/InterruptionBudgetTest.kt` sweeps
  kind × declared frequency × ledger × standing stop × clock. See §D1 of
  [DECISIONS_2026-08.md](./DECISIONS_2026-08.md) for which sweep catches what.
- **The TypeScript substrate**, unchanged in this sprint and still fully verified: signal
  vocabulary, author partition, dialogue types and planner, companion dialogue content, predicate
  bounds at validation on top of the runtime bound. **Measured at HEAD**, not carried forward:
  `pnpm check` **413 files / 0 errors**, `pnpm test` **403 passed / 5 skipped** across 32 files.
  (This line previously read "402 passed / 5 skipped (baseline 406 / 242)"; the 403rd test is the
  prototype-chain regression added in `96e6f11`.)
- **The four-side report.** `ReportDataBuilder` calls `DiscussionPrompts.texts(...)`
  (`export/ReportData.kt:277`), so side 4 prints real prompts. **This was a false-assurance bug
  caught in review** — the generator's "nothing met the threshold" line asserts that a threshold was
  evaluated, and nothing had ever called the rules. Printing that sentence over an uncomputed result
  is worse than printing nothing. Page arithmetic moved into the Android-free
  `export/ReportLayout.kt`, so pagination is testable without a device.
- **Journal export.** `includeAllJournalInRange` is an explicit flag rather than letting an empty
  `includedJournalEntryIds` mean "everything" — that convention would be fail-open on the most
  sensitive content in the product.

### Needs a real Android build before it can be trusted

**This is the blocking item, and it is one file.**
`app/schemas/com.daymark.app.data.AppDatabase/14.json` was **hand-written from 13.json**, because
Room's schema exporter runs under KSP during an Android build and there is no Android SDK here. The
`offer_records` entity, its columns and both indices were added by hand and read correctly — but
**`identityHash` is still byte-identical to 13.json's** (`2331a16f112909957695b8d7180a9467`), which
it cannot be: the hash is computed over the entity set, and the entity set changed.

Consequences, precisely:

- `Room.databaseBuilder` verifies the identity hash at open time. Against this file, a v14 database
  either fails to open or fails validation.
- `MigrationTest.runMigrationsAndValidate(TEST_DB, 14, …)` compares the migrated schema against this
  JSON and will fail — including the new `migrate13To14_createsOfferRecordsTable_withBothIndices`
  and the `migrateAll_from3_toLatest` hop, both of which are `androidTest` and therefore **do not
  run in this environment or in the JVM half of CI**.
- CI's schema-drift check compares the committed JSON against a generated one, so the first real
  build is where this surfaces.

**The fix is mechanical and cannot be done here:** build the app once with the Android SDK, let KSP
regenerate `14.json`, and commit the generated file in place of the hand-written one. Diff it before
committing — anything beyond `identityHash` differing means the hand-written entity block was wrong
too. Until that has happened, treat "the ledger is live" as *written and wired*, not *verified*.

### Written, tested, and NOT connected

- **`CompanionSignals`** (`stats/Signals.kt:395`, appended below the unrelated `Signals` feed engine) is
  the Kotlin mirror of the eight-signal vocabulary and the author partition, unit-tested in
  `stats/SignalsTest.kt:203`. **It has no production caller.** There is no companion surface in the
  Android app and no path by which authored dialogue reaches a device, so nothing computes these
  values for real yet.
- **`InterruptionBudget.Kind.COMPANION` and `.ASSIGNMENT`** have budgets, defaults and ledger keys,
  and nothing calls them. Only `SUPPORT` and `REMINDER` are wired.
- **Dialogue transport, signing and the authoring capability** — none of it exists on either side.
  See the checklist in [COMPANION_DIALOGUE.md](./COMPANION_DIALOGUE.md), which now says which boxes
  are closed and which are not.
- **The per-entry journal picker** does not exist. The switch is honestly labelled "Include all
  journal entries in range" until it does.

### Known and deliberately deferred

- The D6 "no streaks" rule holds in the report but not in the app: `MoodStats.currentStreak` still
  drives Home (`HomeViewModel.kt:51`), Stats (`StatsViewModel.kt:71`) and the `streak_milestone`
  signal (`stats/Signals.kt:180`). The report copy has been corrected so it no longer claims a
  project-wide decision that has not been made.
