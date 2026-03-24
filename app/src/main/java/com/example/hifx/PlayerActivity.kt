package com.example.hifx

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
        binding.buttonSeekBack.setOnClickListener {
            AudioEngine.seekBy(-10_000L)
        }
        binding.buttonSeekForward.setOnClickListener {
            AudioEngine.seekBy(10_000L)
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
        binding.buttonPlayPause.text =
            if (state.isPlaying) getString(R.string.action_pause) else getString(R.string.action_play)
        binding.buttonPlayPause.setIconResource(
            if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )

        val duration = max(1L, state.durationMs)
        updateProgressSliderRange(duration)
        binding.textDuration.text = state.durationMs.toTimeString()
        if (!userSeeking) {
            binding.progressSlider.value = state.positionMs.coerceIn(0L, duration).toFloat()
            binding.textCurrentTime.text = state.positionMs.toTimeString()
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
}
