package com.daymark.app.ui.movement

/**
 * Original, hand-authored stick-figure poses drawn at runtime by [PoseFigure] (same Canvas
 * technique as our mood faces). Joint coordinates are normalized 0..1 (x right, y down) and placed
 * by us to depict a standard pose — only the *idea* of each pose is used (names and poses aren't
 * copyrightable); no third-party images are bundled. The joint set is a reduced COCO-style
 * skeleton used purely as a drawing template.
 */
enum class Joint { HEAD, NECK, PELVIS, SHOULDER_L, SHOULDER_R, ELBOW_L, ELBOW_R, WRIST_L, WRIST_R, HIP_L, HIP_R, KNEE_L, KNEE_R, ANKLE_L, ANKLE_R }

data class Pt(val x: Float, val y: Float)

data class Pose(val id: String, val name: String, val joints: Map<Joint, Pt>)

object PoseLibrary {

    /** Bones to stroke between joints. */
    val BONES: List<Pair<Joint, Joint>> = listOf(
        Joint.NECK to Joint.PELVIS,
        Joint.NECK to Joint.SHOULDER_L, Joint.NECK to Joint.SHOULDER_R,
        Joint.SHOULDER_L to Joint.ELBOW_L, Joint.ELBOW_L to Joint.WRIST_L,
        Joint.SHOULDER_R to Joint.ELBOW_R, Joint.ELBOW_R to Joint.WRIST_R,
        Joint.PELVIS to Joint.HIP_L, Joint.PELVIS to Joint.HIP_R,
        Joint.HIP_L to Joint.KNEE_L, Joint.KNEE_L to Joint.ANKLE_L,
        Joint.HIP_R to Joint.KNEE_R, Joint.KNEE_R to Joint.ANKLE_R,
    )

    // A neutral standing figure; each pose overrides a few joints from this base.
    private val STAND = mapOf(
        Joint.HEAD to Pt(0.50f, 0.12f), Joint.NECK to Pt(0.50f, 0.24f), Joint.PELVIS to Pt(0.50f, 0.56f),
        Joint.SHOULDER_L to Pt(0.42f, 0.26f), Joint.SHOULDER_R to Pt(0.58f, 0.26f),
        Joint.ELBOW_L to Pt(0.40f, 0.40f), Joint.ELBOW_R to Pt(0.60f, 0.40f),
        Joint.WRIST_L to Pt(0.39f, 0.54f), Joint.WRIST_R to Pt(0.61f, 0.54f),
        Joint.HIP_L to Pt(0.45f, 0.57f), Joint.HIP_R to Pt(0.55f, 0.57f),
        Joint.KNEE_L to Pt(0.45f, 0.76f), Joint.KNEE_R to Pt(0.55f, 0.76f),
        Joint.ANKLE_L to Pt(0.45f, 0.95f), Joint.ANKLE_R to Pt(0.55f, 0.95f),
    )

    private fun pose(id: String, name: String, overrides: Map<Joint, Pt>) =
        Pose(id, name, STAND + overrides)

    val MOUNTAIN = pose("mountain", "Mountain", emptyMap())

    val ARMS_UP = pose(
        "arms_up", "Upward reach",
        mapOf(
            Joint.ELBOW_L to Pt(0.43f, 0.13f), Joint.ELBOW_R to Pt(0.57f, 0.13f),
            Joint.WRIST_L to Pt(0.45f, 0.02f), Joint.WRIST_R to Pt(0.55f, 0.02f),
        ),
    )

    val FORWARD_FOLD = pose(
        "forward_fold", "Forward fold",
        mapOf(
            Joint.HEAD to Pt(0.50f, 0.62f), Joint.NECK to Pt(0.50f, 0.52f), Joint.PELVIS to Pt(0.50f, 0.30f),
            Joint.SHOULDER_L to Pt(0.45f, 0.50f), Joint.SHOULDER_R to Pt(0.55f, 0.50f),
            Joint.ELBOW_L to Pt(0.45f, 0.66f), Joint.ELBOW_R to Pt(0.55f, 0.66f),
            Joint.WRIST_L to Pt(0.46f, 0.82f), Joint.WRIST_R to Pt(0.54f, 0.82f),
            Joint.HIP_L to Pt(0.46f, 0.31f), Joint.HIP_R to Pt(0.54f, 0.31f),
            Joint.KNEE_L to Pt(0.46f, 0.62f), Joint.KNEE_R to Pt(0.54f, 0.62f),
            Joint.ANKLE_L to Pt(0.46f, 0.93f), Joint.ANKLE_R to Pt(0.54f, 0.93f),
        ),
    )

