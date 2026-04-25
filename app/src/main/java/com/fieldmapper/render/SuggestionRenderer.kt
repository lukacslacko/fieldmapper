package com.fieldmapper.render

import android.content.Context
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws "go here" markers at the server's frontier-cell suggestions.
 *
 * Each suggestion is a small 3D cross (three perpendicular bars), so it
 * stays visible from any viewing angle. The *first* suggestion (which the
 * server sorts as closest to the user) is rendered larger and brighter so
 * the user has an unambiguous "next step" target.
 *
 * Reuses the fieldline shader; encodes brightness via the per-vertex
 * `confidence` slot and field-strength colour via the `fieldStrength` slot
 * (the shader picks a colour ramp from those two).
 */
class SuggestionRenderer(context: Context) {

    companion object {
        const val FLOATS_PER_VERTEX = 6
        const val BYTES_PER_VERTEX = FLOATS_PER_VERTEX * 4

        // Marker dimensions, in metres.
        const val NORMAL_HALF = 0.06f
        const val HIGHLIGHT_HALF = 0.12f
        // Tint chosen via the fieldline shader's `fieldStrength` ramp slot:
        // 0.0 → cool/blue, 1.0 → warm/yellow. Highlight reads bright yellow,
        // others a calm blue-cyan.
        const val NORMAL_TINT = 0.05f
        const val HIGHLIGHT_TINT = 0.95f
        const val NORMAL_BRIGHTNESS = 0.55f
        const val HIGHLIGHT_BRIGHTNESS = 1.0f
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

    /**
     * @param suggestions list of [x, y, z] positions in world space; the
     * caller is expected to put the closest/best one first.
     */
    fun draw(suggestions: List<FloatArray>, viewProjectionMatrix: FloatArray) {
        rebuildVbo(suggestions)
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

        GLES30.glLineWidth(4f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, vertexCount)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glDisableVertexAttribArray(3)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun rebuildVbo(suggestions: List<FloatArray>) {
        // 3 axes × 2 endpoints = 6 vertices per marker.
        val totalVerts = suggestions.size * 6
        if (totalVerts == 0) { vertexCount = 0; return }

        val buf: FloatBuffer = ByteBuffer
            .allocateDirect(totalVerts * BYTES_PER_VERTEX)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        for ((i, s) in suggestions.withIndex()) {
            val isHi = (i == 0)
            val half = if (isHi) HIGHLIGHT_HALF else NORMAL_HALF
            val tint = if (isHi) HIGHLIGHT_TINT else NORMAL_TINT
            val bri  = if (isHi) HIGHLIGHT_BRIGHTNESS else NORMAL_BRIGHTNESS
            val x = s[0]; val y = s[1]; val z = s[2]
            // X bar
            putVertex(buf, x - half, y, z, bri, 0f, tint)
            putVertex(buf, x + half, y, z, bri, 0f, tint)
            // Y bar
            putVertex(buf, x, y - half, z, bri, 0f, tint)
            putVertex(buf, x, y + half, z, bri, 0f, tint)
            // Z bar
            putVertex(buf, x, y, z - half, bri, 0f, tint)
            putVertex(buf, x, y, z + half, bri, 0f, tint)
        }

        vertexCount = totalVerts
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
        confidence: Float, arcLength: Float, fieldStrength: Float,
    ) {
        buf.put(x); buf.put(y); buf.put(z)
        buf.put(confidence); buf.put(arcLength); buf.put(fieldStrength)
    }
}
