plugins {
    // 2.3.21 because that is what Ktor 3.5.2 is itself built with. Ktor 3.5.2 is the fix for
    // CVE-2025-58056 / CVE-2025-67735 / CVE-2026-42587 (it pins Netty 4.2.16.Final, above all
    // three), and on Kotlin 2.1.0 it does not merely fail to compile — it crashes the compiler
    // with an internal error, which is what 2.3-era .kotlin_metadata looks like to a 2.1 reader.
    // So the CVE fix and the language bump are one change; they cannot be landed separately.
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.daymark.companion"
version = "0.0.1"

// Netty 4.2.16.Final rides in transitively — verified in Ktor 3.5.2's own version catalog,
// not inferred. That closes the bare-LF chunk terminator, CRLF-in-request-URI and
// decompression-bomb advisories that 3.0.3's Netty 4.1.116.Final was exposed to.
val ktorVersion = "3.5.2"
// 1.5.12 was the exact top affected version of CVE-2024-12798 (Janino EL injection -> RCE) and
// CVE-2024-12801 (SaxEventRecorder SSRF). Minimum fix is 1.5.13.
val logbackVersion = "1.5.13"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    // The ONE deliberate outbound exception: owner-configured SMTP for therapist invite /
    // notification links (OFF by default). Jakarta Mail 2.1 reference impl (angus-mail),
    // EPL-2.0 / EDL-1.0 — license-clean, JVM-only, no effect on the web CSP. Pulls
    // jakarta.mail-api + jakarta.activation-api transitively.
    implementation("org.eclipse.angus:angus-mail:2.0.3")
    // Pure-JVM Argon2id (Argon2BytesGenerator) + BLAKE2b for hashing the low-entropy
    // *authenticating* secrets (invite code, TOTP secret, session/rel tokens) AT REST, and
    // for deriving opaque routing ids from inbox tokens. This never touches the E2EE key
    // hierarchy (that stays client-side); the server holds nothing that decrypts. MIT-style
    // license, no native libs. See docs/COMPANION_SECURITY.md §4/§5.
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")   // 1.79 fell inside CVE-2026-0636 (LDAP injection, 1.74-1.83)
    // ktor-server-forwarded-header is intentionally NOT pulled in yet: the trust-none
    // default means we do not honour X-Forwarded-* until the sync milestone wires a
    // pinned-proxy allowlist (see docs/COMPANION_DEPLOYMENT.md).

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testImplementation(kotlin("test"))
    // Embedded fake SMTP server for headless end-to-end mailer tests (Apache-2.0, test-only,
    // never shipped in the shadowJar).
    testImplementation("com.icegreen:greenmail:2.1.2")
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
