package com.daymark.app.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that rations interruptions after a hard day.
 *
 * The regression these guard against: the editor used to navigate away on *every* save at Awful or
 * Bad, gated only by an on/off, so the only escape was turning the whole feature off.
 */
class SupportOfferTest {

    private val day = 24 * 3_600_000L
    private val now = 1_700_000_000_000L

    @Test
    fun `never means never, whatever the history`() {
        assertFalse(SupportOffer.shouldInterrupt(SupportOfferFrequency.Never, 0L, now))
        assertFalse(SupportOffer.shouldInterrupt(SupportOfferFrequency.Never, now - 10 * day, now))
    }

    @Test
    fun `every time always interrupts — the old behaviour, kept as an explicit choice`() {
        assertTrue(SupportOffer.shouldInterrupt(SupportOfferFrequency.EveryTime, now - 1, now))
        assertTrue(SupportOffer.shouldInterrupt(SupportOfferFrequency.EveryTime, now, now))
    }

    @Test
    fun `first ever offer is always allowed`() {
        assertTrue(SupportOffer.shouldInterrupt(SupportOfferFrequency.OncePerDay, 0L, now))
        assertTrue(SupportOffer.shouldInterrupt(SupportOfferFrequency.OncePerWeek, 0L, now))
    }

    @Test
    fun `once a day holds off until a full day has passed`() {
        val f = SupportOfferFrequency.OncePerDay
        assertFalse(SupportOffer.shouldInterrupt(f, now, now))
        assertFalse(SupportOffer.shouldInterrupt(f, now - day + 1, now))
        assertTrue(SupportOffer.shouldInterrupt(f, now - day, now))
        assertTrue(SupportOffer.shouldInterrupt(f, now - 3 * day, now))
    }

    @Test
    fun `once a week holds off for seven days`() {
        val f = SupportOfferFrequency.OncePerWeek
        assertFalse(SupportOffer.shouldInterrupt(f, now - 6 * day, now))
        assertTrue(SupportOffer.shouldInterrupt(f, now - 7 * day, now))
    }

    @Test
    fun `logging several hard days in a row only interrupts once`() {
        val f = SupportOfferFrequency.OncePerDay
        var last = 0L
        var interruptions = 0
        // Six saves across a single afternoon — the exact shape of the reported problem.
        for (minute in listOf(0L, 5L, 20L, 45L, 90L, 200L)) {
            val at = now + minute * 60_000L
            if (SupportOffer.shouldInterrupt(f, last, at)) {
                interruptions++
                last = at
            }
        }
        assertEquals(1, interruptions)
    }

    @Test
    fun `a clock that jumps backwards does not lock the offer out forever`() {
        // Timezone change or a manual clock edit can leave a timestamp in the future. Treat that
        // as "no record" rather than silently withholding the offer until the clock catches up.
        val f = SupportOfferFrequency.OncePerDay
        assertTrue(SupportOffer.shouldInterrupt(f, now + 30 * day, now))
    }

    @Test
    fun `unknown or missing stored frequency falls back to the default`() {
        assertEquals(SupportOfferFrequency.DEFAULT, SupportOfferFrequency.fromKey(null))
        assertEquals(SupportOfferFrequency.DEFAULT, SupportOfferFrequency.fromKey("Hourly"))
        assertEquals(SupportOfferFrequency.Never, SupportOfferFrequency.fromKey("Never"))
    }

    @Test
    fun `the default is not every time`() {
        // The whole point of the fix: opting into gentle support must not opt you into being
        // moved somewhere you didn't ask to go, every single time.
        assertFalse(SupportOfferFrequency.DEFAULT == SupportOfferFrequency.EveryTime)
    }

    @Test
    fun `every frequency has plain summary wording`() {
        SupportOfferFrequency.entries.forEach { f ->
            assertTrue(SupportOffer.summary(f).isNotBlank())
        }
    }
}
