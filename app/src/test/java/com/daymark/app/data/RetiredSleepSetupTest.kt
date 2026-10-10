package com.daymark.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The old sleep setup's five answers are found by the keys it wrote, and nothing else is (#356). */
class RetiredSleepSetupTest {

    @Test
    fun the_five_keys_the_setup_wrote_are_the_ones_forgotten() {
        assertEquals(5, RetiredSleepSetup.KEYS.size)
        assertTrue(RetiredSleepSetup.KEYS.all { it.startsWith("sleep_profile_") })
    }

    @Test
    fun only_keys_still_held_are_deleted_and_nothing_else_is_touched() {
        val held = setOf("sleep_profile_pets", "sleep_profile_position", "lock_enabled", "sleep_log_reminder")
        assertEquals(setOf("sleep_profile_pets", "sleep_profile_position"), RetiredSleepSetup.present { it in held })
        // Control: a phone that never opened the setup has nothing to delete, so nothing is written.
        assertEquals(emptySet<String>(), RetiredSleepSetup.present { it in setOf("lock_enabled") })
        assertEquals(RetiredSleepSetup.KEYS, RetiredSleepSetup.present { true })
    }
}