    val CHAIR = pose(
        "chair", "Chair",
        mapOf(
            Joint.NECK to Pt(0.46f, 0.26f), Joint.HEAD to Pt(0.45f, 0.15f), Joint.PELVIS to Pt(0.56f, 0.56f),
            Joint.ELBOW_L to Pt(0.40f, 0.16f), Joint.ELBOW_R to Pt(0.42f, 0.16f),
            Joint.WRIST_L to Pt(0.36f, 0.06f), Joint.WRIST_R to Pt(0.38f, 0.06f),
            Joint.SHOULDER_L to Pt(0.44f, 0.28f), Joint.SHOULDER_R to Pt(0.48f, 0.27f),
            Joint.HIP_L to Pt(0.55f, 0.56f), Joint.HIP_R to Pt(0.58f, 0.56f),
            Joint.KNEE_L to Pt(0.46f, 0.74f), Joint.KNEE_R to Pt(0.50f, 0.74f),
            Joint.ANKLE_L to Pt(0.50f, 0.95f), Joint.ANKLE_R to Pt(0.54f, 0.95f),
        ),
    )

    val WARRIOR = pose(
        "warrior", "Warrior",
        mapOf(
            Joint.ELBOW_L to Pt(0.28f, 0.30f), Joint.WRIST_L to Pt(0.16f, 0.30f),
            Joint.ELBOW_R to Pt(0.72f, 0.30f), Joint.WRIST_R to Pt(0.84f, 0.30f),
            Joint.SHOULDER_L to Pt(0.40f, 0.30f), Joint.SHOULDER_R to Pt(0.60f, 0.30f),
            Joint.HIP_L to Pt(0.42f, 0.58f), Joint.HIP_R to Pt(0.58f, 0.58f),
            Joint.KNEE_L to Pt(0.30f, 0.76f), Joint.ANKLE_L to Pt(0.18f, 0.95f),
            Joint.KNEE_R to Pt(0.66f, 0.78f), Joint.ANKLE_R to Pt(0.80f, 0.95f),
        ),
    )

    val PLANK = pose(
        "plank", "Plank",
        mapOf(
            Joint.HEAD to Pt(0.16f, 0.45f), Joint.NECK to Pt(0.26f, 0.47f), Joint.PELVIS to Pt(0.58f, 0.52f),
            Joint.SHOULDER_L to Pt(0.28f, 0.47f), Joint.SHOULDER_R to Pt(0.30f, 0.47f),
            Joint.ELBOW_L to Pt(0.28f, 0.60f), Joint.ELBOW_R to Pt(0.30f, 0.60f),
            Joint.WRIST_L to Pt(0.28f, 0.72f), Joint.WRIST_R to Pt(0.30f, 0.72f),
            Joint.HIP_L to Pt(0.58f, 0.52f), Joint.HIP_R to Pt(0.60f, 0.52f),
            Joint.KNEE_L to Pt(0.74f, 0.60f), Joint.KNEE_R to Pt(0.76f, 0.60f),
            Joint.ANKLE_L to Pt(0.90f, 0.70f), Joint.ANKLE_R to Pt(0.92f, 0.70f),
        ),
    )

    val DOWN_DOG = pose(
        "down_dog", "Downward dog",
        mapOf(
            Joint.HEAD to Pt(0.22f, 0.55f), Joint.NECK to Pt(0.28f, 0.50f), Joint.PELVIS to Pt(0.50f, 0.18f),
            Joint.SHOULDER_L to Pt(0.30f, 0.48f), Joint.SHOULDER_R to Pt(0.32f, 0.48f),
            Joint.ELBOW_L to Pt(0.26f, 0.62f), Joint.ELBOW_R to Pt(0.28f, 0.62f),
            Joint.WRIST_L to Pt(0.22f, 0.78f), Joint.WRIST_R to Pt(0.24f, 0.78f),
            Joint.HIP_L to Pt(0.49f, 0.19f), Joint.HIP_R to Pt(0.51f, 0.19f),
            Joint.KNEE_L to Pt(0.62f, 0.55f), Joint.KNEE_R to Pt(0.64f, 0.55f),
            Joint.ANKLE_L to Pt(0.74f, 0.88f), Joint.ANKLE_R to Pt(0.76f, 0.88f),
        ),
    )

    val COBRA = pose(
        "cobra", "Cobra",
        mapOf(
            Joint.HEAD to Pt(0.30f, 0.34f), Joint.NECK to Pt(0.34f, 0.44f), Joint.PELVIS to Pt(0.58f, 0.70f),
            Joint.SHOULDER_L to Pt(0.36f, 0.46f), Joint.SHOULDER_R to Pt(0.38f, 0.46f),
            Joint.ELBOW_L to Pt(0.34f, 0.60f), Joint.ELBOW_R to Pt(0.36f, 0.60f),
            Joint.WRIST_L to Pt(0.34f, 0.74f), Joint.WRIST_R to Pt(0.36f, 0.74f),
            Joint.HIP_L to Pt(0.58f, 0.70f), Joint.HIP_R to Pt(0.60f, 0.70f),
            Joint.KNEE_L to Pt(0.76f, 0.74f), Joint.KNEE_R to Pt(0.78f, 0.74f),
            Joint.ANKLE_L to Pt(0.92f, 0.78f), Joint.ANKLE_R to Pt(0.94f, 0.78f),
        ),
    )

