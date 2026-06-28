package com.daymark.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.daymark.app.ui.movement.Joint
import com.daymark.app.ui.movement.Pose
import com.daymark.app.ui.movement.PoseLibrary

/**
 * Renders a [Pose] as a clean line figure with the same Canvas primitives as the mood faces —
 * stroked bones, round joints, a head circle. Tints with the theme; no bitmap assets.
 */
@Composable
fun PoseFigure(pose: Pose, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (size.minDimension * 0.022f).coerceAtLeast(3f)
        fun at(j: Joint): Offset? = pose.joints[j]?.let { Offset(it.x * w, it.y * h) }

        PoseLibrary.BONES.forEach { (a, b) ->
            val pa = at(a); val pb = at(b)
            if (pa != null && pb != null) {
                drawLine(color, pa, pb, strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
        // Head as a circle centred on the HEAD joint.
        at(Joint.HEAD)?.let { head ->
            drawCircle(color, radius = size.minDimension * 0.06f, center = head, style = Stroke(width = stroke))
        }
        // Small dots at the limb joints for a hand-drawn feel.
        listOf(Joint.SHOULDER_L, Joint.SHOULDER_R, Joint.HIP_L, Joint.HIP_R, Joint.KNEE_L, Joint.KNEE_R, Joint.ELBOW_L, Joint.ELBOW_R)
            .forEach { j -> at(j)?.let { drawCircle(color, radius = stroke * 0.7f, center = it) } }
    }
}
