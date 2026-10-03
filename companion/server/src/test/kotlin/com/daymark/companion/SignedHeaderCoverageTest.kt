package com.daymark.companion

import com.daymark.companion.auth.DeviceSignature
import io.ktor.http.HttpHeaders
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every request header the server reads is covered by a phone's signature ([DeviceSignature.SIGNED_HEADERS]),
 * or is named in [unsigned] with the files that read it and why a phone's request cannot be steered by
 * it (#186). Read from the server's source as text, so a header read added anywhere — a new route, a new
 * branch of an old one, a new file reading a header already named — fails this test until the header
 * is signed or its reason is written here.
 *
 * What the scan cannot see: a header read inside Ktor rather than in this code. Of the plugins the server
 * installs, Ktor's own are StatusPages, which answers exceptions, and ContentNegotiation, which reads
 * Accept only to choose how a response is written, never what a handler does; every body is read as raw
 * bytes (OwnerAuth's requestBody), which it passes through untouched.
 */
class SignedHeaderCoverageTest {

    private val root = File("src/main/kotlin")

    /**
     * The headers read that the signature does not cover: for each, the files that may read it, and why
     * a phone's request cannot be steered by it. Compared without regard to case, as HTTP compares them.
     */
    private val unsigned: Map<String, Pair<Set<String>, String>> = mapOf(
        "X-Device-Key" to (setOf(OWNER_AUTH) to SIGNATURE_OWN),
        "X-Device-Time" to (setOf(OWNER_AUTH) to SIGNATURE_OWN),
        "X-Device-Nonce" to (setOf(OWNER_AUTH) to SIGNATURE_OWN),
        "X-Device-Signature" to (setOf(OWNER_AUTH) to SIGNATURE_OWN),
        "Authorization" to (
            setOf(OWNER_AUTH) to
                "the console's token. A request that carries any of the signature's headers is judged by its signature " +
                "alone, and this header is not read for it"
            ),
        "Content-Length" to (
            setOf(OWNER_AUTH) to
                "frames the body, every byte of which the signature covers; read only to refuse a body larger than any " +
                "route takes, before the key is looked up"
            ),
        "Transfer-Encoding" to (setOf(OWNER_AUTH) to "a signed request that carries it is refused 411, whatever its key"),
        "X-Forwarded-For" to (
            setOf("com/daymark/companion/RequestClient.kt") to
                "the address a proxy on the operator's trusted list vouches for, which a proxy appends to by design, so no " +
                "signature could cover it. It chooses a rate-limit and lockout bucket and the address an audit row may " +
                "record, never what a handler does"
            ),
        "Host" to (
            setOf("com/daymark/companion/routes/RouteUrls.kt") to
                "read to build a link only when no public address is configured, which Config.fromEnv refuses in a " +
                "running server (#180); a proxy in front may rewrite it"
            ),
        "Cookie" to (
            SESSION_ROUTES to
                "a clinician's or practice member's session. Where a route takes the owner too, a request whose owner " +
                "credential passes is the owner's and no session is looked up for it. A cookie added to a phone's " +
                "request can at most spend the clinicians' rate budget for the phone's own address, and so have the " +
                "request refused, which whoever added it could as well do by dropping it"
            ),
        "X-CSRF-Token" to (SESSION_ROUTES to "checked with a session cookie, only for a request no owner credential passed"),
        "X-Stepup-Code" to (
            setOf("com/daymark/companion/routes/OrgRoutes.kt") to
                "a practice admin's second factor, on practice-console routes that take no owner credential"
            ),
    )

    /** Reads whose header is named by a value computed at run time: the file, the expression, and what it can name. */
    private val computed: Map<Pair<String, String>, String> = mapOf(
        (OWNER_AUTH to "it") to "each of DeviceSignature.HEADERS, the signature's own, to tell a signed request from another",
        (OWNER_AUTH to "name") to "each of DeviceSignature.SIGNED_HEADERS, whose values the signature covers",
    )

    private fun signed(name: String): Boolean = DeviceSignature.SIGNED_HEADERS.any { it.equals(name, ignoreCase = true) }

    private fun declared(read: HeaderRead): Boolean =
        if (read.name == null) {
            (read.file to read.expression) in computed
        } else {
            unsigned.entries.any { (name, rule) -> name.equals(read.name, ignoreCase = true) && read.file in rule.first }
        }

    @Test
    fun `every request header the server reads is signed, or named here with why a phone cannot be steered by it`() {
        val reads = HeaderReads.scan(root)
        // Controls: the scan sees every signed header read where a route reads it, in each form the routes use.
        for (name in DeviceSignature.SIGNED_HEADERS) {
            assertTrue(reads.any { it.name.equals(name, ignoreCase = true) }, "the scan sees $name read: it is signed because a route reads it")
        }
        assertTrue(reads.any { it.name == HttpHeaders.Authorization }, "control: the scan sees the owner gate read the token")

        val findings = reads.filterNot { read -> read.name?.let(::signed) == true || declared(read) }
        assertEquals(
            emptyList(),
            findings.map { "${it.file}:${it.line} reads ${it.name ?: "a header named by `${it.expression}`"}" },
            "a request header read that the signature does not cover: add it to DeviceSignature.SIGNED_HEADERS, " +
                "or say here why a phone's request cannot be steered by it",
        )

        // No reason is kept for a read that is gone.
        val stale = unsigned.flatMap { (name, rule) ->
            rule.first.filter { file -> reads.none { it.file == file && it.name.equals(name, ignoreCase = true) } }.map { file -> "$name in $file" }
        } + computed.keys.filter { (file, expression) -> reads.none { it.file == file && it.expression == expression } }.map { "${it.second} in ${it.first}" }
        assertEquals(emptyList(), stale, "a reason kept for a header read that is no longer there")
    }

    @Test
    fun `the scan sees a header read in each form, and only reads`() {
        val planted = """
            package planted

            private const val PLANTED_HEADER = "X-Planted-Constant"

            fun Route.planted() {
                get("/v1/planted") {
                    val a = call.request.headers["X-Planted-Literal"]
                    val b = call.request.headers.getAll(HttpHeaders.IfRange)
                    val c = call.request.header("X-Planted-Function")
                    val d = call.request.headers[PLANTED_HEADER]
                    val e = call.request
                        .headers
                        .get("X-Planted-Split")
                    val f = call.request.cookies["planted"]
                    val g = call.request.userAgent()
                    val h = call.request.headers.contains("X-Planted-Contains")
                    val i = call.request.headers[someName]
                    // val j = call.request.headers["X-Planted-In-A-Comment"]
                    call.response.header("X-Planted-Response", "a write, not a read")
                }
            }
        """.trimIndent()
        val reads = HeaderReads.scanText("planted/Planted.kt", planted, HeaderReads.constants(listOf(planted)))
        assertEquals(
            listOf(
                "X-Planted-Literal", "If-Range", "X-Planted-Function", "X-Planted-Constant", "X-Planted-Split",
                "Cookie", "User-Agent", "X-Planted-Contains", null,
            ).sortedBy { it.toString() },
            reads.map { it.name }.sortedBy { it.toString() },
        )
        assertEquals("someName", reads.single { it.name == null }.expression)
        // And the rule above finds each of them: none is signed, and none is named in this file.
        assertTrue(reads.none { read -> read.name?.let(::signed) == true || declared(read) }, "every planted read is a finding")
    }

    private companion object {
        const val OWNER_AUTH = "com/daymark/companion/auth/OwnerAuth.kt"
        const val SIGNATURE_OWN =
            "the signature's own: the key it names, the time and nonce its message holds, and the signature; a changed " +
                "one fails the signature or names a key that did not make it"
        val SESSION_ROUTES = setOf(
            "com/daymark/companion/routes/RelationRoutes.kt",
            "com/daymark/companion/routes/TherapistAuthRoutes.kt",
            "com/daymark/companion/routes/TherapistKeyRoutes.kt",
            "com/daymark/companion/routes/RelationshipEndingRoutes.kt",
            "com/daymark/companion/routes/OrgRoutes.kt",
        )
    }
}

/** A request header read in the source: the file (relative to the source root), the line, what names the header, and its name when that resolves. */
internal data class HeaderRead(val file: String, val line: Int, val expression: String, val name: String?)

/**
 * Request header reads in Kotlin source, found as text: `headers[...]`, `headers.get(...)`,
 * `headers.getAll(...)`, `headers.contains(...)` and `request.header(...)`, whose argument names the
 * header, and the Ktor helpers whose name says which header they read. A header named by a string, an
 * `HttpHeaders` constant or a `const val` resolves to its name; anything else is kept as the expression.
 * Comment lines are skipped.
 */
internal object HeaderReads {
    private val NAMED = listOf(
        Regex("""\bheaders\s*\[\s*([^\]]+?)\s*]"""),
        Regex("""\bheaders\s*\.\s*(?:get|getAll|contains)\s*\(\s*([^),]+?)\s*\)"""),
        Regex("""\brequest\s*\.\s*header\s*\(\s*([^),]+?)\s*\)"""),
    )

    private val IMPLIED = listOf(
        Regex("""\brequest\s*\.\s*cookies\b""") to "Cookie",
        Regex("""\brequest\s*\.\s*origin\s*\.\s*server(?:Host|Port)\b""") to "Host",
        Regex("""\brequest\s*\.\s*(?:host|port)\s*\(""") to "Host",
        Regex("""\brequest\s*\.\s*(?:contentType|contentCharset|isMultipart)\s*\(""") to "Content-Type",
        Regex("""\brequest\s*\.\s*contentLength\s*\(""") to "Content-Length",
        Regex("""\brequest\s*\.\s*isChunked\s*\(""") to "Transfer-Encoding",
        Regex("""\brequest\s*\.\s*(?:accept|acceptItems)\s*\(""") to "Accept",
        Regex("""\brequest\s*\.\s*(?:acceptEncoding|acceptEncodingItems)\s*\(""") to "Accept-Encoding",
        Regex("""\brequest\s*\.\s*(?:acceptLanguage|acceptLanguageItems)\s*\(""") to "Accept-Language",
        Regex("""\brequest\s*\.\s*(?:acceptCharset|acceptCharsetItems)\s*\(""") to "Accept-Charset",
        Regex("""\brequest\s*\.\s*userAgent\s*\(""") to "User-Agent",
        Regex("""\brequest\s*\.\s*ranges\s*\(""") to "Range",
        Regex("""\brequest\s*\.\s*cacheControl\s*\(""") to "Cache-Control",
        Regex("""\brequest\s*\.\s*authorization\s*\(""") to "Authorization",
        // Every header at once: never covered, so always a finding.
        Regex("""\bheaders\s*\.\s*(?:entries|names|forEach|toMap|flattenEntries|flattenForEach|filter)\b""") to "every header",
    )

    private val STRING = Regex("""^"([^"\\$]*)"$""")
    private val CONST = Regex("""\bconst\s+val\s+(\w+)\s*(?::\s*String\s*)?=\s*"([^"\\$]*)"""")

    /** Ktor's `HttpHeaders` constants, by property name. */
    private val HTTP_HEADERS: Map<String, String> =
        HttpHeaders::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == String::class.java }
            .associate { it.name.removePrefix("get") to it.invoke(HttpHeaders) as String }

    /** Every `const val NAME = "..."` in [sources]: a name given two values is left out, and does not resolve. */
    fun constants(sources: List<String>): Map<String, String> =
        sources.flatMap { text -> CONST.findAll(text).map { it.groupValues[1] to it.groupValues[2] } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.toSet().size == 1 }
            .mapValues { it.value.first() }

    fun scan(root: File): List<HeaderRead> {
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
        check(files.isNotEmpty()) { "no source under ${root.absolutePath}" }
        val texts = files.associateWith { it.readText() }
        val constants = constants(texts.values.toList())
        return files.flatMap { file -> scanText(file.relativeTo(root).invariantSeparatorsPath, texts.getValue(file), constants) }
    }

    fun scanText(file: String, text: String, constants: Map<String, String>): List<HeaderRead> {
        // Comment lines blanked, keeping the line count.
        val code = text.lines().joinToString("\n") { line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
        }
        fun lineOf(index: Int) = code.substring(0, index).count { it == '\n' } + 1
        val named = NAMED.flatMap { regex ->
            regex.findAll(code).map { m ->
                val expression = m.groupValues[1].trim()
                HeaderRead(file, lineOf(m.range.first), expression, resolve(expression, constants))
            }
        }
        val implied = IMPLIED.flatMap { (regex, name) ->
            regex.findAll(code).map { m -> HeaderRead(file, lineOf(m.range.first), m.value, name) }
        }
        return (named + implied).sortedBy { it.line }
    }

    private fun resolve(expression: String, constants: Map<String, String>): String? {
        STRING.matchEntire(expression)?.let { return it.groupValues[1] }
        if (expression.startsWith("HttpHeaders.")) return HTTP_HEADERS[expression.removePrefix("HttpHeaders.")]
        if (Regex("""^[\w.]+$""").matches(expression)) return constants[expression.substringAfterLast('.')]
        return null
    }
}
