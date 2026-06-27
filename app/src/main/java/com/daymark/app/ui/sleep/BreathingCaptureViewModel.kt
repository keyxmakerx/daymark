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
    @ApplicationContext context: Context,
) : ViewModel(), SensorEventListener {

    sealed interface State {
        data object Idle : State
        data object NoSensor : State
        data class Capturing(val progress: Float, val secondsLeft: Int) : State
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
        ticker = viewModelScope.launch {
            while (isActive) {
                val elapsed = if (startNanos == 0L) 0.0 else (lastNanos - startNanos) / 1e9
                _state.value = State.Capturing(
                    progress = (elapsed / durationSec).toFloat().coerceIn(0f, 1f),
                    secondsLeft = ceil((durationSec - elapsed).coerceAtLeast(0.0)).toInt(),
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
        samples.add(sqrt((x * x + y * y + z * z).toDouble()))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun finish() {
        sensorManager.unregisterListener(this)
        val durSec = if (startNanos > 0 && lastNanos > startNanos) (lastNanos - startNanos) / 1e9 else durationSec.toDouble()
        val rate = if (durSec > 0) samples.size / durSec else 0.0
        _state.value = State.Done(BreathingDetector.analyze(samples.toDoubleArray(), rate))
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
