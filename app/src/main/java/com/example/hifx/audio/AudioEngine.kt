package com.example.hifx.audio

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.appcompat.app.AppCompatDelegate
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class LibraryTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val albumId: Long,
    val artworkUri: Uri?
)

data class TrackSection(
    val title: String,
    val tracks: List<LibraryTrack>
)

data class MediaLibraryUiState(
    val loading: Boolean = false,
    val tracks: List<LibraryTrack> = emptyList(),
    val albums: List<TrackSection> = emptyList(),
    val artists: List<TrackSection> = emptyList(),
    val scanFolderLabel: String = "全部媒体库",
    val errorMessage: String? = null
)

data class PlaybackUiState(
    val title: String = "未选择音频",
    val subtitle: String = "支持 FLAC / WAV / MP3 / AAC",
    val artworkUri: Uri? = null,
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val audioSessionId: Int = 0,
    val errorMessage: String? = null
)

data class EffectsUiState(
    val enabled: Boolean = true,
    val bassStrength: Int = 400,
    val virtualizerStrength: Int = 300,
    val loudnessGainMb: Int = 0,
    val eqBandFrequenciesHz: List<Int> = listOf(32, 64, 125, 250, 500, 1000, 4000, 8000),
    val eqBandLevelsMb: List<Int> = List(8) { 0 },
    val eqBandLevelMinMb: Int = -1200,
    val eqBandLevelMaxMb: Int = 1200,
    val spatialEnabled: Boolean = false,
    val channelSeparated: Boolean = false,
    val spatialLeftX: Int = 0,
    val spatialLeftY: Int = 0,
    val spatialLeftZ: Int = 0,
    val spatialRightX: Int = 0,
    val spatialRightY: Int = 0,
    val spatialRightZ: Int = 0,
    val spatialLeftRadiusPercent: Int = 100,
    val spatialRightRadiusPercent: Int = 100,
    val roomSize: Int = 55,
    val roomDamping: Int = 35,
    val earlyReflection: Int = 45,
    val wetMix: Int = 40,
    val perceptualCompression: Int = 45,
    val hrtfEnabled: Boolean = true,
    val hrtfUseDatabase: Boolean = true,
    val hrtfHeadRadiusMm: Int = 87,
    val hrtfBlendPercent: Int = 78,
    val hrtfCrossfeedPercent: Int = 28,
    val hrtfExternalizationPercent: Int = 55,
    val surroundMode: Int = 0,
    val convolutionEnabled: Boolean = false,
    val convolutionWetPercent: Int = 35,
    val convolutionIrUri: String? = null,
    val convolutionIrName: String = "未导入 IRS",
    val realtimeReverbMeterPercent: Int = 0,
    val derivedDistanceMeters: Float = 1f,
    val derivedGainDb: Float = 0f
)

data class SettingsUiState(
    val hiFiMode: Boolean = true,
    val outputSampleRateHz: Int? = null,
    val outputFramesPerBuffer: Int? = null,
    val offloadSupported: Boolean = false,
    val scanFolderUri: Uri? = null,
    val scanFolderLabel: String = "全部媒体库",
    val themeMode: Int = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
)

private data class SpatialModel(
    val gainLinear: Float,
    val gainDb: Float,
    val distanceMeters: Float,
    val lowTiltMb: Float,
    val highTiltMb: Float,
    val roomLevelMb: Int,
    val roomHFLevelMb: Int,
    val decayTimeMs: Int,
    val decayHFRatio: Int,
    val reflectionsLevelMb: Int,
    val reflectionsDelayMs: Int,
    val reverbLevelMb: Int,
    val reverbDelayMs: Int,
    val density: Int,
    val diffusion: Int
)

object AudioEngine {
    private const val PREFS_NAME = "hifx_audio_preferences"
    private const val KEY_HIFI_MODE = "key_hifi_mode"
    private const val KEY_EFFECT_ENABLED = "key_effect_enabled"
    private const val KEY_BASS_STRENGTH = "key_bass_strength"
    private const val KEY_VIRTUALIZER_STRENGTH = "key_virtualizer_strength"
    private const val KEY_LOUDNESS_GAIN_MB = "key_loudness_gain_mb"
    private const val KEY_EQ_BAND_LEVEL_PREFIX = "key_band_level_"
    private const val KEY_SPATIAL_ENABLED = "key_spatial_enabled"
    private const val KEY_CHANNEL_SEPARATED = "key_channel_separated"
    private const val KEY_SPATIAL_X = "key_spatial_x"
    private const val KEY_SPATIAL_Y = "key_spatial_y"
    private const val KEY_SPATIAL_Z = "key_spatial_z"
    private const val KEY_SPATIAL_LEFT_X = "key_spatial_left_x"
    private const val KEY_SPATIAL_LEFT_Y = "key_spatial_left_y"
    private const val KEY_SPATIAL_LEFT_Z = "key_spatial_left_z"
    private const val KEY_SPATIAL_RIGHT_X = "key_spatial_right_x"
    private const val KEY_SPATIAL_RIGHT_Y = "key_spatial_right_y"
    private const val KEY_SPATIAL_RIGHT_Z = "key_spatial_right_z"
    private const val KEY_SPATIAL_LEFT_RADIUS_PERCENT = "key_spatial_left_radius_percent"
    private const val KEY_SPATIAL_RIGHT_RADIUS_PERCENT = "key_spatial_right_radius_percent"
    private const val KEY_ROOM_SIZE = "key_room_size"
    private const val KEY_ROOM_DAMPING = "key_room_damping"
    private const val KEY_EARLY_REFLECTION = "key_early_reflection"
    private const val KEY_WET_MIX = "key_wet_mix"
    private const val KEY_PERCEPTUAL_COMPRESSION = "key_perceptual_compression"
    private const val KEY_HRTF_ENABLED = "key_hrtf_enabled"
    private const val KEY_HRTF_DB_ENABLED = "key_hrtf_db_enabled"
    private const val KEY_HRTF_HEAD_RADIUS_MM = "key_hrtf_head_radius_mm"
    private const val KEY_HRTF_BLEND_PERCENT = "key_hrtf_blend_percent"
    private const val KEY_HRTF_CROSSFEED_PERCENT = "key_hrtf_crossfeed_percent"
    private const val KEY_HRTF_EXTERNALIZATION_PERCENT = "key_hrtf_externalization_percent"
    private const val KEY_SURROUND_MODE = "key_surround_mode"
    private const val KEY_CONVOLUTION_ENABLED = "key_convolution_enabled"
    private const val KEY_CONVOLUTION_WET_PERCENT = "key_convolution_wet_percent"
    private const val KEY_CONVOLUTION_IR_URI = "key_convolution_ir_uri"
    private const val KEY_CONVOLUTION_IR_NAME = "key_convolution_ir_name"
    private const val KEY_SCAN_FOLDER_URI = "key_scan_folder_uri"
    private const val KEY_THEME_MODE = "key_theme_mode"

