package com.edgeimpulse.gattsensors

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream

private const val TAG = "CameraHelper"

/**
 * Wraps CameraX for in-memory JPEG capture with an optional live preview.
 *
 * Call [bindToLifecycle] once (without a surface provider) for background
 * capture-only use. Call [bindToLifecycle] again with a [Preview.SurfaceProvider]
 * (from a `PreviewView`) to attach a live viewfinder; the use-cases are
 * unbound and rebound so the same provider can be swapped in/out safely.
 */
class CameraHelper(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile
    private var bound: Boolean = false

    fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider? = null,
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider unavailable: ${e.message}")
                return@addListener
            }
            cameraProvider = provider
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val useCases: Array<UseCase> = if (surfaceProvider != null) {
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(surfaceProvider) }
                arrayOf(preview, capture)
            } else {
                arrayOf(capture)
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    *useCases,
                )
                imageCapture = capture
                bound = true
                Log.i(TAG, "Camera bound (preview=${surfaceProvider != null})")
            } catch (e: Exception) {
                imageCapture = null
                bound = false
                Log.e(TAG, "Camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun isReady(): Boolean = bound && imageCapture != null

    /**
     * Capture one JPEG frame and deliver the encoded bytes to [onImageCaptured].
     * [onError] is called (on the main thread) if the capture fails so callers
     * can update their status UI.
     */
    fun captureJpeg(
        onImageCaptured: (ByteArray) -> Unit,
        onError: (String) -> Unit = {},
    ) {
        val capture = imageCapture ?: run {
            val msg = "Camera not ready — grant permission and try again"
            Log.e(TAG, msg)
            onError(msg)
            return
        }

        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bytes = imageProxyToJpegBytes(image)
                        Log.i(TAG, "captureJpeg ok: ${bytes.size} bytes")
                        onImageCaptured(bytes)
                    } catch (t: Throwable) {
                        Log.e(TAG, "JPEG conversion failed: ${t.message}", t)
                        onError("JPEG conversion failed: ${t.message}")
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed: ${exception.message}", exception)
                    onError("Capture failed: ${exception.message}")
                }
            },
        )
    }

    private fun imageProxyToJpegBytes(image: ImageProxy): ByteArray {
        val buffer = image.planes[0].buffer
        val raw = ByteArray(buffer.remaining()).also { buffer.get(it) }

        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return raw

        val bmp: Bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (rotated !== bmp) bmp.recycle()
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 90, out)
        rotated.recycle()
        return out.toByteArray()
    }
}
