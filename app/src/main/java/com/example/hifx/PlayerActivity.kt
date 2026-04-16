package com.example.hifx

import android.content.ContentUris
import android.content.res.ColorStateList
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.animation.LinearInterpolator
import android.util.TypedValue
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.addCallback
import androidx.core.animation.doOnEnd
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.example.hifx.audio.AudioEngine
import com.example.hifx.audio.PlaybackUiState
import com.example.hifx.audio.SettingsUiState
import com.example.hifx.databinding.ActivityPlayerBinding
import com.example.hifx.ui.PlayerTransitionState
import com.example.hifx.ui.LyricMaskTextView
import com.example.hifx.util.AppHaptics
import com.example.hifx.util.loadArtworkOrDefault
import com.example.hifx.util.toTimeString
import com.example.hifx.util.WaveformAnalyzer
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

class PlayerActivity : AppCompatActivity() {
    private enum class LyricsTranslationMode {
        ORIGINAL_ONLY,
        ORIGINAL_WITH_TRANSLATION
    }

    companion object {
        private const val TAG = "PlayerLyrics"
        private const val COLLAPSED_COVER_SIZE_DP = 104f * 2f / 3f
        private const val LYRICS_AUTO_RETURN_DELAY_MS = 4_000L
        private val LRC_BRACKET_TOKEN_REGEX = Regex("""\[(.*?)]""")
        private val LRC_METADATA_TOKEN_REGEX = Regex("""^[A-Za-z]{1,16}\s*[:\uFF1A].*$""")
        private val LRC_METADATA_LINE_REGEX = Regex("""^\s*\[\s*[A-Za-z]{1,16}\s*[:\uFF1A][^\]]*]\s*$""")
        private val LRC_METADATA_INLINE_REGEX = Regex("""\[\s*[A-Za-z]{1,16}\s*[:\uFF1A][^\]]*]""")
        private val LRC_OFFSET_REGEX =
            Regex("""\[\s*offset\s*[:\uFF1A]\s*([+-]?\d{1,9})\s*]""", RegexOption.IGNORE_CASE)
        private val LRC_TIMESTAMP_TOKEN_REGEX =
            Regex("""^(?:(\d{1,2}):)?(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?$""")
        private const val UTF16_NULL_RATIO_THRESHOLD = 0.2f
    }

    private lateinit var binding: ActivityPlayerBinding
    private var userSeeking = false
    private var lastArtworkKey: String? = null
    private var lastMediaKey: String? = null
    private var lastLyricMediaKey: String? = null
    private var waveformJob: Job? = null
    private var lyricJob: Job? = null
    private val waveformCache = mutableMapOf<String, FloatArray>()
    private val lyricCache = mutableMapOf<String, List<LyricLine>>()
    private var activeLyrics: List<LyricLine> = emptyList()
    private var latestDurationMs: Long = 0L
    private var statusBarInsetPx: Int = 0
    private var pendingSeekPositionMs: Long? = null
    private var collapsing = false
    private var entering = false
    private var hasRenderedStateAtLeastOnce = false
    private var pendingEnterMaskRelease = false
    private var activeEnterMaskOverlay: EnterMaskOverlay? = null
    private var enterMaskFadeOutInProgress = false
    private var latestArtworkRequestKey: String? = null
    private var enterMaskReadyAtUptimeMs: Long = 0L
    private var trackSwipeAnimating = false
    private var lyricsExpanded = false
    private var lyricsPanelAnimator: ValueAnimator? = null
    private lateinit var lyricsLayoutManager: LinearLayoutManager
    private lateinit var lyricsAdapter: LyricsListAdapter
    private var lyricsAutoFollowEnabled = true
    private var lyricsAutoReturnJob: Job? = null
    private var lyricsPanelVisibleBySetting = true
    private var lyricsTranslationMode = LyricsTranslationMode.ORIGINAL_ONLY
    private var lyricsHasTranslation = false
    private var backgroundBlurStrength = 80
    private var backgroundOpacityPercent = 52
    private var backgroundDynamicEnabled = true
    private var backgroundScrollAnimator: ValueAnimator? = null
    private val backgroundScrollMatrix = Matrix()
    private var lyricsFontSizeSp = 18
    private var lyricsGlowIntensityPercent = 100
    private var lyricsBoldEnabled = false
    private var currentLyricIndex: Int = -1
    private var lastAutoFollowedLyricIndex: Int = -1
    private var lyricInertiaVelocityPxPerSec = 0f
    private var lyricInertiaPhase = 0f
    private var lyricLastScrollUptimeMs = 0L
    private var lyricInertiaAnimator: ValueAnimator? = null
    private var lyricSettleAnimator: ValueAnimator? = null
    private var lyricInertiaLastFrameUptimeMs = 0L
    private var lyricSettleLastFrameUptimeMs = 0L
    private var lyricsTranslationSwitchInProgress = false
    private var lyricsTranslationSwitchJob: Job? = null
    private val lyricLineOffsetsPx = mutableMapOf<Int, Float>()
    private val lyricLineVelocitiesPxPerSec = mutableMapOf<Int, Float>()
    private var albumDefaultHeightPx: Int = 0
    private var titleTextSizeSpDefault = 0f
    private var subtitleTextSizeSpDefault = 0f
    private var albumTextSizeSpDefault = 0f
    private val standardInterpolator = FastOutSlowInInterpolator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        binding.root.alpha = 0f
        binding.root.visibility = View.INVISIBLE
        setContentView(binding.root)

