package com.example.unityandroidcameraplugin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.util.Log
import android.util.Range
import java.util.concurrent.Executors
import kotlin.math.abs

class YUV420_888_Camera(
    private val context: Context,
    private val cameraId: String,
    private val width: Int,
    private val height: Int,
    private var fps: Int = 30 // fixed frame rate
) {
    private val TAG = "UnityAndroidCamera"

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var cameraDevice: CameraDevice? = null
    private lateinit var imageReader: ImageReader
    private var unityCallback: UnityCallback? = null

    // YUV data buffer
    private var yBuffer: ByteArray = ByteArray(width * height)
    private var uvBuffer: ByteArray = ByteArray(width * height / 2)

    fun setUnityCallback(callback: UnityCallback) {
        unityCallback = callback
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startCameraCapture() // temporary solution to start the camera preview immediately
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            Log.e(TAG, "Camera error: $error")
        }
    }

    /**
     * Open the camera with the specified [cameraId].
     * This method requires camera permission to be granted.
     */
    @SuppressLint("MissingPermission")
    fun openCamera() {
        try {
            cameraManager.openCamera(cameraId, cameraStateCallback, null)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera: $e")
        }
    }

    /**
     * Update all buffers with YUV data from the [Image].
     * This method expects the image format to be [ImageFormat.YUV_420_888].
     *
     * @param image The [Image] object containing the YUV data.
     * @throws IllegalArgumentException if the image format is not [ImageFormat.YUV_420_888].
     */
    private fun updateBuffer(image: Image) {
        if (image.format != ImageFormat.YUV_420_888) {
            throw IllegalArgumentException("Invalid image format. Expected YUV_420_888, but got ${image.format}")
        }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // Y plane is first, followed by U and V in interleaved order
        // Also checks https://developer.android.com/reference/android/media/Image.Plane
        // Since some devices may have format such as NV21 or YV12
        yPlane.buffer.get(yBuffer, 0, yBuffer.size)
        uPlane.buffer.get(uvBuffer, 0, uPlane.buffer.remaining())
        vPlane.buffer.get(uvBuffer, uPlane.buffer.remaining(), vPlane.buffer.remaining())

        // Notify Unity that the camera texture has been updated
        unityCallback?.onCameraTextureUpdated()
    }

    /**
     * Start the camera capture session.
     * This method sets up the [ImageReader], and starts the camera capture session.
     * The YUV_420_888 image data is extracted and stored in the [yBuffer] and [uvBuffer].
     * Finally, it notifies Unity that the camera texture has been updated via the [UnityCallback].
     */
    fun startCameraCapture() {
        if (cameraDevice == null) {
            Log.e(TAG, "Camera is not opened, cannot start preview")
            return
        }

        // check the fps is supported
        val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
        val fpsRanges =
            cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        if (fpsRanges.isNullOrEmpty()) {
            Log.e(TAG, "No supported FPS ranges found.")
            return
        }

        var isFpsSupported = false
        for (range in fpsRanges) {
            if (range.lower >= fps && range.upper <= fps) {
                isFpsSupported = true
                break
            }
        }

        if (!isFpsSupported) {
            Log.w(TAG, "FPS $fps is not supported.")

            // find the closest supported fps
            val closestFpsRange = fpsRanges.minByOrNull { range ->
                abs(range.lower - fps) + abs(range.upper - fps)
            }

            if (closestFpsRange != null) {
                Log.w(
                    TAG, "Downgraded to the closest supported FPS range: " +
                            "${closestFpsRange.lower} - ${closestFpsRange.upper}"
                )
                fps = closestFpsRange.lower
            } else {
                Log.e(TAG, "No supported FPS range found.")
                return
            }

        }

        // prepare image reader
        imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
            .apply {
                setOnImageAvailableListener({ reader ->
                    var image: Image? = null
                    try {
                        image = reader.acquireLatestImage()
                        if (image != null) {
                            updateBuffer(image)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "ImageReader Error: $e")
                    } finally {
                        image?.close()
                    }
                }, null)
            }

        val captureRequestBuilder = createCaptureRequest()

        // create session configuration
        val sessionConfiguration = createSessionConfiguration(captureRequestBuilder)

        // create capture session, which will start the preview
        cameraDevice?.createCaptureSession(sessionConfiguration)
    }

    /**
     * Create a [CaptureRequest.Builder] for the camera preview.
     * This method sets the target surface, auto-focus mode, and video stabilization mode.
     *
     * @return The created [CaptureRequest.Builder] instance.
     */
    private fun createCaptureRequest(): CaptureRequest.Builder? {
        return cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
            addTarget(imageReader.surface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        }
    }

    private fun createSessionConfiguration(captureRequestBuilder: CaptureRequest.Builder?): SessionConfiguration {
        val outputConfiguration = OutputConfiguration(imageReader.surface)
        return SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(outputConfiguration),
            Executors.newSingleThreadExecutor(),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (captureRequestBuilder != null) {
                        session.setRepeatingRequest(captureRequestBuilder.build(), null, null)
                    }
                    Log.i(TAG, "CameraCaptureSession configured.")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed to configure capture session.")
                }
            })
    }

    /**
     * Stop the camera capture session and release the resources.
     */
    fun stopCameraCapture() {
        try {
            cameraDevice?.close()
            imageReader.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error while stopping preview: $e")
        }
    }

    fun getYBuffer(): ByteArray {
        return yBuffer
    }

    fun getUVBuffer(): ByteArray {
        return uvBuffer
    }

}