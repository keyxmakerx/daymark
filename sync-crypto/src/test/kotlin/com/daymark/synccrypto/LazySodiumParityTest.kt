package com.daymark.synccrypto

import com.goterl.lazysodium.LazySodium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * The cross-artifact parity invariant, as a test instead of a comment.
 *
 * SyncCrypto is COMPILED against lazysodium-java's copy of the shared `com.goterl.lazysodium.*`
 * types but RUNS on lazysodium-android's copy inside the app (see SyncCrypto's KDoc). For years
 * that linkage was guarded by a comment in `gradle/libs.versions.toml` asserting the two artifacts
 * expose the same API when pinned to the same version. That assertion was MEASURED FALSE at 5.1.0:
 * the android artifact exposed 328 public methods on `LazySodium` and the java artifact 259 — the
 * 69-method gap being the entire Ristretto255 surface. A comment cannot enforce an invariant;
 * this test can, and it checks the property the app actually depends on, in three layers:
 *
 *  1. Every `com.goterl.*` class, method and field reference in *this module's own compiled
 *     bytecode* resolves against the android artifact — the exact linkage the phone performs.
 *  2. For every class present in BOTH artifacts, the java copy's public API is a subset of the
 *     android copy's — so future SyncCrypto code, written and host-tested against java, cannot
 *     quietly reference something the phone lacks.
 *  3. The Ristretto255 surface (what CPace needs, and precisely what java 5.1.0 was missing) is
 *     present on both sides.
 *
 * The android AAR is resolved by Gradle from the SAME version-catalog entry as the runtime
 * dependency and handed in via the `daymark.lazysodiumAndroidAar` system property; a missing
 * property is a loud failure, never a skip — a parity test that silently skips is the same
 * hazard as the comment it replaced. Every layer carries a non-vacuity floor, because this
 * repository's recurring bug shape is a check that goes green by looking where the change
 * cannot appear.
 */
class LazySodiumParityTest {

    // Method or field: name + JVM descriptor. Two methods differing only in return type are
    // distinct JVM members, so the descriptor participates in identity on purpose.
    private data class Member(val name: String, val descriptor: String)

    private data class Ref(val owner: String, val name: String, val descriptor: String)

    private class ParsedClass(
        val binaryName: String,
        val superName: String?,
        val interfaces: List<String>,
        /** public|protected methods (the only ones another module can legally link against). */
        val methods: Set<Member>,
        /** public|protected fields. */
        val fields: Set<Member>,
        val methodRefs: Set<Ref>,
        val fieldRefs: Set<Ref>,
        val classRefs: Set<String>,
    )

