package com.daymark.app.companion

import com.daymark.app.backup.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The web's assignment rules, read as text, against [AssignmentRules] (#177).
 *
 * The owner's console and the phone must refuse the same things. The web is the oracle
 * (`docs/COMPANION_PHONE.md`), so when `types.ts` or `validate.ts` changes, this goes red on the line
 * that says what changed and the port follows. Six things are compared: the capabilities in order,
 * the capability each type requires, the setting allowlist, the apply modes, the capabilities that
 * never apply on their own, and the number of refusals. It also checks that the web's validator
 * still reads the two constants compared here, rather than a copy of its own this test cannot see.
 *
 * Every parser below is shown a planted difference and has to see it, so none of the equalities can
 * pass by reading nothing (`CLAUDE.md` §5).
 *
 * The setting allowlist is also pinned to its four keys outright, on both sides. Widening it is a
 * decision about what a clinician may change on somebody's phone, and it must fail a test, whichever
 * side makes it first.
 */
class AssignmentRulesDriftTest {

    private companion object {
        const val TYPES = "companion/web/src/lib/assignments/types.ts"
        const val VALIDATE = "companion/web/src/lib/assignments/validate.ts"

        val THE_FOUR_SETTINGS = setOf("visibleSelfChecks", "reminderTime", "reminderCadence", "theme")

        /** Fragments no allowlisted setting key may contain, on either side. */
        val SENSITIVE = listOf(
            "pin", "lock", "passcode", "password", "passphrase", "biometric", "crypt", "key", "secret",
            "token", "network", "server", "url", "sync", "backup", "export", "recovery", "crisis", "safety",
        )
    }

    private val types = repoFile(TYPES).readText()
    private val validate = repoFile(VALIDATE).readText()

