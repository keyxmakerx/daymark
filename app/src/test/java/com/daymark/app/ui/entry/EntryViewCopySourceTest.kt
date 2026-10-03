package com.daymark.app.ui.entry

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.argumentsOfCall
import com.daymark.app.ui.firstPhraseIn
import com.daymark.app.ui.stringLiteralsIn
import com.daymark.app.ui.withoutComments
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The entry page shows a day and says nothing about it.
 *
 * `docs/FEATURES.md` §1.3: *"The page is descriptive only: the person's own mood word, their
 * activities, who they were with, their note and their photo. There is no commentary of any kind:
 * no "you seem", no comparison with another day, no count, no encouragement."* `CLAUDE.md` §0 is
 * the same rule for the whole product: *generated text aimed at someone in distress is the hazard
 * the whole architecture exists to avoid*.
 *
 * ## Why this file exists rather than a note in a review
 *
 * A page that shows one day is the single most inviting place in this app for a helpful sentence.
 * It has a face, a word, a note and a photo already on it; adding "that sounds like a hard one"
 * costs one line, reads as kindness, and is the product becoming the thing it was built not to be.
 * Every force acting on this screen pushes that way, and none of them is malicious.
 *
 * So the strings are read and the vocabulary is refused, and the refusal is shown planted examples
 * first — `CLAUDE.md` §5, because a grep that cannot see one proves only that it is blind.
 *
 * ## What is checked, and what is not
 *
 * This reads source text. It cannot tell you what a person sees; this module has no Robolectric
 * and no Compose test runner, so a composition test would be written, never run, and quietly
 * believed. What is rendered is checked by hand and by `/walkthrough`.
 */
class EntryViewCopySourceTest {

    private companion object {
        const val SCREEN = "app/src/main/java/com/daymark/app/ui/entry/EntryViewScreen.kt"

        /** The app describing the person to themselves. */
        val COMMENTARY = listOf(
            "you seem",
            "you seemed",
            "you look",
            "you looked",
            "that sounds",
            "sounds like",
            "seems like",
            "you must have",
            "you were feeling",
            "hard day",
            "good day",
            "bad day",
            "rough day",
            "better than",
            "worse than",
            "your best",
            "your worst",
        )

        /** `CLAUDE.md` §4: no success, no tick, no score, no streak, no congratulation. */
        val CELEBRATION = listOf(
            "congrat",
            "well done",
            "great job",
            "nice work",
            "keep it up",
            "streak",
            "success",
            "you're doing",
            "you are doing",
        )

        /** A gap is never drawn as a failure, and never raised unprompted. */
        val ABSENCE_PROMPTS = listOf(
            "you haven't",
            "you have not",
            "in a while",
            "been a while",
            "nothing since",
            "don't forget",
            "why not",
        )

        val RAW_COLOUR = listOf("Color(0x", "Color.Gray", "Color.Red", "Color.Green")
    }

    private val raw: String = repoFile(SCREEN).readText()
    private val code: String = withoutComments(raw)
    private val literals: List<String> = stringLiteralsIn(code)

    @Test
    fun `the file was found and the stripper neither ate it nor did nothing`() {
        assertTrue("EntryViewScreen.kt is implausibly short", raw.length > 3000)
        // Did nothing? The KDoc states the rule using the very words the copy may not contain.
        assertTrue(
            "EntryViewScreen.kt no longer quotes the forbidden phrasing in its KDoc, so the " +
                "stripper has nothing to prove itself against",
            raw.lowercase().contains("you seem"),
        )
        assertFalse(
            "the comment stripper left KDoc behind",
            code.lowercase().contains("you seem"),
        )
        // Ate it?
        assertTrue("the stripper ate the declaration", code.contains("fun EntryViewScreen("))
        assertTrue("no strings were found, so every phrase check below is vacuous", literals.size > 5)
    }

    @Test
    fun `the page never tells anybody how they felt or seemed`() {
        val phrase = firstPhraseIn(literals, COMMENTARY)
        assertNull(
            "the entry page says \"$phrase\". FEATURES.md §1.3: descriptive only, never " +
                "\"you seem\". The word for the mood is the person's own label read back; " +
                "anything the app concluded from it is the thing this product exists not to do.",
            phrase,
        )
    }

    @Test
    fun `the page never congratulates, scores or counts a streak`() {
        val phrase = firstPhraseIn(literals, CELEBRATION)
        assertNull("the entry page says \"$phrase\" (CLAUDE.md §4)", phrase)
    }

