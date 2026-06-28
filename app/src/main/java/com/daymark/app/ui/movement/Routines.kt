package com.daymark.app.ui.movement

/**
 * Movement routines built from public-domain sequences (e.g. Sun Salutation) and generic interval
 * formats (HIIT/Tabata, a bodyweight circuit of common movements). All step instructions are our
 * own words; no branded programs. Each step references a [PoseLibrary] pose id and a hold time.
 */
data class RoutineStep(val poseId: String, val label: String, val seconds: Int)

data class Routine(
    val id: String,
    val name: String,
    val description: String,
    val steps: List<RoutineStep>,
) {
    val totalSeconds: Int get() = steps.sumOf { it.seconds }
}

object Routines {

    val SUN_SALUTATION = Routine(
        id = "sun_salutation",
        name = "Sun salutation",
        description = "A gentle traditional flow to wake the body up. Move with your breath.",
        steps = listOf(
            RoutineStep("mountain", "Mountain — stand tall, breathe", 15),
            RoutineStep("arms_up", "Reach up", 12),
            RoutineStep("forward_fold", "Fold forward", 15),
            RoutineStep("lunge", "Step back to a lunge", 15),
            RoutineStep("plank", "Plank — hold steady", 15),
            RoutineStep("cobra", "Lower and lift to cobra", 15),
            RoutineStep("down_dog", "Downward dog", 20),
            RoutineStep("forward_fold", "Step forward, fold", 15),
            RoutineStep("arms_up", "Rise and reach", 12),
            RoutineStep("mountain", "Mountain — rest", 15),
        ),
    )

    val MORNING_STRETCH = Routine(
        id = "morning_stretch",
        name = "Wake-up stretch",
        description = "A short, easy stretch to loosen up.",
        steps = listOf(
            RoutineStep("arms_up", "Reach up tall", 20),
            RoutineStep("forward_fold", "Fold forward, soft knees", 20),
            RoutineStep("down_dog", "Downward dog", 25),
            RoutineStep("child", "Child's pose", 30),
            RoutineStep("cobra", "Gentle cobra", 20),
            RoutineStep("rest", "Rest and breathe", 20),
        ),
    )

    val QUICK_CIRCUIT = Routine(
        id = "quick_circuit",
        name = "Quick circuit",
        description = "A short bodyweight interval set of common movements. Work, then rest.",
        steps = buildList {
            repeat(3) {
                add(RoutineStep("squat", "Squats", 30))
                add(RoutineStep("rest", "Rest", 10))
                add(RoutineStep("lunge", "Lunges", 30))
                add(RoutineStep("rest", "Rest", 10))
            }
        },
    )

    val ALL = listOf(SUN_SALUTATION, MORNING_STRETCH, QUICK_CIRCUIT)

    fun byId(id: String): Routine? = ALL.firstOrNull { it.id == id }
}
