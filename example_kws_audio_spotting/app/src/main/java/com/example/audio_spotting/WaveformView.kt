package com.example.audio_spotting

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight scrolling waveform view.
 * Call append(samples) from the UI thread with float samples in [-1, 1].
 */
class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // Ring buffer for recent audio (plenty for UI)
    private val cap = 16 * 1024
    private val ring = FloatArray(cap)
    private var head = 0
    private var size = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val path = Path()

    @Synchronized
    fun append(data: FloatArray, n: Int = data.size) {
        val m = min(n, data.size)
        for (i in 0 until m) {
            ring[head] = data[i]
            head = (head + 1) % cap
            if (size < cap) size++
        }
        invalidate()
    }

    @Synchronized
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 1 || h <= 1 || size <= 1) return

        paint.color = 0xFF9EC5FE.toInt() // subtle blue
        path.reset()

        // map latest "size" samples to [0..w)
        val mid = h * 0.5f
        val scale = (h * 0.40f) // 80% peak to peak

        val step = max(1f, size.toFloat() / w)
        var idxFromOldest = 0f

        // oldest index in ring
        val oldest = (head - size + cap) % cap

        fun sampleAt(pos: Int): Float {
            val i = (oldest + pos) % cap
            return ring[i]
        }

        var y = mid - (sampleAt(0) * scale)
        path.moveTo(0f, y)

        for (x in 1 until w) {
            idxFromOldest += step
            val i = min(size - 1, idxFromOldest.toInt())
            y = mid - (sampleAt(i) * scale)
            path.lineTo(x.toFloat(), y)
        }

        canvas.drawPath(path, paint)
    }
}
