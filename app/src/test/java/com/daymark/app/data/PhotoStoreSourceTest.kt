package com.daymark.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source assertions on the photo pipeline.
 *
 * ## Why this is a source test and not a behavioural one
 *
 * The property that matters is *"a photo's original bytes never reach storage"*, and the honest way
 * to test it is to import a JPEG carrying GPS tags and assert the stored file has none. That needs
 * a real `BitmapFactory` and a real `ExifInterface`, which means an instrumented test on a device —
 * and there is no device in this project's CI, so such a test would be written, never run, and
 * quietly believed. A source assertion that actually executes is worth more than an instrumented one
 * that does not.
 *
 * So this checks the structural precondition instead, which is the thing a future change would
 * break: **[PhotoStore] must decode and re-encode on every path into storage.** That is a weaker
 * claim than "no GPS in the output", and the gap is stated rather than papered over — an
 * instrumented test asserting the output directly is listed in `docs/PLAN_2026-08-NEXT.md` as
 * needing a machine with an SDK.
 *
 * ## The specific change this exists to stop
 *
 * "We already have the bytes — why decode and re-encode? Just copy the file." It is faster, it
 * preserves image quality, it deletes code, it is the obvious review-approved improvement, and it
 * silently reinstates GPS coordinates, capture timestamps and device identifiers into every photo
 * in the journal and every backup made afterwards. Nothing about that change looks like a privacy
 * regression at the diff level. This test is the thing that says so out loud.
 */
class PhotoStoreSourceTest {

    private companion object {
        const val REL = "app/src/main/java/com/daymark/app/data/PhotoStore.kt"

        /**
         * Finds the file by walking up from the working directory, because Gradle runs unit tests
         * with `user.dir` set to the module directory while some IDEs use the repo root. Failing to
         * find it is a test failure, never a silent pass — an assertion suite that cannot locate its
         * subject is exactly the shape of a guard that reports green forever.
         */
        fun sourceOf(rel: String): String {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            val tail = rel.substringAfter("app/")
            while (dir != null) {
                for (candidate in listOf(File(dir, rel), File(dir, tail))) {
                    if (candidate.isFile) return candidate.readText()
                }
                dir = dir.parentFile
            }
            throw AssertionError("could not find $rel from ${System.getProperty("user.dir")}")
        }
    }

    /** One declaration: its name, its parameter list, and its text up to the next declaration. */
    private data class Decl(val name: String, val signature: String, val body: String) {
        val guarded: Boolean get() = body.contains("isSafeName")
        val takesPhotoName: Boolean get() = signature.contains("relPath") && name != "isSafeName"
    }

    /**
     * Every `fun` in the source, sliced at real declaration boundaries.
     *
     * A LIST, not a map keyed by name, and both details are scars:
     *
     *  - boundaries come from a regex over `fun name(` at any indentation and with any modifiers,
     *    not from cutting at the literal `"\n    fun "`. That literal does not match
     *    `    private fun isSafeName`, so an unguarded expression-bodied method ran past the guard's
     *    own declaration and matched its name.
     *  - keyed by name, the two `fileFor` OVERLOADS collided and the guarded companion one silently
     *    replaced the unguarded instance one — so the exact half of the bug this test was written
     *    for reported clean.
     *
     * Both were caught by mutating the source and finding the test unmoved, which is the only reason
     * to ever write the mutation down.
     */
    private fun declarationBodies(src: String): List<Decl> {
        val decls = Regex("""\bfun\s+(\w+)\s*\(([^)]*)\)""").findAll(src).toList()
        return decls.mapIndexed { i, m ->
            val end = if (i + 1 < decls.size) decls[i + 1].range.first else src.length
            Decl(m.groupValues[1], m.groupValues[2], src.substring(m.range.last, end))
        }
    }

    private val source = sourceOf(REL)

    /** The file with comments and KDoc removed — claims must hold against code, not prose. */
    private val code = source
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    @Test
    fun `the source was found and stripped to something that is still code`() {
        // Guards every assertion below: an empty subject would make them all pass.
        assertTrue("source is implausibly short", source.length > 2000)
        assertTrue(code.contains("class PhotoStore"))
        assertTrue(code.contains("fun copyFromUri"))
        assertTrue(code.contains("fun writeBytes"))
        // The stripper must actually strip, or the "no byte copy" checks below would be reading
        // prose that happens to mention copying.
        assertTrue("KDoc survived stripping", source.contains("/**") && !code.contains("/**"))
    }

    @Test
    fun `no path into storage copies source bytes through`() {
        /*
         * Every one of these is a way to put untouched input bytes on disk. `writeBytes` on a File
         * is the one that was actually there before the strip was made deliberate: the backup
         * restore path wrote whatever the JSON carried, straight into the photo directory.
         */
        for (shortcut in listOf(
            "copyTo(",
            "Files.copy",
            "FileUtils.copy",
            "transferTo(",
            "transferFrom(",
            "readBytes().let",
        )) {
            assertFalse(
                "PhotoStore uses $shortcut — a photo's original bytes must never reach storage, " +
                    "because they carry GPS, capture time and device identity. Decode and re-encode.",
                code.contains(shortcut),
            )
        }
    }

