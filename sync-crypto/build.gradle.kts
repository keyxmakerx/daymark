// Pure-JVM, Android-free crypto module for the phone "sync" flavor (Milestone 2b).
//
// This module contains ONLY the byte-for-byte port of companion/web/src/lib/sync/crypto.ts.
// It is compiled against the shared com.goterl.lazysodium.* types (identical across the
// lazysodium-java and lazysodium-android artifacts — see the class's KDoc) so its unit tests
// run on the plain host JVM via lazysodium-java (real native libsodium, no Android SDK or
// emulator needed), while the real `sync` product flavor in :app wires the same class up with
// lazysodium-android at runtime. See docs/COMPANION_PHONE_2B.md and docs/SYNC_PROTOCOL.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // 21, not 17, and only in THIS module: lazysodium 5.2.0's metadata demands a JVM-21 consumer,
    // and the whole point of per-module toolchains is that :app does not have to follow. Whether
    // :app can CONSUME this module at 21 is the question the CI run this commit exists for answers.
    jvmToolchain(21)
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
}
