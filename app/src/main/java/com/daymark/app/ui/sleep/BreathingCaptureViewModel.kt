package com.daymark.app.ui.sleep

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.sensing.BreathingDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Foreground (screen-on) capture harness: samples the accelerometer while the phone rests on the
 * chest, then runs [BreathingDetector] on the magnitude window. No permission is needed for the
 * accelerometer, and no audio/raw data is persisted — only the derived breathing result is shown.
 * Strictly experimental, non-diagnostic screening.
 */
@HiltViewModel
class BreathingCaptureViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel(), SensorEventListener {

    sealed interface State {
        data object Idle : State
        data object NoSensor : State
        /** [level] is a 0..1 indicator of how much chest movement is currently being sensed. */
        data class Capturing(val progress: Float, val secondsLeft: Int, val level: Float) : State
        data class Done(val result: BreathingDetector.Result) : State
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _state = MutableStateFlow<State>(if (accelerometer == null) State.NoSensor else State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val samples = ArrayList<Double>()
    private var startNanos = 0L
    private var lastNanos = 0L
    private var durationSec = 120
    private var ticker: Job? = null

    fun start(seconds: Int) {
        val accel = accelerometer ?: return
        durationSec = seconds
        samples.clear()
        startNanos = 0L
        lastNanos = 0L
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        // A single buzz to confirm it started, then silence — vibrating mid-capture would corrupt
        // the accelerometer reading. (Live "is it sensing you" feedback should be AUDIO, future.)
        com.daymark.app.util.Haptics.pulse(context)
        ticker = viewModelScope.launch {
            while (isActive) {
                val elapsed = if (startNanos == 0L) 0.0 else (lastNanos - startNanos) / 1e9
                _state.value = State.Capturing(
                    progress = (elapsed / durationSec).toFloat().coerceIn(0f, 1f),
                    secondsLeft = ceil((durationSec - elapsed).coerceAtLeast(0.0)).toInt(),
                    level = recentMovementLevel(),
                )
                if (elapsed >= durationSec) { finish(); break }
                delay(200)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        if (startNanos == 0L) startNanos = event.timestamp
        lastNanos = event.timestamp
        val magnitude = sqrt((x * x + y * y + z * z).toDouble())
        synchronized(samples) { samples.add(magnitude) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Std-dev of the last ~1.5 s of magnitude, mapped to 0..1 (breathing motion is tiny). */
    private fun recentMovementLevel(): Float {
        val window = synchronized(samples) { samples.takeLast(75) }
        if (window.size < 8) return 0f
        val mean = window.average()
        val sd = sqrt(window.sumOf { (it - mean) * (it - mean) } / window.size)
        // ~0.03 m/s² of variation is already meaningful chest movement; scale so that reads full.
        return (sd / 0.05).toFloat().coerceIn(0f, 1f)
    }

    private fun finish() {
        sensorManager.unregisterListener(this)
        val durSec = if (startNanos > 0 && lastNanos > startNanos) (lastNanos - startNanos) / 1e9 else durationSec.toDouble()
        val snapshot = synchronized(samples) { samples.toDoubleArray() }
        val rate = if (durSec > 0) snapshot.size / durSec else 0.0
        _state.value = State.Done(BreathingDetector.analyze(snapshot, rate))
        // Two buzzes to say "done — you can pick me up and look now".
        com.daymark.app.util.Haptics.doublePulse(context)
    }

    fun cancel() {
        ticker?.cancel()
        sensorManager.unregisterListener(this)
        samples.clear()
        _state.value = if (accelerometer == null) State.NoSensor else State.Idle
    }

    fun reset() {
        _state.value = if (accelerometer == null) State.NoSensor else State.Idle
    }

    override fun onCleared() {
        ticker?.cancel()
        sensorManager.unregisterListener(this)
    }
}