    companion object {
        private const val ACC_PUBLIC = 0x0001
        private const val ACC_PROTECTED = 0x0004
        private const val GOTERL = "com/goterl/lazysodium"

        private lateinit var javaClasses: Map<String, ParsedClass>
        private lateinit var androidClasses: Map<String, ParsedClass>
        private lateinit var ownClasses: Map<String, ParsedClass>
        private lateinit var javaJar: File
        private lateinit var androidAar: File

        @JvmStatic
        @BeforeClass
        fun loadArtifacts() {
            javaJar = File(
                LazySodium::class.java.protectionDomain.codeSource.location.toURI(),
            )
            val aarPath = System.getProperty("daymark.lazysodiumAndroidAar")
                ?: throw AssertionError(
                    "daymark.lazysodiumAndroidAar is not set. The test task in " +
                        "sync-crypto/build.gradle.kts resolves the android AAR from the version " +
                        "catalog and passes its path here; without it this test cannot compare " +
                        "anything, and it fails rather than skips so that a broken wiring is " +
                        "never mistaken for a verified invariant.",
                )
            androidAar = File(aarPath)
            require(androidAar.isFile) { "android AAR does not exist: $androidAar" }

            javaClasses = parseZipBytes(javaJar.readBytes())
            androidClasses = parseAarClasses(androidAar)
            ownClasses = parseCodeSource(
                File(SyncCrypto::class.java.protectionDomain.codeSource.location.toURI()),
            )
        }

        // ---- container loading -------------------------------------------------------------

        private fun parseAarClasses(aar: File): Map<String, ParsedClass> {
            ZipFile(aar).use { zip ->
                val entry = zip.getEntry("classes.jar")
                    ?: throw AssertionError("$aar has no classes.jar — not an AAR?")
                return parseZipBytes(zip.getInputStream(entry).readBytes())
            }
        }

        private fun parseZipBytes(zipBytes: ByteArray): Map<String, ParsedClass> {
            val out = mutableMapOf<String, ParsedClass>()
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (!entry.name.endsWith(".class") || entry.name.endsWith("module-info.class")) continue
                    val parsed = parseClass(zin.readBytes())
                    out[parsed.binaryName] = parsed
                }
            }
            return out
        }

        /** The module's own output is a classes directory under Gradle, a jar elsewhere. */
        private fun parseCodeSource(location: File): Map<String, ParsedClass> {
            if (location.isFile) return parseZipBytes(location.readBytes())
            val out = mutableMapOf<String, ParsedClass>()
            location.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach {
                val parsed = parseClass(it.readBytes())
                out[parsed.binaryName] = parsed
            }
            return out
        }

        // ---- class-file parsing ------------------------------------------------------------

        // A minimal, dependency-free reader of the parts of the class-file format this test
        // needs: constant pool (for names and outbound references), superclass/interfaces,
        // and member tables. Attribute bodies and code are skipped wholesale.
        private fun parseClass(bytes: ByteArray): ParsedClass {
            val d = DataInputStream(ByteArrayInputStream(bytes))
            check(d.readInt() == -0x35014542) { "not a class file (bad magic)" } // 0xCAFEBABE
            d.readUnsignedShort() // minor
            d.readUnsignedShort() // major — irrelevant here; the parser reads no versioned parts

            val cpCount = d.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(cpCount)
            val classNameIdx = IntArray(cpCount)
            val natName = IntArray(cpCount)
            val natDesc = IntArray(cpCount)
            // tag (9=Fieldref, 10=Methodref, 11=InterfaceMethodref), class index, name-and-type index
            val rawRefs = ArrayList<IntArray>()

            var i = 1
            while (i < cpCount) {
                when (val tag = d.readUnsignedByte()) {
                    1 -> utf8[i] = d.readUTF() // CONSTANT_Utf8 has readUTF's exact layout
                    7 -> classNameIdx[i] = d.readUnsignedShort()
                    9, 10, 11 -> rawRefs.add(intArrayOf(tag, d.readUnsignedShort(), d.readUnsignedShort()))
                    12 -> {
                        natName[i] = d.readUnsignedShort()
                        natDesc[i] = d.readUnsignedShort()
                    }
                    8, 16, 19, 20 -> skip(d, 2) // String, MethodType, Module, Package
                    15 -> skip(d, 3) // MethodHandle
                    3, 4 -> skip(d, 4) // Integer, Float
                    17, 18 -> skip(d, 4) // Dynamic, InvokeDynamic
                    5, 6 -> {
                        skip(d, 8) // Long and Double occupy two constant-pool slots
                        i++
                    }
                    else -> throw AssertionError("unknown constant-pool tag $tag")
                }
                i++
            }

            d.readUnsignedShort() // access flags of the class itself
            val thisClass = utf8[classNameIdx[d.readUnsignedShort()]]
                ?: throw AssertionError("this_class resolves to no Utf8")
            val superIdx = d.readUnsignedShort()
            val superName = if (superIdx == 0) null else utf8[classNameIdx[superIdx]]
            val interfaces = (0 until d.readUnsignedShort()).map {
                utf8[classNameIdx[d.readUnsignedShort()]]!!
            }

            fun readMembers(): Set<Member> {
                val out = mutableSetOf<Member>()
                repeat(d.readUnsignedShort()) {
                    val access = d.readUnsignedShort()
                    val name = utf8[d.readUnsignedShort()]!!
                    val desc = utf8[d.readUnsignedShort()]!!
                    repeat(d.readUnsignedShort()) {
                        d.readUnsignedShort() // attribute name
                        skip(d, d.readInt())
                    }
                    if (access and (ACC_PUBLIC or ACC_PROTECTED) != 0) out.add(Member(name, desc))
                }
                return out
            }

            val fields = readMembers()
            val methods = readMembers()

            val methodRefs = mutableSetOf<Ref>()
            val fieldRefs = mutableSetOf<Ref>()
            for ((tag, classIdx, natIdx) in rawRefs.map { Triple(it[0], it[1], it[2]) }) {
                val owner = utf8[classNameIdx[classIdx]] ?: continue
                if (owner.startsWith("[")) continue // array pseudo-classes
                val ref = Ref(owner, utf8[natName[natIdx]]!!, utf8[natDesc[natIdx]]!!)
                if (tag == 9) fieldRefs.add(ref) else methodRefs.add(ref)
            }
            val classRefs = (1 until cpCount)
                .filter { classNameIdx[it] != 0 }
                .mapNotNull { utf8[classNameIdx[it]] }
                .filterNot { it.startsWith("[") }
                .toSet()

            return ParsedClass(thisClass, superName, interfaces, methods, fields, methodRefs, fieldRefs, classRefs)
        }

        private fun skip(d: DataInputStream, n: Int) {
            var remaining = n
            while (remaining > 0) {
                val skipped = d.skipBytes(remaining)
                if (skipped <= 0) throw AssertionError("truncated class file")
                remaining -= skipped
            }
        }

        // ---- resolution against the android hierarchy --------------------------------------

        /**
         * JVM-style member resolution: the owner's hierarchy (superclasses and interfaces) is
         * walked inside the android artifact; if the walk escapes it — java.lang.Object, JNA —
         * the member is looked up reflectively on this JVM's own classpath, which the app
         * shares for those classes. Returns null when resolved, else a human-readable reason.
         */
        private fun unresolvable(owner: String, member: Member, isField: Boolean): String? {
            val queue = ArrayDeque(listOf(owner))
            val seen = mutableSetOf<String>()
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!seen.add(current)) continue
                val parsed = androidClasses[current]
                if (parsed == null) {
                    if (current == owner) return "class $owner is absent from the android artifact"
                    if (reflectivelyHas(current, member, isField)) return null
                    continue
                }
                val members = if (isField) parsed.fields else parsed.methods
                if (member in members) return null
                parsed.superName?.let { queue.add(it) }
                queue.addAll(parsed.interfaces)
            }
            return "${if (isField) "field" else "method"} ${member.name}${member.descriptor} " +
                "not found on $owner or any supertype in the android artifact"
        }

        private fun reflectivelyHas(binaryName: String, member: Member, isField: Boolean): Boolean {
            val cls = try {
                Class.forName(binaryName.replace('/', '.'), false, LazySodium::class.java.classLoader)
            } catch (_: Throwable) {
                return false
            }
            return if (isField) {
                (cls.fields + cls.declaredFields).any { it.name == member.name }
            } else {
                cls.methods.any { m ->
                    m.name == member.name &&
                        member.descriptor ==
                        m.parameterTypes.joinToString("", "(", ")") { descriptorOf(it) } +
                        descriptorOf(m.returnType)
                }
            }
        }

        private fun descriptorOf(c: Class<*>): String = when {
            c === Void.TYPE -> "V"
            c === Int::class.javaPrimitiveType -> "I"
            c === Long::class.javaPrimitiveType -> "J"
            c === Boolean::class.javaPrimitiveType -> "Z"
            c === Byte::class.javaPrimitiveType -> "B"
            c === Char::class.javaPrimitiveType -> "C"
            c === Short::class.javaPrimitiveType -> "S"
            c === Float::class.javaPrimitiveType -> "F"
            c === Double::class.javaPrimitiveType -> "D"
            c.isArray -> "[" + descriptorOf(c.componentType!!)
            else -> "L" + c.name.replace('.', '/') + ";"
        }
    }

    // ---- the invariants ------------------------------------------------------------------

    @Test
    fun `both artifacts are the same lazysodium version`() {
        val versionOf = { f: File ->
            Regex("""lazysodium-(?:java|android)-(\d+\.\d+\.\d+)""").find(f.name)?.groupValues?.get(1)
                ?: throw AssertionError("cannot read a lazysodium version from filename: $f")
        }
        assertEquals(
            "the two artifacts must come from one catalog version — if this fails the " +
                "sync-crypto test wiring stopped resolving the AAR from the shared version ref",
            versionOf(javaJar),
            versionOf(androidAar),
        )
    }

    @Test
    fun `every lazysodium reference in this module's bytecode resolves on the android artifact`() {
        val failures = mutableListOf<String>()
        var checked = 0
        for (cls in ownClasses.values) {
            for (ref in cls.classRefs.filter { it.startsWith(GOTERL) }) {
                checked++
                if (ref !in androidClasses) {
                    failures.add("${cls.binaryName}: references class $ref, absent from android artifact")
                }
            }
            for (ref in cls.methodRefs.filter { it.owner.startsWith(GOTERL) }) {
                checked++
                unresolvable(ref.owner, Member(ref.name, ref.descriptor), isField = false)?.let {
                    failures.add("${cls.binaryName}: $it")
                }
            }
            for (ref in cls.fieldRefs.filter { it.owner.startsWith(GOTERL) }) {
                checked++
                unresolvable(ref.owner, Member(ref.name, ref.descriptor), isField = true)?.let {
                    failures.add("${cls.binaryName}: $it")
                }
            }
        }
        // Non-vacuity. Measured at the time of writing: 13 references — nine distinct
        // LazySodium method refs (pwhash, kdf, both aead directions, three sign ops, sha256,
        // randombytes), the PwHash.Alg enum field ref, and the owner class refs. The int
        // constants (SALTBYTES and friends) do NOT appear: kotlinc inlines Java static final
        // primitives, so only non-constant references survive to the pool. If this count
        // collapses below the floor, the test stopped looking at the real module output.
        assertTrue(
            "only $checked com.goterl.* references found in the module bytecode; SyncCrypto " +
                "alone contributes 13+ — the code-source path is wrong, not the code small",
            checked >= 10,
        )
        assertTrue(
            "compiled-against-java references that the android artifact cannot satisfy:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `every java class exists on android except the four known host-side ones`() {
        // Without this, a class VANISHING from the android artifact would escape the subset
        // check below entirely — it simply stops being "shared". The allowlist is exact, not
        // a pattern: growing it is a conscious human decision at the next version bump.
        // Measured at 5.2.0: java 109 classes, android 108, shared 105.
        val expectedJavaOnly = setOf(
            "$GOTERL/LazySodiumJava",
            "$GOTERL/SodiumJava",
            "$GOTERL/utils/LibraryLoader",
            "$GOTERL/utils/LibraryLoader\$Mode",
        )
        val javaOnly = javaClasses.keys.filter { it.startsWith(GOTERL) }.toSet() -
            androidClasses.keys
        assertEquals(
            "the set of java-only classes changed — either a class disappeared from the " +
                "android artifact (dangerous: host-tested code may reference it) or upstream " +
                "added a host-side type (update the allowlist deliberately)",
            expectedJavaOnly,
            javaOnly,
        )
    }

    @Test
    fun `on every shared class the java artifact's public api is a subset of the android one`() {
        val shared = javaClasses.keys.intersect(androidClasses.keys).filter { it.startsWith(GOTERL) }
        assertTrue(
            "only ${shared.size} classes are shared between the artifacts — at 5.2.0 the " +
                "com.goterl.lazysodium tree is far larger, so one of the containers was not read",
            shared.size >= 40,
        )
        val violations = mutableListOf<String>()
        for (name in shared.sorted()) {
            val java = javaClasses.getValue(name)
            val android = androidClasses.getValue(name)
            (java.methods - android.methods).forEach {
                violations.add("$name: method ${it.name}${it.descriptor} exists in java, not android")
            }
            (java.fields - android.fields).forEach {
                violations.add("$name: field ${it.name} (${it.descriptor}) exists in java, not android")
            }
        }
        assertTrue(
            "the java artifact exposes API the android artifact lacks — code compiled and " +
                "host-tested against java would break on the phone:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `the ristretto255 surface exists on both sides`() {
        // The exact hole this test exists because of: lazysodium-java 5.1.0 shipped ZERO
        // Ristretto255 entries while lazysodium-android 5.1.0 shipped the full surface, and a
        // comment claiming same-version parity kept everyone from looking. CPace (plan §3.7)
        // needs this surface on both the host-test side and the phone side.
        val ristretto = "$GOTERL/interfaces/Ristretto255"
        for ((label, classes) in mapOf("java" to javaClasses, "android" to androidClasses)) {
            val cls = classes[ristretto]
                ?: throw AssertionError("$label artifact has no $ristretto — the 5.1.0 hole is back")
            assertTrue(
                "$label artifact's Ristretto255 has only ${cls.methods.size} methods",
                cls.methods.size >= 5,
            )
        }
    }

    @Test
    fun `the parser sees the real surface, not an empty shell`() {
        // Floors, not exact counts: exact counts churn on every upstream release, floors only
        // fail when a container stops being read — which is the failure they exist to catch.
        // At 5.2.0, measured: java 328 public methods on LazySodium, android 328 (both now
        // carry ristretto); the goterl tree is ~90 classes a side.
        val base = "$GOTERL/LazySodium"
        assertTrue("java LazySodium method count collapsed", javaClasses.getValue(base).methods.size >= 250)
        assertTrue("android LazySodium method count collapsed", androidClasses.getValue(base).methods.size >= 250)
        assertTrue("java artifact class count collapsed", javaClasses.keys.count { it.startsWith(GOTERL) } >= 60)
        assertTrue("android artifact class count collapsed", androidClasses.keys.count { it.startsWith(GOTERL) } >= 60)
        assertTrue("own module output is missing", ownClasses.values.any { it.binaryName.endsWith("SyncCrypto") })
    }
}
