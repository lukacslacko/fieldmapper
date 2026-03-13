package com.fieldmapper.render

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders the camera feed as the background of the AR scene.
 */
class BackgroundRenderer(context: Context) {

    val cameraTextureId: Int
    private val shaderProgram: Int
    private val quadVertexBuffer: FloatBuffer
    private val texCoordInputBuffer: FloatBuffer
    private var texCoordBuffer: FloatBuffer

    // Fullscreen quad: two triangles
    private val QUAD_VERTICES = floatArrayOf(
        -1f, -1f,  // bottom-left
         1f, -1f,  // bottom-right
        -1f,  1f,  // top-left
         1f,  1f   // top-right
    )

    init {
        // Create external texture for camera
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        // Load shaders
        val vertSrc = loadRawResource(context, "background_vert")
        val fragSrc = loadRawResource(context, "background_frag")
        shaderProgram = createProgram(vertSrc, fragSrc)

        // Vertex buffer
        quadVertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(QUAD_VERTICES)
        quadVertexBuffer.position(0)

        // Tex coord buffers: input has normalized device coords, output is transformed by ARCore
        val defaultTexCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        texCoordInputBuffer = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        texCoordInputBuffer.put(defaultTexCoords)
        texCoordInputBuffer.position(0)

        texCoordBuffer = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        texCoordBuffer.put(defaultTexCoords)
        texCoordBuffer.position(0)
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            texCoordInputBuffer.position(0)
            frame.transformDisplayUvCoords(texCoordInputBuffer, texCoordBuffer)
        }
        texCoordBuffer.position(0)

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)

        GLES30.glUseProgram(shaderProgram)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(shaderProgram, "u_CameraTexture"), 0)

        GLES30.glEnableVertexAttribArray(0)
        quadVertexBuffer.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quadVertexBuffer)

        GLES30.glEnableVertexAttribArray(1)
        texCoordBuffer.position(0)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, texCoordBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)

        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    companion object {
        fun loadRawResource(context: Context, name: String): String {
            val resId = context.resources.getIdentifier(name, "raw", context.packageName)
            return context.resources.openRawResource(resId).bufferedReader().readText()
        }

        fun createProgram(vertSrc: String, fragSrc: String): Int {
            val vert = compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
            val frag = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
            val program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vert)
            GLES30.glAttachShader(program, frag)
            GLES30.glLinkProgram(program)
            GLES30.glDeleteShader(vert)
            GLES30.glDeleteShader(frag)
            return program
        }

        fun compileShader(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            return shader
        }
    }
}
