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
**KSP 2.0.21-1.0.28** (KSP1), **Compose BOM 2026.06.01**, **Room 2.8.4**, **Hilt/Dagger 2.52**,
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
| **Compose BOM** | *(none)* | Was bounded at `<2025.05.00` because foundation ≥1.9.0 needs AGP ≥8.8.2. **Done:** AGP 8.13 cleared it; the BOM is on **2026.06.01** and the eight `@OptIn(ExperimentalLayoutApi)` annotations are gone. | — |
| **Room** | *(none)* | Was bounded at `<2.7.0` because 2.7 empties `room-ktx` into `room-runtime` and we declared it. **Done:** the project is on **2.8.4**, `room-ktx` is dropped, and schema export is verified in CI. The bound is lifted. | — |
| **lazysodium** | `< 5.2.0` | 5.2.0's `lazysodium-java` Gradle metadata marks it **JVM-21-only**; `:sync-crypto` and the app target JVM 17, so it fails variant resolution. `lazysodium-android` and `lazysodium-java` must also stay on the *same* version — `SyncCrypto` compiles against the java copy of the shared types and runs against the android copy. | Moving the whole project to JVM 21 |

**GitHub Actions updates are deliberately unconstrained.** Those bumps are not failing, and action
major versions mostly change the bundled Node runtime — the build log already warns that
`actions/checkout@v4` targets the deprecated Node 20. Review and merge them normally.

## Upgrades: what is done, and what is left

**Move A — DONE.** AGP 8.7.3 → **8.13.0** (the last 8.x release) with Gradle 8.11.1 → **8.13**, then
Room 2.6.1 → **2.8.4** and Compose BOM 2024.12.01 → **2026.06.01**. All verified in CI, each pushed
as its own commit so failures stayed attributable. That last part earned itself: Room failed on a
coupling nothing documents (see below), and would have been far harder to diagnose inside a
combined bump.

**Move B — the expensive one, still open. AGP 9 + Gradle 9.1+ + Kotlin/KSP + Hilt, together.**
These genuinely cannot be separated: Hilt ≥2.59 forces AGP 9, AGP 9 forces Gradle 9.1+ and Kotlin
≥2.2.10, and Kotlin drags KSP with it. Do it as one PR, on a quiet week, verified in CI, with the
intent to revert wholesale if it goes bad. Dagger publishes **no** Kotlin support matrix and has
broken on new Kotlin metadata versions before (`google/dagger#5001`), so this is the least
predictable work in the repo.

Do **not** attempt Room 3.x as part of it. It is a rename-everything migration — new coordinates
(`androidx.room3:room3-*`), new plugin id, new extension, new package — for no gain here.

### The coupling that no release note mentions

Room 2.8's schema-bundle classes are serialized with kotlinx-serialization, and its generated
serializers come from a **newer** serialization compiler plugin than this project's 1.7.3 runtime,
where `GeneratedSerializer.typeParametersSerializers()` is still abstract. Room's KSP processor
therefore died with `AbstractMethodError` on every variant.

`app/build.gradle.kts` fixes it by forcing kotlinx-serialization-core **1.9.0 on the `ksp*`
configurations only**. The app stays on 1.7.3 deliberately: every serialization release above it
requires Kotlin ≥2.1 to *generate* serializers, and this project is on 2.0.21 — but that constraint
governs code generation, not the runtime a third-party annotation processor executes against. If
that force is ever removed, KSP fails immediately and loudly.

The general lesson: **"not gated on AGP" is not the same as "not gated."** Room's real edge here ran
through kotlinx-serialization to Kotlin, and only surfaced by building it.

## How this was established

These constraints came out of a research pass in which every version claim had to be backed by a
re-fetched primary source, and each finding was then adversarially re-checked; all four reports came
back with corrections. Where a claim could not be verified it is not stated here.

One of the two originally-weak points has since been **settled by building it**: the research could
not confirm from the changelog which Compose version stabilised `FlowRow` (the compose-foundation
release notes never mention it, and one reviewer read that silence the opposite way). The upgrade
answered it empirically — on BOM 2026.06.01 all eight `FlowRow` call sites compile with no
`ExperimentalLayoutApi` opt-in at all.

Still true, and worth keeping in mind: **absence of a documented floor is not a guarantee.** Several
"X imposes no minimum" claims rest on no such minimum being *documented*, which is weaker than a
statement that none exists.

There is no Android SDK on the maintainer's machine and Google Maven is unreachable from the dev
container, so **CI is the only verification** — which is exactly why the upgrades above went in one
commit at a time.

## See also

- [`.github/dependabot.yml`](../.github/dependabot.yml) — the enforcement, with per-rule reasons
- [ARCHITECTURE.md](ARCHITECTURE.md) — app internals, Room schema and migrations
- [SAFETY_PLAN_FEATURE_PLAN.md](SAFETY_PLAN_FEATURE_PLAN.md) — why schema export is load-bearing,
  and the `MigrationTest` wiring that depends on the Room version