    val CHILD = pose(
        "child", "Child's pose",
        mapOf(
            Joint.HEAD to Pt(0.22f, 0.66f), Joint.NECK to Pt(0.30f, 0.62f), Joint.PELVIS to Pt(0.66f, 0.62f),
            Joint.SHOULDER_L to Pt(0.32f, 0.60f), Joint.SHOULDER_R to Pt(0.34f, 0.60f),
            Joint.ELBOW_L to Pt(0.26f, 0.58f), Joint.ELBOW_R to Pt(0.28f, 0.58f),
            Joint.WRIST_L to Pt(0.16f, 0.56f), Joint.WRIST_R to Pt(0.18f, 0.56f),
            Joint.HIP_L to Pt(0.66f, 0.62f), Joint.HIP_R to Pt(0.68f, 0.62f),
            Joint.KNEE_L to Pt(0.78f, 0.74f), Joint.KNEE_R to Pt(0.80f, 0.74f),
            Joint.ANKLE_L to Pt(0.84f, 0.62f), Joint.ANKLE_R to Pt(0.86f, 0.62f),
        ),
    )

    val SQUAT = pose(
        "squat", "Squat",
        mapOf(
            Joint.PELVIS to Pt(0.50f, 0.62f),
            Joint.ELBOW_L to Pt(0.36f, 0.40f), Joint.WRIST_L to Pt(0.32f, 0.34f),
            Joint.ELBOW_R to Pt(0.64f, 0.40f), Joint.WRIST_R to Pt(0.68f, 0.34f),
            Joint.HIP_L to Pt(0.45f, 0.62f), Joint.HIP_R to Pt(0.55f, 0.62f),
            Joint.KNEE_L to Pt(0.40f, 0.74f), Joint.KNEE_R to Pt(0.60f, 0.74f),
            Joint.ANKLE_L to Pt(0.44f, 0.95f), Joint.ANKLE_R to Pt(0.56f, 0.95f),
        ),
    )

    val LUNGE = pose(
        "lunge", "Lunge",
        mapOf(
            Joint.PELVIS to Pt(0.48f, 0.56f),
            Joint.HIP_L to Pt(0.44f, 0.57f), Joint.HIP_R to Pt(0.52f, 0.57f),
            Joint.KNEE_L to Pt(0.30f, 0.74f), Joint.ANKLE_L to Pt(0.30f, 0.95f),
            Joint.KNEE_R to Pt(0.66f, 0.78f), Joint.ANKLE_R to Pt(0.82f, 0.95f),
        ),
    )

    val REST = pose(
        "rest", "Rest",
        mapOf(
            Joint.HEAD to Pt(0.12f, 0.50f), Joint.NECK to Pt(0.20f, 0.52f), Joint.PELVIS to Pt(0.56f, 0.54f),
            Joint.SHOULDER_L to Pt(0.22f, 0.50f), Joint.SHOULDER_R to Pt(0.22f, 0.54f),
            Joint.ELBOW_L to Pt(0.32f, 0.48f), Joint.ELBOW_R to Pt(0.32f, 0.56f),
            Joint.WRIST_L to Pt(0.42f, 0.46f), Joint.WRIST_R to Pt(0.42f, 0.58f),
            Joint.HIP_L to Pt(0.56f, 0.52f), Joint.HIP_R to Pt(0.56f, 0.56f),
            Joint.KNEE_L to Pt(0.74f, 0.52f), Joint.KNEE_R to Pt(0.74f, 0.56f),
            Joint.ANKLE_L to Pt(0.92f, 0.52f), Joint.ANKLE_R to Pt(0.92f, 0.56f),
        ),
    )

    val ALL = listOf(
        MOUNTAIN, ARMS_UP, FORWARD_FOLD, CHAIR, WARRIOR, PLANK, DOWN_DOG, COBRA, CHILD, SQUAT, LUNGE, REST,
    )

    fun byId(id: String): Pose = ALL.firstOrNull { it.id == id } ?: MOUNTAIN

    /** Linearly interpolates joint positions from [a] to [b] (t in 0..1) so a figure can flow
     *  smoothly between two poses. Uses [b]'s joint set; missing joints fall back to b. */
    fun lerp(a: Pose, b: Pose, t: Float): Pose {
        val f = t.coerceIn(0f, 1f)
        val joints = b.joints.mapValues { (joint, target) ->
            val from = a.joints[joint] ?: target
            Pt(from.x + (target.x - from.x) * f, from.y + (target.y - from.y) * f)
        }
        return Pose(b.id, b.name, joints)
    }
}
