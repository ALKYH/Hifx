package com.example.hifx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.google.android.material.color.MaterialColors

class LyricMaskTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var sampledProgress: Float = 0f
    private var progressRatePerSec: Float = 0f
    private var sampleUptimeMs: Long = 0L
    private var renderedProgress: Float = 0f
    private var highlightColor: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private var progressFrameRunning = false
    private var splitTailToNewLineEnabled = true
    private val glowRadiusPx: Float get() = textSize * 0.34f
    private val glowRadiusOuterPx: Float get() = textSize * 0.62f
    private val progressFrameRunner = object : Runnable {
        override fun run() {
            if (!progressFrameRunning) return
            renderedProgress = resolveProjectedProgress(SystemClock.uptimeMillis())
            postInvalidateOnAnimation()

            val shouldContinue = progressRatePerSec > 0f && renderedProgress < 0.9995f
            if (!shouldContinue) {
                progressFrameRunning = false
                return
            }
            postOnAnimation(this)
        }
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        val displayText = if (splitTailToNewLineEnabled) formatForDisplay(text) else text
        super.setText(displayText, type)
    }

    fun setSplitTailToNewLineEnabled(enabled: Boolean) {
        splitTailToNewLineEnabled = enabled
    }

    private fun formatForDisplay(source: CharSequence?): CharSequence? {
        if (source.isNullOrEmpty()) return source
        val raw = source.toString()
        if (raw.contains('\n')) return source
        val trimmed = raw.trimEnd()
        if (trimmed.isEmpty()) return source
        val lastSpace = trimmed.lastIndexOfAny(charArrayOf(' ', '\u3000'))
        if (lastSpace <= 0 || lastSpace >= trimmed.lastIndex) return source
        val head = trimmed.substring(0, lastSpace).trimEnd()
        val tail = trimmed.substring(lastSpace + 1).trimStart()
        if (head.isEmpty() || tail.isEmpty()) return source
        return "$head\n$tail"
    }

    fun setLyricProgress(progress: Float) {
        setLyricProgress(progress = progress, ratePerSec = 0f)
    }

    fun setLyricProgress(progress: Float, ratePerSec: Float) {
        val normalized = progress.coerceIn(0f, 1f)
        val normalizedRate = ratePerSec.coerceAtLeast(0f)
        val now = SystemClock.uptimeMillis()
        val previousSample = sampledProgress

        sampledProgress = normalized
        progressRatePerSec = normalizedRate
        sampleUptimeMs = now

        if (!isLaidOut) {
            renderedProgress = normalized
            progressFrameRunning = false
            invalidate()
            return
        }

        val isReset = normalized + 0.12f < previousSample || normalized <= 0.001f
        if (isReset) {
            renderedProgress = normalized
            if (normalizedRate > 0f && normalized < 0.9995f) {
                ensureFrameRunner()
            } else {
                progressFrameRunning = false
            }
            postInvalidateOnAnimation()
            return
        }

        if (normalizedRate <= 0f) {
            renderedProgress = normalized
            progressFrameRunning = false
            postInvalidateOnAnimation()
            return
        }

        val projected = resolveProjectedProgress(now)
        if (projected + 0.02f < renderedProgress || projected > renderedProgress) {
            renderedProgress = projected
        }

        ensureFrameRunner()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val contentWidth = width - paddingLeft - paddingRight
        if (contentWidth <= 0) {
            super.onDraw(canvas)
            return
        }

        val baseColor = applyAlpha(currentTextColor, 0.55f)
        val originalColor = paint.color

        paint.color = baseColor
        super.onDraw(canvas)

        if (renderedProgress > 0f) {
            val clipRight = (paddingLeft + contentWidth * renderedProgress).toInt().coerceAtLeast(paddingLeft)
            val glowSave = canvas.save()
            canvas.clipRect(paddingLeft, 0, clipRight, height)
            paint.color = blendColor(highlightColor, Color.WHITE, 0.26f)
            paint.setShadowLayer(glowRadiusOuterPx, 0f, 0f, applyAlpha(highlightColor, 0.52f))
            super.onDraw(canvas)
            paint.clearShadowLayer()
            canvas.restoreToCount(glowSave)

            val crispSave = canvas.save()
            canvas.clipRect(paddingLeft, 0, clipRight, height)
            paint.color = blendColor(highlightColor, Color.WHITE, 0.2f)
            paint.setShadowLayer(glowRadiusPx, 0f, 0f, applyAlpha(highlightColor, 0.72f))
            super.onDraw(canvas)
            paint.clearShadowLayer()
            canvas.restoreToCount(crispSave)
        }

        paint.clearShadowLayer()
        paint.color = originalColor
    }

    private fun applyAlpha(color: Int, alphaFraction: Float): Int {
        val a = ((color ushr 24) and 0xFF)
        val adjusted = (a * alphaFraction).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (adjusted shl 24)
    }

    private fun blendColor(from: Int, to: Int, ratio: Float): Int {
        val t = ratio.coerceIn(0f, 1f)
        val inv = 1f - t
        val a = ((Color.alpha(from) * inv) + (Color.alpha(to) * t)).toInt().coerceIn(0, 255)
        val r = ((Color.red(from) * inv) + (Color.red(to) * t)).toInt().coerceIn(0, 255)
        val g = ((Color.green(from) * inv) + (Color.green(to) * t)).toInt().coerceIn(0, 255)
        val b = ((Color.blue(from) * inv) + (Color.blue(to) * t)).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    private fun resolveProjectedProgress(nowUptimeMs: Long): Float {
        val elapsedSec = ((nowUptimeMs - sampleUptimeMs).coerceAtLeast(0L)).toFloat() / 1000f
        return (sampledProgress + progressRatePerSec * elapsedSec).coerceIn(0f, 1f)
    }

    private fun ensureFrameRunner() {
        if (progressFrameRunning) return
        progressFrameRunning = true
        postOnAnimation(progressFrameRunner)
    }

    override fun onDetachedFromWindow() {
        progressFrameRunning = false
        super.onDetachedFromWindow()
    }
}
