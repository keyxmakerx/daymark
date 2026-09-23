# Toolchain: why the versions are pinned where they are

Several of Daymark's build dependencies **cannot be upgraded independently**. Dependabot proposes
them one at a time, those pull requests fail, and the failures look like flakes rather than the hard
constraints they are. Every `ignore` rule in [`.github/dependabot.yml`](../.github/dependabot.yml)
points back here.

> **Read this before deleting a pin or an ignore rule.** Each one has a reason and an unblock
> condition. "The number was old" is not a reason to change it.

## What is pinned

- **Android build:**
  - AGP 8.13.0 (the last 8.x) on Gradle 9.3.1;
  - compileSdk 36, with targetSdk held at 35 and set explicitly;
  - Kotlin 2.0.21 with KSP 2.0.21-1.0.28 (KSP1);
  - Hilt/Dagger 2.52;
  - Compose BOM 2026.06.01 and Room 2.8.4.
- **JVM targets:** `:app` compiles for JVM 17. `:sync-crypto` builds on a JDK 21 toolchain, which is
  what allows lazysodium 5.2.0 (the `-java` and `-android` artifacts stay on the same version) with
  jna 5.17.0.
- **The Companion server** (`companion/server`) has its own Gradle wrapper, 8.11.1, and builds on
  JDK 21. **The web consoles** use pnpm 10.

```
AGP 8.13.0 ┬──────────► Hilt/Dagger < 2.59   (2.59+ hard-requires AGP 9)
 (last 8.x)└──────────► androidx core/activity/lifecycle/androidx.hilt/navigation held
                        (their new versions need compileSdk 37, which needs AGP 9.2)

Kotlin 2.0.21 ── locked to ──► KSP 2.0.21-1.0.28 ──► Hilt annotation processing
Room 2.8.4 ──► needs kotlinx-serialization-core 1.9.0 on the ksp* configurations only (below)
```

## Each constraint, and what unblocks it

| Pin | Ceiling | Why | Unblocked by |
|---|---|---|---|
| **AGP** | `< 9.0.0` | AGP 9 enables built-in Kotlin by default and needs Gradle 9.1 or later. The Gradle part is already done (the wrapper is 9.3.1). Nothing in this repo uses the legacy variant API that AGP 9 removes. | Move B (#{B4}) |
| **Hilt / Dagger** | `< 2.59` | Dagger 2.59 makes AGP 9 a hard requirement for anyone applying the Hilt Gradle plugin, which this repo does. Hilt currency and AGP 9 are **one decision**. | Move B |
| **Kotlin / KSP** | no major or minor bumps | They are version-locked (KSP publishes as `<kotlin>-<ksp>`), and Dependabot bumps them in *separate* pull requests, so a Kotlin-only bump cannot build. Kotlin 2.4.0 also changed module naming, and KSP 2.3.9 and earlier then emit invalid identifiers for **`internal`** Hilt provider methods (`google/ksp#2964`), which this repo has. | One hand-made change moving Kotlin and KSP together onto KSP 2.3.10 or later, with Hilt re-verified |
| **androidx core / activity / lifecycle / androidx.hilt / navigation** | core `<1.19.0`, activity `<1.13.0`, lifecycle `<2.11.0`, androidx.hilt `<1.4.0`, navigation `<2.9.0` | These moved to an AGP 9.1 and compileSdk 37 floor. Bumping them once turned `main` red (`CheckAarMetadata`, 17 issues). The trap: `androidx.hilt` versions separately from `com.google.dagger`, so it looks unrelated. It is not. | **Move C**, not Move B (below) |
| **lazysodium** | none now | 5.2.0's `lazysodium-java` is JVM-21-only. `:sync-crypto` now builds on JDK 21, so 5.2.0 is in. The two artifacts must stay on the same version: `SyncCrypto` compiles against the java copy of the shared types and runs against the android copy. The ignore rule in `dependabot.yml` still blocks everything from 5.2.0 up, which is now wrong: #{B2}. | — |

**GitHub Actions updates are deliberately unconstrained.** Those bumps do not fail, and an action's
major version mostly changes its bundled Node runtime. Review and merge them normally.

## The moves

**Move A (done).** AGP 8.7.3 → 8.13.0, then Room 2.6.1 → 2.8.4 and Compose BOM → 2026.06.01. Each
was pushed as its own commit, so a failure stayed attributable. That paid off: Room failed on a
coupling nothing documents (below).

**Move B (open, #{B4}): AGP 9, Kotlin/KSP and Hilt, together.** Hilt 2.59 or later forces AGP 9.
AGP 9 forces Kotlin 2.2.10 or later, and Kotlin drags KSP with it. Do it as one change, on a quiet
week, verified in CI, ready to revert wholesale. Dagger publishes no Kotlin support matrix and has
broken on new Kotlin metadata before (`google/dagger#5001`), so this is the least predictable work
in the repo.

**Move C (waiting on upstream, #{B5}): the androidx tier.** Move B does not unblock it:

```
androidx tier   needs  compileSdk 37
compileSdk 37   needs  AGP 9.2
AGP 9.2         needs  Kotlin > 2.3.21   (KGP 2.3.21's documented AGP ceiling is 9.0.0)
Kotlin > 2.3.21 is refused by Dagger
```

Dagger's Hilt compiler bundles `kotlin-metadata-jvm`, whose reader stops at metadata 2.3.0, so
Kotlin 2.4 aborts it ("Provided Metadata instance has version 2.4.0, while maximum supported version
is 2.3.0" — `google/dagger#5177`). **The signal to watch for is one upstream event: a Dagger release
whose `kotlin-metadata-jvm` is 2.4.x.** Do not attempt Room 3.x as part of any of this; it renames
every coordinate for no gain here.

## The coupling no release note mentions

Room 2.8's schema-bundle classes are serialized with kotlinx-serialization, and their generated
serializers come from a newer compiler plugin than this project's 1.7.3 runtime. Room's KSP
processor therefore died with `AbstractMethodError`. `app/build.gradle.kts` fixes it by forcing
kotlinx-serialization-core **1.9.0 on the `ksp*` configurations only**. The app itself stays on
1.7.3, because every later serialization release needs Kotlin 2.1 to *generate* serializers. If
that force is removed, KSP fails immediately and loudly.

The general lesson: "not gated on AGP" is not the same as "not gated".

## Room schemas

- Room exports each schema version into `app/schemas`.
- CI forces a fresh export with the build cache bypassed, then diffs it. A plain `git diff` was once
  inert, because Room only *creates* a schema file when one is absent.
- The schemas are also wired in as androidTest assets for `MigrationTestHelper`. Room 2.7 and later
  does this automatically, and the explicit line is kept on purpose.
- CI compiles the instrumented tests but never runs them, because they need a device (#{B1}).
- Schema policy: [ARCHITECTURE.md](ARCHITECTURE.md).

## How this is verified

There is no Android SDK in the agent container and Google Maven is unreachable from it, so **CI is
the only verification** for the Android build. That is why upgrades go in one commit at a time. The
constraints above were each backed by a primary source and re-checked.

**Absence of a documented floor is not a guarantee.** Several "X imposes no minimum" claims rest on
no minimum being documented, which is weaker than a statement that none exists.
