package com.edgeimpulse.gattsensors

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream

private const val TAG = "CameraHelper"

/**
 * Wraps CameraX for JPEG image capture.
 *
 * Call [bindToLifecycle] once in the Activity/Fragment.
 * Then call [captureJpeg] to take a photo and receive raw JPEG bytes.
 */
class CameraHelper(private val context: Context) {

    private var imageCapture: ImageCapture? = null

    fun bindToLifecycle(lifecycleOwner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Capture one JPEG frame and deliver raw bytes to [onImageCaptured].
     * Safe to call from any thread; the camera runs on the main executor.
     */
    fun captureJpeg(onImageCaptured: (ByteArray) -> Unit) {
        val capture = imageCapture ?: run {
            Log.e(TAG, "captureJpeg called before bindToLifecycle")
            return
        }

        val stream = ByteArrayOutputStream()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(stream).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onImageCaptured(stream.toByteArray())
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed: ${exception.message}")
                }
            }
        )
    }
}
