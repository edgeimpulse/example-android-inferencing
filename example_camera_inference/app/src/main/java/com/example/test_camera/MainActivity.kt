package com.example.test_camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.Manifest
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.app.ActivityCompat

data class InferenceResult(
    val classification: Map<String, Float>?,   // Classification labels and values
    val objectDetections: List<BoundingBox>?,  // Object detection results
    val visualAnomalyGridCells: List<BoundingBox>?, // Visual anomaly grid
    val anomalyResult: Map<String, Float>?, // Anomaly values
    val timing: Timing  // Timing information
)

data class BoundingBox(
    val label: String,
    val confidence: Float,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class Timing(
    val sampling: Int,
    val dsp: Int,
    val classification: Int,
    val anomaly: Int,
    val dsp_us: Long,
    val classification_us: Long,
    val anomaly_us: Long
)

private const val CAMERA_PERMISSION_REQUEST_CODE = 1001

class BoundingBoxOverlay(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        style = Paint.Style.FILL
    }

    private val anomalyPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        alpha = 60 // Adjust transparency
    }

    var boundingBoxes: List<BoundingBox> = emptyList()
        set(value) {
            field = value
            invalidate() // Redraw when new bounding boxes are set
        }

    @SuppressLint("DefaultLocale")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.TRANSPARENT) // Ensure transparency

        boundingBoxes.forEach { box ->
            val rect = Rect(box.x, box.y, box.x + box.width, box.y + box.height)

            if (box.label == "anomaly") {
                // Fill the box with transparent red
                canvas.drawRect(rect, anomalyPaint)

                // Display anomaly score in the center
                val scoreText = String.format("%.2f", box.confidence)
                val textX = rect.centerX().toFloat()
                val textY = rect.centerY().toFloat()

                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(scoreText, textX, textY, textPaint)
            } else {
                // Standard object detection box
                canvas.drawRect(rect, paint)
                canvas.drawText("${box.label} (${(box.confidence * 100).toInt()}%)", box.x.toFloat(), (box.y - 10).toFloat(), textPaint)
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var resultTextView: TextView
    private lateinit var previewView: PreviewView
    private lateinit var boundingBoxOverlay: BoundingBoxOverlay

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        resultTextView = findViewById(R.id.resultTextView) // Result TextView
        previewView = findViewById(R.id.previewView) // Camera preview view
        boundingBoxOverlay = findViewById(R.id.boundingBoxOverlay) // overlay for bbxes / visual ad

        // Set overlay size to match PreviewView
        previewView.post {
            boundingBoxOverlay.layoutParams = boundingBoxOverlay.layoutParams.apply {
                width = previewView.width
                height = previewView.height
            }
        }

        if (!hasCameraPermission()) {
            requestCameraPermission()
        } else {
            startCamera()
        }

    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val imageAnalysis = ImageAnalysis.Builder().build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImage(imageProxy)
            }

            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                resultTextView.text = "Camera permission required!"
            }
        }
    }

    // Process the captured image
    private fun processImage(imageProxy: ImageProxy) {
        // Convert ImageProxy to Bitmap
        val bitmap = imageProxy.toBitmap()

        // Resize the Bitmap to Edge Impulse model size
        // val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        // resizing is done in C++ code

        // Convert the resized bitmap to ByteArray
        val byteArray = getByteArrayFromBitmap(bitmap)

        // Close the imageProxy after processing
        imageProxy.close()

        // Pass to C++ for Edge Impulse inference
        lifecycleScope.launch(Dispatchers.IO) {
            val result = passToCpp(byteArray)
            runOnUiThread {
                displayResults(result)
            }
        }
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

    // Call the C++ function to process the image and return results
    private external fun passToCpp(imageData: ByteArray): InferenceResult?

    // Display results in UI
    @SuppressLint("SetTextI18n")
    private fun displayResults(result: InferenceResult?) {
        resultTextView.visibility = View.GONE
        boundingBoxOverlay.visibility = View.GONE

        if (result == null) {
            resultTextView.text = "Error running inference"
        } else
        {
            val combinedText = StringBuilder()
            if (result.classification != null) {
                // Display classification results
                val classificationText = result.classification.entries.joinToString("\n") {
                    "${it.key}: ${it.value}"
                }
                combinedText.append("Classification:\n$classificationText\n\n")
            }
            if (result.objectDetections != null) {
                // Display object detection results
//                val objectDetectionText = result.objectDetections.joinToString("\n") {
//                    "${it.label}: ${it.confidence}, ${it.x}, ${it.y}, ${it.width}, ${it.height}"
//                }
                // Update bounding boxes on the overlay
                boundingBoxOverlay.visibility = View.VISIBLE
                boundingBoxOverlay.boundingBoxes = result.objectDetections
                //combinedText.append("Object detection:\n$objectDetectionText\n\n")
            }
            if (result.visualAnomalyGridCells != null) {
                // Display visual anomaly grid cells
//                val visualAnomalyGridText = result.visualAnomalyGridCells.joinToString("\n") {
//                    "${it.label}: ${it.confidence}, ${it.x}, ${it.y}, ${it.width}, ${it.height}"
//                }
                val visualAnomalyMax = result.anomalyResult?.getValue("max")
                val visualAnomalyMean = result.anomalyResult?.getValue("mean")
                boundingBoxOverlay.visibility = View.VISIBLE
                boundingBoxOverlay.boundingBoxes = result.visualAnomalyGridCells
                resultTextView.visibility = View.VISIBLE
                combinedText.append("Visual anomaly values:\nMean: ${visualAnomalyMean}\nMax: ${visualAnomalyMax}")
                //combinedText.append("Visual anomalies:\n$visualAnomalyGridText\n\nVisual anomaly values:\nMean: ${visualAnomalyMean}\nMax: ${visualAnomalyMax}\n\n")
            }
            if (result.anomalyResult?.get("anomaly") != null) {
                // Display anomaly detection score
                val anomalyScore = result.anomalyResult.get("anomaly")
                combinedText.append("Anomaly score:\n${anomalyScore}")
            }
            // print the result
            val textToDisplay = combinedText.toString()
            //Log.d("MainActivity", "Result: $textToDisplay")
            resultTextView.text = textToDisplay
        }
    }

    // Load the native library
    init {
        System.loadLibrary("test_camera")
    }
}