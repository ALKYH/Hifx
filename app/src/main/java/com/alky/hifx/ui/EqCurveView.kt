package com.alky.hifx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class EqCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private data class FrequencyGridMark(
        val frequencyHz: Float,
        val major: Boolean
    )

    data class EqPoint(
        val frequencyHz: Int,
        val gainMb: Int,
        val qTimes100: Int,
        val color: Int
    )

    private data class RenderPoint(
        var frequencyHz: Float,
        var gainMb: Float,
        var qTimes100: Float,
        var color: Int
    )

    var onPointClick: ((Int) -> Unit)? = null

    private val contentRect = RectF()
    private val curvePath = Path()
    private val spectrumPath = Path()
    private val peakPath = Path()
    private var contentLeftCache = Float.NaN
    private var contentTopCache = Float.NaN
    private var contentRightCache = Float.NaN
    private var contentBottomCache = Float.NaN
    private var spectrumFillShader: LinearGradient? = null
    private var spectrumXPositions = FloatArray(0)
    private var curveSampleXs = FloatArray(0)
    private var curveSampleFrequencies = FloatArray(0)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val majorGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(58, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 179, 219, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.15f)
    }
    private val spectrumFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val spectrumStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(88, 142, 213, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.15f)
        pathEffect = CornerPathEffect(dp(8f))
    }
    private val curveGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 132, 205, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(5.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(dp(10f))
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2.1f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = CornerPathEffect(dp(10f))
    }
    private val peakFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val peakStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(245, 255, 255, 255)
    }
    private val nodeHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 226, 234, 245)
        textSize = dp(10.5f)
    }

    private var renderPoints: MutableList<RenderPoint> = mutableListOf()
    private var targetPoints: List<RenderPoint> = emptyList()
    private var selectedIndex: Int = -1
    private var spectrumRender = FloatArray(64)
    private var spectrumTarget = FloatArray(64)
    private val frequencyGridMarks = buildFrequencyGridMarks()

    fun setData(points: List<EqPoint>, selectedIndex: Int) {
        this.selectedIndex = selectedIndex
        val mapped = points.map {
            RenderPoint(
                frequencyHz = it.frequencyHz.toFloat(),
                gainMb = it.gainMb.toFloat(),
                qTimes100 = it.qTimes100.toFloat(),
                color = it.color
            )
        }
        targetPoints = mapped
        if (mapped.isEmpty()) {
            renderPoints.clear()
            invalidate()
            return
        }
        if (renderPoints.size != mapped.size) {
            renderPoints = mapped.map {
                RenderPoint(
                    frequencyHz = it.frequencyHz,
                    gainMb = it.gainMb,
                    qTimes100 = it.qTimes100,
                    color = it.color
                )
            }.toMutableList()
            invalidate()
            return
        }
        postInvalidateOnAnimation()
    }

    fun setSpectrum(spectrum: FloatArray) {
        if (spectrumTarget.size != spectrum.size) {
            spectrumTarget = FloatArray(spectrum.size)
            spectrumRender = FloatArray(spectrum.size)
        }
        if (spectrum.isNotEmpty()) {
            System.arraycopy(spectrum, 0, spectrumTarget, 0, spectrum.size)
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val contentLeft = paddingLeft.toFloat() + dp(14f)
        val contentRight = width - paddingRight.toFloat() - dp(14f)
        val contentTop = paddingTop.toFloat() + dp(18f)
        val contentBottom = height - paddingBottom.toFloat() - dp(28f)
        if (contentRight <= contentLeft || contentBottom <= contentTop) return

        contentRect.set(contentLeft, contentTop, contentRight, contentBottom)
        ensureRenderCaches()
        val pointsSettled = stepPointAnimation()
        val spectrumSettled = stepSpectrumAnimation()

        drawSpectrum(canvas)
        drawGrid(canvas)
        drawPeaks(canvas)
        drawCurve(canvas)
        drawControlNodes(canvas)
        drawLabels(canvas)

        if (!pointsSettled || !spectrumSettled) {
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        if (renderPoints.isEmpty()) return true
        val tappedIndex = findNearestPointIndex(event.x, event.y)
        if (tappedIndex >= 0) {
            onPointClick?.invoke(tappedIndex)
        }
        return true
    }

    private fun stepPointAnimation(): Boolean {
        if (targetPoints.isEmpty()) {
            renderPoints.clear()
            return true
        }
        if (renderPoints.size != targetPoints.size) {
            renderPoints = targetPoints.map {
                RenderPoint(it.frequencyHz, it.gainMb, it.qTimes100, it.color)
            }.toMutableList()
            return false
        }
        var settled = true
        renderPoints.indices.forEach { index ->
            val current = renderPoints[index]
            val target = targetPoints[index]
            settled = approach(current, target) && settled
        }
        return settled
    }

    private fun approach(current: RenderPoint, target: RenderPoint): Boolean {
        current.color = target.color
        val freqSettled = approachValue(
            current = current.frequencyHz,
            target = target.frequencyHz,
            factor = 0.22f,
            snapThreshold = 0.25f
        ).also { current.frequencyHz = it.first }
        val gainSettled = approachValue(
            current = current.gainMb,
            target = target.gainMb,
            factor = 0.26f,
            snapThreshold = 0.18f
        ).also { current.gainMb = it.first }
        val qSettled = approachValue(
            current = current.qTimes100,
            target = target.qTimes100,
            factor = 0.22f,
            snapThreshold = 0.18f
        ).also { current.qTimes100 = it.first }
        return freqSettled.second && gainSettled.second && qSettled.second
    }

    private fun stepSpectrumAnimation(): Boolean {
        if (spectrumRender.size != spectrumTarget.size) {
            spectrumRender = FloatArray(spectrumTarget.size)
        }
        var settled = true
        spectrumTarget.indices.forEach { index ->
            val current = spectrumRender[index]
            val target = spectrumTarget[index]
            val delta = target - current
            val attack = if (delta > 0f) 0.24f else 0.1f
            val next = if (abs(delta) < 0.0025f) target else current + delta * attack
            spectrumRender[index] = next
            if (abs(target - next) >= 0.0025f) {
                settled = false
            }
        }
        return settled
    }

    private fun drawGrid(canvas: Canvas) {
        val left = contentRect.left
        val right = contentRect.right
        val top = contentRect.top
        val bottom = contentRect.bottom

        val dbMarks = listOf(-12f, -6f, 0f, 6f, 12f)
        dbMarks.forEach { db ->
            val y = gainDbToY(db)
            val paint = when (db) {
                0f -> centerLinePaint
                -12f, 12f -> majorGridPaint
                else -> gridPaint
            }
            canvas.drawLine(left, y, right, y, paint)
        }

        frequencyGridMarks.forEach { mark ->
            val x = freqToX(mark.frequencyHz)
            canvas.drawLine(x, top, x, bottom, if (mark.major) majorGridPaint else gridPaint)
        }
    }

    private fun drawSpectrum(canvas: Canvas) {
        if (spectrumRender.isEmpty()) return
        val zeroY = gainDbToY(-12f)
        spectrumPath.reset()
        spectrumPath.moveTo(contentRect.left, zeroY)
        spectrumRender.forEachIndexed { index, value ->
            val x = spectrumXPositions.getOrElse(index) { contentRect.left }
            val y = contentRect.bottom - value.coerceIn(0f, 1f).pow(0.72f) * contentRect.height() * 0.9f
            spectrumPath.lineTo(x, y.coerceIn(contentRect.top, contentRect.bottom))
        }
        spectrumPath.lineTo(contentRect.right, zeroY)
        spectrumPath.close()

        spectrumFillPaint.shader = spectrumFillShader
        canvas.drawPath(spectrumPath, spectrumFillPaint)
        canvas.drawPath(spectrumPath, spectrumStrokePaint)
    }

    private fun drawCurve(canvas: Canvas) {
        if (renderPoints.isEmpty()) return
        curvePath.reset()
        curveSampleXs.indices.forEach { step ->
            val x = curveSampleXs[step]
            val hz = curveSampleFrequencies[step]
            val gainDb = evaluateGainDbAt(hz)
            val y = gainDbToY(gainDb)
            if (step == 0) {
                curvePath.moveTo(x, y)
            } else {
                curvePath.lineTo(x, y)
            }
        }
        canvas.drawPath(curvePath, curveGlowPaint)
        canvas.drawPath(curvePath, curvePaint)
    }

    private fun drawPeaks(canvas: Canvas) {
        if (renderPoints.isEmpty()) return
        val zeroY = gainDbToY(0f)
        renderPoints.forEachIndexed { index, point ->
            peakPath.reset()
            curveSampleXs.indices.forEach { step ->
                val x = curveSampleXs[step]
                val hz = curveSampleFrequencies[step]
                val peakDb = bandContributionDb(point, hz)
                val y = gainDbToY(peakDb)
                if (step == 0) {
                    peakPath.moveTo(x, zeroY)
                    peakPath.lineTo(x, y)
                } else {
                    peakPath.lineTo(x, y)
                }
            }
            peakPath.lineTo(contentRect.right, zeroY)
            peakPath.close()
            val alphaBias = if (index == selectedIndex) 74 else 38
            peakFillPaint.color = Color.argb(
                alphaBias,
                Color.red(point.color),
                Color.green(point.color),
                Color.blue(point.color)
            )
            peakStrokePaint.color = Color.argb(
                if (index == selectedIndex) 180 else 110,
                Color.red(point.color),
                Color.green(point.color),
                Color.blue(point.color)
            )
            canvas.drawPath(peakPath, peakFillPaint)
            canvas.drawPath(peakPath, peakStrokePaint)
        }
    }

    private fun drawLabels(canvas: Canvas) {
        val freqLabels = arrayOf(20 to "20", 100 to "100", 1000 to "1k", 10000 to "10k", 20000 to "20k")
        val labelY = contentRect.bottom + dp(18f)
        freqLabels.forEachIndexed { index, (hz, text) ->
            val x = freqToX(hz.toFloat())
            val width = labelPaint.measureText(text)
            val drawX = when (index) {
                0 -> x
                freqLabels.lastIndex -> x - width
                else -> x - width / 2f
            }
            canvas.drawText(text, drawX, labelY, labelPaint)
        }
        canvas.drawText("+12", contentRect.left + dp(2f), contentRect.top - dp(6f), labelPaint)
        canvas.drawText("0", contentRect.left + dp(2f), gainDbToY(0f) - dp(6f), labelPaint)
        canvas.drawText("-12", contentRect.left + dp(2f), contentRect.bottom - dp(4f), labelPaint)
    }

    private fun drawControlNodes(canvas: Canvas) {
        renderPoints.forEachIndexed { index, point ->
            val x = freqToX(point.frequencyHz)
            val y = gainDbToY(point.gainMb / 100f)
            val radius = if (index == selectedIndex) dp(6.2f) else dp(4.8f)
            nodeHaloPaint.color = Color.argb(
                if (index == selectedIndex) 72 else 34,
                Color.red(point.color),
                Color.green(point.color),
                Color.blue(point.color)
            )
            nodePaint.color = point.color
            canvas.drawCircle(x, y, radius + dp(4f), nodeHaloPaint)
            canvas.drawCircle(x, y, radius, nodePaint)
            canvas.drawCircle(x, y, radius, nodeStrokePaint)
        }
    }

    private fun findNearestPointIndex(touchX: Float, touchY: Float): Int {
        val threshold = dp(26f)
        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        renderPoints.forEachIndexed { index, point ->
            val x = freqToX(point.frequencyHz)
            val y = gainDbToY(point.gainMb / 100f)
            val dx = touchX - x
            val dy = touchY - y
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < bestDistance) {
                bestDistance = dist
                bestIndex = index
            }
        }
        return if (bestDistance <= threshold) bestIndex else -1
    }

    private fun evaluateGainDbAt(targetHz: Float): Float {
        if (renderPoints.isEmpty()) return 0f
        if (renderPoints.size == 1) return renderPoints[0].gainMb / 100f
        val ln2 = ln(2.0).toFloat()
        var sum = 0f
        var weightSum = 0f
        var nearest = renderPoints.first().gainMb
        var nearestDistance = Float.MAX_VALUE
        renderPoints.forEach { point ->
            val f0 = point.frequencyHz.coerceIn(20f, 20_000f)
            val q = (point.qTimes100 / 100f).coerceIn(0.2f, 12f)
            val sigmaOct = (1.08f / q).coerceIn(0.08f, 1.8f)
            val distanceOct = abs((ln(targetHz.coerceAtLeast(20f) / f0) / ln2))
            if (distanceOct < nearestDistance) {
                nearestDistance = distanceOct
                nearest = point.gainMb
            }
            val weight = exp(-0.5f * (distanceOct / sigmaOct).pow(2))
            sum += point.gainMb * weight
            weightSum += weight
        }
        val gainMb = if (weightSum <= 0.0001f) nearest else sum / weightSum
        return (gainMb / 100f).coerceIn(-12f, 12f)
    }

    private fun bandContributionDb(point: RenderPoint, targetHz: Float): Float {
        val f0 = point.frequencyHz.coerceIn(20f, 20_000f)
        val q = (point.qTimes100 / 100f).coerceIn(0.2f, 12f)
        val sigmaOct = (1.08f / q).coerceIn(0.08f, 1.8f)
        val ln2 = ln(2.0).toFloat()
        val distanceOct = abs((ln(targetHz.coerceAtLeast(20f) / f0) / ln2))
        val weight = exp(-0.5f * (distanceOct / sigmaOct).pow(2))
        return ((point.gainMb / 100f) * weight).coerceIn(-12f, 12f)
    }

    private fun freqToX(hz: Float): Float {
        val minHz = 20f
        val maxHz = 20_000f
        val clamped = hz.coerceIn(minHz, maxHz)
        val ratio = (ln(clamped) - ln(minHz)) / (ln(maxHz) - ln(minHz))
        return contentRect.left + contentRect.width() * ratio
    }

    private fun xToFreq(x: Float): Float {
        val ratio = ((x - contentRect.left) / max(1f, contentRect.width())).coerceIn(0f, 1f)
        return xRatioToFrequency(ratio)
    }

    private fun xRatioToFrequency(ratio: Float): Float {
        val minHz = 20f
        val maxHz = 20_000f
        return (minHz * (maxHz / minHz).toDouble().pow(ratio.coerceIn(0f, 1f).toDouble())).toFloat()
    }

    private fun gainDbToY(gainDb: Float): Float {
        val clamped = gainDb.coerceIn(-12f, 12f)
        val ratio = (clamped + 12f) / 24f
        return contentRect.bottom - contentRect.height() * ratio
    }

    private fun approachValue(
        current: Float,
        target: Float,
        factor: Float,
        snapThreshold: Float
    ): Pair<Float, Boolean> {
        val delta = target - current
        return if (abs(delta) <= snapThreshold) {
            target to true
        } else {
            (current + delta * factor) to false
        }
    }

    private fun buildFrequencyGridMarks(): List<FrequencyGridMark> {
        val marks = mutableListOf<FrequencyGridMark>()
        val majorFrequencies = setOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10_000f, 20_000f)
        listOf(10f, 100f, 1000f, 10_000f).forEach { decade ->
            for (multiplier in 1..9) {
                val frequency = decade * multiplier
                if (frequency in 20f..20_000f) {
                    marks += FrequencyGridMark(
                        frequencyHz = frequency,
                        major = frequency in majorFrequencies
                    )
                }
            }
        }
        return marks.distinctBy { it.frequencyHz }
            .sortedBy { it.frequencyHz }
    }

    private fun ensureRenderCaches() {
        if (contentLeftCache == contentRect.left &&
            contentTopCache == contentRect.top &&
            contentRightCache == contentRect.right &&
            contentBottomCache == contentRect.bottom &&
            spectrumXPositions.size == spectrumRender.size &&
            curveSampleXs.isNotEmpty()
        ) {
            return
        }
        contentLeftCache = contentRect.left
        contentTopCache = contentRect.top
        contentRightCache = contentRect.right
        contentBottomCache = contentRect.bottom

        spectrumFillShader = LinearGradient(
            0f,
            contentRect.bottom,
            0f,
            contentRect.top,
            intArrayOf(
                Color.argb(14, 120, 193, 255),
                Color.argb(36, 120, 193, 255),
                Color.argb(76, 140, 220, 255)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        spectrumXPositions = FloatArray(spectrumRender.size) { index ->
            val ratio = if (spectrumRender.size <= 1) 0f else index / (spectrumRender.size - 1).toFloat()
            freqToX(xRatioToFrequency(ratio))
        }

        val sampleCount = max(128, (contentRect.width() / dp(2.2f)).roundToInt()) + 1
        curveSampleXs = FloatArray(sampleCount)
        curveSampleFrequencies = FloatArray(sampleCount)
        curveSampleXs.indices.forEach { step ->
            val t = if (curveSampleXs.lastIndex <= 0) 0f else step / curveSampleXs.lastIndex.toFloat()
            val x = contentRect.left + contentRect.width() * t
            curveSampleXs[step] = x
            curveSampleFrequencies[step] = xToFreq(x)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
