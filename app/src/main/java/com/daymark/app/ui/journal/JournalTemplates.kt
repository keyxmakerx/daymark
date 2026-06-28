package com.daymark.app.ui.journal

/**
 * Self-authored journal starters based on free, evidence-informed writing practices. The methods
 * (gratitude "three good things", expressive writing) are public; all wording here is our own.
 * These are gentle wellbeing practices, not therapy.
 */
data class JournalTemplate(
    val key: String,
    val label: String,
    val title: String,
    val body: String,
    /** Expressive writing can surface difficult feelings — show a supportive note when chosen. */
    val safetyNote: Boolean = false,
)

object JournalTemplates {
    val GRATITUDE = JournalTemplate(
        key = "three_good_things",
        label = "Three good things",
        title = "Three good things",
        body = "Three things that went well today — small ones count.\n\n" +
            "1. \n   Why it happened: \n\n" +
            "2. \n   Why it happened: \n\n" +
            "3. \n   Why it happened: \n",
    )

    val EXPRESSIVE = JournalTemplate(
        key = "expressive_writing",
        label = "Expressive writing",
        title = "",
        body = "For the next 15 minutes, write freely about what's weighing on you — your deepest " +
            "thoughts and feelings about it. Don't worry about spelling or grammar; just keep going.\n\n",
        safetyNote = true,
    )

    val REFLECT = JournalTemplate(
        key = "reflect",
        label = "Reflect on the day",
        title = "",
        body = "What stood out today?\nHow did I feel, and why?\nWhat would I like tomorrow to look like?\n\n",
    )

    val ALL = listOf(GRATITUDE, EXPRESSIVE, REFLECT)
}