    // ---------------------------------------------------------------------------------------------
    // Guards.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the web's sources were found and parse to something`() {
        assertTrue(types.contains("export const TYPE_CAPABILITY"))
        assertTrue(validate.contains("export function validateAssignment"))
        assertEquals(8, allCapabilities(types).size)
        assertEquals(6, typeCapability(types).size)
        assertEquals(4, settingAllowlist(types).size)
        assertEquals(2, applyModes(types).size)
        assertEquals(1, neverAutomatic(validate).size)
        assertTrue(failSites(validate) >= 11)
    }

    // ---------------------------------------------------------------------------------------------
    // The comparisons.
    // ---------------------------------------------------------------------------------------------

    /** In order: a grant is serialised in `ALL_CAPABILITIES` order, and its bytes must match the web's. */
    @Test
    fun `the capabilities are the web's, in the web's order`() {
        assertEquals(allCapabilities(types), AssignmentRules.ALL_CAPABILITIES)
        // The type union says the same as the list.
        assertEquals(capabilityUnion(types).toSet(), AssignmentRules.ALL_CAPABILITIES.toSet())
    }

    @Test
    fun `each type requires the capability the web says it requires`() {
        assertEquals(typeCapability(types), AssignmentRules.TYPE_CAPABILITY)
    }

    @Test
    fun `the setting allowlist is the web's, and it is exactly the four`() {
        assertEquals(settingAllowlist(types).toSet(), AssignmentRules.SETTING_ALLOWLIST.toSet())
        assertEquals(THE_FOUR_SETTINGS, AssignmentRules.SETTING_ALLOWLIST.toSet())
        assertEquals(THE_FOUR_SETTINGS, settingAllowlist(types).toSet())
        assertEquals("a key is listed twice", AssignmentRules.SETTING_ALLOWLIST.size, AssignmentRules.SETTING_ALLOWLIST.toSet().size)
    }

    @Test
    fun `no allowlisted setting is anything like a PIN, a lock, a key or the network, on either side`() {
        for (key in AssignmentRules.SETTING_ALLOWLIST + settingAllowlist(types)) {
            assertEquals("'$key' looks sensitive", emptyList<String>(), sensitiveIn(key))
        }
        // The detector sees the keys it exists to keep out.
        for (planted in listOf("appPin", "lockTimeout", "syncPassphrase", "serverUrl", "biometricUnlock", "encryptionKey", "autoBackup")) {
            assertTrue("detector is blind to '$planted'", sensitiveIn(planted).isNotEmpty())
        }
    }

    @Test
    fun `the apply modes are the web's`() {
        assertEquals(applyModes(types).toSet(), ApplyMode.entries.map { it.wire }.toSet())
    }

    @Test
    fun `the capabilities that never apply on their own are the web's`() {
        assertEquals(neverAutomatic(validate), AssignmentRules.NEVER_AUTOMATIC)
        // And the web's only other way to auto-apply is the mode itself.
        assertTrue(shouldAutoApplyBody(validate).contains("return mode === 'auto'"))
    }

    /**
     * One [Refusal] for each `fail(...)` in the web's validator. A check added there is a refusal the
     * phone does not make until it is ported, and this is where that shows.
     */
    @Test
    fun `there is one refusal for each check the web makes`() {
        assertEquals(failSites(validate), Refusal.entries.size)
    }

    /**
     * The web's validator reads the two constants compared above, from `types.ts`, for the two
     * checks they drive — so the comparisons are about the rules it runs, not about a list it no
     * longer uses. And it checks a payload for exactly the six types.
     */
    @Test
    fun `the web's validator reads the constants compared here, and checks a payload for every type`() {
        val imports = Regex("""import \{([^}]*)\} from '\./types'""").findAll(validate).flatMap { m ->
            m.groupValues[1].split(",").map { it.trim().removePrefix("type ").trim() }
        }.toSet()
        assertTrue("validate.ts no longer imports TYPE_CAPABILITY: $imports", "TYPE_CAPABILITY" in imports)
        assertTrue("validate.ts no longer imports SETTING_ALLOWLIST: $imports", "SETTING_ALLOWLIST" in imports)
        val body = validateBody(validate)
        assertTrue(body.contains("TYPE_CAPABILITY[a.type]"))
        assertTrue(body.contains("SETTING_ALLOWLIST as readonly string[]).includes(p.key)"))
        assertEquals(AssignmentRules.TYPE_CAPABILITY.keys, caseLabels(validate))
    }

    // ---------------------------------------------------------------------------------------------
    // Every parser sees a planted difference.
    // ---------------------------------------------------------------------------------------------

    /**
     * Each parser reads a planted change as a change. Compared with what the same parser reads from
     * the unplanted text, not with the constants here, so this shows the parser can see, whatever
     * the port says; the tests above then compare what it sees with the port.
     */
    @Test
    fun `each parser sees a difference planted in the web's text`() {
        val capabilities = types.replace("  'suggest.setting',\n]", "  'suggest.setting',\n  'suggest.pin',\n]")
        assertNotEquals("mutation did not land", types, capabilities)
        assertNotEquals(allCapabilities(types), allCapabilities(capabilities))

        val reordered = types.replace("  'read.share',\n  'assign.questionnaire',\n", "  'assign.questionnaire',\n  'read.share',\n")
        assertNotEquals("mutation did not land", types, reordered)
        assertNotEquals(allCapabilities(types), allCapabilities(reordered))

        val remapped = types.replace("  goal: 'assign.goal',", "  goal: 'suggest.setting',")
        assertNotEquals("mutation did not land", types, remapped)
        assertNotEquals(typeCapability(types), typeCapability(remapped))

        val widened = types.replace("'reminderCadence', 'theme']", "'reminderCadence', 'theme', 'appPin']")
        assertNotEquals("mutation did not land", types, widened)
        assertNotEquals(settingAllowlist(types).toSet(), settingAllowlist(widened).toSet())

        val moreModes = types.replace("export type ApplyMode = 'propose' | 'auto'", "export type ApplyMode = 'propose' | 'auto' | 'silent'")
        assertNotEquals("mutation did not land", types, moreModes)
        assertNotEquals(applyModes(types).toSet(), applyModes(moreModes).toSet())

        val stricter = validate.replace(
            "if (a.capability === 'suggest.setting') return false",
            "if (a.capability === 'suggest.setting') return false\n  if (a.capability === 'assign.goal') return false",
        )
        assertNotEquals("mutation did not land", validate, stricter)
        assertNotEquals(neverAutomatic(validate), neverAutomatic(stricter))

        val anotherCheck = validate.replace(
            "    case 'goal':\n",
            "    case 'goal':\n      if (typeof p.title === 'string' && p.title.length > 200) fail('goal title is too long')\n",
        )
        assertNotEquals("mutation did not land", validate, anotherCheck)
        assertEquals(failSites(validate) + 1, failSites(anotherCheck))

        val newType = validate.replace("    case 'setting':", "    case 'journalPrompt':\n      break\n    case 'setting':")
        assertNotEquals("mutation did not land", validate, newType)
        assertNotEquals(caseLabels(validate), caseLabels(newType))
    }

    // ---------------------------------------------------------------------------------------------
    // Parsing the web's TypeScript. Each reads one declaration and fails loudly if it is not there.
    // ---------------------------------------------------------------------------------------------

    private fun after(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        assertTrue("`$declaration` is not in the web's source", at >= 0)
        return source.substring(at + declaration.length)
    }

    private fun quoted(text: String): List<String> = Regex("'([^']*)'").findAll(text).map { it.groupValues[1] }.toList()

    private fun allCapabilities(src: String): List<String> =
        quoted(after(src, "export const ALL_CAPABILITIES: Capability[] = [").substringBefore("]"))

    private fun capabilityUnion(src: String): List<String> =
        quoted(after(src, "export type Capability =").substringBefore("\n\n"))

    private fun typeCapability(src: String): Map<String, String> =
        Regex("""(\w+): '([^']+)'""")
            .findAll(after(src, "export const TYPE_CAPABILITY: Record<AssignmentType, Capability> = {").substringBefore("}"))
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun settingAllowlist(src: String): List<String> =
        quoted(after(src, "export const SETTING_ALLOWLIST = [").substringBefore("]"))

    private fun applyModes(src: String): List<String> =
        quoted(after(src, "export type ApplyMode =").substringBefore("\n"))

    private fun shouldAutoApplyBody(src: String): String =
        after(src, "export function shouldAutoApply").substringBefore("\n}")

    private fun neverAutomatic(src: String): Set<String> =
        Regex("""a\.capability === '([^']+)'\) return false""").findAll(shouldAutoApplyBody(src))
            .map { it.groupValues[1] }.toSet()

    private fun validateBody(src: String): String =
        after(src, "export function validateAssignment").substringBefore("\n}")

    /** Calls of `fail(`, not its definition `const fail = (m: string) =>`. */
    private fun failSites(src: String): Int = Regex("""\bfail\(""").findAll(validateBody(src)).count()

    private fun caseLabels(src: String): Set<String> =
        Regex("""case '(\w+)':""").findAll(validateBody(src)).map { it.groupValues[1] }.toSet()

    private fun sensitiveIn(key: String): List<String> = SENSITIVE.filter { key.contains(it, ignoreCase = true) }
}
