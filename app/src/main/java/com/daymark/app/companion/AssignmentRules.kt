package com.daymark.app.companion

/**
 * The checks an assignment from a clinician must pass on the owner's phone before it may apply or
 * even be offered: a port of `companion/web/src/lib/assignments/validate.ts` and of the constants it
 * takes from `types.ts` (#177). `docs/COMPANION_ASSIGNMENTS.md` §1 and §2 are the design.
 *
 * "Companion" here is the server, its consoles and the relationship channels, not the in-app
 * companion presence of `docs/DECISIONS.md` §D1b.
 *
 * ## Import-free, and why
 *
 * Nothing in this package imports anything: no Android, no Room, no `java.time`, no clock, no other
 * Daymark package. These checks are the security boundary on the side being protected — "the check
 * that binds, because it is run by the party being protected" (`docs/COMPANION_ASSIGNMENTS.md`
 * §2.2) — so they are kept where a plain JVM runs them in seconds (`tools/jvm-tests.sh companion`)
 * and where they cannot reach a network, a database or the time of day, the rule `sky/` and
 * `stats/` keep for the same reason. The package sits in `src/main`, beside the tables it guards,
 * because it can reach nothing: the offline `foss` build carries it and still has no way to talk to
 * a server (`docs/COMPANION_PHONE.md` §0).
 *
 * ## The web is the oracle
 *
 * The phone must refuse exactly what the owner's console refuses and accept exactly what it accepts
 * (`docs/COMPANION_PHONE.md` §3). `AssignmentRulesTest` ports the web's own cases one for one, and
 * `AssignmentRulesDriftTest` reads `types.ts` and `validate.ts` and fails when the capabilities, the
 * type-to-capability map, the setting allowlist, the apply modes, the capabilities that never apply
 * on their own, or the number of refusals differ from what is here.
 *
 * The web decides with JavaScript's `typeof`, truthiness and `String.prototype.trim`, so the same
 * decisions are made here over the values a decoded JSON payload holds — [Map], [List], [String],
 * [Number], [Boolean] and `null` — with JavaScript's meaning, not Kotlin's, wherever the two differ.
 *
 * One difference is deliberate. A payload, or an entry in a bundle, that is not an object makes the
 * web's function throw when it is `null`; here it is refused like any other malformed payload, so a
 * single signed item can never stop an inbox from evaluating the rest.
 *
 * ## The catalogue is passed in
 *
 * Whether an id names a self-check or a task is asked of the [AssignableCatalogue] the caller passes,
 * so this package imports no catalogue. The catalogue to pass is the Companion's assignable one: the
 * instrument ids of `companion/web/src/lib/instruments/catalog` and Steady Attention's task id,
 * because that is what a clinician assigns from (`docs/COMPANION_FEATURES.md` §4). Never the phone's
 * own check-ins in `ui/assessments/Assessments` — PHQ-9, GAD-7, WHO-5 — which no clinician can
 * assign. A caller holding no copy of the Companion's catalogue passes [AssignableCatalogue.NONE],
 * and then every self-check, task and bundle is refused as unknown: these checks fail closed.
 */
object AssignmentRules {

    /**
     * Every capability, in `types.ts` `ALL_CAPABILITIES` order. The order is the web's, because a
     * grant is serialised in it and the phone must write a grant's bytes exactly as the web does.
     */
    val ALL_CAPABILITIES: List<String> = listOf(
        "read.share",
        "assign.questionnaire",
        "assign.task",
        "assign.largeAssessment",
        "assign.reminder",
        "assign.goal",
        "authorGamePlan",
        "suggest.setting",
    )

    /**
     * The capability each assignment type requires. `types.ts` `TYPE_CAPABILITY`.
     *
     * `read.share` and `authorGamePlan` are the capability of no assignment type, so an assignment
     * claiming either is always refused, however it is granted.
     */
    val TYPE_CAPABILITY: Map<String, String> = linkedMapOf(
        "questionnaire" to "assign.questionnaire",
        "task" to "assign.task",
        "largeAssessment" to "assign.largeAssessment",
        "reminder" to "assign.reminder",
        "goal" to "assign.goal",
        "setting" to "suggest.setting",
    )

    /**
     * The only settings a clinician may propose. `types.ts` `SETTING_ALLOWLIST`.
     *
     * Nothing about a PIN, the lock, biometrics, encryption, the network, sync or backups is here,
     * and nothing like them may ever be added (`docs/COMPANION_ASSIGNMENTS.md` §2.2).
     */
    val SETTING_ALLOWLIST: List<String> = listOf("visibleSelfChecks", "reminderTime", "reminderCadence", "theme")

