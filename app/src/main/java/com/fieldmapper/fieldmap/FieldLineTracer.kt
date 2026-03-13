package com.fieldmapper.fieldmap

import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * A single field line: a sequence of 3D points with per-point metadata.
 */
data class FieldLine(
    val points: List<FloatArray>,        // world positions
    val confidence: List<Float>,          // 0..1 per point (1 = well-sampled)
    val arcLengths: List<Float>,          // cumulative arc length at each point
    val fieldStrength: List<Float>        // normalized field strength at each point (0..1)
)

/**
 * Traces magnetic field lines from seed points using RK4 integration
 * over interpolated field data.
 */
class FieldLineTracer(private val store: SampleStore) {

    companion object {
        private const val TAG = "FieldMapper"
        const val STEP_SIZE = 0.02f         // 2cm integration steps
        const val MAX_STEPS = 400           // max 8m per direction
        const val SEED_SPACING = 0.3f       // 30cm between seed points
        const val MIN_LINE_DISTANCE = 0.08f // skip seeds too close to existing lines
        const val COVERAGE_RADIUS = 0.5f    // radius to check sample density
        const val MIN_DENSITY = 3           // samples needed for "well-sampled"
    }

    private val interpolator = FieldInterpolator(store)
    private val linesRef = AtomicReference<List<FieldLine>>(emptyList())

    val currentLines: List<FieldLine>
        get() = linesRef.get()

    /**
     * Recompute all field lines from scratch. Called on background thread.
     */
    fun recomputeLines() {
        val bounds = store.getBounds() ?: run {
            Log.d(TAG, "recomputeLines: no bounds (no samples?)")
            return
        }
        val (min, max) = bounds
        Log.d(TAG, "recomputeLines: ${store.sampleCount} samples, " +
            "bounds=(%.2f,%.2f,%.2f)-(%.2f,%.2f,%.2f)".format(
                min[0], min[1], min[2], max[0], max[1], max[2]))

        // Expand bounds slightly
        val pad = 0.3f
        val minX = min[0] - pad; val minY = min[1] - pad; val minZ = min[2] - pad
        val maxX = max[0] + pad; val maxY = max[1] + pad; val maxZ = max[2] + pad

        // Compute average field magnitude for normalization
        val avgMag = computeAverageMagnitude(minX, maxX, minY, maxY, minZ, maxZ)
        if (avgMag < 0.1f) return

        // Generate seed points on a grid
        val lines = mutableListOf<FieldLine>()
        val existingPoints = mutableListOf<FloatArray>()

        var x = minX
        while (x <= maxX) {
            var y = minY
            while (y <= maxY) {
                var z = minZ
                while (z <= maxZ) {
                    val seed = floatArrayOf(x, y, z)

                    // Skip if no field data here
                    val mag = interpolator.magnitude(seed)
                    if (mag == null || mag < 1f) {
                        z += SEED_SPACING; continue
                    }

                    // Skip if too close to an existing line point
                    if (isTooCloseToExisting(seed, existingPoints)) {
                        z += SEED_SPACING; continue
                    }

                    // Trace line in both directions
                    val forward = traceDirection(seed, +1, avgMag)
                    val backward = traceDirection(seed, -1, avgMag)

                    // Merge: backward (reversed) + seed + forward
                    val line = mergeLine(backward, seed, forward, avgMag)
                    if (line.points.size >= 5) {
                        lines.add(line)
                        // Add some points to the existing-points check set
                        for (i in line.points.indices step 5) {
                            existingPoints.add(line.points[i])
                        }
                    }

                    z += SEED_SPACING
                }
                y += SEED_SPACING
            }
            x += SEED_SPACING
        }

        Log.d(TAG, "recomputeLines: done, ${lines.size} lines, " +
            "total ${lines.sumOf { it.points.size }} points, avgMag=%.1f".format(avgMag))
        linesRef.set(lines)
    }

