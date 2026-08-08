# Toolchain — why the versions are pinned where they are

This page exists because several of Daymark's build dependencies **cannot be upgraded
independently**. Dependabot proposes them one at a time, those PRs fail, and the failures look like
flakes rather than the hard constraints they are. Every `ignore` rule in
[`.github/dependabot.yml`](../.github/dependabot.yml) points back here.

> **Read this before deleting a pin or an ignore rule.** Each one has a reason and an unblock
> condition. "The number was old" is not a reason to change it.

---

## Contents

- [The constraint graph](#the-constraint-graph)
- [Each constraint, and what unblocks it](#each-constraint-and-what-unblocks-it)
- [If you want to upgrade: the two coherent moves](#if-you-want-to-upgrade-the-two-coherent-moves)
- [How this was established](#how-this-was-established)

---

## The constraint graph

Currently pinned: **AGP 8.13.0**, **Gradle 8.13**, **JDK 17**, **compileSdk 35**, **Kotlin 2.0.21**,
**KSP 2.0.21-1.0.28** (KSP1), **Compose BOM 2024.12.01**, **Room 2.8.4**, **Hilt/Dagger 2.52**,
**lazysodium 5.1.0**, **jna 5.17.0**.

Almost everything interesting hangs off the AGP version:

```
JDK 17 ──────────────► lazysodium 5.1.0 ceiling  (5.2.0 java artifact is JVM-21-only)

AGP 8.13.0 ┬─────────► Hilt/Dagger 2.58 ceiling  (2.59+ hard-requires AGP 9)
 (last 8.x)├─────────► Compose foundation 1.9+ now ALLOWED (8.13 > the 8.8.2 lint floor)
           └─ needs ─► Gradle 8.13   (AGP 9 would need Gradle 9.1+)

Kotlin 2.0.21 ── locked to ──► KSP 2.0.21-1.0.28
        │                          │
        └──── both feed ───────────┴──► Hilt annotation processing
                                        (Kotlin 2.4.0 + KSP ≤2.3.9 breaks `internal` providers)

Room 2.8.4 ──────────► schema export automated by the Room Gradle plugin; room-ktx dropped
```

## Each constraint, and what unblocks it

| Pin | Ceiling | Why | Unblocked by |
|---|---|---|---|
| **AGP** | `< 9.0.0` | AGP 9 removes the legacy variant API, enables built-in Kotlin by default, and needs Gradle ≥ 9.1.0 (wrapper is 8.11.1). *The variant-API cost here is nil* — nothing in this repo uses `applicationVariants`, `libraryVariants`, `variantFilter`, `dexOptions`, `sdkDirectory` or friends. The cost is the Gradle jump and the built-in-Kotlin decision. | A deliberate AGP 9 + Gradle 9.1 migration PR |
| **Hilt / Dagger** | `< 2.59` | Dagger 2.59 makes AGP 9 a hard requirement for anyone applying the Hilt Gradle plugin, which we do. 2.58's notes say AGP 9 support was deliberately held back "because it forces users onto AGP 9". Hilt currency and the AGP 9 migration are **one atomic decision**. | The AGP 9 migration |
| **Kotlin / KSP** | no major/minor bumps | They are version-locked (KSP publishes as `<kotlin>-<ksp>`), and Dependabot bumps them in *separate* PRs — so a Kotlin-only bump cannot build by construction. Separately, Kotlin 2.4.0 changed default module naming to include a colon, and KSP ≤ 2.3.9 then emits invalid identifiers when Dagger/Hilt processes **`internal`** provider methods (`google/ksp#2964`, fixed in KSP 2.3.10/2.3.11). This repo has internal Hilt providers. | One hand-made PR moving Kotlin + KSP together onto KSP ≥ 2.3.10, with Hilt re-verified against that Kotlin |
| **Compose BOM** | `< 2025.05.00` | foundation ≥ 1.9.0 bundles lint checks requiring **AGP ≥ 8.8.2**; we are on 8.7.3. | A small AGP bump to 8.8.2+ — this does **not** require the AGP 9 work |
| **Room** | *(none)* | Was bounded at `<2.7.0` because 2.7 empties `room-ktx` into `room-runtime` and we declared it. **Done:** the project is on **2.8.4**, `room-ktx` is dropped, and schema export is verified in CI. The bound is lifted. | — |
| **lazysodium** | `< 5.2.0` | 5.2.0's `lazysodium-java` Gradle metadata marks it **JVM-21-only**; `:sync-crypto` and the app target JVM 17, so it fails variant resolution. `lazysodium-android` and `lazysodium-java` must also stay on the *same* version — `SyncCrypto` compiles against the java copy of the shared types and runs against the android copy. | Moving the whole project to JVM 21 |

**GitHub Actions updates are deliberately unconstrained.** Those bumps are not failing, and action
major versions mostly change the bundled Node runtime — the build log already warns that
`actions/checkout@v4` targets the deprecated Node 20. Review and merge them normally.

## If you want to upgrade: the two coherent moves

Upgrading piecemeal is what produces the un-mergeable PR churn. There are really only two sensible
units of work.

**Move A — the cheap one. AGP 8.7.3 → 8.8.2+, then Compose BOM.**
Unblocks the entire Compose stack. No Gradle wrapper change, no variant-API work, no Kotlin
movement, no Hilt movement. If you also take Compose BOM **2025.04.01** (foundation 1.8.0), the
`@OptIn(ExperimentalLayoutApi::class)` annotations around `FlowRow` can be dropped — cosmetic, but
free once you're there.

**Move B — the expensive one. AGP 9 + Gradle 9.1 + Kotlin/KSP + Hilt, together.**
These genuinely cannot be separated: Hilt ≥2.59 forces AGP 9, AGP 9 forces Gradle 9.1, and Kotlin
drags KSP with it. Do it as one PR, on a quiet week, verified in CI, with the intent to revert
wholesale if it goes bad. Note that Dagger publishes **no** Kotlin support matrix and has broken on
new Kotlin metadata versions before (`google/dagger#5001`), so this is the least predictable work in
the repo.

Do **not** attempt Room 3.x as part of either. It is a rename-everything migration — new coordinates
(`androidx.room3:room3-*`), new plugin id, new extension, new package — for no gain here.

## How this was established

These constraints came out of a research pass in which every version claim had to be backed by a
re-fetched primary source, and each finding was then adversarially re-checked; all four reports came
back with corrections. Where a claim could not be verified it is not stated here.

Two known-weaker points, flagged so nobody treats them as settled:

- **Compose BOM 2025.04.01 = foundation 1.8.0 = FlowRow stable.** The compose-foundation changelog
  never documents the stabilisation — the conclusion rests on the frozen API signature files, where
  the 1.8.0-beta01 metalava output shows an unannotated overload and 1.7.0-beta01 does not. One
  reviewer read the changelog silence the other way.
- **Absence of a documented floor is not a guarantee.** "BOM 2025.04.01 imposes no Kotlin/AGP
  minimum" rests on no such floor being *documented* under any 1.8.x heading.

Nothing here was validated by an actual build: there is no Android SDK on the maintainer's machine
and Google Maven is unreachable from the dev container, so **CI is the only verification**. That is
precisely why the guidance above is to move in small, revertible units.

## See also

- [`.github/dependabot.yml`](../.github/dependabot.yml) — the enforcement, with per-rule reasons
- [ARCHITECTURE.md](ARCHITECTURE.md) — app internals, Room schema and migrations
- [SAFETY_PLAN_FEATURE_PLAN.md](SAFETY_PLAN_FEATURE_PLAN.md) — why schema export is load-bearing,
  and the `MigrationTest` wiring that depends on the Room version
