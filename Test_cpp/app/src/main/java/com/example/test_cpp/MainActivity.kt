package com.example.test_cpp

import android.annotation.SuppressLint
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.example.test_cpp.databinding.ActivityMainBinding

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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val result = runInference()
        if (result == null) {
            binding.sampleText.text = "Error running inference"
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
                val objectDetectionText = result.objectDetections.joinToString("\n") {
                    "${it.label}: ${it.confidence}, ${it.x}, ${it.y}, ${it.width}, ${it.height}"
                }
                combinedText.append("Object detection:\n$objectDetectionText\n\n")
            }
            if (result.visualAnomalyGridCells != null) {
                // Display visual anomaly grid cells
                val visualAnomalyGridText = result.visualAnomalyGridCells.joinToString("\n") {
                    "${it.label}: ${it.confidence}, ${it.x}, ${it.y}, ${it.width}, ${it.height}"
                }
                val visualAnomalyMax = result.anomalyResult?.getValue("max")
                val visualAnomalyMean = result.anomalyResult?.getValue("mean")
                combinedText.append("Visual anomalies:\n$visualAnomalyGridText\n\nVisual anomaly values:\nMean: ${visualAnomalyMean}\nMax: ${visualAnomalyMax}\n\n")
            }
            if (result.anomalyResult?.get("anomaly") != null) {
                // Display anomaly detection score
                val anomalyScore = result.anomalyResult.get("anomaly")
                combinedText.append("Anomaly score:\n${anomalyScore}")
            }

            binding.sampleText.text = combinedText.toString()
        }
    }

    /**
     * A native method that is implemented by the 'test_cpp' native library,
     * which is packaged with this application.
     */
    external fun runInference(): InferenceResult?

    companion object {
        // Used to load the 'test_cpp' library on application startup.
        init {
            System.loadLibrary("test_cpp")
        }
    }
}