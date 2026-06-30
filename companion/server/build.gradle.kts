plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.daymark.companion"
version = "0.0.1"

val ktorVersion = "3.0.3"
val logbackVersion = "1.5.12"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    // ktor-server-forwarded-header is intentionally NOT pulled in yet: the trust-none
    // default means we do not honour X-Forwarded-* until the sync milestone wires a
    // pinned-proxy allowlist (see docs/COMPANION_DEPLOYMENT.md).

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.daymark.companion.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("daymark-companion")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}
