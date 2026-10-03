package com.daymark.synccrypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The web's word list, read as text, against [DeviceWords.WORDS] (#432). The console picks the six words
 * from the web's list and the phone from its copy, so the two lists must be one list in one order: a word
 * changed, moved or added on one side makes the two screens show different words for the same key, and a
 * person told to compare them would be right to refuse. The web is the oracle (`docs/COMPANION_PHONE.md`),
 * so when `wordlist.ts` changes this goes red and the copy follows.
 *
 * The parser is shown planted differences and has to see each, so the equality cannot pass by reading
 * nothing (`CLAUDE.md` §5).
 */
class DeviceWordsDriftTest {

    private companion object {
        const val WORDLIST = "companion/web/src/lib/share/wordlist.ts"
        const val DECLARATION = "export const SAS_WORDLIST: readonly string[] = ["
    }

    private val source = repoFile(WORDLIST).readText()

    @Test
    fun theWebsListWasFound_andParsesTo256Words() {
        assertTrue(source.contains(DECLARATION))
        assertEquals(256, webWords(source).size)
    }

    @Test
    fun thePhonesListIsTheWebsInTheWebsOrder() {
        assertEquals(webWords(source), DeviceWords.WORDS)
    }

    /**
     * Each planted change is a change to the text, and the parser reads it as one. Compared with what the
     * same parser reads from the unplanted text, not with the phone's list, so this shows the parser can
     * see, whatever the copy says; the test above then compares what it sees with the copy.
     */
    @Test
    fun theParserSeesADifferencePlantedInTheWebsText() {
        val words = webWords(source)
        val first = words.first()
        val second = words[1]

        val changed = source.replaceFirst("'$first'", "'${first}s'")
        assertNotEquals("the change must land", source, changed)
        assertNotEquals(words, webWords(changed))

        val swapped = source.replaceFirst("'$first', '$second'", "'$second', '$first'")
        assertNotEquals("the swap must land", source, swapped)
        assertNotEquals(words, webWords(swapped))

        val shiftedByOne = withWords(source, words.drop(1) + first)
        assertNotEquals("the shift must land", source, shiftedByOne)
        assertEquals("the same words, in another order", words.toSet(), webWords(shiftedByOne).toSet())
        assertNotEquals(words, webWords(shiftedByOne))

        val added = withWords(source, words + "zebra")
        assertNotEquals("the addition must land", source, added)
        assertNotEquals(words, webWords(added))
    }

    /** The quoted words of the web's `SAS_WORDLIST` array, in order. Fails if the declaration is not there. */
    private fun webWords(text: String): List<String> = Regex("'([^']*)'").findAll(arrayBody(text)).map { it.groupValues[1] }.toList()

    private fun arrayBody(text: String): String {
        val at = text.indexOf(DECLARATION)
        assertTrue("`$DECLARATION` is not in $WORDLIST", at >= 0)
        val start = at + DECLARATION.length
        val end = text.indexOf(']', start)
        assertTrue("the word list in $WORDLIST has no end", end >= 0)
        return text.substring(start, end)
    }

    /** [text] with its array's words replaced by [words]. */
    private fun withWords(text: String, words: List<String>): String {
        val body = arrayBody(text)
        return text.replace(body, words.joinToString(", ", prefix = "\n  ", postfix = ",\n") { "'$it'" })
    }

    /**
     * A file of this repository, found by walking up from the working directory: Gradle runs the tests in
     * the module directory, and a runner outside Gradle may run them from the root. Not finding it fails:
     * a drift test that cannot see the web's file must not pass.
     */
    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("could not find $relative from ${System.getProperty("user.dir")}")
    }
}
