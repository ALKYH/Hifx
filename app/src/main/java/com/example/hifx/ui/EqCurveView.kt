package com.example.hifx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
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
    private val peakFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val peakStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }
    private val path = Path()
    private var points: List<EqPoint> = emptyList()
    private var selectedIndex: Int = -1

    fun setData(points: List<EqPoint>, selectedIndex: Int) {
        this.points = points
        this.selectedIndex = selectedIndex
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val contentLeft = paddingLeft.toFloat() + dp(8f)
        val contentRight = width - paddingRight.toFloat() - dp(8f)
        val contentTop = paddingTop.toFloat() + dp(12f)
        val contentBottom = height - paddingBottom.toFloat() - dp(14f)
        if (contentRight <= contentLeft || contentBottom <= contentTop) return

        drawGrid(canvas, contentLeft, contentTop, contentRight, contentBottom)
        drawPeaks(canvas, contentLeft, contentTop, contentRight, contentBottom)
        drawCurve(canvas, contentLeft, contentTop, contentRight, contentBottom)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        if (points.isEmpty()) return true
        val tappedIndex = findNearestPointIndex(event.x, event.y)
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

    private fun drawCurve(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        if (points.isEmpty()) return
        path.reset()
        val steps = max(80, ((right - left) / dp(2f)).roundToInt())
        for (step in 0..steps) {
            val t = step / steps.toFloat()
            val x = left + (right - left) * t
            val hz = xToFreq(x, left, right)
            val gainDb = evaluateGainDbAt(hz)
            val y = gainDbToY(gainDb, top, bottom)
            if (step == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, curvePaint)
    }

    private fun drawPeaks(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
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

    private fun findNearestPointIndex(touchX: Float, touchY: Float): Int {
        val left = paddingLeft.toFloat() + dp(8f)
        val right = width - paddingRight.toFloat() - dp(8f)
        val top = paddingTop.toFloat() + dp(12f)
        val bottom = height - paddingBottom.toFloat() - dp(14f)
        val threshold = dp(24f)
        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        points.forEachIndexed { index, point ->
            val x = freqToX(point.frequencyHz.toFloat(), left, right)
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

    private fun evaluateGainDbAt(targetHz: Float): Float {
        if (points.isEmpty()) return 0f
        if (points.size == 1) return points[0].gainMb / 100f
        val ln2 = ln(2.0).toFloat()
        var sum = 0f
        var weightSum = 0f
        var nearest = points.first().gainMb.toFloat()
        var nearestDistance = Float.MAX_VALUE
        points.forEach { point ->
            val f0 = point.frequencyHz.toFloat().coerceIn(20f, 20_000f)
            val q = (point.qTimes100 / 100f).coerceIn(0.2f, 12f)
            val sigmaOct = (1.1f / q).coerceIn(0.08f, 1.8f)
            val distanceOct = abs((ln(targetHz.coerceAtLeast(20f) / f0) / ln2))
            if (distanceOct < nearestDistance) {
                nearestDistance = distanceOct
                nearest = point.gainMb.toFloat()
            }
            val weight = exp(-0.5f * (distanceOct / sigmaOct).pow(2))
            sum += point.gainMb * weight
            weightSum += weight
        }
        val gainMb = if (weightSum <= 0.0001f) nearest else sum / weightSum
        return (gainMb / 100f).coerceIn(-12f, 12f)
    }

    private fun bandContributionDb(point: EqPoint, targetHz: Float): Float {
        val f0 = point.frequencyHz.toFloat().coerceIn(20f, 20_000f)
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

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
