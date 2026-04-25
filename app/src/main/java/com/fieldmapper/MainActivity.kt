package com.fieldmapper

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fieldmapper.ar.ArSessionManager
import com.fieldmapper.fieldmap.FieldLine
import com.fieldmapper.fieldmap.FieldLineTracer
import com.fieldmapper.fieldmap.PlanarGrid
import com.fieldmapper.fieldmap.SampleStore
import com.fieldmapper.net.FieldClient
import com.fieldmapper.render.ArrowRenderer
import com.fieldmapper.render.BackgroundRenderer
import com.fieldmapper.render.FieldLineRenderer
import com.fieldmapper.render.SuggestionRenderer
import com.fieldmapper.sensor.MagnetometerReader
import com.google.ar.core.TrackingState
import kotlinx.coroutines.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The app supports two compute modes for field-line reconstruction:
 *
 *   1. **Local** (default if no server / "Use server" off) — the existing
 *      on-device pipeline: SampleStore → FieldInterpolator → FieldLineTracer,
 *      recomputed on a background coroutine when enough new samples arrive.
 *
 *   2. **Server** — `FieldClient` uploads samples in batches to the Python
 *      reconstruction server (`tools/serve.py`) and polls back the
 *      rendered field-line representation + frontier suggestions every ~2s.
 *      The phone keeps doing AR + magnetometer sampling + GL rendering;
 *      the server does IDW interpolation and RK4 tracing.
 *
 * Planar mode is always local — the server has no notion of a fixed-Y plane.
 */