    const val SURROUND_MODE_STEREO = 0
    const val SURROUND_MODE_5_1 = 1
    const val SURROUND_MODE_7_1 = 2

    private val albumArtBaseUri: Uri = Uri.parse("content://media/external/audio/albumart")

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var initialized = false
    private var playbackServiceStarted = false

    private var currentAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var environmentalReverb: EnvironmentalReverb? = null
    private var hrtfBinauralProcessor: HrtfBinauralProcessor? = null
    private var convolutionReverbProcessor: ConvolutionReverbProcessor? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            syncPlaybackState()
            syncRealtimeEffectsMeter()
            mainHandler.postDelayed(this, 350L)
        }
    }

    private val _libraryState = MutableStateFlow(MediaLibraryUiState())
    val libraryState: StateFlow<MediaLibraryUiState> = _libraryState.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    private val _effectsState = MutableStateFlow(EffectsUiState())
    val effectsState: StateFlow<EffectsUiState> = _effectsState.asStateFlow()

    private val _settingsState = MutableStateFlow(SettingsUiState())
    val settingsState: StateFlow<SettingsUiState> = _settingsState.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            syncPlaybackState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncPlaybackState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncPlaybackState()
        }

        override fun onPlayerError(error: PlaybackException) {
            syncPlaybackState(error.message ?: "播放失败")
        }

        override fun onEvents(player: Player, events: Player.Events) {
            val sessionId = this@AudioEngine.player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
            if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != currentAudioSessionId) {
                attachAudioEffects(sessionId)
            }
            syncPlaybackState()
        }
    }

    fun initialize(context: Context) {
        if (initialized) {
            return
        }
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        initialized = true

        val scanUri = prefs.getString(KEY_SCAN_FOLDER_URI, null)?.let(Uri::parse)
        val themeMode = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        val legacySpatialX = prefs.getInt(KEY_SPATIAL_X, 0).coerceIn(-100, 100)
        val legacySpatialY = prefs.getInt(KEY_SPATIAL_Y, 0).coerceIn(-100, 100)
        val legacySpatialZ = prefs.getInt(KEY_SPATIAL_Z, 0).coerceIn(-100, 100)

        val storedEffects = EffectsUiState(
            enabled = prefs.getBoolean(KEY_EFFECT_ENABLED, true),
            bassStrength = prefs.getInt(KEY_BASS_STRENGTH, 400).coerceIn(0, 1000),
            virtualizerStrength = prefs.getInt(KEY_VIRTUALIZER_STRENGTH, 300).coerceIn(0, 1000),
            loudnessGainMb = prefs.getInt(KEY_LOUDNESS_GAIN_MB, 0).coerceIn(0, 2000),
            eqBandLevelsMb = List(8) { index ->
                prefs.getInt("$KEY_EQ_BAND_LEVEL_PREFIX$index", 0).coerceIn(-1200, 1200)
            },
            spatialEnabled = prefs.getBoolean(KEY_SPATIAL_ENABLED, false),
            channelSeparated = prefs.getBoolean(KEY_CHANNEL_SEPARATED, false),
            spatialLeftX = prefs.getInt(KEY_SPATIAL_LEFT_X, legacySpatialX).coerceIn(-100, 100),
            spatialLeftY = prefs.getInt(KEY_SPATIAL_LEFT_Y, legacySpatialY).coerceIn(-100, 100),
            spatialLeftZ = prefs.getInt(KEY_SPATIAL_LEFT_Z, legacySpatialZ).coerceIn(-100, 100),
            spatialRightX = prefs.getInt(KEY_SPATIAL_RIGHT_X, legacySpatialX).coerceIn(-100, 100),
            spatialRightY = prefs.getInt(KEY_SPATIAL_RIGHT_Y, legacySpatialY).coerceIn(-100, 100),
            spatialRightZ = prefs.getInt(KEY_SPATIAL_RIGHT_Z, legacySpatialZ).coerceIn(-100, 100),
            spatialLeftRadiusPercent = prefs.getInt(KEY_SPATIAL_LEFT_RADIUS_PERCENT, 100).coerceIn(20, 100),
            spatialRightRadiusPercent = prefs.getInt(KEY_SPATIAL_RIGHT_RADIUS_PERCENT, 100).coerceIn(20, 100),
            roomSize = prefs.getInt(KEY_ROOM_SIZE, 55).coerceIn(0, 100),
            roomDamping = prefs.getInt(KEY_ROOM_DAMPING, 35).coerceIn(0, 100),
            earlyReflection = prefs.getInt(KEY_EARLY_REFLECTION, 45).coerceIn(0, 100),
            wetMix = prefs.getInt(KEY_WET_MIX, 40).coerceIn(0, 100),
            perceptualCompression = prefs.getInt(KEY_PERCEPTUAL_COMPRESSION, 45).coerceIn(0, 100),
            hrtfEnabled = prefs.getBoolean(KEY_HRTF_ENABLED, true),
            hrtfUseDatabase = prefs.getBoolean(KEY_HRTF_DB_ENABLED, true),
            hrtfHeadRadiusMm = prefs.getInt(KEY_HRTF_HEAD_RADIUS_MM, 87).coerceIn(70, 110),
            hrtfBlendPercent = prefs.getInt(KEY_HRTF_BLEND_PERCENT, 78).coerceIn(0, 100),
            hrtfCrossfeedPercent = prefs.getInt(KEY_HRTF_CROSSFEED_PERCENT, 28).coerceIn(0, 100),
            hrtfExternalizationPercent = prefs.getInt(KEY_HRTF_EXTERNALIZATION_PERCENT, 55).coerceIn(0, 100),
            surroundMode = prefs.getInt(KEY_SURROUND_MODE, SURROUND_MODE_STEREO)
                .coerceIn(SURROUND_MODE_STEREO, SURROUND_MODE_7_1),
            convolutionEnabled = prefs.getBoolean(KEY_CONVOLUTION_ENABLED, false),
            convolutionWetPercent = prefs.getInt(KEY_CONVOLUTION_WET_PERCENT, 35).coerceIn(0, 100),
            convolutionIrUri = prefs.getString(KEY_CONVOLUTION_IR_URI, null),
            convolutionIrName = prefs.getString(KEY_CONVOLUTION_IR_NAME, "未导入 IRS")
                ?: "未导入 IRS"
        )
        _effectsState.value = withSpatialDerived(storedEffects)
        _settingsState.value = _settingsState.value.copy(
            hiFiMode = prefs.getBoolean(KEY_HIFI_MODE, true),
            scanFolderUri = scanUri,
            scanFolderLabel = buildScanFolderLabel(scanUri),
            themeMode = themeMode
        )

        buildPlayer()
        restoreConvolutionImpulseIfNeeded(storedEffects.convolutionIrUri)
        refreshOutputInfo()
        refreshLibrary()
        mainHandler.post(progressTicker)
    }

    fun getThemeMode(): Int = _settingsState.value.themeMode

    fun refreshLibrary() {
        if (!initialized) {
            return
        }
        _libraryState.value = _libraryState.value.copy(
            loading = true,
            errorMessage = null,
            scanFolderLabel = _settingsState.value.scanFolderLabel
        )

        val scanFolderUri = _settingsState.value.scanFolderUri
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { queryTracks(scanFolderUri) }
            }
            val tracks = result.getOrDefault(emptyList())
            val albums = tracks
                .groupBy { it.album.ifBlank { "未知专辑" } }
                .toList()
                .sortedBy { it.first.lowercase() }
                .map { (name, groupedTracks) ->
                    TrackSection(name, groupedTracks.sortedBy { it.title.lowercase() })
                }
            val artists = tracks
                .groupBy { it.artist.ifBlank { "未知艺术家" } }
                .toList()
                .sortedBy { it.first.lowercase() }
                .map { (name, groupedTracks) ->
                    TrackSection(name, groupedTracks.sortedBy { it.title.lowercase() })
                }

            _libraryState.value = MediaLibraryUiState(
                loading = false,
                tracks = tracks.sortedBy { it.title.lowercase() },
                albums = albums,
                artists = artists,
                scanFolderLabel = _settingsState.value.scanFolderLabel,
                errorMessage = if (tracks.isEmpty()) result.exceptionOrNull()?.message else null
            )
        }
    }

    fun setScanFolderUri(uri: Uri?) {
        val editor = prefs.edit()
        if (uri == null) {
            editor.remove(KEY_SCAN_FOLDER_URI)
        } else {
            editor.putString(KEY_SCAN_FOLDER_URI, uri.toString())
        }
        editor.apply()

        _settingsState.value = _settingsState.value.copy(
            scanFolderUri = uri,
            scanFolderLabel = buildScanFolderLabel(uri)
        )
        refreshLibrary()
    }

    fun setThemeMode(mode: Int) {
        _settingsState.value = _settingsState.value.copy(themeMode = mode)
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
    }

    fun loadTrack(uri: Uri, title: String?) {
        playTrack(
            LibraryTrack(
                id = uri.hashCode().toLong(),
                uri = uri,
                title = title ?: "本地音频",
                artist = "未知艺术家",
                album = "未知专辑",
                durationMs = 0L,
                albumId = 0L,
                artworkUri = null
            )
        )
    }

    fun playTrack(track: LibraryTrack) {
        if (!initialized) {
            return
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.artworkUri)
            .build()
        val item = MediaItem.Builder()
            .setUri(track.uri)
            .setMediaId(track.id.toString())
            .setMediaMetadata(metadata)
            .build()

        AudioPlaybackService.start(appContext)
        player?.apply {
            setMediaItem(item)
            prepare()
            play()
        }
    }

    fun togglePlayPause() {
        val currentPlayer = player ?: return
        if (currentPlayer.currentMediaItem == null) {
            return
        }
        if (currentPlayer.isPlaying) {
            currentPlayer.pause()
        } else {
            AudioPlaybackService.start(appContext)
            currentPlayer.play()
        }
    }

    fun play() {
        val currentPlayer = player ?: return
        if (currentPlayer.currentMediaItem == null) {
            return
        }
        AudioPlaybackService.start(appContext)
        currentPlayer.play()
    }

    fun pause() {
        player?.pause()
    }

    fun clearCurrentTrack() {
        player?.stop()
        player?.clearMediaItems()
        syncPlaybackState()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun seekBy(deltaMs: Long) {
        val currentPlayer = player ?: return
        val duration = if (currentPlayer.duration > 0L) currentPlayer.duration else Long.MAX_VALUE
        val target = (currentPlayer.currentPosition + deltaMs).coerceIn(0L, duration)
        currentPlayer.seekTo(target)
    }

    fun setHiFiMode(enabled: Boolean) {
        _settingsState.value = _settingsState.value.copy(hiFiMode = enabled)
        prefs.edit().putBoolean(KEY_HIFI_MODE, enabled).apply()
        applyHiFiMode(enabled)
        syncPlaybackState()
    }

    fun setEffectsEnabled(enabled: Boolean) {
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(enabled = enabled))
        prefs.edit().putBoolean(KEY_EFFECT_ENABLED, enabled).apply()
        applyEffectState()
    }

    fun setBassStrength(value: Int) {
        val clamped = value.coerceIn(0, 1000)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(bassStrength = clamped))
        prefs.edit().putInt(KEY_BASS_STRENGTH, clamped).apply()
        applyEffectState()
    }

    fun setVirtualizerStrength(value: Int) {
        val clamped = value.coerceIn(0, 1000)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(virtualizerStrength = clamped))
        prefs.edit().putInt(KEY_VIRTUALIZER_STRENGTH, clamped).apply()
        applyEffectState()
    }

    fun setLoudnessGainMb(value: Int) {
        val clamped = value.coerceIn(0, 2000)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(loudnessGainMb = clamped))
        prefs.edit().putInt(KEY_LOUDNESS_GAIN_MB, clamped).apply()
        applyEffectState()
    }

    fun setEqBandLevel(index: Int, levelMb: Int) {
        val current = _effectsState.value
        if (index !in current.eqBandLevelsMb.indices) {
            return
        }
        val clamped = levelMb.coerceIn(current.eqBandLevelMinMb, current.eqBandLevelMaxMb)
        val updated = current.eqBandLevelsMb.toMutableList()
        updated[index] = clamped
        _effectsState.value = withSpatialDerived(current.copy(eqBandLevelsMb = updated))
        prefs.edit().putInt("$KEY_EQ_BAND_LEVEL_PREFIX$index", clamped).apply()
        applyEffectState()
    }

    fun setBandLevel(index: Int, level: Int) {
        setEqBandLevel(index, level)
    }

    fun setSpatialEnabled(enabled: Boolean) {
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(spatialEnabled = enabled))
        prefs.edit().putBoolean(KEY_SPATIAL_ENABLED, enabled).apply()
        applyEffectState()
    }

    fun setChannelSeparated(enabled: Boolean) {
        val state = _effectsState.value
        val updated = if (enabled) {
            state.copy(channelSeparated = true)
        } else {
            val mergedX = ((state.spatialLeftX + state.spatialRightX) / 2).coerceIn(-100, 100)
            val mergedY = ((state.spatialLeftY + state.spatialRightY) / 2).coerceIn(-100, 100)
            val mergedZ = ((state.spatialLeftZ + state.spatialRightZ) / 2).coerceIn(-100, 100)
            val mergedRadius =
                ((state.spatialLeftRadiusPercent + state.spatialRightRadiusPercent) / 2).coerceIn(20, 100)
            state.copy(
                channelSeparated = false,
                spatialLeftX = mergedX.coerceIn(-mergedRadius, mergedRadius),
                spatialLeftY = mergedY,
                spatialLeftZ = mergedZ.coerceIn(-mergedRadius, mergedRadius),
                spatialRightX = mergedX.coerceIn(-mergedRadius, mergedRadius),
                spatialRightY = mergedY,
                spatialRightZ = mergedZ.coerceIn(-mergedRadius, mergedRadius),
                spatialLeftRadiusPercent = mergedRadius,
                spatialRightRadiusPercent = mergedRadius
            )
        }
        _effectsState.value = withSpatialDerived(updated)
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialPositionX(value: Int) {
        val radius = _effectsState.value.spatialLeftRadiusPercent.coerceIn(20, 100)
        val clamped = value.coerceIn(-radius, radius)
        _effectsState.value = withSpatialDerived(
            _effectsState.value.copy(
                spatialLeftX = clamped,
                spatialRightX = clamped
            )
        )
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialPositionY(value: Int) {
        val clamped = value.coerceIn(-100, 100)
        _effectsState.value = withSpatialDerived(
            _effectsState.value.copy(
                spatialLeftY = clamped,
                spatialRightY = clamped
            )
        )
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialPositionZ(value: Int) {
        val radius = _effectsState.value.spatialLeftRadiusPercent.coerceIn(20, 100)
        val clamped = value.coerceIn(-radius, radius)
        _effectsState.value = withSpatialDerived(
            _effectsState.value.copy(
                spatialLeftZ = clamped,
                spatialRightZ = clamped
            )
        )
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialLeftPositionX(value: Int) {
        val radius = _effectsState.value.spatialLeftRadiusPercent.coerceIn(20, 100)
        val clamped = value.coerceIn(-radius, radius)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(spatialLeftX = clamped))
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialLeftPositionY(value: Int) {
        val clamped = value.coerceIn(-100, 100)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(spatialLeftY = clamped))
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialLeftPositionZ(value: Int) {
        val radius = _effectsState.value.spatialLeftRadiusPercent.coerceIn(20, 100)
        val clamped = value.coerceIn(-radius, radius)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(spatialLeftZ = clamped))
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialRightPositionX(value: Int) {
        val radius = _effectsState.value.spatialRightRadiusPercent.coerceIn(20, 100)
        val clamped = value.coerceIn(-radius, radius)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(spatialRightX = clamped))
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialRightPositionY(value: Int) {
        val clamped = value.coerceIn(-100, 100)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(spatialRightY = clamped))
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialRightPositionZ(value: Int) {
        val radius = _effectsState.value.spatialRightRadiusPercent.coerceIn(20, 100)
        val clamped = value.coerceIn(-radius, radius)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(spatialRightZ = clamped))
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialRadiusPercent(value: Int) {
        val clamped = value.coerceIn(20, 100)
        val state = _effectsState.value
        _effectsState.value = withSpatialDerived(
            state.copy(
                spatialLeftRadiusPercent = clamped,
                spatialRightRadiusPercent = clamped,
                spatialLeftX = state.spatialLeftX.coerceIn(-clamped, clamped),
                spatialLeftZ = state.spatialLeftZ.coerceIn(-clamped, clamped),
                spatialRightX = state.spatialRightX.coerceIn(-clamped, clamped),
                spatialRightZ = state.spatialRightZ.coerceIn(-clamped, clamped)
            )
        )
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialLeftRadiusPercent(value: Int) {
        val clamped = value.coerceIn(20, 100)
        val state = _effectsState.value
        _effectsState.value = withSpatialDerived(
            state.copy(
                spatialLeftRadiusPercent = clamped,
                spatialLeftX = state.spatialLeftX.coerceIn(-clamped, clamped),
                spatialLeftZ = state.spatialLeftZ.coerceIn(-clamped, clamped)
            )
        )
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialRightRadiusPercent(value: Int) {
        val clamped = value.coerceIn(20, 100)
        val state = _effectsState.value
        _effectsState.value = withSpatialDerived(
            state.copy(
                spatialRightRadiusPercent = clamped,
                spatialRightX = state.spatialRightX.coerceIn(-clamped, clamped),
                spatialRightZ = state.spatialRightZ.coerceIn(-clamped, clamped)
            )
        )
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setRoomSize(value: Int) {
        val clamped = value.coerceIn(0, 100)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(roomSize = clamped))
        prefs.edit().putInt(KEY_ROOM_SIZE, clamped).apply()
        applyEffectState()
    }

    fun setRoomDamping(value: Int) {
        val clamped = value.coerceIn(0, 100)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(roomDamping = clamped))
        prefs.edit().putInt(KEY_ROOM_DAMPING, clamped).apply()
        applyEffectState()
    }

    fun setEarlyReflection(value: Int) {
        val clamped = value.coerceIn(0, 100)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(earlyReflection = clamped))
        prefs.edit().putInt(KEY_EARLY_REFLECTION, clamped).apply()
        applyEffectState()
    }

    fun setWetMix(value: Int) {
        val clamped = value.coerceIn(0, 100)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(wetMix = clamped))
        prefs.edit().putInt(KEY_WET_MIX, clamped).apply()
        applyEffectState()
    }

    fun setPerceptualCompression(value: Int) {
        val clamped = value.coerceIn(0, 100)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(perceptualCompression = clamped))
        prefs.edit().putInt(KEY_PERCEPTUAL_COMPRESSION, clamped).apply()
        applyEffectState()
    }

    fun setHrtfEnabled(enabled: Boolean) {
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(hrtfEnabled = enabled))
        prefs.edit().putBoolean(KEY_HRTF_ENABLED, enabled).apply()
        applyEffectState()
    }

    fun setHrtfDatabaseEnabled(enabled: Boolean) {
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(hrtfUseDatabase = enabled))
        prefs.edit().putBoolean(KEY_HRTF_DB_ENABLED, enabled).apply()
        applyEffectState()
    }

    fun setHrtfHeadRadiusMm(value: Int) {
        val clamped = value.coerceIn(70, 110)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(hrtfHeadRadiusMm = clamped))
        prefs.edit().putInt(KEY_HRTF_HEAD_RADIUS_MM, clamped).apply()
        applyEffectState()
    }

    fun setHrtfBlendPercent(value: Int) {
        val clamped = value.coerceIn(0, 100)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(hrtfBlendPercent = clamped))
        prefs.edit().putInt(KEY_HRTF_BLEND_PERCENT, clamped).apply()
        applyEffectState()
    }

    fun setHrtfCrossfeedPercent(value: Int) {
        val clamped = value.coerceIn(0, 100)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(hrtfCrossfeedPercent = clamped))
        prefs.edit().putInt(KEY_HRTF_CROSSFEED_PERCENT, clamped).apply()
        applyEffectState()
    }

    fun setHrtfExternalizationPercent(value: Int) {
        val clamped = value.coerceIn(0, 100)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(hrtfExternalizationPercent = clamped))
        prefs.edit().putInt(KEY_HRTF_EXTERNALIZATION_PERCENT, clamped).apply()
        applyEffectState()
    }

    fun setSurroundMode(mode: Int) {
        val clamped = mode.coerceIn(SURROUND_MODE_STEREO, SURROUND_MODE_7_1)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(surroundMode = clamped))
        prefs.edit().putInt(KEY_SURROUND_MODE, clamped).apply()
        applyEffectState()
    }

    fun setConvolutionEnabled(enabled: Boolean) {
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(convolutionEnabled = enabled))
        prefs.edit().putBoolean(KEY_CONVOLUTION_ENABLED, enabled).apply()
        applyEffectState()
    }

    fun setConvolutionWetPercent(value: Int) {
        val clamped = value.coerceIn(0, 100)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(convolutionWetPercent = clamped))
        prefs.edit().putInt(KEY_CONVOLUTION_WET_PERCENT, clamped).apply()
        applyEffectState()
    }

    fun importConvolutionIr(uri: Uri) {
        if (!initialized) {
            return
        }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { loadImpulseResponseFromUri(uri) }
            }
            result.onSuccess { irAsset ->
                convolutionReverbProcessor?.updateImpulse(irAsset.samples)
                _effectsState.value = withSpatialDerived(
                    _effectsState.value.copy(
                        convolutionIrUri = uri.toString(),
                        convolutionIrName = irAsset.name
                    )
                )
                prefs.edit()
                    .putString(KEY_CONVOLUTION_IR_URI, uri.toString())
                    .putString(KEY_CONVOLUTION_IR_NAME, irAsset.name)
                    .apply()
                applyEffectState()
            }.onFailure {
                _effectsState.value = withSpatialDerived(
                    _effectsState.value.copy(
                        convolutionIrUri = null,
                        convolutionIrName = "IRS 导入失败"
                    )
                )
            }
        }
    }

    fun clearConvolutionIr() {
        convolutionReverbProcessor?.updateImpulse(null)
        _effectsState.value = withSpatialDerived(
            _effectsState.value.copy(
                convolutionIrUri = null,
                convolutionIrName = "未导入 IRS",
                convolutionEnabled = false
            )
        )
        prefs.edit()
            .remove(KEY_CONVOLUTION_IR_URI)
            .remove(KEY_CONVOLUTION_IR_NAME)
            .putBoolean(KEY_CONVOLUTION_ENABLED, false)
            .apply()
        applyEffectState()
    }

    fun setReverbEnabled(enabled: Boolean) {
        setSpatialEnabled(enabled)
    }

    fun setReverbPreset(value: Int) {
        val clamped = value.coerceIn(0, 6)
        setRoomSize((20 + clamped * 13).coerceIn(0, 100))
    }

    fun refreshOutputInfo() {
        if (!initialized) {
            return
        }
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        val sampleRate = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
        val frames = audioManager
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()
        val offload = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate ?: 48_000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            AudioManager.isOffloadedPlaybackSupported(format, attrs)
        } else {
            false
        }
        _settingsState.value = _settingsState.value.copy(
            outputSampleRateHz = sampleRate,
            outputFramesPerBuffer = frames,
            offloadSupported = offload
        )
    }

    fun release() {
        mainHandler.removeCallbacks(progressTicker)
        player?.removeListener(playerListener)
        player?.release()
        player = null
        hrtfBinauralProcessor = null
        convolutionReverbProcessor = null
        releaseAudioEffects()
        AudioPlaybackService.stop(appContext)
        playbackServiceStarted = false
        initialized = false
    }

    @UnstableApi
    private fun buildPlayer() {
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        hrtfBinauralProcessor = HrtfBinauralProcessor()
        convolutionReverbProcessor = ConvolutionReverbProcessor()
        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(hrtfBinauralProcessor!!, convolutionReverbProcessor!!))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }

        player = ExoPlayer.Builder(appContext, renderersFactory)
            .build()
            .apply {
            setAudioAttributes(attrs, true)
            setHandleAudioBecomingNoisy(true)
            addListener(playerListener)
        }
        applyHiFiMode(_settingsState.value.hiFiMode)
        applyEffectState()
    }

    private fun applyHiFiMode(enabled: Boolean) {
        player?.setSkipSilenceEnabled(false)
        val maybeMethod = player?.javaClass?.methods?.firstOrNull {
            it.name == "experimentalSetOffloadSchedulingEnabled" && it.parameterTypes.size == 1
        }
        runCatching {
            maybeMethod?.invoke(player, enabled)
        }
    }

    private fun attachAudioEffects(audioSessionId: Int) {
        releaseAudioEffects()
        currentAudioSessionId = audioSessionId

        equalizer = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, audioSessionId) }.getOrNull()
        loudnessEnhancer = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
        environmentalReverb = runCatching { EnvironmentalReverb(0, audioSessionId) }.getOrNull()

        val eq = equalizer
        if (eq != null) {
            val range = eq.bandLevelRange
            val min = range.getOrNull(0)?.toInt() ?: -1500
            val max = range.getOrNull(1)?.toInt() ?: 1500
            val desiredMin = maxOf(-1200, min)
            val desiredMax = minOf(1200, max)
            val clampedLevels = _effectsState.value.eqBandLevelsMb.map { it.coerceIn(desiredMin, desiredMax) }
            _effectsState.value = withSpatialDerived(
                _effectsState.value.copy(
                    eqBandLevelsMb = clampedLevels,
                    eqBandLevelMinMb = desiredMin,
                    eqBandLevelMaxMb = desiredMax
                )
            )
        }

        applyEffectState()
        syncPlaybackState()
    }

    private fun releaseAudioEffects() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudnessEnhancer?.release() }
        runCatching { environmentalReverb?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        environmentalReverb = null
        currentAudioSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun applyEffectState() {
        val state = _effectsState.value
        val model = buildSpatialModel(state)
        val finalCurve = composeFinalEqCurveMb(state, model)

        updateHrtfProcessor(state)
        updateConvolutionProcessor(state)

        player?.volume = if (state.enabled && state.spatialEnabled) {
            model.gainLinear.coerceIn(0.08f, 1f)
        } else {
            1f
        }

        runCatching {
            equalizer?.enabled = state.enabled
            val eq = equalizer ?: return@runCatching
            val hardwareBandCount = eq.numberOfBands.toInt()
            for (hardwareBand in 0 until hardwareBandCount) {
                val centerHz = eq.getCenterFreq(hardwareBand.toShort()).toFloat() / 1000f
                val value = interpolateEqLevelMb(centerHz, state.eqBandFrequenciesHz, finalCurve)
                    .coerceIn(state.eqBandLevelMinMb, state.eqBandLevelMaxMb)
                eq.setBandLevel(hardwareBand.toShort(), value.toShort())
            }
        }

        runCatching {
            bassBoost?.enabled = state.enabled
            bassBoost?.setStrength(state.bassStrength.toShort())
        }

        runCatching {
            virtualizer?.enabled = state.enabled
            virtualizer?.setStrength(state.virtualizerStrength.toShort())
        }

        runCatching {
            loudnessEnhancer?.enabled = state.enabled
            loudnessEnhancer?.setTargetGain(state.loudnessGainMb)
        }

        runCatching {
            val reverbEnabled = state.enabled && state.spatialEnabled
            val reverb = environmentalReverb
            if (reverb != null) {
                reverb.enabled = reverbEnabled
                if (reverbEnabled) {
                    reverb.setRoomLevel(model.roomLevelMb.toShort())
                    reverb.setRoomHFLevel(model.roomHFLevelMb.toShort())
                    reverb.setDecayTime(model.decayTimeMs)
                    reverb.setDecayHFRatio(model.decayHFRatio.toShort())
                    reverb.setReflectionsLevel(model.reflectionsLevelMb.toShort())
                    reverb.setReflectionsDelay(model.reflectionsDelayMs)
                    reverb.setReverbLevel(model.reverbLevelMb.toShort())
                    reverb.setReverbDelay(model.reverbDelayMs)
                    reverb.setDensity(model.density.toShort())
                    reverb.setDiffusion(model.diffusion.toShort())
                }
            }
        }

        val derived = withSpatialDerived(state)
        if (derived != state) {
            _effectsState.value = derived
        }
    }

    private fun updateHrtfProcessor(state: EffectsUiState) {
        val rightX = if (state.channelSeparated) state.spatialRightX else state.spatialLeftX
        val rightY = if (state.channelSeparated) state.spatialRightY else state.spatialLeftY
        val rightZ = if (state.channelSeparated) state.spatialRightZ else state.spatialLeftZ
        hrtfBinauralProcessor?.updateConfig(
            HrtfRenderConfig(
                enabled = state.enabled && state.hrtfEnabled,
                spatialEnabled = state.spatialEnabled,
                channelSeparated = state.channelSeparated,
                leftXNorm = state.spatialLeftX / 100f,
                leftYNorm = state.spatialLeftY / 100f,
                leftZNorm = state.spatialLeftZ / 100f,
                rightXNorm = rightX / 100f,
                rightYNorm = rightY / 100f,
                rightZNorm = rightZ / 100f,
                roomDampingNorm = state.roomDamping / 100f,
                wetMixNorm = state.wetMix / 100f,
                headRadiusMeters = state.hrtfHeadRadiusMm / 1000f,
                blend = state.hrtfBlendPercent / 100f,
                crossfeed = state.hrtfCrossfeedPercent / 100f,
                externalization = state.hrtfExternalizationPercent / 100f,
                useHrtfDatabase = state.hrtfUseDatabase,
                surroundMode = state.surroundMode
            )
        )
    }

    private fun updateConvolutionProcessor(state: EffectsUiState) {
        convolutionReverbProcessor?.updateConfig(
            enabled = state.enabled && state.convolutionEnabled && !state.convolutionIrUri.isNullOrBlank(),
            wetMix = state.convolutionWetPercent / 100f
        )
    }

    private fun withSpatialDerived(state: EffectsUiState): EffectsUiState {
        val model = buildSpatialModel(state)
        return state.copy(
            derivedDistanceMeters = model.distanceMeters,
            derivedGainDb = model.gainDb
        )
    }

    private fun syncRealtimeEffectsMeter() {
        val meter = convolutionReverbProcessor?.readRealtimeMeterPercent() ?: 0
        val current = _effectsState.value
        if (current.realtimeReverbMeterPercent == meter) {
            return
        }
        _effectsState.value = current.copy(realtimeReverbMeterPercent = meter)
    }

    private fun buildSpatialModel(state: EffectsUiState): SpatialModel {
        val center = effectiveSpatialCenter(state)
        val x = center.x / 100f
        val y = center.y / 100f
        val z = center.z / 100f

        val distanceNorm = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val distanceMeters = 0.35f + distanceNorm * 5.8f
        val inverseSquare = 1f / (1f + (distanceMeters / 1.2f).pow(2))
        val compression = state.perceptualCompression / 100f
        val compressedGain = inverseSquare.pow(1f - 0.75f * compression)
        val gainLinear = (0.18f + compressedGain * 0.82f).coerceIn(0.05f, 1f)
        val gainDb = (20f * log10(gainLinear.coerceAtLeast(0.0001f)))

        val room = state.roomSize / 100f
        val damping = state.roomDamping / 100f
        val early = state.earlyReflection / 100f
        val wet = state.wetMix / 100f

        val back = (-z).coerceAtLeast(0f)
        val front = z.coerceAtLeast(0f)

        val lowTiltMb = (-y * 420f) + (back * 220f)
        val highTiltMb = (y * 480f) + (front * 220f) - (back * 680f)

        val roomLevelMb = (-7500 + room * 3600 - distanceMeters * 260).toInt().coerceIn(-9000, 0)
        val roomHFLevelMb =
            (-6500 + (1f - damping) * 2800 + front * 300 - back * 1000).toInt().coerceIn(-9000, 0)
        val decayTimeMs = (450 + room * 7000).toInt().coerceIn(100, 20000)
        val decayHFRatio = (350 + (1f - damping) * 1300 - back * 180).toInt().coerceIn(100, 2000)
        val reflectionsLevelMb =
            (-7600 + wet * 6200 + early * 2200 + front * 260 - damping * 700).toInt().coerceIn(-9000, 1000)
        val reflectionsDelayMs = (6 + room * 110 + distanceMeters * 8).toInt().coerceIn(0, 300)
        val reverbLevelMb = (-7600 + wet * 9200 + room * 900 - distanceMeters * 420).toInt().coerceIn(-9000, 2000)
        val reverbDelayMs = (18 + room * 70 + early * 16).toInt().coerceIn(0, 100)
        val density = (280 + room * 720).toInt().coerceIn(0, 1000)
        val diffusion = (250 + early * 740).toInt().coerceIn(0, 1000)

        return SpatialModel(
            gainLinear = gainLinear,
            gainDb = gainDb,
            distanceMeters = distanceMeters,
            lowTiltMb = lowTiltMb,
            highTiltMb = highTiltMb,
            roomLevelMb = roomLevelMb,
            roomHFLevelMb = roomHFLevelMb,
            decayTimeMs = decayTimeMs,
            decayHFRatio = decayHFRatio,
            reflectionsLevelMb = reflectionsLevelMb,
            reflectionsDelayMs = reflectionsDelayMs,
            reverbLevelMb = reverbLevelMb,
            reverbDelayMs = reverbDelayMs,
            density = density,
            diffusion = diffusion
        )
    }

    private fun effectiveSpatialCenter(state: EffectsUiState): SpatialVector {
        if (!state.channelSeparated) {
            return SpatialVector(state.spatialLeftX, state.spatialLeftY, state.spatialLeftZ)
        }
        return SpatialVector(
            x = ((state.spatialLeftX + state.spatialRightX) / 2).coerceIn(-100, 100),
            y = ((state.spatialLeftY + state.spatialRightY) / 2).coerceIn(-100, 100),
            z = ((state.spatialLeftZ + state.spatialRightZ) / 2).coerceIn(-100, 100)
        )
    }

    private fun persistSpatialPosition(state: EffectsUiState) {
        val center = effectiveSpatialCenter(state)
        prefs.edit()
            .putBoolean(KEY_CHANNEL_SEPARATED, state.channelSeparated)
            .putInt(KEY_SPATIAL_LEFT_X, state.spatialLeftX)
            .putInt(KEY_SPATIAL_LEFT_Y, state.spatialLeftY)
            .putInt(KEY_SPATIAL_LEFT_Z, state.spatialLeftZ)
            .putInt(KEY_SPATIAL_RIGHT_X, state.spatialRightX)
            .putInt(KEY_SPATIAL_RIGHT_Y, state.spatialRightY)
            .putInt(KEY_SPATIAL_RIGHT_Z, state.spatialRightZ)
            .putInt(KEY_SPATIAL_LEFT_RADIUS_PERCENT, state.spatialLeftRadiusPercent)
            .putInt(KEY_SPATIAL_RIGHT_RADIUS_PERCENT, state.spatialRightRadiusPercent)
            .putInt(KEY_SPATIAL_X, center.x)
            .putInt(KEY_SPATIAL_Y, center.y)
            .putInt(KEY_SPATIAL_Z, center.z)
            .apply()
    }

    private data class SpatialVector(
        val x: Int,
        val y: Int,
        val z: Int
    )

    private data class ImpulseResponseAsset(
        val name: String,
        val samples: FloatArray
    )

    private fun restoreConvolutionImpulseIfNeeded(uriString: String?) {
        if (uriString.isNullOrBlank()) {
            convolutionReverbProcessor?.updateImpulse(null)
            return
        }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { loadImpulseResponseFromUri(Uri.parse(uriString)) }
            }
            result.onSuccess { irAsset ->
                convolutionReverbProcessor?.updateImpulse(irAsset.samples)
                _effectsState.value = withSpatialDerived(
                    _effectsState.value.copy(
                        convolutionIrUri = uriString,
                        convolutionIrName = irAsset.name
                    )
                )
                applyEffectState()
            }.onFailure {
                convolutionReverbProcessor?.updateImpulse(null)
                _effectsState.value = withSpatialDerived(
                    _effectsState.value.copy(
                        convolutionEnabled = false,
                        convolutionIrName = "IRS 加载失败"
                    )
                )
                prefs.edit().putBoolean(KEY_CONVOLUTION_ENABLED, false).apply()
            }
        }
    }

    private fun loadImpulseResponseFromUri(uri: Uri): ImpulseResponseAsset {
        val resolver = appContext.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
            ?: uri.lastPathSegment
            ?: "IRS"

        if (!displayName.endsWith(".irs", ignoreCase = true)) {
            throw IllegalArgumentException("仅支持 .irs 文件")
        }

        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(16 * 1024)
            val out = ByteArrayOutputStream()
            var read = input.read(buffer)
            var total = 0
            while (read > 0) {
                total += read
                if (total > 8 * 1024 * 1024) {
                    throw IllegalArgumentException("IRS 文件过大")
                }
                out.write(buffer, 0, read)
                read = input.read(buffer)
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("无法读取 IRS 文件")

        val rawImpulse = parseWavImpulse(bytes) ?: parseRawPcmImpulse(bytes)
        val prepared = prepareImpulse(rawImpulse)
        if (prepared.isEmpty()) {
            throw IllegalArgumentException("IRS 数据为空")
        }
        return ImpulseResponseAsset(
            name = displayName,
            samples = prepared
        )
    }

    private fun parseWavImpulse(bytes: ByteArray): FloatArray? {
        if (bytes.size < 44) return null
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null

        var offset = 12
        var channels = 0
        var bitsPerSample = 0
        var format = 0
        var dataStart = -1
        var dataSize = 0

        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4)
            val size = littleEndianInt(bytes, offset + 4)
            val payload = offset + 8
            if (payload + size > bytes.size) break
            when (id) {
                "fmt " -> {
                    format = littleEndianShort(bytes, payload).toInt() and 0xFFFF
                    channels = littleEndianShort(bytes, payload + 2).toInt() and 0xFFFF
                    bitsPerSample = littleEndianShort(bytes, payload + 14).toInt() and 0xFFFF
                }
                "data" -> {
                    dataStart = payload
                    dataSize = size
                    break
                }
            }
            offset = payload + size + (size and 1)
        }

        if (dataStart < 0 || dataSize <= 0 || channels <= 0) return null
        if (format != 1 || bitsPerSample != 16) return null

        val frameBytes = channels * 2
        val frameCount = dataSize / frameBytes
        if (frameCount <= 0) return null

        val output = FloatArray(frameCount)
        var readOffset = dataStart
        for (frame in 0 until frameCount) {
            var sum = 0f
            for (channel in 0 until channels) {
                val sample = littleEndianShort(bytes, readOffset).toInt().toShort()
                sum += sample.toInt() / 32768f
                readOffset += 2
            }
            output[frame] = (sum / channels).coerceIn(-1f, 1f)
        }
        return output
    }

    private fun parseRawPcmImpulse(bytes: ByteArray): FloatArray {
        val sampleCount = bytes.size / 2
        val output = FloatArray(sampleCount)
        var offset = 0
        for (index in 0 until sampleCount) {
            val sample = littleEndianShort(bytes, offset)
            output[index] = sample.toInt() / 32768f
            offset += 2
        }
        return output
    }

    private fun prepareImpulse(source: FloatArray): FloatArray {
        if (source.isEmpty()) {
            return FloatArray(0)
        }
        val leadingTrimmed = trimLeadingSilence(source)
        val trimmed = trimTrailingSilence(leadingTrimmed)
        if (trimmed.isEmpty()) {
            return FloatArray(0)
        }
        val maxTaps = 4096
        val output = if (trimmed.size > maxTaps) {
            trimmed.copyOf(maxTaps)
        } else {
            trimmed.copyOf()
        }
        var maxAbs = 0f
        for (i in output.indices) {
            maxAbs = max(maxAbs, abs(output[i]))
        }
        if (maxAbs > 0.0001f) {
            val gain = 0.92f / maxAbs
            for (i in output.indices) {
                output[i] = (output[i] * gain).coerceIn(-1f, 1f)
            }
        }
        applyImpulseTailFade(output)
        return output
    }

    private fun trimLeadingSilence(source: FloatArray): FloatArray {
        var start = 0
        while (start < source.size && abs(source[start]) < 0.001f) {
            start++
        }
        if (start >= source.size) {
            return FloatArray(0)
        }
        return source.copyOfRange(start, source.size)
    }

    private fun trimTrailingSilence(source: FloatArray): FloatArray {
        if (source.isEmpty()) {
            return source
        }
        var end = source.lastIndex
        while (end >= 0 && abs(source[end]) < 0.0008f) {
            end--
        }
        if (end < 0) {
            return FloatArray(0)
        }
        return source.copyOfRange(0, end + 1)
    }

    private fun applyImpulseTailFade(samples: FloatArray) {
        if (samples.size < 8) {
            return
        }
        val fadeLength = min(320, max(24, samples.size / 3))
        if (fadeLength >= samples.size) {
            return
        }
        val fadeStart = samples.size - fadeLength
        val denominator = max(1f, (fadeLength - 1).toFloat())
        for (index in fadeStart until samples.size) {
            val ratio = ((index - fadeStart).toFloat() / denominator).coerceIn(0f, 1f)
            val fade = 1f - ratio
            samples[index] *= fade * fade
        }
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Short {
        return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short
    }

    private fun composeFinalEqCurveMb(
        state: EffectsUiState,
        model: SpatialModel
    ): List<Int> {
        return state.eqBandFrequenciesHz.mapIndexed { index, freq ->
            val base = state.eqBandLevelsMb.getOrNull(index) ?: 0
            if (!state.spatialEnabled) {
                return@mapIndexed base.coerceIn(state.eqBandLevelMinMb, state.eqBandLevelMaxMb)
            }
            val freqFloat = freq.toFloat().coerceAtLeast(20f)
            val lowWeight = 1f / (1f + (freqFloat / 380f).pow(1.6f))
            val highWeight = 1f / (1f + (2200f / freqFloat).pow(1.45f))
            val spatialMb = model.lowTiltMb * lowWeight + model.highTiltMb * highWeight
            (base + spatialMb).toInt().coerceIn(state.eqBandLevelMinMb, state.eqBandLevelMaxMb)
        }
    }

    private fun interpolateEqLevelMb(
        targetHz: Float,
        anchorFreqHz: List<Int>,
        levelsMb: List<Int>
    ): Int {
        if (anchorFreqHz.isEmpty() || levelsMb.isEmpty()) {
            return 0
        }
        val size = minOf(anchorFreqHz.size, levelsMb.size)
        if (size == 1) {
            return levelsMb.first()
        }
        val clampedHz = targetHz.coerceAtLeast(anchorFreqHz.first().toFloat())
        if (clampedHz <= anchorFreqHz.first()) {
            return levelsMb.first()
        }
        if (clampedHz >= anchorFreqHz[size - 1]) {
            return levelsMb[size - 1]
        }
        for (index in 0 until size - 1) {
            val left = anchorFreqHz[index].toFloat()
            val right = anchorFreqHz[index + 1].toFloat()
            if (clampedHz in left..right) {
                val leftLog = ln(left)
                val rightLog = ln(right)
                val targetLog = ln(clampedHz)
                val ratio = ((targetLog - leftLog) / (rightLog - leftLog)).coerceIn(0f, 1f)
                val value = levelsMb[index] + (levelsMb[index + 1] - levelsMb[index]) * ratio
                return value.toInt()
            }
        }
        return levelsMb[size - 1]
    }

    private fun syncPlaybackState(errorMessage: String? = null) {
        val currentPlayer = player ?: return
        val hasMedia = currentPlayer.currentMediaItem != null
        val metadata = currentPlayer.currentMediaItem?.mediaMetadata
        val title = metadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "未选择音频"
        val artist = metadata?.artist?.toString().orEmpty()
        val album = metadata?.albumTitle?.toString().orEmpty()
        val subtitle = when {
            !hasMedia -> "支持 FLAC / WAV / MP3 / AAC"
            artist.isNotBlank() || album.isNotBlank() -> {
                listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")
            }

            _settingsState.value.hiFiMode -> "Hi-Fi 输出模式已开启"
            else -> "标准音频模式"
        }

        _playbackState.value = _playbackState.value.copy(
            title = title,
            subtitle = subtitle,
            artworkUri = metadata?.artworkUri,
            hasMedia = hasMedia,
            isPlaying = currentPlayer.isPlaying,
            durationMs = if (currentPlayer.duration > 0L) currentPlayer.duration else 0L,
            positionMs = currentPlayer.currentPosition.coerceAtLeast(0L),
            bufferedPositionMs = currentPlayer.bufferedPosition.coerceAtLeast(0L),
            audioSessionId = currentPlayer.audioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET } ?: 0,
            errorMessage = errorMessage
        )

        if (hasMedia && !playbackServiceStarted) {
            AudioPlaybackService.start(appContext)
            playbackServiceStarted = true
        } else if (!hasMedia && playbackServiceStarted) {
            AudioPlaybackService.stop(appContext)
            playbackServiceStarted = false
        }
    }

    private fun queryTracks(scanFolderUri: Uri?): List<LibraryTrack> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 10000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val relativePathPrefix = resolveRelativePathPrefix(scanFolderUri)
        val tracks = mutableListOf<LibraryTrack>()

        appContext.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val relativePathIndex = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val relativePath = if (relativePathIndex >= 0) cursor.getString(relativePathIndex).orEmpty() else ""
                if (!relativePathPrefix.isNullOrBlank()) {
                    if (relativePath.isBlank() || !relativePath.startsWith(relativePathPrefix, ignoreCase = true)) {
                        continue
                    }
                }

                val id = cursor.getLong(idIndex)
                val albumId = cursor.getLong(albumIdIndex)
                val contentUri = ContentUris.withAppendedId(uri, id)
                val artworkUri = if (albumId > 0) {
                    ContentUris.withAppendedId(albumArtBaseUri, albumId)
                } else {
                    null
                }
                tracks += LibraryTrack(
                    id = id,
                    uri = contentUri,
                    title = cursor.getString(titleIndex).orEmpty().ifBlank { "未知标题" },
                    artist = cursor.getString(artistIndex).orEmpty().ifBlank { "未知艺术家" },
                    album = cursor.getString(albumIndex).orEmpty().ifBlank { "未知专辑" },
                    durationMs = cursor.getLong(durationIndex),
                    albumId = albumId,
                    artworkUri = artworkUri
                )
            }
        }
        return tracks
    }

    private fun buildScanFolderLabel(uri: Uri?): String {
        if (uri == null) {
            return "全部媒体库"
        }
        val relativePath = resolveRelativePathPrefix(uri)?.trimEnd('/')
        return if (relativePath.isNullOrBlank()) {
            "外部存储根目录"
        } else {
            "扫描目录：$relativePath"
        }
    }

    private fun resolveRelativePathPrefix(scanFolderUri: Uri?): String? {
        if (scanFolderUri == null) {
            return null
        }
        val docId = runCatching { DocumentsContract.getTreeDocumentId(scanFolderUri) }.getOrNull() ?: return null
        val relative = docId.substringAfter(':', "").trim('/')
        return if (relative.isBlank()) null else "$relative/"
    }
}
