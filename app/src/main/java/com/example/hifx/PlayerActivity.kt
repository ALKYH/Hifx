package com.example.hifx

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import com.example.hifx.audio.AudioEngine
import com.example.hifx.audio.PlaybackUiState
import com.example.hifx.databinding.ActivityPlayerBinding
import com.example.hifx.util.loadArtworkOrDefault
import com.example.hifx.util.toTimeString
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch
import kotlin.math.max

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var userSeeking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarPlayer)
        binding.toolbarPlayer.setNavigationOnClickListener { finish() }

        setupControls()
        observePlaybackState()
    }

    private fun setupControls() {
        binding.buttonPlayPause.setOnClickListener {
            AudioEngine.togglePlayPause()
        }
        binding.buttonPreviousTrack.setOnClickListener {
            AudioEngine.skipToPreviousTrack()
        }
        binding.buttonNextTrack.setOnClickListener {
            AudioEngine.skipToNextTrack()
        }
        binding.buttonSeekBack.setOnClickListener {
            AudioEngine.seekBy(-10_000L)
        }
        binding.buttonSeekForward.setOnClickListener {
            AudioEngine.seekBy(10_000L)
        }
        binding.buttonShuffle.setOnClickListener {
            AudioEngine.toggleShuffleEnabled()
        }
        binding.buttonRepeatMode.setOnClickListener {
            AudioEngine.cycleRepeatMode()
        }
        binding.buttonSleepTimer.setOnClickListener {
            showSleepTimerDialog()
        }
        binding.progressSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.textCurrentTime.text = value.toLong().toTimeString()
            }
        }
        binding.progressSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                userSeeking = false
                AudioEngine.seekTo(slider.value.toLong())
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

    override fun onPause() {
        userSeeking = false
        super.onPause()
    }

    private fun renderState(state: PlaybackUiState) {
        binding.imageCover.loadArtworkOrDefault(state.artworkUri)
        binding.textTrackTitle.text = state.title
        binding.textTrackSubtitle.text = state.subtitle
        binding.buttonPreviousTrack.isEnabled = state.hasPrevious
        binding.buttonNextTrack.isEnabled = state.hasNext
        binding.buttonPlayPause.text =
            if (state.isPlaying) getString(R.string.action_pause) else getString(R.string.action_play)
        binding.buttonPlayPause.setIconResource(
            if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        binding.buttonShuffle.text = if (state.shuffleEnabled) {
            getString(R.string.player_shuffle_on)
        } else {
            getString(R.string.player_shuffle_off)
        }
        binding.buttonRepeatMode.text = when (state.repeatMode) {
            Player.REPEAT_MODE_ONE -> getString(R.string.player_repeat_one)
            Player.REPEAT_MODE_ALL -> getString(R.string.player_repeat_all)
            else -> getString(R.string.player_repeat_off)
        }
        binding.textSleepTimer.text = if (state.sleepTimerRemainingMs > 0L) {
            getString(R.string.player_sleep_timer_remaining, state.sleepTimerRemainingMs.toTimeString())
        } else {
            getString(R.string.player_sleep_timer_off)
        }

        val duration = max(1L, state.durationMs)
        updateProgressSliderRange(duration)
        binding.textDuration.text = state.durationMs.toTimeString()
        if (!userSeeking) {
            binding.progressSlider.value = state.positionMs.coerceIn(0L, duration).toFloat()
            binding.textCurrentTime.text = state.positionMs.toTimeString()
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
}
