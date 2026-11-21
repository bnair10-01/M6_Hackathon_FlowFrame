package com.stevencastro.wornrun

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * The glasses' own sensors: barometer (elevation), step counter (cadence),
 * off-body detection (auto-pause), the Spreadtrum thermometer, and ambient
 * light (HUD dimming). Each reading goes to a callback on the main thread.
 */
class BodySensors(
    private val sensors: SensorManager,
    private val onAltitude: (Double) -> Unit,
    /** Total steps since boot; the caller timestamps it with its own clock. */
    private val onSteps: (Long) -> Unit,
    private val onOffBody: (Boolean) -> Unit,
    private val onTemperature: (Double) -> Unit,
    private val onLux: (Float) -> Unit,
) : SensorEventListener {
    private var altitude = Double.NaN
    private var loggedTemps = 0

    /** Logs which sensors exist, for the Phase 0 hardware check. */
    fun start() {
        val pressure = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)
        val steps = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        // Off-body detection is defined as a wake-up sensor.
        val offBody = sensors.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        val light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
        val thermometer = sensors.getSensorList(Sensor.TYPE_ALL).firstOrNull {
            it.type == SPRD_THERMOMETER && !it.isWakeUpSensor
        }
        Log.i(TAG, "sensors: pressure=${pressure != null} steps=${steps != null} offBody=${offBody != null} " +
                "light=${light != null} thermometer=${thermometer?.name}")
        for (s in listOfNotNull(pressure, steps, offBody, light, thermometer)) {
            sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, event.values[0]).toDouble()
                // Barometers are noisy; smooth before the engine's hysteresis.
                altitude = if (altitude.isNaN()) alt else altitude + (alt - altitude) * 0.1
                onAltitude(altitude)
            }
            Sensor.TYPE_STEP_COUNTER -> onSteps(event.values[0].toLong())
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> onOffBody(event.values[0] < 0.5f)
            Sensor.TYPE_LIGHT -> onLux(event.values[0])
            SPRD_THERMOMETER -> {
                val c = event.values[0].toDouble()
                if (loggedTemps < 5) {
                    loggedTemps++
                    Log.i(TAG, "thermometer: ${event.values.joinToString()}")
                }
                // Only plausible human/ambient temperatures.
                if (c in -30.0..50.0) onTemperature(c)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    companion object {
        private const val TAG = "WornRun"
        /** com.spreadtrum.thermometer on the M6 glasses. */
        const val SPRD_THERMOMETER = 65545
    }
}
