package com.example.hifx

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hifx.audio.AudioEngine
import com.example.hifx.audio.PlaybackUiState
import com.example.hifx.databinding.ActivityMainBinding
import com.example.hifx.util.loadArtworkOrDefault
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var miniProgressUserSeeking = false
    private var latestDurationMs: Long = 0L
    private var latestPlaybackState: PlaybackUiState = PlaybackUiState()
    private var miniCollapsedByGesture = false
    private var lastHasMedia = false
    private var gestureDownX = 0f
    private var gestureDownY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        setupBottomNavigation()
        setupMiniPlayerActions()
        observeMiniPlayerState()

        if (savedInstanceState == null) {
            binding.bottomNav.menu.findItem(R.id.navigation_playback).isChecked = true
            navigateByItem(R.id.navigation_playback)
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            navigateByItem(item.itemId)
        }
    }

    private fun setupMiniPlayerActions() {
        binding.miniPlayerCard.setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }
        binding.buttonMiniPlayPause.setOnClickListener {
            AudioEngine.togglePlayPause()
        }
        binding.progressMini.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
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
    }

    private fun observeMiniPlayerState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AudioEngine.playbackState.collect { state ->
                    renderMiniPlayer(state)
                }
            }
        }
    }

    private fun renderMiniPlayer(state: PlaybackUiState) {
        latestPlaybackState = state
        val availabilityChanged = state.hasMedia != lastHasMedia
        lastHasMedia = state.hasMedia

        if (!state.hasMedia) {
            miniCollapsedByGesture = false
        }
        applyMiniPlayerVisibility(animated = availabilityChanged)

        if (!state.hasMedia) {
            latestDurationMs = 0L
            return
        }
        binding.imageMiniCover.loadArtworkOrDefault(state.artworkUri)
        binding.textMiniTitle.text = state.title
        binding.textMiniSubtitle.text = state.subtitle
        binding.buttonMiniPlayPause.setIconResource(
            if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        latestDurationMs = state.durationMs
        val hasDuration = state.durationMs > 0L
        binding.progressMini.isEnabled = hasDuration
        if (hasDuration && !miniProgressUserSeeking) {
            val ratio = (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
            binding.progressMini.value = (ratio * 1000f).roundToInt().toFloat()
        } else if (!hasDuration && !miniProgressUserSeeking) {
            binding.progressMini.value = 0f
        }
    }

    private fun applyMiniPlayerVisibility(animated: Boolean) {
        val shouldShow = latestPlaybackState.hasMedia && !miniCollapsedByGesture
        val card = binding.miniPlayerCard
        val collapsedTranslation = 46f * resources.displayMetrics.density

        if (!animated) {
            card.animate().cancel()
            card.alpha = if (shouldShow) 1f else 0f
            card.translationY = if (shouldShow) 0f else collapsedTranslation
            card.visibility = if (shouldShow) View.VISIBLE else View.GONE
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
                .setDuration(180L)
                .start()
            return
        }

        if (card.visibility != View.VISIBLE) {
            return
        }
        card.animate()
            .alpha(0f)
            .translationY(collapsedTranslation)
            .setDuration(180L)
            .withEndAction {
                if (!(latestPlaybackState.hasMedia && !miniCollapsedByGesture)) {
                    card.visibility = View.GONE
                }
            }
            .start()
    }

    private fun setMiniCollapsedByGesture(collapsed: Boolean, animated: Boolean = true) {
        if (miniCollapsedByGesture == collapsed) {
            return
        }
        miniCollapsedByGesture = collapsed
        applyMiniPlayerVisibility(animated = animated)
    }

    private fun animateMiniProgressEmphasis(emphasized: Boolean) {
        binding.progressMini.animate()
            .scaleY(if (emphasized) 1.55f else 1f)
            .setDuration(140L)
            .start()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        handleGlobalMiniPlayerGesture(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun handleGlobalMiniPlayerGesture(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownX = ev.rawX
                gestureDownY = ev.rawY
            }

            MotionEvent.ACTION_UP -> {
                if (miniProgressUserSeeking || !latestPlaybackState.hasMedia) {
                    return
                }
                val dx = ev.rawX - gestureDownX
                val dy = ev.rawY - gestureDownY
                val threshold = 56f * resources.displayMetrics.density
                if (abs(dy) < threshold || abs(dy) < abs(dx) * 1.2f) {
                    return
                }
                if (dy > 0f) {
                    setMiniCollapsedByGesture(collapsed = true, animated = true)
                } else {
                    setMiniCollapsedByGesture(collapsed = false, animated = true)
                }
            }
        }
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
        supportActionBar?.title = getString(titleRes)
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}
