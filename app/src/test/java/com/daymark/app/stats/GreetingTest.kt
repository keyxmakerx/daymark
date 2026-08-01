package com.daymark.app.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {

    @Test
    fun morningBandCoversFiveToEleven() {
        assertEquals("Good morning", Greeting.forHour(5))
        assertEquals("Good morning", Greeting.forHour(9))
        assertEquals("Good morning", Greeting.forHour(11))
    }

    @Test
    fun afternoonBandCoversNoonToFour() {
        assertEquals("Good afternoon", Greeting.forHour(12))
        assertEquals("Good afternoon", Greeting.forHour(16))
    }

    @Test
    fun eveningBandCoversFiveToNine() {
        assertEquals("Good evening", Greeting.forHour(17))
        assertEquals("Good evening", Greeting.forHour(21))
    }

    @Test
    fun lateNightIsNeutral() {
        // "Good evening" at 3am reads as a machine that isn't paying attention.
        assertEquals("Hello", Greeting.forHour(22))
        assertEquals("Hello", Greeting.forHour(0))
        assertEquals("Hello", Greeting.forHour(3))
        assertEquals("Hello", Greeting.forHour(4))
    }

    @Test
    fun everyHourOfTheDayHasAGreeting() {
        (0..23).forEach { hour ->
            assertEquals(true, Greeting.forHour(hour).isNotBlank())
        }
    }
}
