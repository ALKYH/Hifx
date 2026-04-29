package com.alky.hifx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min

class AlphabetIndexView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var values: List<String> = DEFAULT_LETTERS
    private var displayLabels: List<String> = DEFAULT_LETTERS
    private var selectedIndex = -1
    private var touching = false

    private val normalPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
    }

    private val selectedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
        isFakeBoldText = true
    }

    var onLetterTouch: ((letter: String, touching: Boolean) -> Unit)? = null

    init {
        updateThemeColors()
    }

    fun setLetters(values: List<String>) {
        setEntries(values, values)
    }

    fun setEntries(values: List<String>, displayLabels: List<String>) {
        val actualValues = values.ifEmpty { DEFAULT_LETTERS }
        this.values = actualValues
        this.displayLabels = if (displayLabels.size == actualValues.size) displayLabels else actualValues
        selectedIndex = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return

        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val rawHeight = max(1f, bottom - top)
        val drawHeight = rawHeight * 0.9f
        val drawTop = top + (rawHeight - drawHeight) * 0.5f
        val cellHeight = drawHeight / values.size
        val centerX = width * 0.5f

        displayLabels.forEachIndexed { index, label ->
            val paint = if (touching && index == selectedIndex) selectedPaint else normalPaint
            val baseline = drawTop + cellHeight * index + cellHeight * 0.5f - (paint.descent() + paint.ascent()) * 0.5f
            canvas.drawText(label, centerX, baseline, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || values.isEmpty()) {
            return super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touching = true
                updateSelection(event.y, true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateSelection(event.y, true)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                updateSelection(event.y, false)
                touching = false
                selectedIndex = -1
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateSelection(y: Float, active: Boolean) {
        val index = yToIndex(y)
        if (index != selectedIndex || !active) {
            selectedIndex = index
            invalidate()
        }
        val letter = values.getOrNull(index) ?: return
        onLetterTouch?.invoke(letter, active)
    }

    private fun yToIndex(y: Float): Int {
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val clampedY = y.coerceIn(top, bottom)
        val progress = ((clampedY - top) / max(1f, bottom - top)).coerceIn(0f, 1f)
        val raw = (progress * values.size).toInt()
        return min(values.lastIndex, max(0, raw))
    }

    private fun updateThemeColors() {
        normalPaint.color = MaterialColors.getColor(this, MaterialR.attr.colorOnSurfaceVariant)
        selectedPaint.color = MaterialColors.getColor(this, MaterialR.attr.colorPrimary)
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    }

    companion object {
        val DEFAULT_LETTERS: List<String> = buildList {
            for (code in 'A'.code..'Z'.code) {
                add(code.toChar().toString())
            }
            add("#")
        }
    }
}
