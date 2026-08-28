// Pure-JVM, Android-free crypto module for the phone "sync" flavor (Milestone 2b).
//
// This module contains ONLY the byte-for-byte port of companion/web/src/lib/sync/crypto.ts.
// It is compiled against the shared com.goterl.lazysodium.* types so its unit tests run on the
// plain host JVM via lazysodium-java (real native libsodium, no Android SDK or emulator needed),
// while the real `sync` product flavor in :app wires the same class up with lazysodium-android
// at runtime. That cross-artifact linkage is verified by LazySodiumParityTest on every test run
// — see the version-catalog comment for why it stopped being taken on trust. See
// docs/COMPANION_PHONE_2B.md and docs/SYNC_PROTOCOL.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // 21, not 17, and only in THIS module: lazysodium 5.2.0's Gradle metadata demands a JVM-21
    // consumer, and jvmToolchain is per-module, so :app does not have to follow. Whether :app
    // (target 17, AGP 8.13 / D8, compileSdk 36) can consume this module's 21 classfiles could
    // not be answered on a machine without the Android SDK; CI answered it — run 33128182690,
    // green, branch claude/lazysodium-520-experiment, 2026-08-28.
    jvmToolchain(21)
}

// The android artifact, resolved from the SAME version-catalog entry as :app's runtime
// dependency, so LazySodiumParityTest can compare the two artifacts' bytecode. Resolving it
// here (instead of letting the test download something) keeps the compared version and the
// shipped version one value by construction.
val lazysodiumAndroidForParity: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    // Compile-time only: gives the shared com.goterl.lazysodium.* types without shipping
    // lazysodium-java's desktop native libs into the Android app (the app instead brings
    // lazysodium-android via :app's syncImplementation dependency). jna is `optional` in
    // lazysodium-java's own POM (not pulled transitively), so it's declared explicitly here
    // too — SyncCrypto references com.sun.jna.NativeLong directly.
    compileOnly(libs.lazysodium.java)
    compileOnly(libs.jna)

    // Test-only: a real, JNA-loaded libsodium binding that runs on the CI host JVM.
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.jna)
    testImplementation(libs.junit)

    lazysodiumAndroidForParity(variantOf(libs.lazysodium.android) { artifactType("aar") })
}

tasks.test {
    // Resolved lazily (doFirst), so configuration-only invocations like `gradlew help` and
    // `--dry-run` don't download the AAR. A missing property makes the parity test FAIL, not
    // skip — a parity check that silently skips is the comment it replaced, all over again.
    inputs.files(lazysodiumAndroidForParity)
    doFirst {
        systemProperty(
            "daymark.lazysodiumAndroidAar",
            lazysodiumAndroidForParity.singleFile.absolutePath,
        )
    }
}
