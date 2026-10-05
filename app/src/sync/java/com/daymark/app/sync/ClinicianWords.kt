package com.daymark.app.sync

import com.daymark.synccrypto.ClinicianCeremony

/**
 * Every sentence the Clinicians screens say (#174). The pairing lines are the owner console's
 * (`OWNER_COPY` in `companion/web/src/lib/pairing/copy.ts`) word for word, with "console" and "page"
 * read as "phone" and "screen"; the rest are the screen design on #174. Fixed, human-written lines with
 * the owner's own name for the clinician slotted in, and nothing else.
 */
object ClinicianWords {
    const val TITLE = "Clinicians"
    const val ROW = "Clinicians"
    const val ROW_HINT = "Invite a clinician to read what you share with them"
    const val ROW_LOCKED = "Open the key with your sync passphrase to manage clinicians"
    const val NONE_YET = "No clinicians on this phone yet."
    const val UNREADABLE =
        "The clinicians kept on this phone cannot be read here, so nothing can be added or changed. " +
            "Nothing on the server has changed."
    const val ADD = "Add a clinician"

    const val NAME_LABEL = "Their name, as you want to see it"
    const val NAME_HINT = "Only you see this."
    const val NAME_REFUSED = "Use a name of up to 64 characters, with no hidden marks."

    const val TOKEN_TITLE = "Give them this sign-in key"
    const val TOKEN_BODY =
        "They enter this when they sign in. It is shown once: the server keeps only a fingerprint, so it " +
            "cannot be shown again."
    fun threeThings(name: String) =
        "You will give $name three things, one at a time: this key, then a link, then a short code. Each " +
            "does a different job and none works without the others."
    const val TOKEN_DONE = "I have given them the key"

    const val INVITE = "Invite them"
    const val TWO_CHANNELS =
        "Send the link however you like. Say the code some other way — out loud, by text, in the room. " +
            "Keeping them apart is what makes this work: anyone who ends up with only the link cannot get in."
    const val LINK_LABEL = "The link"
    const val LINK_GONE =
        "The link was shown when this invitation was made and is not kept, so it cannot be shown again. " +
            "If you no longer have it, stop this invitation and send a fresh one."
    const val SHOW_CODE = "Show the code"
    const val CODE_LABEL = "Say this code to them"
    const val CODE_HINT = "Eight characters. Case does not matter, and the dash is only there to read by."
    const val WAITING = "Waiting for them to type it. You can leave this screen open, or come back to it."
    const val WAITING_RESUMED =
        "This pairing is still open. The code was not kept when the screen closed, so it cannot be shown " +
            "again. If you already sent the link, give them a new code — it works with the link they have. " +
            "If you had not sent the link yet, stop this invitation and send a fresh one."
    const val CHECK = "Check for a reply"
    const val NEW_CODE = "New code"
    const val NEW_CODE_HINT =
        "Same link, different code. Ends this attempt and starts another. If you no longer have the link, " +
            "stop this invitation and send a fresh one."

    const val MISMATCH_TITLE = "A reply did not open with your code"
    fun mismatchBody(name: String) =
        "Keep this invitation open and ask $name whether they answered, or stop it and send a new link?"
    const val KEEP_OPEN = "Keep it open"

    const val ANSWERED_TITLE = "They typed the code"
    const val ANSWERED_BODY =
        "This opened with your code, so it came from the person you gave it to. Check the name reads as you " +
            "expect, then approve."
    const val NAME_THEY_ENTERED = "The name they entered"
    const val APPROVE = "Approve"

    fun replaceTitle(name: String) = "Replace the keys held for $name"
    fun replaceBody(name: String) = listOf(
        "The offer opened under the code you gave $name. Its keys are not the ones this phone holds for them.",
        "Approving records the new keys. Nothing further is sealed to the old ones.",
        "This does not reach what was already sealed to the old keys. Anyone holding the device that carried " +
            "them can still open every share sent to $name before now.",
        "You do not need to read these out: the code already did that job.",
        "If $name did not ask for this, do not approve.",
    )
    const val REPLACE_APPROVE = "Replace and approve"
    const val NOT_NOW = "Not now"
    const val REPLACED_BODY =
        "The new keys are recorded. Nothing further is sealed to the old ones, and what was already sealed " +
            "to them is unchanged."

