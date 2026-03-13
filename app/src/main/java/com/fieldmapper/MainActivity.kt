package com.fieldmapper

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fieldmapper.ar.ArSessionManager
import com.fieldmapper.fieldmap.FieldLineTracer
import com.fieldmapper.fieldmap.SampleStore
import com.fieldmapper.render.BackgroundRenderer
import com.fieldmapper.render.FieldLineRenderer
import com.fieldmapper.sensor.MagnetometerReader
import com.google.ar.core.TrackingState
import kotlinx.coroutines.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "FieldMapper"
    }

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var arSession: ArSessionManager
    private lateinit var magnetometer: MagnetometerReader
    private lateinit var sampleStore: SampleStore
    private lateinit var fieldLineTracer: FieldLineTracer
    private lateinit var backgroundRenderer: BackgroundRenderer
    private lateinit var fieldLineRenderer: FieldLineRenderer

    private val recomputeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastRecomputeSampleCount = 0
    private var lastRecomputeTime = 0L
    private var isRecomputing = false

    private var lastSamplePosition: FloatArray? = null
    private var frameCount = 0
    private var lastStatusUpdate = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        sampleStore = SampleStore()
        fieldLineTracer = FieldLineTracer(sampleStore)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
        } else {
            setupGlSurface()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupGlSurface()
        } else {
            finish()
        }
    }

    private fun setupGlSurface() {
        glSurfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@MainActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
            textSize = 14f
            setPadding(24, 24, 24, 24)
            text = "Starting..."
        }

        val layout = FrameLayout(this)
        layout.addView(glSurfaceView)
        layout.addView(statusText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))
        setContentView(layout)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        arSession = ArSessionManager(this)
        magnetometer = MagnetometerReader(this)
        backgroundRenderer = BackgroundRenderer(this)
        fieldLineRenderer = FieldLineRenderer(this)

        arSession.setupSession(backgroundRenderer.cameraTextureId)
        magnetometer.start()
        Log.d(TAG, "onSurfaceCreated: all components initialized, magnetometer started")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        arSession.setDisplayGeometry(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        try {
            val frameData = arSession.update() ?: run {
                Log.w(TAG, "onDrawFrame: update() returned null")
                return
            }

            // Always draw camera feed (even before tracking starts)
            backgroundRenderer.draw(frameData.frame)

            frameCount++
            val now = System.currentTimeMillis()

            if (frameData.trackingState != TrackingState.TRACKING) {
                if (now - lastStatusUpdate > 500) {
                    lastStatusUpdate = now
                    val state = frameData.trackingState.name
                    Log.d(TAG, "Not tracking yet, state=$state")
                    runOnUiThread { statusText.text = "AR state: $state\nMove the phone slowly" }
                }
                return
            }

            // Collect magnetic field sample
            val position = frameData.cameraPosition
            val rotation = frameData.cameraRotation
            val worldField = magnetometer.getWorldFieldVector(rotation)

            if (worldField != null && shouldCollectSample(position)) {
                sampleStore.addSample(position.copyOf(), worldField)
                lastSamplePosition = position.copyOf()
                Log.d(TAG, "Sample #${sampleStore.sampleCount}: " +
                    "pos=(%.2f, %.2f, %.2f) ".format(position[0], position[1], position[2]) +
                    "B=(%.1f, %.1f, %.1f) uT".format(worldField[0], worldField[1], worldField[2]))
            }

            // Trigger field line recomputation if enough new data
            maybeRecomputeFieldLines()

            // Render field lines
            val lines = fieldLineTracer.currentLines
            fieldLineRenderer.draw(lines, frameData.viewProjectionMatrix)

            // Update status overlay (~2 times per second)
            if (now - lastStatusUpdate > 500) {
                lastStatusUpdate = now
                val samples = sampleStore.sampleCount
                val lineCount = lines.size
                val totalPts = lines.sumOf { it.points.size }
                val magStr = if (worldField != null) {
                    val mag = kotlin.math.sqrt(worldField[0]*worldField[0] + worldField[1]*worldField[1] + worldField[2]*worldField[2])
                    "%.0f uT".format(mag)
                } else "no reading"
                val pos = "%.2f, %.2f, %.2f".format(position[0], position[1], position[2])
                val status = "Tracking | Pos: ($pos)\n" +
                    "Samples: $samples | |B|: $magStr\n" +
                    "Lines: $lineCount ($totalPts pts)" +
                    if (isRecomputing) " [computing...]" else ""
                runOnUiThread { statusText.text = status }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDrawFrame error", e)
        }
    }

    private fun shouldCollectSample(position: FloatArray): Boolean {
        val last = lastSamplePosition ?: return true
        val dx = position[0] - last[0]
        val dy = position[1] - last[1]
        val dz = position[2] - last[2]
        return (dx * dx + dy * dy + dz * dz) > 0.0025f // 5cm
    }

    private fun maybeRecomputeFieldLines() {
        if (isRecomputing) return
        val now = System.currentTimeMillis()
        val count = sampleStore.sampleCount
        if (count < 5) return

        val countGrowth = count - lastRecomputeSampleCount
        val timeSinceLastRecompute = now - lastRecomputeTime
        val shouldRecompute = (countGrowth > lastRecomputeSampleCount * 0.2f && timeSinceLastRecompute > 1000)
            || (timeSinceLastRecompute > 3000 && countGrowth > 0)
            || lastRecomputeSampleCount == 0

        if (shouldRecompute) {
            isRecomputing = true
            lastRecomputeSampleCount = count
            lastRecomputeTime = now
            recomputeScope.launch {
                fieldLineTracer.recomputeLines()
                isRecomputing = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::glSurfaceView.isInitialized) glSurfaceView.onResume()
        if (::arSession.isInitialized) arSession.resume()
        if (::magnetometer.isInitialized) magnetometer.start()
    }

    override fun onPause() {
        super.onPause()
        if (::magnetometer.isInitialized) magnetometer.stop()
        if (::arSession.isInitialized) arSession.pause()
        if (::glSurfaceView.isInitialized) glSurfaceView.onPause()
    }

    override fun onDestroy() {
        recomputeScope.cancel()
        super.onDestroy()
    }
}
