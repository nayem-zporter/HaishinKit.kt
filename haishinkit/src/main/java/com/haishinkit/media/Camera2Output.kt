package com.haishinkit.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.haishinkit.graphics.ImageOrientation
import com.haishinkit.screen.Video
import java.util.concurrent.Executors

internal class Camera2Output(
    val context: Context,
    val source: VideoSource,
    private val cameraId: String,
) : CameraDevice.StateCallback(), Video.OnSurfaceChangedListener {
    val facing: Int?
        get() = characteristics?.get(CameraCharacteristics.LENS_FACING)

    val video: Video by lazy {
        Video().apply {
            isRotatesWithContent = true
            listener = this@Camera2Output
        }
    }

    private var device: CameraDevice? = null

    // Retained so a mid-stream zoom change can re-issue the repeating request.
    @Volatile
    private var session: CameraCaptureSession? = null

    @Volatile
    private var requestBuilder: CaptureRequest.Builder? = null

    @Volatile
    private var zoomRatio: Float = 1f
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val executor = Executors.newSingleThreadExecutor()
    private var characteristics: CameraCharacteristics? = null
    private val imageOrientation: ImageOrientation
        get() {
            return when (characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)) {
                0 -> ImageOrientation.UP
                90 -> ImageOrientation.LEFT
                180 -> ImageOrientation.DOWN
                270 -> ImageOrientation.RIGHT
                else -> ImageOrientation.UP
            }
        }

    private val handler: Handler by lazy {
        val thread = HandlerThread(TAG)
        thread.start()
        Handler(thread.looper)
    }

    @SuppressLint("MissingPermission")
    fun open() {
        characteristics = manager.getCameraCharacteristics(cameraId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.openCamera(cameraId, executor, this)
        } else {
            manager.openCamera(cameraId, this, handler)
        }
    }

    fun close() {
        source.screen.removeChild(video)
        device?.close()
        session = null
        requestBuilder = null
    }

    override fun onSurfaceChanged(surface: Surface?) {
        surface?.let {
            createCaptureSession(it)
        }
    }

    override fun onOpened(camera: CameraDevice) {
        device = camera
        source.stream?.screen?.frame?.let {
            if (it.height() <= it.width()) {
                getCameraSize(it.width(), it.height())?.let { size ->
                    video.videoSize = size
                }
            } else {
                getCameraSize(it.height(), it.width())?.let { size ->
                    video.videoSize = size
                }
            }
        }
        video.imageOrientation = imageOrientation
        source.screen.addChild(video)
    }

    override fun onDisconnected(camera: CameraDevice) {
    }

    override fun onError(
        camera: CameraDevice,
        error: Int,
    ) {
    }

    private fun getCameraSize(
        width: Int?,
        height: Int?,
    ): Size? {
        val scm = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = scm?.getOutputSizes(SurfaceTexture::class.java)
        if (width == null || height == null) {
            return sizes?.get(0)
        }
        return sizes?.filter { size ->
            (width <= size.width) && (height <= size.height)
        }?.sortedBy { size -> size.width * size.height }?.get(0) ?: sizes?.get(0)
    }

    private fun createCaptureSession(surface: Surface) {
        val device = device ?: return
        val builder =
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }
        requestBuilder = builder
        applyZoom(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputList =
                buildList {
                    add(OutputConfiguration(surface))
                }
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputList,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            this@Camera2Output.session = session
                            try {
                                session.setRepeatingRequest(builder.build(), null, null)
                            } catch (e: RuntimeException) {
                                Log.e(TAG, "", e)
                            }
                        }

                        override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        }
                    },
                ),
            )
        } else {
            val surfaces =
                buildList {
                    add(surface)
                }
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        this@Camera2Output.session = session
                        try {
                            session.setRepeatingRequest(builder.build(), null, null)
                        } catch (e: RuntimeException) {
                            Log.e(TAG, "", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                    }
                },
                handler,
            )
        }
    }

    /** Applies the current [zoomRatio] to a capture-request builder. */
    private fun applyZoom(builder: CaptureRequest.Builder) {
        val chars = characteristics ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        } else {
            val active =
                chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val cropW = (active.width() / zoomRatio).toInt()
            val cropH = (active.height() / zoomRatio).toInt()
            val left = (active.width() - cropW) / 2
            val top = (active.height() - cropH) / 2
            builder.set(
                CaptureRequest.SCALER_CROP_REGION,
                Rect(left, top, left + cropW, top + cropH),
            )
        }
    }

    /**
     * Sets the digital zoom ratio on the live capture (1.0 = no zoom). Clamped to
     * the device's supported range, capped at 5x to match the iOS behaviour and
     * the UI presets.
     */
    fun setZoom(ratio: Float) {
        val chars = characteristics ?: return
        val maxZoom: Float =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f
            } else {
                chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            }
        zoomRatio = ratio.coerceIn(1f, minOf(maxZoom, 5f))
        val builder = requestBuilder ?: return
        val session = session ?: return
        applyZoom(builder)
        try {
            session.setRepeatingRequest(builder.build(), null, null)
        } catch (e: RuntimeException) {
            Log.e(TAG, "setZoom failed", e)
        }
    }

    companion object {
        private val TAG = Camera2Output::class.java.simpleName
    }
}
