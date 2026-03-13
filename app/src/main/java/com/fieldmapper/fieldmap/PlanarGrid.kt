package com.fieldmapper.fieldmap

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A 2D grid of magnetic field vectors at a fixed height (Y coordinate).
 * Grid cells are CELL_SIZE x CELL_SIZE in the XZ plane.
 * When the phone enters a cell's volume, the measurement is assigned to that cell.
 */
class PlanarGrid {

    companion object {
        const val CELL_SIZE = 0.4f    // 40cm grid spacing
        const val HALF_HEIGHT = 0.15f // ±15cm from plane Y to accept a measurement
    }

    var planeY: Float = 0f
        private set
    private var isLocked = false

    // Grid cell (ix, iz) -> field vector (Bx, By, Bz) in world coords
    private val cells = HashMap<Long, FloatArray>()

    // Running average per cell: stores (sumX, sumY, sumZ, count)
    private val cellAccum = HashMap<Long, FloatArray>()

    val filledCount: Int get() = cells.size

    fun lockPlane(y: Float) {
        planeY = y
        isLocked = true
    }

    fun reset() {
        cells.clear()
        cellAccum.clear()
        isLocked = false
    }

    /**
     * Try to assign a measurement to a grid cell.
     * Returns true if the measurement was within the plane's vertical band.
     */
    fun addMeasurement(position: FloatArray, field: FloatArray, bias: FloatArray): Boolean {
        if (!isLocked) return false
        // Check if within vertical band
        if (kotlin.math.abs(position[1] - planeY) > HALF_HEIGHT) return false

        val ix = floor(position[0] / CELL_SIZE).toInt()
        val iz = floor(position[2] / CELL_SIZE).toInt()
        val key = packKey(ix, iz)

        val bx = field[0] - bias[0]
        val by = field[1] - bias[1]
        val bz = field[2] - bias[2]

        val accum = cellAccum.getOrPut(key) { floatArrayOf(0f, 0f, 0f, 0f) }
        accum[0] += bx
        accum[1] += by
        accum[2] += bz
        accum[3] += 1f

        val n = accum[3]
        cells[key] = floatArrayOf(accum[0] / n, accum[1] / n, accum[2] / n)
        return true
    }

    /**
     * Get all grid arrows for rendering.
     * Returns list of (centerX, centerY, centerZ, fieldX, fieldY, fieldZ).
     */
    fun getArrows(): List<GridArrow> {
        val result = mutableListOf<GridArrow>()
        for ((key, field) in cells) {
            val (ix, iz) = unpackKey(key)
            val cx = (ix + 0.5f) * CELL_SIZE
            val cz = (iz + 0.5f) * CELL_SIZE
            result.add(GridArrow(
                floatArrayOf(cx, planeY, cz),
                field.copyOf()
            ))
        }
        return result
    }

    /**
     * Get the centers of all grid cells that are near existing filled cells
     * but don't have data yet — these are where the user should go.
     */
    fun getEmptyCellCenters(): List<FloatArray> {
        if (cells.isEmpty()) return emptyList()
        val empty = mutableListOf<FloatArray>()
        val checked = HashSet<Long>()

        for (key in cells.keys) {
            val (ix, iz) = unpackKey(key)
            // Check all 8 neighbors
            for (dx in -1..1) {
                for (dz in -1..1) {
                    if (dx == 0 && dz == 0) continue
                    val nk = packKey(ix + dx, iz + dz)
                    if (nk !in cells && nk !in checked) {
                        checked.add(nk)
                        val (nix, niz) = unpackKey(nk)
                        empty.add(floatArrayOf(
                            (nix + 0.5f) * CELL_SIZE,
                            planeY,
                            (niz + 0.5f) * CELL_SIZE
                        ))
                    }
                }
            }
        }
        return empty
    }

    private fun packKey(ix: Int, iz: Int): Long =
        (ix.toLong() shl 32) or (iz.toLong() and 0xFFFFFFFFL)

    private fun unpackKey(key: Long): Pair<Int, Int> =
        Pair((key shr 32).toInt(), key.toInt())
}

data class GridArrow(
    val center: FloatArray,  // world position of the grid cell center
    val field: FloatArray     // field vector (possibly bias-subtracted)
)
