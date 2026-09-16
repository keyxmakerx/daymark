package com.daymark.app.stats

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * The openers, and the one rule that matters more than the rest of this file put together.
 *
 * > **The draw is blind to how the person seemed.** A phrasing is never chosen because of how they
 * > appeared to be doing.
 *
 * That is asserted **twice, by two methods that fail independently**, because each is blind to the
 * other's failure and either alone would be a guard that reports green forever:
 *
 *  1. **By signature.** Reflection over every method [PhrasePool] declares: the only parameter types
 *     that exist are `int` and [PhrasePool.Band]. Nothing carrying a reading of a person is typed
 *     into this object, so no call site can pass one. This catches the obvious regression — someone
 *     adding `fun opener(band: Band, rotation: Int, signals: Signals.Inputs)` — and is blind to the
 *     subtle one, because a level is an `Int` and so is an hour.
 *  2. **By name, over the source with its comments stripped.** No identifier in this file's *code*
 *     may be named for a state of a person. This is what catches `fun opener(hour: Int, level: Int)`,
 *     which the reflection check waves straight through. The comments have to come off first or the
 *     check would fail on the file's own header, which says "blind to mood" in as many words — and
 *     that sentence surviving in the raw text is itself one of the controls below.
 *
 * Both carry a **planted positive control**: the same checker, pointed at something that really does
 * take a reading of a person, must go red. `CLAUDE.md` §5 — a grep that cannot see a planted example
 * proves only that it is blind, and this repository's most common bug shape is a check written
 * against an assumption that goes green when the assumption stops holding.
 *
 * Nothing in this file draws a random value, so none of §5's four mutation-that-might-not-be-a-
 * mutation bugs has anywhere to live here; where a value is changed on purpose the change is derived
 * from the value and asserted to have really changed.
 */
class PhrasePoolTest {

    /** Something that plainly is a reading of a person, for the reflection control to catch. */
    private data class PlantedReading(val level: Int)

    /** And an object that takes one, so the checker has a real failure to find. */
    private object PlantedPool {
        @Suppress("UNUSED_PARAMETER")
        fun opener(reading: PlantedReading): String = "never written"
    }

    /**
     * The only parameter types the openers may be reached through. Everything else is a route by
     * which something about the person could arrive.
     */
    private val allowedParameterTypes: Set<Class<*>> = setOf(
        Int::class.javaPrimitiveType!!,
        PhrasePool.Band::class.java,
    )

    /** Every parameter of every declared method that is not on the allowed list. */
    private fun disallowedParameters(target: Class<*>): List<String> {
        val found = ArrayList<String>()
        for (method: Method in target.declaredMethods) {
            if (method.isSynthetic) continue
            for (parameter in method.parameterTypes) {
                if (!allowedParameterTypes.contains(parameter)) {
                    found.add(method.name + "(" + parameter.name + ")")
                }
            }
        }
        return found
    }

    /** Words that name a state of a person. None of them may appear in this file's code. */
    private val readingOfAPerson: List<String> = listOf(
        "mood",
        "Mood",
        "feel",
        "Feel",
        "emotion",
        "Emotion",
        "sentiment",
        "Sentiment",
        "distress",
        "Distress",
        "valence",
        "Valence",
        "level",
        "Level",
        "score",
        "Score",
    )

    private fun readingWordsIn(source: String): List<String> =
        readingOfAPerson.filter { source.contains(it) }

    // ---- the draw is blind to how the person seemed -----------------------------------------

    @Test
    fun `no reading of a person can be typed into the draw`() {
        assertEquals(
            "PhrasePool takes something it should not",
            emptyList<String>(),
            disallowedParameters(PhrasePool::class.java),
        )
        // Positive control. Without this the assertion above would also pass if declaredMethods
        // came back empty, if every method were synthetic, or if the allow list had quietly grown
        // to contain everything.
        assertNotEquals(
            "the signature check cannot see a planted reading",
            emptyList<String>(),
            disallowedParameters(PlantedPool::class.java),
        )
        // And it is looking at something: the object really does declare the openers.
        val names = PhrasePool::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue("opener is missing from $names", names.contains("opener"))
        assertTrue("openerForHour is missing from $names", names.contains("openerForHour"))
    }

    @Test
    fun `nothing in the source is named for a state of a person`() {
        val path = "app/src/main/java/com/daymark/app/stats/PhrasePool.kt"
        val raw = repoFile(path).readText()
        val code = stripKotlinComments(raw)

        // Control: the stripper left the code behind. An emptied string satisfies every absence
        // assertion below, which is exactly the failure this repository keeps finding.
        assertTrue("the stripper ate the file", code.contains("fun opener(band: Band, rotation: Int)"))
        assertTrue("the stripper ate the file", code.contains("val MORNING"))
        assertTrue("the stripper ate the file", code.contains("val EVENING"))

        // Control: the raw file really does contain the vocabulary, in its header, so the matcher
        // below is demonstrably able to find these words in this file — and the only reason it
        // finds none is that the comments came off.
        assertTrue(
            "the header should say what this rule is, in words",
            readingWordsIn(raw).isNotEmpty(),
        )

        assertEquals(
            "PhrasePool's code names a state of a person",
            emptyList<String>(),
            readingWordsIn(code),
        )

        // Positive control: the same matcher over a planted file must catch it.
        val planted = "fun opener(hour: Int, rotation: Int, moodLevel: Int): String = \"\""
        assertNotEquals(
            "the source check cannot see a planted reading",
            emptyList<String>(),
            readingWordsIn(stripKotlinComments(planted)),
        )
    }

