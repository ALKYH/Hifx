package com.alky.hifx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
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
    private val headFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#35E0E0E0")
    }
    private val headStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#CFEAEAEA")
        strokeWidth = 2f * density
    }
    private val headBoundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E6FFFFFF")
        strokeWidth = 1.6f * density
        pathEffect = DashPathEffect(
            floatArrayOf(5f * density, 4f * density),
            0f
        )
    }
    private val headBoundaryTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#CCFFFFFF")
        strokeWidth = 1.5f * density
    }
    private val earPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#9FD0D0D0")
        strokeWidth = 1.8f * density
    }
    private val nosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#B5FFFFFF")
        strokeWidth = 1.8f * density
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
    private val linkedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E6FFFFFF")
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
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
    private var controlRadiusCm: Int = DEFAULT_CONTROL_RADIUS_CM
    private var headRadiusCm: Float = DEFAULT_HEAD_RADIUS_CM
    private var linkedChannelSpacingCm: Int = DEFAULT_LINKED_SPACING_CM
    private val headBoundaryPath = Path()
    private val nosePath = Path()
    private var headGeometryDirty = true
    private var cachedHeadCenterX = Float.NaN
    private var cachedHeadCenterY = Float.NaN
    private var cachedPadRadius = Float.NaN
    private var cachedHeadRadiusPx = 0f

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
        val centerX = (leftXNorm + rightXNorm) * 0.5f
        val centerZ = (leftZNorm + rightZNorm) * 0.5f
        linkedMode = linked
        if (linked) {
            applyLinkedLayout(centerX, centerZ)
        } else {
            invalidate()
        }
    }

    fun setLinkedChannelSpacingCm(valueCm: Int, notify: Boolean = false) {
        val clamped = valueCm.coerceIn(MIN_LINKED_SPACING_CM, MAX_LINKED_SPACING_CM)
        if (linkedChannelSpacingCm == clamped) {
            return
        }
        linkedChannelSpacingCm = clamped
        if (linkedMode) {
            val centerX = (leftXNorm + rightXNorm) * 0.5f
            val centerZ = (leftZNorm + rightZNorm) * 0.5f
            applyLinkedLayout(centerX, centerZ)
            if (notify) {
                notifyPositionChanged(false)
            }
        } else {
            invalidate()
        }
    }

    fun setControlRadiusPercent(percent: Int, notify: Boolean = false) {
        setControlRadiusCm(percent, notify)
    }

    fun setControlRadiusCm(radiusCm: Int, notify: Boolean = false) {
        val clamped = radiusCm.coerceIn(MIN_CONTROL_RADIUS_CM, MAX_CONTROL_RADIUS_CM)
        if (clamped == controlRadiusCm) {
            return
        }
        val leftX = currentOutputX(leftXNorm)
        val leftZ = currentOutputZ(leftZNorm)
        val rightX = currentOutputX(rightXNorm)
        val rightZ = currentOutputZ(rightZNorm)
        controlRadiusCm = clamped
        headGeometryDirty = true
        setHandles(leftX, leftZ, rightX, rightZ, notify)
    }

    fun getControlRadiusPercent(): Int = controlRadiusCm

    fun getControlRadiusCm(): Int = controlRadiusCm

    fun setHeadRadiusCm(valueCm: Float) {
        val clamped = valueCm.coerceIn(6.5f, 12f)
        if (kotlin.math.abs(clamped - headRadiusCm) < 0.001f) {
            return
        }
        headRadiusCm = clamped
        headGeometryDirty = true
        val leftX = currentOutputX(leftXNorm)
        val leftZ = currentOutputZ(leftZNorm)
        val rightX = currentOutputX(rightXNorm)
        val rightZ = currentOutputZ(rightZNorm)
        setHandles(leftX, leftZ, rightX, rightZ, notify = false)
    }

    fun setHandles(leftX: Int, leftZ: Int, rightX: Int, rightZ: Int, notify: Boolean = false) {
        if (linkedMode) {
            val centerX = toNormalized((leftX + rightX) / 2)
            val centerZ = toNormalized((leftZ + rightZ) / 2)
            applyLinkedLayout(centerX, centerZ)
        } else {
            leftXNorm = toNormalized(leftX)
            leftZNorm = toNormalized(leftZ)
            val left = clampToHrtfDomain(leftXNorm, leftZNorm)
            leftXNorm = left.first
            leftZNorm = left.second

            rightXNorm = toNormalized(rightX)
            rightZNorm = toNormalized(rightZ)
            val right = clampToHrtfDomain(rightXNorm, rightZNorm)
            rightXNorm = right.first
            rightZNorm = right.second
            invalidate()
        }
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
        ensureHeadGeometry(cx, cy, radius)

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
        drawHeadContour(canvas, cx, cy, radius)

        if (linkedMode) {
            val leftPx = cx + leftXNorm * radius
            val leftPy = cy - leftZNorm * radius
            val rightPx = cx + rightXNorm * radius
            val rightPy = cy - rightZNorm * radius
            canvas.drawLine(leftPx, leftPy, rightPx, rightPy, linkedBarPaint)
        }

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

    private fun drawHeadContour(canvas: Canvas, cx: Float, cy: Float, padRadius: Float) {
        val headRadiusPx = cachedHeadRadiusPx
        canvas.drawPath(headBoundaryPath, headFillPaint)
        canvas.drawPath(headBoundaryPath, headStrokePaint)
        canvas.drawPath(headBoundaryPath, headBoundaryPaint)
        drawHeadBoundaryTicks(canvas, cx, cy, headRadiusPx)

        val earOffsetX = headRadiusPx * 1.02f
        val earRadius = (headRadiusPx * 0.28f).coerceAtLeast(3f * density)
        canvas.drawCircle(cx - earOffsetX, cy, earRadius, earPaint)
        canvas.drawCircle(cx + earOffsetX, cy, earRadius, earPaint)
        canvas.drawPath(nosePath, nosePaint)
    }

    private fun ensureHeadGeometry(cx: Float, cy: Float, padRadius: Float) {
        if (!headGeometryDirty && cachedHeadCenterX == cx && cachedHeadCenterY == cy && cachedPadRadius == padRadius) {
            return
        }
        cachedHeadCenterX = cx
        cachedHeadCenterY = cy
        cachedPadRadius = padRadius
        val radiusRatio = (headRadiusCm / controlRadiusCm.toFloat()).coerceIn(0.07f, 0.62f)
        cachedHeadRadiusPx = (padRadius * radiusRatio).coerceAtLeast(8f * density)
        rebuildHeadBoundaryPath(cx, cy, cachedHeadRadiusPx)
        rebuildNosePath(cx, cy, cachedHeadRadiusPx)
        headGeometryDirty = false
    }

    private fun rebuildHeadBoundaryPath(cx: Float, cy: Float, radiusPx: Float) {
        val segments = 96
        headBoundaryPath.reset()
        for (index in 0..segments) {
            val angle = ((index.toFloat() / segments.toFloat()) * Math.PI * 2.0 - Math.PI / 2.0).toFloat()
            val x = cx + cos(angle) * radiusPx
            val y = cy + sin(angle) * radiusPx
            if (index == 0) {
                headBoundaryPath.moveTo(x, y)
            } else {
                headBoundaryPath.lineTo(x, y)
            }
        }
        headBoundaryPath.close()
    }

    private fun rebuildNosePath(cx: Float, cy: Float, headRadiusPx: Float) {
        nosePath.reset()
        nosePath.moveTo(cx, cy - headRadiusPx * 1.08f)
        nosePath.lineTo(cx - headRadiusPx * 0.18f, cy - headRadiusPx * 0.84f)
        nosePath.moveTo(cx, cy - headRadiusPx * 1.08f)
        nosePath.lineTo(cx + headRadiusPx * 0.18f, cy - headRadiusPx * 0.84f)
    }

    private fun drawHeadBoundaryTicks(canvas: Canvas, cx: Float, cy: Float, radiusPx: Float) {
        val inner = radiusPx * 0.92f
        val outer = radiusPx * 1.06f
        for (degree in 0 until 360 step 30) {
            val angle = Math.toRadians((degree - 90).toDouble()).toFloat()
            val startX = cx + cos(angle) * inner
            val startY = cy + sin(angle) * inner
            val endX = cx + cos(angle) * outer
            val endY = cy + sin(angle) * outer
            canvas.drawLine(startX, startY, endX, endY, headBoundaryTickPaint)
        }
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
        val clamped = clampToHrtfDomain(nx, nz)
        nx = clamped.first
        nz = clamped.second

        if (linkedMode) {
            applyLinkedLayout(nx, nz)
            notifyPositionChanged(fromUser)
            return
        }

        if (handle == Handle.LEFT) {
            leftXNorm = nx
            leftZNorm = nz
        } else {
            rightXNorm = nx
            rightZNorm = nz
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
        val scale = controlRadiusCm.toFloat().coerceAtLeast(1f)
        return (value / scale).coerceIn(-1f, 1f)
    }

    private fun currentOutputX(normalized: Float): Int {
        return (normalized * controlRadiusCm).roundToInt().coerceIn(-MAX_CONTROL_RADIUS_CM, MAX_CONTROL_RADIUS_CM)
    }

    private fun currentOutputZ(normalized: Float): Int {
        return (normalized * controlRadiusCm).roundToInt().coerceIn(-MAX_CONTROL_RADIUS_CM, MAX_CONTROL_RADIUS_CM)
    }

    private fun clampToUnit(x: Float, z: Float): Pair<Float, Float> {
        val magnitude = sqrt(x * x + z * z)
        if (magnitude <= 1f) {
            return x to z
        }
        return (x / magnitude) to (z / magnitude)
    }

    private fun clampToHrtfDomain(x: Float, z: Float): Pair<Float, Float> {
        val clamped = clampToUnit(x, z)
        val magnitude = sqrt(clamped.first * clamped.first + clamped.second * clamped.second)
        val minimum = minimumSourceRadiusNorm()
        if (magnitude >= minimum) {
            return clamped
        }
        if (magnitude < 0.0001f) {
            return 0f to minimum
        }
        val scale = minimum / magnitude
        return (clamped.first * scale).coerceIn(-1f, 1f) to (clamped.second * scale).coerceIn(-1f, 1f)
    }

    private fun minimumSourceRadiusNorm(): Float {
        val controlRadius = controlRadiusCm.toFloat().coerceAtLeast(1f)
        return (headRadiusCm / controlRadius).coerceIn(0.04f, 0.9f)
    }

    private fun linkedSpacingNorm(): Float {
        val scale = controlRadiusCm.toFloat().coerceAtLeast(1f)
        return (linkedChannelSpacingCm / scale).coerceIn(0f, 2f)
    }

    private fun applyLinkedLayout(centerXNorm: Float, centerZNorm: Float) {
        var centerX = centerXNorm.coerceIn(-1f, 1f)
        var centerZ = centerZNorm.coerceIn(-1f, 1f)
        var halfSpacing = (linkedSpacingNorm() * 0.5f).coerceIn(0f, 1f)

        val maxHalfByZ = (sqrt((1f - centerZ * centerZ).coerceAtLeast(0f)) - abs(centerX)).coerceAtLeast(0f)
        halfSpacing = min(halfSpacing, maxHalfByZ)

        val minimumRadius = minimumSourceRadiusNorm()
        val nearXAbs = (abs(centerX) - halfSpacing).coerceAtLeast(0f)
        val requiredZAbs = sqrt((minimumRadius * minimumRadius - nearXAbs * nearXAbs).coerceAtLeast(0f))
        val zSign = if (centerZ >= 0f) 1f else -1f
        var zAbs = abs(centerZ)
        if (zAbs < requiredZAbs) {
            zAbs = requiredZAbs
        }

        val maxZAbsByHalf = sqrt((1f - (abs(centerX) + halfSpacing).pow(2)).coerceAtLeast(0f))
        if (zAbs > maxZAbsByHalf) {
            zAbs = maxZAbsByHalf
        }
        centerZ = (zSign * zAbs).coerceIn(-1f, 1f)

        val maxHalfByFinalZ = (sqrt((1f - centerZ * centerZ).coerceAtLeast(0f)) - abs(centerX)).coerceAtLeast(0f)
        halfSpacing = min(halfSpacing, maxHalfByFinalZ)
        val maxCenterX = (sqrt((1f - centerZ * centerZ).coerceAtLeast(0f)) - halfSpacing).coerceAtLeast(0f)
        centerX = centerX.coerceIn(-maxCenterX, maxCenterX)

        leftXNorm = (centerX - halfSpacing).coerceIn(-1f, 1f)
        rightXNorm = (centerX + halfSpacing).coerceIn(-1f, 1f)
        leftZNorm = centerZ
        rightZNorm = centerZ
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        headGeometryDirty = true
    }

    private val density: Float
        get() = resources.displayMetrics.density

    companion object {
        private const val MIN_CONTROL_RADIUS_CM = 20
        private const val MAX_CONTROL_RADIUS_CM = 120
        private const val DEFAULT_CONTROL_RADIUS_CM = 100
        private const val DEFAULT_HEAD_RADIUS_CM = 8.7f
        private const val MIN_LINKED_SPACING_CM = 0
        private const val MAX_LINKED_SPACING_CM = MAX_CONTROL_RADIUS_CM * 2
        private const val DEFAULT_LINKED_SPACING_CM = 24
    }
}
