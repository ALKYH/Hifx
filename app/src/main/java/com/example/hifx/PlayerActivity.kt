package com.example.hifx

import android.content.ContentUris
import android.content.res.ColorStateList
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.addCallback
import androidx.core.animation.doOnEnd
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.example.hifx.audio.AudioEngine
import com.example.hifx.audio.PlaybackUiState
import com.example.hifx.databinding.ActivityPlayerBinding
import com.example.hifx.ui.PlayerTransitionState
import com.example.hifx.util.loadArtworkOrDefault
import com.example.hifx.util.toTimeString
import com.example.hifx.util.WaveformAnalyzer
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

class PlayerActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "PlayerLyrics"
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
    private val standardInterpolator = FastOutSlowInInterpolator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupImmersiveUi()
        setupControls()
        setupAlbumGestures()
        setupBackgroundEffects()
        applyPrewarmedStateIfAvailable()
        observePlaybackState()
        playEnterAnimation()
        onBackPressedDispatcher.addCallback(this) {
            animateCollapseToMiniPlayerAndFinish()
        }
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
        binding.buttonShuffle.setOnClickListener {
            AudioEngine.toggleShuffleEnabled()
        }
        binding.buttonRepeatMode.setOnClickListener {
            AudioEngine.cycleRepeatMode()
        }
        binding.buttonSleepTimer.setOnClickListener {
            showSleepTimerDialog()
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

        fun animateGroupOut(onEnd: () -> Unit) {
            albumCard.animate().translationX(direction * shift).alpha(0.5f)
                .setInterpolator(standardInterpolator).setDuration(130L).withEndAction(onEnd).start()
            title.animate().translationX(direction * shift * 0.72f).alpha(0.5f)
                .setInterpolator(standardInterpolator).setDuration(130L).start()
            subtitle.animate().translationX(direction * shift * 0.72f).alpha(0.5f)
                .setInterpolator(standardInterpolator).setDuration(130L).start()
        }

        fun animateGroupIn() {
            albumCard.translationX = -direction * shift * 0.62f
            albumCard.alpha = 0.55f
            title.translationX = -direction * shift * 0.42f
            title.alpha = 0.55f
            subtitle.translationX = -direction * shift * 0.42f
            subtitle.alpha = 0.55f

            albumCard.animate().translationX(0f).alpha(1f)
                .setInterpolator(standardInterpolator).setDuration(180L).start()
            title.animate().translationX(0f).alpha(1f)
                .setInterpolator(standardInterpolator).setDuration(180L).start()
            subtitle.animate().translationX(0f).alpha(1f)
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
            if (entering) {
                binding.imagePlayerBg.alpha = 0.52f
                binding.imagePlayerBg.scaleX = 1.24f
                binding.imagePlayerBg.scaleY = 1.24f
            } else {
                binding.imagePlayerBg.alpha = 0.15f
                binding.imagePlayerBg.animate()
                    .alpha(0.52f)
                    .setInterpolator(standardInterpolator)
                    .setDuration(260L)
                    .start()
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
        binding.textTrackTitle.text = state.title
        binding.textTrackSubtitle.text = state.subtitle
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
        updateLyrics(state.positionMs.coerceIn(0L, duration))
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
            lastLyricMediaKey = null
            renderLyrics(null, null)
            return
        }
        val key = mediaUri.toString()
        if (key == lastLyricMediaKey) {
            return
        }
        lastLyricMediaKey = key
        lyricCache[key]?.let { cached ->
            activeLyrics = cached
            return
        }
        lyricJob?.cancel()
        lyricJob = lifecycleScope.launch {
            val loadResult = readLrcMetadata(mediaUri)
            val parsed = parseLrcLines(loadResult.text)
            lyricCache[key] = parsed
            if (lastLyricMediaKey == key) {
                activeLyrics = parsed
                updateLyrics(binding.progressSlider.value.toLong())
                Log.d(
                    TAG,
                    "lyrics loaded: source=${loadResult.source}, parsedLines=${parsed.size}, media=$key"
                )
            }
        }
    }

    private fun updateLyrics(positionMs: Long) {
        if (activeLyrics.isEmpty()) {
            renderLyrics(null, null)
            return
        }
        val currentIndex = findCurrentLyricIndex(positionMs, activeLyrics)
        if (currentIndex < 0) {
            renderLyrics(activeLyrics.firstOrNull()?.text, null, 0f)
            return
        }
        val current = activeLyrics.getOrNull(currentIndex)?.text
        val next = activeLyrics.getOrNull(currentIndex + 1)?.text
        val progress = computeLyricProgress(positionMs, currentIndex, activeLyrics)
        renderLyrics(current, next, progress)
    }

    private fun renderLyrics(current: String?, next: String?, currentProgress: Float = 0f) {
        val hasCurrent = !current.isNullOrBlank()
        val hasNext = !next.isNullOrBlank()
        if (!hasCurrent && !hasNext) {
            binding.layoutLyrics.visibility = View.GONE
            return
        }
        binding.layoutLyrics.visibility = View.VISIBLE
        binding.textLyricCurrent.text = current.orEmpty()
        binding.textLyricCurrent.setLyricProgress(currentProgress)
        binding.textLyricNext.text = next.orEmpty()
        binding.textLyricNext.setLyricProgress(0f)
        binding.textLyricNext.visibility = if (hasNext) View.VISIBLE else View.INVISIBLE
    }

    private fun computeLyricProgress(positionMs: Long, currentIndex: Int, lyrics: List<LyricLine>): Float {
        val currentLine = lyrics.getOrNull(currentIndex) ?: return 0f
        val nextLine = lyrics.getOrNull(currentIndex + 1) ?: return 1f
        val span = (nextLine.timeMs - currentLine.timeMs).coerceAtLeast(1L)
        return ((positionMs - currentLine.timeMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }

    private suspend fun readLrcMetadata(uri: Uri): LyricLoadResult = withContext(Dispatchers.IO) {
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
        val lrcName = "$baseName.lrc"
        val filesUri = MediaStore.Files.getContentUri("external")
        val lrcUri = runCatching {
            contentResolver.query(
                filesUri,
                arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME}=?",
                arrayOf(pair.second, lrcName),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    ContentUris.withAppendedId(filesUri, id)
                } else {
                    null
                }
            }
        }.getOrNull() ?: return null

        return runCatching {
            contentResolver.openInputStream(lrcUri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    private fun parseLrcLines(lrcText: String?): List<LyricLine> {
        if (lrcText.isNullOrBlank()) {
            return emptyList()
        }
        val result = mutableListOf<LyricLine>()
        val regex = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{1,3}))?]""")
        lrcText.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEach
            val matches = regex.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val content = regex.replace(line, "").trim()
            if (content.isBlank()) return@forEach
            for (match in matches) {
                val minutes = match.groupValues[1].toLongOrNull() ?: continue
                val seconds = match.groupValues[2].toLongOrNull() ?: continue
                val fractionRaw = match.groupValues[3]
                val fractionMs = when (fractionRaw.length) {
                    0 -> 0L
                    1 -> fractionRaw.toLong() * 100L
                    2 -> fractionRaw.toLong() * 10L
                    else -> fractionRaw.take(3).toLong()
                }
                val timeMs = minutes * 60_000L + seconds * 1_000L + fractionMs
                result += LyricLine(timeMs, content)
            }
        }
        return result.sortedBy { it.timeMs }
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
        val text: String
    )

    private data class LyricLoadResult(
        val text: String?,
        val source: String
    ) {
        fun isNullOrBlank(): Boolean = text.isNullOrBlank()
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.imagePlayerBg.setRenderEffect(
                RenderEffect.createBlurEffect(80f, 80f, Shader.TileMode.CLAMP)
            )
        }
    }

    private fun playEnterAnimation() {
        val startOnScreen = PlayerTransitionState.miniPlayerRectOnScreen
        if (startOnScreen == null) {
            entering = false
            val content = binding.root
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
            val overlayHost = findViewById<ViewGroup>(android.R.id.content) ?: run {
                entering = false
                binding.root.alpha = 1f
                return@post
            }
            entering = true
            val hostLoc = IntArray(2)
            overlayHost.getLocationOnScreen(hostLoc)
            val endRect = rectInHost(binding.root, hostLoc)
            val startRect = Rect(
                startOnScreen.left - hostLoc[0],
                startOnScreen.top - hostLoc[1],
                startOnScreen.right - hostLoc[0],
                startOnScreen.bottom - hostLoc[1]
            )
            if (startRect.width() <= 0 || startRect.height() <= 0 || endRect.width() <= 0 || endRect.height() <= 0) {
                entering = false
                binding.root.alpha = 1f
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
            binding.root.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            binding.root.clipToOutline = true
            binding.root.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(liveClipRect, currentCorner)
                }
            }
            binding.root.clipBounds = Rect(liveClipRect)
            binding.root.invalidateOutline()

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
                    binding.root.clipBounds = Rect(liveClipRect)
                    binding.root.invalidateOutline()
                }
                doOnEnd {
                    binding.root.clipBounds = null
                    binding.root.clipToOutline = false
                    binding.root.outlineProvider = ViewOutlineProvider.BOUNDS
                    binding.root.setLayerType(View.LAYER_TYPE_NONE, null)
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

    private data class EnterMaskOverlay(
        val host: ViewGroup,
        val maskView: FrameLayout,
        val snapshot: Bitmap
    )
}
