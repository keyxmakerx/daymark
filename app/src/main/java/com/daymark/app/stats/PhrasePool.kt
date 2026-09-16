package com.daymark.app.stats

/**
 * The openers — a small set of fixed, human-written lines, rotated so the app does not repeat itself.
 *
 * Every string below was typed by a person and lives in this file. Nothing is generated, nothing is
 * assembled from parts, nothing is filled in from a template, and there are no numbers slotted in:
 * these are not even the "fixed template with the person's own numbers" [Signals] uses, they are
 * just twelve sentences. `CLAUDE.md` §0 is the reason — generated text aimed at someone in distress
 * is the hazard the whole architecture exists to avoid, and an opener is the very first thing the
 * app says when it decides to speak.
 *
 * ## The one rule that matters most here: the draw is blind to how the person seemed
 *
 * A phrasing is **never** chosen because of how someone appeared to be doing. There is no path from
 * a mood value, a check-in score, an entry, a screener, a trend or anything else about the person to
 * the string that comes back — and the way that is guaranteed is the **signature**: [opener] takes a
 * [Band] and a rotation counter, [openerForHour] takes an hour and a rotation counter, and there is
 * no third way in. Nothing else is reachable, so there is nothing to get wrong at a call site.
 *
 * This is not squeamishness about personalisation. An app that softens its wording when it thinks
 * you are struggling has (a) made a clinical reading with no instrument and no consent, which §D1a
 * forbids outright, and (b) told you what it thinks of you, in a sentence you did not ask for, at
 * the worst possible moment. The failure mode is not a bad sentence — it is a person learning to
 * read the app's tone as a verdict on them.
 *
 * `PhrasePoolTest` pins this two ways rather than one, because each is blind to the other's
 * failure: a reflection check over the declared signatures (nothing typed can get in) and a scan of
 * this file's own code with the comments stripped (nothing named can get in either, including an
 * `Int` that happens to be a level rather than an hour). Both carry a planted positive control, so
 * a check that has gone blind fails instead of passing.
 *
 * ## Why the strings never name the hour
 *
 * [Greeting]'s header records the hazard: "Good evening" at 3am reads as a machine that is not
 * paying attention. A greeting can afford that because it has four bands and a neutral one to fall
 * back on; an opener that has to work at any hour the gate permits cannot. So the clock is used for
 * the one thing it states without judgement — whether the day is **ahead of** the person or
 * **behind** them, which noon decides and nothing else is needed to decide — and no line says
 * "morning" or "evening" out loud. Get the band wrong and the worst case is a line that is merely
 * general, never one that is wrong.
 *
 * Pure and Android-free like the rest of `stats/`: it holds no clock and no random source, so the
 * caller owns both the hour and the rotation counter, and the same arguments always give the same
 * sentence.
 */
object PhrasePool {

    /**
     * Which pool to draw from. Two bands, decided by the clock alone.
     *
     * [Morning] is the half of the day with the day still ahead; [Evening] is the half with it
     * behind. That is the only distinction drawn, and it is a fact about a clock rather than a
     * reading of a person.
     */
    enum class Band { Morning, Evening }

    /**
     * The day-ahead openers.
     *
     * Six lines, in the register the rest of the product uses — an invitation with the way out
     * stated in the same breath, the way `Signals` says "There's nothing you have to do — but a few
     * gentle options are here if you want them." No exclamation marks, no congratulation, nothing
     * that assumes anything went well or badly, and nothing that names an hour.
     */
    val MORNING: List<String> = listOf(
        "Whenever you have a minute today.",
        "A quiet minute, if there is one.",
        "No rush. This is here when you want it.",
        "Here if you'd like to make a note before the day gets going.",
        "Only if it suits — nothing here is owed.",
        "Here when you want it, and not before.",
    )

    /**
     * The day-behind openers. Same register, same rules, different six.
     *
     * "Whatever kind of day it was, this is here" is doing deliberate work: it is the app saying
     * out loud that it is not guessing, which is both true and the friendliest thing it can say at
     * that hour.
     */
    val EVENING: List<String> = listOf(
        "Whenever you have a minute.",
        "A quiet minute now, if there is one.",
        "Here if you'd like to write anything down from today.",
        "No rush. Today can go in whenever you like.",
        "Whatever kind of day it was, this is here.",
        "Nothing you have to do — it's here either way.",
    )

    /**
     * Which pool an hour draws from. Noon is the line, and nothing else is consulted.
     *
     * Total over every [Int] on purpose: an hour outside 0..23 is wrapped rather than rejected, so
     * a caller cannot make this throw and cannot get a null back that it would then have to invent
     * a fallback sentence for.
     */
    fun bandForHour(hour: Int): Band {
        val wrapped = ((hour % 24) + 24) % 24
        return if (wrapped < 12) Band.Morning else Band.Evening
    }

    /** Every line in one pool, in order — what the debug screen shows. */
    fun pool(band: Band): List<String> = when (band) {
        Band.Morning -> MORNING
        Band.Evening -> EVENING
    }

    /**
     * One opener.
     *
     * [rotation] is a counter the **caller** owns and persists, advanced with [nextRotation] each
     * time a line is used, so the app works through a pool rather than repeating itself. It is the
     * only other input, and it carries nothing about the person: it is a position in a list.
     *
     * Any [Int] is accepted, including a negative one, so a counter that has been reset, restored
     * from a backup or wrapped cannot produce a crash on the path where the app is about to speak.
     */
    fun opener(band: Band, rotation: Int): String {
        val lines = pool(band)
        val size = lines.size
        val index = ((rotation % size) + size) % size
        return lines[index]
    }

    /** The same draw, for a caller that has an hour rather than a [Band]. */
    fun openerForHour(hour: Int, rotation: Int): String = opener(bandForHour(hour), rotation)

    /**
     * The next value of the rotation counter.
     *
     * Wraps at [Int.MAX_VALUE] instead of overflowing, because an overflow here would be a negative
     * counter that still works (see [opener]) but reads as a bug to anyone who later looks at the
     * stored value.
     */
    fun nextRotation(rotation: Int): Int = if (rotation == Int.MAX_VALUE) 0 else rotation + 1
}