    @Test
    fun `the page never raises an absence`() {
        val phrase = firstPhraseIn(literals, ABSENCE_PROMPTS)
        assertNull("the entry page says \"$phrase\"", phrase)
    }

    @Test
    fun `the page uses no raw colour`() {
        val offender = RAW_COLOUR.firstOrNull { code.contains(it) }
        assertNull("the entry page uses a raw colour (\"$offender\"). Semantic tokens only.", offender)
    }

    @Test
    fun `all four detectors see planted examples`() {
        for (planted in listOf(
            "You seem happier than yesterday.",
            "That sounds like a hard day.",
            "This was one of your best.",
        )) {
            assertNotNull(
                "the commentary detector did not see: \"$planted\"",
                firstPhraseIn(stringLiteralsIn(code + "\nText(\"" + planted + "\")\n"), COMMENTARY),
            )
        }
        for (planted in listOf("Well done for logging this.", "A 6 day streak.")) {
            assertNotNull(
                "the celebration detector did not see: \"$planted\"",
                firstPhraseIn(stringLiteralsIn(code + "\nText(\"" + planted + "\")\n"), CELEBRATION),
            )
        }
        for (planted in listOf("You haven't logged since Tuesday.", "It has been a while.")) {
            assertNotNull(
                "the absence detector did not see: \"$planted\"",
                firstPhraseIn(stringLiteralsIn(code + "\nText(\"" + planted + "\")\n"), ABSENCE_PROMPTS),
            )
        }
        for (planted in listOf("Color(0xFF4CAF50)", "Color.Green")) {
            assertNotNull(
                "the raw-colour detector did not see: \"$planted\"",
                RAW_COLOUR.firstOrNull { (code + "\nval t = " + planted).contains(it) },
            )
        }
    }

    /**
     * The mood word shown is the person's own, including one they renamed themselves.
     *
     * `MaterialTheme.moodLabels` is the customised-labels holder. Reading `Mood.fromLevel(...).label`
     * instead would quietly ignore a rename and show this app's word for their day in place of
     * theirs.
     */
    @Test
    fun `the mood word is the person's own label`() {
        assertTrue(
            "the entry page no longer reads the person's own mood labels, so a renamed mood would " +
                "be shown under this app's word for it instead of theirs",
            code.contains("MaterialTheme.moodLabels.forLevel("),
        )
    }

    @Test
    fun `the page shows the with list and offers the mark`() {
        assertTrue(
            "the \"With\" heading is gone, so an entry no longer shows who it names",
            literals.any { it == "With" },
        )
        assertTrue(
            "the mark-this-day action is gone. FEATURES.md §1.3 puts it here so the Sky's " +
                "landmarks are easy to place from the day they are about.",
            literals.any { it == "Mark this day" },
        )
        assertTrue(
            "the entry page no longer writes a life event",
            withoutComments(
                repoFile("app/src/main/java/com/daymark/app/ui/entry/EntryViewViewModel.kt").readText(),
            ).contains("lifeEventDao.insert("),
        )
    }

    /**
     * The mark records a date and the person's words, and nothing about how the day was logged.
     *
     * A mark placed — or coloured, or sized — by the mood on the entry would be the app deciding
     * which of somebody's days mattered. `docs/SKY.md` §2.2 gives `life_events` one writer: the
     * person's own tap.
     */
    @Test
    fun `the life event carries no mood`() {
        val vm = withoutComments(
            repoFile("app/src/main/java/com/daymark/app/ui/entry/EntryViewViewModel.kt").readText(),
        )
        val insert = argumentsOfCall(vm, "lifeEventDao.insert")
        assertNotNull("EntryViewViewModel no longer inserts a life event", insert)
        assertFalse(
            "the life event being written carries a mood. A mark says a day mattered, which is " +
                "the person's judgement and not something read off how they logged it.",
            insert!!.lowercase().contains("mood"),
        )
        // The detector, shown a planted one. The mutation is asserted to be a mutation first —
        // a "planted" example that changed nothing proves the detector saw the original.
        val planted = vm.replace("label = text,", "label = text, moodLevel = uiState.value.moodLevel,")
        assertTrue("the planted mood was not actually planted", planted != vm)
        assertTrue(
            "the check cannot see a mood added to the life event",
            argumentsOfCall(planted, "lifeEventDao.insert")!!.lowercase().contains("mood"),
        )
    }
}