    /**
     * Capabilities whose assignments never apply on their own, whatever the grant's mode says: a
     * suggested setting always waits for the owner (`docs/COMPANION_ASSIGNMENTS.md` §1.2). The set
     * `validate.ts` `shouldAutoApply` names.
     */
    val NEVER_AUTOMATIC: Set<String> = setOf("suggest.setting")

    /**
     * Checks one assignment against the owner's current grant for its clinician. `validate.ts`
     * `validateAssignment`.
     *
     * Every check runs and every refusal is collected, in the web's order, so an item wrong in three
     * ways reports three. The apply mode comes back only when nothing was refused; it says whether
     * the item may apply on its own or must be offered, and [shouldAutoApply] has the last word.
     */
    fun validate(assignment: Assignment, grant: Grant, catalogue: AssignableCatalogue): AssignmentCheck {
        val refusals = ArrayList<Refusal>()

        // 1. The type exists, and the capability claimed is the one it requires.
        val required = TYPE_CAPABILITY[assignment.type]
        if (required == null) {
            refusals += Refusal.UNKNOWN_TYPE
        } else if (assignment.capability != required) {
            refusals += Refusal.CAPABILITY_MISMATCH
        }

        // 2. The owner grants that capability now.
        val capability = grant.capabilities[assignment.capability]
        if (capability?.granted != true) refusals += Refusal.NOT_GRANTED

        // 3. The author is the clinician this grant is for. The signature is verified separately,
        //    against the pinned key, before anything reaches these checks.
        if (assignment.authorFingerprint != grant.therapistFingerprint) refusals += Refusal.AUTHOR_MISMATCH

        // 4. The payload is in bounds for its type. A payload that is not an object has no fields.
        val payload = assignment.payload as? Map<*, *> ?: emptyMap<String, Any?>()
        when (assignment.type) {
            "questionnaire" -> {
                val id = payload["instrumentId"]
                if (id !is String || !catalogue.instrumentExists(id)) refusals += Refusal.UNKNOWN_INSTRUMENT
            }
            "task" -> {
                val id = payload["taskId"]
                if (id !is String || !catalogue.taskExists(id)) refusals += Refusal.UNKNOWN_TASK
            }
            "largeAssessment" -> {
                val bundle = payload["bundle"]
                if (bundle !is List<*> || bundle.isEmpty()) {
                    refusals += Refusal.EMPTY_BUNDLE
                } else {
                    for (entry in bundle) {
                        val fields = entry as? Map<*, *>
                        val id = fields?.get("id")
                        val known = when (fields?.get("kind")) {
                            "questionnaire" -> id is String && catalogue.instrumentExists(id)
                            "task" -> id is String && catalogue.taskExists(id)
                            else -> false
                        }
                        if (!known) refusals += Refusal.UNKNOWN_BUNDLE_ITEM
                    }
                }
            }
            "goal" -> {
                val title = payload["title"]
                if (title !is String || isBlankInJavaScript(title)) refusals += Refusal.GOAL_WITHOUT_TITLE
            }
            "reminder" -> {
                if (!isTruthyInJavaScript(payload["every"]) || payload["count"] !is Number) {
                    refusals += Refusal.REMINDER_WITHOUT_CADENCE
                }
            }
            "setting" -> {
                val key = payload["key"]
                if (key !is String || key !in SETTING_ALLOWLIST) refusals += Refusal.SETTING_NOT_ALLOWED
            }
        }

        return AssignmentCheck(refusals, if (refusals.isEmpty()) capability?.apply else null)
    }

    /**
     * Whether an assignment that passed [validate] applies on its own rather than waiting for the
     * owner. `validate.ts` `shouldAutoApply`: only in [ApplyMode.AUTO], and never for a capability in
     * [NEVER_AUTOMATIC].
     */
    fun shouldAutoApply(assignment: Assignment, mode: ApplyMode): Boolean {
        if (assignment.capability in NEVER_AUTOMATIC) return false
        return mode == ApplyMode.AUTO
    }

    /**
     * JavaScript's truthiness over a JSON value: `null`, `false`, `0`, `-0`, `NaN` and the empty
     * string are false, and everything else — including an empty list or object — is true.
     */
    private fun isTruthyInJavaScript(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Number -> value.toDouble().let { it != 0.0 && !it.isNaN() }
        else -> true
    }

    /**
     * Whether `s.trim()` in JavaScript would leave nothing. Not Kotlin's [String.isBlank], which
     * disagrees both ways: it keeps U+FEFF, which JavaScript trims, and trims U+001C–U+001F, which
     * JavaScript keeps.
     */
    private fun isBlankInJavaScript(s: String): Boolean = s.all { isJavaScriptWhiteSpace(it) }

