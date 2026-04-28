package com.example.hifx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.example.hifx.audio.AudioEngine
import com.example.hifx.audio.TopBarVisualizationMode
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class TopBarVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val LEVEL_METER_HEIGHT_DP = 24f
        private const val ANALOG_METER_HEIGHT_DP = 30f
        private const val WAVEFORM_HEIGHT_DP = 28f
        private const val BARS_HEIGHT_DP = 32f
    }

    private val drawBounds = RectF()
    private val contentBounds = RectF()
    private val tempRect = RectF()
    private val waveformPath = Path()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val waveformGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.2f * density
        alpha = 88
    }
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 0.40f * density
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val meterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val meterOffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val analogScalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val analogLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = 8.5f * density
    }
    private val analogRedZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val analogPointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val analogHubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val analogTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var visualizationEnabled = false
    private var visualizationMode = TopBarVisualizationMode.LEVEL_METER
    private var waveformSnapshot = FloatArray(0)
    private var barSnapshot = FloatArray(0)
    private val meterSnapshot = FloatArray(2)
    private val analogScaleLabels = arrayOf("-20", "-10", "-7", "-5", "-3", "0", "+3")
    private val analogScalePositions = floatArrayOf(0f, 0.18f, 0.34f, 0.5f, 0.68f, 0.84f, 1f)

    private var analogLeftPointer = 0f
    private var analogRightPointer = 0f
    private var analogLeftVelocity = 0f
    private var analogRightVelocity = 0f
    private var lastFrameNanos = 0L
    private var useIsoDacTheme = false

    fun applySettings(enabled: Boolean, mode: TopBarVisualizationMode) {
        val changed = visualizationEnabled != enabled || visualizationMode != mode
        val modeChanged = visualizationMode != mode
        visualizationEnabled = enabled
        visualizationMode = mode
        visibility = if (enabled) View.VISIBLE else View.GONE
        if (modeChanged) {
            resetAnalogMotion()
            requestLayout()
        }
        if (changed && enabled) {
            postInvalidateOnAnimation()
        } else if (changed) {
            invalidate()
        }
    }

    fun setIsoDacTheme(enabled: Boolean) {
        if (useIsoDacTheme == enabled) return
        useIsoDacTheme = enabled
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = when (visualizationMode) {
            TopBarVisualizationMode.AUDIO_INFO -> 0
            TopBarVisualizationMode.LEVEL_METER -> dp(LEVEL_METER_HEIGHT_DP)
            TopBarVisualizationMode.ANALOG_METER -> dp(ANALOG_METER_HEIGHT_DP)
            TopBarVisualizationMode.WAVEFORM -> dp(WAVEFORM_HEIGHT_DP)
            TopBarVisualizationMode.BARS -> dp(BARS_HEIGHT_DP)
        }
        val resolvedWidth = MeasureSpec.getSize(widthMeasureSpec)
        val resolvedHeight = resolveSize(
            (desiredHeight + paddingTop + paddingBottom).coerceAtLeast(suggestedMinimumHeight),
            heightMeasureSpec
        )
        setMeasuredDimension(resolvedWidth, resolvedHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visualizationEnabled) {
            return
        }
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right <= left || bottom <= top) {
            return
        }

        applyDisplayPalette()
        drawBounds.set(left, top, right, bottom)
        canvas.drawRect(drawBounds, backgroundPaint)
        contentBounds.set(
            drawBounds.left + 1f * density,
            drawBounds.top + 1f * density,
            drawBounds.right - 1f * density,
            drawBounds.bottom - 1f * density
        )
        drawGrid(canvas)

        when (visualizationMode) {
            TopBarVisualizationMode.AUDIO_INFO -> Unit
            TopBarVisualizationMode.LEVEL_METER -> drawLevelMeter(canvas)
            TopBarVisualizationMode.ANALOG_METER -> drawAnalogMeter(canvas)
            TopBarVisualizationMode.WAVEFORM -> drawWaveform(canvas)
            TopBarVisualizationMode.BARS -> drawBars(canvas)
        }
        if (isAttachedToWindow && visibility == View.VISIBLE) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val verticalDivisions = 2
        val horizontalDivisions = 3
        for (index in 1 until verticalDivisions) {
            val x = contentBounds.left + contentBounds.width() * index / verticalDivisions
            canvas.drawLine(x, contentBounds.top, x, contentBounds.bottom, gridPaint)
        }
        for (index in 1 until horizontalDivisions) {
            val y = contentBounds.top + contentBounds.height() * index / horizontalDivisions
            canvas.drawLine(contentBounds.left, y, contentBounds.right, y, gridPaint)
        }
    }

    private fun drawLevelMeter(canvas: Canvas) {
        AudioEngine.fillTopBarVisualizationSnapshot(TopBarVisualizationMode.LEVEL_METER, meterSnapshot)
        val leftLevel = meterSnapshot.getOrElse(0) { 0f }.coerceIn(0f, 1f)
        val rightLevel = meterSnapshot.getOrElse(1) { leftLevel }.coerceIn(0f, 1f)
        val innerPadding = 1.5f * density
        val laneGap = 3f * density
        val laneHeight = (drawBounds.height() - innerPadding * 2f - laneGap) / 2f
        val topLane = RectF(
            drawBounds.left + innerPadding,
            drawBounds.top + innerPadding,
            drawBounds.right - innerPadding,
            drawBounds.top + innerPadding + laneHeight
        )
        val bottomLane = RectF(
            drawBounds.left + innerPadding,
            topLane.bottom + laneGap,
            drawBounds.right - innerPadding,
            drawBounds.bottom - innerPadding
        )
        drawMeterLane(canvas, topLane, leftLevel)
        drawMeterLane(canvas, bottomLane, rightLevel)
    }

    private fun drawMeterLane(canvas: Canvas, lane: RectF, level: Float) {
        val segments = max(18, (lane.width() / (3.2f * density)).toInt())
        val gap = 1f * density
        val segmentWidth = ((lane.width() - gap * (segments - 1)) / segments).coerceAtLeast(1.2f * density)
        val activeSegments = (segments * level).toInt().coerceIn(0, segments)
        for (index in 0 until segments) {
            val left = lane.left + index * (segmentWidth + gap)
            tempRect.set(left, lane.top, left + segmentWidth, lane.bottom)
            val ratio = index / max(1f, (segments - 1).toFloat())
            val paint = if (index < activeSegments) meterPaint else meterOffPaint
            if (index < activeSegments) {
                paint.color = when {
                    ratio > 0.88f -> 0xFFFF3B30.toInt()
                    ratio > 0.72f -> 0xFFFFC145.toInt()
                    else -> 0xFF34E26F.toInt()
                }
            }
            canvas.drawRect(tempRect, paint)
        }
        val peakX = lane.left + lane.width() * level
        canvas.drawLine(peakX, lane.top - 0.5f * density, peakX, lane.bottom + 0.5f * density, capPaint)
    }

    private fun drawAnalogMeter(canvas: Canvas) {
        AudioEngine.fillTopBarVisualizationSnapshot(TopBarVisualizationMode.ANALOG_METER, meterSnapshot)
        val (leftLevel, rightLevel) = resolveAnalogStereoLevels(
            meterSnapshot.getOrElse(0) { 0f },
            meterSnapshot.getOrElse(1) { meterSnapshot.getOrElse(0) { 0f } }
        )
        val leftTarget = meterToNeedleTarget(leftLevel)
        val rightTarget = meterToNeedleTarget(rightLevel)
        stepAnalogPhysics(leftTarget, rightTarget)

        val labelBandHeight = drawBounds.height() * 0.16f
        val laneGap = 4f * density
        val laneHeight = (drawBounds.height() - labelBandHeight - laneGap * 2f) / 2f
        val topLane = RectF(drawBounds.left, drawBounds.top, drawBounds.right, drawBounds.top + laneHeight)
        val labelLane = RectF(drawBounds.left, topLane.bottom + laneGap, drawBounds.right, topLane.bottom + laneGap + labelBandHeight)
        val bottomLane = RectF(drawBounds.left, labelLane.bottom + laneGap, drawBounds.right, labelLane.bottom + laneGap + laneHeight)
        drawAnalogLane(canvas, topLane, analogLeftPointer, 0xFF8FFFD6.toInt(), showLabels = false)
        drawAnalogLane(canvas, bottomLane, analogRightPointer, 0xFFFFD37A.toInt(), showLabels = false)
        drawAnalogLabels(canvas, labelLane)
    }

    private fun drawAnalogLane(canvas: Canvas, lane: RectF, pointer: Float, pointerColor: Int, showLabels: Boolean) {
        val left = lane.left + 4f * density
        val right = lane.right - 4f * density
        val centerY = lane.centerY()
        val scaleTop = lane.top + 2.5f * density
        val scaleBottom = lane.bottom - 2.5f * density
        val usableWidth = right - left
        val majorTicks = 10
        val minorTicksPerMajor = 3
        val redZoneStartX = left + usableWidth * 0.86f

        analogRedZonePaint.strokeWidth = 2f * density
        canvas.drawLine(redZoneStartX, scaleTop, right, scaleTop, analogRedZonePaint)

        analogScalePaint.strokeWidth = 1f * density
        for (major in 0..majorTicks) {
            val baseRatio = major / majorTicks.toFloat()
            val x = left + usableWidth * baseRatio
            canvas.drawLine(x, scaleTop, x, scaleBottom, analogScalePaint)
            if (major == majorTicks) continue
            for (minor in 1 until minorTicksPerMajor) {
                val minorRatio = (major + minor / minorTicksPerMajor.toFloat()) / majorTicks.toFloat()
                val minorX = left + usableWidth * minorRatio
                val tickHalf = lane.height() * 0.18f
                canvas.drawLine(minorX, centerY - tickHalf, minorX, centerY + tickHalf, gridPaint)
            }
        }

        if (showLabels) {
            drawAnalogLabels(canvas, lane)
        }

        val pointerX = left + usableWidth * pointer.coerceIn(0f, 1.06f)
        analogTrailPaint.color = pointerColor
        analogTrailPaint.alpha = 82
        analogTrailPaint.strokeWidth = 1.5f * density
        canvas.drawLine(left, centerY, pointerX, centerY, analogTrailPaint)

        analogPointerPaint.color = pointerColor
        analogPointerPaint.strokeWidth = 2.3f * density
        canvas.drawLine(pointerX, lane.top + 1.5f * density, pointerX, lane.bottom - 1.5f * density, analogPointerPaint)
        analogHubPaint.color = pointerColor
        canvas.drawCircle(pointerX, centerY, 1.8f * density, analogHubPaint)
    }

    private fun drawAnalogLabels(canvas: Canvas, lane: RectF) {
        val left = lane.left + 4f * density
        val right = lane.right - 4f * density
        val usableWidth = right - left
        val fm = analogLabelPaint.fontMetrics
        val baseline = lane.centerY() - (fm.ascent + fm.descent) * 0.5f
        analogScaleLabels.forEachIndexed { index, label ->
            val x = left + usableWidth * analogScalePositions[index]
            canvas.drawText(label, x, baseline, analogLabelPaint)
        }
    }

    private fun drawWaveform(canvas: Canvas) {
        waveformSnapshot = ensureBuffer(waveformSnapshot, max(96, width / max(1, dp(3f))))
        AudioEngine.fillTopBarVisualizationSnapshot(TopBarVisualizationMode.WAVEFORM, waveformSnapshot)
        if (waveformSnapshot.isEmpty()) {
            return
        }
        val centerY = contentBounds.centerY()
        val amplitude = contentBounds.height() * 0.44f
        val step = contentBounds.width() / max(1, waveformSnapshot.size - 1)
        waveformPath.reset()
        waveformSnapshot.forEachIndexed { index, value ->
            val x = contentBounds.left + step * index
            val y = centerY - value.coerceIn(-1f, 1f) * amplitude
            if (index == 0) {
                waveformPath.moveTo(x, y)
            } else {
                waveformPath.lineTo(x, y)
            }
        }
        canvas.drawLine(contentBounds.left, centerY, contentBounds.right, centerY, axisPaint)
        canvas.drawPath(waveformPath, waveformGlowPaint)
        canvas.drawPath(waveformPath, waveformPaint)
    }

    private fun drawBars(canvas: Canvas) {
        barSnapshot = ensureBuffer(barSnapshot, 48)
        AudioEngine.fillTopBarVisualizationSnapshot(TopBarVisualizationMode.BARS, barSnapshot)
        if (barSnapshot.isEmpty()) {
            return
        }
        val innerPadding = 1.5f * density
        val contentWidth = contentBounds.width() - innerPadding * 2f
        val contentHeight = contentBounds.height() - innerPadding * 2f
        val gap = 1f * density
        val barWidth = ((contentWidth - gap * (barSnapshot.size - 1)) / barSnapshot.size).coerceAtLeast(1.5f * density)
        barSnapshot.forEachIndexed { index, value ->
            val normalized = value.coerceIn(0f, 1f)
            val barHeight = max(contentHeight * 0.05f, normalized * contentHeight)
            val left = contentBounds.left + innerPadding + index * (barWidth + gap)
            val top = contentBounds.bottom - innerPadding - barHeight
            tempRect.set(left, top, left + barWidth, contentBounds.bottom - innerPadding)
            canvas.drawRect(tempRect, barPaint)
            val capTop = (top - 1.6f * density).coerceAtLeast(contentBounds.top + innerPadding)
            canvas.drawRect(left, capTop, left + barWidth, capTop + 1.2f * density, capPaint)
        }
    }

    private fun applyDisplayPalette() {
        val surfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
        val isoGold = 0x1150391a.toInt()
        backgroundPaint.color = if (useIsoDacTheme) isoGold else surfaceColor
        gridPaint.color = 0x1434E26F
        axisPaint.color = 0x4D34E26F
        waveformGlowPaint.color = 0xFF6BFFD0.toInt()
        waveformPaint.color = 0xFF9DFFE6.toInt()
        capPaint.color = 0xFFFFF2A6.toInt()
        meterOffPaint.color = 0x1034E26F
        analogScalePaint.color = 0x4CE9F7D6
        analogLabelPaint.color = 0x99E9F7D6.toInt()
        analogRedZonePaint.color = 0x99FF6A4A.toInt()
        analogTrailPaint.color = 0x668FFFD6
        meterPaint.shader = null
        barPaint.shader = LinearGradient(
            0f,
            drawBounds.bottom,
            0f,
            drawBounds.top,
            intArrayOf(
                0xFF1ED760.toInt(),
                0xFF76F55B.toInt(),
                0xFFFFC145.toInt(),
                0xFFFF5A36.toInt()
            ),
            floatArrayOf(0f, 0.45f, 0.8f, 1f),
            Shader.TileMode.CLAMP
        )
        barPaint.alpha = 255
    }

    private val density: Float
        get() = resources.displayMetrics.density

    private fun dp(value: Float): Int = (value * density).toInt()

    private fun ensureBuffer(buffer: FloatArray, requiredSize: Int): FloatArray {
        return if (buffer.size == requiredSize) buffer else FloatArray(requiredSize)
    }

    private fun resetAnalogMotion() {
        analogLeftPointer = 0f
        analogRightPointer = 0f
        analogLeftVelocity = 0f
        analogRightVelocity = 0f
        lastFrameNanos = 0L
    }

    private fun stepAnalogPhysics(leftTarget: Float, rightTarget: Float) {
        val now = System.nanoTime()
        val dtSeconds = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((now - lastFrameNanos) / 1_000_000_000.0f).coerceIn(1f / 240f, 1f / 24f)
        }
        lastFrameNanos = now

        val stiffness = 104f
        val damping = 8.6f
        val staticDrag = 1.45f

        analogLeftVelocity += ((leftTarget - analogLeftPointer) * stiffness - analogLeftVelocity * damping) * dtSeconds
        analogRightVelocity += ((rightTarget - analogRightPointer) * stiffness - analogRightVelocity * damping) * dtSeconds

        analogLeftVelocity *= (1f - staticDrag * dtSeconds).coerceIn(0.82f, 1f)
        analogRightVelocity *= (1f - staticDrag * dtSeconds).coerceIn(0.82f, 1f)

        analogLeftPointer = (analogLeftPointer + analogLeftVelocity * dtSeconds).coerceIn(0f, 1.06f)
        analogRightPointer = (analogRightPointer + analogRightVelocity * dtSeconds).coerceIn(0f, 1.06f)
    }

    private fun resolveAnalogStereoLevels(rawLeft: Float, rawRight: Float): Pair<Float, Float> {
        val left = rawLeft.coerceIn(0f, 1f)
        val right = rawRight.coerceIn(0f, 1f)
        val center = (left + right) * 0.5f
        val delta = (left - right) * 0.22f
        return Pair(
            (center + (left - center) * 1.08f + delta).coerceIn(0f, 1f),
            (center + (right - center) * 1.08f - delta).coerceIn(0f, 1f)
        )
    }

    private fun meterToNeedleTarget(level: Float): Float {
        val normalized = level.coerceIn(0f, 1f)
        if (normalized <= 0.0001f) {
            return 0f
        }
        return normalized.pow(0.52f).coerceIn(0f, 1f)
    }
}
