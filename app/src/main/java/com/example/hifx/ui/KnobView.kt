package com.example.hifx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.withStyledAttributes
import com.example.hifx.R
import com.google.android.material.color.MaterialColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class KnobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var valueFrom: Float = 0f
        set(newValue) {
            field = newValue
            coerceValueWithinCurrentBounds()
            invalidate()
        }

    var valueTo: Float = 100f
        set(newValue) {
            field = newValue
            coerceValueWithinCurrentBounds()
            invalidate()
        }

    var stepSize: Float = 1f
    var defaultValue: Float? = null
    var onValueChange: ((Float, Boolean) -> Unit)? = null

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                return resetToDefault(fromUser = true)
            }
        }
    )

    var value: Float = 0f
        set(newValue) {
            val clamped = normalizeValue(newValue)
            if (field == clamped) return
            field = clamped
            contentDescription = clamped.roundToInt().toString()
            invalidate()
        }

    private val startAngle = 135f
    private val sweepAngle = 270f
    private var lastTouchAngle = 0f

    private val trackRect = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        isClickable = true
        isFocusable = true
        context.withStyledAttributes(attrs, R.styleable.KnobView) {
            valueFrom = getFloat(R.styleable.KnobView_valueFrom, valueFrom)
            valueTo = getFloat(R.styleable.KnobView_valueTo, valueTo)
            stepSize = getFloat(R.styleable.KnobView_stepSize, stepSize)
            value = getFloat(R.styleable.KnobView_value, value)
        }
        updateColors()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val strokeWidth = size * 0.08f
        trackPaint.strokeWidth = strokeWidth
        progressPaint.strokeWidth = strokeWidth
        indicatorPaint.strokeWidth = strokeWidth * 0.72f

        val inset = strokeWidth * 1.4f
        trackRect.set(inset, inset, width - inset, height - inset)

        canvas.drawArc(trackRect, startAngle, sweepAngle, false, trackPaint)
        canvas.drawArc(trackRect, startAngle, sweepAngle * valueFraction(), false, progressPaint)

        val radius = trackRect.width() / 2f
        val centerX = trackRect.centerX()
        val centerY = trackRect.centerY()
        val knobRadius = radius * 0.68f
        canvas.drawCircle(centerX, centerY, knobRadius, knobPaint)

        val indicatorAngle = Math.toRadians((startAngle + sweepAngle * valueFraction()).toDouble())
        val indicatorLength = knobRadius * 0.62f
        val endX = centerX + cos(indicatorAngle).toFloat() * indicatorLength
        val endY = centerY + sin(indicatorAngle).toFloat() * indicatorLength
        canvas.drawLine(centerX, centerY, endX, endY, indicatorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchAngle = touchAngle(event.x, event.y)
                isPressed = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val angle = touchAngle(event.x, event.y)
                var delta = angle - lastTouchAngle
                if (delta > 180f) delta -= 360f
                if (delta < -180f) delta += 360f
                val next = value + (delta / sweepAngle) * (valueTo - valueFrom)
                updateFromUser(next)
                lastTouchAngle = angle
                return true
            }

            MotionEvent.ACTION_UP -> {
                performClick()
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun setEnabled(enabled: Boolean) {
        val changed = enabled != isEnabled
        super.setEnabled(enabled)
        if (changed) {
            alpha = if (enabled) 1f else 0.45f
            invalidate()
        }
    }

    private fun updateFromUser(newValue: Float) {
        val normalized = normalizeValue(newValue)
        if (normalized == value) return
        value = normalized
        onValueChange?.invoke(normalized, true)
    }

    private fun normalizeValue(raw: Float): Float {
        val min = minOf(valueFrom, valueTo)
        val max = maxOf(valueFrom, valueTo)
        val clamped = raw.coerceIn(min, max)
        if (stepSize <= 0f) return clamped
        val steps = ((clamped - min) / stepSize).roundToInt()
        val snapped = min + steps * stepSize
        return snapped.coerceIn(min, max)
    }

    private fun valueFraction(): Float {
        val min = minOf(valueFrom, valueTo)
        val max = maxOf(valueFrom, valueTo)
        val range = max - min
        if (range <= 0f) return 0f
        return ((value - min) / range).coerceIn(0f, 1f)
    }

    private fun coerceValueWithinCurrentBounds() {
        val min = minOf(valueFrom, valueTo)
        val max = maxOf(valueFrom, valueTo)
        value = value.coerceIn(min, max)
    }

    private fun resetToDefault(fromUser: Boolean): Boolean {
        val target = defaultValue ?: return false
        val normalized = normalizeValue(target)
        if (normalized == value) return true
        value = normalized
        onValueChange?.invoke(normalized, fromUser)
        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        return true
    }

    private fun touchAngle(x: Float, y: Float): Float {
        val centerX = width / 2f
        val centerY = height / 2f
        return Math.toDegrees(atan2(y - centerY, x - centerX).toDouble()).toFloat()
    }

    private fun updateColors() {
        trackPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        progressPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        knobPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest)
        indicatorPaint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        alpha = if (isEnabled) 1f else 0.45f
    }
}
