package com.example.hifx

import android.content.Intent
import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hifx.audio.AudioEngine
import com.example.hifx.audio.PlaybackUiState
import com.example.hifx.audio.SettingsUiState
import com.example.hifx.audio.TopBarVisualizationMode
import com.example.hifx.databinding.ActivityMainBinding
import com.example.hifx.databinding.LayoutMiniPlayerBinding
import com.example.hifx.ui.PlayerTransitionState
import com.example.hifx.util.AppHaptics
import com.example.hifx.util.loadArtworkOrDefault
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private data class BannerTextModel(
        val left: String,
        val center: String,
        val right: String,
        val accent: Int
    )

    companion object {
        private const val EXTRA_OPEN_TARGET = "extra_open_target"
        private const val EXTRA_TARGET_NAME = "extra_target_name"
        private const val TARGET_ARTIST = "artist"
        private const val TARGET_ALBUM = "album"

        fun createOpenArtistIntent(context: android.content.Context, artistName: String): Intent {
            return Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_TARGET, TARGET_ARTIST)
                .putExtra(EXTRA_TARGET_NAME, artistName)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        fun createOpenAlbumIntent(context: android.content.Context, albumName: String): Intent {
            return Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_TARGET, TARGET_ALBUM)
                .putExtra(EXTRA_TARGET_NAME, albumName)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var miniPlayerBinding: LayoutMiniPlayerBinding
    private var miniProgressUserSeeking = false
    private var latestDurationMs: Long = 0L
    private var latestPlaybackState: PlaybackUiState = PlaybackUiState()
    private var miniCollapsedByGesture = false
    private var lastHasMedia = false
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var containerInsetAnimator: ValueAnimator? = null
    private var miniCardBaseBottomMargin = -1
    private var miniHandleBaseBottomMargin = -1
    private var miniSwipeTrackAnimating = false
    private var miniGestureFromCard = false
    private var suppressMiniCardClickOnce = false
    private var miniHorizontalSwitchTriggered = false
    private var bottomNavHiddenForKeyboard = false
    private var streamBannerVisible = false
    private val standardInterpolator = FastOutSlowInInterpolator()
    private var latestSettingsState: SettingsUiState = SettingsUiState()
    private var currentBannerTextModel: BannerTextModel? = null
    private var lastStableBannerTextModel: BannerTextModel? = null
    private var lastBannerMeasuredHeight = 0
    private val isoGoldColor = 0xFFB08A1E.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        miniPlayerBinding = LayoutMiniPlayerBinding.bind(
            binding.root.findViewById(R.id.mini_player)
        )

        setSupportActionBar(binding.toolbar)
        supportActionBar?.hide()
        supportFragmentManager.addOnBackStackChangedListener {
            updateTopBarTitleFromCurrentFragment()
            updateFragmentBottomInset(animated = false)
        }
        setupBottomNavigation()
        setupMiniPlayerActions()
        attachPlayerExitWarmupBridge()
        observeMiniPlayerState()
        observeTopBarSettings()
        binding.cardStreamBanner.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val newHeight = bottom - top
            val oldHeight = oldBottom - oldTop
            if (newHeight > 0 && newHeight != oldHeight && newHeight != lastBannerMeasuredHeight) {
                lastBannerMeasuredHeight = newHeight
                updateFragmentBottomInset(animated = true)
            }
        }
        binding.root.post {
            updateTopChromeOffsets()
            updateMiniPlayerBottomSpacing()
            updateFragmentBottomInset(animated = false)
        }

        if (savedInstanceState == null) {
            binding.bottomNav.menu.findItem(R.id.navigation_playback).isChecked = true
            if (!handleExternalNavigationIntent(intent)) {
                navigateByItem(R.id.navigation_playback)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleExternalNavigationIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        AudioEngine.refreshPlaybackStateNow()
        AudioEngine.requestExternalDacExclusiveAccess(this)
        if (PlayerTransitionState.consumeMainUiWarmupRequest()) {
            prewarmHomeUiForPlayerExit()
        }
        // Fallback: if overlay payload is already published, start it after Activity is visible.
        startPendingCollapseOverlayOnHome()
    }

    override fun onDestroy() {
        PlayerTransitionState.onMainUiWarmupRequested = null
        super.onDestroy()
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            AppHaptics.click(binding.bottomNav)
            navigateByItem(item.itemId)
        }
    }

    fun setBottomNavHiddenForKeyboard(hidden: Boolean) {
        if (bottomNavHiddenForKeyboard == hidden) return
        bottomNavHiddenForKeyboard = hidden
        binding.bottomNav.visibility = if (hidden) View.GONE else View.VISIBLE
        updateMiniPlayerBottomSpacing()
        updateFragmentBottomInset(animated = false)
    }

    private fun setupMiniPlayerActions() {
        miniPlayerBinding.miniPlayerCard.setOnClickListener {
            if (suppressMiniCardClickOnce) {
                suppressMiniCardClickOnce = false
                return@setOnClickListener
            }
            AppHaptics.click(it)
            openPlayerDetailWithAnimation()
        }
        miniPlayerBinding.viewMiniHandle.setOnClickListener {
            AppHaptics.click(it)
            setMiniCollapsedByGesture(collapsed = false, animated = true)
        }
        miniPlayerBinding.buttonMiniPlayPause.setOnClickListener {
            AppHaptics.click(it)
            AudioEngine.togglePlayPause()
        }
        miniPlayerBinding.buttonMiniNext.setOnClickListener {
            AppHaptics.click(it)
            AudioEngine.skipToNextTrack()
        }
        miniPlayerBinding.progressMini.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                miniProgressUserSeeking = true
                animateMiniProgressEmphasis(true)
            }

            override fun onStopTrackingTouch(slider: Slider) {
                miniProgressUserSeeking = false
                animateMiniProgressEmphasis(false)
                val duration = latestDurationMs
                if (duration > 0L) {
                    val ratio = (slider.value / 1000f).coerceIn(0f, 1f)
                    AudioEngine.seekTo((duration * ratio).toLong())
                }
            }
        })
        miniPlayerBinding.progressMini.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                updateMiniProgressFill((value / 1000f).coerceIn(0f, 1f))
            }
        }
    }

    private fun animateMiniPlayerTrackSwitch(next: Boolean) {
        if (miniSwipeTrackAnimating || !latestPlaybackState.hasMedia) {
            return
        }
        val canSwitch = if (next) latestPlaybackState.hasNext else latestPlaybackState.hasPrevious
        val card = miniPlayerBinding.miniPlayerCard
        if (!canSwitch) {
            val nudge = if (next) -dp(18f).toFloat() else dp(18f).toFloat()
            card.animate().cancel()
            card.animate()
                .translationX(nudge)
                .setInterpolator(standardInterpolator)
                .setDuration(70L)
                .withEndAction {
                    card.animate().translationX(0f)
                        .setInterpolator(standardInterpolator)
                        .setDuration(90L)
                        .start()
                }
                .start()
            return
        }

        miniSwipeTrackAnimating = true
        val direction = if (next) -1f else 1f
        val shift = maxOf(dp(64f).toFloat(), card.width * 0.34f)

        card.animate().cancel()
        card.animate()
            .translationX(direction * shift)
            .alpha(0.55f)
            .setInterpolator(standardInterpolator)
            .setDuration(120L)
            .withEndAction {
                if (next) {
                    AudioEngine.skipToNextTrack()
                } else {
                    AudioEngine.skipToPreviousTrack()
                }
                card.translationX = -direction * shift * 0.58f
                card.alpha = 0.55f
                card.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setInterpolator(standardInterpolator)
                    .setDuration(170L)
                    .withEndAction { miniSwipeTrackAnimating = false }
                    .start()
            }
            .start()
    }

    private fun observeMiniPlayerState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AudioEngine.playbackState.collect { state ->
                    renderMiniPlayer(state)
                    renderStreamBanner(state)
                }
            }
        }
    }

    private fun observeTopBarSettings() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AudioEngine.settingsState.collect { state ->
                    renderBannerPreferences(state)
                }
            }
        }
    }

    private fun renderBannerPreferences(state: SettingsUiState) {
        latestSettingsState = state
        renderStreamBanner(latestPlaybackState)
        updateTopChromeOffsets()
        updateFragmentBottomInset(animated = false)
    }

    private fun renderStreamBanner(state: PlaybackUiState) {
        val showInfoLayer = latestSettingsState.showStreamInfoEnabled
        val showVisualizerLayer = latestSettingsState.showVisualizationEnabled && state.hasMedia
        val card = binding.cardStreamBanner
        val useDirectDacTheme = state.streamInfoUseIsoDacTheme && latestSettingsState.directDacGoldThemeEnabled
        val accent = if (useDirectDacTheme) {
            0xFF111111.toInt()
        } else if (state.streamInfoUseDacAccent) {
            isoGoldColor
        } else {
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
        }
        val incomingTextModel = createBannerTextModel(
            left = state.streamInfoLeft,
            center = state.streamInfoCenter,
            right = state.streamInfoRight,
            accent = accent
        )
        if (incomingTextModel != null) {
            lastStableBannerTextModel = incomingTextModel
        }
        val effectiveTextModel = if (showInfoLayer) incomingTextModel ?: lastStableBannerTextModel else null
        val shouldShow = (showVisualizerLayer || effectiveTextModel != null) && state.hasMedia

        if (effectiveTextModel != null) {
            updateBannerTextContent(
                model = effectiveTextModel,
                animate = streamBannerVisible && card.visibility == View.VISIBLE
            )
        }
        binding.layoutStreamBannerText.visibility = if (effectiveTextModel != null) View.VISIBLE else View.GONE
        binding.viewStreamBannerVisualizer.applySettings(
            enabled = showVisualizerLayer,
            mode = latestSettingsState.topBarVisualizationMode
        )
        binding.viewStreamBannerVisualizer.setIsoDacTheme(useDirectDacTheme)
        applyTopChromeTheme(useDirectDacTheme)

        if (streamBannerVisible == shouldShow) {
            if (!shouldShow) {
                card.visibility = View.GONE
                currentBannerTextModel = null
            }
            updateFragmentBottomInset(animated = true)
            return
        }
        streamBannerVisible = shouldShow
        card.animate().cancel()
        if (shouldShow) {
            card.visibility = View.VISIBLE
            card.alpha = 0f
            card.translationY = -dp(4f).toFloat()
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setInterpolator(standardInterpolator)
                .setDuration(180L)
                .start()
            card.post { updateFragmentBottomInset(animated = true) }
        } else {
            card.animate()
                .alpha(0f)
                .translationY(-dp(4f).toFloat())
                .setInterpolator(standardInterpolator)
                .setDuration(140L)
                .withEndAction {
                    card.visibility = View.GONE
                    card.alpha = 1f
                    card.translationY = 0f
                    updateFragmentBottomInset(animated = true)
                }
                .start()
        }
    }

    private fun createBannerTextModel(
        left: String,
        center: String,
        right: String,
        accent: Int
    ): BannerTextModel? {
        val normalizedLeft = left.trim()
        val normalizedCenter = center.trim()
        val normalizedRight = right.trim()
        if (normalizedLeft.isEmpty() && normalizedCenter.isEmpty() && normalizedRight.isEmpty()) {
            return null
        }
        return BannerTextModel(
            left = normalizedLeft,
            center = normalizedCenter,
            right = normalizedRight,
            accent = accent
        )
    }

    private fun updateBannerTextContent(model: BannerTextModel, animate: Boolean) {
        if (currentBannerTextModel == model) {
            return
        }
        val content = binding.layoutStreamBannerText
        content.animate().cancel()
        if (!animate || content.visibility != View.VISIBLE) {
            applyBannerTextModel(model)
            content.alpha = 1f
            content.translationY = 0f
            currentBannerTextModel = model
            return
        }
        content.animate()
            .alpha(0f)
            .translationY(-dp(3f).toFloat())
            .setInterpolator(standardInterpolator)
            .setDuration(80L)
            .withEndAction {
                applyBannerTextModel(model)
                content.translationY = dp(3f).toFloat()
                content.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setInterpolator(standardInterpolator)
                    .setDuration(140L)
                    .withEndAction {
                        currentBannerTextModel = model
                    }
                    .start()
            }
            .start()
    }

    private fun applyBannerTextModel(model: BannerTextModel) {
        binding.textStreamBannerLeft.text = model.left
        binding.textStreamBannerCenter.text = model.center
        binding.textStreamBannerRight.text = model.right
        binding.textStreamBannerLeft.setTextColor(model.accent)
        binding.textStreamBannerCenter.setTextColor(model.accent)
        binding.textStreamBannerRight.setTextColor(model.accent)
    }

    private fun applyTopChromeTheme(useIsoDacTheme: Boolean) {
        val toolbarColor = if (useIsoDacTheme) {
            isoGoldColor
        } else {
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
        }
        val titleColor = if (useIsoDacTheme) 0xFF111111.toInt()
        else MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
        binding.appBar.setBackgroundColor(toolbarColor)
        binding.toolbar.setBackgroundColor(toolbarColor)
        binding.toolbar.setTitleTextColor(titleColor)
        binding.cardStreamBanner.setCardBackgroundColor(toolbarColor)
        window.statusBarColor = toolbarColor
        if (window.navigationBarColor != android.graphics.Color.TRANSPARENT) {
            window.navigationBarColor = toolbarColor
        }
        val navBackground = binding.bottomNav.background
        if (navBackground is ColorDrawable) {
            binding.bottomNav.setBackgroundColor(toolbarColor)
        }
    }

    private fun renderMiniPlayer(state: PlaybackUiState) {
        latestPlaybackState = state
        PlayerTransitionState.updatePrewarmedPlaybackState(state)
        val availabilityChanged = state.hasMedia != lastHasMedia
        lastHasMedia = state.hasMedia

        if (!state.hasMedia) {
            miniCollapsedByGesture = false
        }
        if (availabilityChanged) {
            applyMiniPlayerVisibility(animated = true)
            updateFragmentBottomInset(animated = true)
        }

        if (!state.hasMedia) {
            latestDurationMs = 0L
            updateMiniProgressFill(0f)
            PlayerTransitionState.miniPlayerRectOnScreen = null
            return
        }
        miniPlayerBinding.imageMiniCover.loadArtworkOrDefault(state.artworkUri)
        miniPlayerBinding.textMiniTitle.text = state.title
        miniPlayerBinding.textMiniSubtitle.text = state.subtitle
        miniPlayerBinding.buttonMiniPlayPause.setImageResource(
            if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        miniPlayerBinding.buttonMiniNext.isEnabled = state.hasNext
        miniPlayerBinding.buttonMiniNext.alpha = if (state.hasNext) 1f else 0.42f
        latestDurationMs = state.durationMs
        val hasDuration = state.durationMs > 0L
        miniPlayerBinding.progressMini.isEnabled = hasDuration
        if (hasDuration && !miniProgressUserSeeking) {
            val ratio = (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
            miniPlayerBinding.progressMini.value = (ratio * 1000f).roundToInt().toFloat()
        } else if (!hasDuration && !miniProgressUserSeeking) {
            miniPlayerBinding.progressMini.value = 0f
        }
        val fillRatio = when {
            !hasDuration -> 0f
            miniProgressUserSeeking -> (miniPlayerBinding.progressMini.value / 1000f).coerceIn(0f, 1f)
            else -> (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
        }
        updateMiniProgressFill(fillRatio)
        updateMiniPlayerTransitionState()
    }

    private fun updateMiniProgressFill(ratio: Float) {
        miniPlayerBinding.viewMiniProgressFill.pivotX = 0f
        miniPlayerBinding.viewMiniProgressFill.scaleX = ratio.coerceIn(0f, 1f)
    }

    private fun applyMiniPlayerVisibility(animated: Boolean) {
        val hasMedia = latestPlaybackState.hasMedia
        val shouldShow = hasMedia && !miniCollapsedByGesture
        val card = miniPlayerBinding.miniPlayerCard
        val handle = miniPlayerBinding.viewMiniHandle
        val collapsedTranslation = 46f * resources.displayMetrics.density
        val handleTranslation = 12f * resources.displayMetrics.density

        if (!animated) {
            card.animate().cancel()
            handle.animate().cancel()
            card.alpha = if (shouldShow) 1f else 0f
            card.translationY = if (shouldShow) 0f else collapsedTranslation
            card.visibility = if (shouldShow) View.VISIBLE else View.GONE
            val showHandle = hasMedia && !shouldShow
            handle.alpha = if (showHandle) 1f else 0f
            handle.translationY = if (showHandle) 0f else handleTranslation
            handle.visibility = if (showHandle) View.VISIBLE else View.GONE
            updateMiniPlayerTransitionState()
            return
        }

        if (shouldShow) {
            if (card.visibility != View.VISIBLE) {
                card.visibility = View.VISIBLE
                card.alpha = 0f
                card.translationY = collapsedTranslation
            }
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setInterpolator(standardInterpolator)
                .setDuration(180L)
                .start()
            if (handle.visibility == View.VISIBLE) {
                handle.animate()
                    .alpha(0f)
                    .translationY(handleTranslation)
                    .setInterpolator(standardInterpolator)
                    .setDuration(150L)
                    .withEndAction {
                        handle.visibility = View.GONE
                    }
                    .start()
            }
            return
        }

        if (!hasMedia) {
            card.visibility = View.GONE
            handle.visibility = View.GONE
            return
        }

        if (card.visibility == View.VISIBLE) {
            card.animate()
                .alpha(0f)
                .translationY(collapsedTranslation)
                .setInterpolator(standardInterpolator)
                .setDuration(180L)
                .withEndAction {
                    if (!(latestPlaybackState.hasMedia && !miniCollapsedByGesture)) {
                        card.visibility = View.GONE
                    }
                }
                .start()
        }
        if (handle.visibility != View.VISIBLE) {
            handle.visibility = View.VISIBLE
            handle.alpha = 0f
            handle.translationY = handleTranslation
        }
        handle.animate()
            .alpha(1f)
            .translationY(0f)
            .setInterpolator(standardInterpolator)
            .setDuration(180L)
            .start()
        updateMiniPlayerTransitionState()
    }

    private fun setMiniCollapsedByGesture(collapsed: Boolean, animated: Boolean = true) {
        if (miniCollapsedByGesture == collapsed) {
            return
        }
        miniCollapsedByGesture = collapsed
        applyMiniPlayerVisibility(animated = animated)
        updateFragmentBottomInset(animated = animated)
    }

    private fun animateMiniProgressEmphasis(emphasized: Boolean) {
        miniPlayerBinding.progressMini.animate()
            .scaleY(if (emphasized) 1.55f else 1f)
            .setInterpolator(standardInterpolator)
            .setDuration(140L)
            .start()
    }

    private fun updateFragmentBottomInset(animated: Boolean) {
        updateMiniPlayerBottomSpacing()
        updateTopChromeOffsets()
        val navHeight = resolveBottomNavHeight()

        val navInset = navHeight + dp(8f)
        val targetBottomInset = navInset
        val targetTopInset = resolveTopChromeInset()

        val container = binding.fragmentContainer
        val currentBottom = container.paddingBottom
        val currentTop = container.paddingTop
        if (!animated || (currentBottom == targetBottomInset && currentTop == targetTopInset)) {
            containerInsetAnimator?.cancel()
            container.updatePadding(top = targetTopInset, bottom = targetBottomInset)
            return
        }
        containerInsetAnimator?.cancel()
        containerInsetAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            interpolator = standardInterpolator
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                val top = (currentTop + (targetTopInset - currentTop) * t).roundToInt()
                val bottom = (currentBottom + (targetBottomInset - currentBottom) * t).roundToInt()
                container.updatePadding(top = top, bottom = bottom)
            }
            start()
        }
    }

    private fun resolveTopChromeInset(): Int {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        val baseInset = resolveVisibleBannerInset()
        if (baseInset <= 0) {
            return 0
        }
        if (currentFragment is PlaybackFragment) {
            return baseInset
        }
        val bannerHeight = binding.cardStreamBanner.height.takeIf { it > 0 }
            ?: binding.cardStreamBanner.measuredHeight.takeIf { it > 0 }
            ?: 0
        val gentleShift = minOf(dp(10f), (bannerHeight * 0.22f).roundToInt()).coerceAtLeast(dp(4f))
        return baseInset + gentleShift
    }

    private fun resolveVisibleBannerInset(): Int {
        val banner = binding.cardStreamBanner
        if (banner.visibility != View.VISIBLE) {
            return 0
        }
        val lp = banner.layoutParams as? ViewGroup.MarginLayoutParams
        val topMargin = lp?.topMargin ?: 0
        val height = banner.height.takeIf { it > 0 } ?: banner.measuredHeight.takeIf { it > 0 } ?: 0
        return (topMargin + height).coerceAtLeast(0)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun resolveToolbarHeight(): Int {
        return binding.appBar.height.takeIf { it > 0 }
            ?: binding.toolbar.height.takeIf { it > 0 }
            ?: dp(44f)
    }

    private fun updateTopChromeOffsets() {
        val bannerLp = binding.cardStreamBanner.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val anchorTop = dp(2f)
        val targetTop = anchorTop
        if (bannerLp.topMargin != targetTop) {
            bannerLp.topMargin = targetTop
            binding.cardStreamBanner.layoutParams = bannerLp
        }
    }

    private fun updateMiniPlayerBottomSpacing() {
        val navHeight = resolveBottomNavHeight()
        if (binding.bottomNav.visibility == View.VISIBLE && navHeight <= 0) return

        val cardLp = miniPlayerBinding.miniPlayerCard.layoutParams as? FrameLayout.LayoutParams ?: return
        if (miniCardBaseBottomMargin < 0) {
            miniCardBaseBottomMargin = cardLp.bottomMargin.coerceAtLeast(dp(6f))
        }
        val targetCardBottom = navHeight + miniCardBaseBottomMargin
        if (cardLp.bottomMargin != targetCardBottom) {
            cardLp.bottomMargin = targetCardBottom
            miniPlayerBinding.miniPlayerCard.layoutParams = cardLp
        }

        val handleLp = miniPlayerBinding.viewMiniHandle.layoutParams as? FrameLayout.LayoutParams ?: return
        if (miniHandleBaseBottomMargin < 0) {
            miniHandleBaseBottomMargin = handleLp.bottomMargin.coerceAtLeast(dp(6f))
        }
        val targetHandleBottom = navHeight + miniHandleBaseBottomMargin
        if (handleLp.bottomMargin != targetHandleBottom) {
            handleLp.bottomMargin = targetHandleBottom
            miniPlayerBinding.viewMiniHandle.layoutParams = handleLp
        }
    }

    private fun resolveBottomNavHeight(): Int {
        if (binding.bottomNav.visibility != View.VISIBLE) {
            return 0
        }
        return binding.bottomNav.height.takeIf { it > 0 }
            ?: binding.bottomNav.measuredHeight.takeIf { it > 0 }
            ?: 0
    }

    private fun attachPlayerExitWarmupBridge() {
        PlayerTransitionState.onMainUiWarmupRequested = {
            runOnUiThread {
                prewarmHomeUiForPlayerExit()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    startPendingCollapseOverlayOnHome()
                }
            }
        }
        if (PlayerTransitionState.consumeMainUiWarmupRequest()) {
            prewarmHomeUiForPlayerExit()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                startPendingCollapseOverlayOnHome()
            }
        }
    }

    private fun prewarmHomeUiForPlayerExit() {
        if (isFinishing || isDestroyed) {
            return
        }
        updateMiniPlayerBottomSpacing()
        applyMiniPlayerVisibility(animated = false)
        updateFragmentBottomInset(animated = false)
        updateMiniPlayerTransitionState()
    }

    private fun startPendingCollapseOverlayOnHome() {
        val payload = PlayerTransitionState.consumeCollapseOverlay() ?: return
        val overlayHost = findViewById<ViewGroup>(android.R.id.content) ?: run {
            payload.snapshot.recycle()
            return
        }
        val hostLoc = IntArray(2)
        overlayHost.getLocationOnScreen(hostLoc)
        val source = Rect(
            payload.sourceRectOnScreen.left - hostLoc[0],
            payload.sourceRectOnScreen.top - hostLoc[1],
            payload.sourceRectOnScreen.right - hostLoc[0],
            payload.sourceRectOnScreen.bottom - hostLoc[1]
        )
        val target = Rect(
            payload.targetRectOnScreen.left - hostLoc[0],
            payload.targetRectOnScreen.top - hostLoc[1],
            payload.targetRectOnScreen.right - hostLoc[0],
            payload.targetRectOnScreen.bottom - hostLoc[1]
        )
        if (source.width() <= 0 || source.height() <= 0 || target.width() <= 0 || target.height() <= 0) {
            payload.snapshot.recycle()
            return
        }

        val duration = 300L
        val startCorner = dp(2f).toFloat()
        val endCorner = dp(20f).toFloat()
        var currentCorner = startCorner
        var clipWidth = source.width()
        var clipHeight = source.height()
        val clipRect = Rect(0, 0, clipWidth, clipHeight)

        val maskedSnapshot = FrameLayout(this).apply {
            x = source.left.toFloat()
            y = source.top.toFloat()
            layoutParams = FrameLayout.LayoutParams(source.width(), source.height())
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, clipWidth, clipHeight, currentCorner)
                }
            }
        }
        val snapshotImage = ImageView(this).apply {
            setImageBitmap(payload.snapshot)
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = FrameLayout.LayoutParams(source.width(), source.height())
        }
        val snapshotMask = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(source.width(), source.height())
            setBackgroundColor(
                MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorSurface
                )
            )
            alpha = 0.58f
        }
        maskedSnapshot.addView(snapshotImage)
        maskedSnapshot.addView(snapshotMask)
        overlayHost.addView(maskedSnapshot)

        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            this.interpolator = standardInterpolator
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val w = (source.width() + (target.width() - source.width()) * t).toInt().coerceAtLeast(1)
                val h = (source.height() + (target.height() - source.height()) * t).toInt().coerceAtLeast(1)
                val x = source.left + (target.left - source.left) * t
                val y = source.top + (target.top - source.top) * t

                currentCorner = startCorner + (endCorner - startCorner) * t
                clipWidth = w
                clipHeight = h
                clipRect.right = clipWidth
                clipRect.bottom = clipHeight

                maskedSnapshot.x = x
                maskedSnapshot.y = y
                maskedSnapshot.clipBounds = clipRect
                maskedSnapshot.invalidateOutline()
                snapshotImage.x = source.left.toFloat() - x
                snapshotImage.y = source.top.toFloat() - y
                snapshotMask.x = source.left.toFloat() - x
                snapshotMask.y = source.top.toFloat() - y
            }
            doOnEnd {
                maskedSnapshot.setLayerType(View.LAYER_TYPE_NONE, null)
                overlayHost.removeView(maskedSnapshot)
                payload.snapshot.recycle()
            }
            start()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        handleGlobalMiniPlayerGesture(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun handleGlobalMiniPlayerGesture(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isBottomInteractionArea(ev.rawY)) {
                    gestureDownX = Float.NaN
                    gestureDownY = Float.NaN
                    miniGestureFromCard = false
                    miniHorizontalSwitchTriggered = false
                    return
                }
                gestureDownX = ev.rawX
                gestureDownY = ev.rawY
                miniGestureFromCard = isTouchInsideView(ev.rawX, ev.rawY, miniPlayerBinding.miniPlayerCard)
                miniHorizontalSwitchTriggered = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (miniProgressUserSeeking || !latestPlaybackState.hasMedia || gestureDownX.isNaN()) {
                    return
                }
                if (!miniGestureFromCard || miniHorizontalSwitchTriggered) {
                    return
                }
                val dx = ev.rawX - gestureDownX
                val dy = ev.rawY - gestureDownY
                val horizontalThreshold = 30f * resources.displayMetrics.density
                if (abs(dx) > horizontalThreshold && abs(dx) > abs(dy) * 1.1f) {
                    miniHorizontalSwitchTriggered = true
                    suppressMiniCardClickOnce = true
                    animateMiniPlayerTrackSwitch(next = dx < 0f)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (miniProgressUserSeeking || !latestPlaybackState.hasMedia || gestureDownX.isNaN()) {
                    return
                }
                if (miniHorizontalSwitchTriggered) {
                    miniHorizontalSwitchTriggered = false
                    miniGestureFromCard = false
                    gestureDownX = Float.NaN
                    gestureDownY = Float.NaN
                    return
                }
                val dx = ev.rawX - gestureDownX
                val dy = ev.rawY - gestureDownY
                val threshold = 56f * resources.displayMetrics.density
                val horizontalThreshold = 36f * resources.displayMetrics.density
                if (miniGestureFromCard && abs(dx) > horizontalThreshold && abs(dx) > abs(dy) * 1.15f) {
                    suppressMiniCardClickOnce = true
                    animateMiniPlayerTrackSwitch(next = dx < 0f)
                    gestureDownX = Float.NaN
                    gestureDownY = Float.NaN
                    miniGestureFromCard = false
                    return
                }
                if (abs(dy) < threshold || abs(dy) < abs(dx) * 1.2f) {
                    return
                }
                if (dy > 0f) {
                    setMiniCollapsedByGesture(collapsed = true, animated = true)
                } else {
                    setMiniCollapsedByGesture(collapsed = false, animated = true)
                }
                miniGestureFromCard = false
            }

            MotionEvent.ACTION_CANCEL -> {
                gestureDownX = Float.NaN
                gestureDownY = Float.NaN
                miniGestureFromCard = false
                miniHorizontalSwitchTriggered = false
            }
        }
    }

    private fun isTouchInsideView(rawX: Float, rawY: Float, view: View): Boolean {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) {
            return false
        }
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + view.width &&
            rawY >= loc[1] && rawY <= loc[1] + view.height
    }

    private fun isBottomInteractionArea(rawY: Float): Boolean {
        val navHeight = resolveBottomNavHeight()
        val boundary = binding.root.height - navHeight - dp(120f)
        return rawY >= boundary
    }

    private fun openPlayerDetailWithAnimation() {
        updateMiniPlayerTransitionState()
        PlayerTransitionState.updatePrewarmedPlaybackState(latestPlaybackState)
        val intent = Intent(this, PlayerActivity::class.java)
        startActivity(intent)
        overridePendingTransition(0, 0)
    }

    private fun updateMiniPlayerTransitionState() {
        val target = when {
            miniPlayerBinding.miniPlayerCard.visibility == View.VISIBLE -> miniPlayerBinding.miniPlayerCard
            miniPlayerBinding.viewMiniHandle.visibility == View.VISIBLE -> miniPlayerBinding.viewMiniHandle
            else -> null
        } ?: run {
            PlayerTransitionState.miniPlayerRectOnScreen = null
            return
        }
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        PlayerTransitionState.miniPlayerRectOnScreen = Rect(
            loc[0],
            loc[1],
            loc[0] + target.width,
            loc[1] + target.height
        )
    }

    private fun navigateByItem(itemId: Int): Boolean {
        return when (itemId) {
            R.id.navigation_playback -> {
                navigateTo(PlaybackFragment(), R.string.page_playback)
                true
            }

            R.id.navigation_effects -> {
                navigateTo(EffectsFragment(), R.string.page_effects)
                true
            }

            R.id.navigation_settings -> {
                navigateTo(SettingsFragment(), R.string.page_settings)
                true
            }

            else -> false
        }
    }

    private fun navigateTo(fragment: Fragment, titleRes: Int) {
        setTopBarTitle(getString(titleRes))
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment)
            .commit()
        setBottomNavHiddenForKeyboard(false)
        binding.fragmentContainer.post { updateFragmentBottomInset(animated = false) }
    }

    private fun handleExternalNavigationIntent(intent: Intent): Boolean {
        val target = intent.getStringExtra(EXTRA_OPEN_TARGET).orEmpty()
        val name = intent.getStringExtra(EXTRA_TARGET_NAME).orEmpty()
        if (name.isBlank()) {
            return false
        }
        return when (target) {
            TARGET_ARTIST -> {
                openExternalDetailFromPlaybackRoot(ArtistDetailFragment.newInstance(name), name)
                true
            }

            TARGET_ALBUM -> {
                openExternalDetailFromPlaybackRoot(AlbumDetailFragment.newInstance(name), name)
                true
            }

            else -> false
        }
    }

    private fun openExternalDetailFromPlaybackRoot(detailFragment: Fragment, title: String) {
        val fm = supportFragmentManager
        fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        if (fm.findFragmentById(R.id.fragment_container) !is PlaybackFragment) {
            fm.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.fragment_container, PlaybackFragment())
                .commit()
            fm.executePendingTransactions()
        }
        binding.bottomNav.menu.findItem(R.id.navigation_playback).isChecked = true
        setTopBarTitle(title)
        fm.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, detailFragment)
            .addToBackStack(null)
            .commit()
        setBottomNavHiddenForKeyboard(false)
        binding.fragmentContainer.post { updateFragmentBottomInset(animated = false) }
    }

    fun setTopBarTitle(title: CharSequence) {
        binding.toolbar.title = title
        supportActionBar?.title = title
    }

    private fun updateTopBarTitleFromCurrentFragment() {
        when (supportFragmentManager.findFragmentById(R.id.fragment_container)) {
            is PlaybackFragment -> setTopBarTitle(getString(R.string.page_playback))
            is EffectsFragment -> setTopBarTitle(getString(R.string.page_effects))
            is SettingsFragment -> setTopBarTitle(getString(R.string.page_settings))
        }
    }
}
