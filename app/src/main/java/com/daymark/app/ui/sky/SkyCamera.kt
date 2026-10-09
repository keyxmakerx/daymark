package com.daymark.app.ui.sky

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.daymark.app.sky.SkyOpening
import kotlin.math.hypot
import kotlin.math.min

/**
 * Where the Sky is looked at from: the point of the sky at the middle of the screen, and a zoom.
 *
 * The camera holds a point of the sky rather than a pan in pixels, so the view stays on the same
 * stars when the sky's extent changes under it (a new memory, a day's drift, a rotated screen).
 * Every piece of arithmetic is [SkyPresentation]'s, where a plain-JVM test reaches it; what is here
 * is state and the order things happen in.
 *
 * Zoom is relative to the whole-sky view: at 1 all of it fits. A journey ([flyTo]) is a
 * [SkyOpening.Flight], advanced one frame at a time by whoever runs the frame loop ([step]); with
 * motion off there are no journeys, only cuts.
 */
@Stable
internal class SkyCamera {

    var x by mutableFloatStateOf(Float.NaN)
        private set
    var y by mutableFloatStateOf(Float.NaN)
        private set
    var zoom by mutableFloatStateOf(SkyPresentation.DEFAULT_ZOOM)
        private set

    /** True once the surface has measured a screen and a sky, and the camera is somewhere. */
    var ready by mutableStateOf(false)
        private set

    /** Bumped for each new journey, so a frame loop keyed on it starts for it. */
    var journey by mutableIntStateOf(0)
        private set

    // State too, so a draw that read them redraws when the sky's extent changes under it.
    var viewportWidth by mutableFloatStateOf(0f)
        private set
    var viewportHeight by mutableFloatStateOf(0f)
        private set
    var bounds by mutableStateOf(SkyPresentation.Bounds(0f, 0f, 1f, 1f))
        private set
    var fitPx by mutableFloatStateOf(0f)
        private set

    private var flight: SkyOpening.Flight? = null
    private var flightStart = -1L

    val isFlying: Boolean get() = flight != null

    val scalePx: Float get() = SkyPresentation.scalePx(fitPx, zoom)

    /** How many screen widths the sky's width spans now: what decides how much detail is drawn. */
    val screenWidthsAcross: Float get() = if (viewportWidth > 0f) scalePx / viewportWidth else 0f

    /** The most this screen can zoom in: see [SkyPresentation.zoomLimit]. */
    val zoomLimit: Float get() = SkyPresentation.zoomLimit(fitPx, viewportWidth)

    /** The screen and the sky's extent, whenever either changes. The first one places the camera. */
    fun measure(width: Float, height: Float, sky: SkyPresentation.Bounds) {
        if (width <= 0f || height <= 0f) return
        viewportWidth = width
        viewportHeight = height
        bounds = sky
        fitPx = SkyPresentation.fitPx(width, height, sky)
        if (x.isNaN() || y.isNaN()) {
            x = (sky.left + sky.right) / 2f
            y = (sky.top + sky.bottom) / 2f
            zoom = SkyPresentation.DEFAULT_ZOOM
        }
        if (flight == null) keepInSky()
        ready = true
    }

    /** Where the sky's left and top edges are on screen now. */
    fun panX(): Float = pan(x, bounds.left, bounds.width, viewportWidth)

    fun panY(): Float = pan(y, bounds.top, bounds.height, viewportHeight)

    /** On one axis: the pan that centres the camera's point, held inside the sky unless mid-flight. */
    private fun pan(centre: Float, origin: Float, extent: Float, viewport: Float): Float {
        val scale = scalePx
        val raw = SkyPresentation.panToCentre(centre, origin, scale, viewport)
        // A journey that draws back passes through views the clamp would refuse, so it is drawn as
        // it goes and only its end is held inside the sky.
        return if (flight != null) raw else SkyPresentation.clampPan(raw, extent * scale, viewport)
    }

    /** Straight there, with no journey. */
    fun snapTo(toX: Float, toY: Float, toZoom: Float) {
        flight = null
        x = toX
        y = toY
        zoom = toZoom.coerceIn(SkyPresentation.MIN_ZOOM, zoomLimit)
        keepInSky()
    }

    /** A journey there, or a cut when motion is off. */
    fun flyTo(toX: Float, toY: Float, toZoom: Float, motion: Boolean) {
        if (!ready) return
        val target = toZoom.coerceIn(SkyPresentation.MIN_ZOOM, zoomLimit)
        if (!motion || x.isNaN()) {
            snapTo(toX, toY, target)
            return
        }
        val wider = min(zoom, target)
        val apart = hypot(toX - x, toY - y) * SkyPresentation.scalePx(fitPx, wider) / viewportWidth
        flight = SkyOpening.Flight(x, y, zoom, toX, toY, target, apart)
        flightStart = -1L
        journey++
    }

    /** The whole sky. */
    fun fitAll(motion: Boolean) {
        val midX = (bounds.left + bounds.right) / 2f
        val midY = (bounds.top + bounds.bottom) / 2f
        flyTo(midX, midY, SkyPresentation.DEFAULT_ZOOM, motion)
    }

    /** Advances a journey to [frameMillis]. Returns true when there is nothing left to do. */
    fun step(frameMillis: Long): Boolean {
        val f = flight ?: return true
        if (flightStart < 0L) flightStart = frameMillis
        val done = f.at(frameMillis - flightStart)
        x = f.x
        y = f.y
        zoom = f.zoom
        if (done) {
            flight = null
            keepInSky()
        }
        return done
    }

    /**
     * A pinch and a drag, as one movement: the point under the fingers stays under them while the
     * zoom changes, then the drag moves the sky, then the result is held inside the sky. Any
     * journey under way stops where it is.
     */
    fun gesture(focusX: Float, focusY: Float, dragX: Float, dragY: Float, factor: Float) {
        if (!ready) return
        flight = null
        val from = zoom
        val to = (from * factor).coerceIn(SkyPresentation.MIN_ZOOM, zoomLimit)
        val scale = SkyPresentation.scalePx(fitPx, to)
        val heldX = SkyPresentation.panForZoomAbout(focusX, panX(), from, to) + dragX
        val heldY = SkyPresentation.panForZoomAbout(focusY, panY(), from, to) + dragY
        val leftAt = SkyPresentation.clampPan(heldX, bounds.width * scale, viewportWidth)
        val topAt = SkyPresentation.clampPan(heldY, bounds.height * scale, viewportHeight)
        zoom = to
        x = SkyPresentation.fromScreen(viewportWidth / 2f, bounds.left, scale, leftAt)
        y = SkyPresentation.fromScreen(viewportHeight / 2f, bounds.top, scale, topAt)
    }

    /** Holds the zoom in range and the camera's point where the sky still fills the screen. */
    private fun keepInSky() {
        if (viewportWidth <= 0f || fitPx <= 0f) return
        zoom = zoom.coerceIn(SkyPresentation.MIN_ZOOM, zoomLimit)
        val scale = scalePx
        x = SkyPresentation.fromScreen(viewportWidth / 2f, bounds.left, scale, panX())
        y = SkyPresentation.fromScreen(viewportHeight / 2f, bounds.top, scale, panY())
    }
}
