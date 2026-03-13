package com.fieldmapper.render

import android.content.Context
import android.opengl.GLES30
import com.fieldmapper.fieldmap.FieldLine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders magnetic field lines as GL_LINE_STRIP with per-vertex
 * confidence (for dash pattern) and field strength (for color).
 *
 * Vertex layout: [x, y, z, confidence, arcLength, fieldStrength] = 6 floats
 */
class FieldLineRenderer(context: Context) {

    companion object {
        const val FLOATS_PER_VERTEX = 6
        const val BYTES_PER_VERTEX = FLOATS_PER_VERTEX * 4
    }

    private val shaderProgram: Int
    private val vpUniformLoc: Int

    // We rebuild the VBO each time lines change
    private var vbo = 0
    private var lineSegments = listOf<LineSegment>() // start index + count for each line
    private var lastLinesIdentity: List<FieldLine> = emptyList()

    data class LineSegment(val startVertex: Int, val vertexCount: Int)

    init {
        val vertSrc = BackgroundRenderer.loadRawResource(context, "fieldline_vert")
        val fragSrc = BackgroundRenderer.loadRawResource(context, "fieldline_frag")
        shaderProgram = BackgroundRenderer.createProgram(vertSrc, fragSrc)
        vpUniformLoc = GLES30.glGetUniformLocation(shaderProgram, "u_ViewProjection")

        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]
    }

    fun draw(lines: List<FieldLine>, viewProjectionMatrix: FloatArray) {
        if (lines.isEmpty()) return

        // Rebuild VBO if lines changed
        if (lines !== lastLinesIdentity) {
            rebuildVbo(lines)
            lastLinesIdentity = lines
        }

        if (lineSegments.isEmpty()) return

        GLES30.glUseProgram(shaderProgram)
        GLES30.glUniformMatrix4fv(vpUniformLoc, 1, false, viewProjectionMatrix, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)

        // position
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, BYTES_PER_VERTEX, 0)

        // confidence
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, BYTES_PER_VERTEX, 3 * 4)

        // arcLength
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, BYTES_PER_VERTEX, 4 * 4)

        // fieldStrength
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, BYTES_PER_VERTEX, 5 * 4)

        GLES30.glLineWidth(3f)

        for (seg in lineSegments) {
            GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, seg.startVertex, seg.vertexCount)
        }

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glDisableVertexAttribArray(3)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun rebuildVbo(lines: List<FieldLine>) {
        // Count total vertices
        var totalVertices = 0
        for (line in lines) {
            totalVertices += line.points.size
        }

        if (totalVertices == 0) {
            lineSegments = emptyList()
            return
        }

        // Build interleaved vertex data
        val buffer: FloatBuffer = ByteBuffer
            .allocateDirect(totalVertices * BYTES_PER_VERTEX)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val segments = mutableListOf<LineSegment>()
        var vertexOffset = 0

        for (line in lines) {
            segments.add(LineSegment(vertexOffset, line.points.size))
            for (i in line.points.indices) {
                val p = line.points[i]
                buffer.put(p[0])
                buffer.put(p[1])
                buffer.put(p[2])
                buffer.put(line.confidence[i])
                buffer.put(line.arcLengths[i])
                buffer.put(line.fieldStrength[i])
            }
            vertexOffset += line.points.size
        }
        buffer.position(0)

        // Upload to GPU
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            totalVertices * BYTES_PER_VERTEX,
            buffer,
            GLES30.GL_DYNAMIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        lineSegments = segments
    }
}
