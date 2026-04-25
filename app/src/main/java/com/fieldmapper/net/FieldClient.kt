package com.fieldmapper.net

import android.util.Log
import com.fieldmapper.fieldmap.FieldLine
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * Thin client for the Python field-reconstruction server (tools/serve.py).
 *
 * Responsibilities, mirroring web/field-xr.js:
 *   * Hold (and persist via the caller) a session ID.
 *   * Buffer samples and POST in batches every ~1.5 s or when the buffer fills.
 *   * Poll GET /field every ~2 s with `?since=<gen>` short-circuiting.
 *   * Apply bias-mode + reset by hitting the matching endpoints.
 *   * Hand parsed `FieldLine`s + suggestion positions to the renderer via
 *     a single `onUpdate` callback (called on a background thread; the
 *     renderer should re-publish to the GL thread itself).
 *
 * Threading: all network I/O runs on a private `Dispatchers.IO` scope;
 * `queueSample` is callable from any thread (it just adds to a synchronized
 * list). The renderer should read `latestRepresentation` (atomic ref) on the
 * GL thread, or rely on the `onUpdate` callback for change notifications.
 *
 * No new gradle deps: uses HttpURLConnection + org.json from the Android
 * platform. That keeps the repo's dependency surface unchanged.
 */
class FieldClient(
    initialBaseUrl: String,
    initialSessionId: String?,
    private val onUpdate: (Representation) -> Unit,
    private val onStatus: (Status) -> Unit,
) {

    companion object {
        private const val TAG = "FieldClient"
        private const val UPLOAD_INTERVAL_MS = 1500L
        private const val MAX_BATCH = 80
        private const val POLL_INTERVAL_MS = 2000L
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val READ_TIMEOUT_MS = 8000
    }

    /** What the server returned on the last successful poll. */
    data class Representation(
        val generation: Int,
        val sampleCount: Int,
        val lines: List<FieldLine>,
        val suggestions: List<FloatArray>,   // each = [x, y, z]
        val averageField: FloatArray,         // [bx, by, bz] in µT
        val averageMagnitude: Float,          // µT
        val userPosition: FloatArray?,        // [x, y, z], from the latest sample
        val bias: String,                     // "none" | "earth"
        val computeMs: Int,
        val pending: Boolean,                 // server hasn't reconstructed yet
    )

    /** What the rest of the app cares about for its overlay text. */
    data class Status(
        val baseUrl: String,
        val sessionId: String?,
        val online: Boolean,
        val lastError: String?,
        val pendingSamples: Int,
        val totalSamplesSent: Int,
        val lastUploadMs: Long,
        val lastPollMs: Long,
        val lastComputeMs: Int,
    )

    @Volatile private var baseUrl: String = sanitiseBase(initialBaseUrl)
    @Volatile var sessionId: String? = initialSessionId
        private set

    private val pending = ArrayList<Sample>()
    private val pendingLock = Object()

    @Volatile private var bias: String = "none"
    private val latestRep = AtomicReference<Representation?>(null)

    @Volatile private var lastError: String? = null
    @Volatile private var totalSent: Int = 0
    @Volatile private var lastUploadAt: Long = 0L
    @Volatile private var lastPollAt: Long = 0L
    @Volatile private var lastComputeMs: Int = 0
    @Volatile private var lastPolledGen: Int = -1
    @Volatile private var online: Boolean = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var uploadJob: Job? = null
    private var pollJob: Job? = null

    // ---- public API ----

    fun start() {
        stop() // idempotent
        scope.launch { ensureSessionAsync() }
        uploadJob = scope.launch { uploadLoop() }
        pollJob   = scope.launch { pollLoop() }
        emitStatus()
    }

    fun stop() {
        uploadJob?.cancel(); uploadJob = null
        pollJob?.cancel();   pollJob = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    fun setBaseUrl(url: String) {
        val cleaned = sanitiseBase(url)
        if (cleaned == baseUrl) return
        baseUrl = cleaned
        sessionId = null  // server changed, the old ID isn't ours anymore
        latestRep.set(null)
        lastPolledGen = -1
        scope.launch { ensureSessionAsync() }
        emitStatus()
    }

    fun setBias(b: String) {
        val v = if (b == "earth") "earth" else "none"
        if (v == bias) return
        bias = v
        scope.launch { postBias() }
    }

    fun reset() {
        synchronized(pendingLock) { pending.clear() }
        latestRep.set(null)
        lastPolledGen = -1
        scope.launch { postReset() }
        emitStatus()
    }

    fun queueSample(t: Float, p: FloatArray, b: FloatArray) {
        synchronized(pendingLock) {
            pending.add(Sample(t, p[0], p[1], p[2], b[0], b[1], b[2]))
        }
    }

    fun latestRepresentation(): Representation? = latestRep.get()

    fun currentStatus(): Status = buildStatus()

    // ---- internals ----

    private suspend fun ensureSessionAsync() {
        if (sessionId != null) return
        try {
            val resp = httpRequest("POST", "/api/session", null) ?: return
            val obj = JSONObject(resp)
            if (!obj.has("id")) return
            sessionId = obj.getString("id")
            Log.d(TAG, "session created: $sessionId")
            // Push the bias once we have an ID, in case the user toggled
            // before we connected.
            if (bias == "earth") postBias()
            emitStatus()
        } catch (e: Exception) {
            recordError("session create failed: ${e.message}")
        }
    }

    private suspend fun uploadLoop() {
        while (coroutineContext.isActive) {
            try {
                delay(UPLOAD_INTERVAL_MS)
                val sid = sessionId ?: continue
                val batch: List<Sample>
                synchronized(pendingLock) {
                    if (pending.isEmpty()) {
                        batch = emptyList()
                    } else {
                        val n = minOf(pending.size, MAX_BATCH)
                        batch = ArrayList(pending.subList(0, n))
                        if (n == pending.size) pending.clear()
                        else pending.subList(0, n).clear()
                    }
                }
                if (batch.isEmpty()) continue
                val body = encodeSamples(batch)
                val r = httpRequest("POST", "/api/session/$sid/samples", body)
                if (r != null) {
                    totalSent += batch.size
                    lastUploadAt = System.currentTimeMillis()
                    online = true
                    lastError = null
                } else {
                    // Re-queue at the front so we don't drop data on a flaky link
                    synchronized(pendingLock) { pending.addAll(0, batch) }
                }
                emitStatus()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                recordError("upload failed: ${e.message}")
            }
        }
    }

    private suspend fun pollLoop() {
        while (coroutineContext.isActive) {
            try {
                val sid = sessionId
                if (sid == null) {
                    delay(500); continue
                }
                val resp = httpRequest("GET", "/api/session/$sid/field?since=$lastPolledGen", null)
                if (resp != null) {
                    val obj = JSONObject(resp)
                    if (!obj.optBoolean("unchanged", false)) {
                        val rep = parseRepresentation(obj)
                        latestRep.set(rep)
                        lastPolledGen = rep.generation
                        lastComputeMs = rep.computeMs
                        try { onUpdate(rep) } catch (e: Exception) {
                            Log.w(TAG, "onUpdate threw", e)
                        }
                    }
                    online = true
                    lastError = null
                    lastPollAt = System.currentTimeMillis()
                    emitStatus()
                }
                delay(POLL_INTERVAL_MS)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                recordError("poll failed: ${e.message}")
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun postBias() {
        val sid = sessionId ?: return
        val body = JSONObject().put("bias", bias).toString()
        httpRequest("POST", "/api/session/$sid/config", body)
    }

    private suspend fun postReset() {
        val sid = sessionId ?: return
        httpRequest("POST", "/api/session/$sid/reset", "{}")
        // Force the next poll to refetch.
        lastPolledGen = -1
    }

    // ---- HTTP transport ----

    private suspend fun httpRequest(method: String, path: String, body: String?): String? =
        withContext(Dispatchers.IO) {
            val urlStr = baseUrl + path
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doInput = true
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) {
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    online = true
                    lastError = null
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    val err = try {
                        conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    } catch (e: Exception) { "" }
                    recordError("HTTP $code on $method $path${if (err.isBlank()) "" else ": ${err.take(120)}"}")
                    null
                }
            } catch (e: Exception) {
                recordError("$method $urlStr: ${e.javaClass.simpleName}: ${e.message}")
                null
            } finally {
                conn.disconnect()
            }
        }

    // ---- encode / decode ----

    private fun encodeSamples(batch: List<Sample>): String {
        val arr = JSONArray()
        for (s in batch) {
            val o = JSONObject()
            o.put("t", s.t.toDouble())
            val p = JSONArray(); p.put(s.px.toDouble()); p.put(s.py.toDouble()); p.put(s.pz.toDouble())
            val b = JSONArray(); b.put(s.bx.toDouble()); b.put(s.by.toDouble()); b.put(s.bz.toDouble())
            o.put("p", p); o.put("b", b)
            arr.put(o)
        }
        return JSONObject().put("samples", arr).toString()
    }

    private fun parseRepresentation(obj: JSONObject): Representation {
        val gen = obj.optInt("generation", 0)
        val sampleCount = obj.optInt("sampleCount", 0)
        val pending = obj.optBoolean("pending", false)
        val biasStr = obj.optString("bias", this.bias)
        val computeMs = obj.optInt("computeMs", 0)
        val avgMag = obj.optDouble("averageMagnitude", 0.0).toFloat()

        val avgArr = obj.optJSONArray("averageField")
        val avg = FloatArray(3) { i -> avgArr?.optDouble(i, 0.0)?.toFloat() ?: 0f }

        val userPosArr = obj.optJSONArray("userPosition")
        val userPos = if (userPosArr != null && userPosArr.length() == 3)
            FloatArray(3) { i -> userPosArr.optDouble(i, 0.0).toFloat() } else null

        val linesArr = obj.optJSONArray("lines") ?: JSONArray()
        val lines = ArrayList<FieldLine>(linesArr.length())
        for (i in 0 until linesArr.length()) {
            val ln = linesArr.getJSONObject(i)
            val ptsArr = ln.getJSONArray("points")    // flat [x0,y0,z0, x1,y1,z1, ...]
            val n = ptsArr.length() / 3
            if (n < 2) continue
            val pts = ArrayList<FloatArray>(n)
            for (k in 0 until n) {
                pts.add(floatArrayOf(
                    ptsArr.getDouble(k * 3 + 0).toFloat(),
                    ptsArr.getDouble(k * 3 + 1).toFloat(),
                    ptsArr.getDouble(k * 3 + 2).toFloat(),
                ))
            }
            val str = readFloatList(ln.optJSONArray("strengths"), n, 0.5f)
            val cnf = readFloatList(ln.optJSONArray("confidences"), n, 0.5f)
            val arc = computeArcLengths(pts)
            lines.add(FieldLine(pts, cnf, arc, str))
        }

        val sugArr = obj.optJSONArray("suggestions") ?: JSONArray()
        val suggestions = ArrayList<FloatArray>(sugArr.length())
        for (i in 0 until sugArr.length()) {
            val s = sugArr.getJSONArray(i)
            if (s.length() == 3) {
                suggestions.add(floatArrayOf(
                    s.getDouble(0).toFloat(),
                    s.getDouble(1).toFloat(),
                    s.getDouble(2).toFloat(),
                ))
            }
        }

        return Representation(
            generation = gen,
            sampleCount = sampleCount,
            lines = lines,
            suggestions = suggestions,
            averageField = avg,
            averageMagnitude = avgMag,
            userPosition = userPos,
            bias = biasStr,
            computeMs = computeMs,
            pending = pending,
        )
    }

    private fun readFloatList(arr: JSONArray?, expectedLen: Int, fallback: Float): List<Float> {
        if (arr == null) return List(expectedLen) { fallback }
        val n = minOf(arr.length(), expectedLen)
        val out = ArrayList<Float>(expectedLen)
        for (i in 0 until n) out.add(arr.optDouble(i, fallback.toDouble()).toFloat())
        // Pad if shorter
        while (out.size < expectedLen) out.add(fallback)
        return out
    }

    private fun computeArcLengths(pts: List<FloatArray>): List<Float> {
        val arc = ArrayList<Float>(pts.size)
        arc.add(0f)
        var acc = 0f
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]
            acc += sqrt(dx * dx + dy * dy + dz * dz)
            arc.add(acc)
        }
        return arc
    }

    // ---- status plumbing ----

    private fun recordError(msg: String) {
        lastError = msg
        online = false
        Log.w(TAG, msg)
        emitStatus()
    }

    private fun emitStatus() {
        try { onStatus(buildStatus()) } catch (_: Exception) {}
    }

    private fun buildStatus(): Status {
        val pendCount = synchronized(pendingLock) { pending.size }
        return Status(
            baseUrl = baseUrl,
            sessionId = sessionId,
            online = online,
            lastError = lastError,
            pendingSamples = pendCount,
            totalSamplesSent = totalSent,
            lastUploadMs = lastUploadAt,
            lastPollMs = lastPollAt,
            lastComputeMs = lastComputeMs,
        )
    }

    private fun sanitiseBase(url: String): String {
        var u = url.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        while (u.endsWith("/")) u = u.dropLast(1)
        return u
    }

    // ---- packed sample ----

    private data class Sample(
        val t: Float,
        val px: Float, val py: Float, val pz: Float,
        val bx: Float, val by: Float, val bz: Float,
    )
}
