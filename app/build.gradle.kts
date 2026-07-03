import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Export Room schemas so future migrations can be tested and reviewed.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.androidx.room.ktx)
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