        setupImmersiveUi()
        setupControls()
        setupAlbumGestures()
        setupLyricsPanelExpansion()
        setupBackgroundEffects()
        applyPrewarmedStateIfAvailable()
        observePlaybackState()
        observeSettingsState()
        playEnterAnimation()
        onBackPressedDispatcher.addCallback(this) {
            animateCollapseToMiniPlayerAndFinish()
        }
    }

    private fun setupControls() {
        binding.buttonPlayPause.setOnClickListener {
            AppHaptics.click(it)
            AudioEngine.togglePlayPause()
        }
        binding.buttonPreviousTrack.setOnClickListener {
            AppHaptics.click(it)
            AudioEngine.skipToPreviousTrack()
        }
        binding.buttonNextTrack.setOnClickListener {
            AppHaptics.click(it)
            AudioEngine.skipToNextTrack()
        }
        binding.buttonShuffle.setOnClickListener {
            AppHaptics.click(it)
            AudioEngine.toggleShuffleEnabled()
        }
        binding.buttonRepeatMode.setOnClickListener {
            AppHaptics.click(it)
            AudioEngine.cycleRepeatMode()
        }
        binding.buttonSleepTimer.setOnClickListener {
            AppHaptics.click(it)
            showSleepTimerDialog()
        }
        binding.textTrackSubtitle.setOnClickListener {
            if (!binding.textTrackSubtitle.isEnabled) return@setOnClickListener
            val artistName = binding.textTrackSubtitle.text?.toString().orEmpty().trim()
            if (artistName.isNotBlank()) {
                AppHaptics.click(it)
                openArtistDetail(artistName)
            }
        }
        binding.textTrackAlbum.setOnClickListener {
            if (!binding.textTrackAlbum.isEnabled) return@setOnClickListener
            val albumName = binding.textTrackAlbum.text?.toString().orEmpty().trim()
            if (albumName.isNotBlank()) {
                AppHaptics.click(it)
                openAlbumDetail(albumName)
            }
        }
        binding.waveformPreviewView.onScrub = { ratio ->
            val duration = latestDurationMs.coerceAtLeast(1L)
            val target = (duration * ratio).toLong().coerceIn(0L, duration)
            userSeeking = true
            binding.progressSlider.value = target.toFloat()
            binding.textCurrentTime.text = target.toTimeString()
            updateWaveformPreviewTime(target, duration)
            binding.waveformPreviewView.setScrubRatio(ratio)
        }
        binding.waveformPreviewView.onScrubFinished = { ratio ->
            val duration = latestDurationMs.coerceAtLeast(1L)
            val target = (duration * ratio).toLong().coerceIn(0L, duration)
            pendingSeekPositionMs = target
            AudioEngine.seekTo(target)
            hideWaveformPreview()
            binding.progressSlider.animate().scaleY(1f).setInterpolator(standardInterpolator).setDuration(140L).start()
        }
        binding.progressSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.textCurrentTime.text = value.toLong().toTimeString()
                val duration = latestDurationMs.coerceAtLeast(1L)
                showWaveformPreview(value.toLong().coerceIn(0L, duration), duration)
            }
        }
        binding.progressSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                userSeeking = true
                pendingSeekPositionMs = null
                binding.progressSlider.animate()
                    .scaleY(1.85f)
                    .setInterpolator(standardInterpolator)
                    .setDuration(120L)
                    .start()
                showWaveformPreview(slider.value.toLong(), latestDurationMs.coerceAtLeast(1L))
            }

            override fun onStopTrackingTouch(slider: Slider) {
                val target = slider.value.toLong()
                pendingSeekPositionMs = target
                AudioEngine.seekTo(target)
                binding.progressSlider.animate()
                    .scaleY(1f)
                    .setInterpolator(standardInterpolator)
                    .setDuration(140L)
                    .start()
                hideWaveformPreview()
            }
        })
    }

    private fun observePlaybackState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AudioEngine.playbackState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun observeSettingsState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AudioEngine.settingsState.collect { state ->
                    renderSettings(state)
                }
            }
        }
    }

    private fun setupAlbumGestures() {
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val start = e1 ?: return false
                    val dx = e2.x - start.x
                    val dy = e2.y - start.y
                    val absDx = abs(dx)
                    val absDy = abs(dy)
                    val density = resources.displayMetrics.density
                    val horizontalDistance = 42f * density
                    val verticalDistance = 58f * density
                    val velocityThreshold = 520f

                    if (absDy > absDx * 1.2f && dy > verticalDistance && abs(velocityY) > velocityThreshold) {
                        animateAlbumCardDismissDown()
                        return true
                    }
                    if (absDx > absDy * 1.15f && absDx > horizontalDistance && abs(velocityX) > velocityThreshold) {
                        val next = dx < 0f
                        animateAlbumCardTrackSwitch(next = next)
                        return true
                    }
                    return false
                }
            }
        )

        val albumCard = (binding.imageCover.parent as? View) ?: binding.imageCover
        val touchListener = View.OnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
        albumCard.setOnTouchListener(touchListener)
        binding.imageCover.setOnTouchListener(touchListener)
    }

    private fun animateAlbumCardDismissDown() {
        if (collapsing) {
            return
        }
        val albumCard = (binding.imageCover.parent as? View) ?: binding.imageCover
        albumCard.animate().cancel()
        albumCard.animate()
            .translationY(dp(56f).toFloat())
            .alpha(0.8f)
            .setInterpolator(standardInterpolator)
            .setDuration(120L)
            .withEndAction {
                albumCard.translationY = 0f
                albumCard.alpha = 1f
                animateCollapseToMiniPlayerAndFinish()
            }
            .start()
    }

    private fun animateAlbumCardTrackSwitch(next: Boolean) {
        if (trackSwipeAnimating) {
            return
        }
        val canSwitch = if (next) binding.buttonNextTrack.isEnabled else binding.buttonPreviousTrack.isEnabled
        if (!canSwitch) {
            val nudge = if (next) -dp(22f).toFloat() else dp(22f).toFloat()
            val albumCard = (binding.imageCover.parent as? View) ?: binding.imageCover
            albumCard.animate().cancel()
            albumCard.animate()
                .translationX(nudge)
                .setDuration(80L)
                .setInterpolator(standardInterpolator)
                .withEndAction {
                    albumCard.animate()
                        .translationX(0f)
                        .setDuration(100L)
                        .setInterpolator(standardInterpolator)
                        .start()
                }
                .start()
            return
        }
        trackSwipeAnimating = true
        val direction = if (next) -1f else 1f
        val shift = max(dp(110f).toFloat(), binding.imageCover.width * 0.42f)
        val albumCard = (binding.imageCover.parent as? View) ?: binding.imageCover
        val title = binding.textTrackTitle
        val subtitle = binding.textTrackSubtitle
        val album = binding.textTrackAlbum

        fun animateGroupOut(onEnd: () -> Unit) {
            albumCard.animate().translationX(direction * shift).alpha(0.5f)
                .setInterpolator(standardInterpolator).setDuration(130L).withEndAction(onEnd).start()
            title.animate().translationX(direction * shift * 0.72f).alpha(0.5f)
                .setInterpolator(standardInterpolator).setDuration(130L).start()
            subtitle.animate().translationX(direction * shift * 0.72f).alpha(0.5f)
                .setInterpolator(standardInterpolator).setDuration(130L).start()
            album.animate().translationX(direction * shift * 0.72f).alpha(0.5f)
                .setInterpolator(standardInterpolator).setDuration(130L).start()
        }

        fun animateGroupIn() {
            albumCard.translationX = -direction * shift * 0.62f
            albumCard.alpha = 0.55f
            title.translationX = -direction * shift * 0.42f
            title.alpha = 0.55f
            subtitle.translationX = -direction * shift * 0.42f
            subtitle.alpha = 0.55f
            album.translationX = -direction * shift * 0.42f
            album.alpha = 0.55f

            albumCard.animate().translationX(0f).alpha(1f)
                .setInterpolator(standardInterpolator).setDuration(180L).start()
            title.animate().translationX(0f).alpha(1f)
                .setInterpolator(standardInterpolator).setDuration(180L).start()
            subtitle.animate().translationX(0f).alpha(1f)
                .setInterpolator(standardInterpolator).setDuration(180L).start()
            album.animate().translationX(0f).alpha(1f)
                .setInterpolator(standardInterpolator).setDuration(180L)
                .withEndAction { trackSwipeAnimating = false }
                .start()
        }

        animateGroupOut {
            if (next) {
                AudioEngine.skipToNextTrack()
            } else {
                AudioEngine.skipToPreviousTrack()
            }
            animateGroupIn()
        }
    }

    override fun onPause() {
        userSeeking = false
        hideWaveformPreview()
        super.onPause()
    }

    override fun onDestroy() {
        waveformJob?.cancel()
        lyricJob?.cancel()
        lyricsAutoReturnJob?.cancel()
        lyricsTranslationSwitchJob?.cancel()
        lyricInertiaAnimator?.cancel()
        lyricSettleAnimator?.cancel()
        lyricLineOffsetsPx.clear()
        lyricLineVelocitiesPxPerSec.clear()
        stopBackgroundDynamicScroll()
        releaseEnterMaskOverlay(immediate = true)
        super.onDestroy()
    }

    private fun renderState(state: PlaybackUiState) {
        hasRenderedStateAtLeastOnce = true
        latestArtworkRequestKey = state.artworkUri?.toString()
        binding.imageCover.loadArtworkOrDefault(state.artworkUri)
        val artworkKey = state.artworkUri?.toString()
        if (artworkKey != lastArtworkKey) {
            binding.imagePlayerBg.loadArtworkOrDefault(state.artworkUri)
            binding.imagePlayerBg.alpha = 1f
            if (entering) {
                binding.imagePlayerBg.scaleX = 1.24f
                binding.imagePlayerBg.scaleY = 1.24f
            } else {
                binding.imagePlayerBg.scaleX = 1.08f
                binding.imagePlayerBg.scaleY = 1.08f
                binding.imagePlayerBg.animate()
                    .scaleX(1.24f)
                    .scaleY(1.24f)
                    .setInterpolator(standardInterpolator)
                    .setDuration(520L)
                    .start()
            }
            lastArtworkKey = artworkKey
        }
        if (backgroundDynamicEnabled) {
            binding.root.post { ensureDynamicBackgroundTiled() }
        }
        binding.textTrackTitle.text = state.title
        val artistText = state.artist.ifBlank { state.subtitle }
        binding.textTrackSubtitle.text = artistText
        binding.textTrackSubtitle.visibility = if (artistText.isBlank()) View.GONE else View.VISIBLE
        binding.textTrackSubtitle.isEnabled = state.artist.isNotBlank()
        binding.textTrackAlbum.text = state.album
        binding.textTrackAlbum.visibility = if (state.album.isBlank()) View.GONE else View.VISIBLE
        binding.textTrackAlbum.isEnabled = state.album.isNotBlank()
        binding.buttonPreviousTrack.isEnabled = state.hasPrevious
        binding.buttonNextTrack.isEnabled = state.hasNext
        binding.buttonPlayPause.setImageResource(
            if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        updateControlIcons(state)
        binding.textSleepTimer.text = if (state.sleepTimerRemainingMs > 0L) {
            getString(R.string.player_sleep_timer_remaining, state.sleepTimerRemainingMs.toTimeString())
        } else {
            getString(R.string.player_sleep_timer_off)
        }

        val duration = max(1L, state.durationMs)
        latestDurationMs = duration
        updateProgressSliderRange(duration)
        binding.textDuration.text = state.durationMs.toTimeString()
        val pending = pendingSeekPositionMs
        if (pending != null) {
            val pendingMatched = abs(state.positionMs - pending) <= 1_200L
            if (pendingMatched) {
                pendingSeekPositionMs = null
                userSeeking = false
            }
        }
        if (!userSeeking && pendingSeekPositionMs == null) {
            binding.progressSlider.value = state.positionMs.coerceIn(0L, duration).toFloat()
            binding.textCurrentTime.text = state.positionMs.toTimeString()
        }
        bindLyricsForCurrentTrack(state)
        updateLyrics(
            positionMs = state.positionMs.coerceIn(0L, duration),
            isPlaying = state.isPlaying
        )
        bindWaveformForCurrentTrack(state)
        if (binding.waveformPreviewCard.visibility == View.VISIBLE) {
            val previewPositionMs = when {
                userSeeking -> binding.progressSlider.value.toLong()
                pendingSeekPositionMs != null -> pendingSeekPositionMs ?: state.positionMs
                else -> state.positionMs
            }.coerceIn(0L, duration)
            showWaveformPreview(previewPositionMs, duration)
        }
        if (pendingEnterMaskRelease && isReadyToReleaseEnterMask()) {
            releaseEnterMaskOverlay(immediate = false)
        }
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf(
            getString(R.string.player_sleep_option_off),
            getString(R.string.player_sleep_option_15),
            getString(R.string.player_sleep_option_30),
            getString(R.string.player_sleep_option_45),
            getString(R.string.player_sleep_option_60)
        )
        val durationMs = longArrayOf(
            0L,
            15L * 60_000L,
            30L * 60_000L,
            45L * 60_000L,
            60L * 60_000L
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.action_sleep_timer)
            .setItems(options) { _, which ->
                AppHaptics.click(this)
                val value = durationMs.getOrNull(which) ?: 0L
                if (value <= 0L) {
                    AudioEngine.cancelSleepTimer()
                } else {
                    AudioEngine.setSleepTimer(value)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupLyricsPanelExpansion() {
        setupLyricsRecycler()
        binding.buttonLyricsTranslationToggle.setOnClickListener {
            if (!lyricsHasTranslation) return@setOnClickListener
            if (lyricsTranslationSwitchInProgress) return@setOnClickListener
            lyricsTranslationSwitchInProgress = true
            lyricsTranslationSwitchJob?.cancel()
            lyricsTranslationSwitchJob = lifecycleScope.launch {
                delay(360L)
                lyricsTranslationSwitchInProgress = false
            }
            AppHaptics.click(it)
            lyricsTranslationMode = if (lyricsTranslationMode == LyricsTranslationMode.ORIGINAL_ONLY) {
                LyricsTranslationMode.ORIGINAL_WITH_TRANSLATION
            } else {
                LyricsTranslationMode.ORIGINAL_ONLY
            }
            lyricsAdapter.setTranslationMode(lyricsTranslationMode, animate = true)
            updateLyricsTranslationToggleUi()
        }
        binding.cardLyrics.setOnClickListener {
            if (!lyricsPanelVisibleBySetting) return@setOnClickListener
            AppHaptics.click(it)
            toggleLyricsPanelExpanded()
        }
        binding.buttonLyricsCollapse.setOnClickListener {
            if (!lyricsExpanded) return@setOnClickListener
            AppHaptics.click(it)
            toggleLyricsPanelExpanded()
        }
        binding.scrollPlayer.post {
            if (albumDefaultHeightPx <= 0) {
                albumDefaultHeightPx = binding.cardAlbum.height
            }
            cacheTrackTextSizesIfNeeded()
            applyAlbumLayout(expanded = false)
            applyLyricsExpandedVisual(expanded = false)
            updateLyricsTranslationToggleUi()
        }
    }

    private fun renderSettings(state: SettingsUiState) {
        applyLyricsPanelVisibility(state.showLyricsPanelEnabled)
        applyBackgroundSettings(state)
        applyLyricsFontSize(state.lyricsFontSizeSp)
        applyLyricsGlowIntensity(state.lyricsGlowIntensityPercent)
        applyLyricsBoldEnabled(state.lyricsBoldEnabled)
    }

    private fun applyLyricsFontSize(sizeSp: Int) {
        val normalized = sizeSp.coerceIn(12, 40)
        if (lyricsFontSizeSp == normalized) {
            return
        }
        lyricsFontSizeSp = normalized
        if (::lyricsAdapter.isInitialized) {
            lyricsAdapter.setLyricFontSizeSp(normalized)
        }
    }

    private fun applyLyricsGlowIntensity(value: Int) {
        val normalized = value.coerceIn(0, 100)
        if (lyricsGlowIntensityPercent == normalized) return
        lyricsGlowIntensityPercent = normalized
        if (::lyricsAdapter.isInitialized) {
            lyricsAdapter.setLyricGlowIntensityPercent(normalized)
        }
    }

    private fun applyLyricsBoldEnabled(enabled: Boolean) {
        if (lyricsBoldEnabled == enabled) return
        lyricsBoldEnabled = enabled
        if (::lyricsAdapter.isInitialized) {
            lyricsAdapter.setLyricsBoldEnabled(enabled)
        }
    }

    private fun applyLyricsPanelVisibility(enabled: Boolean) {
        if (lyricsPanelVisibleBySetting == enabled &&
            binding.cardLyrics.visibility == if (enabled) View.VISIBLE else View.GONE
        ) {
            return
        }
        lyricsPanelVisibleBySetting = enabled
        if (!enabled) {
            lyricsPanelAnimator?.cancel()
            lyricsExpanded = false
            lyricsAutoReturnJob?.cancel()
            lyricsAutoFollowEnabled = true
            applyLyricsExpandedVisual(expanded = false)
            binding.layoutLyrics.layoutParams = binding.layoutLyrics.layoutParams.apply {
                height = dp(65f)
            }
            binding.layoutLyrics.requestLayout()
            binding.cardLyrics.visibility = View.GONE
            return
        }
        binding.cardLyrics.visibility = View.VISIBLE
        lyricsExpanded = false
        applyLyricsExpandedVisual(expanded = false)
        binding.layoutLyrics.layoutParams = binding.layoutLyrics.layoutParams.apply {
            height = dp(65f)
        }
        binding.layoutLyrics.requestLayout()
    }

    private fun applyBackgroundSettings(state: SettingsUiState) {
        val dynamicChanged = backgroundDynamicEnabled != state.backgroundDynamicEnabled
        backgroundBlurStrength = state.backgroundBlurStrength
        backgroundOpacityPercent = state.backgroundOpacityPercent
        backgroundDynamicEnabled = state.backgroundDynamicEnabled

        applyBackgroundBlur(backgroundBlurStrength)
        binding.viewPlayerBgOverlay.alpha = (backgroundOpacityPercent / 100f).coerceIn(0f, 1f)

        if (dynamicChanged) {
            if (backgroundDynamicEnabled) {
                ensureDynamicBackgroundTiled()
                startBackgroundDynamicScroll()
            } else {
                stopBackgroundDynamicScroll()
                binding.imagePlayerBg.scaleType = ImageView.ScaleType.CENTER_CROP
                binding.imagePlayerBg.imageMatrix = Matrix()
                val uri = latestArtworkRequestKey?.let { Uri.parse(it) }
                binding.imagePlayerBg.loadArtworkOrDefault(uri)
            }
        } else if (backgroundDynamicEnabled) {
            ensureDynamicBackgroundTiled()
            startBackgroundDynamicScroll()
        }
    }

    private fun setupLyricsRecycler() {
        lyricsLayoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        lyricsAdapter = LyricsListAdapter(
            onLineClick = { index ->
                if (!lyricsExpanded) {
                    if (lyricsPanelAnimator?.isRunning != true) {
                        toggleLyricsPanelExpanded()
                    }
                    return@LyricsListAdapter
                }
                val target = activeLyrics.getOrNull(index)?.timeMs ?: return@LyricsListAdapter
                AppHaptics.click(binding.recyclerLyrics)
                pendingSeekPositionMs = target
                userSeeking = true
                lyricsAutoFollowEnabled = true
                AudioEngine.seekTo(target)
                scrollLyricsToCurrent(animated = true)
            }
        )
        binding.recyclerLyrics.layoutManager = lyricsLayoutManager
        binding.recyclerLyrics.adapter = lyricsAdapter
        binding.recyclerLyrics.itemAnimator = null
        binding.recyclerLyrics.setOnTouchListener { _, event ->
            if (!lyricsExpanded) {
                if (event.actionMasked == MotionEvent.ACTION_UP && lyricsPanelAnimator?.isRunning != true) {
                    AppHaptics.click(binding.cardLyrics)
                    toggleLyricsPanelExpanded()
                }
                true
            } else {
                false
            }
        }
        binding.recyclerLyrics.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val now = android.os.SystemClock.uptimeMillis()
                val last = lyricLastScrollUptimeMs
                lyricLastScrollUptimeMs = now
                if (last > 0L) {
                    val deltaMs = (now - last).coerceAtLeast(1L)
                    val instantVelocity = (dy.toFloat() * 1000f) / deltaMs.toFloat()
                    lyricInertiaVelocityPxPerSec = (
                        lyricInertiaVelocityPxPerSec * 0.35f + instantVelocity * 0.65f
                        ).coerceIn(-3200f, 3200f)
                    lyricInertiaPhase += (dy.toFloat() / dp(44f).coerceAtLeast(1)).coerceIn(-2f, 2f)
                    applyLyricsInertiaToVisibleItems((deltaMs / 1000f).coerceIn(1f / 240f, 1f / 24f))
                }
                updateLyricsViewportEdgeBlur()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        lyricInertiaAnimator?.cancel()
                        lyricSettleAnimator?.cancel()
                        lyricInertiaLastFrameUptimeMs = 0L
                        lyricSettleLastFrameUptimeMs = 0L
                        if (lyricsExpanded) {
                            lyricsAutoFollowEnabled = false
                            lyricsAutoReturnJob?.cancel()
                        }
                    }
                    RecyclerView.SCROLL_STATE_SETTLING -> {
                        lyricInertiaAnimator?.cancel()
                        lyricSettleAnimator?.cancel()
                        lyricInertiaLastFrameUptimeMs = 0L
                        lyricSettleLastFrameUptimeMs = 0L
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        startLyricInertiaDecay()
                        if (lyricsExpanded && !lyricsAutoFollowEnabled) {
                            scheduleLyricsAutoReturn()
                        }
                    }
                }
                updateLyricsViewportEdgeBlur()
            }
        })
        binding.recyclerLyrics.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateLyricsViewportEdgeBlur()
        }
        binding.recyclerLyrics.post { updateLyricsViewportEdgeBlur() }
    }

    private data class LyricInertiaNode(
        val view: LyricMaskTextView,
        val adapterPos: Int,
        val distance: Int,
        val isCurrent: Boolean
    )

    private fun applyLyricsInertiaToVisibleItems(deltaSec: Float = 1f / 60f) {
        if (!lyricsExpanded) {
            resetLyricInertiaTransforms()
            return
        }
        val rv = binding.recyclerLyrics
        if (rv.childCount == 0) {
            return
        }
        val dt = deltaSec.coerceIn(1f / 240f, 1f / 24f)
        val velocityAbs = abs(lyricInertiaVelocityPxPerSec)
        val intensity = (velocityAbs / 1800f).coerceIn(0f, 1f)
        val signed = if (lyricInertiaVelocityPxPerSec >= 0f) 1f else -1f
        val centerY = rv.paddingTop + (rv.height - rv.paddingTop - rv.paddingBottom) * 0.5f
        val nodes = ArrayList<LyricInertiaNode>(rv.childCount)

        for (i in 0 until rv.childCount) {
            val lyricView = rv.getChildAt(i) as? LyricMaskTextView ?: continue
            val adapterPos = rv.getChildAdapterPosition(lyricView)
            if (adapterPos == RecyclerView.NO_POSITION) continue
            val distance = lyricView.getTag(R.id.tag_lyric_distance) as? Int ?: 4
            val isCurrent = lyricView.getTag(R.id.tag_lyric_is_current) == true
            val depthWeight = when (distance.coerceAtLeast(0)) {
                0 -> if (isCurrent) 0.18f else 0.5f
                1 -> 1f
                2 -> 0.8f
                3 -> 0.62f
                else -> 0.42f
            }
            val childCenterY = lyricView.top + lyricView.height * 0.5f + lyricView.translationY
            val repulseFromCenter = if (childCenterY >= centerY) 1f else -1f
            val phaseOffset = distance * 0.52f + (adapterPos % 3) * 0.21f
            val oscillation = sin(lyricInertiaPhase + phaseOffset) * dp(1.45f)
            val inertialDrift = signed * dp(2.6f) * intensity
            val repulsion = repulseFromCenter * dp(2.1f) * (0.3f + 0.7f * intensity) * (1f - (distance / 5f).coerceIn(0f, 1f))
            val target = (inertialDrift + oscillation + repulsion) * depthWeight

            val currentOffset = lyricLineOffsetsPx[adapterPos] ?: lyricView.translationY
            val currentVelocity = lyricLineVelocitiesPxPerSec[adapterPos] ?: 0f
            val stiffness = 26f + intensity * 18f
            val damping = 12f + (1f - intensity) * 8f
            val acceleration = (target - currentOffset) * stiffness - currentVelocity * damping
            val nextVelocity = currentVelocity + acceleration * dt
            val nextOffset = currentOffset + nextVelocity * dt

            lyricLineVelocitiesPxPerSec[adapterPos] = nextVelocity
            lyricLineOffsetsPx[adapterPos] = nextOffset
            lyricView.translationY = nextOffset
            nodes += LyricInertiaNode(
                view = lyricView,
                adapterPos = adapterPos,
                distance = distance,
                isCurrent = isCurrent
            )
        }

        // Repulsion spacing pass: keep lightweight minimum gap between adjacent visible lines.
        val desiredGapPx = dp(4f).toFloat()
        nodes.sortedBy { it.view.top + it.view.translationY }
            .windowed(2, 1, partialWindows = false)
            .forEach { pair ->
                val first = pair[0]
                val second = pair[1]
                val firstBottom = first.view.top + first.view.height + first.view.translationY
                val secondTop = second.view.top + second.view.translationY
                val overlap = (firstBottom + desiredGapPx) - secondTop
                if (overlap > 0f) {
                    val push = overlap * 0.5f
                    first.view.translationY -= push
                    second.view.translationY += push
                    lyricLineOffsetsPx[first.adapterPos] = first.view.translationY
                    lyricLineOffsetsPx[second.adapterPos] = second.view.translationY
                }
            }
    }

    private fun resetLyricInertiaTransforms() {
        val rv = binding.recyclerLyrics
        for (i in 0 until rv.childCount) {
            val lyricView = rv.getChildAt(i) as? LyricMaskTextView ?: continue
            lyricView.translationY = 0f
        }
        lyricLineOffsetsPx.clear()
        lyricLineVelocitiesPxPerSec.clear()
    }

    private fun startLyricInertiaDecay() {
        val startVelocity = lyricInertiaVelocityPxPerSec
        if (abs(startVelocity) < 12f) {
            lyricInertiaVelocityPxPerSec = 0f
            startLyricOffsetSettle()
            return
        }
        lyricInertiaAnimator?.cancel()
        lyricSettleAnimator?.cancel()
        lyricInertiaLastFrameUptimeMs = 0L
        lyricInertiaAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 860L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val now = android.os.SystemClock.uptimeMillis()
                val last = lyricInertiaLastFrameUptimeMs
                lyricInertiaLastFrameUptimeMs = now
                val dt = if (last > 0L) {
                    ((now - last).coerceAtLeast(1L) / 1000f).coerceIn(1f / 240f, 1f / 24f)
                } else {
                    1f / 60f
                }
                val decay = (animator.animatedValue as Float).coerceIn(0f, 1f)
                lyricInertiaVelocityPxPerSec = startVelocity * decay * decay
                lyricInertiaPhase += (lyricInertiaVelocityPxPerSec / 3600f).coerceIn(-0.12f, 0.12f)
                applyLyricsInertiaToVisibleItems(dt)
            }
            doOnEnd {
                lyricInertiaVelocityPxPerSec = 0f
                lyricInertiaLastFrameUptimeMs = 0L
                startLyricOffsetSettle()
            }
            start()
        }
    }

    private fun startLyricOffsetSettle() {
        lyricSettleAnimator?.cancel()
        if (lyricLineOffsetsPx.isEmpty() && lyricLineVelocitiesPxPerSec.isEmpty()) {
            return
        }
        lyricSettleLastFrameUptimeMs = 0L
        lyricSettleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620L
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val now = android.os.SystemClock.uptimeMillis()
                val last = lyricSettleLastFrameUptimeMs
                lyricSettleLastFrameUptimeMs = now
                val dt = if (last > 0L) {
                    ((now - last).coerceAtLeast(1L) / 1000f).coerceIn(1f / 240f, 1f / 20f)
                } else {
                    1f / 60f
                }
                val rv = binding.recyclerLyrics
                var maxOffset = 0f
                var maxVelocity = 0f
                for (i in 0 until rv.childCount) {
                    val lyricView = rv.getChildAt(i) as? LyricMaskTextView ?: continue
                    val adapterPos = rv.getChildAdapterPosition(lyricView)
                    if (adapterPos == RecyclerView.NO_POSITION) continue
                    val currentOffset = lyricLineOffsetsPx[adapterPos] ?: lyricView.translationY
                    val currentVelocity = lyricLineVelocitiesPxPerSec[adapterPos] ?: 0f
                    val acceleration = (-currentOffset * 42f) - (currentVelocity * 13f)
                    val nextVelocity = currentVelocity + acceleration * dt
                    val nextOffset = currentOffset + nextVelocity * dt
                    val settled = abs(nextOffset) < 0.08f && abs(nextVelocity) < 1.25f
                    val finalOffset = if (settled) 0f else nextOffset
                    val finalVelocity = if (settled) 0f else nextVelocity
                    lyricView.translationY = finalOffset
                    if (settled) {
                        lyricLineOffsetsPx.remove(adapterPos)
                        lyricLineVelocitiesPxPerSec.remove(adapterPos)
                    } else {
                        lyricLineOffsetsPx[adapterPos] = finalOffset
                        lyricLineVelocitiesPxPerSec[adapterPos] = finalVelocity
                    }
                    maxOffset = max(maxOffset, abs(finalOffset))
                    maxVelocity = max(maxVelocity, abs(finalVelocity))
                }
                if (maxOffset < 0.08f && maxVelocity < 1.25f) {
                    animator.end()
                }
            }
            doOnEnd {
                endLyricOffsetSettle(immediate = false)
            }
            start()
        }
    }

    private fun endLyricOffsetSettle(immediate: Boolean) {
        if (immediate) {
            lyricSettleAnimator?.cancel()
        }
        lyricSettleAnimator = null
        lyricSettleLastFrameUptimeMs = 0L
        lyricLineOffsetsPx.entries.removeAll { abs(it.value) < 0.08f }
        lyricLineVelocitiesPxPerSec.entries.removeAll { abs(it.value) < 1.25f }
        val rv = binding.recyclerLyrics
        for (i in 0 until rv.childCount) {
            val lyricView = rv.getChildAt(i) as? LyricMaskTextView ?: continue
            val adapterPos = rv.getChildAdapterPosition(lyricView)
            if (adapterPos == RecyclerView.NO_POSITION) continue
            lyricView.translationY = lyricLineOffsetsPx[adapterPos] ?: 0f
        }
    }

    private fun toggleLyricsPanelExpanded() {
        if (lyricsPanelAnimator?.isRunning == true) {
            return
        }
        val expand = !lyricsExpanded
        animateLyricsPanel(expand)
    }

    private fun animateLyricsPanel(expand: Boolean) {
        val lyricsStart = binding.layoutLyrics.height.takeIf { it > 0 } ?: dp(65f)
        val lyricsEnd = if (expand) resolveExpandedLyricsHeight() else dp(65f)
        val albumStart = binding.cardAlbum.height.takeIf { it > 0 } ?: albumDefaultHeightPx
        val bottomAnchorStart = albumStart + lyricsStart
        val coverStartWidth = binding.cardCover.width.coerceAtLeast(1)
        val coverStartHeight = binding.cardCover.height.coerceAtLeast(1)
        if (albumDefaultHeightPx <= 0) {
            albumDefaultHeightPx = albumStart
        }
        val delta = lyricsEnd - lyricsStart
        val albumMin = resolveExpandedAlbumMinHeight()
        val albumEnd = if (expand) {
            (albumStart - delta).coerceAtLeast(albumMin)
        } else {
            albumDefaultHeightPx
        }
        val coverTargetCollapsed = dp(COLLAPSED_COVER_SIZE_DP).coerceAtLeast(1)
        val coverTargetExpandedWidth = resolveExpandedCoverWidth().coerceAtLeast(1)
        val coverTargetExpandedHeight = dp(300f).coerceAtLeast(1)
        val coverScaleStartX: Float
        val coverScaleStartY: Float
        val coverScaleEndX: Float
        val coverScaleEndY: Float
        if (expand) {
            binding.scrollPlayer.scrollTo(0, 0)
            applyAlbumLayout(expanded = true)
            coverScaleStartX = (coverStartWidth.toFloat() / coverTargetCollapsed.toFloat()).coerceAtLeast(1f)
            coverScaleStartY = (coverStartHeight.toFloat() / coverTargetCollapsed.toFloat()).coerceAtLeast(1f)
            coverScaleEndX = 1f
            coverScaleEndY = 1f
            binding.cardCover.scaleX = coverScaleStartX
            binding.cardCover.scaleY = coverScaleStartY
        } else {
            coverScaleStartX = 1f
            coverScaleStartY = 1f
            coverScaleEndX = (coverTargetExpandedWidth.toFloat() / coverStartWidth.toFloat()).coerceAtLeast(1f)
            coverScaleEndY = (coverTargetExpandedHeight.toFloat() / coverStartHeight.toFloat()).coerceAtLeast(1f)
        }
        applyLyricsExpandedVisual(expanded = expand)
        lyricsPanelAnimator?.cancel()
        lyricsPanelAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = standardInterpolator
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                val albumT = mapAlbumHeightFraction(expand = expand, rawFraction = t)
                val coverT = mapCoverScaleFraction(expand = expand, rawFraction = t)
                val currentLyricsHeight = lerp(lyricsStart.toFloat(), lyricsEnd.toFloat(), t).toInt()
                val currentAlbumHeight = lerp(albumStart.toFloat(), albumEnd.toFloat(), albumT).toInt()
                val bottomShift = (currentAlbumHeight + currentLyricsHeight - bottomAnchorStart).toFloat()
                binding.cardCover.scaleX = lerp(coverScaleStartX, coverScaleEndX, coverT)
                binding.cardCover.scaleY = lerp(coverScaleStartY, coverScaleEndY, coverT)
                binding.layoutBottomControls.translationY = -bottomShift
                binding.layoutLyrics.layoutParams = binding.layoutLyrics.layoutParams.apply {
                    height = currentLyricsHeight
                }
                binding.cardAlbum.layoutParams = binding.cardAlbum.layoutParams.apply {
                    height = currentAlbumHeight
                }
                binding.cardAlbum.requestLayout()
                binding.layoutLyrics.requestLayout()
            }
            doOnEnd {
                lyricsExpanded = expand
                if (!expand) {
                    applyAlbumLayout(expanded = false)
                    lyricsAutoReturnJob?.cancel()
                    lyricsAutoFollowEnabled = true
                }
                binding.cardCover.scaleX = 1f
                binding.cardCover.scaleY = 1f
                binding.layoutBottomControls.translationY = 0f
                binding.cardAlbum.layoutParams = binding.cardAlbum.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                binding.layoutLyrics.layoutParams = binding.layoutLyrics.layoutParams.apply {
                    height = if (lyricsExpanded) resolveExpandedLyricsHeight() else dp(65f)
                }
                if (!lyricsExpanded) {
                    binding.layoutLyrics.layoutParams = binding.layoutLyrics.layoutParams.apply {
                        height = dp(65f)
                    }
                }
                binding.cardAlbum.requestLayout()
                binding.layoutLyrics.requestLayout()
                lyricsAutoFollowEnabled = true
                scrollLyricsToCurrent(animated = false)
                lastAutoFollowedLyricIndex = currentLyricIndex
            }
        }
        lyricsPanelAnimator?.start()
    }

    private fun mapAlbumHeightFraction(expand: Boolean, rawFraction: Float): Float {
        val t = rawFraction.coerceIn(0f, 1f)
        return if (expand) {
            1f - (1f - t).pow(2.35f)
        } else {
            t.pow(1.55f)
        }
    }

    private fun mapCoverScaleFraction(expand: Boolean, rawFraction: Float): Float {
        val t = rawFraction.coerceIn(0f, 1f)
        return if (expand) {
            1f - (1f - t).pow(2.1f)
        } else {
            t.pow(1.4f)
        }
    }

    private fun resolveExpandedLyricsHeight(): Int {
        val rootHeight = binding.root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val dynamic = (rootHeight * 0.52f).toInt()
        return dynamic.coerceIn(dp(250f), dp(460f))
    }

    private fun applyLyricsExpandedVisual(expanded: Boolean) {
        binding.viewLyricsFadeTop.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.viewLyricsFadeBottom.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.buttonLyricsCollapse.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.buttonLyricsTranslationToggle.visibility = if (expanded) View.VISIBLE else View.GONE
        lyricsAdapter.setExpanded(expanded)
        updateLyricsTranslationToggleUi()
        binding.recyclerLyrics.post { updateLyricsViewportEdgeBlur() }
    }

    private fun updateLyricsTranslationToggleUi() {
        val hasTranslation = lyricsHasTranslation
        binding.buttonLyricsTranslationToggle.isEnabled = hasTranslation
        binding.buttonLyricsTranslationToggle.alpha = if (hasTranslation) 1f else 0.45f
        val showBoth = lyricsTranslationMode == LyricsTranslationMode.ORIGINAL_WITH_TRANSLATION
        binding.buttonLyricsTranslationToggle.text = if (showBoth) {
            getString(R.string.player_lyrics_translation_with_translation_short)
        } else {
            getString(R.string.player_lyrics_translation_original_only_short)
        }
        binding.buttonLyricsTranslationToggle.contentDescription = if (showBoth) {
            getString(R.string.player_lyrics_translation_mode_with_translation)
        } else {
            getString(R.string.player_lyrics_translation_mode_original_only)
        }
    }

    private fun applyAlbumLayout(expanded: Boolean) {
        val albumLayout = binding.layoutAlbumContent
        val coverLp = binding.cardCover.layoutParams as? LinearLayout.LayoutParams ?: return
        val infoLp = binding.layoutTrackInfo.layoutParams as? LinearLayout.LayoutParams ?: return
        val imageLp = binding.imageCover.layoutParams
        if (expanded) {
            albumLayout.orientation = LinearLayout.HORIZONTAL
            val collapsedCover = dp(COLLAPSED_COVER_SIZE_DP)
            coverLp.width = collapsedCover
            coverLp.height = collapsedCover
            coverLp.weight = 0f
            infoLp.width = 0
            infoLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            infoLp.weight = 1f
            infoLp.marginStart = dp(12f)
            infoLp.topMargin = 0
            imageLp.height = ViewGroup.LayoutParams.MATCH_PARENT
            applyTrackInfoTextScale(expanded = true)
        } else {
            albumLayout.orientation = LinearLayout.VERTICAL
            coverLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            coverLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            coverLp.weight = 0f
            infoLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            infoLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            infoLp.weight = 0f
            infoLp.marginStart = 0
            infoLp.topMargin = dp(14f)
            imageLp.height = dp(300f)
            applyTrackInfoTextScale(expanded = false)
        }
        binding.cardCover.layoutParams = coverLp
        binding.layoutTrackInfo.layoutParams = infoLp
        binding.imageCover.layoutParams = imageLp
    }

    private fun resolveExpandedAlbumMinHeight(): Int {
        val coverSize = dp(COLLAPSED_COVER_SIZE_DP)
        val textHeight = measureTrackInfoContentHeight(expanded = true)
        val paddingVertical = dp(18f) * 2
        return (max(coverSize, textHeight) + paddingVertical).coerceAtLeast(dp(112f))
    }

    private fun resolveExpandedCoverWidth(): Int {
        val content = binding.layoutAlbumContent
        val baseWidth = if (content.width > 0) content.width else binding.cardAlbum.width
        val available = baseWidth - content.paddingLeft - content.paddingRight
        return available.coerceAtLeast(dp(180f))
    }

    private fun measureTrackInfoContentHeight(expanded: Boolean): Int {
        cacheTrackTextSizesIfNeeded()
        val titleSizePx = spToPx(if (expanded) titleTextSizeSpDefault * 0.86f else titleTextSizeSpDefault)
        val subtitleSizePx = spToPx(if (expanded) subtitleTextSizeSpDefault * 0.9f else subtitleTextSizeSpDefault)
        val albumSizePx = spToPx(if (expanded) albumTextSizeSpDefault * 0.9f else albumTextSizeSpDefault)
        val titleHeight = (titleSizePx * 1.3f).toInt()
        val subtitleHeight = if (binding.textTrackSubtitle.visibility == View.VISIBLE) (subtitleSizePx * 1.3f).toInt() else 0
        val albumHeight = if (binding.textTrackAlbum.visibility == View.VISIBLE) (albumSizePx * 1.3f).toInt() else 0
        val verticalGaps = dp(6f)
        return titleHeight + subtitleHeight + albumHeight + verticalGaps
    }

    private fun cacheTrackTextSizesIfNeeded() {
        if (titleTextSizeSpDefault > 0f) {
            return
        }
        titleTextSizeSpDefault = pxToSp(binding.textTrackTitle.textSize)
        subtitleTextSizeSpDefault = pxToSp(binding.textTrackSubtitle.textSize)
        albumTextSizeSpDefault = pxToSp(binding.textTrackAlbum.textSize)
    }

    private fun applyTrackInfoTextScale(expanded: Boolean) {
        cacheTrackTextSizesIfNeeded()
        val titleSp = if (expanded) titleTextSizeSpDefault * 0.86f else titleTextSizeSpDefault
        val subtitleSp = if (expanded) subtitleTextSizeSpDefault * 0.9f else subtitleTextSizeSpDefault
        val albumSp = if (expanded) albumTextSizeSpDefault * 0.9f else albumTextSizeSpDefault
        binding.textTrackTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp)
        binding.textTrackSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSp)
        binding.textTrackAlbum.setTextSize(TypedValue.COMPLEX_UNIT_SP, albumSp)
    }

    private fun pxToSp(px: Float): Float {
        return px / resources.displayMetrics.scaledDensity
    }

    private fun spToPx(sp: Float): Float {
        return sp * resources.displayMetrics.scaledDensity
    }

    private fun openArtistDetail(artistName: String) {
        startActivity(MainActivity.createOpenArtistIntent(this, artistName))
        if (!isFinishing) {
            finish()
        }
    }

    private fun openAlbumDetail(albumName: String) {
        startActivity(MainActivity.createOpenAlbumIntent(this, albumName))
        if (!isFinishing) {
            finish()
        }
    }

    private fun updateProgressSliderRange(durationMs: Long) {
        val slider = binding.progressSlider
        val maxValue = durationMs.toFloat().coerceAtLeast(1f)
        runCatching {
            slider.value = slider.value.coerceIn(0f, maxValue)
            slider.valueFrom = 0f
            slider.valueTo = maxValue
        }.onFailure {
            slider.valueFrom = 0f
            slider.valueTo = maxValue
            slider.value = slider.value.coerceIn(0f, maxValue)
        }
    }

    private fun bindWaveformForCurrentTrack(state: PlaybackUiState) {
        val mediaUri = state.mediaUri ?: return
        val key = mediaUri.toString()
        if (key == lastMediaKey) {
            return
        }
        lastMediaKey = key
        waveformCache[key]?.let { cached ->
            binding.waveformPreviewView.setWaveformLevels(cached)
            return
        }
        waveformJob?.cancel()
        waveformJob = lifecycleScope.launch {
            val levels = WaveformAnalyzer.analyze(
                context = applicationContext,
                uri = mediaUri,
                durationMs = state.durationMs
            )
            waveformCache[key] = levels
            if (lastMediaKey == key) {
                binding.waveformPreviewView.setWaveformLevels(levels)
            }
        }
    }

    private fun bindLyricsForCurrentTrack(state: PlaybackUiState) {
        val mediaUri = state.mediaUri ?: run {
            activeLyrics = emptyList()
            lyricsHasTranslation = false
            lastLyricMediaKey = null
            currentLyricIndex = -1
            lastAutoFollowedLyricIndex = -1
            lyricsAdapter.submitLyrics(emptyList())
            lyricsAdapter.updatePlayback(currentIndex = -1, progress = 0f, progressRatePerSec = 0f)
            updateLyricsTranslationToggleUi()
            return
        }
        val key = mediaUri.toString()
        if (key == lastLyricMediaKey) {
            return
        }
        lastLyricMediaKey = key
        lyricsAutoFollowEnabled = true
        lyricsAutoReturnJob?.cancel()
        currentLyricIndex = -1
        lastAutoFollowedLyricIndex = -1
        lyricsHasTranslation = false
        lyricsAdapter.submitLyrics(emptyList())
        lyricsAdapter.updatePlayback(currentIndex = -1, progress = 0f, progressRatePerSec = 0f)
        updateLyricsTranslationToggleUi()
        lyricCache[key]?.let { cachedRaw ->
            val cached = normalizeLyricTranslations(cachedRaw)
            lyricCache[key] = cached
            activeLyrics = cached
            lyricsHasTranslation = cached.any { !it.translatedText.isNullOrBlank() }
            lyricsAdapter.submitLyrics(cached)
            updateLyricsTranslationToggleUi()
            return
        }
        lyricJob?.cancel()
        lyricJob = lifecycleScope.launch {
            val loadResult = readLrcMetadata(mediaUri)
            val parsed = normalizeLyricTranslations(parseLrcLines(loadResult.text))
            lyricCache[key] = parsed
            if (lastLyricMediaKey == key) {
                activeLyrics = parsed
                lyricsHasTranslation = parsed.any { !it.translatedText.isNullOrBlank() }
                lyricsAdapter.submitLyrics(parsed)
                updateLyricsTranslationToggleUi()
                updateLyrics(
                    positionMs = binding.progressSlider.value.toLong(),
                    isPlaying = AudioEngine.playbackState.value.isPlaying
                )
                Log.d(
                    TAG,
                    "lyrics loaded: source=${loadResult.source}, parsedLines=${parsed.size}, media=$key"
                )
            }
        }
    }

    private fun updateLyrics(positionMs: Long, isPlaying: Boolean) {
        if (activeLyrics.isEmpty()) {
            currentLyricIndex = -1
            lastAutoFollowedLyricIndex = -1
            lyricsAdapter.updatePlayback(currentIndex = -1, progress = 0f, progressRatePerSec = 0f)
            return
        }
        val previousIndex = currentLyricIndex
        val rawIndex = findCurrentLyricIndex(positionMs, activeLyrics)
        currentLyricIndex = rawIndex.coerceAtLeast(0).coerceAtMost(activeLyrics.lastIndex)
        val progress = computeLyricProgress(positionMs, currentLyricIndex, activeLyrics)
        val progressRatePerSec = if (isPlaying) {
            computeLyricProgressRate(currentLyricIndex, activeLyrics)
        } else {
            0f
        }
        lyricsAdapter.updatePlayback(currentIndex = currentLyricIndex, progress = progress, progressRatePerSec = progressRatePerSec)
        maybeAutoFollowLyrics(animated = previousIndex != currentLyricIndex)
    }

    private fun maybeAutoFollowLyrics(animated: Boolean) {
        if (lyricsExpanded && !lyricsAutoFollowEnabled) return
        if (currentLyricIndex < 0 || currentLyricIndex >= activeLyrics.size) return
        val shouldAnimate = lyricsExpanded && animated
        if (!shouldAnimate && currentLyricIndex == lastAutoFollowedLyricIndex) return
        scrollLyricsToCurrent(animated = shouldAnimate)
        lastAutoFollowedLyricIndex = currentLyricIndex
    }

    private fun scheduleLyricsAutoReturn() {
        lyricsAutoReturnJob?.cancel()
        lyricsAutoReturnJob = lifecycleScope.launch {
            delay(LYRICS_AUTO_RETURN_DELAY_MS)
            if (!lyricsExpanded || isFinishing || isDestroyed) return@launch
            lyricsAutoFollowEnabled = true
            scrollLyricsToCurrent(animated = true)
        }
    }

    private fun scrollLyricsToCurrent(animated: Boolean) {
        val index = currentLyricIndex
        if (index !in activeLyrics.indices) return
        val rv = binding.recyclerLyrics
        val targetOffset = resolveLyricCenterOffset(index)
        if (!animated || !rv.isShown) {
            lyricsLayoutManager.scrollToPositionWithOffset(index, targetOffset)
            return
        }
        val targetView = lyricsLayoutManager.findViewByPosition(index)
        if (targetView != null) {
            val dy = targetView.top - targetOffset
            if (abs(dy) <= 1) return
            injectLyricSpringKick(dy)
            val duration = (abs(dy).toFloat().pow(0.72f) * 14f + 120f)
                .toInt()
                .coerceIn(140, 420)
            rv.smoothScrollBy(0, dy, standardInterpolator, duration)
            return
        }
        val anchor = lyricsLayoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val estimateDy = ((index - anchor) * dp(46f)).coerceIn(-dp(460f), dp(460f))
        injectLyricSpringKick(estimateDy)
        val scroller = object : LinearSmoothScroller(this) {
            override fun getVerticalSnapPreference(): Int = SNAP_TO_START

            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int {
                val targetTop = boxStart + targetOffset
                return targetTop - viewStart
            }

            override fun calculateTimeForDeceleration(dx: Int): Int {
                val distance = abs(dx).toFloat()
                return (distance.pow(0.68f) * 18f + 180f)
                    .toInt()
                    .coerceIn(180, 520)
            }
        }.apply { targetPosition = index }
        lyricsLayoutManager.startSmoothScroll(scroller)
    }

    private fun injectLyricSpringKick(dy: Int) {
        if (dy == 0) return
        lyricSettleAnimator?.cancel()
        val rv = binding.recyclerLyrics
        if (rv.childCount <= 0) return
        val signed = if (dy >= 0) 1f else -1f
        val magnitude = abs(dy).toFloat().coerceIn(dp(12f).toFloat(), dp(220f).toFloat())
        val centerY = rv.paddingTop + (rv.height - rv.paddingTop - rv.paddingBottom) * 0.5f
        for (i in 0 until rv.childCount) {
            val lyricView = rv.getChildAt(i) as? LyricMaskTextView ?: continue
            val adapterPos = rv.getChildAdapterPosition(lyricView)
            if (adapterPos == RecyclerView.NO_POSITION) continue
            val childCenterY = lyricView.top + lyricView.translationY + lyricView.height * 0.5f
            val normalizedDistance = (abs(childCenterY - centerY) / rv.height.coerceAtLeast(1)).coerceIn(0f, 0.6f)
            val depthWeight = (0.42f + normalizedDistance * 1.25f).coerceIn(0.35f, 1.15f)
            val phase = lyricInertiaPhase + (adapterPos % 5) * 0.46f
            val wave = sin(phase) * dp(1.8f).toFloat()
            val targetOffset = signed * magnitude * 0.04f * depthWeight + wave
            val currentOffset = lyricLineOffsetsPx[adapterPos] ?: lyricView.translationY
            val blendedOffset = currentOffset * 0.42f + targetOffset * 0.58f
            val currentVelocity = lyricLineVelocitiesPxPerSec[adapterPos] ?: 0f
            val kickVelocity = signed * magnitude * (14f + depthWeight * 5f)
            val blendedVelocity = currentVelocity * 0.34f + kickVelocity * 0.66f
            lyricLineOffsetsPx[adapterPos] = blendedOffset
            lyricLineVelocitiesPxPerSec[adapterPos] = blendedVelocity
            lyricView.translationY = blendedOffset
        }
        lyricInertiaVelocityPxPerSec = signed * magnitude * 5.4f
        lyricInertiaPhase += signed * 0.35f
    }

    private fun resolveLyricCenterOffset(index: Int): Int {
        val rv = binding.recyclerLyrics
        val indexedView = lyricsLayoutManager.findViewByPosition(index)
        val sampleHeight = indexedView?.height
            ?: rv.getChildAt(0)?.height
            ?: dp(40f)
        val available = rv.height - rv.paddingTop - rv.paddingBottom
        return rv.paddingTop + (available - sampleHeight).coerceAtLeast(0) / 2
    }

    private fun computeLyricProgress(positionMs: Long, currentIndex: Int, lyrics: List<LyricLine>): Float {
        val currentLine = lyrics.getOrNull(currentIndex) ?: return 0f
        val nextLine = lyrics.getOrNull(currentIndex + 1) ?: return 1f
        val span = (nextLine.timeMs - currentLine.timeMs).coerceAtLeast(1L)
        return ((positionMs - currentLine.timeMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }

    private fun computeLyricProgressRate(currentIndex: Int, lyrics: List<LyricLine>): Float {
        val currentLine = lyrics.getOrNull(currentIndex) ?: return 0f
        val nextLine = lyrics.getOrNull(currentIndex + 1) ?: return 0f
        val span = (nextLine.timeMs - currentLine.timeMs).coerceAtLeast(1L)
        return 1000f / span.toFloat()
    }

    private suspend fun readLrcMetadata(uri: Uri): LyricLoadResult = withContext(Dispatchers.IO) {
        val fromId3Frames = readEmbeddedLyricsFromId3(uri)
        if (fromId3Frames != null && !fromId3Frames.isNullOrBlank()) {
            return@withContext fromId3Frames
        }

        val fromFlacVorbis = readEmbeddedLyricsFromFlacVorbis(uri)
        if (fromFlacVorbis != null && !fromFlacVorbis.isNullOrBlank()) {
            return@withContext fromFlacVorbis
        }

        val fromRetriever = runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(applicationContext, uri)
                val fromComment = extractMetadataByFieldName(mmr, "METADATA_KEY_COMMENT")
                if (!fromComment.isNullOrBlank()) {
                    return@runCatching LyricLoadResult(fromComment, "comment")
                }
                extractMetadataByFieldName(mmr, "METADATA_KEY_LYRIC")?.let {
                    LyricLoadResult(it, "lyric")
                }
            } finally {
                runCatching { mmr.release() }
            }
        }.getOrNull()
        if (fromRetriever != null && !fromRetriever.isNullOrBlank()) {
            return@withContext fromRetriever
        }
        val fromColumns = runCatching {
            contentResolver.query(uri, arrayOf("lyrics", "lyric"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val lyricsIndex = cursor.getColumnIndex("lyrics")
                    if (lyricsIndex >= 0) {
                        val value = cursor.getString(lyricsIndex)
                        if (!value.isNullOrBlank()) return@use LyricLoadResult(value, "column:lyrics")
                    }
                    val lyricIndex = cursor.getColumnIndex("lyric")
                    if (lyricIndex >= 0) {
                        val value = cursor.getString(lyricIndex)
                        if (!value.isNullOrBlank()) return@use LyricLoadResult(value, "column:lyric")
                    }
                }
                null
            }
        }.getOrNull()
        if (fromColumns != null && !fromColumns.isNullOrBlank()) {
            return@withContext fromColumns
        }
        val sidecar = readSidecarLrc(uri)
        if (!sidecar.isNullOrBlank()) {
            return@withContext LyricLoadResult(sidecar, "sidecar:lrc")
        }
        LyricLoadResult(null, "none")
    }

    private fun readEmbeddedLyricsFromId3(uri: Uri): LyricLoadResult? {
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(10)
                if (input.read(header) != 10) return@use null
                if (!(header[0].toInt().toChar() == 'I' && header[1].toInt().toChar() == 'D' && header[2].toInt().toChar() == '3')) {
                    return@use null
                }
                val version = header[3].toInt() and 0xFF
                val flags = header[5].toInt() and 0xFF
                val tagSize = synchsafeToInt(header, 6)
                if (tagSize <= 0) return@use null
                val payload = ByteArray(tagSize)
                var read = 0
                while (read < tagSize) {
                    val n = input.read(payload, read, tagSize - read)
                    if (n <= 0) break
                    read += n
                }
                if (read <= 0) return@use null
                val effective = if (read == payload.size) payload else payload.copyOf(read)
                Id3Blob(version = version, flags = flags, payload = effective)
            }
        }.getOrNull() ?: return null

        val payload = if ((bytes.flags and 0x80) != 0) removeUnsynchronization(bytes.payload) else bytes.payload
        val frameStart = skipId3ExtendedHeader(payload, bytes.version)
        if (frameStart < 0 || frameStart >= payload.size) return null
        return parseId3FramesForLyrics(payload, frameStart, bytes.version)
    }

    private fun readEmbeddedLyricsFromFlacVorbis(uri: Uri): LyricLoadResult? {
        return runCatching {
            contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                if (!seekToFlacStream(input)) return@use null

                while (true) {
                    val header = ByteArray(4)
                    if (!readFully(input, header)) return@use null
                    val isLast = (header[0].toInt() and 0x80) != 0
                    val blockType = header[0].toInt() and 0x7F
                    val blockLength =
                        ((header[1].toInt() and 0xFF) shl 16) or
                        ((header[2].toInt() and 0xFF) shl 8) or
                        (header[3].toInt() and 0xFF)

                    if (blockLength < 0) return@use null
                    if (blockType == 4) {
                        val block = ByteArray(blockLength)
                        if (!readFully(input, block)) return@use null
                        val parsed = parseFlacVorbisCommentForLyrics(block)
                        if (parsed != null && !parsed.isNullOrBlank()) return@use parsed
                    } else {
                        if (!skipFully(input, blockLength.toLong())) return@use null
                    }

                    if (isLast) break
                }
                null
            }
        }.getOrNull()
    }

    private fun seekToFlacStream(input: InputStream): Boolean {
        val marker = ByteArray(4)
        if (!readFully(input, marker)) return false

        val startsWithFlac =
            marker[0].toInt().toChar() == 'f' &&
            marker[1].toInt().toChar() == 'L' &&
            marker[2].toInt().toChar() == 'a' &&
            marker[3].toInt().toChar() == 'C'
        if (startsWithFlac) {
            // Cursor is now exactly at the first FLAC metadata block header.
            return true
        }

        val startsWithId3 =
            marker[0].toInt().toChar() == 'I' &&
            marker[1].toInt().toChar() == 'D' &&
            marker[2].toInt().toChar() == '3'
        if (!startsWithId3) return false

        val id3RestHeader = ByteArray(6)
        if (!readFully(input, id3RestHeader)) return false
        var tagSize =
            ((id3RestHeader[2].toInt() and 0x7F) shl 21) or
            ((id3RestHeader[3].toInt() and 0x7F) shl 14) or
            ((id3RestHeader[4].toInt() and 0x7F) shl 7) or
            (id3RestHeader[5].toInt() and 0x7F)
        if ((id3RestHeader[1].toInt() and 0x10) != 0) {
            tagSize += 10 // ID3 footer
        }

        if (!skipFully(input, tagSize.toLong())) return false

        val flac = ByteArray(4)
        if (!readFully(input, flac)) return false
        return flac[0].toInt().toChar() == 'f' &&
            flac[1].toInt().toChar() == 'L' &&
            flac[2].toInt().toChar() == 'a' &&
            flac[3].toInt().toChar() == 'C'
    }

    private fun parseFlacVorbisCommentForLyrics(block: ByteArray): LyricLoadResult? {
        if (block.size < 8) return null
        var cursor = 0

        val vendorLength = littleEndianInt(block, cursor)
        if (vendorLength < 0) return null
        cursor += 4
        if (cursor + vendorLength > block.size) return null
        cursor += vendorLength

        val commentCount = littleEndianInt(block, cursor)
        if (commentCount < 0) return null
        cursor += 4

        val directKeys = setOf(
            "LYRICS",
            "LYRIC",
            "UNSYNCEDLYRICS",
            "UNSYNCED_LYRICS",
            "UNSYNCED LYRICS",
            "SYNCEDLYRICS",
            "SYNCED LYRICS",
            "SYNCHRONIZEDLYRICS",
            "SYNCHRONIZED LYRICS",
            "LRC"
        )
        val keyNormalizeRegex = Regex("""[\s_\-]""")
        val normalizedDirectKeys = directKeys.mapTo(mutableSetOf()) {
            it.replace(keyNormalizeRegex, "")
        }

        var fallback: LyricLoadResult? = null
        repeat(commentCount.coerceAtMost(2048)) {
            if (cursor + 4 > block.size) return@repeat
            val len = littleEndianInt(block, cursor)
            cursor += 4
            if (len < 0 || cursor + len > block.size) return@repeat

            val entry = runCatching {
                String(block, cursor, len, Charsets.UTF_8)
            }.getOrNull().orEmpty()
            cursor += len

            val sep = entry.indexOf('=')
            if (sep <= 0 || sep >= entry.lastIndex) return@repeat
            val key = entry.substring(0, sep).trim().uppercase(Locale.ROOT)
            val normalizedKey = key.replace(keyNormalizeRegex, "")
            val value = entry.substring(sep + 1).trim()
            if (value.isBlank()) return@repeat

            if (key in directKeys || normalizedKey in normalizedDirectKeys) {
                return LyricLoadResult(value, "flac:vorbis:$key")
            }

            if (fallback == null && isLikelyLyricText(value) && key in setOf("COMMENT", "DESCRIPTION", "TEXT")) {
                fallback = LyricLoadResult(value, "flac:vorbis:$key")
            }
        }

        return fallback
    }

    private fun littleEndianInt(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return -1
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun isLikelyLyricText(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isBlank()) return false
        if (LRC_BRACKET_TOKEN_REGEX.findAll(normalized).any { parseFlexibleTimestamp(it.groupValues[1]) != null }) {
            return true
        }
        val lines = normalized.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.take(4).count()
        return lines >= 3
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n <= 0) return false
            total += n
        }
        return true
    }

    private fun skipFully(input: InputStream, byteCount: Long): Boolean {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (input.read() == -1) return false
            remaining--
        }
        return true
    }
    private fun parseId3FramesForLyrics(payload: ByteArray, start: Int, version: Int): LyricLoadResult? {
        var cursor = start
        while (cursor + 10 <= payload.size) {
            val id = String(payload, cursor, 4, Charsets.ISO_8859_1)
            if (id.all { it == '\u0000' }) break
            if (!id.all { it.isLetterOrDigit() }) break
            val frameSize = if (version >= 4) {
                synchsafeToInt(payload, cursor + 4)
            } else {
                bigEndianInt(payload, cursor + 4)
            }
            if (frameSize <= 0 || cursor + 10 + frameSize > payload.size) break
            val dataStart = cursor + 10
            val dataEnd = dataStart + frameSize
            val frame = payload.copyOfRange(dataStart, dataEnd)

            when (id) {
                "USLT" -> {
                    val lyric = parseUsltFrame(frame)
                    if (!lyric.isNullOrBlank()) return LyricLoadResult(lyric, "id3:USLT")
                }

                "SYLT" -> {
                    val lyric = parseSyltFrame(frame)
                    if (!lyric.isNullOrBlank()) return LyricLoadResult(lyric, "id3:SYLT")
                }
            }
            cursor = dataEnd
        }
        return null
    }

    private fun parseUsltFrame(frame: ByteArray): String? {
        if (frame.size <= 4) return null
        val encoding = frame[0].toInt() and 0xFF
        val descriptorEnd = findTextTerminator(frame, from = 4, encoding = encoding)
        if (descriptorEnd < 0 || descriptorEnd >= frame.lastIndex) return null
        val lyricStart = descriptorEnd + textTerminatorSize(encoding)
        if (lyricStart >= frame.size) return null
        return decodeTextBytes(frame, lyricStart, frame.size - lyricStart, encoding)?.trim()
    }

    private fun parseSyltFrame(frame: ByteArray): String? {
        if (frame.size <= 7) return null
        val encoding = frame[0].toInt() and 0xFF
        val descriptorEnd = findTextTerminator(frame, from = 6, encoding = encoding)
        if (descriptorEnd < 0) return null
        var cursor = descriptorEnd + textTerminatorSize(encoding)
        if (cursor >= frame.size) return null
        val lines = mutableListOf<String>()
        while (cursor < frame.size) {
            val textEnd = findTextTerminator(frame, from = cursor, encoding = encoding)
            if (textEnd < 0) break
            val text = decodeTextBytes(frame, cursor, textEnd - cursor, encoding).orEmpty().trim()
            cursor = textEnd + textTerminatorSize(encoding)
            if (cursor + 4 > frame.size) break
            cursor += 4 // skip timestamp
            if (text.isNotBlank()) lines += text
        }
        return if (lines.isEmpty()) null else lines.joinToString("\n")
    }

    private fun findTextTerminator(data: ByteArray, from: Int, encoding: Int): Int {
        if (from >= data.size) return -1
        return if (encoding == 1 || encoding == 2) {
            var i = from
            while (i + 1 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
                i += 2
            }
            -1
        } else {
            data.indexOfFirstFrom(from) { it.toInt() == 0 }
        }
    }

    private fun ByteArray.indexOfFirstFrom(from: Int, predicate: (Byte) -> Boolean): Int {
        var i = from.coerceAtLeast(0)
        while (i < size) {
            if (predicate(this[i])) return i
            i++
        }
        return -1
    }

    private fun textTerminatorSize(encoding: Int): Int = if (encoding == 1 || encoding == 2) 2 else 1

    private fun decodeTextBytes(data: ByteArray, start: Int, length: Int, encoding: Int): String? {
        if (start < 0 || length <= 0 || start + length > data.size) return null
        val charset: Charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charset.forName("UTF-16BE")
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return runCatching {
            String(data, start, length, charset)
        }.getOrNull()
    }

    private fun skipId3ExtendedHeader(payload: ByteArray, version: Int): Int {
        if (payload.isEmpty()) return -1
        if (version >= 4 && payload.size >= 4) {
            val possible = synchsafeToInt(payload, 0)
            if (possible in 6 until payload.size && payload[4].toInt() == 1) {
                return possible
            }
        }
        if (version == 3 && payload.size >= 4) {
            val possible = bigEndianInt(payload, 0)
            if (possible in 6 until payload.size) {
                return possible + 4
            }
        }
        return 0
    }

    private fun synchsafeToInt(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)
    }

    private fun bigEndianInt(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun removeUnsynchronization(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        val out = ByteArray(bytes.size)
        var outIndex = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            out[outIndex++] = b
            if (b.toInt() and 0xFF == 0xFF && i + 1 < bytes.size && bytes[i + 1].toInt() == 0) {
                i += 2
                continue
            }
            i++
        }
        return out.copyOf(outIndex)
    }

    private fun extractMetadataByFieldName(
        retriever: MediaMetadataRetriever,
        fieldName: String
    ): String? {
        val key = runCatching {
            MediaMetadataRetriever::class.java.getField(fieldName).getInt(null)
        }.getOrNull() ?: return null
        return runCatching { retriever.extractMetadata(key) }.getOrNull()
    }

    private fun readSidecarLrc(audioUri: Uri): String? {
        val pair = runCatching {
            contentResolver.query(
                audioUri,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME))
                val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH))
                if (name.isNullOrBlank() || path.isNullOrBlank()) null else name to path
            }
        }.getOrNull() ?: return null

        val baseName = pair.first.substringBeforeLast('.', pair.first)
        val lrcNames = linkedSetOf("$baseName.lrc", "$baseName.LRC")
        val filesUri = MediaStore.Files.getContentUri("external")
        val lrcUri = runCatching {
            var hit: Uri? = null
            for (name in lrcNames) {
                hit = contentResolver.query(
                    filesUri,
                    arrayOf(MediaStore.Files.FileColumns._ID),
                    "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME}=?",
                    arrayOf(pair.second, name),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                        ContentUris.withAppendedId(filesUri, id)
                    } else {
                        null
                    }
                }
                if (hit != null) break
            }
            hit
        }.getOrNull() ?: return null

        return runCatching {
            contentResolver.openInputStream(lrcUri)?.use { input ->
                decodeSidecarLyricBytes(input.readBytes())
            }
        }.getOrNull()
    }

    private fun decodeSidecarLyricBytes(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null

        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xFE
        ) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 &&
            (bytes[0].toInt() and 0xFF) == 0xFE &&
            (bytes[1].toInt() and 0xFF) == 0xFF
        ) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        val utf8 = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        val utf16Le = runCatching { String(bytes, Charsets.UTF_16LE) }.getOrNull()
        val utf16Be = runCatching { String(bytes, Charsets.UTF_16BE) }.getOrNull()
        return listOfNotNull(utf8, utf16Le, utf16Be)
            .maxByOrNull { scoreLyricDecodeCandidate(it) }
    }

    private fun scoreLyricDecodeCandidate(text: String): Int {
        if (text.isBlank()) return Int.MIN_VALUE
        val matches = LRC_BRACKET_TOKEN_REGEX.findAll(text).toList()
        val timedCount = matches.count { parseFlexibleTimestamp(it.groupValues[1]) != null }
        val metadataCount = matches.count { LRC_METADATA_TOKEN_REGEX.matches(it.groupValues[1].trim()) }
        val replacementCount = text.count { it == '\uFFFD' }
        val nullCount = text.count { it == '\u0000' }
        return timedCount * 100 + metadataCount * 3 - replacementCount * 12 - nullCount * 8
    }

    private fun parseLrcLines(lrcText: String?): List<LyricLine> {
        if (lrcText.isNullOrBlank()) {
            return emptyList()
        }
        val normalized = normalizeLyricPayload(lrcText)
        if (normalized.isBlank()) {
            return emptyList()
        }
        val result = mutableListOf<LyricLine>()
        val offsetMs = extractLrcOffsetMs(normalized)
        normalized.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEach
            val matches = LRC_BRACKET_TOKEN_REGEX.findAll(line).toList()
            if (matches.isEmpty()) return@forEach

            val times = mutableListOf<Long>()
            val removableRanges = mutableListOf<IntRange>()
            for (match in matches) {
                val token = match.groupValues[1].trim()
                val parsed = parseFlexibleTimestamp(token)
                when {
                    parsed != null -> {
                        times += (parsed + offsetMs).coerceAtLeast(0L)
                        removableRanges += match.range
                    }
                    LRC_METADATA_TOKEN_REGEX.matches(token) -> removableRanges += match.range
                }
            }
            if (times.isEmpty()) return@forEach

            var content = line
            removableRanges
                .sortedByDescending { it.first }
                .forEach { range ->
                    content = content.removeRange(range)
                }
            content = sanitizeLyricText(content).trim()
            if (content.isBlank()) return@forEach
            val (original, translation) = splitLyricTranslation(content)
            if (original.isBlank()) return@forEach
            for (timeMs in times) {
                result += LyricLine(timeMs = timeMs, originalText = original, translatedText = translation)
            }
        }
        if (result.isNotEmpty()) {
            return result.sortedBy { it.timeMs }
        }

        // Fallback: support unsynced lyric tags (plain lines without timestamps).
        val plainLines = normalized.lineSequence()
            .map { it.trim() }
            .map { it.replace(LRC_METADATA_INLINE_REGEX, "").trim() }
            .map { sanitizeLyricText(it).trim() }
            .filter { it.isNotBlank() && !LRC_METADATA_LINE_REGEX.matches(it) }
            .toList()
        if (plainLines.isEmpty()) {
            return emptyList()
        }
        val stepMs = 3_500L
        return plainLines.mapIndexed { index, text ->
            val (original, translation) = splitLyricTranslation(text)
            LyricLine(
                timeMs = index * stepMs,
                originalText = original,
                translatedText = translation
            )
        }
    }

    private fun splitLyricTranslation(content: String): Pair<String, String?> {
        val separators = listOf(
            " ⟂ ",
            " ⫽ ",
            " || ",
            " // ",
            " ｜ ",
            "\u2009",
            "\u200A",
            "\u202F",
            "\u205F",
            "|",
            "｜"
        )
        val normalized = content.trim()
        for (separator in separators) {
            val index = normalized.lastIndexOf(separator)
            if (index <= 0 || index >= normalized.lastIndex) {
                continue
            }
            val original = normalized.substring(0, index).trim()
            val translated = normalized.substring(index + separator.length).trim()
            if (original.isNotBlank() && translated.isNotBlank()) {
                return original to translated
            }
        }
        return normalized to null
    }

    private fun normalizeLyricTranslations(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return lines
        return lines.map { line ->
            if (!line.translatedText.isNullOrBlank()) {
                line
            } else {
                val (original, translation) = splitLyricTranslation(line.originalText)
                if (translation.isNullOrBlank()) {
                    line
                } else {
                    line.copy(originalText = original, translatedText = translation)
                }
            }
        }
    }

    private fun normalizeLyricPayload(raw: String): String {
        var text = raw
            .replace('\uFEFF', ' ')
        text = if (looksLikeInterleavedUtf16(text)) {
            text.replace("\u0000", "")
        } else {
            text.replace('\u0000', '\n')
        }
        text = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u3010', '[')
            .replace('\u3011', ']')
            .replace('\uFF3B', '[')
            .replace('\uFF3D', ']')
            .trim()
        text = sanitizeLyricText(text)
        text = text.replace(
            Regex("""<(\d{1,2}:\d{1,2}(?::\d{1,2})?(?:[.:]\d{1,3})?)>"""),
            "[$1]"
        )
        return stripLikelyUsltHeader(text).trim()
    }

    private fun looksLikeInterleavedUtf16(text: String): Boolean {
        if (text.isEmpty()) return false
        val nullCount = text.count { it == '\u0000' }
        if (nullCount < 8) return false
        val ratio = nullCount.toFloat() / text.length.toFloat()
        return ratio >= UTF16_NULL_RATIO_THRESHOLD
    }

    private fun sanitizeLyricText(input: String): String = buildString(input.length) {
        input.forEach { ch ->
            when {
                ch == '\n' || ch == '\t' -> append(ch)
                Character.isISOControl(ch) -> append(' ')
                else -> append(ch)
            }
        }
    }

    private fun extractLrcOffsetMs(text: String): Long {
        val match = LRC_OFFSET_REGEX.find(text)
        return match?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
    }

    private fun parseFlexibleTimestamp(token: String): Long? {
        val normalized = token
            .trim()
            .removePrefix("(")
            .removeSuffix(")")
            .replace(Regex("""\s+"""), "")
            .replace('\uFF1A', ':')
            .replace('\uFE55', ':')
            .replace('\uFF0C', '.')
            .replace('\u3002', '.')
            .replace('\uFF0E', '.')
            .replace('\uFE52', '.')
            .replace(',', '.')
        val match = LRC_TIMESTAMP_TOKEN_REGEX.matchEntire(normalized)
            ?: return null

        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        if (seconds !in 0L..59L) return null
        if (hours > 0L && minutes !in 0L..59L) return null

        val fractionRaw = match.groupValues[4]
        val fractionMs = when (fractionRaw.length) {
            0 -> 0L
            1 -> fractionRaw.toLong() * 100L
            2 -> fractionRaw.toLong() * 10L
            else -> fractionRaw.take(3).toLong()
        }
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + fractionMs
    }

    private fun stripLikelyUsltHeader(text: String): String {
        if (text.length < 6) {
            return text
        }
        val first = text[0].code
        if (first !in 0..3) {
            return text
        }
        if (!text.substring(1, 4).all { it.isLetter() }) {
            return text
        }
        val payloadStart = text.indexOf('\n', startIndex = 4)
        if (payloadStart <= 0 || payloadStart >= text.lastIndex) {
            return text
        }
        return text.substring(payloadStart + 1)
    }

    private fun findCurrentLyricIndex(positionMs: Long, lyrics: List<LyricLine>): Int {
        if (lyrics.isEmpty()) return -1
        var low = 0
        var high = lyrics.lastIndex
        var ans = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lyrics[mid].timeMs <= positionMs) {
                ans = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return ans
    }

    private data class LyricLine(
        val timeMs: Long,
        val originalText: String,
        val translatedText: String? = null
    ) {
        fun displayText(mode: LyricsTranslationMode): String {
            return if (mode == LyricsTranslationMode.ORIGINAL_WITH_TRANSLATION && !translatedText.isNullOrBlank()) {
                "$originalText\n$translatedText"
            } else {
                originalText
            }
        }
    }

    private data class LyricLoadResult(
        val text: String?,
        val source: String
    ) {
        fun isNullOrBlank(): Boolean = text.isNullOrBlank()
    }

    private data class Id3Blob(
        val version: Int,
        val flags: Int,
        val payload: ByteArray
    )

    private fun showWaveformPreview(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) {
            return
        }
        val ratio = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        binding.waveformPreviewView.setScrubRatio(ratio)
        updateWaveformPreviewTime(positionMs, durationMs)
        positionWaveformPreviewCard()
        if (binding.waveformPreviewCard.visibility != View.VISIBLE) {
            binding.waveformPreviewCard.alpha = 0f
            binding.waveformPreviewCard.visibility = View.VISIBLE
            binding.waveformPreviewCard.animate().alpha(1f).setInterpolator(standardInterpolator).setDuration(120L).start()
        }
    }

    private fun hideWaveformPreview() {
        if (binding.waveformPreviewCard.visibility != View.VISIBLE) {
            return
        }
        binding.waveformPreviewCard.animate()
            .alpha(0f)
            .setInterpolator(standardInterpolator)
            .setDuration(120L)
            .withEndAction {
                binding.waveformPreviewCard.visibility = View.GONE
                binding.waveformPreviewCard.alpha = 1f
            }
            .start()
    }

    private fun updateWaveformPreviewTime(positionMs: Long, durationMs: Long) {
        binding.textWaveformPreviewTime.text = "${positionMs.toTimeString()} / ${durationMs.toTimeString()}"
    }

    private fun positionWaveformPreviewCard() {
        binding.root.post {
            if (binding.waveformPreviewCard.visibility != View.VISIBLE) {
                return@post
            }
            val sliderPos = IntArray(2)
            val rootPos = IntArray(2)
            binding.progressSlider.getLocationOnScreen(sliderPos)
            binding.root.getLocationOnScreen(rootPos)
            val targetY = sliderPos[1] - rootPos[1] - binding.waveformPreviewCard.height - dp(12f)
            val minY = statusBarInsetPx + dp(8f)
            binding.waveformPreviewCard.translationY = max(minY, targetY).toFloat()
        }
    }

    private fun animateCollapseToMiniPlayerAndFinish() {
        if (collapsing) return
        val target = PlayerTransitionState.miniPlayerRectOnScreen
        if (target == null) {
            finish()
            overridePendingTransition(0, 0)
            return
        }
        collapsing = true
        hideWaveformPreview()
        val loc = IntArray(2)
        binding.root.getLocationOnScreen(loc)
        val sourceRectOnScreen = Rect(
            loc[0],
            loc[1],
            loc[0] + binding.root.width,
            loc[1] + binding.root.height
        )
        if (sourceRectOnScreen.width() <= 0 || sourceRectOnScreen.height() <= 0 || target.width() <= 0 || target.height() <= 0) {
            finish()
            overridePendingTransition(0, 0)
            return
        }
        val snapshot = runCatching {
            Bitmap.createBitmap(sourceRectOnScreen.width(), sourceRectOnScreen.height(), Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                binding.root.draw(canvas)
            }
        }.getOrNull() ?: run {
            finish()
            overridePendingTransition(0, 0)
            return
        }
        PlayerTransitionState.publishCollapseOverlay(
            PlayerTransitionState.CollapseOverlayPayload(
                snapshot = snapshot,
                sourceRectOnScreen = sourceRectOnScreen,
                targetRectOnScreen = target
            )
        )
        PlayerTransitionState.requestMainUiWarmup()
        finish()
        overridePendingTransition(0, 0)
    }

    private fun createGhostView(view: View, host: ViewGroup, hostLoc: IntArray): View? {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return null
        val bmp = runCatching {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                view.draw(canvas)
            }
        }.getOrNull() ?: return null
        val rect = rectInHost(view, hostLoc)
        return ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_XY
            x = rect.left.toFloat()
            y = rect.top.toFloat()
            layoutParams = FrameLayout.LayoutParams(rect.width(), rect.height())
            host.addView(this)
        }
    }

    private fun rectInHost(view: View, hostLoc: IntArray): Rect {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Rect(
            loc[0] - hostLoc[0],
            loc[1] - hostLoc[1],
            loc[0] - hostLoc[0] + view.width,
            loc[1] - hostLoc[1] + view.height
        )
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction.coerceIn(0f, 1f)
    }

    private fun springEase(fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        val base = 1f - (1f - t).pow(2.25f)
        val oscillation = sin(t * Math.PI.toFloat() * 2.1f) * (1f - t) * 0.14f
        return (base + oscillation).coerceIn(0f, 1.1f)
    }

    private fun updateControlIcons(state: PlaybackUiState) {
        val active = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val inactive = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)

        binding.buttonShuffle.iconTint = ColorStateList.valueOf(if (state.shuffleEnabled) active else inactive)
        binding.buttonRepeatMode.iconTint = ColorStateList.valueOf(
            if (state.repeatMode == Player.REPEAT_MODE_OFF) inactive else active
        )
        binding.buttonSleepTimer.iconTint = ColorStateList.valueOf(
            if (state.sleepTimerRemainingMs > 0L) active else inactive
        )

        binding.buttonRepeatMode.setIconResource(
            when (state.repeatMode) {
                Player.REPEAT_MODE_ONE -> android.R.drawable.ic_menu_revert
                Player.REPEAT_MODE_ALL -> android.R.drawable.ic_menu_rotate
                else -> android.R.drawable.ic_menu_rotate
            }
        )
    }

    private fun dp(value: Float): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun setupImmersiveUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.navigationBarColor = Color.TRANSPARENT
        }
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = false

        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollPlayer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarInsetPx = systemBars.top
            view.setPadding(
                view.paddingLeft,
                systemBars.top + dp(6f),
                view.paddingRight,
                systemBars.bottom + dp(18f)
            )
            insets
        }
    }

    private fun setupBackgroundEffects() {
        applyBackgroundBlur(backgroundBlurStrength)
    }

    private fun applyBackgroundBlur(strength: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        val radius = (strength.coerceIn(0, 100) / 100f) * 120f
        binding.imagePlayerBg.setRenderEffect(
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        )
    }

    private fun ensureDynamicBackgroundTiled() {
        if (!backgroundDynamicEnabled) {
            return
        }
        val current = binding.imagePlayerBg.drawable ?: return
        val bitmapCurrent = current as? BitmapDrawable
        val alreadyTiled = bitmapCurrent?.tileModeY == Shader.TileMode.REPEAT &&
            binding.imagePlayerBg.scaleType == ImageView.ScaleType.MATRIX
        if (alreadyTiled) {
            return
        }
        val tileSize = dp(160f).coerceAtLeast(64)
        val bitmap = current.toBitmap(width = tileSize, height = tileSize, config = Bitmap.Config.ARGB_8888)
        val tiled = BitmapDrawable(resources, bitmap).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        binding.imagePlayerBg.scaleType = ImageView.ScaleType.MATRIX
        binding.imagePlayerBg.setImageDrawable(tiled)
        startBackgroundDynamicScroll()
    }

    private fun startBackgroundDynamicScroll() {
        if (!backgroundDynamicEnabled || backgroundScrollAnimator?.isRunning == true) {
            return
        }
        val travelY = dp(180f).toFloat()
        backgroundScrollAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                backgroundScrollMatrix.reset()
                backgroundScrollMatrix.setTranslate(0f, travelY * t)
                binding.imagePlayerBg.imageMatrix = backgroundScrollMatrix
            }
            start()
        }
    }

    private fun stopBackgroundDynamicScroll() {
        backgroundScrollAnimator?.cancel()
        backgroundScrollAnimator = null
        backgroundScrollMatrix.reset()
        binding.imagePlayerBg.imageMatrix = backgroundScrollMatrix
    }

    private fun playEnterAnimation() {
        val startOnScreen = PlayerTransitionState.miniPlayerRectOnScreen
        if (startOnScreen == null) {
            entering = false
            val content = binding.root
            content.visibility = View.VISIBLE
            content.alpha = 0f
            content.translationY = 22f
            content.animate()
                .alpha(1f)
                .translationY(0f)
                .setInterpolator(standardInterpolator)
                .setDuration(260L)
                .start()
            return
        }

        binding.root.post {
            val content = binding.root
            val overlayHost = findViewById<ViewGroup>(android.R.id.content) ?: run {
                entering = false
                content.visibility = View.VISIBLE
                content.alpha = 1f
                return@post
            }
            entering = true
            val hostLoc = IntArray(2)
            overlayHost.getLocationOnScreen(hostLoc)
            val endRect = rectInHost(content, hostLoc)
            val startRect = Rect(
                startOnScreen.left - hostLoc[0],
                startOnScreen.top - hostLoc[1],
                startOnScreen.right - hostLoc[0],
                startOnScreen.bottom - hostLoc[1]
            )
            if (startRect.width() <= 0 || startRect.height() <= 0 || endRect.width() <= 0 || endRect.height() <= 0) {
                entering = false
                content.visibility = View.VISIBLE
                content.alpha = 1f
                return@post
            }

            val duration = 300L
            val startCorner = dp(20f).toFloat()
            val endCorner = dp(2f).toFloat()
            var currentCorner = startCorner
            val rootStartLeft = (startRect.left - endRect.left).coerceIn(0, endRect.width())
            val rootStartTop = (startRect.top - endRect.top).coerceIn(0, endRect.height())
            val rootStartRight = (rootStartLeft + startRect.width()).coerceIn(1, endRect.width())
            val rootStartBottom = (rootStartTop + startRect.height()).coerceIn(1, endRect.height())
            var liveClipRect = Rect(rootStartLeft, rootStartTop, rootStartRight, rootStartBottom)
            content.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            content.clipToOutline = true
            content.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(liveClipRect, currentCorner)
                }
            }
            content.clipBounds = Rect(liveClipRect)
            content.invalidateOutline()
            content.visibility = View.VISIBLE
            content.alpha = 1f

            ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                this.interpolator = standardInterpolator
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    val w = (startRect.width() + (endRect.width() - startRect.width()) * t).toInt().coerceAtLeast(1)
                    val h = (startRect.height() + (endRect.height() - startRect.height()) * t).toInt().coerceAtLeast(1)
                    val x = startRect.left + (endRect.left - startRect.left) * t
                    val y = startRect.top + (endRect.top - startRect.top) * t

                    currentCorner = startCorner + (endCorner - startCorner) * t
                    val localLeft = (x - endRect.left).toInt().coerceIn(0, endRect.width() - 1)
                    val localTop = (y - endRect.top).toInt().coerceIn(0, endRect.height() - 1)
                    val localRight = (localLeft + w).coerceIn(localLeft + 1, endRect.width())
                    val localBottom = (localTop + h).coerceIn(localTop + 1, endRect.height())
                    liveClipRect = Rect(localLeft, localTop, localRight, localBottom)
                    content.clipBounds = Rect(liveClipRect)
                    content.invalidateOutline()
                }
                doOnEnd {
                    content.clipBounds = null
                    content.clipToOutline = false
                    content.outlineProvider = ViewOutlineProvider.BOUNDS
                    content.setLayerType(View.LAYER_TYPE_NONE, null)
                    entering = false
                }
                start()
            }
        }
    }

    private fun isReadyToReleaseEnterMask(): Boolean {
        if (!hasRenderedStateAtLeastOnce) {
            return false
        }
        val artworkKey = latestArtworkRequestKey
        if (artworkKey.isNullOrBlank()) {
            return true
        }

        val coverApplied = binding.imageCover.getTag(R.id.tag_artwork_applied_uri) as? String
        val bgApplied = binding.imagePlayerBg.getTag(R.id.tag_artwork_applied_uri) as? String
        if (coverApplied == artworkKey && bgApplied == artworkKey) {
            return true
        }

        val waited = android.os.SystemClock.uptimeMillis() - enterMaskReadyAtUptimeMs
        return waited >= 320L
    }

    private fun releaseEnterMaskOverlay(immediate: Boolean) {
        pendingEnterMaskRelease = false
        val overlay = activeEnterMaskOverlay ?: run {
            entering = false
            return
        }
        if (!immediate && !enterMaskFadeOutInProgress) {
            enterMaskFadeOutInProgress = true
            overlay.maskView.animate()
                .alpha(0f)
                .setDuration(110L)
                .setInterpolator(standardInterpolator)
                .withEndAction {
                    val current = activeEnterMaskOverlay
                    if (current != null && current.maskView === overlay.maskView) {
                        current.maskView.setLayerType(View.LAYER_TYPE_NONE, null)
                        current.host.removeView(current.maskView)
                        current.snapshot.recycle()
                        activeEnterMaskOverlay = null
                    }
                    enterMaskFadeOutInProgress = false
                    entering = false
                }
                .start()
            return
        }
        overlay.maskView.animate().cancel()
        overlay.maskView.setLayerType(View.LAYER_TYPE_NONE, null)
        overlay.host.removeView(overlay.maskView)
        overlay.snapshot.recycle()
        activeEnterMaskOverlay = null
        enterMaskFadeOutInProgress = false
        entering = false
    }

    private fun applyPrewarmedStateIfAvailable() {
        val prewarmed = PlayerTransitionState.consumePrewarmedPlaybackState() ?: return
        renderState(prewarmed)
    }

    private fun applyLyricEdgeBlur(view: LyricMaskTextView, expanded: Boolean, isCurrent: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val rv = binding.recyclerLyrics
        val viewportHeight = rv.height - rv.paddingTop - rv.paddingBottom
        if (!expanded || viewportHeight <= 0 || isCurrent) {
            view.setRenderEffect(null)
            return
        }

        val centerY = rv.paddingTop + viewportHeight * 0.5f
        val clearBandHalf = (viewportHeight * 0.28f).coerceAtLeast(dp(34f).toFloat())
        val edgeSpan = (viewportHeight * 0.5f - clearBandHalf).coerceAtLeast(1f)
        val childCenterY = view.top + view.translationY + view.height * 0.5f
        val distanceFromCenter = abs(childCenterY - centerY)
        val raw = ((distanceFromCenter - clearBandHalf) / edgeSpan).coerceIn(0f, 1f)
        val eased = raw * raw * (3f - 2f * raw)
        val maxRadius = dp(15f).toFloat()
        val radius = maxRadius * eased

        if (radius < 0.3f) {
            view.setRenderEffect(null)
            return
        }
        view.setRenderEffect(
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        )
    }

    private fun updateLyricsViewportEdgeBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val rv = binding.recyclerLyrics
        for (i in 0 until rv.childCount) {
            val lyricView = rv.getChildAt(i) as? LyricMaskTextView ?: continue
            val isCurrent = lyricView.getTag(R.id.tag_lyric_is_current) == true
            applyLyricEdgeBlur(lyricView, expanded = lyricsExpanded, isCurrent = isCurrent)
        }
    }

    private inner class LyricsListAdapter(
        private val onLineClick: (Int) -> Unit
    ) : RecyclerView.Adapter<LyricsListAdapter.LyricViewHolder>() {
        private val payloadTranslationMode = "payload_translation_mode"

        private var items: List<LyricLine> = emptyList()
        private var currentIndex: Int = -1
        private var currentProgress: Float = 0f
        private var currentProgressRatePerSec: Float = 0f
        private var expanded: Boolean = false
        private var lyricFontSizeSp: Int = lyricsFontSizeSp
        private var lyricGlowIntensityPercent: Int = lyricsGlowIntensityPercent
        private var lyricBoldEnabled: Boolean = lyricsBoldEnabled
        private var translationMode: LyricsTranslationMode = lyricsTranslationMode

        fun submitLyrics(lines: List<LyricLine>) {
            items = lines
            lyricLineOffsetsPx.keys.retainAll(items.indices.toSet())
            lyricLineVelocitiesPxPerSec.keys.retainAll(items.indices.toSet())
            if (currentIndex !in items.indices) {
                currentIndex = if (items.isEmpty()) -1 else 0
                currentProgress = 0f
                currentProgressRatePerSec = 0f
            }
            notifyDataSetChanged()
            binding.recyclerLyrics.post { updateLyricsViewportEdgeBlur() }
        }

        fun setExpanded(value: Boolean) {
            if (expanded == value) return
            expanded = value
            if (!expanded) {
                resetLyricInertiaTransforms()
            }
            notifyDataSetChanged()
        }

        fun setTranslationMode(mode: LyricsTranslationMode, animate: Boolean = false) {
            if (translationMode == mode) return
            translationMode = mode
            if (items.isEmpty()) {
                notifyDataSetChanged()
                return
            }
            if (animate && expanded) {
                val kick = if (mode == LyricsTranslationMode.ORIGINAL_WITH_TRANSLATION) -dp(128f) else dp(112f)
                injectLyricSpringKick(kick)
                notifyItemRangeChanged(0, items.size, payloadTranslationMode)
            } else {
                notifyDataSetChanged()
            }
        }

        fun setLyricFontSizeSp(value: Int) {
            val normalized = value.coerceIn(12, 40)
            if (lyricFontSizeSp == normalized) return
            lyricFontSizeSp = normalized
            notifyDataSetChanged()
            binding.recyclerLyrics.post { updateLyricsViewportEdgeBlur() }
        }

        fun setLyricGlowIntensityPercent(value: Int) {
            val normalized = value.coerceIn(0, 100)
            if (lyricGlowIntensityPercent == normalized) return
            lyricGlowIntensityPercent = normalized
            notifyDataSetChanged()
        }

        fun setLyricsBoldEnabled(enabled: Boolean) {
            if (lyricBoldEnabled == enabled) return
            lyricBoldEnabled = enabled
            notifyDataSetChanged()
        }

        fun updatePlayback(currentIndex: Int, progress: Float, progressRatePerSec: Float) {
            val oldIndex = this.currentIndex
            this.currentIndex = currentIndex
            this.currentProgress = progress.coerceIn(0f, 1f)
            this.currentProgressRatePerSec = progressRatePerSec.coerceAtLeast(0f)
            if (oldIndex != this.currentIndex) {
                if (oldIndex in items.indices) notifyItemChanged(oldIndex)
                if (this.currentIndex in items.indices) notifyItemChanged(this.currentIndex)
                val start = (this.currentIndex - 5).coerceAtLeast(0)
                val end = (this.currentIndex + 5).coerceAtMost(items.lastIndex)
                if (start <= end) notifyItemRangeChanged(start, end - start + 1)
            } else if (this.currentIndex in items.indices) {
                notifyItemChanged(this.currentIndex)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_player_lyric_line, parent, false) as LyricMaskTextView
            return LyricViewHolder(view)
        }

        override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
            holder.bind(position, animateTranslationModeChange = false)
        }

        override fun onBindViewHolder(holder: LyricViewHolder, position: Int, payloads: MutableList<Any>) {
            val animateModeChange = payloads.any { it == payloadTranslationMode }
            holder.bind(position, animateTranslationModeChange = animateModeChange)
        }

        override fun onViewRecycled(holder: LyricViewHolder) {
            holder.cancelLineAnimator()
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = items.size

        inner class LyricViewHolder(
            private val textView: LyricMaskTextView
        ) : RecyclerView.ViewHolder(textView) {
            private var lineAnimator: ValueAnimator? = null
            private var lastShowTranslation: Boolean? = null

            fun bind(position: Int, animateTranslationModeChange: Boolean) {
                val line = items[position]
                val isCurrent = position == currentIndex
                val distance = abs(position - currentIndex)
                val baseAlpha = when (distance) {
                    0 -> 0.98f
                    1 -> 0.8f
                    2 -> 0.6f
                    3 -> 0.43f
                    4 -> 0.33f
                    else -> 0.26f
                }
                val backwardPenalty = if (position < currentIndex) 0.08f else 0f
                val currentLift = if (isCurrent) 0.02f + currentProgress.pow(0.62f) * 0.08f else 0f
                textView.alpha = (baseAlpha - backwardPenalty + currentLift).coerceIn(0.2f, 1f)
                val showTranslation = translationMode == LyricsTranslationMode.ORIGINAL_WITH_TRANSLATION
                val oldHeight = textView.height.takeIf { it > 0 } ?: textView.measuredHeight.takeIf { it > 0 } ?: 0
                val oldSpacing = textView.lineSpacingExtra
                textView.maxLines = if (expanded) {
                    if (showTranslation) 6 else 4
                } else {
                    1
                }
                val displayText = line.displayText(translationMode)
                textView.text = displayText
                textView.textSize = lyricFontSizeSp.toFloat()
                textView.typeface = if (lyricBoldEnabled) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                textView.setGlowIntensityPercent(lyricGlowIntensityPercent)
                val targetSpacing = if (expanded && showTranslation) dp(3f).toFloat() else 0f
                textView.setLineSpacing(targetSpacing, 1f)
                textView.setProgressSections(
                    originalText = line.originalText,
                    translatedText = line.translatedText,
                    bilingualEnabled = showTranslation
                )
                textView.setLyricFocusState(
                    isCurrent = isCurrent,
                    distance = distance,
                    expanded = expanded
                )
                textView.setLyricProgress(
                    if (isCurrent) currentProgress else 0f,
                    if (isCurrent) currentProgressRatePerSec else 0f
                )
                textView.setTag(R.id.tag_lyric_is_current, isCurrent)
                textView.setTag(R.id.tag_lyric_distance, distance)
                applyLyricEdgeBlur(textView, expanded = expanded, isCurrent = isCurrent)
                val baseOffset = lyricLineOffsetsPx[position] ?: 0f
                val modeChanged = lastShowTranslation != null && lastShowTranslation != showTranslation
                if (animateTranslationModeChange && modeChanged && expanded && oldHeight > 0 && textView.width > 0) {
                    animateLineMorph(
                        startHeight = oldHeight,
                        startSpacing = oldSpacing,
                        endSpacing = targetSpacing,
                        baseOffset = baseOffset,
                        showingTranslation = showTranslation
                    )
                } else {
                    lineAnimator?.cancel()
                    lineAnimator = null
                    textView.layoutParams = textView.layoutParams.apply {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    textView.translationY = baseOffset
                }
                lastShowTranslation = showTranslation
                textView.setOnClickListener {
                    val adapterPos = bindingAdapterPosition
                    if (adapterPos == RecyclerView.NO_POSITION) return@setOnClickListener
                    onLineClick(adapterPos)
                }
            }

            private fun animateLineMorph(
                startHeight: Int,
                startSpacing: Float,
                endSpacing: Float,
                baseOffset: Float,
                showingTranslation: Boolean
            ) {
                lineAnimator?.cancel()
                textView.measure(
                    View.MeasureSpec.makeMeasureSpec(textView.width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val targetHeight = textView.measuredHeight.takeIf { it > 0 } ?: startHeight
                if (targetHeight <= 0 || startHeight <= 0 || targetHeight == startHeight) {
                    textView.layoutParams = textView.layoutParams.apply {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    textView.translationY = baseOffset
                    textView.setLineSpacing(endSpacing, 1f)
                    return
                }
                val travel = if (showingTranslation) dp(11f).toFloat() else -dp(9f).toFloat()
                textView.layoutParams = textView.layoutParams.apply {
                    height = startHeight
                }
                textView.requestLayout()
                lineAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 340L
                    interpolator = LinearInterpolator()
                    addUpdateListener { animator ->
                        val t = springEase(animator.animatedFraction)
                        val nextHeight = (startHeight + (targetHeight - startHeight) * t)
                            .toInt()
                            .coerceAtLeast(1)
                        textView.layoutParams = textView.layoutParams.apply {
                            height = nextHeight
                        }
                        textView.translationY = baseOffset + travel * (1f - t).coerceIn(0f, 1f)
                        val spacing = startSpacing + (endSpacing - startSpacing) * t
                        textView.setLineSpacing(spacing, 1f)
                        textView.requestLayout()
                    }
                    doOnEnd {
                        textView.layoutParams = textView.layoutParams.apply {
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                        textView.translationY = baseOffset
                        textView.setLineSpacing(endSpacing, 1f)
                        lineAnimator = null
                    }
                    start()
                }
            }

            fun cancelLineAnimator() {
                lineAnimator?.cancel()
                lineAnimator = null
                textView.layoutParams = textView.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
        }
    }

    private data class EnterMaskOverlay(
        val host: ViewGroup,
        val maskView: FrameLayout,
        val snapshot: Bitmap
    )
}