    /**
     * ECMAScript's WhiteSpace and LineTerminator, which `trim` removes: tab, line feed, vertical tab,
     * form feed, carriage return, the line and paragraph separators, U+FEFF, and every Space_Separator
     * (Zs). The Zs code points are listed rather than looked up, so the answer does not move with the
     * JVM's Unicode tables; they have been these since Unicode 6.3.
     */
    private fun isJavaScriptWhiteSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', '﻿' -> true
        ' ', ' ', ' ', ' ', ' ', '　' -> true
        in ' '..' ' -> true
        else -> false
    }
}

/** How a granted capability applies: offered to the owner first, or at once. `types.ts` `ApplyMode`. */
enum class ApplyMode(val wire: String) {
    PROPOSE("propose"),
    AUTO("auto"),
}

/** One capability's entry in a grant. `types.ts` `CapabilityGrant`. */
data class CapabilityGrant(val granted: Boolean, val apply: ApplyMode)

/**
 * The owner's current grant for one clinician: which capabilities are on and in which mode, and the
 * fingerprint of the clinician signing key it is for. `types.ts` `Grant`, whose field name
 * `therapistFingerprint` is kept because it is the name the signed grant carries.
 *
 * [capabilities] is keyed by the capability's wire name. A capability with no entry is not granted.
 */
data class Grant(val therapistFingerprint: String, val capabilities: Map<String, CapabilityGrant>)

/**
 * The four fields of a signed assignment that [AssignmentRules] reads. `types.ts` `Assignment` has
 * more; they are the inbox's business, and the signed payload is kept whole where an accepted
 * assignment is stored (`AcceptedAssignment.payloadJson`).
 *
 * [type] and [capability] are the wire's strings, never enums: an assignment from a newer console,
 * or a forged one, can name a type or a capability this build does not know, and the checks must
 * refuse it rather than have nowhere to put it. [payload] is the decoded JSON value, whatever it
 * turned out to be.
 */
data class Assignment(
    val type: String,
    val capability: String,
    val payload: Any?,
    val authorFingerprint: String,
)

/**
 * What [AssignmentRules.validate] found. `validate.ts` `AssignmentCheck`, with each refusal a
 * [Refusal] rather than a sentence: what the owner is shown is fixed copy chosen from the refusal,
 * never a message made up here.
 */
data class AssignmentCheck(val refusals: List<Refusal>, val applyMode: ApplyMode?) {
    val ok: Boolean get() = refusals.isEmpty()
}

/**
 * Why an assignment was refused: one entry for each `fail(...)` in `validate.ts`, in its order.
 * `AssignmentRulesDriftTest` counts them, so a check added on the web cannot go unported.
 */
enum class Refusal {
    /** The type is none of the six. */
    UNKNOWN_TYPE,

    /** The capability claimed is not the one the type requires. */
    CAPABILITY_MISMATCH,

    /** The owner does not grant that capability to this clinician now. */
    NOT_GRANTED,

    /** The author's fingerprint is not the one this grant is for. */
    AUTHOR_MISMATCH,

    /** A self-check that is not in the catalogue passed in, or no id at all. */
    UNKNOWN_INSTRUMENT,

    /** A task that is not in the catalogue passed in, or no id at all. */
    UNKNOWN_TASK,

    /** A bundle that is missing, not a list, or empty. */
    EMPTY_BUNDLE,

    /** One entry of a bundle that is not a known self-check or task. Reported once per entry. */
    UNKNOWN_BUNDLE_ITEM,

    /** A goal whose title is missing, not text, or only white space. */
    GOAL_WITHOUT_TITLE,

    /** A reminder without a cadence: no `every`, or a `count` that is not a number. */
    REMINDER_WITHOUT_CADENCE,

    /** A setting whose key is not one of the four on the allowlist. */
    SETTING_NOT_ALLOWED,
}

/**
 * The catalogue lookups the checks need, passed in so that this package imports no catalogue. See
 * [AssignmentRules] for which catalogue a caller passes, and why never the phone's own check-ins.
 */
interface AssignableCatalogue {

    /** True when [id] names a catalogue self-check, which passed the honesty gate when it loaded. */
    fun instrumentExists(id: String): Boolean

    /** True when [id] names an assignable timed task. */
    fun taskExists(id: String): Boolean

    companion object {
        /** A catalogue that knows nothing, for a caller that holds none: every lookup is refused. */
        val NONE: AssignableCatalogue = object : AssignableCatalogue {
            override fun instrumentExists(id: String): Boolean = false
            override fun taskExists(id: String): Boolean = false
        }
    }
}
