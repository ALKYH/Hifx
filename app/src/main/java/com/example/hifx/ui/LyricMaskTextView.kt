package com.example.hifx.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.google.android.material.color.MaterialColors

class LyricMaskTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var lyricProgress: Float = 0f
    private var highlightColor: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)

    fun setLyricProgress(progress: Float) {
        val normalized = progress.coerceIn(0f, 1f)
        if (lyricProgress == normalized) return
        lyricProgress = normalized
        invalidate()
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

        if (lyricProgress > 0f) {
            val clipRight = (paddingLeft + contentWidth * lyricProgress).toInt().coerceAtLeast(paddingLeft)
            val save = canvas.save()
            canvas.clipRect(paddingLeft, 0, clipRight, height)
            paint.color = highlightColor
            super.onDraw(canvas)
            canvas.restoreToCount(save)
        }

        paint.color = originalColor
    }

    private fun applyAlpha(color: Int, alphaFraction: Float): Int {
        val a = ((color ushr 24) and 0xFF)
        val adjusted = (a * alphaFraction).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (adjusted shl 24)
    }
}

