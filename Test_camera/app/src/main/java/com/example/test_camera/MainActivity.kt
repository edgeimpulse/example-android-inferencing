package com.example.test_camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.test_camera.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var resultTextView: TextView
    private lateinit var previewView: PreviewView
    private lateinit var processedImageView: ImageView

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        resultTextView = findViewById(R.id.resultTextView) // Result TextView
        previewView = findViewById(R.id.previewView) // Camera preview view
        // processedImageView = findViewById(R.id.processedImageView)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Camera selector
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Preview use case
            val preview = Preview.Builder()
                .build()

            // Set up the preview to show camera feed on the PreviewView
            preview.setSurfaceProvider(previewView.surfaceProvider)

            // Image analysis use case
            val imageAnalysis = ImageAnalysis.Builder()
                .build()

            // Set up the analysis use case
            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImage(imageProxy)  // Process image and pass it to C++
            }

            // Bind use cases to lifecycle
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

        }, ContextCompat.getMainExecutor(this))
    }

    // Process the captured image
    private fun processImage(imageProxy: ImageProxy) {
        // Convert ImageProxy to Bitmap
        val bitmap = imageProxy.toBitmap()

        // Resize the Bitmap to Edge Impulse model size
        // val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 64, 64, true)

        // Convert the resized bitmap to ByteArray
        val byteArray = getByteArrayFromBitmap(bitmap)
//        val outputPath = File(getExternalFilesDir(null), "processed_image.bmp").absolutePath
//        Log.d("MainActivity", "Output path: $outputPath")

        // Close the imageProxy after processing
        imageProxy.close()

        // Pass to C++ for Edge Impulse inference
        lifecycleScope.launch(Dispatchers.IO) {
            val result = passToCpp(byteArray)
            runOnUiThread {
                displayResults(result)
            }
        }

//        lifecycleScope.launch(Dispatchers.IO) {
//            val result = passToCppDebugSave(byteArray)
//            runOnUiThread {
//                displayResults(result)
//            }
//        }

//        lifecycleScope.launch(Dispatchers.IO) {
//            val processedByteArray = passToCppDebug(byteArray)
//            val processedBitmap = getBitmapFromByteArray(processedByteArray, 64, 64)
//
//            runOnUiThread {
//                processedImageView.setImageBitmap(processedBitmap)
//            }
//        }

    }

    // Convert ImageProxy to Bitmap
    private fun ImageProxy.toBitmap(): Bitmap {
        val planes = this.planes
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // Convert Bitmap to ByteArray (RGB888 format)
    private fun getByteArrayFromBitmap(bitmap: Bitmap): ByteArray {

        // Rotate the bitmap by 90 degrees
        val matrix = Matrix()
        matrix.postRotate(90f)

        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        val width = rotatedBitmap.width
        val height = rotatedBitmap.height

        val pixels = IntArray(width * height) // Holds ARGB pixels
        val rgbByteArray = ByteArray(width * height * 3) // Holds RGB888 data

        rotatedBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert ARGB to RGB888
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            rgbByteArray[i * 3] = r.toByte()
            rgbByteArray[i * 3 + 1] = g.toByte()
            rgbByteArray[i * 3 + 2] = b.toByte()
        }

        return rgbByteArray
    }

    private fun getBitmapFromByteArray(byteArray: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        for (i in pixels.indices) {
            val r = byteArray[i * 3].toInt() and 0xFF
            val g = byteArray[i * 3 + 1].toInt() and 0xFF
            val b = byteArray[i * 3 + 2].toInt() and 0xFF

            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        return bitmap
    }

    // Call the C++ function to process the image and return results
    private external fun passToCpp(imageData: ByteArray): String
    private external fun passToCppDebug(imageData: ByteArray): ByteArray
    private external fun passToCppDebugSave(imageData: ByteArray): String

    // Display results in UI
    private fun displayResults(result: String) {
        // print the result
        // Log.d("MainActivity", "Result: $result")
        resultTextView.text = result
    }

    // Load the native library
    init {
        System.loadLibrary("test_camera")
    }
}
