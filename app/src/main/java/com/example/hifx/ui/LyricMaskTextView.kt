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
import kotlin.math.pow
import kotlin.math.sin

class LyricMaskTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private enum class ProgressSyncMode {
        NONE,
        BILINGUAL_SECTION
    }

    private var sampledProgress: Float = 0f
    private var progressRatePerSec: Float = 0f
    private var sampleUptimeMs: Long = 0L
    private var renderedProgress: Float = 0f
    private var highlightColor: Int = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private var progressFrameRunning = false
    private var lineIsCurrent = false
    private var lineDistance = Int.MAX_VALUE
    private var lyricsExpanded = false
    private var lyricGlowEnabled = true
    private var scanHeadEnabled = true
    private var glowIntensityFactor = 1f
    private var progressSyncMode = ProgressSyncMode.NONE
    private var bilingualBoundaryIndex = -1
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

    fun setLyricFocusState(isCurrent: Boolean, distance: Int, expanded: Boolean) {
        lineIsCurrent = isCurrent
        lineDistance = distance.coerceAtLeast(0)
        lyricsExpanded = expanded
        // Keep the TextView container static; per-glyph scaling is handled in draw passes.
        scaleX = 1f
        scaleY = 1f
        translationY = 0f
    }

    fun setProgressSections(originalText: String, translatedText: String?, bilingualEnabled: Boolean) {
        val hasTranslation = bilingualEnabled && !translatedText.isNullOrBlank()
        if (!hasTranslation || originalText.isBlank()) {
            progressSyncMode = ProgressSyncMode.NONE
            bilingualBoundaryIndex = -1
            return
        }
        progressSyncMode = ProgressSyncMode.BILINGUAL_SECTION
        bilingualBoundaryIndex = originalText.length
    }

    fun setLyricProgress(progress: Float) {
        setLyricProgress(progress = progress, ratePerSec = 0f)
    }

    fun setScanHeadEnabled(enabled: Boolean) {
        if (scanHeadEnabled == enabled) return
        scanHeadEnabled = enabled
        invalidate()
    }

    fun setLyricGlowEnabled(enabled: Boolean) {
        if (lyricGlowEnabled == enabled) return
        lyricGlowEnabled = enabled
        invalidate()
    }

    fun setGlowIntensityPercent(value: Int) {
        glowIntensityFactor = (value.coerceIn(0, 100) / 100f).coerceIn(0f, 1f)
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
            val twoLineSyncProgress = shouldUseTwoLineSynchronizedProgress(content)
            drawCurrentLineAura(
                canvas = canvas,
                textLayout = textLayout,
                content = content,
                progress = progress
            )
            drawBaseLayerWithoutSwept(
                canvas = canvas,
                content = content,
                build = build,
                progress = progress,
                baseColor = baseColor,
                twoLineSyncProgress = twoLineSyncProgress
            )
            if (progress > 0f) {
                drawGlyphProgressOverlay(
                    canvas = canvas,
                    content = content,
                    build = build,
                    progress = progress,
                    twoLineSyncProgress = twoLineSyncProgress
                )
            }
        }

        paint.alpha = originalAlpha
        paint.color = originalColor
        paint.clearShadowLayer()
    }

    private fun drawCurrentLineAura(
        canvas: Canvas,
        textLayout: Layout,
        content: CharSequence,
        progress: Float
    ) {
        if (!lyricGlowEnabled) return
        if (!scanHeadEnabled) return
        if (!lineIsCurrent || progress <= 0f) return
        val save = canvas.save()
        canvas.clipRect(
            totalPaddingLeft,
            totalPaddingTop,
            width - totalPaddingRight,
            height - totalPaddingBottom
        )
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())

        val eased = easeOutCubic(progress.coerceIn(0f, 1f))
        val intensityAlpha = glowIntensityFactor.pow(0.86f)
        val intensityBoost = (0.72f + intensityAlpha * 0.7f).coerceIn(0.72f, 1.42f)
        val auraAlphaBase = ((0.06f + eased * 0.18f) * intensityAlpha).coerceIn(0f, 0.28f)
        val auraRadiusBase = textSize * (0.11f + eased * 0.05f) * intensityBoost
        val auraColor = blendColor(highlightColor, Color.WHITE, 0.34f)
        val contentWidth = (width - totalPaddingLeft - totalPaddingRight).toFloat().coerceAtLeast(1f)
        val contentHeight = (height - totalPaddingTop - totalPaddingBottom).toFloat().coerceAtLeast(1f)

        paint.color = auraColor

        for (line in 0 until textLayout.lineCount) {
            val start = textLayout.getLineStart(line)
            val end = textLayout.getLineEnd(line).coerceAtMost(content.length)
            if (end <= start) continue
            val baseline = textLayout.getLineBaseline(line).toFloat()
            val drawX = textLayout.getLineLeft(line)
            val lineTop = textLayout.getLineTop(line).toFloat()
            val lineBottom = textLayout.getLineBottom(line).toFloat()
            val lineLeft = textLayout.getLineLeft(line)
            val lineRight = textLayout.getLineRight(line)
            val nearestEdge = min(
                min(lineLeft, contentWidth - lineRight),
                min(lineTop, contentHeight - lineBottom)
            ).coerceAtLeast(0f)
            val edgeFactor = ((nearestEdge / (textSize * 0.92f).coerceAtLeast(1f)).coerceIn(0f, 1f)).pow(1.55f)
            if (edgeFactor <= 0.02f) continue
            val auraAlpha = auraAlphaBase * edgeFactor
            val auraRadius = (auraRadiusBase * (0.56f + edgeFactor * 0.44f)).coerceAtLeast(0.45f)
            paint.alpha = (255f * auraAlpha).toInt().coerceIn(0, 255)
            paint.setShadowLayer(
                auraRadius,
                0f,
                0f,
                applyAlpha(
                    blendColor(highlightColor, Color.WHITE, 0.42f),
                    ((0.2f + eased * 0.34f) * edgeFactor * intensityAlpha).coerceIn(0f, 0.68f)
                )
            )
            canvas.drawText(content, start, end, drawX, baseline, paint)
        }
        paint.clearShadowLayer()
        canvas.restoreToCount(save)
    }

    private data class UnitSpan(
        val start: Int,
        val end: Int,
        val section: Int
    )

    private data class RenderUnitFragment(
        val start: Int,
        val end: Int,
        val unitIndex: Int,
        val unitIndexInLine: Int,
        val unitIndexInSection: Int,
        val lineUnitCount: Int,
        val sectionUnitCount: Int,
        val lineTop: Float,
        val lineBottom: Float,
        val baseline: Float,
        val left: Float,
        val right: Float,
        val clipPadding: Float,
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
        progress: Float,
        baseColor: Int,
        twoLineSyncProgress: Boolean
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
            val unitProgressRaw = resolveUnitProgressRaw(
                fragment = fragment,
                progress = progress,
                unitCount = build.unitCount,
                twoLineSyncProgress = twoLineSyncProgress
            )
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
        progress: Float,
        twoLineSyncProgress: Boolean
    ) {
        if (progress <= 0f) return

        val save = canvas.save()
        canvas.clipRect(
            totalPaddingLeft,
            totalPaddingTop,
            width - totalPaddingRight,
            height - totalPaddingBottom
        )
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())

        for (fragment in build.fragments) {
            val unitProgressRaw = resolveUnitProgressRaw(
                fragment = fragment,
                progress = progress,
                unitCount = build.unitCount,
                twoLineSyncProgress = twoLineSyncProgress
            )
            val reveal = unitProgressRaw.coerceIn(0f, 1f)
            if (reveal <= 0f) continue
            drawUnitFragmentOverlay(
                canvas = canvas,
                content = content,
                fragment = fragment,
                unitProgressRaw = unitProgressRaw,
                reveal = reveal,
                scanHeadEnabled = true
            )
        }

        canvas.restoreToCount(save)
    }

    private fun buildRenderUnits(textLayout: Layout, content: CharSequence): RenderUnitBuild {
        val logicalUnits = buildLogicalUnits(content)
        if (logicalUnits.isEmpty()) {
            return RenderUnitBuild(emptyList(), 0)
        }
        val unitLineIndexes = IntArray(logicalUnits.size) { 0 }
        val lineUnitCounts = mutableMapOf<Int, Int>()
        logicalUnits.forEachIndexed { index, unit ->
            val line = textLayout.getLineForOffset(unit.start)
            unitLineIndexes[index] = line
            lineUnitCounts[line] = (lineUnitCounts[line] ?: 0) + 1
        }
        val lineRunningIndexes = mutableMapOf<Int, Int>()
        val unitIndexInLine = IntArray(logicalUnits.size) { 0 }
        val sectionRunningIndexes = mutableMapOf<Int, Int>()
        val unitIndexInSection = IntArray(logicalUnits.size) { 0 }
        val sectionUnitCounts = mutableMapOf<Int, Int>()
        logicalUnits.indices.forEach { index ->
            val line = unitLineIndexes[index]
            val running = lineRunningIndexes[line] ?: 0
            unitIndexInLine[index] = running
            lineRunningIndexes[line] = running + 1

            val section = logicalUnits[index].section
            val sectionRunning = sectionRunningIndexes[section] ?: 0
            unitIndexInSection[index] = sectionRunning
            sectionRunningIndexes[section] = sectionRunning + 1
            sectionUnitCounts[section] = (sectionUnitCounts[section] ?: 0) + 1
        }
        val fragments = ArrayList<RenderUnitFragment>(logicalUnits.size * 2)
        logicalUnits.forEachIndexed { unitIndex, unit ->
            var cursor = unit.start
            while (cursor < unit.end) {
                val line = textLayout.getLineForOffset(cursor)
                val lineEnd = textLayout.getLineEnd(line).coerceAtMost(unit.end)
                if (lineEnd <= cursor) break

                val measuredAdvance = paint.measureText(content, cursor, lineEnd).coerceAtLeast(0f)
                val startX = textLayout.getPrimaryHorizontal(cursor)
                val rtl = textLayout.isRtlCharAt(cursor)
                val lineLeft = min(textLayout.getLineLeft(line), textLayout.getLineRight(line))
                val lineRight = max(textLayout.getLineLeft(line), textLayout.getLineRight(line))
                val left = if (rtl) {
                    (startX - measuredAdvance).coerceIn(lineLeft, lineRight)
                } else {
                    startX.coerceIn(lineLeft, lineRight)
                }
                val right = if (rtl) {
                    startX.coerceIn(left, lineRight)
                } else {
                    (startX + measuredAdvance).coerceIn(left, lineRight)
                }
                if ((right - left) > 0.001f) {
                    val clipPadding = (textSize * 0.055f).coerceIn(0.75f, 3.5f)
                    fragments += RenderUnitFragment(
                        start = cursor,
                        end = lineEnd,
                        unitIndex = unitIndex,
                        unitIndexInLine = unitIndexInLine[unitIndex],
                        unitIndexInSection = unitIndexInSection[unitIndex],
                        lineUnitCount = lineUnitCounts[line] ?: 1,
                        sectionUnitCount = sectionUnitCounts[unit.section] ?: 1,
                        lineTop = textLayout.getLineTop(line).toFloat(),
                        lineBottom = textLayout.getLineBottom(line).toFloat(),
                        baseline = textLayout.getLineBaseline(line).toFloat(),
                        left = left,
                        right = right,
                        clipPadding = clipPadding,
                        rtl = rtl
                    )
                }
                cursor = lineEnd
            }
        }
        return RenderUnitBuild(fragments = fragments, unitCount = logicalUnits.size)
    }

    private fun buildLogicalUnits(content: CharSequence): List<UnitSpan> {
        val units = mutableListOf<UnitSpan>()
        val boundary = bilingualBoundaryIndex
        var offset = 0
        while (offset < content.length) {
            val codePoint = Character.codePointAt(content, offset)
            val step = Character.charCount(codePoint)
            if (codePoint == '\n'.code || codePoint == '\r'.code || Character.isWhitespace(codePoint)) {
                offset += step
                continue
            }
            val section = if (
                progressSyncMode == ProgressSyncMode.BILINGUAL_SECTION &&
                boundary >= 0 &&
                offset > boundary
            ) {
                1
            } else {
                0
            }
            units += UnitSpan(start = offset, end = offset + step, section = section)
            offset += step
        }
        return units
    }

    private fun resolveUnitProgressRaw(
        fragment: RenderUnitFragment,
        progress: Float,
        unitCount: Int,
        twoLineSyncProgress: Boolean
    ): Float {
        val clamped = progress.coerceIn(0f, 1f)
        return if (progressSyncMode == ProgressSyncMode.BILINGUAL_SECTION) {
            val frontier = clamped * fragment.sectionUnitCount.coerceAtLeast(1).toFloat()
            frontier - fragment.unitIndexInSection.toFloat()
        } else if (twoLineSyncProgress) {
            val frontier = clamped * fragment.lineUnitCount.coerceAtLeast(1).toFloat()
            frontier - fragment.unitIndexInLine.toFloat()
        } else {
            val frontier = clamped * unitCount.coerceAtLeast(1).toFloat()
            frontier - fragment.unitIndex.toFloat()
        }
    }

    private fun shouldUseTwoLineSynchronizedProgress(content: CharSequence): Boolean {
        if (progressSyncMode == ProgressSyncMode.BILINGUAL_SECTION) {
            return false
        }
        // Only sync when text is exactly "original + '\n' + translation".
        var newlineCount = 0
        content.forEach { ch ->
            if (ch == '\n') newlineCount++
        }
        return newlineCount == 1
    }

    private fun drawUnitFragmentOverlay(
        canvas: Canvas,
        content: CharSequence,
        fragment: RenderUnitFragment,
        unitProgressRaw: Float,
        reveal: Float,
        scanHeadEnabled: Boolean
    ) {
        val width = (fragment.right - fragment.left).coerceAtLeast(textSize * 0.2f)
        val centerX = (fragment.left + fragment.right) * 0.5f
        val centerY = (fragment.lineTop + fragment.lineBottom) * 0.5f

        val lineWeight = resolveLineWeight()
        val motion = resolveUnitMotion(unitProgressRaw, lineWeight)
        val scale = motion.scale
        val lift = motion.lift
        val disableBlurForCurrent = false
        val passed = (unitProgressRaw - 1f).coerceAtLeast(0f)
        val intensityAlpha = glowIntensityFactor.pow(0.86f)
        val intensityBoost = (0.72f + intensityAlpha * 0.7f).coerceIn(0.72f, 1.42f)
        val rawDecay = (1f / (1f + passed * if (lineIsCurrent) 3.8f else 2.9f)).pow(1.85f)
        // Keep a tiny afterglow for scanned characters.
        val afterglowFloor = if (lineIsCurrent) 0.1f else 0.06f
        val decay = if (passed > 0f) {
            afterglowFloor + (1f - afterglowFloor) * rawDecay
        } else {
            rawDecay
        }
        val scannedTailAlpha = if (passed > 0f) {
            (((0.016f + 0.042f * rawDecay) * if (lineIsCurrent) 1f else 0.82f) * intensityAlpha)
                .coerceIn(0f, 0.075f)
        } else {
            0f
        }
        val scannedTailRadius = if (passed > 0f) {
            textSize * (0.032f + 0.03f * rawDecay) * intensityBoost
        } else {
            0f
        }
        val scannedTailInflate = textSize * 0.014f * (0.9f + 0.45f * intensityAlpha)

        val revealEase = easeOutCubic(reveal)
        val blurAlpha = if (!lyricGlowEnabled || disableBlurForCurrent) 0f else
            ((0.03f + (1f - revealEase) * 0.08f + motion.energy * 0.1f) * decay * intensityAlpha).coerceIn(0f, 0.24f)
        val glowAlpha = if (!lyricGlowEnabled) 0f else if (disableBlurForCurrent)
            ((0.08f + motion.energy * 0.08f) * decay * intensityAlpha).coerceIn(0f, 0.24f)
        else
            ((0.08f + motion.energy * if (lineIsCurrent) 0.22f else 0.14f) * decay * intensityAlpha).coerceIn(0f, 0.4f)
        val solidAlpha = if (unitProgressRaw >= 1f) 1f else (0.22f + revealEase * 0.78f).coerceIn(0f, 1f)
        val blurRadius = if (disableBlurForCurrent) 0f else
            textSize * (0.08f + motion.energy * if (lineIsCurrent) 0.26f else 0.13f) * decay * intensityBoost
        val glowRadius = if (disableBlurForCurrent) 0f else
            textSize * (0.06f + motion.energy * if (lineIsCurrent) 0.18f else 0.08f) * decay * intensityBoost
        val clipInflate = textSize * (0.02f + motion.energy * 0.04f) * (0.62f + 0.38f * decay) * (0.9f + 0.45f * intensityAlpha)

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
            shadowColor = applyAlpha(blendColor(highlightColor, Color.WHITE, 0.46f), (0.62f * intensityAlpha).coerceIn(0f, 0.75f)),
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
            shadowColor = applyAlpha(blendColor(highlightColor, Color.WHITE, 0.18f), (0.58f * intensityAlpha).coerceIn(0f, 0.72f)),
            clipInflatePx = clipInflate * 0.5f
        )

        if (lyricGlowEnabled && scanHeadEnabled && scannedTailAlpha > 0f) {
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
                reveal = 1f,
                scale = scale,
                lift = lift,
                rtl = fragment.rtl,
                color = blendColor(highlightColor, Color.WHITE, 0.08f),
                alpha = scannedTailAlpha,
                shadowRadiusPx = scannedTailRadius,
                shadowColor = applyAlpha(blendColor(highlightColor, Color.WHITE, 0.24f), (0.4f * intensityAlpha).coerceIn(0f, 0.52f)),
                clipInflatePx = scannedTailInflate
            )
        }

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
        val safePadding = fragmentClipPadding(left, right)
        val clipTop = lineTop - inflate
        val clipBottom = lineBottom + inflate
        if (reveal < 0.999f) {
            if (rtl) {
                val clipLeft = right - width * reveal - inflate - safePadding
                val clipRight = right + inflate + safePadding
                canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
            } else {
                val clipLeft = left - inflate - safePadding
                val clipRight = left + width * reveal + inflate + safePadding
                canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
            }
        } else if (inflate > 0f) {
            canvas.clipRect(left - inflate - safePadding, clipTop, right + inflate + safePadding, clipBottom)
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

    private fun fragmentClipPadding(left: Float, right: Float): Float {
        val width = (right - left).coerceAtLeast(0f)
        if (width <= 0f) return 0f
        return min((textSize * 0.055f).coerceAtLeast(0.75f), width * 0.35f)
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
