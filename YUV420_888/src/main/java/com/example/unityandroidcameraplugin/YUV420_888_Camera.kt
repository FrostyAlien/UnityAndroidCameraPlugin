package com.example.unityandroidcameraplugin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import java.lang.RuntimeException
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class YUV420_888_Camera(
    private val context: Context,
    private val cameraId: String,
    private val width: Int,
    private val height: Int,
) {
    private val TAG ="UnityCameraPlugin"

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var cameraDevice: CameraDevice? = null
    private lateinit var imageReader: ImageReader
    private lateinit var surfaceTexture: SurfaceTexture

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createExternalTexture()
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
     * Create an external texture for rendering the camera preview.
     * This method generates a texture ID, sets texture parameters, and creates a [SurfaceTexture].
     */
    private fun createExternalTexture() {
        val externalTextureId = IntArray(1)
        GLES20.glGenTextures(externalTextureId.size, externalTextureId, 0) // generate only 1 texture
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, externalTextureId[0]) // bind the texture
        // Set texture filtering and wrapping parameters
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR.toFloat()
        );
        // Set magnification filter to GL_LINEAR
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR.toFloat()
        );
        // Set texture wrap mode for S coordinate to GL_CLAMP_TO_EDGE
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE.toFloat()
        );
        // Set texture wrap mode for T coordinate to GL_CLAMP_TO_EDGE
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE.toFloat()
        );

        // Create a SurfaceTexture with the texture id
        surfaceTexture = SurfaceTexture(externalTextureId[0]).apply {
            setDefaultBufferSize(width, height)
        }
    }

    /**
     * Update the external texture with YUV data from the [Image].
     * This method expects the image format to be [ImageFormat.YUV_420_888].
     *
     * @param image The [Image] object containing the YUV data.
     * @throws IllegalArgumentException if the image format is not [ImageFormat.YUV_420_888].
     * @throws RuntimeException if failed to update the [SurfaceTexture].
     */
    private fun updateExternalTextureWithYUVImage(image: Image) {
        if (image.format != ImageFormat.YUV_420_888) {
            throw IllegalArgumentException("Invalid image format. Expected YUV_420_888, but got ${image.format}")
        }

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val yuvData = ByteArray(ySize + uSize + vSize)

        // Y plane is first, followed by U and V in interleaved order
        // Also checks https://developer.android.com/reference/android/media/Image.Plane
        // Since some devices may have format such as NV21
        yBuffer.get(yuvData, 0, ySize)
        uBuffer.get(yuvData, ySize, uSize)
        vBuffer.get(yuvData, ySize + uSize, vSize)

        val yuvDataBuffer = ByteBuffer.wrap(yuvData)

        try {
            surfaceTexture.updateTexImage()

            GLES20.glTexImage2D(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                0,
                GLES20.GL_LUMINANCE,
                image.width,
                image.height,
                0,
                GLES20.GL_LUMINANCE,
                GLES20.GL_UNSIGNED_BYTE,
                yuvDataBuffer
            )
        } catch (e: Exception) {
            throw RuntimeException("Failed to update SurfaceTexture: $e")
        }
    }

    /**
     * Start the camera capture session and send the preview on the external texture.
     * This method creates the external texture, sets up the [ImageReader], and starts the camera capture session.
     */
    fun startCameraCapture() {
        if (cameraDevice == null) {
            Log.e(TAG, "Camera is not opened, cannot start preview")
            return
        }
        try {
            createExternalTexture()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SurfaceTexture: $e")
        }

        // prepare image reader
        imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
            .apply {
            setOnImageAvailableListener({ reader ->
                var image: Image? = null
                try {
                    image = reader.acquireLatestImage()
                    if (image != null) {
                        updateExternalTextureWithYUVImage(image)
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
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
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
            surfaceTexture.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error while stopping preview: $e")
        }
    }

    /**
     * Get the [SurfaceTexture] used for the camera preview.
     *
     * @return The [SurfaceTexture] instance.
     */
    fun getExternalTexture(): SurfaceTexture {
        return surfaceTexture
    }

}