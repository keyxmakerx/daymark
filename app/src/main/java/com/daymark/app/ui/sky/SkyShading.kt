package com.daymark.app.ui.sky

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.daymark.app.sky.SkyShaders

/**
 * The sky's shaders on the screen: the space behind the stars, and a star close in drawn as a sun
 * ([SkyShaders]). Android 13 and later only, because that is where a shader can be written; below
 * it, and on any device where one will not compile, [create] returns null and the surface draws the
 * space's flat glows and a simple sun instead, so the sky is never missing anything that carries
 * meaning, only depth.
 *
 * An interface over the one class that touches the shader API, so that nothing else in the
 * renderer has to know which version it is running on.
 */
internal interface SkyShading {

    /**
     * The space behind the stars, filling this scope. The scope is the space's own layer,
     * [SkyShaders.SPACE_DOWN] times smaller than the screen each way, so every length here is in
     * its pixels.
     *
     * @param centreX the point of the sky at the middle of the screen, in the sky's units, and
     *   [centreY].
     * @param scale the layer's pixels to a unit of the sky, at the stars' own depth.
     * @param across screen widths across the sky, `1` for the whole sky.
     * @param seed where in its noise this sky's space begins ([SkyShaders.spaceSeed]).
     * @param space the space's tones, darkest first, then the accent; packed `0xRRGGBB`.
     * @param gas the gas's three colours, packed `0xRRGGBB`.
     * @param show how far the opening has brought the gas in, `0..1`.
     * @param lights the stars lighting the gas, four floats each: where, in the layer's pixels, how
     *   far the light reaches and how strongly; [lightCount] of them.
     * @param lightTints each light's colour, packed `0xRRGGBB`.
     */
    fun drawSpace(
        scope: DrawScope,
        centreX: Float,
        centreY: Float,
        scale: Float,
        across: Float,
        seed: FloatArray,
        space: IntArray,
        gas: IntArray,
        seconds: Float,
        show: Float,
        lights: FloatArray,
        lightTints: IntArray,
        lightCount: Int,
    )

    /**
     * One star close in, drawn as the sun it is, round [centre] at [radius].
     *
     * @param tint the star's own colour, packed `0xRRGGBB`.
     * @param light how brightly it burns, `0..1`: its age, its arrival and how far into being a
     *   sun it is.
     * @param seed its own number ([SkyShaders.sunSeed]).
     * @param giant a life event's sun, which is bigger and has longer streamers.
     */
    fun drawSun(
        scope: DrawScope,
        centre: Offset,
        radius: Float,
        tint: Int,
        light: Float,
        seconds: Float,
        seed: Float,
        giant: Boolean,
    )

    companion object {
        /** The shaders, compiled and checked once, or null where they cannot run. */
        fun create(): SkyShading? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AgslSkyShading.create() else null
    }
}

