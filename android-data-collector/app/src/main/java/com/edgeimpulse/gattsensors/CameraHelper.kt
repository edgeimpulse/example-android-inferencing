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
 * Wraps CameraX for in-memory JPEG capture.
 *
 * Call [bindToLifecycle] once in the Activity/Fragment. Then call
 * [captureJpeg] to take a photo and receive the JPEG-encoded bytes.
 *
 * The in-memory `takePicture(executor, OnImageCapturedCallback)` variant is
 * used (the file/OutputStream variant requires a real File / MediaStore URI
 * and does not give you bytes directly).
 */
class CameraHelper(private val context: Context) {

    private var imageCapture: ImageCapture? = null

    @Volatile
    private var bound: Boolean = false

    fun bindToLifecycle(lifecycleOwner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider unavailable: ${e.message}")
                return@addListener
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    capture,
                )
                imageCapture = capture
                bound = true
                Log.i(TAG, "Camera bound (BACK)")
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
     * Runs on the camera executor; the callback is invoked on the same thread.
     *
     * The CameraX `ImageProxy` is in `JPEG` format already (since
     * `ImageCapture` defaults to `OUTPUT_FORMAT_JPEG`), but its rotation
     * metadata may be non-zero, so we re-encode rotated when needed so the
     * uploaded sample is upright in Edge Impulse Studio.
     */
    fun captureJpeg(onImageCaptured: (ByteArray) -> Unit) {
        val capture = imageCapture ?: run {
            Log.e(TAG, "captureJpeg called before bindToLifecycle (or bind failed)")
            return
        }

        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bytes = imageProxyToJpegBytes(image)
                        Log.i(TAG, "captureJpeg ok: ${bytes.size} bytes, rot=${image.imageInfo.rotationDegrees}")
                        onImageCaptured(bytes)
                    } catch (t: Throwable) {
                        Log.e(TAG, "JPEG conversion failed: ${t.message}", t)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed: ${exception.message}", exception)
                }
            },
        )
    }

    private fun imageProxyToJpegBytes(image: ImageProxy): ByteArray {
        // ImageCapture default output format is JPEG, so plane[0] holds the
        // compressed bytes directly.
        val buffer = image.planes[0].buffer
        val raw = ByteArray(buffer.remaining()).also { buffer.get(it) }

        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return raw

        // Re-encode rotated so the upload is upright.
        val bmp: Bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: return raw
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (rotated !== bmp) bmp.recycle()
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 90, out)
        rotated.recycle()
        return out.toByteArray()
    }
}

