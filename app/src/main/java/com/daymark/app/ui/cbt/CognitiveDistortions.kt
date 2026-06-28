package com.daymark.app.ui.cbt

/**
 * Common "thinking traps" used in CBT, in our OWN wording (not copied from any specific
 * copyrighted checklist). Labels are prompts for reflection, never diagnoses.
 */
data class Distortion(val key: String, val name: String, val description: String)

object CognitiveDistortions {
    val ALL = listOf(
        Distortion("all_or_nothing", "All-or-nothing", "Seeing things in black-and-white, with no middle ground."),
        Distortion("catastrophizing", "Catastrophizing", "Jumping to the worst-case outcome."),
        Distortion("mind_reading", "Mind-reading", "Assuming you know what others are thinking."),
        Distortion("fortune_telling", "Fortune-telling", "Predicting the future as if it's certain."),
        Distortion("overgeneralizing", "Overgeneralizing", "Turning one event into an 'always' or 'never'."),
        Distortion("discounting_positives", "Discounting the good", "Brushing off what actually went well."),
        Distortion("emotional_reasoning", "Emotional reasoning", "\"I feel it, so it must be true.\""),
        Distortion("shoulds", "Shoulds & musts", "Rigid rules about how you or others have to be."),
        Distortion("labeling", "Labeling", "Turning a mistake into a verdict on who you are."),
        Distortion("personalizing", "Personalizing", "Taking the blame for things outside your control."),
    )

    fun nameFor(key: String): String = ALL.firstOrNull { it.key == key }?.name ?: key
}