    const val NOT_APPROVED = "Not approved"
    fun sameKeysAsOther(name: String, other: String) =
        "Those are the keys this phone has recorded for $other. Nothing was approved and nothing was " +
            "recorded: if they were also recorded for $name, a share meant for one of them could be opened by " +
            "the other. Check with $name on a channel that is not this server before going further."

    const val APPROVED_TITLE = "Approved"
    const val APPROVED_BODY =
        "They can finish setting up now. Nothing else is needed from you. If they never do, take the " +
            "approval back and start again with a new code."
    const val TAKE_BACK = "Take the approval back"

    fun attemptsLeft(n: Int) = "$n of 8 tries left on this invitation."
    fun wrongLinkTried(n: Long) =
        if (n == 1L) "One wrong invitation link has been tried against this invitation."
        else "$n wrong invitation links have been tried against this invitation."
    const val WRONG_LINK_ADVICE = "If that was not your clinician, you can stop this invitation."

    const val STOP = "Stop this invitation"
    const val STOP_BODY =
        "Sent it to the wrong person, lost the link, or no longer want it used? Ending it stops the link " +
            "working. It changes nothing already shared, and the other person is not told. You can send a " +
            "fresh one afterwards."
    const val KEEP = "Keep"
    const val STOPPED_BODY =
        "This invitation has ended. The old link no longer works. Nothing already shared has changed, and " +
            "nobody has been told. A fresh invitation comes with a new link and a new code."
    const val FRESH = "Send a fresh invitation"
    const val CAPPED_TITLE = "This invitation has been tried eight times"
    const val CAPPED_BODY = "The old link cannot be used again. A fresh invitation comes with a new link and a new code."
    const val EXPIRED_BODY =
        "This invitation is no longer open. The old link no longer works. A fresh invitation comes with a " +
            "new link and a new code."
    const val ENDED_CANCELLED = "You stopped this pairing. Start another whenever you are ready."
    const val ENDED_SUPERSEDED = "A newer code was made for this invitation, so this attempt is closed."
    const val LOOK_AGAIN = "Look again"

    const val COPY = "Copy"
    const val SHARE = "Share"

    const val NO_ANSWER_UNSENT = "The server did not answer. Nothing has changed. Try again when you are ready."
    const val NO_ANSWER_SENT =
        "The server did not answer, so this may have been done. Try again, or go back and the list shows " +
            "whether it was."
    const val REFUSED =
        "The server refused that. This invitation is unchanged. If it keeps happening, stop it and send a " +
            "fresh one."
    const val RUN_CHANGED = "This attempt has changed since you last looked. Check for a reply again before deciding."
    const val CONTRADICTED =
        "The server gave two different answers about this attempt, so it cannot be trusted. Stop this " +
            "invitation and send a fresh one. Nothing has been approved."
    const val NOT_RECORDED = "The approval was not recorded. Nothing is sealed to them. Check for a reply and try again."

    /** A halt's words. [sent] is whether the tap sent anything, which decides what no answer can claim. */
    fun halt(halt: ClinicianCeremony.Halt, sent: Boolean): String? = when (halt) {
        ClinicianCeremony.Halt.NO_ANSWER -> if (sent) NO_ANSWER_SENT else NO_ANSWER_UNSENT
        ClinicianCeremony.Halt.REFUSED -> REFUSED
        ClinicianCeremony.Halt.RUN_CHANGED -> RUN_CHANGED
        ClinicianCeremony.Halt.CONTRADICTED -> CONTRADICTED
        // Said by the screen in full, with both names, rather than as a halt line.
        ClinicianCeremony.Halt.OTHER_PERSON -> null
        ClinicianCeremony.Halt.NOT_RECORDED -> NOT_RECORDED
    }
}
