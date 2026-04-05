package com.example.hifx.ui

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.hifx.audio.PlaybackUiState

object PlayerTransitionState {
    @Volatile
    var miniPlayerRectOnScreen: Rect? = null

    @Volatile
    private var mainUiWarmupRequested: Boolean = false

    @Volatile
    var onMainUiWarmupRequested: (() -> Unit)? = null

    data class CollapseOverlayPayload(
        val snapshot: Bitmap,
        val sourceRectOnScreen: Rect,
        val targetRectOnScreen: Rect
    )

    @Volatile
    private var collapseOverlayPayload: CollapseOverlayPayload? = null

    @Volatile
    private var prewarmedPlaybackState: PlaybackUiState? = null

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
}
