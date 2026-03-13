package com.fieldmapper.render

import android.content.Context
import android.opengl.GLES30
import com.fieldmapper.fieldmap.GridArrow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Renders field vectors as 3D arrows at grid points, plus small markers
 * at empty grid cells where data is still needed.
 *
 * Reuses the fieldline shader. Each arrow = 3 line segments (shaft + 2 head lines).
 * Each empty cell marker = 2 line segments (a small cross).
 */
class ArrowRenderer(context: Context) {

    companion object {
        const val FLOATS_PER_VERTEX = 6  // x,y,z, confidence, arcLength, fieldStrength
        const val BYTES_PER_VERTEX = FLOATS_PER_VERTEX * 4
        const val ARROW_SCALE = 0.004f   // scale factor: uT -> meters for arrow length
        const val MAX_ARROW_LEN = 0.25f  // cap arrow length at 25cm
        const val HEAD_FRAC = 0.25f      // arrowhead is 25% of shaft length
        const val HEAD_WIDTH = 0.03f     // arrowhead half-width in meters
        const val MARKER_SIZE = 0.04f    // half-size of empty cell cross marker
    }

    private val shaderProgram: Int
    private val vpUniformLoc: Int
    private var vbo = 0
    private var vertexCount = 0

    init {
        val vertSrc = BackgroundRenderer.loadRawResource(context, "fieldline_vert")
        val fragSrc = BackgroundRenderer.loadRawResource(context, "fieldline_frag")
        shaderProgram = BackgroundRenderer.createProgram(vertSrc, fragSrc)
        vpUniformLoc = GLES30.glGetUniformLocation(shaderProgram, "u_ViewProjection")

        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]
    }

    fun draw(
        arrows: List<GridArrow>,
        emptyCells: List<FloatArray>,
        viewProjectionMatrix: FloatArray
    ) {
        rebuildVbo(arrows, emptyCells)
        if (vertexCount == 0) return

        GLES30.glUseProgram(shaderProgram)
        GLES30.glUniformMatrix4fv(vpUniformLoc, 1, false, viewProjectionMatrix, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, BYTES_PER_VERTEX, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, BYTES_PER_VERTEX, 3 * 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, BYTES_PER_VERTEX, 4 * 4)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, BYTES_PER_VERTEX, 5 * 4)

        GLES30.glLineWidth(3f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, vertexCount)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glDisableVertexAttribArray(3)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun rebuildVbo(arrows: List<GridArrow>, emptyCells: List<FloatArray>) {
        // Each arrow = 3 line segments = 6 vertices
        // Each empty marker = 2 line segments = 4 vertices
        val totalVerts = arrows.size * 6 + emptyCells.size * 4
        if (totalVerts == 0) { vertexCount = 0; return }

        val buf: FloatBuffer = ByteBuffer
            .allocateDirect(totalVerts * BYTES_PER_VERTEX)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        for (arrow in arrows) {
            val cx = arrow.center[0]
            val cy = arrow.center[1]
            val cz = arrow.center[2]
            val fx = arrow.field[0]
            val fy = arrow.field[1]
            val fz = arrow.field[2]

            val mag = sqrt(fx * fx + fy * fy + fz * fz)
            if (mag < 0.001f) continue

            // Arrow length proportional to field, capped
            val len = (mag * ARROW_SCALE).coerceAtMost(MAX_ARROW_LEN)
            val normStrength = (len / MAX_ARROW_LEN).coerceIn(0f, 1f)

            // Direction unit vector
            val dx = fx / mag
            val dy = fy / mag
            val dz = fz / mag

            // Tip of arrow
            val tx = cx + dx * len
            val ty = cy + dy * len
            val tz = cz + dz * len

            // Shaft: center -> tip
            putVertex(buf, cx, cy, cz, 1f, 0f, normStrength)
            putVertex(buf, tx, ty, tz, 1f, 0f, normStrength)

            // Arrowhead: find two perpendicular vectors
            val headLen = len * HEAD_FRAC
            // Pick a vector not parallel to direction
            var px: Float; var py: Float; var pz: Float
            if (kotlin.math.abs(dy) < 0.9f) {
                // Cross with Y-up
                px = dz; py = 0f; pz = -dx
            } else {
                // Cross with X-right
                px = 0f; py = -dz; pz = dy
            }
            val pMag = sqrt(px * px + py * py + pz * pz)
            px /= pMag; py /= pMag; pz /= pMag

            // Head wing 1
            val hx1 = tx - dx * headLen + px * HEAD_WIDTH
            val hy1 = ty - dy * headLen + py * HEAD_WIDTH
            val hz1 = tz - dz * headLen + pz * HEAD_WIDTH
            putVertex(buf, tx, ty, tz, 1f, 0f, normStrength)
            putVertex(buf, hx1, hy1, hz1, 1f, 0f, normStrength)

            // Head wing 2
            val hx2 = tx - dx * headLen - px * HEAD_WIDTH
            val hy2 = ty - dy * headLen - py * HEAD_WIDTH
            val hz2 = tz - dz * headLen - pz * HEAD_WIDTH
            putVertex(buf, tx, ty, tz, 1f, 0f, normStrength)
            putVertex(buf, hx2, hy2, hz2, 1f, 0f, normStrength)
        }

        // Empty cell markers: small dim crosses in the XZ plane
        for (pos in emptyCells) {
            val x = pos[0]; val y = pos[1]; val z = pos[2]
            // Confidence=0.3 makes them dim, arcLength=0 so no dashing
            putVertex(buf, x - MARKER_SIZE, y, z, 0.3f, 0f, 0f)
            putVertex(buf, x + MARKER_SIZE, y, z, 0.3f, 0f, 0f)
            putVertex(buf, x, y, z - MARKER_SIZE, 0.3f, 0f, 0f)
            putVertex(buf, x, y, z + MARKER_SIZE, 0.3f, 0f, 0f)
        }

        vertexCount = buf.position() / FLOATS_PER_VERTEX
        buf.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertexCount * BYTES_PER_VERTEX,
            buf,
            GLES30.GL_DYNAMIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun putVertex(
        buf: FloatBuffer, x: Float, y: Float, z: Float,
        confidence: Float, arcLength: Float, fieldStrength: Float
    ) {
        buf.put(x); buf.put(y); buf.put(z)
        buf.put(confidence); buf.put(arcLength); buf.put(fieldStrength)
    }
}
