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
    fun `it reads exactly one EXIF tag, and that tag is orientation`() {
        // Reading anything else would mean something in this app has a use for it, and there is no
        // use for capture location in a journal that does not have a location feature.
        val tags = Regex("TAG_[A-Z_]+").findAll(code).map { it.value }.toSet()
        assertEquals(setOf("TAG_ORIENTATION"), tags)
    }
}
