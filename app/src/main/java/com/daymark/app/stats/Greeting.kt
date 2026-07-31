package com.daymark.app.stats

/**
 * The Home header's greeting.
 *
 * Four fixed, human-written strings chosen by one rule over the clock — no generated text, no
 * inference about how the person is doing. The late-night band exists on purpose: "Good evening"
 * at 3am reads as a machine that isn't paying attention, and a neutral "Hello" never implies
 * anything about being awake at that hour.
 *
 * Pure and Android-free so it unit-tests on the JVM; the caller owns the clock and time zone.
 */
object Greeting {

    /** @param hour hour-of-day, 0..23, in the person's own time zone. */
    fun forHour(hour: Int): String = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Hello"
    }
}
