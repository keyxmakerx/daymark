package com.daymark.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepMoodInsightTest {

    @Test
    fun tooFewNightsReturnsNull() {
        val pairs = listOf(5 to 4.0, 2 to 2.0)
        assertNull(SleepMoodInsight.compute(pairs, minNights = 5))
    }

    @Test
    fun splitsByMedianAndAveragesMood() {
        // quality -> day mood; better-sleep half should average higher mood here.
        val pairs = listOf(1 to 2.0, 2 to 3.0, 4 to 4.0, 5 to 5.0, 5 to 4.0, 1 to 1.0)
        val r = SleepMoodInsight.compute(pairs, minNights = 5)!!
        assertEquals(6, r.nights)
        // worse half = qualities [1,1,2] -> moods [1,2,3] avg 2.0
        assertEquals(2.0, r.worseSleepMood, 0.0001)
        // better half = qualities [4,5,5] -> moods [4,5,4] avg 4.333...
        assertEquals(13.0 / 3.0, r.betterSleepMood, 0.0001)
    }
}