    @Test
    fun `writeBytes re-encodes rather than writing its argument`() {
        // The single most likely regression, because "write these bytes to this file" is precisely
        // what the method name promises and precisely what it must not do.
        // Bounded by the next declaration, not by the next KDoc block — `code` has had its comments
        // removed, so a `/**` delimiter finds nothing and `substringBefore` silently returns the
        // whole rest of the file. The assertions below still passed that way, which is precisely
        // the sort of accident that leaves a test looking stricter than it is.
        val body = code.substringAfter("fun writeBytes").substringBefore("\n    fun ")
        assertTrue("writeBytes body not found", body.length in 100..1500)
        assertFalse(
            "writeBytes writes its bytes straight to the file — backup restore is an import path " +
                "like any other, and a pre-strip backup carries full EXIF",
            Regex("File\\([^)]*\\)\\s*\\.writeBytes\\(").containsMatchIn(body),
        )
        assertTrue("writeBytes does not decode", body.contains("decodeDownsampled"))
        assertTrue("writeBytes does not re-encode", body.contains("writeStripped"))
    }

    @Test
    fun `both import paths run through the same single re-encode`() {
        // One writer means one place to audit and one place that can be wrong.
        assertEquals(
            "there should be exactly one bitmap-compressing writer in PhotoStore",
            1,
            Regex("\\.compress\\(").findAll(code).count(),
        )
        for (entry in listOf("fun copyFromUri", "fun writeBytes")) {
            val body = code.substringAfter(entry).take(900)
            assertTrue("$entry does not reach writeStripped", body.contains("writeStripped"))
        }
    }

    @Test
    fun `orientation is read before the decode that destroys it`() {
        // Reading EXIF from a decoded Bitmap is impossible — it has no tags — so getting this order
        // wrong does not fail loudly, it just permanently loses which way was up.
        val body = code.substringAfter("fun copyFromUri").substringBefore("fun delete")
        val readsExif = body.indexOf("readOrientation")
        val decodes = body.indexOf("decodeDownsampled")
        assertTrue("copyFromUri does not read orientation", readsExif >= 0)
        assertTrue("copyFromUri does not decode", decodes >= 0)
        assertTrue("orientation is read after the decode", readsExif < decodes)
    }

    @Test
    fun `the pipeline keeps no tag of its own`() {
        // Writing an orientation tag back would defeat the point: the whole design is that the
        // rotation lands in the pixels and the stored file carries no metadata at all.
        assertFalse(
            "PhotoStore sets an EXIF attribute — the rotation belongs in the pixels, not a tag",
            code.contains("setAttribute"),
        )
        assertFalse("PhotoStore saves EXIF", code.contains("saveAttributes"))
    }

    @Test
    fun `every door that takes a photo name checks it first`() {
        /*
         * THE BUG THIS EXISTS FOR. The guard was present and applied to readBytes, writeBytes and
         * exists — but not to delete, and not to either fileFor. Four of six doors. A backup naming
         * a photo it does not contain ("../../databases/daymark.db", no matching blob) therefore
         * never met the one guarded door on the import path, was written verbatim onto the entry,
         * and was resolved by the next ordinary swipe-delete into <filesDir>/../databases/daymark.db
         * — unlinking the journal, silently, with no OS backup to restore from.
         *
         * Counting the doors is the point. A test that checked "delete is guarded" would have gone
         * green the day someone added a seventh method.
         */
        val doors = declarationBodies(code).filter { it.takesPhotoName }

        assertTrue("found no doors — the pattern stopped matching", doors.size >= 6)
        assertTrue("delete must be a door under scrutiny", doors.any { it.name == "delete" })
        // BOTH fileFor overloads, counted separately — one of them was the hole.
        assertEquals("both fileFor overloads must be checked", 2, doors.count { it.name == "fileFor" })

        val unguarded = doors.filterNot { it.guarded }.map { "${it.name}(${it.signature})" }
        assertEquals(
            "these take a photo name and never ask isSafeName — exactly the shape of the traversal " +
                "bug: a guard applied to most of the doors is not a partial defence, it is the " +
                "absence of one plus the belief that you have one",
            emptyList<String>(),
            unguarded,
        )
    }

    @Test
    fun `the every-door check is not vacuous — it catches each half of the original bug`() {
        /*
         * Both mutations are the real thing, and the second one caught a hole in THIS test: the
         * body slicer originally cut at the literal "\n    fun ", which does not match
         * "    private fun isSafeName", so an unguarded expression-bodied fileFor swallowed the
         * guard's own DECLARATION and its name matched. A test for a partial guard that was itself
         * partial.
         */
        val guardless = code
            .replace("if (relPath.isNullOrEmpty() || !isSafeName(relPath)) return", "if (relPath.isNullOrEmpty()) return")
        assertFalse("mutation did not land", guardless.contains("|| !isSafeName(relPath)) return"))
        assertTrue(
            "an unguarded delete must be detected",
            declarationBodies(guardless).any { it.name == "delete" && !it.guarded },
        )

        val looseFileFor = code.replace(
            Regex("fun fileFor\\(relPath: String\\): File\\? = [^\n]*"),
            "fun fileFor(relPath: String): File = File(dir, relPath)",
        )
        assertFalse("mutation did not land", looseFileFor.contains("fun fileFor(relPath: String): File?"))
        assertTrue(
            "an unguarded fileFor must be detected — this is the one that slipped through twice, " +
                "first past a bad body slice and then past an overload collision",
            declarationBodies(looseFileFor).any { it.name == "fileFor" && !it.guarded },
        )
    }

    @Test
    fun `it reads exactly one EXIF tag, and that tag is orientation`() {
        // Reading anything else would mean something in this app has a use for it, and there is no
        // use for capture location in a journal that does not have a location feature.
        val tags = Regex("TAG_[A-Z_]+").findAll(code).map { it.value }.toSet()
        assertEquals(setOf("TAG_ORIENTATION"), tags)
    }
}
