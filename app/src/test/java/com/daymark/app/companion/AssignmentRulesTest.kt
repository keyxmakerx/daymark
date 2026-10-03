package com.daymark.app.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AssignmentRules] against the web's own cases, one for one, and then against what the web's tests
 * do not ask (#177).
 *
 * The first two groups are `companion/web/src/lib/assignments/assignments.test.ts`'s "assignment
 * validation (capability gate)" and the validation half of `inbox.test.ts`, each named as the web
 * names it. The web asserts `ok` and, twice, a word in an error message; here every case asserts the
 * exact refusals, which is the stronger claim and the one the phone's inbox will choose its copy by.
 *
 * Every mutation below is derived from the value it changes and then asserted to differ from it,
 * so no case can pass by changing nothing (`CLAUDE.md` §5).
 */
class AssignmentRulesTest {

    private companion object {
        const val FP = "therapist-fp"

        /** The instrument ids of the web's catalogue, `companion/web/src/lib/instruments/catalog`. */
        val INSTRUMENTS = setOf("wellbeing-selfcheck", "focus-selfcheck", "compassion-hard-moment", "values-what-matters")

        /** The Companion's assignable catalogue, as the web's tests run against it. */
        val CATALOGUE = object : AssignableCatalogue {
            override fun instrumentExists(id: String): Boolean = id in INSTRUMENTS
            override fun taskExists(id: String): Boolean = id == "steady-attention"
        }

        /** A payload that passes its type's own check, for every type. */
        val VALID_PAYLOAD: Map<String, Map<String, Any?>> = mapOf(
            "questionnaire" to mapOf("instrumentId" to "wellbeing-selfcheck"),
            "task" to mapOf("taskId" to "steady-attention"),
            "largeAssessment" to mapOf(
                "bundle" to listOf(
                    mapOf("kind" to "questionnaire", "id" to "focus-selfcheck"),
                    mapOf("kind" to "task", "id" to "steady-attention"),
                ),
            ),
            "reminder" to mapOf("every" to "week", "count" to 1),
            "goal" to mapOf("title" to "A walk after lunch"),
            "setting" to mapOf("key" to "theme", "value" to "dark"),
        )

        /** The refusal each type's payload check gives. */
        val PAYLOAD_REFUSAL: Map<String, Refusal> = mapOf(
            "questionnaire" to Refusal.UNKNOWN_INSTRUMENT,
            "task" to Refusal.UNKNOWN_TASK,
            "largeAssessment" to Refusal.EMPTY_BUNDLE,
            "reminder" to Refusal.REMINDER_WITHOUT_CADENCE,
            "goal" to Refusal.GOAL_WITHOUT_TITLE,
            "setting" to Refusal.SETTING_NOT_ALLOWED,
        )
    }

    /** `assignments.test.ts` `grantFor`. */
    private fun grantFor(fp: String) = Grant(
        fp,
        mapOf(
            "assign.questionnaire" to CapabilityGrant(true, ApplyMode.PROPOSE),
            "assign.task" to CapabilityGrant(true, ApplyMode.AUTO),
            // Auto here must still be overridden for settings.
            "suggest.setting" to CapabilityGrant(true, ApplyMode.AUTO),
        ),
    )

    /** `grant.ts` `emptyGrant`: every capability present and off. */
    private fun emptyGrant(fp: String) =
        Grant(fp, AssignmentRules.ALL_CAPABILITIES.associateWith { CapabilityGrant(false, ApplyMode.PROPOSE) })

    /** Every capability granted, in [mode]. */
    private fun allGranted(fp: String, mode: ApplyMode = ApplyMode.PROPOSE) =
        Grant(fp, AssignmentRules.ALL_CAPABILITIES.associateWith { CapabilityGrant(true, mode) })

    /**
     * `grant.ts` `setCapability`, without its coercion of `suggest.setting` to `propose`. That coercion
     * belongs to the grant editor; these checks must hold without it.
     */
    private fun Grant.with(capability: String, granted: Boolean, apply: ApplyMode) =
        copy(capabilities = capabilities + (capability to CapabilityGrant(granted, apply)))

    /** `assignments.test.ts` `baseAssignment`: a questionnaire for a catalogue self-check. */
    private fun base(
        fp: String = FP,
        type: String = "questionnaire",
        capability: String = "assign.questionnaire",
        payload: Any? = mapOf("instrumentId" to "wellbeing-selfcheck"),
    ) = Assignment(type, capability, payload, fp)

    /** An assignment of [type] carrying the capability it requires. */
    private fun ofType(type: String, payload: Any? = VALID_PAYLOAD[type]) =
        base(type = type, capability = AssignmentRules.TYPE_CAPABILITY.getValue(type), payload = payload)

    private fun check(a: Assignment, grant: Grant = grantFor(FP), catalogue: AssignableCatalogue = CATALOGUE) =
        AssignmentRules.validate(a, grant, catalogue)

    // ---------------------------------------------------------------------------------------------
    // assignments.test.ts — "assignment validation (capability gate)", one for one.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `accepts a granted questionnaire assignment for a catalog instrument`() {
        val r = check(base())
        assertTrue(r.ok)
        assertEquals(ApplyMode.PROPOSE, r.applyMode)
        assertEquals(emptyList<Refusal>(), r.refusals)
    }

    @Test
    fun `rejects an assignment whose capability is not granted`() {
        val g = grantFor(FP).let { it.copy(capabilities = it.capabilities - "assign.questionnaire") }
        assertNotEquals("mutation did not land", grantFor(FP), g)
        val r = check(base(), g)
        assertFalse(r.ok)
        assertEquals(listOf(Refusal.NOT_GRANTED), r.refusals)
    }

    @Test
    fun `rejects a type-capability mismatch`() {
        val r = check(base(type = "task", capability = "assign.questionnaire"))
        assertFalse(r.ok)
        // The web asserts only `ok`. The payload still names a self-check, so the task check refuses
        // too: every check runs.
        assertEquals(listOf(Refusal.CAPABILITY_MISMATCH, Refusal.UNKNOWN_TASK), r.refusals)
    }

    @Test
    fun `rejects a non-catalog instrument`() {
        assertFalse("made-up" in INSTRUMENTS)
        val r = check(base(payload = mapOf("instrumentId" to "made-up")))
        assertFalse(r.ok)
        assertEquals(listOf(Refusal.UNKNOWN_INSTRUMENT), r.refusals)
    }

    @Test
    fun `rejects a setting key not on the allowlist (e g security keys)`() {
        val a = base(type = "setting", capability = "suggest.setting", payload = mapOf("key" to "pin", "value" to "1234"))
        val r = check(a)
        assertFalse(r.ok)
        assertEquals(listOf(Refusal.SETTING_NOT_ALLOWED), r.refusals)
    }

    @Test
    fun `accepts an allowlisted setting but NEVER auto-applies it`() {
        val a = base(type = "setting", capability = "suggest.setting", payload = mapOf("key" to "theme", "value" to "dark"))
        val r = check(a)
        assertTrue(r.ok)
        // The grant says auto, and it is overruled: settings are always propose, then accept.
        assertEquals(ApplyMode.AUTO, r.applyMode)
        assertFalse(AssignmentRules.shouldAutoApply(a, r.applyMode!!))
    }

    @Test
    fun `honours auto for a low-risk capability`() {
        val a = base(type = "task", capability = "assign.task", payload = mapOf("taskId" to "steady-attention"))
        val r = check(a)
        assertTrue(r.ok)
        assertTrue(AssignmentRules.shouldAutoApply(a, r.applyMode!!))
    }

    @Test
    fun `rejects an author that does not match the granted therapist`() {
        val other = "someone-else"
        assertNotEquals(FP, other)
        assertEquals(listOf(Refusal.AUTHOR_MISMATCH), check(base(fp = other), grantFor(FP)).refusals)
    }

    // ---------------------------------------------------------------------------------------------
    // inbox.test.ts — the cases that are decided by validation, one for one. The inbox's VERIFIED
    // and requiresAccept are, for an item that opened and verified, `ok` and `!shouldAutoApply`.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `VERIFIED for a granted questionnaire from the pinned therapist, and it waits for the owner`() {
        val g = emptyGrant(FP).with("assign.questionnaire", true, ApplyMode.PROPOSE)
        val a = base()
        val r = check(a, g)
        assertTrue(r.ok)
        assertFalse("propose must require accept", AssignmentRules.shouldAutoApply(a, r.applyMode!!))
    }

    @Test
    fun `a granted AUTO non-setting capability does not require accept`() {
        val g = emptyGrant(FP).with("assign.task", true, ApplyMode.AUTO)
        val a = base(type = "task", capability = "assign.task", payload = mapOf("taskId" to "steady-attention"))
        val r = check(a, g)
        assertTrue(r.ok)
        assertTrue(AssignmentRules.shouldAutoApply(a, r.applyMode!!))
    }

    @Test
    fun `suggest setting ALWAYS requires accept even when the grant marks it auto`() {
        // The web's grant editor stores `propose` for settings whatever was asked. This grant holds
        // `auto`, uncoerced, and the item still waits.
        val g = emptyGrant(FP).with("suggest.setting", true, ApplyMode.AUTO)
        val a = base(type = "setting", capability = "suggest.setting", payload = mapOf("key" to "theme", "value" to "dark"))
        val r = check(a, g)
        assertTrue(r.ok)
        assertEquals(ApplyMode.AUTO, r.applyMode)
        assertFalse(AssignmentRules.shouldAutoApply(a, r.applyMode!!))
    }

    @Test
    fun `REJECTED when the capability is not currently granted`() {
        val r = check(base(), emptyGrant(FP))
        assertFalse(r.ok)
        assertEquals(listOf(Refusal.NOT_GRANTED), r.refusals)
    }

    @Test
    fun `REJECTED when a setting key is OFF the allowlist (security keys can never apply)`() {
        val g = emptyGrant(FP).with("suggest.setting", true, ApplyMode.PROPOSE)
        val a = base(type = "setting", capability = "suggest.setting", payload = mapOf("key" to "pin", "value" to "1234"))
        assertEquals(listOf(Refusal.SETTING_NOT_ALLOWED), check(a, g).refusals)
    }

    @Test
    fun `REJECTED on a type-capability mismatch, whose payload is otherwise good`() {
        val g = emptyGrant(FP).with("assign.questionnaire", true, ApplyMode.PROPOSE)
        val a = base(type = "task", capability = "assign.questionnaire", payload = mapOf("taskId" to "steady-attention"))
        assertEquals(listOf(Refusal.CAPABILITY_MISMATCH), check(a, g).refusals)
    }

    @Test
    fun `a clinician who re-paired is refused under a grant still naming the old key, and accepted once it is re-bound`() {
        val oldFp = if (FP == "old-key-fp") "older-key-fp" else "old-key-fp"
        assertNotEquals(FP, oldFp)
        val oldGrant = emptyGrant(oldFp).with("assign.questionnaire", true, ApplyMode.PROPOSE)
        assertEquals(listOf(Refusal.AUTHOR_MISMATCH), check(base(), oldGrant).refusals)

        val rebound = oldGrant.copy(therapistFingerprint = FP)
        assertTrue(check(base(), rebound).ok)
    }

    // ---------------------------------------------------------------------------------------------
    // What the web's tests do not ask.
    // ---------------------------------------------------------------------------------------------

    /**
     * Every refusal, each caused on its own by an item that breaks that one rule and nothing else —
     * and every such item's valid twin accepted, so no case passes because the item was refused for
     * something else.
     */
    @Test
    fun `each check refuses on its own, with exactly its own refusal`() {
        val g = allGranted(FP)
        val cases: Map<Refusal, Assignment> = mapOf(
            Refusal.UNKNOWN_TYPE to base(type = "prescription"),
            Refusal.CAPABILITY_MISMATCH to base(capability = "assign.goal"),
            Refusal.NOT_GRANTED to base(),
            Refusal.AUTHOR_MISMATCH to base(fp = "$FP-other"),
            Refusal.UNKNOWN_INSTRUMENT to ofType("questionnaire", mapOf("instrumentId" to "phq9")),
            Refusal.UNKNOWN_TASK to ofType("task", mapOf("taskId" to "tova")),
            Refusal.EMPTY_BUNDLE to ofType("largeAssessment", mapOf("bundle" to emptyList<Any?>())),
            Refusal.UNKNOWN_BUNDLE_ITEM to ofType(
                "largeAssessment",
                mapOf("bundle" to listOf(mapOf("kind" to "questionnaire", "id" to "wellbeing-selfcheck"), mapOf("kind" to "task", "id" to "tova"))),
            ),
            Refusal.GOAL_WITHOUT_TITLE to ofType("goal", mapOf("title" to "   ")),
            Refusal.REMINDER_WITHOUT_CADENCE to ofType("reminder", mapOf("every" to "week")),
            Refusal.SETTING_NOT_ALLOWED to ofType("setting", mapOf("key" to "appLockPin", "value" to "0000")),
        )
        assertEquals("a refusal has no case", Refusal.entries.toSet(), cases.keys)
        for ((refusal, a) in cases) {
            val grant = if (refusal == Refusal.NOT_GRANTED) g.with(a.capability, false, ApplyMode.PROPOSE) else g
            assertEquals("$refusal", listOf(refusal), check(a, grant).refusals)
        }
        // The valid twins: every type, granted, with its good payload.
        for (type in AssignmentRules.TYPE_CAPABILITY.keys) {
            assertEquals(type, emptyList<Refusal>(), check(ofType(type), g).refusals)
        }
        assertTrue("the unknown type is really unknown", "prescription" !in AssignmentRules.TYPE_CAPABILITY)
        assertFalse("phq9 is the phone's own check-in, not the Companion's", CATALOGUE.instrumentExists("phq9"))
    }

    @Test
    fun `every refusal is collected, in the web's order`() {
        val g = grantFor(FP).let { it.copy(capabilities = it.capabilities - "assign.task") }
        val a = base(fp = "$FP-other", capability = "assign.task", payload = mapOf("instrumentId" to "made-up"))
        assertEquals(
            listOf(Refusal.CAPABILITY_MISMATCH, Refusal.NOT_GRANTED, Refusal.AUTHOR_MISMATCH, Refusal.UNKNOWN_INSTRUMENT),
            check(a, g).refusals,
        )
    }

    @Test
    fun `the apply mode is the grant's when nothing is refused, and absent when anything is`() {
        for (mode in ApplyMode.entries) {
            for (type in AssignmentRules.TYPE_CAPABILITY.keys) {
                assertEquals("$type in $mode", mode, check(ofType(type), allGranted(FP, mode)).applyMode)
                assertNull("$type in $mode", check(ofType(type, payload = null), allGranted(FP, mode)).applyMode)
            }
        }
    }

    @Test
    fun `granted is what counts - a capability switched off in auto mode is refused`() {
        val g = emptyGrant(FP).with("assign.questionnaire", false, ApplyMode.AUTO)
        val r = check(base(), g)
        assertEquals(listOf(Refusal.NOT_GRANTED), r.refusals)
        assertNull(r.applyMode)
    }

    /**
     * `read.share` and `authorGamePlan` are real capabilities, and the capability of no assignment
     * type. An assignment claiming either is refused for every type, however it is granted.
     */
    @Test
    fun `read share and authorGamePlan can never carry an assignment`() {
        val g = allGranted(FP, ApplyMode.AUTO)
        for (capability in listOf("read.share", "authorGamePlan")) {
            assertTrue(capability in AssignmentRules.ALL_CAPABILITIES)
            assertFalse(capability in AssignmentRules.TYPE_CAPABILITY.values)
            for (type in AssignmentRules.TYPE_CAPABILITY.keys) {
                val a = ofType(type).copy(capability = capability)
                assertEquals("$type claiming $capability", listOf(Refusal.CAPABILITY_MISMATCH), check(a, g).refusals)
            }
        }
    }

    /**
     * The four allowlisted keys are accepted, and nothing else is: not a PIN, a lock, a key, the
     * network, sync, backup or recovery, and not the allowed keys in another case or with a space.
     */
    @Test
    fun `only the four settings are allowed, and nothing near a lock, a key or the network`() {
        val g = allGranted(FP)
        fun setting(key: Any?) = ofType("setting", mapOf("key" to key, "value" to "x"))

        assertEquals(listOf("visibleSelfChecks", "reminderTime", "reminderCadence", "theme"), AssignmentRules.SETTING_ALLOWLIST)
        for (key in AssignmentRules.SETTING_ALLOWLIST) assertTrue(key, check(setting(key), g).ok)

        val nearMisses = AssignmentRules.SETTING_ALLOWLIST.flatMap { key ->
            listOf(key.uppercase(), key.replaceFirstChar { it.uppercaseChar() }, "$key ", " $key", key.dropLast(1), "$key\u0000")
        }
        for ((index, key) in nearMisses.withIndex()) {
            assertFalse("near miss $index is not a miss: '$key'", key in AssignmentRules.SETTING_ALLOWLIST)
        }
        val sensitive = listOf(
            "pin", "appPin", "PIN", "lock", "appLock", "lockTimeoutMinutes", "biometric", "biometricUnlock",
            "encryption", "journalEncryption", "encryptionKey", "dataKey", "syncPassphrase", "passphrase",
            "serverUrl", "network", "allowNetwork", "syncEnabled", "backup", "autoBackup", "backupLocation",
            "recoveryCode", "exportLocation", "crisisContacts", "safetyPlan", "",
        )
        for (key in nearMisses + sensitive) {
            assertEquals("'$key'", listOf(Refusal.SETTING_NOT_ALLOWED), check(setting(key), g).refusals)
        }
        for (key in listOf<Any?>(null, 1, true, listOf("theme"), mapOf("key" to "theme"))) {
            assertEquals("$key", listOf(Refusal.SETTING_NOT_ALLOWED), check(setting(key), g).refusals)
        }
        assertEquals(listOf(Refusal.SETTING_NOT_ALLOWED), check(ofType("setting", mapOf("value" to "dark")), g).refusals)
    }

    @Test
    fun `a suggested setting never applies on its own, in any mode, and everything else only in auto`() {
        val setting = ofType("setting")
        for (mode in ApplyMode.entries) assertFalse("$mode", AssignmentRules.shouldAutoApply(setting, mode))
        assertEquals(setOf("suggest.setting"), AssignmentRules.NEVER_AUTOMATIC)

        for (capability in AssignmentRules.ALL_CAPABILITIES - AssignmentRules.NEVER_AUTOMATIC) {
            val a = base(capability = capability)
            assertTrue(capability, AssignmentRules.shouldAutoApply(a, ApplyMode.AUTO))
            assertFalse(capability, AssignmentRules.shouldAutoApply(a, ApplyMode.PROPOSE))
        }
    }

    @Test
    fun `a bundle is refused when it is missing, empty or not a list, and once for each bad entry`() {
        val g = allGranted(FP)
        fun bundle(value: Any?) = ofType("largeAssessment", mapOf("bundle" to value))

        for (value in listOf<Any?>(null, emptyList<Any?>(), "wellbeing-selfcheck", mapOf("kind" to "task", "id" to "steady-attention"), 3)) {
            assertEquals("$value", listOf(Refusal.EMPTY_BUNDLE), check(bundle(value), g).refusals)
        }
        assertEquals(listOf(Refusal.EMPTY_BUNDLE), check(ofType("largeAssessment", emptyMap<String, Any?>()), g).refusals)

        val good = mapOf("kind" to "questionnaire", "id" to "values-what-matters")
        val badEntries = listOf<Any?>(
            null, // the web's validator throws on this one
            "wellbeing-selfcheck",
            mapOf("kind" to "journal", "id" to "wellbeing-selfcheck"),
            mapOf("kind" to "questionnaire", "id" to 7),
            mapOf("kind" to "questionnaire", "id" to "steady-attention"), // a task, filed as a self-check
            mapOf("kind" to "task", "id" to "wellbeing-selfcheck"), // a self-check, filed as a task
            mapOf("id" to "wellbeing-selfcheck"),
        )
        for (entry in badEntries) {
            assertEquals("$entry", listOf(Refusal.UNKNOWN_BUNDLE_ITEM), check(bundle(listOf(good, entry)), g).refusals)
        }
        assertEquals(List(badEntries.size) { Refusal.UNKNOWN_BUNDLE_ITEM }, check(bundle(badEntries), g).refusals)
        assertTrue(check(bundle(listOf(good, mapOf("kind" to "task", "id" to "steady-attention"))), g).ok)
    }

    /**
     * A goal's title is judged by JavaScript's `trim`, which is not Kotlin's `isBlank`: the two
     * disagree about U+FEFF and U+001C–U+001F, and the phone must decide as the web does.
     */
    @Test
    fun `a goal title is blank exactly when JavaScript's trim would leave nothing`() {
        val g = allGranted(FP)
        fun goal(title: Any?) = ofType("goal", mapOf("title" to title, "activityId" to null))

        val blank = listOf("", " ", "   ", "\t\n", "\r\n", "\u000B\u000C", " ", "﻿", "　  ", "     ")
        for (title in blank) assertEquals("'$title'", listOf(Refusal.GOAL_WITHOUT_TITLE), check(goal(title), g).refusals)
        for (title in listOf<Any?>(null, 42, true, listOf("Walk"))) {
            assertEquals("$title", listOf(Refusal.GOAL_WITHOUT_TITLE), check(goal(title), g).refusals)
        }
        assertEquals(listOf(Refusal.GOAL_WITHOUT_TITLE), check(ofType("goal", emptyMap<String, Any?>()), g).refusals)

        for (title in listOf("Walk", " a ", "\u001F", "​")) assertTrue("'$title'", check(goal(title), g).ok)

        // The two places Kotlin's own answer differs, so the cases above are not testing nothing.
        assertTrue("\u001F".isBlank())
        assertFalse("﻿".isBlank())
    }

    /**
     * A reminder needs an `every` JavaScript counts as true and a `count` that is a number, and no
     * more: the web accepts any truthy unit and any number. Whether a cadence makes sense is for the
     * code that applies it; these checks decide only what the web decides.
     */
    @Test
    fun `a reminder needs a truthy every and a numeric count, as the web does`() {
        val g = allGranted(FP)
        fun reminder(vararg fields: Pair<String, Any?>) = ofType("reminder", mapOf(*fields))

        val refused = listOf(
            reminder(),
            reminder("every" to "week"),
            reminder("count" to 1),
            reminder("every" to "", "count" to 1),
            reminder("every" to false, "count" to 1),
            reminder("every" to 0, "count" to 1),
            reminder("every" to -0.0, "count" to 1),
            reminder("every" to Double.NaN, "count" to 1),
            reminder("every" to null, "count" to 1),
            reminder("every" to "week", "count" to "1"),
            reminder("every" to "week", "count" to null),
        )
        for (a in refused) assertEquals("${a.payload}", listOf(Refusal.REMINDER_WITHOUT_CADENCE), check(a, g).refusals)

        val accepted = listOf(
            reminder("every" to "week", "count" to 1),
            reminder("every" to "day", "count" to 0L),
            reminder("every" to "month", "count" to 2.5),
            reminder("every" to true, "count" to 1),
            reminder("every" to emptyList<Any?>(), "count" to 1),
        )
        for (a in accepted) assertTrue("${a.payload}", check(a, g).ok)
    }

    @Test
    fun `a payload that is not an object is refused by its type's check, and never thrown on`() {
        val g = allGranted(FP)
        for (payload in listOf<Any?>(null, "wellbeing-selfcheck", 5, true, listOf(VALID_PAYLOAD["task"]))) {
            for (type in AssignmentRules.TYPE_CAPABILITY.keys) {
                assertEquals("$type with $payload", listOf(PAYLOAD_REFUSAL.getValue(type)), check(ofType(type, payload), g).refusals)
            }
        }
        // A type with no payload check is refused for its type alone.
        assertEquals(listOf(Refusal.UNKNOWN_TYPE), check(base(type = "prescription", payload = null), g).refusals)
    }

    @Test
    fun `the checks ask only the catalogue they are given, and NONE refuses every catalogue item`() {
        val g = allGranted(FP)
        val catalogueShaped = listOf("questionnaire", "task", "largeAssessment")
        for (type in catalogueShaped) {
            assertTrue(type, check(ofType(type), g, CATALOGUE).ok)
            assertFalse(type, check(ofType(type), g, AssignableCatalogue.NONE).ok)
        }
        // The other three types never consult it.
        for (type in AssignmentRules.TYPE_CAPABILITY.keys - catalogueShaped.toSet()) {
            assertTrue(type, check(ofType(type), g, AssignableCatalogue.NONE).ok)
        }
        // A catalogue that knew the phone's own check-in would pass it, so the choice of catalogue is
        // the caller's to get right — see AssignmentRules' header.
        val permissive = object : AssignableCatalogue {
            override fun instrumentExists(id: String) = id == "phq9"
            override fun taskExists(id: String) = false
        }
        val phq9 = ofType("questionnaire", mapOf("instrumentId" to "phq9"))
        assertFalse(check(phq9, g, CATALOGUE).ok)
        assertTrue(check(phq9, g, permissive).ok)
    }
}
