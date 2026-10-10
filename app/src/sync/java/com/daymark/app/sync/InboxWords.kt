package com.daymark.app.sync

import com.daymark.app.companion.Refusal
import com.daymark.synccrypto.ClinicianItems

/**
 * Every sentence the inbox says (#177): fixed, human-written lines with the owner's own name for the
 * clinician and the clinician's own words slotted in, and nothing else. Non-diagnostic throughout: an
 * item is guidance from a real clinician, never something Daymark concluded. A refusal names what
 * happened to the item (nothing was added), never a guess at who did what.
 */
object InboxWords {
    const val TITLE = "From your clinicians"
    const val ROW = "From your clinicians"
    const val ROW_HINT = "Game plans and suggestions your clinicians send you"
    const val ROW_LOCKED = "Open the key with your sync passphrase to see what your clinicians sent"
    const val FRAMING =
        "These come from clinicians you paired with. They are guidance from a real person; Daymark is not " +
            "diagnosing you. Nothing is added to your journal until you accept it."
    const val CHECK = "Check for new items"
    const val CHECKING = "Checking…"
    const val NONE_WAITING = "Nothing is waiting for you."
    const val NO_CLINICIANS = "Add and approve a clinician first, under Clinicians."
    const val WAITING_HEADING = "Waiting for you"
    const val ACCEPTED_HEADING = "Accepted"
    const val NONE_ACCEPTED = "Nothing accepted yet."
    const val ACCEPT = "Accept"
    const val DECLINE = "Decline"
    const val DISMISS = "Dismiss"
    const val DECLINED_NOTE = "Declined. Your clinician is not told; this phone just stops showing it."

    const val NO_ANSWER = "The server did not answer. Nothing has changed. Try again when you are ready."
    const val REFUSED = "The server refused the request. Nothing has changed."
    const val NOT_SAVED = "This could not be saved on your phone. Nothing was added."

    fun planFrom(name: String) = "A game plan from $name"
    fun planUpdate(name: String) = "An updated game plan from $name. Accepting replaces the version you accepted before."
    fun planWithdrawn(name: String) = "$name has withdrawn this game plan. Accepting marks it withdrawn on your phone."
    fun reviewEvery(count: Int, every: String) = "Review: every ${count.takeIf { it > 1 }?.let { "$it ${every}s" } ?: every}."
    fun perWeek(n: Int) = if (n == 1) "Once a week" else "$n times a week"
    const val KIND_GOAL = "Goal"
    const val KIND_EXERCISE = "Exercise"
    const val KIND_TASK = "Task"
    const val KIND_NOTE = "Note"
    fun kind(kind: String) = when (kind) {
        "goal" -> KIND_GOAL
        "exercise" -> KIND_EXERCISE
        "task" -> KIND_TASK
        else -> KIND_NOTE
    }

    fun suggestionFrom(name: String) = "A suggestion from $name"
    fun goal(title: String) = "A goal: $title"
    fun reminder(count: Long, every: String) = "A reminder, ${if (count == 1L) "once" else "$count times"} each $every"
    fun setting(key: String) = "A change to a setting: $key"
    const val SELF_CHECK = "A self-check"

    /** A suggestion as it was opened: its type and the clinician's own payload, in fixed words. */
    fun describe(type: String, payload: Any?): String {
        val fields = payload as? Map<*, *> ?: emptyMap<String, Any?>()
        return when (type) {
            "goal" -> (fields["title"] as? String)?.let { goal(it) } ?: SELF_CHECK
            "reminder" -> reminder((fields["count"] as? Number)?.toLong() ?: 1L, fields["every"] as? String ?: "week")
            "setting" -> setting(fields["key"] as? String ?: "")
            else -> SELF_CHECK
        }
    }
    fun theirNote(note: String) = "Their note: $note"
    fun addedOnItsOwn(n: Int) =
        if (n == 1) "One suggestion was added on its own, as you allowed for that kind of item."
        else "$n suggestions were added on their own, as you allowed for those kinds of item."

    const val CANNOT_ADD_SETTING = "This phone does not change settings for a clinician, so this cannot be added."
    const val CANNOT_ADD_SELF_CHECK = "This phone does not have that self-check, so it cannot be added."
    const val CANNOT_ADD_INCOMPLETE = "This item is incomplete, so it cannot be added."
    fun notAllowed(name: String) =
        "You have not allowed $name to send this kind of item, so it cannot be added. What each clinician " +
            "may send is set on your owner page."
    fun doesNotMatch(name: String) = "This item does not match what $name may send, so it cannot be added."

    /** The first refusal's words: the one that decides the item. */
    fun refused(refusals: List<Refusal>, name: String): String = when (refusals.first()) {
        Refusal.NOT_GRANTED -> notAllowed(name)
        Refusal.UNKNOWN_TYPE, Refusal.CAPABILITY_MISMATCH, Refusal.AUTHOR_MISMATCH -> doesNotMatch(name)
        Refusal.UNKNOWN_INSTRUMENT, Refusal.UNKNOWN_TASK, Refusal.EMPTY_BUNDLE, Refusal.UNKNOWN_BUNDLE_ITEM -> CANNOT_ADD_SELF_CHECK
        Refusal.GOAL_WITHOUT_TITLE, Refusal.REMINDER_WITHOUT_CADENCE -> CANNOT_ADD_INCOMPLETE
        Refusal.SETTING_NOT_ALLOWED -> CANNOT_ADD_SETTING
    }

    fun notBelieved(refusal: ClinicianItems.Refusal, name: String): String = when (refusal) {
        ClinicianItems.Refusal.NOT_OPENED ->
            "An item for you from $name's relationship could not be opened with your key. It was not sealed to " +
                "you, or it was changed on the way. Nothing was added."
        ClinicianItems.Refusal.NOT_THEIRS ->
            "An item is not signed by the key you recorded for $name. Nothing was added."
        ClinicianItems.Refusal.MISDIRECTED ->
            "An item signed by $name was not sent to you as it is now shown. Nothing was added."
        ClinicianItems.Refusal.MALFORMED -> "An item from $name could not be read. Nothing was added."
    }

    fun sameNameAsAnother(name: String) =
        "An item from $name has the same name as one you accepted from someone else, so it cannot replace it. " +
            "Nothing was added."

    fun grantNotBelieved(name: String) =
        "The permissions you set for $name could not be checked, so nothing they suggest can be added. Their " +
            "game plans are not affected."
    fun gone(n: Int) = if (n == 1) "One older item has expired on the server." else "$n older items have expired on the server."
    fun unreadable(n: Int) = if (n == 1) "One item could not be read. Nothing was added." else "$n items could not be read. Nothing was added."
    fun withdrawnBy(name: String) = "Withdrawn by $name"
    const val SOMEONE = "a clinician no longer on this phone"
}
