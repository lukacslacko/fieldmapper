package com.fieldmapper.ar

import android.app.Activity
import android.opengl.Matrix
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.*

class FrameData(
    val frame: Frame,
    val trackingState: TrackingState,
    val cameraPosition: FloatArray,
    val cameraRotation: FloatArray,
    val viewProjectionMatrix: FloatArray
)

class ArSessionManager(private val activity: Activity) {

    private var session: Session? = null
    private var displayWidth = 0
    private var displayHeight = 0

    fun setupSession(cameraTextureId: Int) {
        val session = Session(activity)
        val config = Config(session).apply {
            planeFindingMode = Config.PlaneFindingMode.DISABLED
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode = Config.FocusMode.AUTO
        }
        session.configure(config)
        session.setCameraTextureName(cameraTextureId)
        this.session = session
        session.resume()
    }

    fun setDisplayGeometry(width: Int, height: Int) {
        displayWidth = width
        displayHeight = height
        val rotation = (activity.getSystemService(Activity.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.rotation
        session?.setDisplayGeometry(rotation, width, height)
    }

    fun update(): FrameData? {
        val session = this.session ?: return null
        val frame = session.update()
        val camera = frame.camera

        if (camera.trackingState != TrackingState.TRACKING) {
            return FrameData(frame, camera.trackingState, FloatArray(3), FloatArray(4), FloatArray(16))
        }

        val pose = camera.displayOrientedPose

        val position = floatArrayOf(pose.tx(), pose.ty(), pose.tz())
        val rotation = pose.rotationQuaternion // [x, y, z, w]

        val viewMatrix = FloatArray(16)
        camera.getViewMatrix(viewMatrix, 0)

        val projMatrix = FloatArray(16)
        camera.getProjectionMatrix(projMatrix, 0, 0.05f, 50f)

        val viewProjection = FloatArray(16)
        Matrix.multiplyMM(viewProjection, 0, projMatrix, 0, viewMatrix, 0)

        return FrameData(frame, camera.trackingState, position, rotation, viewProjection)
    }

    fun resume() {
        session?.resume()
    }

    fun pause() {
        session?.pause()
    }
}
