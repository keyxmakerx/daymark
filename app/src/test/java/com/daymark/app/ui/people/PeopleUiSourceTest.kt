package com.daymark.app.ui.people

import com.daymark.app.backup.repoFile
import com.daymark.app.ui.argumentsOfCall
import com.daymark.app.ui.firstPhraseIn
import com.daymark.app.ui.stringLiteralsIn
import com.daymark.app.ui.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the people screens may contain, and the two things they may never contain.
 *
 * `docs/PLAN_2026-09-SKY-PEOPLE-TIMING.md` §2 asked for both of these as *shapes* rather than
 * promises:
 *
 *  1. **No person or community reaches anything that reads mood.** *"Correlations, patterns and
 *     the cards they produce cannot receive a person or a community, groups included, enforced by
 *     signature the way the Sky's field is kept blind to data."* The data layer holds up its end
 *     by what it does not return (`PeopleRepository`'s header). This holds up the UI's end: no
 *     file under `ui/people` names a mood at all, and the editor — the one screen that legitimately
 *     holds both — writes them through two calls, neither of which receives the other's argument.
 *  2. **Nothing prompts about an absence.** *"Never: 'you haven't written about X in a while.' A
 *     gap is never a prompt."* That sentence is the single most likely thing to be added to this
 *     feature by somebody trying to make it friendlier, and it lands hardest on exactly the person
 *     it would find.
 *
 * ## Why source text, and what that is worth
 *
 * This module has no Robolectric and no Compose test runner, so a composition test would be
 * written, never run, and quietly believed. Reading the source is the weaker claim and it is
 * stated as such; what is rendered is checked by hand and by `/walkthrough`.
 *
 * ## Every absence here is shown a planted example first
 *
 * `CLAUDE.md` §5: a grep that cannot see a planted example proves only that it is blind, and that
 * is this repository's single most common bug shape. So each detector below is run over the real
 * source with the forbidden thing spliced into it, and must report it. The comment stripper gets
 * the same treatment from both directions: a word that appears only in the KDoc must be gone
 * afterwards, and a known declaration must still be there.
 */
class PeopleUiSourceTest {

    private companion object {

        const val ANCHOR = "app/src/main/java/com/daymark/app/ui/people/PersonGroups.kt"
        const val EDITOR = "app/src/main/java/com/daymark/app/ui/entry/EntryEditorViewModel.kt"

        /**
         * Anything that names a mood. One word catches the model (`Mood`), the row
         * (`MoodEntry.moodLevel`), the palette (`MoodColors`), the labels (`moodLabels`) and the
         * face (`MoodFaceIcon`) — every door from here into the half of the app this one must not
         * touch.
         */
        const val MOOD = "mood"

        /** The statistics layer, which is where a correlation would be run. */
        val STATS_TOKENS = listOf(
            "com.daymark.app.stats",
            "Signals",
            "Correlation",
            "factorDeltas",
            "FactorDelta",
        )

        /** The prompt §2 forbids by name, and the family it belongs to. */
        val ABSENCE_PROMPTS = listOf(
            "haven't written",
            "have not written",
            "haven't heard",
            "in a while",
            "been a while",
            "long time since",
            "nothing since",
            "you last wrote",
            "why not write",
            "don't forget to",
        )

        /** Commentary: the app telling somebody how they are, or how somebody else is. */
        val COMMENTARY = listOf(
            "you seem",
            "you look",
            "you must have",
            "sounds like",
            "seems like",
            "you're feeling",
            "you are feeling",
        )

        /** `CLAUDE.md` §4: no success, no tick, no score, no streak, no congratulation. */
        val CELEBRATION = listOf(
            "congrat",
            "well done",
            "great job",
            "nice work",
            "keep it up",
            "keep going",
            "streak",
            "success",
            "you're doing",
            "you are doing",
        )

        /** Raw colour. `CLAUDE.md` §4: semantic tokens only. */
        val RAW_COLOUR = listOf(
            "Color(0x",
            "Color.Gray",
            "Color.Red",
            "Color.Green",
            "Color.Blue",
            "Color.Black",
            "Color.White",
            "Color.Yellow",
        )

        fun firstTokenIn(code: String, tokens: List<String>): String? =
            tokens.firstOrNull { code.contains(it) }
    }

    private val peopleDir: File = repoFile(ANCHOR).parentFile
        ?: throw AssertionError("ui/people has no parent directory")

    private val peopleFiles: List<File> = (peopleDir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(".kt") }
        .sortedBy { it.name }

    /** File name to its source with comments removed. */
    private val peopleCode: Map<String, String> =
        peopleFiles.associate { it.name to withoutComments(it.readText()) }

    // --------------------------------------------------------------------------------------
    // The guards in front of every assertion below.
    // --------------------------------------------------------------------------------------

    @Test
    fun `the package was found and the stripper neither ate it nor did nothing`() {
        assertTrue(
            "ui/people has fewer files than the screens this feature is made of; the path has " +
                "probably moved and every absence check below is now vacuous",
            peopleFiles.size >= 8,
        )

        // Did nothing? PersonScreen's KDoc states the mood rule using the word the code may not
        // contain. If the stripper is a no-op, this word survives and the check below is a lie.
        val screenRaw = peopleFiles.first { it.name == "PersonScreen.kt" }.readText()
        assertTrue(
            "PersonScreen.kt no longer explains the rule in its KDoc, so the stripper has nothing " +
                "to prove itself against",
            screenRaw.lowercase().contains(MOOD),
        )
        assertFalse(
            "the comment stripper left KDoc behind: the word is still there after stripping",
            withoutComments(screenRaw).lowercase().contains(MOOD),
        )

        // Ate it? A stripper that returned "" would satisfy every absence check in this file.
        for (file in peopleFiles) {
            val code = peopleCode.getValue(file.name)
            assertTrue(
                "${file.name} is empty after stripping, so nothing below is checking anything",
                code.contains("package com.daymark.app.ui.people"),
            )
        }
        assertTrue(
            "PersonScreen.kt lost its declaration to the stripper",
            peopleCode.getValue("PersonScreen.kt").contains("fun PersonScreen("),
        )
    }

    // --------------------------------------------------------------------------------------
    // 1. No person reaches anything that reads mood.
    // --------------------------------------------------------------------------------------

    @Test
    fun `no screen in ui-people names a mood`() {
        for ((name, code) in peopleCode) {
            assertFalse(
                "$name names a mood. A person or a community may never reach anything that " +
                    "reads one (PLAN_2026-09 §2), and a page about somebody is where that breaks " +
                    "first: a column of faces under their name is the correlation drawn for the " +
                    "eye, whether or not any code computed it.",
                code.lowercase().contains(MOOD),
            )
        }
    }

    @Test
    fun `no screen in ui-people reaches the statistics layer`() {
        for ((name, code) in peopleCode) {
            val token = firstTokenIn(code, STATS_TOKENS)
            assertNull(
                "$name reaches `stats/` (\"$token\"). Correlations, patterns and the cards they " +
                    "produce cannot receive a person or a community, groups included.",
                token,
            )
        }
    }

    /**
     * The join a person's page is allowed to do, kept to the one file that is allowed to do it.
     *
     * `PeopleRepository`'s header sanctions exactly one caller shape: ids out of the people layer,
     * entries out of `EntryRepository`, joined in a view model for a list about to be rendered.
     * One file, so the shape stays a shape.
     */
    @Test
    fun `only the person page joins entries, and it takes no mood with it`() {
        val joiners = peopleCode.filterValues { it.contains("EntryRepository") }.keys
        assertEquals(
            "the entry join has spread beyond PersonViewModel: $joiners",
            setOf("PersonViewModel.kt"),
            joiners,
        )
    }

    @Test
    fun `the mood detector, the stats detector and the join counter all see a planted example`() {
        val real = peopleCode.getValue("PersonViewModel.kt")

        for (planted in listOf(
            "val level: Int = found.entry.moodLevel",
            "import com.daymark.app.ui.theme.moodLabels",
            "MoodFaceIcon(level = it.entry.moodLevel)",
        )) {
            assertTrue(
                "the mood detector did not see the planted line: \"$planted\"",
                (real + "\n" + planted).lowercase().contains(MOOD),
            )
        }

        for (planted in listOf(
            "import com.daymark.app.stats.MoodCorrelations",
            "val deltas = factorDeltas(pairs, 3)",
            "Signals.forPerson(person)",
        )) {
            assertNotNull(
                "the stats detector did not see the planted line: \"$planted\"",
                firstTokenIn(real + "\n" + planted, STATS_TOKENS),
            )
        }

        // The join counter, shown a second joiner.
        val withExtraJoiner = peopleCode.toMutableMap()
        withExtraJoiner["PeopleViewModel.kt"] =
            peopleCode.getValue("PeopleViewModel.kt") + "\nprivate val r: EntryRepository? = null\n"
        assertEquals(
            "the join counter cannot see a second file reaching EntryRepository",
            setOf("PersonViewModel.kt", "PeopleViewModel.kt"),
            withExtraJoiner.filterValues { it.contains("EntryRepository") }.keys,
        )
    }

    // --------------------------------------------------------------------------------------
    // 2. The editor writes the two halves of an entry through two calls.
    // --------------------------------------------------------------------------------------

    /**
     * The one screen that legitimately holds a mood and a *with* list at the same time, and the
     * property that keeps it honest: **neither call receives the other's argument.**
     *
     * A single `save(entry, activities, people)` would be the one place in the app where the two
     * are handed over together, and every later convenience would reach for it.
     */
    @Test
    fun `the editor saves the mood and the with list in separate calls`() {
        val code = withoutComments(repoFile(EDITOR).readText())

        val saveArgs = argumentsOfCall(code, "entryRepository.save")
        assertNotNull("EntryEditorViewModel no longer calls entryRepository.save", saveArgs)
        val withArgs = argumentsOfCall(code, "peopleRepository.setPeopleOnEntry")
        assertNotNull(
            "EntryEditorViewModel no longer writes the entry's with list, so \"an entry gains " +
                "with\" is not built",
            withArgs,
        )

        assertTrue(
            "the entry save no longer carries the mood, which means this test is no longer " +
                "looking at the call it thinks it is",
            saveArgs!!.lowercase().contains(MOOD),
        )
        assertFalse(
            "the entry save now receives people as well as a mood. That single call is the " +
                "bridge PLAN_2026-09 §2 rules out; keep them two calls.",
            saveArgs.contains("PersonIds") || saveArgs.contains("person"),
        )

        assertTrue(
            "the with write no longer carries the selected people",
            withArgs!!.contains("selectedPersonIds"),
        )
        assertFalse(
            "the with write now receives a mood. Nothing that takes a person may take one.",
            withArgs.lowercase().contains(MOOD),
        )
    }

    @Test
    fun `the two-call check fails when either call is given the other's argument`() {
        val code = withoutComments(repoFile(EDITOR).readText())

        val poisonedSave = code.replace(
            "s.selectedActivityIds.toList(),",
            "s.selectedActivityIds.toList(), s.selectedPersonIds.toList(),",
        )
        assertTrue(
            "the planted mutation did not change the source, so the next assertion proves nothing",
            poisonedSave != code,
        )
        val poisonedSaveArgs = argumentsOfCall(poisonedSave, "entryRepository.save")
        assertTrue(
            "the check cannot see people smuggled into the entry save",
            poisonedSaveArgs!!.contains("PersonIds"),
        )

        val poisonedWith = code.replace(
            "peopleRepository.setPeopleOnEntry(savedId, s.selectedPersonIds.toList())",
            "peopleRepository.setPeopleOnEntry(savedId, s.selectedPersonIds.toList(), s.moodLevel)",
        )
        assertTrue(
            "the planted mutation did not change the source, so the next assertion proves nothing",
            poisonedWith != code,
        )
        val poisonedWithArgs = argumentsOfCall(poisonedWith, "peopleRepository.setPeopleOnEntry")
        assertTrue(
            "the check cannot see a mood smuggled into the with write",
            poisonedWithArgs!!.lowercase().contains(MOOD),
        )
    }

    // --------------------------------------------------------------------------------------
    // 3. The sharing rule is called, never copied.
    // --------------------------------------------------------------------------------------

    @Test
    fun `the sharing screen calls the one expression that decides sharing`() {
        val sharing = peopleCode.getValue("PeopleSharingViewModel.kt")
        assertTrue(
            "PeopleSharingViewModel no longer calls PeopleRepository.isShared. That expression is " +
                "what decides whether somebody's name leaves the device; a screen that re-derives " +
                "it is a screen that can disagree with the code that actually sends, and the " +
                "phone would look right either way.",
            sharing.contains("PeopleRepository.isShared("),
        )
    }

    @Test
    fun `nothing in ui-people re-derives the sharing rule`() {
        for ((name, code) in peopleCode) {
            val offender = code.lines().firstOrNull { line ->
                line.contains("sharedOverride") && (line.contains("?:") || line.contains("||"))
            }
            assertNull(
                "$name resolves sharing itself: \"${offender?.trim()}\". Call " +
                    "PeopleRepository.isShared instead — and note that `||` is not the same rule: " +
                    "an override of false must BEAT a group default of true, which is the whole " +
                    "purpose of a per-item exclusion.",
                offender,
            )
        }
    }

    @Test
    fun `the re-derivation check sees a planted copy of the rule`() {
        for (planted in listOf(
            "val shared = person.sharedOverride ?: groupDefault",
            "val shared = person.sharedOverride == true || groupDefault",
        )) {
            val code = peopleCode.getValue("PeopleSharingViewModel.kt") + "\n" + planted + "\n"
            val offender = code.lines().firstOrNull { line ->
                line.contains("sharedOverride") && (line.contains("?:") || line.contains("||"))
            }
            assertNotNull("the check did not see the planted copy: \"$planted\"", offender)
        }
    }

    // --------------------------------------------------------------------------------------
    // 4. The copy.
    // --------------------------------------------------------------------------------------

    private fun literalsOf(name: String): List<String> = stringLiteralsIn(peopleCode.getValue(name))

    @Test
    fun `no screen prompts about an absence, comments on anybody, or congratulates`() {
        for (name in peopleCode.keys) {
            val literals = literalsOf(name)
            val absence = firstPhraseIn(literals, ABSENCE_PROMPTS)
            assertNull(
                "$name says \"$absence\". PLAN_2026-09 §2 forbids a prompt about a gap by name: " +
                    "\"you haven't written about X in a while\". A page may state `last note: " +
                    "June` as a fact when it is opened; nothing may raise a silence unprompted.",
                absence,
            )
            val commentary = firstPhraseIn(literals, COMMENTARY)
            assertNull("$name comments on the person: \"$commentary\"", commentary)
            val celebration = firstPhraseIn(literals, CELEBRATION)
            assertNull(
                "$name congratulates or scores: \"$celebration\" (CLAUDE.md §4)",
                celebration,
            )
        }
    }

    @Test
    fun `no screen uses a raw colour`() {
        for ((name, code) in peopleCode) {
            val raw = firstTokenIn(code, RAW_COLOUR)
            assertNull("$name uses a raw colour (\"$raw\"). Semantic tokens only.", raw)
        }
    }

    @Test
    fun `the copy detectors see planted examples of each kind`() {
        val real = peopleCode.getValue("PersonScreen.kt")

        for (planted in listOf(
            "You haven't written about them in a while.",
            "It's been a while since your last note.",
            "Nothing since March — why not write something?",
        )) {
            val literals = stringLiteralsIn(real + "\nText(\"" + planted + "\")\n")
            assertNotNull(
                "the absence-prompt detector did not see: \"$planted\"",
                firstPhraseIn(literals, ABSENCE_PROMPTS),
            )
        }

        for (planted in listOf(
            "You seem happier when you see them.",
            "That sounds like a good week.",
        )) {
            val literals = stringLiteralsIn(real + "\nText(\"" + planted + "\")\n")
            assertNotNull(
                "the commentary detector did not see: \"$planted\"",
                firstPhraseIn(literals, COMMENTARY),
            )
        }

        for (planted in listOf(
            "Well done — four notes this month!",
            "You're doing great. Keep it up.",
            "A 6 week streak.",
        )) {
            val literals = stringLiteralsIn(real + "\nText(\"" + planted + "\")\n")
            assertNotNull(
                "the celebration detector did not see: \"$planted\"",
                firstPhraseIn(literals, CELEBRATION),
            )
        }

        for (planted in listOf("Color(0xFF4CAF50)", "Color.Green")) {
            assertNotNull(
                "the raw-colour detector did not see: \"$planted\"",
                firstTokenIn(real + "\nval tint = " + planted + "\n", RAW_COLOUR),
            )
        }
    }

    /** The other half: a presence check has to be able to fail. */
    @Test
    fun `the archive promise is stated where the archive control is`() {
        val literals = literalsOf("PersonScreen.kt")
        assertTrue(
            "the archive control no longer says what archiving leaves alone. §2: \"Archive hides " +
                "one from the picker. Entries and notes are untouched\" — and somebody archiving " +
                "a person who has left their life is deciding whether the record of the years " +
                "they were in it survives.",
            literals.any { it.contains("Archiving only hides them from the with picker") },
        )
        assertTrue(
            "the archive dialog no longer names what is kept",
            literals.any { it.contains("every note you have written and this page all stay") },
        )
        assertFalse(
            "the presence check passes over a reworded promise, so it is not checking the wording",
            stringLiteralsIn(
                peopleCode.getValue("PersonScreen.kt")
                    .replace("Archiving only hides them from the with picker", "Archiving hides them"),
            ).any { it.contains("Archiving only hides them from the with picker") },
        )
    }

    @Test
    fun `the sharing state is stated both ways and neither is dressed as the right one`() {
        val literals = literalsOf("PersonScreen.kt")
        assertTrue(
            "the person page no longer states that they are shared",
            literals.any { it == "Shared with your clinician." },
        )
        assertTrue(
            "the person page no longer states that they are not shared. Off is the default and " +
                "the line has to read as a state, not as something left undone.",
            literals.any { it == "Not shared with your clinician." },
        )
    }
}