class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "FieldMapper"
        private const val PREFS = "fieldmapper.prefs"
        private const val KEY_SERVER_URL = "serverUrl"
        private const val KEY_SESSION_ID = "sessionId"
        private const val KEY_USE_SERVER = "useServer"
        private const val DEFAULT_SERVER_URL = "http://192.168.1.20:8080"
    }

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var arSession: ArSessionManager
    private lateinit var magnetometer: MagnetometerReader
    private lateinit var sampleStore: SampleStore
    private lateinit var fieldLineTracer: FieldLineTracer
    private lateinit var backgroundRenderer: BackgroundRenderer
    private lateinit var fieldLineRenderer: FieldLineRenderer
    private lateinit var arrowRenderer: ArrowRenderer
    private lateinit var suggestionRenderer: SuggestionRenderer
    private val planarGrid = PlanarGrid()

    // Coroutine scope only used for the local-mode recompute.
    private val recomputeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastRecomputeSampleCount = 0
    private var lastRecomputeTime = 0L
    private var isRecomputing = false

    private var lastSamplePosition: FloatArray? = null
    private var frameCount = 0
    private var lastStatusUpdate = 0L
    @Volatile private var subtractAverage = false
    @Volatile private var planarMode = false
    @Volatile private var useServer = false

    private lateinit var prefs: SharedPreferences
    private var fieldClient: FieldClient? = null
    @Volatile private var serverLines: List<FieldLine> = emptyList()
    @Volatile private var serverSuggestions: List<FloatArray> = emptyList()
    @Volatile private var serverStatusText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
            textSize = 13f
            setPadding(24, 24, 24, 16)
            text = "Starting..."
        }

        // Server URL row: EditText + "Use server" toggle.
        val savedUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        // Default to server-on: the laptop reconstruction is the recommended
        // path. If the laptop is unreachable the renderer just shows nothing
        // until the user fixes the URL or flips the toggle off.
        val savedUseServer = prefs.getBoolean(KEY_USE_SERVER, true)
        useServer = savedUseServer

        val urlEdit = EditText(this).apply {
            hint = "http://laptop:8080"
            setText(savedUrl)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(180, 200, 200, 200))
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(20, 12, 20, 12)
            textSize = 13f
        }

        @Suppress("UseSwitchCompatOrMaterialCode")
        val useServerSwitch = Switch(this).apply {
            text = "Server"
            setTextColor(Color.WHITE)
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
            textSize = 13f
            isChecked = savedUseServer
            setPadding(24, 8, 24, 8)
            setOnCheckedChangeListener { _, isChecked ->
                useServer = isChecked
                prefs.edit().putBoolean(KEY_USE_SERVER, isChecked).apply()
                if (isChecked) startOrUpdateClient(urlEdit.text.toString())
                else stopClient()
            }
        }

        // Save URL on focus loss / IME 'done' and (re)connect if useServer is on.
        urlEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyUrlChange(urlEdit.text.toString())
                urlEdit.clearFocus()
                true
            } else false
        }
        urlEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyUrlChange(urlEdit.text.toString())
        }

        val urlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(urlEdit, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(useServerSwitch, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        @Suppress("UseSwitchCompatOrMaterialCode")
        val subtractSwitch = Switch(this).apply {
            text = "Subtract Earth field"
            setTextColor(Color.WHITE)
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
            textSize = 13f
            setPadding(24, 8, 24, 8)
            setOnCheckedChangeListener { _, isChecked ->
                subtractAverage = isChecked
                fieldClient?.setBias(if (isChecked) "earth" else "none")
                forceLocalRecompute()
            }
        }

        @Suppress("UseSwitchCompatOrMaterialCode")
        val planarSwitch = Switch(this).apply {
            text = "Planar view (local only)"
            setTextColor(Color.WHITE)
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
            textSize = 13f
            setPadding(24, 8, 24, 8)
            setOnCheckedChangeListener { _, isChecked ->
                planarMode = isChecked
                planarGrid.reset()
            }
        }

        val resetBtn = Button(this).apply {
            text = "Reset map"
            textSize = 12f
            setOnClickListener { resetMap() }
        }

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText)
            addView(urlRow)              // contains urlEdit + useServerSwitch
            addView(subtractSwitch)
            addView(planarSwitch)
            addView(resetBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(24, 8, 24, 8) })
        }

        val layout = FrameLayout(this)
        layout.addView(glSurfaceView)
        layout.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))
        setContentView(layout)

        if (savedUseServer) startOrUpdateClient(savedUrl)
    }

    // ------- Server / FieldClient lifecycle -----------------------------

    private fun applyUrlChange(newUrl: String) {
        val cleaned = newUrl.trim()
        if (cleaned.isBlank()) return
        prefs.edit().putString(KEY_SERVER_URL, cleaned).apply()
        if (useServer) startOrUpdateClient(cleaned)
    }

    private fun startOrUpdateClient(url: String) {
        val cleaned = url.trim()
        if (cleaned.isBlank()) return
        val existing = fieldClient
        if (existing != null) {
            existing.setBaseUrl(cleaned)
            existing.setBias(if (subtractAverage) "earth" else "none")
            return
        }
        val sid = prefs.getString(KEY_SESSION_ID, null)
        val client = FieldClient(
            initialBaseUrl = cleaned,
            initialSessionId = sid,
            onUpdate = { rep ->
                serverLines = rep.lines
                serverSuggestions = rep.suggestions
                // Persist any newly-issued session ID so we resume after restart.
                fieldClient?.sessionId?.let {
                    prefs.edit().putString(KEY_SESSION_ID, it).apply()
                }
            },
            onStatus = { st ->
                serverStatusText = formatServerStatus(st)
                fieldClient?.sessionId?.let {
                    if (it != sid) prefs.edit().putString(KEY_SESSION_ID, it).apply()
                }
            },
        )
        client.setBias(if (subtractAverage) "earth" else "none")
        client.start()
        fieldClient = client
        Log.d(TAG, "FieldClient started against $cleaned (session=$sid)")
    }

    private fun stopClient() {
        fieldClient?.shutdown()
        fieldClient = null
        serverLines = emptyList()
        serverSuggestions = emptyList()
        serverStatusText = ""
    }

    private fun resetMap() {
        // Clear everything visible on both sides so the user gets a clean slate.
        sampleStore = SampleStore()
        fieldLineTracer = FieldLineTracer(sampleStore)
        lastSamplePosition = null
        lastRecomputeSampleCount = 0
        planarGrid.reset()
        fieldClient?.reset()
        serverLines = emptyList()
        serverSuggestions = emptyList()
    }

    // ------- GL lifecycle ----------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        arSession = ArSessionManager(this)
        magnetometer = MagnetometerReader(this)
        backgroundRenderer = BackgroundRenderer(this)
        fieldLineRenderer = FieldLineRenderer(this)
        arrowRenderer = ArrowRenderer(this)
        suggestionRenderer = SuggestionRenderer(this)

        arSession.setupSession(backgroundRenderer.cameraTextureId)
        magnetometer.start()
        Log.d(TAG, "onSurfaceCreated: all components initialized")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        arSession.setDisplayGeometry(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        try {
            val frameData = arSession.update() ?: return
            backgroundRenderer.draw(frameData.frame)
            frameCount++
            val now = System.currentTimeMillis()

            if (frameData.trackingState != TrackingState.TRACKING) {
                if (now - lastStatusUpdate > 500) {
                    lastStatusUpdate = now
                    val state = frameData.trackingState.name
                    runOnUiThread { statusText.text = "AR state: $state\nMove the phone slowly" }
                }
                return
            }

            val position = frameData.cameraPosition
            val rotation = frameData.cameraRotation
            val worldField = magnetometer.getWorldFieldVector(rotation)

            if (planarMode) {
                drawPlanarMode(position, worldField, frameData.viewProjectionMatrix, now)
            } else {
                drawFieldLineMode(position, worldField, frameData.viewProjectionMatrix, now)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDrawFrame error", e)
        }
    }

    // ------- Field-line mode ------------------------------------------

    private fun drawFieldLineMode(
        position: FloatArray, worldField: FloatArray?,
        viewProjectionMatrix: FloatArray, now: Long
    ) {
        val haveSample = worldField != null && shouldCollectSample(position)
        if (haveSample) {
            // Always feed the local store too — keeps the local fallback hot
            // and gives us a rolling average for bias subtraction.
            sampleStore.addSample(position.copyOf(), worldField!!)
            lastSamplePosition = position.copyOf()
            // And forward to the server when we're in server mode.
            if (useServer) {
                fieldClient?.queueSample(now / 1000f, position, worldField)
            }
        }

        val lines: List<FieldLine>
        val suggestions: List<FloatArray>
        if (useServer) {
            lines = serverLines
            suggestions = serverSuggestions
        } else {
            maybeLocalRecompute()
            lines = fieldLineTracer.currentLines
            suggestions = emptyList()
        }

        fieldLineRenderer.draw(lines, viewProjectionMatrix)
        if (suggestions.isNotEmpty()) {
            suggestionRenderer.draw(suggestions, viewProjectionMatrix)
        }

        if (now - lastStatusUpdate > 500) {
            lastStatusUpdate = now
            val samples = sampleStore.sampleCount
            val totalPts = lines.sumOf { it.points.size }
            val magStr = if (worldField != null) {
                val mag = kotlin.math.sqrt(
                    worldField[0]*worldField[0] +
                    worldField[1]*worldField[1] +
                    worldField[2]*worldField[2]
                )
                "%.0f µT".format(mag)
            } else "no reading"
            val mode = (if (useServer) "Server " else "Local ") +
                       (if (subtractAverage) "(local)" else "(total)")
            val pos = "%.2f, %.2f, %.2f".format(position[0], position[1], position[2])
            val statusFirstLine = "$mode | Pos: ($pos)\n" +
                "Samples: $samples | |B|: $magStr\n" +
                "Lines: ${lines.size} ($totalPts pts)" +
                if (!useServer && isRecomputing) " [computing...]" else ""
            val full = if (useServer && serverStatusText.isNotEmpty())
                "$statusFirstLine\n$serverStatusText" else statusFirstLine
            runOnUiThread { statusText.text = full }
        }
    }

    // ------- Planar mode (local only) ---------------------------------

    private fun drawPlanarMode(
        position: FloatArray, worldField: FloatArray?,
        viewProjectionMatrix: FloatArray, now: Long
    ) {
        if (planarGrid.filledCount == 0 && worldField != null) {
            planarGrid.lockPlane(position[1])
        }
        if (worldField != null) {
            val bias = if (subtractAverage) sampleStore.getAverageField() else floatArrayOf(0f, 0f, 0f)
            planarGrid.addMeasurement(position, worldField, bias)
            if (shouldCollectSample(position)) {
                sampleStore.addSample(position.copyOf(), worldField)
                lastSamplePosition = position.copyOf()
            }
        }
        val arrows = planarGrid.getArrows()
        val emptyCells = planarGrid.getEmptyCellCenters()
        arrowRenderer.draw(arrows, emptyCells, viewProjectionMatrix)

        if (now - lastStatusUpdate > 500) {
            lastStatusUpdate = now
            val dy = position[1] - planarGrid.planeY
            val inPlane = kotlin.math.abs(dy) <= PlanarGrid.HALF_HEIGHT
            val planeStatus = if (inPlane) "IN plane" else "%.0fcm %s plane".format(
                kotlin.math.abs(dy) * 100,
                if (dy > 0) "above" else "below"
            )
            val magStr = if (worldField != null) {
                val mag = kotlin.math.sqrt(
                    worldField[0]*worldField[0] +
                    worldField[1]*worldField[1] +
                    worldField[2]*worldField[2]
                )
                "%.0f µT".format(mag)
            } else "no reading"
            val status = "Planar | $planeStatus\n" +
                "Grid cells: ${planarGrid.filledCount} | |B|: $magStr\n" +
                "Empty neighbors: ${emptyCells.size}"
            runOnUiThread { statusText.text = status }
        }
    }

    // ------- Helpers --------------------------------------------------

    private fun shouldCollectSample(position: FloatArray): Boolean {
        val last = lastSamplePosition ?: return true
        val dx = position[0] - last[0]
        val dy = position[1] - last[1]
        val dz = position[2] - last[2]
        return (dx * dx + dy * dy + dz * dz) > 0.0025f // 5 cm
    }

    private fun forceLocalRecompute() {
        if (isRecomputing) return
        if (sampleStore.sampleCount < 5) return
        isRecomputing = true
        lastRecomputeSampleCount = sampleStore.sampleCount
        lastRecomputeTime = System.currentTimeMillis()
        recomputeScope.launch {
            fieldLineTracer.setSubtractAverage(subtractAverage)
            fieldLineTracer.recomputeLines()
            isRecomputing = false
        }
    }

    private fun maybeLocalRecompute() {
        if (isRecomputing) return
        val now = System.currentTimeMillis()
        val count = sampleStore.sampleCount
        if (count < 5) return
        val countGrowth = count - lastRecomputeSampleCount
        val timeSinceLastRecompute = now - lastRecomputeTime
        val shouldRecompute =
            (countGrowth > lastRecomputeSampleCount * 0.2f && timeSinceLastRecompute > 1000) ||
            (timeSinceLastRecompute > 3000 && countGrowth > 0) ||
            lastRecomputeSampleCount == 0
        if (shouldRecompute) {
            isRecomputing = true
            lastRecomputeSampleCount = count
            lastRecomputeTime = now
            recomputeScope.launch {
                fieldLineTracer.setSubtractAverage(subtractAverage)
                fieldLineTracer.recomputeLines()
                isRecomputing = false
            }
        }
    }

    private fun formatServerStatus(st: FieldClient.Status): String {
        val now = System.currentTimeMillis()
        val pollAgo = if (st.lastPollMs > 0) "%.1fs".format((now - st.lastPollMs) / 1000.0) else "—"
        val uploadAgo = if (st.lastUploadMs > 0) "%.1fs".format((now - st.lastUploadMs) / 1000.0) else "—"
        val sid = st.sessionId?.take(8) ?: "—"
        val online = if (st.online) "online" else "OFFLINE"
        val errPart = st.lastError?.let { "\n  err: ${it.take(80)}" } ?: ""
        val computePart = if (st.lastComputeMs > 0) " | compute ${st.lastComputeMs}ms" else ""
        return "Srv: $online | sid $sid | sent ${st.totalSamplesSent} | pending ${st.pendingSamples}\n" +
            "  poll ${pollAgo} ago | upload ${uploadAgo} ago${computePart}$errPart"
    }

    override fun onResume() {
        super.onResume()
        if (::glSurfaceView.isInitialized) glSurfaceView.onResume()
        if (::arSession.isInitialized) arSession.resume()
        if (::magnetometer.isInitialized) magnetometer.start()
        if (useServer && fieldClient == null) {
            val url = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
            startOrUpdateClient(url)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::magnetometer.isInitialized) magnetometer.stop()
        if (::arSession.isInitialized) arSession.pause()
        if (::glSurfaceView.isInitialized) glSurfaceView.onPause()
        // Keep FieldClient running across short pauses so any queued samples
        // still upload, but if the user backgrounds the app we tear it down.
        // (Activity destruction handles the full shutdown via onDestroy.)
    }

    override fun onDestroy() {
        recomputeScope.cancel()
        fieldClient?.shutdown()
        fieldClient = null
        super.onDestroy()
    }
}
