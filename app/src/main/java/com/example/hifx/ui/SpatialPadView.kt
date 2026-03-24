package com.example.hifx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class SpatialPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Handle {
        LEFT,
        RIGHT
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#66FFFFFF")
        strokeWidth = 2f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#99FFFFFF")
        strokeWidth = 2.4f
    }
    private val leftPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFEF5350")
    }
    private val rightPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF42A5F5")
    }
    private val selectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 4f * density
    }
    private val normalStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#CCFFFFFF")
        strokeWidth = 2f * density
    }
    private val handleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
        isFakeBoldText = true
    }

    private var leftXNorm = 0f
    private var leftZNorm = 0f
    private var rightXNorm = 0f
    private var rightZNorm = 0f
    private var selectedHandle = Handle.LEFT
    private var draggingHandle: Handle? = null
    private var linkedMode = true
    private var controlRadiusPercent: Int = 100

    var onSelectionChanged: ((selected: Handle, fromUser: Boolean) -> Unit)? = null
    var onPositionChanged: ((
        leftX: Int,
        leftZ: Int,
        rightX: Int,
        rightZ: Int,
        selected: Handle,
        fromUser: Boolean
    ) -> Unit)? = null

    fun setLinkedMode(linked: Boolean) {
        if (linkedMode == linked) {
            return
        }
        linkedMode = linked
        if (linked) {
            rightXNorm = leftXNorm
            rightZNorm = leftZNorm
            invalidate()
        }
    }

    fun setControlRadiusPercent(percent: Int, notify: Boolean = false) {
        val clamped = percent.coerceIn(20, 100)
        if (clamped == controlRadiusPercent) {
            return
        }
        val leftX = currentOutputX(leftXNorm)
        val leftZ = currentOutputZ(leftZNorm)
        val rightX = currentOutputX(rightXNorm)
        val rightZ = currentOutputZ(rightZNorm)
        controlRadiusPercent = clamped
        setHandles(leftX, leftZ, rightX, rightZ, notify)
    }

    fun getControlRadiusPercent(): Int = controlRadiusPercent

    fun setHandles(leftX: Int, leftZ: Int, rightX: Int, rightZ: Int, notify: Boolean = false) {
        leftXNorm = toNormalized(leftX)
        leftZNorm = toNormalized(leftZ)
        val left = clampToUnit(leftXNorm, leftZNorm)
        leftXNorm = left.first
        leftZNorm = left.second

        rightXNorm = toNormalized(rightX)
        rightZNorm = toNormalized(rightZ)
        val right = clampToUnit(rightXNorm, rightZNorm)
        rightXNorm = right.first
        rightZNorm = right.second

        if (linkedMode) {
            rightXNorm = leftXNorm
            rightZNorm = leftZNorm
        }
        invalidate()
        if (notify) {
            notifyPositionChanged(false)
        }
    }

    fun setSelectedHandle(handle: Handle, notify: Boolean = false) {
        if (selectedHandle == handle) {
            return
        }
        selectedHandle = handle
        invalidate()
        if (notify) {
            onSelectionChanged?.invoke(selectedHandle, false)
        }
    }

    fun getSelectedHandle(): Handle = selectedHandle

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (300f * density).roundToInt()
        val width = resolveSize(desired, widthMeasureSpec)
        val height = resolveSize(desired, heightMeasureSpec)
        val size = minOf(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = width.coerceAtMost(height) / 2f - 10f * density

        for (i in 1..4) {
            canvas.drawCircle(cx, cy, radius * i / 4f, gridPaint)
        }
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45.0) - 90.0)
            val x = cx + cos(angle).toFloat() * radius
            val y = cy + sin(angle).toFloat() * radius
            canvas.drawLine(cx, cy, x, y, gridPaint)
        }
        canvas.drawLine(cx - radius, cy, cx + radius, cy, axisPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, axisPaint)

        drawHandle(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            xNorm = leftXNorm,
            zNorm = leftZNorm,
            fillPaint = leftPointPaint,
            label = "L",
            selected = selectedHandle == Handle.LEFT
        )
        drawHandle(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            xNorm = rightXNorm,
            zNorm = rightZNorm,
            fillPaint = rightPointPaint,
            label = "R",
            selected = selectedHandle == Handle.RIGHT
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val nearHandle = nearestHandle(event.x, event.y)
                if (nearHandle != null) {
                    if (selectedHandle != nearHandle) {
                        selectedHandle = nearHandle
                        onSelectionChanged?.invoke(selectedHandle, true)
                    }
                    draggingHandle = nearHandle
                    invalidate()
                } else {
                    draggingHandle = selectedHandle
                    updateHandleFromTouch(event.x, event.y, selectedHandle, true)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val handle = draggingHandle ?: selectedHandle
                updateHandleFromTouch(event.x, event.y, handle, true)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingHandle = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun drawHandle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        xNorm: Float,
        zNorm: Float,
        fillPaint: Paint,
        label: String,
        selected: Boolean
    ) {
        val px = cx + xNorm * radius
        val py = cy - zNorm * radius
        val pointRadius = 12f * density
        canvas.drawCircle(px, py, pointRadius, fillPaint)
        canvas.drawCircle(px, py, pointRadius, if (selected) selectedStrokePaint else normalStrokePaint)
        val baseline = (handleTextPaint.ascent() + handleTextPaint.descent()) / 2f
        canvas.drawText(label, px, py - baseline, handleTextPaint)
    }

    private fun updateHandleFromTouch(x: Float, y: Float, handle: Handle, fromUser: Boolean) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = width.coerceAtMost(height) / 2f - 10f * density
        if (radius <= 0f) {
            return
        }
        var nx = (x - cx) / radius
        var nz = -(y - cy) / radius
        val clamped = clampToUnit(nx, nz)
        nx = clamped.first
        nz = clamped.second

        if (handle == Handle.LEFT) {
            leftXNorm = nx
            leftZNorm = nz
            if (linkedMode) {
                rightXNorm = nx
                rightZNorm = nz
            }
        } else {
            rightXNorm = nx
            rightZNorm = nz
            if (linkedMode) {
                leftXNorm = nx
                leftZNorm = nz
            }
        }
        invalidate()
        notifyPositionChanged(fromUser)
    }

    private fun notifyPositionChanged(fromUser: Boolean) {
        onPositionChanged?.invoke(
            currentOutputX(leftXNorm),
            currentOutputZ(leftZNorm),
            currentOutputX(rightXNorm),
            currentOutputZ(rightZNorm),
            selectedHandle,
            fromUser
        )
    }

    private fun nearestHandle(x: Float, y: Float): Handle? {
        val cx = width / 2f
        val cy = height / 2f
        val radius = width.coerceAtMost(height) / 2f - 10f * density
        if (radius <= 0f) {
            return null
        }
        val leftPx = cx + leftXNorm * radius
        val leftPy = cy - leftZNorm * radius
        val rightPx = cx + rightXNorm * radius
        val rightPy = cy - rightZNorm * radius
        val hitRadius = 22f * density

        val distLeft = distance(x, y, leftPx, leftPy)
        val distRight = distance(x, y, rightPx, rightPy)
        val leftHit = distLeft <= hitRadius
        val rightHit = distRight <= hitRadius
        if (!leftHit && !rightHit) return null
        return if (leftHit && rightHit) {
            if (distLeft <= distRight) Handle.LEFT else Handle.RIGHT
        } else if (leftHit) {
            Handle.LEFT
        } else {
            Handle.RIGHT
        }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    private fun toNormalized(value: Int): Float {
        val scale = controlRadiusPercent.toFloat().coerceAtLeast(1f)
        return (value / scale).coerceIn(-1f, 1f)
    }

    private fun currentOutputX(normalized: Float): Int {
        return (normalized * controlRadiusPercent).roundToInt().coerceIn(-100, 100)
    }

    private fun currentOutputZ(normalized: Float): Int {
        return (normalized * controlRadiusPercent).roundToInt().coerceIn(-100, 100)
    }

    private fun clampToUnit(x: Float, z: Float): Pair<Float, Float> {
        val magnitude = sqrt(x * x + z * z)
        if (magnitude <= 1f) {
            return x to z
        }
        return (x / magnitude) to (z / magnitude)
    }

    private val density: Float
        get() = resources.displayMetrics.density
}
