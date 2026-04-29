package com.alky.hifx.ui

import android.graphics.Bitmap
import android.graphics.Rect
import com.alky.hifx.audio.PlaybackUiState

object PlayerTransitionState {
    @Volatile
    var miniPlayerRectOnScreen: Rect? = null

    @Volatile
    var miniPlayerCoverRectOnScreen: Rect? = null

    @Volatile
    var miniPlayerTitleRectOnScreen: Rect? = null

    @Volatile
    var miniPlayerSubtitleRectOnScreen: Rect? = null

    @Volatile
    private var mainUiWarmupRequested: Boolean = false

    @Volatile
    var onMainUiWarmupRequested: (() -> Unit)? = null

    data class CollapseOverlayPayload(
        val snapshot: Bitmap,
        val sourceRectOnScreen: Rect,
        val targetRectOnScreen: Rect,
        val startCornerRadiusPx: Float,
        val endCornerRadiusPx: Float
    )

    @Volatile
    private var collapseOverlayPayload: CollapseOverlayPayload? = null

    @Volatile
    private var prewarmedPlaybackState: PlaybackUiState? = null

    @Volatile
    private var backgroundSnapshot: Bitmap? = null

    @Volatile
    private var returningFromPlayer: Boolean = false

    fun requestMainUiWarmup() {
        mainUiWarmupRequested = true
        onMainUiWarmupRequested?.invoke()
    }

    fun consumeMainUiWarmupRequest(): Boolean {
        val requested = mainUiWarmupRequested
        mainUiWarmupRequested = false
        return requested
    }

    fun publishCollapseOverlay(payload: CollapseOverlayPayload) {
        collapseOverlayPayload?.snapshot?.recycle()
        collapseOverlayPayload = payload
    }

    fun consumeCollapseOverlay(): CollapseOverlayPayload? {
        val payload = collapseOverlayPayload
        collapseOverlayPayload = null
        return payload
    }

    fun updatePrewarmedPlaybackState(state: PlaybackUiState) {
        prewarmedPlaybackState = state
    }

    fun consumePrewarmedPlaybackState(): PlaybackUiState? {
        val state = prewarmedPlaybackState
        prewarmedPlaybackState = null
        return state
    }

    fun updateBackgroundSnapshot(snapshot: Bitmap?) {
        backgroundSnapshot?.takeIf { it !== snapshot }?.recycle()
        backgroundSnapshot = snapshot
    }

    fun consumeBackgroundSnapshot(): Bitmap? {
        val snapshot = backgroundSnapshot
        backgroundSnapshot = null
        return snapshot
    }

    fun markReturningFromPlayer() {
        returningFromPlayer = true
    }

    fun consumeReturningFromPlayer(): Boolean {
        val returning = returningFromPlayer
        returningFromPlayer = false
        return returning
    }
}
