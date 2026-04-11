package com.example.hifx.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

class EqCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class EqPoint(
        val frequencyHz: Int,
        val gainMb: Int,
        val qTimes100: Int,
        val color: Int
    )

    private data class RenderPoint(
        val frequencyHz: Float,
        val gainMb: Float,
        val qTimes100: Float,
        val color: Int
    )

    var onPointClick: ((Int) -> Unit)? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(66, 140, 140, 140)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 200, 200, 200)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
    }
    private val curveGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 124, 202, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
    }
    private val peakFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val peakStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        color = Color.WHITE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 210, 220, 235)
        textSize = dp(10.5f)
    }

    private val path = Path()
    private var currentPoints: List<RenderPoint> = emptyList()
    private var startPoints: List<RenderPoint> = emptyList()
    private var targetPoints: List<RenderPoint> = emptyList()
    private var transitionProgress = 1f
    private var selectedIndex: Int = -1

    private val transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 240L
        interpolator = DecelerateInterpolator()
        addUpdateListener { animator ->
            transitionProgress = animator.animatedValue as Float
            invalidate()
        }
    }

    fun setData(points: List<EqPoint>, selectedIndex: Int) {
        val mapped = points.map {
            RenderPoint(
                frequencyHz = it.frequencyHz.toFloat(),
                gainMb = it.gainMb.toFloat(),
                qTimes100 = it.qTimes100.toFloat(),
                color = it.color
            )
        }
        this.selectedIndex = selectedIndex
        if (mapped.isEmpty()) {
            transitionAnimator.cancel()
            currentPoints = emptyList()
            startPoints = emptyList()
            targetPoints = emptyList()
            transitionProgress = 1f
            invalidate()
            return
        }
        if (currentPoints.isEmpty()) {
            currentPoints = mapped
            startPoints = mapped
            targetPoints = mapped
            transitionProgress = 1f
            invalidate()
            return
        }
        transitionAnimator.cancel()
        startPoints = currentPoints
        targetPoints = mapped
        transitionProgress = 0f
        transitionAnimator.start()
    }

    override fun onDetachedFromWindow() {
        transitionAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val contentLeft = paddingLeft.toFloat() + dp(10f)
        val contentRight = width - paddingRight.toFloat() - dp(8f)
        val contentTop = paddingTop.toFloat() + dp(20f)
        val contentBottom = height - paddingBottom.toFloat() - dp(24f)
        if (contentRight <= contentLeft || contentBottom <= contentTop) return

        val points = visiblePoints()
        drawGrid(canvas, contentLeft, contentTop, contentRight, contentBottom)
        drawPeaks(canvas, contentLeft, contentTop, contentRight, contentBottom, points)
        drawCurve(canvas, contentLeft, contentTop, contentRight, contentBottom, points)
        drawControlNodes(canvas, contentLeft, contentTop, contentRight, contentBottom, points)
        drawLabels(canvas, contentLeft, contentTop, contentRight, contentBottom)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val points = visiblePoints()
        if (points.isEmpty()) return true
        val tappedIndex = findNearestPointIndex(event.x, event.y, points)
        if (tappedIndex >= 0) {
            onPointClick?.invoke(tappedIndex)
        }
        return true
    }

    private fun drawGrid(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val dbMarks = listOf(-12f, -6f, 0f, 6f, 12f)
        dbMarks.forEach { db ->
            val y = gainDbToY(db, top, bottom)
            val paint = if (db == 0f) centerLinePaint else gridPaint
            canvas.drawLine(left, y, right, y, paint)
        }

        val freqMarks = intArrayOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000)
        freqMarks.forEach { hz ->
            val x = freqToX(hz.toFloat(), left, right)
            canvas.drawLine(x, top, x, bottom, gridPaint)
        }
    }

    private fun drawCurve(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, points: List<RenderPoint>) {
        if (points.isEmpty()) return
        path.reset()
        val steps = max(80, ((right - left) / dp(2f)).roundToInt())
        for (step in 0..steps) {
            val t = step / steps.toFloat()
            val x = left + (right - left) * t
            val hz = xToFreq(x, left, right)
            val gainDb = evaluateGainDbAt(hz, points)
            val y = gainDbToY(gainDb, top, bottom)
            if (step == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, curveGlowPaint)
        canvas.drawPath(path, curvePaint)
    }

    private fun drawPeaks(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, points: List<RenderPoint>) {
        if (points.isEmpty()) return
        val zeroY = gainDbToY(0f, top, bottom)
        points.forEachIndexed { index, point ->
            val color = point.color
            val peakPath = Path()
            val steps = 70
            for (step in 0..steps) {
                val t = step / steps.toFloat()
                val x = left + (right - left) * t
                val hz = xToFreq(x, left, right)
                val peakDb = bandContributionDb(point, hz)
                val y = gainDbToY(peakDb, top, bottom)
                if (step == 0) {
                    peakPath.moveTo(x, zeroY)
                    peakPath.lineTo(x, y)
                } else {
                    peakPath.lineTo(x, y)
                }
            }
            peakPath.lineTo(right, zeroY)
            peakPath.close()
            peakFillPaint.color = Color.argb(if (index == selectedIndex) 78 else 52, Color.red(color), Color.green(color), Color.blue(color))
            peakStrokePaint.color = Color.argb(if (index == selectedIndex) 220 else 150, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawPath(peakPath, peakFillPaint)
            canvas.drawPath(peakPath, peakStrokePaint)
        }
    }

    private fun drawLabels(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val freqLabels = arrayOf(20 to "20", 100 to "100", 1000 to "1k", 10000 to "10k", 20000 to "20k")
        val labelY = bottom + dp(14f)
        freqLabels.forEach { (hz, text) ->
            val x = freqToX(hz.toFloat(), left, right)
            val width = labelPaint.measureText(text)
            canvas.drawText(text, x - width / 2f, labelY, labelPaint)
        }
        canvas.drawText("+12 dB", left + dp(4f), top - dp(5f), labelPaint)
        canvas.drawText("0 dB", left + dp(4f), gainDbToY(0f, top, bottom) - dp(4f), labelPaint)
        canvas.drawText("-12 dB", left + dp(4f), bottom - dp(4f), labelPaint)
    }

    private fun drawControlNodes(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        points: List<RenderPoint>
    ) {
        points.forEachIndexed { index, point ->
            val x = freqToX(point.frequencyHz, left, right)
            val y = gainDbToY(point.gainMb / 100f, top, bottom)
            val radius = if (index == selectedIndex) dp(6.3f) else dp(4.8f)
            nodePaint.color = point.color
            canvas.drawCircle(x, y, radius, nodePaint)
            canvas.drawCircle(x, y, radius, nodeStrokePaint)
        }
    }

    private fun visiblePoints(): List<RenderPoint> {
        if (transitionProgress >= 1f || startPoints.isEmpty() || targetPoints.isEmpty()) {
            currentPoints = targetPoints.ifEmpty { currentPoints }
            return currentPoints
        }
        val blended = targetPoints.indices.map { index ->
            val end = targetPoints[index]
            val start = startPoints[index.coerceIn(0, startPoints.lastIndex)]
            RenderPoint(
                frequencyHz = lerp(start.frequencyHz, end.frequencyHz, transitionProgress),
                gainMb = lerp(start.gainMb, end.gainMb, transitionProgress),
                qTimes100 = lerp(start.qTimes100, end.qTimes100, transitionProgress),
                color = end.color
            )
        }
        if (transitionProgress >= 0.999f) {
            currentPoints = targetPoints
        }
        return blended
    }

    private fun findNearestPointIndex(touchX: Float, touchY: Float, points: List<RenderPoint>): Int {
        val left = paddingLeft.toFloat() + dp(10f)
        val right = width - paddingRight.toFloat() - dp(8f)
        val top = paddingTop.toFloat() + dp(20f)
        val bottom = height - paddingBottom.toFloat() - dp(24f)
        val threshold = dp(24f)
        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        points.forEachIndexed { index, point ->
            val x = freqToX(point.frequencyHz, left, right)
            val y = gainDbToY(point.gainMb / 100f, top, bottom)
            val dx = touchX - x
            val dy = touchY - y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist < bestDistance) {
                bestDistance = dist
                bestIndex = index
            }
        }
        return if (bestDistance <= threshold) bestIndex else -1
    }

    private fun evaluateGainDbAt(targetHz: Float, points: List<RenderPoint>): Float {
        if (points.isEmpty()) return 0f
        if (points.size == 1) return points[0].gainMb / 100f
        val ln2 = ln(2.0).toFloat()
        var sum = 0f
        var weightSum = 0f
        var nearest = points.first().gainMb
        var nearestDistance = Float.MAX_VALUE
        points.forEach { point ->
            val f0 = point.frequencyHz.coerceIn(20f, 20_000f)
            val q = (point.qTimes100 / 100f).coerceIn(0.2f, 12f)
            val sigmaOct = (1.1f / q).coerceIn(0.08f, 1.8f)
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
        val sigmaOct = (1.1f / q).coerceIn(0.08f, 1.8f)
        val ln2 = ln(2.0).toFloat()
        val distanceOct = abs((ln(targetHz.coerceAtLeast(20f) / f0) / ln2))
        val weight = exp(-0.5f * (distanceOct / sigmaOct).pow(2))
        return ((point.gainMb / 100f) * weight).coerceIn(-12f, 12f)
    }

    private fun freqToX(hz: Float, left: Float, right: Float): Float {
        val minHz = 20f
        val maxHz = 20_000f
        val clamped = hz.coerceIn(minHz, maxHz)
        val ratio = (ln(clamped) - ln(minHz)) / (ln(maxHz) - ln(minHz))
        return left + (right - left) * ratio
    }

    private fun xToFreq(x: Float, left: Float, right: Float): Float {
        val ratio = ((x - left) / max(1f, right - left)).coerceIn(0f, 1f)
        val minHz = 20f
        val maxHz = 20_000f
        return (minHz * (maxHz / minHz).toDouble().pow(ratio.toDouble())).toFloat()
    }

    private fun gainDbToY(gainDb: Float, top: Float, bottom: Float): Float {
        val clamped = gainDb.coerceIn(-12f, 12f)
        val ratio = (clamped + 12f) / 24f
        return bottom - (bottom - top) * ratio
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction.coerceIn(0f, 1f)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
