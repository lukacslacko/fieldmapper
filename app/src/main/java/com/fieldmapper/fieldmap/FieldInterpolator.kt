package com.fieldmapper.fieldmap

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Inverse-distance-weighted interpolation of the magnetic field
 * from collected spatial samples.
 */
class FieldInterpolator(private val store: SampleStore) {

    companion object {
        const val SEARCH_RADIUS = 1.0f   // meters
        const val EPSILON = 0.01f         // minimum distance to avoid div-by-zero
        const val MIN_SAMPLES = 2         // need at least this many nearby samples
    }

    /**
     * Interpolate the magnetic field at the given position.
     * Returns null if the position is too far from any samples.
     */
    fun interpolate(position: FloatArray): FloatArray? {
        val nearby = store.getSamplesNear(position, SEARCH_RADIUS)
        if (nearby.size < MIN_SAMPLES) return null

        var wx = 0f; var wy = 0f; var wz = 0f
        var wSum = 0f

        for (s in nearby) {
            val dist = max(distance(position, s.position), EPSILON)
            val w = 1f / (dist * dist)
            wx += w * s.field[0]
            wy += w * s.field[1]
            wz += w * s.field[2]
            wSum += w
        }

        return floatArrayOf(wx / wSum, wy / wSum, wz / wSum)
    }

    /**
     * Interpolate and normalize to unit vector. Returns null if no data.
     */
    fun interpolateNormalized(position: FloatArray): FloatArray? {
        val b = interpolate(position) ?: return null
        val mag = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (mag < 0.1f) return null
        return floatArrayOf(b[0] / mag, b[1] / mag, b[2] / mag)
    }

    /**
     * Get the field magnitude at a position, or null if not interpolatable.
     */
    fun magnitude(position: FloatArray): Float? {
        val b = interpolate(position) ?: return null
        return sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
