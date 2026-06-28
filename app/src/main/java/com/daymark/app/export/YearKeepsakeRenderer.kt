package com.daymark.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import com.daymark.app.stats.YearReview
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders a year's "Review" into a square-ish keepsake image (a night sky of the year's stars plus
 * its honest numbers) using plain [Canvas] — no third-party deps, fully offline. Purpose-designed
 * for sharing rather than a screenshot, so it looks the same on every device. Mood colours are
 * passed in (ARGB) so custom palettes carry through. All copy comes from [YearReview] (no AI).
 */
@Singleton
class YearKeepsakeRenderer @Inject constructor() {

    fun render(review: YearReview.Review, moodArgb: IntArray): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)

        // The sky: every logged day a star, scattered deterministically across the whole card.
        val levels = review.chapters.flatMap { it.starLevels }
        drawSky(c, levels, moodArgb)

        // A soft scrim over the lower half so the text stays legible against the stars.
        val scrim = Paint().apply { color = BG; alpha = 200 }
        c.drawRect(0f, H * 0.5f, W.toFloat(), H.toFloat(), scrim)

        val serif = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        val sans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        val cx = W / 2f

        // Title.
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK; typeface = serif; textAlign = Paint.Align.CENTER; textSize = 68f
        }
        c.drawText("${review.totalStars} stars.", cx, H * 0.60f, title)
        c.drawText("This was your year.", cx, H * 0.60f + 84f, title)

        // Gentle subtitle.
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FAINT; typeface = sans; textAlign = Paint.Align.CENTER; textSize = 30f
        }
        c.drawText("Every day you noticed how you felt.", cx, H * 0.60f + 150f, sub)

        // Three honest stats.
        val statY = H * 0.78f
        stat(c, W * 0.22f, statY, review.avgMoodLabel ?: "–", "avg mood")
        stat(c, W * 0.50f, statY, review.brightestMonthLabel ?: "–", "brightest month")
        stat(c, W * 0.78f, statY, "${review.longestStreak}", "longest streak")

        // Wordmark footer.
        val foot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FAINT; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER; textSize = 34f
        }
        c.drawText("Daymark · ${review.year}", cx, H - 60f, foot)

        return bmp
    }

    private fun stat(c: Canvas, x: Float, y: Float, value: String, label: String) {
        val v = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER; textSize = 44f
        }
        // Shrink the value until it fits within its third of the width.
        val maxW = W / 3f - 24f
        while (v.measureText(value) > maxW && v.textSize > 22f) v.textSize -= 2f
        c.drawText(value, x, y, v)
        val l = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FAINT; typeface = Typeface.SANS_SERIF; textAlign = Paint.Align.CENTER; textSize = 24f
        }
        c.drawText(label, x, y + 36f, l)
    }

    private fun drawSky(c: Canvas, levels: List<Int>, moodArgb: IntArray) {
        // Faint background specks for depth.
        val speck = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
        for (i in 0 until 90) {
            speck.alpha = 20 + (h(i, 7) * 40).toInt()
            c.drawCircle(h(i, 1) * W, h(i, 2) * H, 1.5f + h(i, 3) * 2f, speck)
        }
        // The day-stars.
        levels.forEachIndexed { i, level ->
            val color = ColorUtils.blendARGB(moodArgb[(level - 1).coerceIn(0, 4)], 0xFFFFFFFF.toInt(), 0.18f)
            drawStar(c, h(i, 11) * W, h(i, 13) * (H * 0.92f), level, color)
        }
    }

    /** One star: a soft glowing dot for ordinary days, a cross-ray glint for the best. */
    private fun drawStar(c: Canvas, cx: Float, cy: Float, level: Int, color: Int) {
        val r = when (level) { 1 -> 5f; 2 -> 6.5f; 3 -> 8f; 4 -> 10f; else -> 12f }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        if (level >= 4) {
            p.alpha = 40; c.drawCircle(cx, cy, r * 2.6f, p)
            p.alpha = 80; c.drawCircle(cx, cy, r * 1.4f, p)
            p.alpha = 255
            p.strokeWidth = r * 0.5f; p.strokeCap = Paint.Cap.ROUND
            val ray = r * 2.7f
            c.drawLine(cx, cy - ray, cx, cy + ray, p)
            c.drawLine(cx - ray, cy, cx + ray, cy, p)
            c.drawCircle(cx, cy, r * 0.85f, p)
        } else {
            p.alpha = 56; c.drawCircle(cx, cy, r * 1.8f, p)
            p.alpha = 255; c.drawCircle(cx, cy, r * 0.75f, p)
        }
    }

    /** Stable pseudo-random in [0,1) from an index and salt (no Random — same keepsake every time). */
    private fun h(i: Int, salt: Int): Float {
        var x = (i * 73856093) xor (salt * 19349663)
        x = x xor (x ushr 13)
        x *= 1274126177
        return ((x ushr 8) and 0xFFFF) / 65536f
    }

    private companion object {
        const val W = 1080
        const val H = 1350
        const val BG = 0xFF16150F.toInt()
        const val INK = 0xFFEBE5D8.toInt()
        const val FAINT = 0xFF8E887A.toInt()
    }
}