    private fun traceDirection(
        start: FloatArray, direction: Int, avgMag: Float
    ): List<TracePoint> {
        val result = mutableListOf<TracePoint>()
        val p = start.copyOf()
        val dir = direction.toFloat()

        for (step in 0 until MAX_STEPS) {
            // RK4 integration
            val k1 = interpolator.interpolateNormalized(p) ?: break
            val p2 = floatArrayOf(
                p[0] + dir * STEP_SIZE * 0.5f * k1[0],
                p[1] + dir * STEP_SIZE * 0.5f * k1[1],
                p[2] + dir * STEP_SIZE * 0.5f * k1[2]
            )
            val k2 = interpolator.interpolateNormalized(p2) ?: break
            val p3 = floatArrayOf(
                p[0] + dir * STEP_SIZE * 0.5f * k2[0],
                p[1] + dir * STEP_SIZE * 0.5f * k2[1],
                p[2] + dir * STEP_SIZE * 0.5f * k2[2]
            )
            val k3 = interpolator.interpolateNormalized(p3) ?: break
            val p4 = floatArrayOf(
                p[0] + dir * STEP_SIZE * k3[0],
                p[1] + dir * STEP_SIZE * k3[1],
                p[2] + dir * STEP_SIZE * k3[2]
            )
            val k4 = interpolator.interpolateNormalized(p4) ?: break

            p[0] += dir * STEP_SIZE / 6f * (k1[0] + 2f * k2[0] + 2f * k3[0] + k4[0])
            p[1] += dir * STEP_SIZE / 6f * (k1[1] + 2f * k2[1] + 2f * k3[1] + k4[1])
            p[2] += dir * STEP_SIZE / 6f * (k1[2] + 2f * k2[2] + 2f * k3[2] + k4[2])

            val density = store.countSamplesNear(p, COVERAGE_RADIUS)
            val confidence = (density.toFloat() / MIN_DENSITY).coerceIn(0f, 1f)

            val mag = interpolator.magnitude(p) ?: 0f
            val normStrength = (mag / avgMag).coerceIn(0f, 1f)

            result.add(TracePoint(p.copyOf(), confidence, normStrength))
        }

        return result
    }

    private fun mergeLine(
        backward: List<TracePoint>, seed: FloatArray, forward: List<TracePoint>, avgMag: Float
    ): FieldLine {
        val points = mutableListOf<FloatArray>()
        val confidences = mutableListOf<Float>()
        val strengths = mutableListOf<Float>()

        // Backward points in reverse order
        for (i in backward.indices.reversed()) {
            points.add(backward[i].position)
            confidences.add(backward[i].confidence)
            strengths.add(backward[i].strength)
        }

        // Seed point
        val seedDensity = store.countSamplesNear(seed, COVERAGE_RADIUS)
        val seedConfidence = (seedDensity.toFloat() / MIN_DENSITY).coerceIn(0f, 1f)
        val seedMag = interpolator.magnitude(seed) ?: 0f
        points.add(seed.copyOf())
        confidences.add(seedConfidence)
        strengths.add((seedMag / avgMag).coerceIn(0f, 1f))

        // Forward points
        for (tp in forward) {
            points.add(tp.position)
            confidences.add(tp.confidence)
            strengths.add(tp.strength)
        }

        // Compute arc lengths
        val arcLengths = mutableListOf(0f)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val dx = curr[0] - prev[0]
            val dy = curr[1] - prev[1]
            val dz = curr[2] - prev[2]
            arcLengths.add(arcLengths.last() + sqrt(dx * dx + dy * dy + dz * dz))
        }

        return FieldLine(points, confidences, arcLengths, strengths)
    }

    private fun isTooCloseToExisting(
        point: FloatArray, existing: List<FloatArray>
    ): Boolean {
        val minDist2 = MIN_LINE_DISTANCE * MIN_LINE_DISTANCE
        for (p in existing) {
            val dx = point[0] - p[0]
            val dy = point[1] - p[1]
            val dz = point[2] - p[2]
            if (dx * dx + dy * dy + dz * dz < minDist2) return true
        }
        return false
    }

    private fun computeAverageMagnitude(
        minX: Float, maxX: Float, minY: Float, maxY: Float, minZ: Float, maxZ: Float
    ): Float {
        var sum = 0f
        var count = 0
        val step = SEED_SPACING * 2
        var x = minX
        while (x <= maxX) {
            var y = minY
            while (y <= maxY) {
                var z = minZ
                while (z <= maxZ) {
                    val m = interpolator.magnitude(floatArrayOf(x, y, z))
                    if (m != null) { sum += m; count++ }
                    z += step
                }
                y += step
            }
            x += step
        }
        return if (count > 0) sum / count else 0f
    }

    private data class TracePoint(
        val position: FloatArray,
        val confidence: Float,
        val strength: Float
    )
}
