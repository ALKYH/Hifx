package com.example.hifx.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.os.SystemClock
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
    private var lineIsCurrent = false
    private var lineDistance = Int.MAX_VALUE
    private var lyricsExpanded = false
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

    fun setLyricFocusState(isCurrent: Boolean, distance: Int, expanded: Boolean) {
        lineIsCurrent = isCurrent
        lineDistance = distance.coerceAtLeast(0)
        lyricsExpanded = expanded
        // Keep the TextView container static; per-glyph scaling is handled in draw passes.
        scaleX = 1f
        scaleY = 1f
        translationY = 0f
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
        val contentWidth = width - totalPaddingLeft - totalPaddingRight
        val contentHeight = height - totalPaddingTop - totalPaddingBottom
        if (contentWidth <= 0 || contentHeight <= 0) {
            super.onDraw(canvas)
            return
        }
        val textLayout = layout ?: run {
            super.onDraw(canvas)
            return
        }
        val content = text ?: run {
            super.onDraw(canvas)
            return
        }
        if (content.isEmpty()) {
            super.onDraw(canvas)
            return
        }

        val baseColor = applyAlpha(currentTextColor, if (lineIsCurrent) 0.62f else 0.54f)
        val originalAlpha = paint.alpha
        val originalColor = paint.color

        val build = buildRenderUnits(textLayout, content)
        if (build.unitCount <= 0 || build.fragments.isEmpty()) {
            paint.color = baseColor
            paint.alpha = Color.alpha(baseColor)
            super.onDraw(canvas)
        } else {
            val progress = resolveNonLinearScanProgress(renderedProgress)
            val frontier = (progress * build.unitCount.toFloat()).coerceIn(0f, build.unitCount.toFloat())
            drawBaseLayerWithoutSwept(canvas, content, build, frontier, baseColor)
            if (frontier > 0f) {
                drawGlyphProgressOverlay(canvas, content, build, frontier)
            }
        }

        paint.alpha = originalAlpha
        paint.color = originalColor
        paint.clearShadowLayer()
    }

    private data class UnitSpan(
        val start: Int,
        val end: Int
    )

    private data class RenderUnitFragment(
        val start: Int,
        val end: Int,
        val unitIndex: Int,
        val lineTop: Float,
        val lineBottom: Float,
        val baseline: Float,
        val left: Float,
        val right: Float,
        val rtl: Boolean
    )

    private data class RenderUnitBuild(
        val fragments: List<RenderUnitFragment>,
        val unitCount: Int
    )

    private fun drawBaseLayerWithoutSwept(
        canvas: Canvas,
        content: CharSequence,
        build: RenderUnitBuild,
        frontier: Float,
        baseColor: Int
    ) {
        val save = canvas.save()
        canvas.clipRect(
            totalPaddingLeft,
            totalPaddingTop,
            width - totalPaddingRight,
            height - totalPaddingBottom
        )
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())

        paint.color = baseColor
        paint.alpha = Color.alpha(baseColor)
        paint.clearShadowLayer()

        for (fragment in build.fragments) {
            val unitProgressRaw = frontier - fragment.unitIndex.toFloat()
            val drawX = if (fragment.rtl) fragment.right else fragment.left
            when {
                unitProgressRaw <= 0f -> {
                    canvas.drawText(content, fragment.start, fragment.end, drawX, fragment.baseline, paint)
                }

                unitProgressRaw < 1f -> {
                    val reveal = unitProgressRaw.coerceIn(0f, 1f)
                    val fragSave = canvas.save()
                    val width = (fragment.right - fragment.left).coerceAtLeast(0f)
                    if (width > 0f) {
                        if (fragment.rtl) {
                            val clipRight = fragment.right - width * reveal
                            canvas.clipRect(fragment.left, fragment.lineTop, clipRight, fragment.lineBottom)
                        } else {
                            val clipLeft = fragment.left + width * reveal
                            canvas.clipRect(clipLeft, fragment.lineTop, fragment.right, fragment.lineBottom)
                        }
                        canvas.drawText(content, fragment.start, fragment.end, drawX, fragment.baseline, paint)
                    }
                    canvas.restoreToCount(fragSave)
                }
            }
        }

        canvas.restoreToCount(save)
    }

    private fun drawGlyphProgressOverlay(
        canvas: Canvas,
        content: CharSequence,
        build: RenderUnitBuild,
        frontier: Float
    ) {
        if (frontier <= 0f) return

        val save = canvas.save()
        canvas.clipRect(
            totalPaddingLeft,
            totalPaddingTop,
            width - totalPaddingRight,
            height - totalPaddingBottom
        )
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())

        for (fragment in build.fragments) {
            val unitProgressRaw = frontier - fragment.unitIndex.toFloat()
            val reveal = unitProgressRaw.coerceIn(0f, 1f)
            if (reveal <= 0f) continue
            drawUnitFragmentOverlay(
                canvas = canvas,
                content = content,
                fragment = fragment,
                unitProgressRaw = unitProgressRaw,
                reveal = reveal
            )
        }

        canvas.restoreToCount(save)
    }

    private fun buildRenderUnits(textLayout: Layout, content: CharSequence): RenderUnitBuild {
        val logicalUnits = buildLogicalUnits(content)
        if (logicalUnits.isEmpty()) {
            return RenderUnitBuild(emptyList(), 0)
        }
        val fragments = ArrayList<RenderUnitFragment>(logicalUnits.size * 2)
        logicalUnits.forEachIndexed { unitIndex, unit ->
            var cursor = unit.start
            while (cursor < unit.end) {
                val line = textLayout.getLineForOffset(cursor)
                val lineEnd = textLayout.getLineEnd(line).coerceAtMost(unit.end)
                if (lineEnd <= cursor) break

                val startX = textLayout.getPrimaryHorizontal(cursor)
                val endX = textLayout.getPrimaryHorizontal(lineEnd)
                val left = min(startX, endX)
                val right = max(startX, endX)
                if ((right - left) > 0.001f) {
                    fragments += RenderUnitFragment(
                        start = cursor,
                        end = lineEnd,
                        unitIndex = unitIndex,
                        lineTop = textLayout.getLineTop(line).toFloat(),
                        lineBottom = textLayout.getLineBottom(line).toFloat(),
                        baseline = textLayout.getLineBaseline(line).toFloat(),
                        left = left,
                        right = right,
                        rtl = endX < startX
                    )
                }
                cursor = lineEnd
            }
        }
        return RenderUnitBuild(fragments = fragments, unitCount = logicalUnits.size)
    }

    private fun buildLogicalUnits(content: CharSequence): List<UnitSpan> {
        val units = mutableListOf<UnitSpan>()
        var offset = 0
        while (offset < content.length) {
            val codePoint = Character.codePointAt(content, offset)
            val step = Character.charCount(codePoint)
            if (codePoint == '\n'.code || codePoint == '\r'.code || Character.isWhitespace(codePoint)) {
                offset += step
                continue
            }
            units += UnitSpan(start = offset, end = offset + step)
            offset += step
        }
        return units
    }

    private fun drawUnitFragmentOverlay(
        canvas: Canvas,
        content: CharSequence,
        fragment: RenderUnitFragment,
        unitProgressRaw: Float,
        reveal: Float
    ) {
        val width = (fragment.right - fragment.left).coerceAtLeast(textSize * 0.2f)
        val centerX = (fragment.left + fragment.right) * 0.5f
        val centerY = (fragment.lineTop + fragment.lineBottom) * 0.5f

        val lineWeight = resolveLineWeight()
        val motion = resolveUnitMotion(unitProgressRaw, lineWeight)
        val scale = motion.scale
        val lift = motion.lift

        val revealEase = easeOutCubic(reveal)
        val blurAlpha = (0.03f + (1f - revealEase) * 0.08f + motion.energy * 0.1f).coerceIn(0f, 0.2f)
        val glowAlpha = (0.06f + motion.energy * 0.14f).coerceIn(0f, 0.26f)
        val solidAlpha = if (unitProgressRaw >= 1f) 1f else (0.22f + revealEase * 0.78f).coerceIn(0f, 1f)
        val blurRadius = textSize * (0.06f + motion.energy * if (lineIsCurrent) 0.2f else 0.13f)
        val glowRadius = textSize * (0.04f + motion.energy * if (lineIsCurrent) 0.12f else 0.08f)
        val clipInflate = textSize * (0.03f + motion.energy * 0.05f)

        drawGlyphPass(
            canvas = canvas,
            content = content,
            start = fragment.start,
            end = fragment.end,
            drawX = if (fragment.rtl) fragment.right else fragment.left,
            baseline = fragment.baseline,
            lineTop = fragment.lineTop,
            lineBottom = fragment.lineBottom,
            left = fragment.left,
            right = fragment.right,
            width = width,
            centerX = centerX,
            centerY = centerY,
            reveal = reveal,
            scale = scale,
            lift = lift,
            rtl = fragment.rtl,
            color = blendColor(highlightColor, Color.WHITE, 0.4f),
            alpha = blurAlpha,
            shadowRadiusPx = blurRadius,
            shadowColor = applyAlpha(blendColor(highlightColor, Color.WHITE, 0.46f), 0.62f),
            clipInflatePx = clipInflate
        )

        drawGlyphPass(
            canvas = canvas,
            content = content,
            start = fragment.start,
            end = fragment.end,
            drawX = if (fragment.rtl) fragment.right else fragment.left,
            baseline = fragment.baseline,
            lineTop = fragment.lineTop,
            lineBottom = fragment.lineBottom,
            left = fragment.left,
            right = fragment.right,
            width = width,
            centerX = centerX,
            centerY = centerY,
            reveal = reveal,
            scale = scale,
            lift = lift,
            rtl = fragment.rtl,
            color = blendColor(highlightColor, Color.WHITE, 0.23f),
            alpha = glowAlpha,
            shadowRadiusPx = glowRadius,
            shadowColor = applyAlpha(blendColor(highlightColor, Color.WHITE, 0.18f), 0.58f),
            clipInflatePx = clipInflate * 0.5f
        )

        drawGlyphPass(
            canvas = canvas,
            content = content,
            start = fragment.start,
            end = fragment.end,
            drawX = if (fragment.rtl) fragment.right else fragment.left,
            baseline = fragment.baseline,
            lineTop = fragment.lineTop,
            lineBottom = fragment.lineBottom,
            left = fragment.left,
            right = fragment.right,
            width = width,
            centerX = centerX,
            centerY = centerY,
            reveal = reveal,
            scale = scale,
            lift = lift,
            rtl = fragment.rtl,
            color = blendColor(highlightColor, Color.WHITE, 0.1f),
            alpha = solidAlpha,
            shadowRadiusPx = 0f,
            shadowColor = Color.TRANSPARENT,
            clipInflatePx = 0f
        )
    }

    private fun drawGlyphPass(
        canvas: Canvas,
        content: CharSequence,
        start: Int,
        end: Int,
        drawX: Float,
        baseline: Float,
        lineTop: Float,
        lineBottom: Float,
        left: Float,
        right: Float,
        width: Float,
        centerX: Float,
        centerY: Float,
        reveal: Float,
        scale: Float,
        lift: Float,
        rtl: Boolean,
        color: Int,
        alpha: Float,
        shadowRadiusPx: Float,
        shadowColor: Int,
        clipInflatePx: Float
    ) {
        if (alpha <= 0f || reveal <= 0f) return
        val save = canvas.save()

        val inflate = clipInflatePx.coerceAtLeast(0f)
        val clipTop = lineTop - inflate
        val clipBottom = lineBottom + inflate
        if (reveal < 0.999f) {
            if (rtl) {
                val clipLeft = right - width * reveal - inflate
                val clipRight = right + inflate
                canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
            } else {
                val clipLeft = left - inflate
                val clipRight = left + width * reveal + inflate
                canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
            }
        } else if (inflate > 0f) {
            canvas.clipRect(left - inflate, clipTop, right + inflate, clipBottom)
        }

        canvas.translate(0f, -lift)
        canvas.scale(scale, scale, centerX, centerY)

        paint.color = color
        paint.alpha = (255f * alpha).toInt().coerceIn(0, 255)
        if (shadowRadiusPx > 0f) {
            paint.setShadowLayer(shadowRadiusPx, 0f, 0f, shadowColor)
        } else {
            paint.clearShadowLayer()
        }
        canvas.drawText(content, start, end, drawX, baseline, paint)
        paint.clearShadowLayer()

        canvas.restoreToCount(save)
    }

    private fun resolveLineWeight(): Float {
        val expandedBoost = if (lyricsExpanded) 1f else 0.86f
        val distanceWeight = when (lineDistance) {
            0 -> 1f
            1 -> 0.74f
            2 -> 0.48f
            3 -> 0.28f
            else -> 0.12f
        }
        return (distanceWeight * expandedBoost).coerceIn(0.08f, 1f)
    }

    private data class UnitMotion(
        val scale: Float,
        val lift: Float,
        val energy: Float
    )

    private fun resolveUnitMotion(unitProgressRaw: Float, lineWeight: Float): UnitMotion {
        if (unitProgressRaw <= 0f) {
            return UnitMotion(scale = 1f, lift = 0f, energy = 0f)
        }
        val finalScale = (1f + (if (lineIsCurrent) 0.08f else 0.045f) * lineWeight).coerceIn(1f, 1.14f)
        val finalLift = (textSize * (if (lineIsCurrent) 0.042f else 0.022f) * lineWeight).coerceIn(0f, textSize * 0.14f)
        if (unitProgressRaw >= 1f) {
            return UnitMotion(scale = finalScale, lift = finalLift, energy = 0.82f + 0.18f * lineWeight)
        }

        val t = unitProgressRaw.coerceIn(0f, 1f)
        val eased = easeOutCubic(t)
        val pulse = sin((Math.PI.toFloat() * t)).coerceAtLeast(0f)
        val pulseScaleBoost = (if (lineIsCurrent) 0.042f else 0.024f) * lineWeight
        val pulseLiftBoost = textSize * (if (lineIsCurrent) 0.018f else 0.01f) * lineWeight
        val scale = (1f + (finalScale - 1f) * eased + pulse * pulseScaleBoost).coerceIn(1f, 1.18f)
        val lift = (finalLift * eased + pulse * pulseLiftBoost).coerceIn(0f, textSize * 0.16f)
        val energy = (0.36f + eased * 0.44f + pulse * 0.34f).coerceIn(0f, 1.2f)
        return UnitMotion(scale = scale, lift = lift, energy = energy)
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

    private fun resolveNonLinearScanProgress(progress: Float): Float {
        val linear = progress.coerceIn(0f, 1f)
        val eased = easeOutCubic(linear)
        return lerp(linear, eased, 0.42f).coerceIn(0f, 1f)
    }

    private fun lerp(start: Float, end: Float, t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return start + (end - start) * clamped
    }

    private fun easeOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val inv = 1f - x
        return 1f - inv * inv * inv
    }

    private fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
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
