import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.room)
}

// Release signing is read from keystore.properties (git-ignored) or CI env vars.
// When no keystore is configured, the release build falls back to debug signing so
// contributors can still build it locally.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseStorePath: String? =
    keystoreProps.getProperty("storeFile") ?: System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.daymark.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.daymark.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // "network" flavor dimension (see docs/COMPANION_PHONE_2B.md §0): the default `foss`
    // flavor is the flagship, offline-only build — unchanged, no INTERNET permission, no
    // network code reachable. `sync` is the separate, opt-in "Daymark Sync" flavor; it alone
    // gets INTERNET (declared only in src/sync/AndroidManifest.xml) and the Companion sync
    // crypto. Network/sync/portal code must live under src/sync/ ONLY, so `foss` can never
    // reference it — enforced structurally by Gradle source sets, not by convention.
    flavorDimensions += "network"
    productFlavors {
        create("foss") {
            dimension = "network"
        }
        create("sync") {
            dimension = "network"
            applicationIdSuffix = ".sync"
            versionNameSuffix = "-sync"
            // JNA (via lazysodium-android) binds native methods reflectively — R8 needs
            // extra keep rules or a minified release build can break at runtime instead of
            // compile time. Only this flavor pulls in JNA, so only it needs the rules.
            proguardFile("proguard-rules-sync.pro")
        }
    }

    signingConfigs {
        create("release") {
            if (releaseStorePath != null) {
                storeFile = file(releaseStorePath)
                storePassword = keystoreProps.getProperty("storePassword") ?: System.getenv("KEYSTORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("keyAlias") ?: System.getenv("KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (releaseStorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // MigrationTestHelper loads the exported schemas from the androidTest APK's assets, so the
    // schema directory has to be an androidTest asset source.
    //
    // Room 2.7.0+ registers this automatically, so as of 2.8.4 this line is belt-and-braces —
    // it points at the same directory the plugin would add. Kept deliberately: nothing in CI
    // RUNS the instrumented tests (they are only compiled), so if the automatic registration
    // ever changed, the failure would be a silent "Cannot find the schema file in the assets
    // folder" at runtime on someone's machine rather than a red build here.
    sourceSets.getByName("androidTest") {
        assets.srcDir("$projectDir/schemas")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Export Room schemas so future migrations can be tested and reviewed.
//
// Configured through Room's own Gradle plugin rather than the raw
// `ksp { arg("room.schemaLocation", ...) }`, so the directory is a **declared Gradle task
// input/output** instead of a path the processor writes to blind — up-to-date checks and the
// build cache track it. That distinction is not academic: adding v13 was the first time this repo
// had to *create* a schema during a build rather than read one from the tree, and that build died
// on a zero-byte 13.json (`IllegalStateException: Empty schema file`).
//
// One global directory is correct here, NOT one per variant: the single @Database lives in
// src/main, so all four variants (foss/sync x debug/release) export identical schemas. Room only
// calls for per-variant directories when the schemas actually differ.
room {
    schemaDirectory("$projectDir/schemas")
}

// Room 2.8's schema-bundle classes (androidx.room.migration.bundle.*) were generated by a NEWER
// kotlinx-serialization compiler plugin than this project's 1.7.3 runtime, where
// GeneratedSerializer.typeParametersSerializers() is still abstract. Running Room's processor
// against 1.7.3 therefore dies with:
//
//   AbstractMethodError: androidx.room.migration.bundle.FieldBundle$$serializer does not define
//   or inherit an implementation of ... typeParametersSerializers()
//
// Raise the serialization RUNTIME on the annotation-processor classpath only. The app deliberately
// stays on 1.7.3: every release above it requires Kotlin >= 2.1 to *generate* serializers and this
// project is on 2.0.21, so bumping it app-wide would break our own @Serializable classes. That
// requirement is about code generation, not about a processor's runtime, which is why scoping it
// to `ksp*` is both sufficient and safe.
//
// This is load-bearing for the Room upgrade; if it is ever removed, KSP fails immediately and
// loudly rather than subtly. The exported schemas are additionally guarded in CI by
// `git diff --exit-code -- app/schemas`, so a serialization change that altered schema output
// could not pass unnoticed either.
configurations.matching { it.name.startsWith("ksp") }.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.nayuki.qrcodegen)

    // Companion sync crypto (Milestone 2b) — the `sync` flavor only; see
    // docs/COMPANION_PHONE_2B.md. lazysodium-android's own POM depends on the plain (desktop)
    // jna jar, not the Android-native `aar` variant, so that transitive is excluded and the
    // `aar` artifact is requested explicitly instead (mirrors lazysodium-android's own README).
    // Deliberately NOT `libs.jna) { artifact { type = "aar" } }` — combining a version-catalog
    // accessor with an `artifact {}` block resolves transitive deps instead of the plain
    // artifact (https://github.com/gradle/gradle/issues/21267); the string form below is the
    // confirmed-working `@aar` shorthand, with the version still pulled from the catalog.
    "syncImplementation"(project(":sync-crypto"))
    "syncImplementation"(libs.lazysodium.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    "syncImplementation"("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