/**
 * [SkyShading] in AGSL. Every uniform each shader declares is set once when it is made, so a name
 * the two sides disagree on fails here, where it is caught and the sky falls back, and never in the
 * middle of a frame.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslSkyShading private constructor(
    private val space: RuntimeShader,
    private val sun: RuntimeShader,
) : SkyShading {

    override fun drawSpace(
        scope: DrawScope,
        centreX: Float,
        centreY: Float,
        scale: Float,
        across: Float,
        seed: FloatArray,
        space: IntArray,
        gas: IntArray,
        seconds: Float,
        show: Float,
        lights: FloatArray,
        lightTints: IntArray,
        lightCount: Int,
    ) {
        val shader = this.space
        shader.setFloatUniform("uSize", scope.size.width, scope.size.height)
        shader.setFloatUniform("uCentre", centreX, centreY)
        shader.setFloatUniform("uScale", scale)
        shader.setFloatUniform("uAcross", across)
        shader.setFloatUniform("uSeed", seed[0], seed[1])
        setColour(shader, "uSpace0", space[0])
        setColour(shader, "uSpace1", space[1])
        setColour(shader, "uSpace2", space[2])
        setColour(shader, "uAccent", space[3])
        setColour(shader, "uGasA", gas[0])
        setColour(shader, "uGasB", gas[1])
        setColour(shader, "uGasC", gas[2])
        shader.setFloatUniform("uSeconds", seconds)
        shader.setFloatUniform("uShow", show)
        for (k in 0 until SkyShaders.LIGHTS) {
            if (k < lightCount) {
                shader.setFloatUniform(LIGHT[k], lights[k * 4], lights[k * 4 + 1], lights[k * 4 + 2], lights[k * 4 + 3])
                setColour(shader, LIGHT_TINT[k], lightTints[k])
            } else {
                // No star: a light of no strength, which the brightest-wins rule passes over.
                shader.setFloatUniform(LIGHT[k], 0f, 0f, 1f, 0f)
                shader.setFloatUniform(LIGHT_TINT[k], 0f, 0f, 0f)
            }
        }
        scope.drawRect(brush = ShaderBrush(shader), size = scope.size)
    }

    override fun drawSun(
        scope: DrawScope,
        centre: Offset,
        radius: Float,
        tint: Int,
        light: Float,
        seconds: Float,
        seed: Float,
        giant: Boolean,
    ) {
        if (radius <= 0f || light <= 0f) return
        val reach = radius * SkyShaders.SUN_REACH
        val shader = sun
        shader.setFloatUniform("uCentre", centre.x, centre.y)
        shader.setFloatUniform("uRadius", radius)
        shader.setFloatUniform("uDensity", scope.density)
        setColour(shader, "uTint", tint)
        shader.setFloatUniform("uLight", light.coerceIn(0f, 1f))
        shader.setFloatUniform("uSeconds", seconds)
        shader.setFloatUniform("uSeed", seed)
        shader.setFloatUniform("uGiant", if (giant) 1f else 0f)
        shader.setFloatUniform("uReach", reach)
        // Each draw takes the shader as its uniforms stand when it is recorded, so one shader
        // serves every sun in a frame.
        scope.drawRect(
            brush = ShaderBrush(shader),
            topLeft = Offset(centre.x - reach, centre.y - reach),
            size = Size(reach * 2f, reach * 2f),
        )
    }

    companion object {
        val LIGHT = Array(SkyShaders.LIGHTS) { "uLight$it" }
        val LIGHT_TINT = Array(SkyShaders.LIGHTS) { "uLightTint$it" }

        /**
         * Both shaders, compiled, with every uniform set once. Null if either will not compile or
         * a uniform is missing, which a device's AGSL can do that this repository's cannot test:
         * the sky then falls back rather than failing.
         */
        fun create(): SkyShading? = try {
            val shading = AgslSkyShading(RuntimeShader(SkyShaders.SPACE), RuntimeShader(SkyShaders.SUN))
            shading.touchEveryUniform()
            shading
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IllegalStateException) {
            null
        }

        private fun setColour(shader: RuntimeShader, name: String, rgb: Int) {
            shader.setFloatUniform(
                name,
                SkyShaders.linear(rgb, 16),
                SkyShaders.linear(rgb, 8),
                SkyShaders.linear(rgb, 0),
            )
        }
    }

    /** Sets every uniform once, with harmless values, so a missing one throws here. */
    private fun touchEveryUniform() {
        space.setFloatUniform("uSize", 1f, 1f)
        space.setFloatUniform("uCentre", 0f, 0f)
        space.setFloatUniform("uScale", 1f)
        space.setFloatUniform("uAcross", 1f)
        space.setFloatUniform("uSeed", 0f, 0f)
        setColour(space, "uSpace0", 0)
        setColour(space, "uSpace1", 0)
        setColour(space, "uSpace2", 0)
        setColour(space, "uAccent", 0)
        setColour(space, "uGasA", 0)
        setColour(space, "uGasB", 0)
        setColour(space, "uGasC", 0)
        space.setFloatUniform("uSeconds", 0f)
        space.setFloatUniform("uShow", 0f)
        for (k in 0 until SkyShaders.LIGHTS) {
            space.setFloatUniform(LIGHT[k], 0f, 0f, 1f, 0f)
            space.setFloatUniform(LIGHT_TINT[k], 0f, 0f, 0f)
        }
        sun.setFloatUniform("uCentre", 0f, 0f)
        sun.setFloatUniform("uRadius", 1f)
        sun.setFloatUniform("uDensity", 1f)
        setColour(sun, "uTint", 0)
        sun.setFloatUniform("uLight", 0f)
        sun.setFloatUniform("uSeconds", 0f)
        sun.setFloatUniform("uSeed", 0f)
        sun.setFloatUniform("uGiant", 0f)
        sun.setFloatUniform("uReach", 1f)
    }
}
