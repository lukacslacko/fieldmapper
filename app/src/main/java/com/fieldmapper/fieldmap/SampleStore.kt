package com.fieldmapper.fieldmap

import kotlin.math.floor

data class SpatialSample(
    val position: FloatArray,  // world x, y, z in meters
    val field: FloatArray       // Bx, By, Bz in microtesla (world frame)
)

data class GridCell(val ix: Int, val iy: Int, val iz: Int)

class SampleStore {

    companion object {
        const val CELL_SIZE = 0.2f // 20cm grid cells
    }

    private val samples = mutableListOf<SpatialSample>()
    private val grid = HashMap<GridCell, MutableList<Int>>()

    @Volatile
    var sampleCount = 0
        private set

    @Synchronized
    fun addSample(position: FloatArray, field: FloatArray) {
        val index = samples.size
        samples.add(SpatialSample(position.copyOf(), field.copyOf()))
        val cell = positionToCell(position)
        grid.getOrPut(cell) { mutableListOf() }.add(index)
        sampleCount = samples.size
    }

    @Synchronized
    fun getSamplesNear(position: FloatArray, radius: Float): List<SpatialSample> {
        val result = mutableListOf<SpatialSample>()
        val cellRadius = (radius / CELL_SIZE).toInt() + 1
        val center = positionToCell(position)
        val r2 = radius * radius

        for (dx in -cellRadius..cellRadius) {
            for (dy in -cellRadius..cellRadius) {
                for (dz in -cellRadius..cellRadius) {
                    val cell = GridCell(center.ix + dx, center.iy + dy, center.iz + dz)
                    grid[cell]?.forEach { idx ->
                        val s = samples[idx]
                        val dist2 = distSq(position, s.position)
                        if (dist2 <= r2) {
                            result.add(s)
                        }
                    }
                }
            }
        }
        return result
    }

    @Synchronized
    fun countSamplesNear(position: FloatArray, radius: Float): Int {
        var count = 0
        val cellRadius = (radius / CELL_SIZE).toInt() + 1
        val center = positionToCell(position)
        val r2 = radius * radius

        for (dx in -cellRadius..cellRadius) {
            for (dy in -cellRadius..cellRadius) {
                for (dz in -cellRadius..cellRadius) {
                    val cell = GridCell(center.ix + dx, center.iy + dy, center.iz + dz)
                    grid[cell]?.forEach { idx ->
                        if (distSq(position, samples[idx].position) <= r2) count++
                    }
                }
            }
        }
        return count
    }

    @Synchronized
    fun getBounds(): Pair<FloatArray, FloatArray>? {
        if (samples.isEmpty()) return null
        val min = samples[0].position.copyOf()
        val max = samples[0].position.copyOf()
        for (s in samples) {
            for (i in 0..2) {
                if (s.position[i] < min[i]) min[i] = s.position[i]
                if (s.position[i] > max[i]) max[i] = s.position[i]
            }
        }
        return Pair(min, max)
    }

    private fun positionToCell(p: FloatArray): GridCell {
        return GridCell(
            floor(p[0] / CELL_SIZE).toInt(),
            floor(p[1] / CELL_SIZE).toInt(),
            floor(p[2] / CELL_SIZE).toInt()
        )
    }

    private fun distSq(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return dx * dx + dy * dy + dz * dz
    }
}
