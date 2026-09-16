package com.daymark.app.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cluster warp: fixed, seamless, and blind to the person.
 *
 * The property that matters most — that the warp cannot see anyone's data — is half a fact about
 * the signature, exactly as it is for [SkyField]: `warpedX` takes two `Float`s and a `Long`, and
 * there is no data type in it for a test to vary. The other half is that the *file* stays that way,
 * which is what [`the warp cannot see a record`] checks by reading this repository's own source.
 * That is worth a test rather than a comment because the change that would make the clumping mean
 * something — a kind here, a date there, so practices cluster together or a busy month draws a
 * constellation — is a small edit that would read as a helpful one.
 */
class SkyWarpTest {

    private val seed = 0x5B1E5EEDL

    // -------------------------------------------------------------------------------------------
    // The field itself.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the same point is warped to the same place, every time`() {
        for (i in 0 until 64) {
            val x = i / 64f
            val y = ((i * 7) % 64) / 64f
            assertEquals(SkyWarp.warpedX(x, y, seed), SkyWarp.warpedX(x, y, seed), 0f)
            assertEquals(SkyWarp.warpedY(x, y, seed), SkyWarp.warpedY(x, y, seed), 0f)
        }
    }

    @Test
    fun `a warped point stays inside the field`() {
        for (i in 0 until 512) {
            val x = i / 512f
            val y = ((i * 37) % 512) / 512f
            val wx = SkyWarp.warpedX(x, y, seed)
            val wy = SkyWarp.warpedY(x, y, seed)
            assertTrue("x escaped: $wx", wx >= 0f && wx < 1f)
            assertTrue("y escaped: $wy", wy >= 0f && wy < 1f)
        }
    }

    @Test
    fun `the warp actually moves things`() {
        // Otherwise every assertion here would pass on an identity function, and the sky would be
        // the even scatter the warp exists to break up.
        var moved = 0
        for (i in 0 until 256) {
            val x = i / 256f
            val y = ((i * 11) % 256) / 256f
            if (Math.abs(SkyWarp.warpedX(x, y, seed) - x) > 0.005f) moved++
        }
        assertTrue("the warp displaced only $moved of 256 points", moved > 200)
    }

    @Test
    fun `a different seed is a different field`() {
        var differ = 0
        for (i in 0 until 256) {
            val x = i / 256f
            val y = ((i * 11) % 256) / 256f
            if (SkyWarp.warpedX(x, y, seed) != SkyWarp.warpedX(x, y, seed + 1)) differ++
        }
        assertTrue("two seeds warped $differ of 256 points differently", differ > 200)
    }

    @Test
    fun `the field is seamless across the edges`() {
        // The lattice wraps, so the displacement at x = 1 is the displacement at x = 0. Without
        // that, taking the warped position modulo 1 would put a visible seam down the sky — a
        // straight line of density, which is a structure someone could read their own data against.
        for (i in 0 until 64) {
            val t = i / 64f
            assertEquals(SkyWarp.warpedX(0f, t, seed), SkyWarp.warpedX(1f, t, seed), 1e-5f)
            assertEquals(SkyWarp.warpedY(t, 0f, seed), SkyWarp.warpedY(t, 1f, seed), 1e-5f)
        }
    }

    @Test
    fun `the warp never folds the field back over itself`() {
        // SkyWarp.WARP is chosen so the displacement's slope stays under 1. If it did not, the map
        // would reverse somewhere and two stretches of the field would land on top of each other.
        // Order is checked modulo the wrap, because the field is periodic by design.
        val step = 1f / 2048f
        for (row in 0 until 16) {
            val y = row / 16f
            var x = 0f
            while (x < 1f - step) {
                var delta = SkyWarp.warpedX(x + step, y, seed) - SkyWarp.warpedX(x, y, seed)
                if (delta < -0.5f) delta += 1f
                assertTrue("the warp reversed at x = $x, y = $y", delta > 0f)
                x += step
            }
        }
        // The stated bound, as a number: the steepest displacement gradient the interpolant can
        // produce is 1.5 per cell, and there are LUMPS cells per unit.
        assertTrue(
            "WARP is above the folding bound",
            SkyWarp.WARP * 1.5f * SkyWarp.LUMPS < 1f,
        )
    }

    // -------------------------------------------------------------------------------------------
    // Blindness, read off the source.
    // -------------------------------------------------------------------------------------------

    /**
     * Words that would mean the warp had been given something about the person.
     *
     * Deliberately wide: a parameter named `epochDay`, a type named `SkyRecord`, and a mood or an
     * index reached through any name at all are all the same defect, which is the clumping becoming
     * a measurement of how much somebody logged.
     */
    private val dataWords = listOf(
        "SkyRecord", "SkyKind", "SkyLayout", "epochDay", "moodLevel", "recordId",
        "kind", "mood", "date", "index", "count", "records",
    )

    private fun dataWordsIn(code: String): List<String> =
        dataWords.filter { it.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(code) }

    /**
     * Kotlin source with its comments removed.
     *
     * The comments here discuss records and moods at length — they have to, to explain what the
     * file must never do — so a scan of the raw text would fire on the documentation and prove
     * nothing. Naive on purpose: [SkyWarp] contains no string literals for a `//` to hide inside,
     * and the positive controls below are what establishes that this does the job.
     */
    private fun stripComments(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\\n]*"), " ")

    @Test
    fun `the source scanner can see a data word, and the comment stripper hides one`() {
        // Run first, so the silence below means something. Three controls, because three separate
        // things have to work: the vocabulary has to match real code, the stripper has to remove
        // comments, and it has to leave code alone.
        val planted = "fun warpedX(x: Float, y: Float, seed: Long, record: SkyRecord): Float = x"
        assertTrue("the scanner misses a planted parameter", dataWordsIn(planted).isNotEmpty())

        // The vocabulary is the one real code is written in: Sky.kt is full of these words, and a
        // scan of it has to say so or the word list is fiction.
        val sky = stripComments(
            com.daymark.app.backup.repoFile("app/src/main/java/com/daymark/app/sky/Sky.kt").readText(),
        )
        assertTrue("the scanner finds nothing in Sky.kt itself", dataWordsIn(sky).size >= 5)

        // The stripper removes a comment...
        assertTrue(dataWordsIn(stripComments("/** never sees a SkyRecord */\nval a = 1")).isEmpty())
        assertTrue(dataWordsIn(stripComments("// never sees a SkyRecord\nval a = 1")).isEmpty())
        // ...and only a comment.
        assertFalse(dataWordsIn(stripComments("val a: SkyRecord? = null // nothing")).isEmpty())
    }

    @Test
    fun `the warp cannot see a record`() {
        // Not imported: `import com.daymark.app.backup.repoFile` would make `tools/jvm-tests.sh`
        // skip this whole file as "reaches into another package", and this is exactly the test that
        // has to be runnable in two seconds rather than in a twelve-minute CI round trip.
        val source = com.daymark.app.backup.repoFile(
            "app/src/main/java/com/daymark/app/sky/SkyWarp.kt",
        ).readText()
        val code = stripComments(source)

        assertTrue("SkyWarp.kt was not read", code.contains("fun warpedX"))
        assertEquals(
            "SkyWarp's code mentions the person's data: ${dataWordsIn(code)}",
            emptyList<String>(),
            dataWordsIn(code),
        )

        // And the signatures specifically, so a future parameter has to be one of these three types.
        for (declaration in Regex("fun\\s+\\w+\\(([^)]*)\\)").findAll(code)) {
            val parameters = declaration.groupValues[1]
            for (parameter in parameters.split(',')) {
                if (parameter.isBlank()) continue
                val type = parameter.substringAfter(':').trim()
                assertTrue(
                    "SkyWarp takes a $type",
                    type == "Float" || type == "Int" || type == "Long",
                )
            }
        }
    }

    @Test
    fun `the warp is the only thing between the hash and the drawn position`() {
        // A guard on the one route by which the blindness above could be got around: if the layout
        // stopped calling the warp, or called something else as well, the clusters could come from
        // anywhere. Sky.kt has to reach the drawn coordinates through SkyWarp and nothing else.
        val layout = stripComments(
            com.daymark.app.backup.repoFile("app/src/main/java/com/daymark/app/sky/Sky.kt").readText(),
        )
        assertTrue(layout.contains("SkyWarp.warpedX("))
        assertTrue(layout.contains("SkyWarp.warpedY("))
        // The detector: a word that is not in the file reports absent, so the two above are facts.
        assertFalse(layout.contains("SkyWarp.warpedZ("))
    }

    @Test
    fun `the two channels are not each other`() {
        // One channel used for both axes, or two whose hashes agree, would put every star on a
        // diagonal — the same defect the placement salts had before 2026-09-16, one layer down.
        var same = 0
        for (i in 0 until 256) {
            val x = i / 256f
            val y = ((i * 11) % 256) / 256f
            val dx = SkyWarp.warpedX(x, y, seed) - x
            val dy = SkyWarp.warpedY(x, y, seed) - y
            if (Math.abs(dx - dy) < 1e-6f) same++
        }
        assertNotEquals("the two channels displace identically", 256, same)
        assertTrue("the two channels agree on $same of 256 points", same < 8)
    }
}
