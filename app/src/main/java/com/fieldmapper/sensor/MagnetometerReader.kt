package com.fieldmapper.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class MagnetometerReader(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // Low-pass filtered reading in device coordinates (microtesla)
    private val filtered = FloatArray(3)
    private var hasReading = false
    private val alpha = 0.2f // smoothing factor

    fun start() {
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            if (!hasReading) {
                filtered[0] = event.values[0]
                filtered[1] = event.values[1]
                filtered[2] = event.values[2]
                hasReading = true
            } else {
                filtered[0] = filtered[0] * (1 - alpha) + event.values[0] * alpha
                filtered[1] = filtered[1] * (1 - alpha) + event.values[1] * alpha
                filtered[2] = filtered[2] * (1 - alpha) + event.values[2] * alpha
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Transform the device-local magnetic field reading into ARCore world coordinates
     * using the camera's rotation quaternion [x, y, z, w].
     */
    fun getWorldFieldVector(cameraRotation: FloatArray): FloatArray? {
        if (!hasReading) return null

        val qx = cameraRotation[0]
        val qy = cameraRotation[1]
        val qz = cameraRotation[2]
        val qw = cameraRotation[3]

        // Device coordinate system: X right, Y up, Z out of screen
        // Rotate device-local field by camera quaternion to get world field
        val vx = filtered[0]
        val vy = filtered[1]
        val vz = filtered[2]

        // Quaternion rotation: q * v * q^-1
        // Expanded formula for rotating vector by quaternion:
        val tx = 2f * (qy * vz - qz * vy)
        val ty = 2f * (qz * vx - qx * vz)
        val tz = 2f * (qx * vy - qy * vx)

        return floatArrayOf(
            vx + qw * tx + (qy * tz - qz * ty),
            vy + qw * ty + (qz * tx - qx * tz),
            vz + qw * tz + (qx * ty - qy * tx)
        )
    }
}
