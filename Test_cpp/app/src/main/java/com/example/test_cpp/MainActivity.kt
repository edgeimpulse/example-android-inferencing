package com.example.test_cpp

import android.annotation.SuppressLint
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import com.example.test_cpp.databinding.ActivityMainBinding

data class InferenceResult(
    val classification: Map<String, Float>, // Classification labels and values
    val objectDetections: List<BoundingBox>?, // Object detection results
    val anomalyScore: Float, // Anomaly detection score
    val timing: Timing, // Timing information
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

        // Display classification results
        val classificationText = result.classification.entries.joinToString("\n") {
            "${it.key}: ${it.value}"
        }
        binding.sampleText.text = "Classification:\n$classificationText\n\n" +
                "Anomaly Score:\n ${result.anomalyScore}\n\n"}

    /**
     * A native method that is implemented by the 'test_cpp' native library,
     * which is packaged with this application.
     */
    external fun runInference(): InferenceResult

    companion object {
        // Used to load the 'test_cpp' library on application startup.
        init {
            System.loadLibrary("test_cpp")
        }
    }
}