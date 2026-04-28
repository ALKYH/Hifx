package com.example.hifx.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

class PagedHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    companion object {
        private const val PAGE_SWITCH_THRESHOLD_RATIO = 0.16f
    }

    var pageCount: Int = 0
    var currentPage: Int = 0
        private set
    var onPageChanged: ((Int) -> Unit)? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var isDraggingHorizontally = false

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                isDraggingHorizontally = false
                super.onInterceptTouchEvent(ev)
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!isDraggingHorizontally &&
                    abs(dx) > touchSlop &&
                    abs(dx) > abs(dy)
                ) {
                    isDraggingHorizontally = true
                    return true
                }
                return false
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isDraggingHorizontally = false
                return false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !isDraggingHorizontally) {
            downX = ev.x
            downY = ev.y
        }
        val handled = super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            isDraggingHorizontally = false
            snapToNearestPage(smooth = true)
        }
        return handled
    }

    fun snapToPage(page: Int, smooth: Boolean) {
        val clamped = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val targetX = clamped * width
        if (smooth) smoothScrollTo(targetX, 0) else scrollTo(targetX, 0)
        if (currentPage != clamped) {
            currentPage = clamped
            onPageChanged?.invoke(clamped)
        }
    }

    fun snapToNearestPage(smooth: Boolean) {
        if (width <= 0 || pageCount <= 0) return
        val pageOffset = scrollX.toFloat() / width - currentPage
        val targetPage = when {
            pageOffset >= PAGE_SWITCH_THRESHOLD_RATIO -> currentPage + 1
            pageOffset <= -PAGE_SWITCH_THRESHOLD_RATIO -> currentPage - 1
            else -> currentPage
        }
        snapToPage(targetPage, smooth)
    }
}
