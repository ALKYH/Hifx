package com.alky.hifx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class WaveformScrubPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bounds = RectF()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x20202020
    }
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val activeWaveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
        strokeWidth = 2.4f * density
    }
    private val pointerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
    }

    private var levels: FloatArray = FloatArray(0)
    private var scrubRatio: Float = 0f
    private var touchActive = false
    private var lastTouchX = 0f
    private val barsPerScreen = 120

    var onScrub: ((Float) -> Unit)? = null
    var onScrubFinished: ((Float) -> Unit)? = null

    fun setWaveformLevels(values: FloatArray) {
        levels = values
        invalidate()
    }

    fun setScrubRatio(value: Float) {
        scrubRatio = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (104f * density).toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right <= left || bottom <= top) return

        bounds.set(left, top, right, bottom)
        val corner = 12f * density
        canvas.drawRoundRect(bounds, corner, corner, bgPaint)

        val centerY = bounds.centerY()
        val drawableHeight = bounds.height() * 0.78f
        val minBar = max(2f * density, drawableHeight * 0.08f)
        val values = if (levels.isNotEmpty()) levels else defaultLevels(320)
        val pointerX = bounds.centerX()
        val step = bounds.width() / max(1, barsPerScreen - 1)
        val centerIndex = (scrubRatio * (values.size - 1)).toInt().coerceIn(0, values.lastIndex)
        val halfBars = barsPerScreen / 2

        for (barIndex in 0 until barsPerScreen) {
            val sampleIndex = centerIndex + (barIndex - halfBars)
            if (sampleIndex !in values.indices) continue
            val x = bounds.left + step * barIndex
            val amp = values[sampleIndex].coerceIn(0f, 1f)
            val bar = minBar + amp * (drawableHeight - minBar)
            val y1 = centerY - bar * 0.5f
            val y2 = centerY + bar * 0.5f
            val paint = if (x <= pointerX) activeWaveformPaint else waveformPaint
            canvas.drawLine(x, y1, x, y2, paint)
        }

        canvas.drawLine(pointerX, bounds.top, pointerX, bounds.bottom, pointerPaint)
        canvas.drawCircle(pointerX, centerY, 4.8f * density, pointerDotPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchActive = true
                lastTouchX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touchActive) return false
                updateRatioFromDrag(event.x, notify = true)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!touchActive) return false
                onScrubFinished?.invoke(scrubRatio)
                touchActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!touchActive) return false
                onScrubFinished?.invoke(scrubRatio)
                touchActive = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateRatioFromDrag(x: Float, notify: Boolean) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        if (right <= left) return
        val deltaX = x - lastTouchX
        lastTouchX = x
        val deltaRatio = deltaX / (right - left)
        scrubRatio = (scrubRatio + deltaRatio).coerceIn(0f, 1f)
        if (notify) onScrub?.invoke(scrubRatio)
        invalidate()
    }

    private fun defaultLevels(size: Int): FloatArray {
        val output = FloatArray(size)
        for (i in 0 until size) {
            val t = i / (size - 1f)
            val base = 0.36f + 0.34f * kotlin.math.abs(kotlin.math.sin(t * 130f))
            output[i] = base.coerceIn(0f, 1f)
        }
        return output
    }

    private val density: Float
        get() = resources.displayMetrics.density
}