    @Test
    fun `the same band and rotation always give the same line`() {
        // Determinism is the other half of "blind": a draw that varied on anything at all would
        // have somewhere for a reading to hide, and there is no clock and no random source here.
        for (band in PhrasePool.Band.entries) {
            for (rotation in -100..100) {
                assertEquals(
                    PhrasePool.opener(band, rotation),
                    PhrasePool.opener(band, rotation),
                )
            }
        }
        for (hour in -48..71) {
            for (rotation in 0..20) {
                assertEquals(
                    PhrasePool.opener(PhrasePool.bandForHour(hour), rotation),
                    PhrasePool.openerForHour(hour, rotation),
                )
            }
        }
    }

    // ---- the pools themselves ---------------------------------------------------------------

    @Test
    fun `every line is a distinct, human-written sentence`() {
        val all = PhrasePool.MORNING + PhrasePool.EVENING
        assertTrue("the pools are too small to rotate", PhrasePool.MORNING.size >= 4)
        assertTrue("the pools are too small to rotate", PhrasePool.EVENING.size >= 4)
        assertEquals("a pool repeats itself", all.size, all.distinct().size)
        for (line in all) {
            assertTrue("a line is blank", line.isNotBlank())
            assertEquals("a line has stray whitespace: [$line]", line.trim(), line)
            assertTrue("a line does not end as a sentence: [$line]", line.endsWith("."))
            assertFalse("an opener must not exclaim: [$line]", line.contains("!"))
            // No template, no slot, no assembly — the whole point of CLAUDE.md §0.
            assertFalse("a line looks like a template: [$line]", line.contains("%"))
            assertFalse("a line looks like a template: [$line]", line.contains("{"))
        }
    }

    @Test
    fun `no line congratulates, scores or names an hour`() {
        // CLAUDE.md §4: no green, success, tick, score, streak or congratulation anywhere. And
        // PhrasePool's own rule: the strings never name a time of day, because an opener that says
        // "morning" is wrong at some hour the gate will eventually permit.
        val banned = listOf(
            "well done", "great", "amazing", "congrat", "keep it up", "streak", "score",
            "nice work", "proud", "success", "achiev",
            "morning", "evening", "afternoon", "tonight", "o'clock",
        )
        for (line in PhrasePool.MORNING + PhrasePool.EVENING) {
            val lower = line.lowercase()
            for (word in banned) {
                assertFalse("[$line] contains \"$word\"", lower.contains(word))
            }
        }
        // Positive control: the matcher can see one when it is there.
        val planted = "Good morning — great work on that streak!".lowercase()
        assertTrue(banned.any { planted.contains(it) })
    }

    // ---- rotation ---------------------------------------------------------------------------

    @Test
    fun `rotation works through a pool before repeating`() {
        for (band in PhrasePool.Band.entries) {
            val lines = PhrasePool.pool(band)
            val drawn = (0 until lines.size).map { PhrasePool.opener(band, it) }
            assertEquals("a full turn of the rotation should use every line", lines, drawn)
            assertEquals(
                "and then start again",
                PhrasePool.opener(band, 0),
                PhrasePool.opener(band, lines.size),
            )
            // Consecutive draws differ, which is the whole reason a rotation exists.
            for (rotation in 0 until lines.size) {
                assertNotEquals(
                    PhrasePool.opener(band, rotation),
                    PhrasePool.opener(band, PhrasePool.nextRotation(rotation)),
                )
            }
        }
    }

    @Test
    fun `a counter that was reset, restored or wrapped still draws a line`() {
        val awkward = listOf(Int.MIN_VALUE, -7, -1, 0, 1, Int.MAX_VALUE - 1, Int.MAX_VALUE)
        for (band in PhrasePool.Band.entries) {
            for (rotation in awkward) {
                val line = PhrasePool.opener(band, rotation)
                assertTrue("rotation=$rotation gave [$line]", PhrasePool.pool(band).contains(line))
            }
        }
        assertEquals(0, PhrasePool.nextRotation(Int.MAX_VALUE))
        assertEquals(1, PhrasePool.nextRotation(0))
    }

    // ---- the band is a fact about a clock ---------------------------------------------------

    @Test
    fun `noon is the only line drawn, and every hour lands on one side of it`() {
        for (hour in 0..11) {
            assertEquals("hour=$hour", PhrasePool.Band.Morning, PhrasePool.bandForHour(hour))
        }
        for (hour in 12..23) {
            assertEquals("hour=$hour", PhrasePool.Band.Evening, PhrasePool.bandForHour(hour))
        }
        // Total over every Int: an out-of-range hour wraps rather than throwing, so the path where
        // the app is about to speak cannot fail on arithmetic.
        for (hour in -100..200) {
            val band = PhrasePool.bandForHour(hour)
            assertEquals("hour=$hour", PhrasePool.bandForHour(hour + 24), band)
            assertTrue(PhrasePool.pool(band).isNotEmpty())
        }
    }

    @Test
    fun `the pools are reachable by band and nothing else`() {
        assertEquals(PhrasePool.MORNING, PhrasePool.pool(PhrasePool.Band.Morning))
        assertEquals(PhrasePool.EVENING, PhrasePool.pool(PhrasePool.Band.Evening))
        assertEquals(2, PhrasePool.Band.entries.size)
    }
}
