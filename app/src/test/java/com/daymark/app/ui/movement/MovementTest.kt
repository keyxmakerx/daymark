package com.daymark.app.ui.movement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementTest {

    @Test
    fun everyRoutineStepReferencesARealPose() {
        Routines.ALL.forEach { routine ->
            routine.steps.forEach { step ->
                assertTrue(
                    "Routine ${routine.id} references unknown pose ${step.poseId}",
                    PoseLibrary.ALL.any { it.id == step.poseId },
                )
                assertTrue("step must have positive duration", step.seconds > 0)
            }
        }
    }

    @Test
    fun totalSecondsIsSumOfSteps() {
        val r = Routines.SUN_SALUTATION
        assertEquals(r.steps.sumOf { it.seconds }, r.totalSeconds)
    }

    @Test
    fun posesHaveJointsInRange() {
        PoseLibrary.ALL.forEach { pose ->
            assertTrue("pose ${pose.id} has joints", pose.joints.isNotEmpty())
            pose.joints.values.forEach { p ->
                assertTrue("x in range", p.x in 0f..1f)
                assertTrue("y in range", p.y in 0f..1f)
            }
        }
    }

    @Test
    fun byIdFallsBackToMountain() {
        assertEquals("mountain", PoseLibrary.byId("nope").id)
        assertNotNull(Routines.byId("sun_salutation"))
    }
}
