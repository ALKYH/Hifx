package com.alky.hifx.audio

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.audiofx.HapticGenerator
import android.media.audiofx.BassBoost
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.Renderer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.alky.hifx.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class LibraryTrack(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val albumId: Long,
    val artworkUri: Uri?,
    val dateAddedEpochSeconds: Long = 0L,
    val playCount: Int = 0,
    val discNumber: Int = 1,
    val trackNumber: Int = 0
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
    val title: String = "鏈€夋嫨闊抽",
    val subtitle: String = "鏀寔 FLAC / WAV / MP3 / AAC",
    val artist: String = "",
    val album: String = "",
    val mediaUri: Uri? = null,
    val artworkUri: Uri? = null,
    val hasMedia: Boolean = false,
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val audioSessionId: Int = 0,
    val errorMessage: String? = null,
    val queueSize: Int = 0,
    val queueIndex: Int = -1,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val sleepTimerRemainingMs: Long = 0L,
    val streamInfoVisible: Boolean = false,
    val streamInfoLeft: String = "",
    val streamInfoCenter: String = "",
    val streamInfoRight: String = "",
    val streamInfoUseDacAccent: Boolean = false,
    val streamInfoUseIsoDacTheme: Boolean = false
)

private val DEFAULT_EQ_BAND_FREQUENCIES_HZ = listOf(32, 64, 125, 250, 500, 1000, 4000, 8000)
private val DEFAULT_EQ_BAND_Q_TIMES100 = List(DEFAULT_EQ_BAND_FREQUENCIES_HZ.size) { 100 }
private const val EQ_PRESET_CUSTOM_NAME = "自定义"

data class EffectsUiState(
    val enabled: Boolean = true,
    val eqEnabled: Boolean = true,
    val limiterEnabled: Boolean = false,
    val panPercent: Int = 0,
    val panInvertEnabled: Boolean = false,
    val monoEnabled: Boolean = false,
    val vocalRemovalEnabled: Boolean = false,
    val vocalKeyShiftSemitones: Int = 0,
    val vocalBandLowCutHz: Int = 140,
    val vocalBandHighCutHz: Int = 4200,
    val phaseInvertEnabled: Boolean = false,
    val rightChannelPhaseInvertEnabled: Boolean = false,
    val crossfeedPercent: Int = 0,
    val playbackSpeedPercent: Int = 100,
    val playbackSpeedPitchCompensationEnabled: Boolean = true,
    val bassStrength: Int = 400,
    val virtualizerStrength: Int = 300,
    val loudnessGainMb: Int = 0,
    val eqBandFrequenciesHz: List<Int> = DEFAULT_EQ_BAND_FREQUENCIES_HZ,
    val eqBandLevelsMb: List<Int> = List(DEFAULT_EQ_BAND_FREQUENCIES_HZ.size) { 0 },
    val eqBandQTimes100: List<Int> = DEFAULT_EQ_BAND_Q_TIMES100,
    val eqPresetNames: List<String> = listOf("Flat", "Bass Boost", "Vocal", "Treble Boost", "V Shape"),
    val eqActivePresetName: String = "Flat",
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
    val linkedChannelSpacingCm: Int = 24,
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
    val surroundStereoFlPercent: Int = 100,
    val surroundStereoFrPercent: Int = 100,
    val surround51FlPercent: Int = 100,
    val surround51FrPercent: Int = 100,
    val surround51CPercent: Int = 100,
    val surround51LfePercent: Int = 100,
    val surround51SlPercent: Int = 100,
    val surround51SrPercent: Int = 100,
    val surround71FlPercent: Int = 100,
    val surround71FrPercent: Int = 100,
    val surround71CPercent: Int = 100,
    val surround71LfePercent: Int = 100,
    val surround71SlPercent: Int = 100,
    val surround71SrPercent: Int = 100,
    val surround71RlPercent: Int = 100,
    val surround71RrPercent: Int = 100,
    val convolutionEnabled: Boolean = false,
    val convolutionWetPercent: Int = 35,
    val convolutionIrUri: String? = null,
    val convolutionIrName: String = "鏈鍏?IRS",
    val realtimeReverbMeterPercent: Int = 0,
    val derivedDistanceMeters: Float = 1f,
    val derivedGainDb: Float = 0f
)

data class SettingsUiState(
    val hiFiMode: Boolean = true,
    val hiResApiEnabled: Boolean = true,
    val rememberPlaybackSessionEnabled: Boolean = true,
    val rememberPlaybackTrackEnabled: Boolean = true,
    val openPlayerOnTrackPlayEnabled: Boolean = false,
    val hapticFeedbackEnabled: Boolean = true,
    val hapticAudioEnabled: Boolean = false,
    val hapticAudioDelayMs: Int = 0,
    val directDacGoldThemeEnabled: Boolean = true,
    val showLyricsPanelEnabled: Boolean = true,
    val showLyricsScanHeadEnabled: Boolean = true,
    val showStreamInfoEnabled: Boolean = true,
    val showVisualizationEnabled: Boolean = true,
    val visualizationFpsLimit30Enabled: Boolean = false,
    val visualizationDelayMs: Int = 0,
    val topBarVisualizationMode: TopBarVisualizationMode = TopBarVisualizationMode.ANALOG_METER,
    val backgroundBlurStrength: Int = 80,
    val backgroundOpacityPercent: Int = 52,
    val backgroundDynamicEnabled: Boolean = true,
    val lyricsFontSizeSp: Int = 18,
    val lyricsGlowEnabled: Boolean = true,
    val lyricsGlowIntensityPercent: Int = 100,
    val lyricsBoldEnabled: Boolean = false,
    val preferredBitDepth: Int = 32,
    val preferredOutputSampleRateHz: Int? = null,
    val preferredMaxBitrateKbps: Int? = null,
    val preferredUsbDeviceId: Int? = null,
    val preferredUsbDirectSampleRateHz: Int? = null,
    val preferredUsbDirectBitDepth: Int? = null,
    val preferredUsbResampleAlgorithm: Int = USB_RESAMPLER_ALGORITHM_LINEAR,
    val usbExclusiveModeEnabled: Boolean = false,
    val usbExclusiveSupported: Boolean = false,
    val usbExclusiveActive: Boolean = false,
    val usbSrcBypassGuaranteed: Boolean = false,
    val usbHostDirectSupported: Boolean = false,
    val usbHostDirectActive: Boolean = false,
    val usbCompatibilityActive: Boolean = false,
    val usbResolvedSampleRateHz: Int? = null,
    val usbResolvedBitDepth: Int? = null,
    val usbOutputOptions: List<UsbOutputOption> = emptyList(),
    val usbDirectSampleRateOptionsHz: List<Int> = emptyList(),
    val usbDirectBitDepthOptions: List<Int> = emptyList(),
    val usbDirectCapabilitySummary: String = "",
    val activeOutputRouteLabel: String = "绯荤粺榛樿",
    val outputSampleRateHz: Int? = null,
    val outputFramesPerBuffer: Int? = null,
    val offloadSupported: Boolean = false,
    val audioPipelineDetails: String = "",
    val scanFolderUri: Uri? = null,
    val scanFolderRelativePaths: List<String> = emptyList(),
    val scanFolderRelativePath: String? = null,
    val scanFolderLabel: String = "全部媒体库",
    val themeMode: Int = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
)

data class UsbOutputOption(
    val id: Int,
    val label: String
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

private data class EqBandConfig(
    val frequenciesHz: List<Int>,
    val levelsMb: List<Int>,
    val qTimes100: List<Int>
)

private data class EqPreset(
    val name: String,
    val config: EqBandConfig,
    val builtIn: Boolean
)

private data class UsbHostCapability(
    val supported: Boolean,
    val reason: String
)

private data class UsbResolvedTarget(
    val sampleRateHz: Int?,
    val bitDepth: Int?
)

private data class StreamBannerInfo(
    val visible: Boolean,
    val left: String,
    val center: String,
    val right: String,
    val useDacAccent: Boolean,
    val useIsoDacTheme: Boolean
)

object AudioEngine {
    private const val ACTION_USB_DAC_PERMISSION = "com.alky.hifx.action.USB_DAC_PERMISSION"
    private const val PREFS_NAME = "hifx_audio_preferences"
    private const val KEY_HIFI_MODE = "key_hifi_mode"
    private const val KEY_HIRES_API_ENABLED = "key_hires_api_enabled"
    private const val KEY_REMEMBER_PLAYBACK_SESSION = "key_remember_playback_session"
    private const val KEY_REMEMBER_PLAYBACK_STATE = "key_remember_playback_state"
    private const val KEY_REMEMBER_PLAYBACK_TRACK = "key_remember_playback_track"
    private const val KEY_OPEN_PLAYER_ON_TRACK_PLAY = "key_open_player_on_track_play"
    private const val KEY_HAPTIC_FEEDBACK = "key_haptic_feedback"
    private const val KEY_HAPTIC_AUDIO_ENABLED = "key_haptic_audio_enabled"
    private const val KEY_HAPTIC_AUDIO_DELAY_MS = "key_haptic_audio_delay_ms"
    private const val KEY_DIRECT_DAC_GOLD_THEME = "key_direct_dac_gold_theme"
    private const val KEY_SHOW_LYRICS_PANEL = "key_show_lyrics_panel"
    private const val KEY_SHOW_LYRICS_SCAN_HEAD = "key_show_lyrics_scan_head"
    private const val KEY_SHOW_STREAM_INFO = "key_show_stream_info"
    private const val KEY_SHOW_VISUALIZATION = "key_show_visualization"
    private const val KEY_VISUALIZATION_FPS_LIMIT_30 = "key_visualization_fps_limit_30"
    private const val KEY_VISUALIZATION_DELAY_MS = "key_visualization_delay_ms"
    private const val KEY_TOP_BAR_VISUALIZATION_MODE = "key_top_bar_visualization_mode"
    private const val KEY_BACKGROUND_BLUR_STRENGTH = "key_background_blur_strength"
    private const val KEY_BACKGROUND_OPACITY_PERCENT = "key_background_opacity_percent"
    private const val KEY_BACKGROUND_DYNAMIC_ENABLED = "key_background_dynamic_enabled"
    private const val KEY_LYRICS_FONT_SIZE_SP = "key_lyrics_font_size_sp"
    private const val KEY_LYRICS_GLOW_ENABLED = "key_lyrics_glow_enabled"
    private const val KEY_LYRICS_GLOW_INTENSITY_PERCENT = "key_lyrics_glow_intensity_percent"
    private const val KEY_LYRICS_BOLD_ENABLED = "key_lyrics_bold_enabled"
    private const val KEY_PREFERRED_BIT_DEPTH = "key_preferred_bit_depth"
    private const val KEY_PREFERRED_OUTPUT_SAMPLE_RATE_HZ = "key_preferred_output_sample_rate_hz"
    private const val KEY_MAX_AUDIO_BITRATE_KBPS = "key_max_audio_bitrate_kbps"
    private const val KEY_PREFERRED_USB_DEVICE_ID = "key_preferred_usb_device_id"
    private const val KEY_PREFERRED_USB_DIRECT_SAMPLE_RATE_HZ = "key_preferred_usb_direct_sample_rate_hz"
    private const val KEY_PREFERRED_USB_DIRECT_BIT_DEPTH = "key_preferred_usb_direct_bit_depth"
    private const val KEY_PREFERRED_USB_RESAMPLE_ALGORITHM = "key_preferred_usb_resample_algorithm"
    private const val KEY_USB_EXCLUSIVE_MODE = "key_usb_exclusive_mode"
    private const val KEY_EFFECT_ENABLED = "key_effect_enabled"
    private const val KEY_LIMITER_ENABLED = "key_limiter_enabled"
    private const val KEY_PAN_PERCENT = "key_pan_percent"
    private const val KEY_PAN_INVERT_ENABLED = "key_pan_invert_enabled"
    private const val KEY_MONO_ENABLED = "key_mono_enabled"
    private const val KEY_VOCAL_REMOVAL_ENABLED = "key_vocal_removal_enabled"
    private const val KEY_VOCAL_KEY_SHIFT_SEMITONES = "key_vocal_key_shift_semitones"
    private const val KEY_VOCAL_BAND_LOW_CUT_HZ = "key_vocal_band_low_cut_hz"
    private const val KEY_VOCAL_BAND_HIGH_CUT_HZ = "key_vocal_band_high_cut_hz"
    private const val KEY_PHASE_INVERT_ENABLED = "key_phase_invert_enabled"
    private const val KEY_RIGHT_CHANNEL_PHASE_INVERT_ENABLED = "key_right_channel_phase_invert_enabled"
    private const val KEY_CROSSFEED_PERCENT = "key_crossfeed_percent"
    private const val KEY_PLAYBACK_SPEED_PERCENT = "key_playback_speed_percent"
    private const val KEY_PLAYBACK_SPEED_PITCH_COMPENSATION = "key_playback_speed_pitch_compensation"
    private const val KEY_BASS_STRENGTH = "key_bass_strength"
    private const val KEY_VIRTUALIZER_STRENGTH = "key_virtualizer_strength"
    private const val KEY_LOUDNESS_GAIN_MB = "key_loudness_gain_mb"
    private const val KEY_EQ_ENABLED = "key_eq_enabled"
    private const val KEY_EQ_BAND_LEVEL_PREFIX = "key_band_level_"
    private const val KEY_EQ_BAND_CONFIG_JSON = "key_eq_band_config_json"
    private const val KEY_EQ_CUSTOM_PRESETS_JSON = "key_eq_custom_presets_json"
    private const val KEY_EQ_ACTIVE_PRESET_NAME = "key_eq_active_preset_name"
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
    private const val KEY_LINKED_CHANNEL_SPACING_CM = "key_linked_channel_spacing_cm"
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
    private const val KEY_SURROUND_GAIN_STEREO_FL = "key_surround_gain_stereo_fl"
    private const val KEY_SURROUND_GAIN_STEREO_FR = "key_surround_gain_stereo_fr"
    private const val KEY_SURROUND_GAIN_51_FL = "key_surround_gain_51_fl"
    private const val KEY_SURROUND_GAIN_51_FR = "key_surround_gain_51_fr"
    private const val KEY_SURROUND_GAIN_51_C = "key_surround_gain_51_c"
    private const val KEY_SURROUND_GAIN_51_LFE = "key_surround_gain_51_lfe"
    private const val KEY_SURROUND_GAIN_51_SL = "key_surround_gain_51_sl"
    private const val KEY_SURROUND_GAIN_51_SR = "key_surround_gain_51_sr"
    private const val KEY_SURROUND_GAIN_71_FL = "key_surround_gain_71_fl"
    private const val KEY_SURROUND_GAIN_71_FR = "key_surround_gain_71_fr"
    private const val KEY_SURROUND_GAIN_71_C = "key_surround_gain_71_c"
    private const val KEY_SURROUND_GAIN_71_LFE = "key_surround_gain_71_lfe"
    private const val KEY_SURROUND_GAIN_71_SL = "key_surround_gain_71_sl"
    private const val KEY_SURROUND_GAIN_71_SR = "key_surround_gain_71_sr"
    private const val KEY_SURROUND_GAIN_71_RL = "key_surround_gain_71_rl"
    private const val KEY_SURROUND_GAIN_71_RR = "key_surround_gain_71_rr"
    private const val KEY_CONVOLUTION_ENABLED = "key_convolution_enabled"
    private const val KEY_CONVOLUTION_WET_PERCENT = "key_convolution_wet_percent"
    private const val KEY_CONVOLUTION_IR_URI = "key_convolution_ir_uri"
    private const val KEY_CONVOLUTION_IR_NAME = "key_convolution_ir_name"
    private const val KEY_SCAN_FOLDER_URI = "key_scan_folder_uri"
    private const val KEY_SCAN_FOLDER_PATHS_JSON = "key_scan_folder_paths_json"
    private const val KEY_SCAN_FOLDER_RELATIVE_PATH = "key_scan_folder_relative_path"
    private const val KEY_THEME_MODE = "key_theme_mode"
    private const val KEY_PLAYBACK_SHUFFLE = "key_playback_shuffle"
    private const val KEY_PLAYBACK_REPEAT_MODE = "key_playback_repeat_mode"
    private const val KEY_LAST_QUEUE_JSON = "key_last_queue_json"
    private const val KEY_LAST_QUEUE_INDEX = "key_last_queue_index"
    private const val KEY_LAST_POSITION_MS = "key_last_position_ms"
    private const val KEY_LAST_PLAY_WHEN_READY = "key_last_play_when_ready"
    private const val KEY_TRACK_PLAY_COUNTS_JSON = "key_track_play_counts_json"

    const val SURROUND_MODE_STEREO = 0
    const val SURROUND_MODE_5_1 = 1
    const val SURROUND_MODE_7_1 = 2
    const val SURROUND_CHANNEL_FRONT_LEFT = 0
    const val SURROUND_CHANNEL_FRONT_RIGHT = 1
    const val SURROUND_CHANNEL_CENTER = 2
    const val SURROUND_CHANNEL_LFE = 3
    const val SURROUND_CHANNEL_SIDE_LEFT = 4
    const val SURROUND_CHANNEL_SIDE_RIGHT = 5
    const val SURROUND_CHANNEL_REAR_LEFT = 6
    const val SURROUND_CHANNEL_REAR_RIGHT = 7

    private const val SURROUND_GAIN_MIN_PERCENT = 0
    private const val SURROUND_GAIN_MAX_PERCENT = 200
    private const val SPATIAL_COORD_MAX_CM = 120
    private const val SPATIAL_COORD_MIN_CM = -SPATIAL_COORD_MAX_CM
    private const val SPATIAL_RADIUS_MIN_CM = 20
    private const val SPATIAL_RADIUS_MAX_CM = 120
    private const val LINKED_CHANNEL_SPACING_MIN_CM = 0
    private const val LINKED_CHANNEL_SPACING_MAX_CM = SPATIAL_RADIUS_MAX_CM * 2
    private const val HRTF_SOURCE_MARGIN_CM = 0.5f
    private const val HRTF_MAX_SOURCE_DISTANCE_METERS = 4f
    private const val BIT_DEPTH_16 = 16
    private const val BIT_DEPTH_32_FLOAT = 32
    private val OUTPUT_SAMPLE_RATE_OPTIONS_HZ = intArrayOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
    private const val MAX_AUDIO_BITRATE_MIN_KBPS = 64
    private const val MAX_AUDIO_BITRATE_MAX_KBPS = 9216

    private val albumArtBaseUri: Uri = Uri.parse("content://media/external/audio/albumart")

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var initialized = false
    private var playbackServiceStarted = false

    private var currentAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var usbExclusiveSupportedCached: Boolean = false
    private var usbExclusiveActiveCached: Boolean = false
    private var usbSrcBypassGuaranteedCached: Boolean = false
    private var usbHostDirectSupportedCached: Boolean = false
    private var usbHostDirectDesiredCached: Boolean = false
    private var usbHostDirectActiveCached: Boolean = false
    private var usbHostCapabilityReasonCached: String = "Not checked"
    private var usbCompatibilityActiveCached: Boolean = false
    private var usbResolvedSampleRateHzCached: Int? = null
    private var usbResolvedBitDepthCached: Int? = null
    private var usbPreferredRouteAppliedCached: Boolean = false
    private var lastAudioDecoderName: String? = null
    private var lastAudioDecoderInitDurationMs: Long? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var environmentalReverb: EnvironmentalReverb? = null
    private var hapticGenerator: HapticGenerator? = null
    private var hrtfBinauralProcessor: HrtfBinauralProcessor? = null
    private var nativeSurroundProcessor: NativeSurroundProcessor? = null
    private var convolutionReverbProcessor: ConvolutionReverbProcessor? = null
    private var vocalIsolationProcessor: VocalIsolationProcessor? = null
    private var stereoUtilityProcessor: StereoUtilityProcessor? = null
    private var topBarVisualizationProcessor: TopBarVisualizationProcessor? = null
    private var transientPlaybackSpeedOverride: Float? = null
    private var transientPlaybackPitchOverride: Float? = null
    private var hapticAudioProcessor: HapticAudioProcessor? = null
    private var hapticAudioDriver: HapticAudioDriver? = null
    private var usbHostPassthroughProcessor: UsbHostPassthroughProcessor? = null
    private var usbHostDirectOutput: UsbPcmOutputBackend? = null
    private var usbHostDirectBackendLabelCached: String = "none"
    private var usbHostDirectRouteLabelCached: String = "none"
    private var bluetoothCodecLabelCached: String? = null
    private val playbackQueue = mutableListOf<LibraryTrack>()
    private var sleepTimerEndElapsedMs: Long? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var bluetoothA2dpProfile: BluetoothProfile? = null
    private var bluetoothProfileListener: BluetoothProfile.ServiceListener? = null
    private var usbPermissionReceiver: BroadcastReceiver? = null
    private var usbPermissionReceiverRegistered = false
    private var bitPerfectBypassActive: Boolean = false
    private var lastUsbHostDirectRecoveryAttemptElapsedMs: Long = 0L
    private var lastUsbTransportToastSignature: String? = null
    private var lastUsbTransportToastElapsedMs: Long = 0L
    private var activeToast: Toast? = null
    private var activeToastCategory: String? = null
    private var eqCustomPresets: MutableList<EqPreset> = mutableListOf()
    private var trackPlayCounts: MutableMap<Long, Int> = mutableMapOf()
    private var lastPersistSignature: String? = null
    private var lastPersistPositionMs: Long = -1L
    private var lastPersistElapsedRealtimeMs: Long = 0L

    private val progressTicker = object : Runnable {
        override fun run() {
            checkSleepTimer()
            val currentPlayer = player
            if (currentPlayer != null && currentPlayer.playWhenReady) {
                ensureUsbHostDirectReadyForPlayback()
            }
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
            incrementTrackPlayCount(mediaItem?.mediaId?.toLongOrNull())
            syncPlaybackState()
            refreshOutputInfo()
        }

        override fun onPlayerError(error: PlaybackException) {
            syncPlaybackState(error.message ?: "鎾斁澶辫触")
        }

        override fun onEvents(player: Player, events: Player.Events) {
            val sessionId = this@AudioEngine.player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
            if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != currentAudioSessionId) {
                if (bitPerfectBypassActive) {
                    releaseAudioEffects()
                } else {
                    attachAudioEffects(sessionId)
                }
            }
            syncPlaybackState()
            if (events.contains(Player.EVENT_TRACKS_CHANGED) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                refreshOutputInfo()
            }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            lastAudioDecoderName = decoderName
            lastAudioDecoderInitDurationMs = initializationDurationMs
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
        val storedScanFolderRelativePath = prefs.getString(KEY_SCAN_FOLDER_RELATIVE_PATH, null)
        val scanFolderRelativePaths = parseScanFolderRelativePaths(
            prefs.getString(KEY_SCAN_FOLDER_PATHS_JSON, null),
            fallback = listOfNotNull(
                normalizeScanFolderRelativePath(storedScanFolderRelativePath ?: resolveRelativePathPrefix(scanUri))
            )
        )
        val themeMode = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        val legacySpatialX = prefs.getInt(KEY_SPATIAL_X, 0).coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM)
        val legacySpatialY = prefs.getInt(KEY_SPATIAL_Y, 0).coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM)
        val legacySpatialZ = prefs.getInt(KEY_SPATIAL_Z, 0).coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM)

        val legacyEqLevels = List(DEFAULT_EQ_BAND_FREQUENCIES_HZ.size) { index ->
            prefs.getInt("$KEY_EQ_BAND_LEVEL_PREFIX$index", 0).coerceIn(-1200, 1200)
        }
        val parsedEq = parseEqBandConfig(
            prefs.getString(KEY_EQ_BAND_CONFIG_JSON, null),
            legacyEqLevels
        )
        trackPlayCounts = parseTrackPlayCounts(
            prefs.getString(KEY_TRACK_PLAY_COUNTS_JSON, null)
        ).toMutableMap()
        eqCustomPresets = parseCustomEqPresets(prefs.getString(KEY_EQ_CUSTOM_PRESETS_JSON, null)).toMutableList()
        val presetNames = buildEqPresetNames(eqCustomPresets)
        val savedActivePreset = prefs.getString(KEY_EQ_ACTIVE_PRESET_NAME, null)
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { it in presetNames }
            ?: presetNames.firstOrNull().orEmpty()

        val storedEffects = EffectsUiState(
            enabled = prefs.getBoolean(KEY_EFFECT_ENABLED, true),
            eqEnabled = prefs.getBoolean(KEY_EQ_ENABLED, true),
            limiterEnabled = prefs.getBoolean(KEY_LIMITER_ENABLED, false),
            panPercent = prefs.getInt(KEY_PAN_PERCENT, 0).coerceIn(-100, 100),
            panInvertEnabled = prefs.getBoolean(KEY_PAN_INVERT_ENABLED, false),
            monoEnabled = prefs.getBoolean(KEY_MONO_ENABLED, false),
            vocalRemovalEnabled = prefs.getBoolean(KEY_VOCAL_REMOVAL_ENABLED, false),
            vocalKeyShiftSemitones = prefs.getInt(KEY_VOCAL_KEY_SHIFT_SEMITONES, 0).coerceIn(-24, 24),
            vocalBandLowCutHz = prefs.getInt(KEY_VOCAL_BAND_LOW_CUT_HZ, 140).coerceIn(60, 2000),
            vocalBandHighCutHz = prefs.getInt(KEY_VOCAL_BAND_HIGH_CUT_HZ, 4200).coerceIn(800, 8000),
            phaseInvertEnabled = prefs.getBoolean(KEY_PHASE_INVERT_ENABLED, false),
            rightChannelPhaseInvertEnabled = prefs.getBoolean(KEY_RIGHT_CHANNEL_PHASE_INVERT_ENABLED, false),
            crossfeedPercent = prefs.getInt(KEY_CROSSFEED_PERCENT, 0).coerceIn(0, 100),
            playbackSpeedPercent = prefs.getInt(KEY_PLAYBACK_SPEED_PERCENT, 100).coerceIn(50, 200),
            playbackSpeedPitchCompensationEnabled = prefs.getBoolean(KEY_PLAYBACK_SPEED_PITCH_COMPENSATION, true),
            bassStrength = prefs.getInt(KEY_BASS_STRENGTH, 400).coerceIn(0, 1000),
            virtualizerStrength = prefs.getInt(KEY_VIRTUALIZER_STRENGTH, 300).coerceIn(0, 1000),
            loudnessGainMb = prefs.getInt(KEY_LOUDNESS_GAIN_MB, 0).coerceIn(0, 2000),
            eqBandFrequenciesHz = parsedEq.frequenciesHz,
            eqBandLevelsMb = parsedEq.levelsMb,
            eqBandQTimes100 = parsedEq.qTimes100,
            eqPresetNames = presetNames,
            eqActivePresetName = savedActivePreset,
            spatialEnabled = prefs.getBoolean(KEY_SPATIAL_ENABLED, false),
            channelSeparated = prefs.getBoolean(KEY_CHANNEL_SEPARATED, false),
            spatialLeftX = prefs.getInt(KEY_SPATIAL_LEFT_X, legacySpatialX)
                .coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM),
            spatialLeftY = prefs.getInt(KEY_SPATIAL_LEFT_Y, legacySpatialY)
                .coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM),
            spatialLeftZ = prefs.getInt(KEY_SPATIAL_LEFT_Z, legacySpatialZ)
                .coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM),
            spatialRightX = prefs.getInt(KEY_SPATIAL_RIGHT_X, legacySpatialX)
                .coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM),
            spatialRightY = prefs.getInt(KEY_SPATIAL_RIGHT_Y, legacySpatialY)
                .coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM),
            spatialRightZ = prefs.getInt(KEY_SPATIAL_RIGHT_Z, legacySpatialZ)
                .coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM),
            spatialLeftRadiusPercent = prefs.getInt(KEY_SPATIAL_LEFT_RADIUS_PERCENT, 100)
                .coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM),
            spatialRightRadiusPercent = prefs.getInt(KEY_SPATIAL_RIGHT_RADIUS_PERCENT, 100)
                .coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM),
            linkedChannelSpacingCm = prefs.getInt(KEY_LINKED_CHANNEL_SPACING_CM, 24)
                .coerceIn(LINKED_CHANNEL_SPACING_MIN_CM, LINKED_CHANNEL_SPACING_MAX_CM),
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
            surroundStereoFlPercent = prefs.getInt(KEY_SURROUND_GAIN_STEREO_FL, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            surroundStereoFrPercent = prefs.getInt(KEY_SURROUND_GAIN_STEREO_FR, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            surround51FlPercent = prefs.getInt(KEY_SURROUND_GAIN_51_FL, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            surround51FrPercent = prefs.getInt(KEY_SURROUND_GAIN_51_FR, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            surround51CPercent = prefs.getInt(KEY_SURROUND_GAIN_51_C, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            surround51LfePercent = prefs.getInt(KEY_SURROUND_GAIN_51_LFE, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            surround51SlPercent = prefs.getInt(KEY_SURROUND_GAIN_51_SL, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            surround51SrPercent = prefs.getInt(KEY_SURROUND_GAIN_51_SR, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            surround71FlPercent = prefs.getInt(KEY_SURROUND_GAIN_71_FL, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            surround71FrPercent = prefs.getInt(KEY_SURROUND_GAIN_71_FR, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            surround71CPercent = prefs.getInt(KEY_SURROUND_GAIN_71_C, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            surround71LfePercent = prefs.getInt(KEY_SURROUND_GAIN_71_LFE, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            surround71SlPercent = prefs.getInt(KEY_SURROUND_GAIN_71_SL, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            surround71SrPercent = prefs.getInt(KEY_SURROUND_GAIN_71_SR, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            surround71RlPercent = prefs.getInt(KEY_SURROUND_GAIN_71_RL, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            surround71RrPercent = prefs.getInt(KEY_SURROUND_GAIN_71_RR, 100)
                .coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT),
            convolutionEnabled = prefs.getBoolean(KEY_CONVOLUTION_ENABLED, false),
            convolutionWetPercent = prefs.getInt(KEY_CONVOLUTION_WET_PERCENT, 35).coerceIn(0, 100),
            convolutionIrUri = prefs.getString(KEY_CONVOLUTION_IR_URI, null),
            convolutionIrName = prefs.getString(KEY_CONVOLUTION_IR_NAME, "鏈鍏?IRS")
                ?: "鏈鍏?IRS"
        )
        _effectsState.value = withSpatialDerived(storedEffects)
        persistSpatialPosition(_effectsState.value)
        val legacyPreferredUsbDirectSampleRateHz = normalizeOutputSampleRate(
            prefs.getInt(KEY_PREFERRED_USB_DIRECT_SAMPLE_RATE_HZ, -1).takeIf { it > 0 }
        )
        val preferredOutputSampleRateHz = normalizeOutputSampleRate(
            prefs.getInt(KEY_PREFERRED_OUTPUT_SAMPLE_RATE_HZ, -1).takeIf { it > 0 }
        ) ?: legacyPreferredUsbDirectSampleRateHz
        val legacyPreferredUsbDirectBitDepth = prefs.getInt(KEY_PREFERRED_USB_DIRECT_BIT_DEPTH, -1)
            .takeIf { it > 0 }
            ?.let(::normalizeBitDepth)
        val preferredBitDepth = prefs.getInt(KEY_PREFERRED_BIT_DEPTH, -1)
            .takeIf { it > 0 }
            ?.let(::normalizeBitDepth)
            ?: legacyPreferredUsbDirectBitDepth
            ?: BIT_DEPTH_32_FLOAT
        val preferredUsbResampleAlgorithm = prefs.getInt(
            KEY_PREFERRED_USB_RESAMPLE_ALGORITHM,
            USB_RESAMPLER_ALGORITHM_LINEAR
        ).coerceIn(USB_RESAMPLER_ALGORITHM_NEAREST, USB_RESAMPLER_ALGORITHM_SOXR_HQ)
        val preferredMaxBitrate = prefs.getInt(KEY_MAX_AUDIO_BITRATE_KBPS, -1)
            .takeIf { it in MAX_AUDIO_BITRATE_MIN_KBPS..MAX_AUDIO_BITRATE_MAX_KBPS }
        val preferredUsbDeviceId = prefs.getInt(KEY_PREFERRED_USB_DEVICE_ID, -1).takeIf { it >= 0 }
        val storedVisualizationMode = TopBarVisualizationMode.fromPrefValue(
            prefs.getString(KEY_TOP_BAR_VISUALIZATION_MODE, TopBarVisualizationMode.ANALOG_METER.prefValue)
        )
        val topBarVisualizationMode = if (storedVisualizationMode == TopBarVisualizationMode.AUDIO_INFO) {
            TopBarVisualizationMode.ANALOG_METER
        } else {
            storedVisualizationMode
        }
        val rememberPlaybackLegacy = prefs.getBoolean(KEY_REMEMBER_PLAYBACK_SESSION, true)
        val rememberPlaybackStateEnabled = if (prefs.contains(KEY_REMEMBER_PLAYBACK_STATE)) {
            prefs.getBoolean(KEY_REMEMBER_PLAYBACK_STATE, rememberPlaybackLegacy)
        } else {
            rememberPlaybackLegacy
        }
        val rememberPlaybackTrackEnabled = if (prefs.contains(KEY_REMEMBER_PLAYBACK_TRACK)) {
            prefs.getBoolean(KEY_REMEMBER_PLAYBACK_TRACK, rememberPlaybackLegacy)
        } else {
            rememberPlaybackLegacy
        }
        _settingsState.value = _settingsState.value.copy(
            hiFiMode = prefs.getBoolean(KEY_HIFI_MODE, true),
            hiResApiEnabled = prefs.getBoolean(KEY_HIRES_API_ENABLED, true),
            rememberPlaybackSessionEnabled = rememberPlaybackStateEnabled,
            rememberPlaybackTrackEnabled = rememberPlaybackTrackEnabled,
            openPlayerOnTrackPlayEnabled = prefs.getBoolean(KEY_OPEN_PLAYER_ON_TRACK_PLAY, false),
            hapticFeedbackEnabled = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true),
            hapticAudioEnabled = prefs.getBoolean(KEY_HAPTIC_AUDIO_ENABLED, false),
            hapticAudioDelayMs = prefs.getInt(KEY_HAPTIC_AUDIO_DELAY_MS, 0).coerceIn(-250, 250),
            directDacGoldThemeEnabled = prefs.getBoolean(KEY_DIRECT_DAC_GOLD_THEME, true),
            showLyricsPanelEnabled = prefs.getBoolean(KEY_SHOW_LYRICS_PANEL, true),
            showLyricsScanHeadEnabled = prefs.getBoolean(KEY_SHOW_LYRICS_SCAN_HEAD, true),
            showStreamInfoEnabled = prefs.getBoolean(KEY_SHOW_STREAM_INFO, true),
            showVisualizationEnabled = prefs.getBoolean(KEY_SHOW_VISUALIZATION, true),
            visualizationFpsLimit30Enabled = prefs.getBoolean(KEY_VISUALIZATION_FPS_LIMIT_30, false),
            visualizationDelayMs = prefs.getInt(KEY_VISUALIZATION_DELAY_MS, 0).coerceIn(-250, 250),
            topBarVisualizationMode = topBarVisualizationMode,
            backgroundBlurStrength = prefs.getInt(KEY_BACKGROUND_BLUR_STRENGTH, 80).coerceIn(0, 220),
            backgroundOpacityPercent = prefs.getInt(KEY_BACKGROUND_OPACITY_PERCENT, 52).coerceIn(0, 100),
            backgroundDynamicEnabled = prefs.getBoolean(KEY_BACKGROUND_DYNAMIC_ENABLED, true),
            lyricsFontSizeSp = prefs.getInt(KEY_LYRICS_FONT_SIZE_SP, 18).coerceIn(12, 40),
            lyricsGlowEnabled = prefs.getBoolean(KEY_LYRICS_GLOW_ENABLED, true),
            lyricsGlowIntensityPercent = prefs.getInt(KEY_LYRICS_GLOW_INTENSITY_PERCENT, 100).coerceIn(0, 100),
            lyricsBoldEnabled = prefs.getBoolean(KEY_LYRICS_BOLD_ENABLED, false),
            preferredBitDepth = preferredBitDepth,
            preferredOutputSampleRateHz = preferredOutputSampleRateHz,
            preferredMaxBitrateKbps = preferredMaxBitrate,
            preferredUsbDeviceId = preferredUsbDeviceId,
            preferredUsbDirectSampleRateHz = preferredOutputSampleRateHz,
            preferredUsbDirectBitDepth = preferredBitDepth,
            preferredUsbResampleAlgorithm = preferredUsbResampleAlgorithm,
            usbExclusiveModeEnabled = prefs.getBoolean(KEY_USB_EXCLUSIVE_MODE, false),
              scanFolderUri = scanUri,
              scanFolderRelativePaths = scanFolderRelativePaths,
              scanFolderRelativePath = scanFolderRelativePaths.firstOrNull(),
              scanFolderLabel = buildScanFolderLabel(scanFolderRelativePaths),
              themeMode = themeMode
          )

        buildPlayer()
        if (_settingsState.value.rememberPlaybackTrackEnabled) {
            restoreLastPlaybackSession(_settingsState.value.rememberPlaybackSessionEnabled)
        } else {
            clearLastPlaybackSession()
        }
        registerAudioDeviceMonitor()
        registerBluetoothCodecMonitor()
        registerUsbPermissionReceiver()
        restoreConvolutionImpulseIfNeeded(storedEffects.convolutionIrUri)
        refreshOutputInfo()
        requestExternalDacExclusiveAccess(appContext)
        refreshLibrary()
        mainHandler.post(progressTicker)
    }

    fun requestExternalDacExclusiveAccess(context: Context) {
        if (!initialized) {
            return
        }
        val audioManager = context.getSystemService(AudioManager::class.java)
        val connectedUsbOutputs = audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.filter { it.isUsbOutputDevice() }
            .orEmpty()
        if (connectedUsbOutputs.isEmpty()) {
            return
        }

        if (!_settingsState.value.usbExclusiveModeEnabled) {
            _settingsState.value = _settingsState.value.copy(usbExclusiveModeEnabled = true)
            prefs.edit().putBoolean(KEY_USB_EXCLUSIVE_MODE, true).apply()
        }

        refreshOutputInfo()
        if (_settingsState.value.preferredUsbDeviceId == null) {
            val autoCandidate = connectedUsbOutputs
                .sortedBy { it.productName?.toString().orEmpty().lowercase() }
                .firstOrNull()
            setPreferredUsbOutputDeviceId(autoCandidate?.id)
        } else {
            applyPreferredOutputDevice()
        }

        val preferredUsbInfo = _settingsState.value.preferredUsbDeviceId?.let { preferredId ->
            connectedUsbOutputs.firstOrNull { it.id == preferredId }
        } ?: connectedUsbOutputs.firstOrNull()
        val usbManager = context.getSystemService(UsbManager::class.java) ?: return
        val connectedDacs = findConnectedUsbAudioDevices(usbManager)
        if (connectedDacs.isEmpty()) {
            return
        }
        val preferredUsbDevice = resolveUsbDeviceForHostDirect(usbManager, preferredUsbInfo)
        val deviceNeedingPermission = sequenceOf(preferredUsbDevice)
            .filterNotNull()
            .plus(connectedDacs.asSequence())
            .firstOrNull { usbDevice -> !usbManager.hasPermission(usbDevice) }
            ?: return

        val permissionIntent = Intent(ACTION_USB_DAC_PERMISSION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, 0, permissionIntent, flags)
        runCatching {
            usbManager.requestPermission(deviceNeedingPermission, pendingIntent)
        }
    }

    private fun registerAudioDeviceMonitor() {
        if (audioDeviceCallback != null) {
            return
        }
        val audioManager = appContext.getSystemService(AudioManager::class.java) ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                refreshOutputInfo()
                if (addedDevices.any { it.isUsbOutputDevice() }) {
                    requestExternalDacExclusiveAccess(appContext)
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                refreshOutputInfo()
            }
        }
        runCatching {
            audioManager.registerAudioDeviceCallback(callback, mainHandler)
            audioDeviceCallback = callback
        }
    }

    private fun registerBluetoothCodecMonitor() {
        if (bluetoothProfileListener != null) {
            return
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.A2DP) {
                    return
                }
                bluetoothA2dpProfile = proxy
                refreshBluetoothCodecInfo()
                refreshOutputInfo()
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile != BluetoothProfile.A2DP) {
                    return
                }
                bluetoothA2dpProfile = null
                bluetoothCodecLabelCached = null
                refreshOutputInfo()
            }
        }
        val registered = runCatching {
            adapter.getProfileProxy(appContext, listener, BluetoothProfile.A2DP)
        }.getOrDefault(false)
        if (registered) {
            bluetoothProfileListener = listener
        }
    }

    private fun unregisterAudioDeviceMonitor() {
        val callback = audioDeviceCallback ?: return
        val audioManager = appContext.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            audioManager.unregisterAudioDeviceCallback(callback)
        }
        audioDeviceCallback = null
    }

    private fun unregisterBluetoothCodecMonitor() {
        bluetoothProfileListener ?: return
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        val proxy = bluetoothA2dpProfile
        if (adapter != null && proxy != null) {
            runCatching {
                adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
            }
        }
        bluetoothA2dpProfile = null
        bluetoothProfileListener = null
        bluetoothCodecLabelCached = null
    }

    private fun registerUsbPermissionReceiver() {
        if (usbPermissionReceiverRegistered) {
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_USB_DAC_PERMISSION) {
                    return
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    applyPreferredOutputDevice()
                } else if (_settingsState.value.usbExclusiveModeEnabled) {
                    _settingsState.value = _settingsState.value.copy(usbExclusiveModeEnabled = false)
                    prefs.edit().putBoolean(KEY_USB_EXCLUSIVE_MODE, false).apply()
                }
                refreshOutputInfo()
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(
                    receiver,
                    IntentFilter(ACTION_USB_DAC_PERMISSION),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                appContext.registerReceiver(receiver, IntentFilter(ACTION_USB_DAC_PERMISSION))
            }
            usbPermissionReceiver = receiver
            usbPermissionReceiverRegistered = true
        }
    }

    private fun unregisterUsbPermissionReceiver() {
        if (!usbPermissionReceiverRegistered) {
            return
        }
        val receiver = usbPermissionReceiver ?: return
        runCatching {
            appContext.unregisterReceiver(receiver)
        }
        usbPermissionReceiver = null
        usbPermissionReceiverRegistered = false
    }

    private fun findConnectedUsbAudioDevices(usbManager: UsbManager): List<UsbDevice> {
        return usbManager.deviceList.values.filter { device ->
            if (
                device.deviceClass == UsbConstants.USB_CLASS_AUDIO ||
                device.deviceClass == UsbConstants.USB_CLASS_PER_INTERFACE
            ) {
                return@filter true
            }
            val interfaceCount = device.interfaceCount
            var matched = false
            for (index in 0 until interfaceCount) {
                val intf = runCatching { device.getInterface(index) }.getOrNull() ?: continue
                if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                    matched = true
                    break
                }
                val endpointCount = intf.endpointCount
                for (endpointIndex in 0 until endpointCount) {
                    val endpoint = runCatching { intf.getEndpoint(endpointIndex) }.getOrNull() ?: continue
                    val canWritePcm = endpoint.direction == UsbConstants.USB_DIR_OUT && (
                        endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK ||
                            endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
                        )
                    if (canWritePcm) {
                        matched = true
                        break
                    }
                }
                if (matched) break
            }
            matched
        }
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

        val scanFolderRelativePaths = _settingsState.value.scanFolderRelativePaths
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { queryTracks(scanFolderRelativePaths) }
            }
            val tracks = result.getOrDefault(emptyList())
            val albums = tracks
                .groupBy { it.album.ifBlank { "鏈煡涓撹緫" } }
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
        val relativePath = normalizeScanFolderRelativePath(resolveRelativePathPrefix(uri))
        applyScanFolderSelection(
            uri = uri,
            relativePaths = mergeScanFolderRelativePaths(
                _settingsState.value.scanFolderRelativePaths,
                listOfNotNull(relativePath)
            )
        )
    }

    fun setScanFolderRelativePath(relativePath: String?) {
        applyScanFolderSelection(
            uri = null,
            relativePaths = mergeScanFolderRelativePaths(
                _settingsState.value.scanFolderRelativePaths,
                listOfNotNull(normalizeScanFolderRelativePath(relativePath))
            )
        )
    }

    fun setScanFolderRelativePaths(relativePaths: List<String>) {
        applyScanFolderSelection(
            uri = _settingsState.value.scanFolderUri,
            relativePaths = mergeScanFolderRelativePaths(emptyList(), relativePaths)
        )
    }

    fun removeScanFolderRelativePath(relativePath: String) {
        val normalized = normalizeScanFolderRelativePath(relativePath) ?: return
        applyScanFolderSelection(
            uri = _settingsState.value.scanFolderUri,
            relativePaths = _settingsState.value.scanFolderRelativePaths.filterNot { it.equals(normalized, ignoreCase = true) }
        )
    }

    fun clearScanFolders() {
        applyScanFolderSelection(uri = null, relativePaths = emptyList())
    }

    fun getAvailableScanFolders(): List<String> {
        if (!initialized) {
            return emptyList()
        }
        val projection = arrayOf(MediaStore.Audio.Media.RELATIVE_PATH)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 10000"
        val folders = linkedSetOf<String>()
        appContext.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.RELATIVE_PATH} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val relativePathIndex = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val rawPath = if (relativePathIndex >= 0) cursor.getString(relativePathIndex) else null
                val normalized = normalizeScanFolderRelativePath(rawPath) ?: continue
                folders += normalized
            }
        }
        return folders.sortedBy { it.lowercase() }
    }

    private fun applyScanFolderSelection(uri: Uri?, relativePaths: List<String>) {
        val normalizedPaths = mergeScanFolderRelativePaths(emptyList(), relativePaths)
        val editor = prefs.edit()
        if (uri == null) {
            editor.remove(KEY_SCAN_FOLDER_URI)
        } else {
            editor.putString(KEY_SCAN_FOLDER_URI, uri.toString())
        }
        if (normalizedPaths.isEmpty()) {
            editor.remove(KEY_SCAN_FOLDER_PATHS_JSON)
            editor.remove(KEY_SCAN_FOLDER_RELATIVE_PATH)
        } else {
            editor.putString(KEY_SCAN_FOLDER_PATHS_JSON, serializeScanFolderRelativePaths(normalizedPaths))
            editor.putString(KEY_SCAN_FOLDER_RELATIVE_PATH, normalizedPaths.first())
        }
        editor.apply()

        _settingsState.value = _settingsState.value.copy(
            scanFolderUri = uri,
            scanFolderRelativePaths = normalizedPaths,
            scanFolderRelativePath = normalizedPaths.firstOrNull(),
            scanFolderLabel = buildScanFolderLabel(normalizedPaths)
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
                artworkUri = null,
                discNumber = 1,
                trackNumber = 0
            )
        )
    }

    fun playTrack(track: LibraryTrack) {
        val libraryTracks = _libraryState.value.tracks
        val indexInLibrary = libraryTracks.indexOfFirst { it.id == track.id }
        if (indexInLibrary >= 0) {
            playTrackList(libraryTracks, indexInLibrary)
            return
        }
        playTrackList(listOf(track), 0)
    }

    fun playTrackList(tracks: List<LibraryTrack>, startIndex: Int) {
        if (!initialized) {
            return
        }
        if (tracks.isEmpty()) {
            return
        }
        val clampedIndex = startIndex.coerceIn(0, tracks.lastIndex)
        playbackQueue.clear()
        playbackQueue.addAll(tracks)

        val items = tracks.map { libraryTrack ->
            val metadata = MediaMetadata.Builder()
                .setTitle(libraryTrack.title)
                .setArtist(libraryTrack.artist)
                .setAlbumTitle(libraryTrack.album)
                .setArtworkUri(libraryTrack.artworkUri)
                .build()
            MediaItem.Builder()
                .setUri(libraryTrack.uri)
                .setMediaId(libraryTrack.id.toString())
                .setMediaMetadata(metadata)
                .build()
        }

        AudioPlaybackService.start(appContext)
        player?.apply {
            setMediaItems(items, clampedIndex, 0L)
            prepare()
            play()
        }
        syncPlaybackState()
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
            ensureUsbHostDirectReadyForPlayback()
            currentPlayer.play()
        }
    }

    fun play() {
        val currentPlayer = player ?: return
        if (currentPlayer.currentMediaItem == null) {
            return
        }
        AudioPlaybackService.start(appContext)
        ensureUsbHostDirectReadyForPlayback()
        currentPlayer.play()
    }

    fun pause() {
        player?.pause()
    }

    fun skipToPreviousTrack() {
        val currentPlayer = player ?: return
        if (!currentPlayer.hasPreviousMediaItem()) {
            if (currentPlayer.currentPosition > 3_000L) {
                currentPlayer.seekTo(0L)
            }
            return
        }
        currentPlayer.seekToPreviousMediaItem()
    }

    fun skipToNextTrack() {
        val currentPlayer = player ?: return
        if (!currentPlayer.hasNextMediaItem()) {
            return
        }
        currentPlayer.seekToNextMediaItem()
    }

    fun getPlaybackQueueSnapshot(): List<LibraryTrack> {
        return playbackQueue.toList()
    }

    fun setShuffleEnabled(enabled: Boolean) {
        player?.shuffleModeEnabled = enabled
        prefs.edit().putBoolean(KEY_PLAYBACK_SHUFFLE, enabled).apply()
        syncPlaybackState()
    }

    fun toggleShuffleEnabled() {
        val enabled = !(player?.shuffleModeEnabled ?: false)
        setShuffleEnabled(enabled)
    }

    fun setRepeatMode(repeatMode: Int) {
        val normalized = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        player?.repeatMode = normalized
        prefs.edit().putInt(KEY_PLAYBACK_REPEAT_MODE, normalized).apply()
        syncPlaybackState()
    }

    fun cycleRepeatMode() {
        val currentMode = player?.repeatMode ?: Player.REPEAT_MODE_OFF
        val nextMode = when (currentMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        setRepeatMode(nextMode)
    }

    fun setSleepTimer(durationMs: Long) {
        if (durationMs <= 0L) {
            cancelSleepTimer()
            return
        }
        sleepTimerEndElapsedMs = SystemClock.elapsedRealtime() + durationMs
        syncPlaybackState()
    }

    fun cancelSleepTimer() {
        sleepTimerEndElapsedMs = null
        syncPlaybackState()
    }

    fun clearCurrentTrack() {
        player?.stop()
        player?.clearMediaItems()
        playbackQueue.clear()
        sleepTimerEndElapsedMs = null
        clearLastPlaybackSession()
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

    fun refreshPlaybackStateNow() {
        if (!initialized) {
            return
        }
        syncPlaybackState()
    }

    fun setHiFiMode(enabled: Boolean) {
        _settingsState.value = _settingsState.value.copy(hiFiMode = enabled)
        prefs.edit().putBoolean(KEY_HIFI_MODE, enabled).apply()
        applyHiFiMode(enabled)
        syncPlaybackState()
    }

    fun setHiResApiEnabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.hiResApiEnabled == enabled) {
            return
        }
        _settingsState.value = current.copy(hiResApiEnabled = enabled)
        prefs.edit().putBoolean(KEY_HIRES_API_ENABLED, enabled).apply()
        rebuildPlayerPreservingState()
        refreshOutputInfo()
    }

    fun setRememberPlaybackSessionEnabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.rememberPlaybackSessionEnabled == enabled) {
            return
        }
        _settingsState.value = current.copy(rememberPlaybackSessionEnabled = enabled)
        prefs.edit()
            .putBoolean(KEY_REMEMBER_PLAYBACK_STATE, enabled)
            .apply()
        if (!enabled) {
            clearLastPlaybackState()
        }
    }

    fun setRememberPlaybackTrackEnabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.rememberPlaybackTrackEnabled == enabled) {
            return
        }
        _settingsState.value = current.copy(rememberPlaybackTrackEnabled = enabled)
        prefs.edit()
            .putBoolean(KEY_REMEMBER_PLAYBACK_TRACK, enabled)
            .apply()
        if (!enabled) {
            clearLastPlaybackSession()
        }
    }

    fun setOpenPlayerOnTrackPlayEnabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.openPlayerOnTrackPlayEnabled == enabled) {
            return
        }
        _settingsState.value = current.copy(openPlayerOnTrackPlayEnabled = enabled)
        prefs.edit()
            .putBoolean(KEY_OPEN_PLAYER_ON_TRACK_PLAY, enabled)
            .apply()
    }

    fun shouldOpenPlayerOnTrackPlay(): Boolean {
        return _settingsState.value.openPlayerOnTrackPlayEnabled
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.hapticFeedbackEnabled == enabled) {
            return
        }
        _settingsState.value = current.copy(hapticFeedbackEnabled = enabled)
        prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, enabled).apply()
    }

    fun isHapticFeedbackEnabled(): Boolean = _settingsState.value.hapticFeedbackEnabled

    fun setHapticAudioEnabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.hapticAudioEnabled == enabled) {
            return
        }
        _settingsState.value = current.copy(hapticAudioEnabled = enabled)
        prefs.edit().putBoolean(KEY_HAPTIC_AUDIO_ENABLED, enabled).apply()
        applyHapticAudioState()
        refreshOutputInfo()
    }

    fun setHapticAudioDelayMs(delayMs: Int) {
        val normalized = delayMs.coerceIn(-250, 250)
        val current = _settingsState.value
        if (current.hapticAudioDelayMs == normalized) {
            return
        }
        _settingsState.value = current.copy(hapticAudioDelayMs = normalized)
        prefs.edit().putInt(KEY_HAPTIC_AUDIO_DELAY_MS, normalized).apply()
        hapticAudioDriver?.setDelayMs(normalized)
        updateAudioPipelineDetailsSnapshot()
    }

    fun setShowLyricsPanelEnabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.showLyricsPanelEnabled == enabled) {
            return
        }
        _settingsState.value = current.copy(showLyricsPanelEnabled = enabled)
        prefs.edit().putBoolean(KEY_SHOW_LYRICS_PANEL, enabled).apply()
    }

    fun setDirectDacGoldThemeEnabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.directDacGoldThemeEnabled == enabled) {
            return
        }
        _settingsState.value = current.copy(directDacGoldThemeEnabled = enabled)
        prefs.edit().putBoolean(KEY_DIRECT_DAC_GOLD_THEME, enabled).apply()
    }

    fun setShowLyricsScanHeadEnabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.showLyricsScanHeadEnabled == enabled) {
            return
        }
        _settingsState.value = current.copy(showLyricsScanHeadEnabled = enabled)
        prefs.edit().putBoolean(KEY_SHOW_LYRICS_SCAN_HEAD, enabled).apply()
    }

    fun setShowVisualizationEnabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.showVisualizationEnabled == enabled) {
            return
        }
        _settingsState.value = current.copy(showVisualizationEnabled = enabled)
        prefs.edit().putBoolean(KEY_SHOW_VISUALIZATION, enabled).apply()
    }

    fun setVisualizationFpsLimit30Enabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.visualizationFpsLimit30Enabled == enabled) {
            return
        }
        _settingsState.value = current.copy(visualizationFpsLimit30Enabled = enabled)
        prefs.edit().putBoolean(KEY_VISUALIZATION_FPS_LIMIT_30, enabled).apply()
    }

    fun setVisualizationDelayMs(delayMs: Int) {
        val normalized = delayMs.coerceIn(-250, 250)
        val current = _settingsState.value
        if (current.visualizationDelayMs == normalized) {
            return
        }
        _settingsState.value = current.copy(visualizationDelayMs = normalized)
        prefs.edit().putInt(KEY_VISUALIZATION_DELAY_MS, normalized).apply()
    }

    fun setShowStreamInfoEnabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.showStreamInfoEnabled == enabled) {
            return
        }
        _settingsState.value = current.copy(showStreamInfoEnabled = enabled)
        prefs.edit().putBoolean(KEY_SHOW_STREAM_INFO, enabled).apply()
    }

    fun setTopBarVisualizationMode(mode: TopBarVisualizationMode) {
        val current = _settingsState.value
        if (current.topBarVisualizationMode == mode) {
            return
        }
        _settingsState.value = current.copy(topBarVisualizationMode = mode)
        prefs.edit().putString(KEY_TOP_BAR_VISUALIZATION_MODE, mode.prefValue).apply()
    }

    fun setBackgroundBlurStrength(value: Int) {
        val normalized = value.coerceIn(0, 220)
        val current = _settingsState.value
        if (current.backgroundBlurStrength == normalized) {
            return
        }
        _settingsState.value = current.copy(backgroundBlurStrength = normalized)
        prefs.edit().putInt(KEY_BACKGROUND_BLUR_STRENGTH, normalized).apply()
    }

    fun setBackgroundOpacityPercent(value: Int) {
        val normalized = value.coerceIn(0, 100)
        val current = _settingsState.value
        if (current.backgroundOpacityPercent == normalized) {
            return
        }
        _settingsState.value = current.copy(backgroundOpacityPercent = normalized)
        prefs.edit().putInt(KEY_BACKGROUND_OPACITY_PERCENT, normalized).apply()
    }

    fun setBackgroundDynamicEnabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.backgroundDynamicEnabled == enabled) {
            return
        }
        _settingsState.value = current.copy(backgroundDynamicEnabled = enabled)
        prefs.edit().putBoolean(KEY_BACKGROUND_DYNAMIC_ENABLED, enabled).apply()
    }

    fun setLyricsFontSizeSp(value: Int) {
        val normalized = value.coerceIn(12, 40)
        val current = _settingsState.value
        if (current.lyricsFontSizeSp == normalized) {
            return
        }
        _settingsState.value = current.copy(lyricsFontSizeSp = normalized)
        prefs.edit().putInt(KEY_LYRICS_FONT_SIZE_SP, normalized).apply()
    }

    fun setLyricsGlowEnabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.lyricsGlowEnabled == enabled) {
            return
        }
        _settingsState.value = current.copy(lyricsGlowEnabled = enabled)
        prefs.edit().putBoolean(KEY_LYRICS_GLOW_ENABLED, enabled).apply()
    }

    fun setLyricsGlowIntensityPercent(value: Int) {
        val normalized = value.coerceIn(0, 100)
        val current = _settingsState.value
        if (current.lyricsGlowIntensityPercent == normalized) {
            return
        }
        _settingsState.value = current.copy(lyricsGlowIntensityPercent = normalized)
        prefs.edit().putInt(KEY_LYRICS_GLOW_INTENSITY_PERCENT, normalized).apply()
    }

    fun setLyricsBoldEnabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.lyricsBoldEnabled == enabled) {
            return
        }
        _settingsState.value = current.copy(lyricsBoldEnabled = enabled)
        prefs.edit().putBoolean(KEY_LYRICS_BOLD_ENABLED, enabled).apply()
    }

    fun setPreferredBitDepth(bitDepth: Int) {
        val normalized = normalizeBitDepth(bitDepth)
        val current = _settingsState.value
        if (current.preferredBitDepth == normalized) {
            return
        }
        _settingsState.value = current.copy(
            preferredBitDepth = normalized,
            preferredUsbDirectBitDepth = normalized
        )
        prefs.edit()
            .putInt(KEY_PREFERRED_BIT_DEPTH, normalized)
            .remove(KEY_PREFERRED_USB_DIRECT_BIT_DEPTH)
            .apply()
        rebuildPlayerPreservingState()
        refreshOutputInfo()
    }

    fun setPreferredOutputSampleRateHz(sampleRateHz: Int?) {
        val normalized = normalizeOutputSampleRate(sampleRateHz)
        val current = _settingsState.value
        if (current.preferredOutputSampleRateHz == normalized) {
            return
        }
        _settingsState.value = current.copy(
            preferredOutputSampleRateHz = normalized,
            preferredUsbDirectSampleRateHz = normalized
        )
        val editor = prefs.edit()
        if (normalized == null) {
            editor.remove(KEY_PREFERRED_OUTPUT_SAMPLE_RATE_HZ)
        } else {
            editor.putInt(KEY_PREFERRED_OUTPUT_SAMPLE_RATE_HZ, normalized)
        }
        editor.remove(KEY_PREFERRED_USB_DIRECT_SAMPLE_RATE_HZ)
        editor.apply()
        applyPreferredOutputDevice()
        refreshUsbDirectTransportConfigNow()
        refreshOutputInfo()
    }

    fun setPreferredMaxAudioBitrateKbps(kbps: Int?) {
        val normalized = kbps?.coerceIn(MAX_AUDIO_BITRATE_MIN_KBPS, MAX_AUDIO_BITRATE_MAX_KBPS)
        val current = _settingsState.value
        if (current.preferredMaxBitrateKbps == normalized) {
            return
        }
        _settingsState.value = current.copy(preferredMaxBitrateKbps = normalized)
        val editor = prefs.edit()
        if (normalized == null) {
            editor.remove(KEY_MAX_AUDIO_BITRATE_KBPS)
        } else {
            editor.putInt(KEY_MAX_AUDIO_BITRATE_KBPS, normalized)
        }
        editor.apply()
        applyTrackSelectionPreferences()
    }

    fun setPreferredUsbOutputDeviceId(deviceId: Int?) {
        val current = _settingsState.value
        if (current.preferredUsbDeviceId == deviceId) {
            return
        }
        _settingsState.value = current.copy(preferredUsbDeviceId = deviceId)
        val editor = prefs.edit()
        if (deviceId == null) {
            editor.remove(KEY_PREFERRED_USB_DEVICE_ID)
        } else {
            editor.putInt(KEY_PREFERRED_USB_DEVICE_ID, deviceId)
        }
        editor.apply()
        applyPreferredOutputDevice()
        refreshOutputInfo()
    }

    fun setPreferredUsbDirectSampleRateHz(sampleRateHz: Int?) {
        setPreferredOutputSampleRateHz(sampleRateHz)
    }

    fun setPreferredUsbDirectBitDepth(bitDepth: Int?) {
        setPreferredBitDepth(bitDepth?.let(::normalizeBitDepth) ?: BIT_DEPTH_32_FLOAT)
    }

    fun setPreferredUsbResampleAlgorithm(algorithm: Int) {
        val normalized = algorithm.coerceIn(USB_RESAMPLER_ALGORITHM_NEAREST, USB_RESAMPLER_ALGORITHM_SOXR_HQ)
        val current = _settingsState.value
        if (current.preferredUsbResampleAlgorithm == normalized) {
            return
        }
        _settingsState.value = current.copy(preferredUsbResampleAlgorithm = normalized)
        prefs.edit().putInt(KEY_PREFERRED_USB_RESAMPLE_ALGORITHM, normalized).apply()
        applyPreferredOutputDevice()
        refreshUsbDirectTransportConfigNow()
        refreshOutputInfo()
    }

    fun setUsbExclusiveModeEnabled(enabled: Boolean) {
        val current = _settingsState.value
        if (current.usbExclusiveModeEnabled == enabled) {
            return
        }
        _settingsState.value = current.copy(usbExclusiveModeEnabled = enabled)
        prefs.edit().putBoolean(KEY_USB_EXCLUSIVE_MODE, enabled).apply()
        applyPreferredOutputDevice()
        refreshOutputInfo()
    }

    fun setEffectsEnabled(enabled: Boolean) {
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(enabled = enabled))
        prefs.edit().putBoolean(KEY_EFFECT_ENABLED, enabled).apply()
        applyEffectState()
    }

    fun setLimiterEnabled(enabled: Boolean) {
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(limiterEnabled = enabled))
        prefs.edit().putBoolean(KEY_LIMITER_ENABLED, enabled).apply()
        applyEffectState()
    }

    fun setPanPercent(value: Int) {
        val clamped = value.coerceIn(-100, 100)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(panPercent = clamped))
        prefs.edit().putInt(KEY_PAN_PERCENT, clamped).apply()
        applyEffectState()
    }

    fun setPanInvertEnabled(enabled: Boolean) {
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(panInvertEnabled = enabled))
        prefs.edit().putBoolean(KEY_PAN_INVERT_ENABLED, enabled).apply()
        applyEffectState()
    }

    fun setMonoEnabled(enabled: Boolean) {
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(monoEnabled = enabled))
        prefs.edit().putBoolean(KEY_MONO_ENABLED, enabled).apply()
        applyEffectState()
    }

    fun setVocalRemovalEnabled(enabled: Boolean) {
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(vocalRemovalEnabled = enabled))
        prefs.edit().putBoolean(KEY_VOCAL_REMOVAL_ENABLED, enabled).apply()
        applyEffectState()
    }

    fun setVocalKeyShiftSemitones(value: Int) {
        val normalized = value.coerceIn(-24, 24)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(vocalKeyShiftSemitones = normalized))
        prefs.edit().putInt(KEY_VOCAL_KEY_SHIFT_SEMITONES, normalized).apply()
        applyEffectState()
    }

    fun adjustVocalKeyShiftSemitonesByStep(step: Int) {
        if (step == 0) return
        val current = _effectsState.value.vocalKeyShiftSemitones
        setVocalKeyShiftSemitones(current + step)
    }

    fun setVocalBandLowCutHz(value: Int) {
        val current = _effectsState.value
        val normalized = value.coerceIn(60, current.vocalBandHighCutHz - 100)
        _effectsState.value = withSpatialDerived(current.copy(vocalBandLowCutHz = normalized))
        prefs.edit().putInt(KEY_VOCAL_BAND_LOW_CUT_HZ, normalized).apply()
        applyEffectState()
    }

    fun setVocalBandHighCutHz(value: Int) {
        val current = _effectsState.value
        val normalized = value.coerceIn(current.vocalBandLowCutHz + 100, 8000)
        _effectsState.value = withSpatialDerived(current.copy(vocalBandHighCutHz = normalized))
        prefs.edit().putInt(KEY_VOCAL_BAND_HIGH_CUT_HZ, normalized).apply()
        applyEffectState()
    }

    fun setPhaseInvertEnabled(enabled: Boolean) {
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(phaseInvertEnabled = enabled))
        prefs.edit().putBoolean(KEY_PHASE_INVERT_ENABLED, enabled).apply()
        applyEffectState()
    }

    fun setRightChannelPhaseInvertEnabled(enabled: Boolean) {
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(rightChannelPhaseInvertEnabled = enabled))
        prefs.edit().putBoolean(KEY_RIGHT_CHANNEL_PHASE_INVERT_ENABLED, enabled).apply()
        applyEffectState()
    }

    fun setCrossfeedPercent(value: Int) {
        val clamped = value.coerceIn(0, 100)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(crossfeedPercent = clamped))
        prefs.edit().putInt(KEY_CROSSFEED_PERCENT, clamped).apply()
        applyEffectState()
    }

    fun setPlaybackSpeedPercent(value: Int) {
        val clamped = value.coerceIn(50, 200)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(playbackSpeedPercent = clamped))
        prefs.edit().putInt(KEY_PLAYBACK_SPEED_PERCENT, clamped).apply()
        applyPlaybackSpeed(_effectsState.value)
    }

    fun adjustPlaybackSpeedByStep(stepPercent: Int) {
        if (stepPercent == 0) return
        val current = _effectsState.value.playbackSpeedPercent
        setPlaybackSpeedPercent(current + stepPercent)
    }

    fun setPlaybackSpeedPitchCompensationEnabled(enabled: Boolean) {
        _effectsState.value = withSpatialDerived(
            _effectsState.value.copy(playbackSpeedPitchCompensationEnabled = enabled)
        )
        prefs.edit().putBoolean(KEY_PLAYBACK_SPEED_PITCH_COMPENSATION, enabled).apply()
        applyPlaybackSpeed(_effectsState.value)
    }

    fun setTransientPlaybackSpeedPercent(value: Int) {
        val normalizedPercent = value.coerceIn(25, 500)
        val speed = normalizedPercent / 100f
        val pitch = resolvePitchForSpeed(_effectsState.value, speed)
        transientPlaybackSpeedOverride = speed
        transientPlaybackPitchOverride = pitch
        applyPlaybackSpeed(_effectsState.value)
    }

    fun setTransientPlaybackSpeedOverride(speed: Float, preservePitch: Boolean = false) {
        val normalized = speed.coerceIn(0.25f, 5.0f)
        transientPlaybackSpeedOverride = normalized
        transientPlaybackPitchOverride = if (preservePitch) 1f else normalized
        applyPlaybackSpeed(_effectsState.value)
    }

    fun clearTransientPlaybackSpeedOverride() {
        transientPlaybackSpeedOverride = null
        transientPlaybackPitchOverride = null
        applyPlaybackSpeed(_effectsState.value)
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

    fun setEqEnabled(enabled: Boolean) {
        val current = _effectsState.value
        if (current.eqEnabled == enabled) {
            return
        }
        _effectsState.value = withSpatialDerived(current.copy(eqEnabled = enabled))
        prefs.edit().putBoolean(KEY_EQ_ENABLED, enabled).apply()
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
        val next = withSpatialDerived(markEqPresetAsCustom(current.copy(eqBandLevelsMb = updated)))
        _effectsState.value = next
        prefs.edit().putInt("$KEY_EQ_BAND_LEVEL_PREFIX$index", clamped).apply()
        persistEqBandConfig(next)
        applyEffectState()
    }

    fun setBandLevel(index: Int, level: Int) {
        setEqBandLevel(index, level)
    }

    fun setEqBandFrequency(index: Int, frequencyHz: Int) {
        val current = _effectsState.value
        if (index !in current.eqBandFrequenciesHz.indices) {
            return
        }
        val clamped = frequencyHz.coerceIn(20, 20_000)
        val updated = current.eqBandFrequenciesHz.toMutableList()
        updated[index] = clamped
        val next = withSpatialDerived(markEqPresetAsCustom(current.copy(eqBandFrequenciesHz = updated)))
        _effectsState.value = next
        persistEqBandConfig(next)
        applyEffectState()
    }

    fun setEqBandQ(index: Int, qTimes100: Int) {
        val current = _effectsState.value
        if (index !in current.eqBandQTimes100.indices) {
            return
        }
        val clamped = qTimes100.coerceIn(20, 1200)
        val updated = current.eqBandQTimes100.toMutableList()
        updated[index] = clamped
        val next = withSpatialDerived(markEqPresetAsCustom(current.copy(eqBandQTimes100 = updated)))
        _effectsState.value = next
        persistEqBandConfig(next)
        applyEffectState()
    }

    fun addEqBandPoint(
        frequencyHz: Int = 1000,
        qTimes100: Int = 100,
        levelMb: Int = 0
    ) {
        val current = _effectsState.value
        if (current.eqBandFrequenciesHz.size >= 16) {
            return
        }
        val frequencies = current.eqBandFrequenciesHz.toMutableList()
        val levels = current.eqBandLevelsMb.toMutableList()
        val qs = current.eqBandQTimes100.toMutableList()
        frequencies += frequencyHz.coerceIn(20, 20_000)
        levels += levelMb.coerceIn(current.eqBandLevelMinMb, current.eqBandLevelMaxMb)
        qs += qTimes100.coerceIn(20, 1200)
        val next = withSpatialDerived(
            markEqPresetAsCustom(current.copy(
                eqBandFrequenciesHz = frequencies,
                eqBandLevelsMb = levels,
                eqBandQTimes100 = qs
            ))
        )
        _effectsState.value = next
        persistEqBandConfig(next)
        applyEffectState()
    }

    fun removeEqBandPoint(index: Int) {
        val current = _effectsState.value
        if (current.eqBandFrequenciesHz.size <= 1 || index !in current.eqBandFrequenciesHz.indices) {
            return
        }
        val frequencies = current.eqBandFrequenciesHz.toMutableList().also { it.removeAt(index) }
        val levels = current.eqBandLevelsMb.toMutableList().also { it.removeAt(index) }
        val qs = current.eqBandQTimes100.toMutableList().also { it.removeAt(index) }
        val next = withSpatialDerived(
            markEqPresetAsCustom(current.copy(
                eqBandFrequenciesHz = frequencies,
                eqBandLevelsMb = levels,
                eqBandQTimes100 = qs
            ))
        )
        _effectsState.value = next
        persistEqBandConfig(next)
        applyEffectState()
    }

    fun applyEqPreset(name: String) {
        val preset = findEqPresetByName(name) ?: return
        val current = _effectsState.value
        val next = withSpatialDerived(
            current.copy(
                eqBandFrequenciesHz = preset.config.frequenciesHz,
                eqBandLevelsMb = preset.config.levelsMb,
                eqBandQTimes100 = preset.config.qTimes100,
                eqActivePresetName = preset.name,
                eqPresetNames = buildEqPresetNames(eqCustomPresets)
            )
        )
        _effectsState.value = next
        persistEqBandConfig(next)
        prefs.edit().putString(KEY_EQ_ACTIVE_PRESET_NAME, preset.name).apply()
        applyEffectState()
    }

    fun saveCurrentEqAsPreset(name: String) {
        val normalized = name.trim().ifBlank { return }
        val current = _effectsState.value
        val config = EqBandConfig(
            frequenciesHz = current.eqBandFrequenciesHz.map { it.coerceIn(20, 20_000) },
            levelsMb = current.eqBandLevelsMb.map { it.coerceIn(current.eqBandLevelMinMb, current.eqBandLevelMaxMb) },
            qTimes100 = current.eqBandQTimes100.map { it.coerceIn(20, 1200) }
        )
        val existing = eqCustomPresets.indexOfFirst { it.name.equals(normalized, ignoreCase = true) }
        val preset = EqPreset(normalized, config, builtIn = false)
        if (existing >= 0) {
            eqCustomPresets[existing] = preset
        } else {
            eqCustomPresets.add(preset)
        }
        persistCustomEqPresets(eqCustomPresets)
        val next = current.copy(
            eqPresetNames = buildEqPresetNames(eqCustomPresets),
            eqActivePresetName = normalized
        )
        _effectsState.value = withSpatialDerived(next)
        prefs.edit().putString(KEY_EQ_ACTIVE_PRESET_NAME, normalized).apply()
    }

    fun isBuiltInEqPreset(name: String): Boolean {
        val normalized = name.trim()
        if (normalized.isBlank()) return false
        return builtInEqPresets().any { it.name.equals(normalized, ignoreCase = true) }
    }

    fun deleteEqPreset(name: String): Boolean {
        val normalized = name.trim()
        if (normalized.isBlank()) return false
        if (isBuiltInEqPreset(normalized)) return false
        val existing = eqCustomPresets.indexOfFirst { it.name.equals(normalized, ignoreCase = true) }
        if (existing < 0) return false
        eqCustomPresets.removeAt(existing)
        persistCustomEqPresets(eqCustomPresets)
        val current = _effectsState.value
        val nextPresetNames = buildEqPresetNames(eqCustomPresets)
        val nextActive = when {
            current.eqActivePresetName.equals(normalized, ignoreCase = true) -> EQ_PRESET_CUSTOM_NAME
            current.eqActivePresetName in nextPresetNames -> current.eqActivePresetName
            else -> nextPresetNames.firstOrNull().orEmpty()
        }
        val next = withSpatialDerived(
            current.copy(
                eqPresetNames = nextPresetNames,
                eqActivePresetName = nextActive
            )
        )
        _effectsState.value = next
        prefs.edit().putString(KEY_EQ_ACTIVE_PRESET_NAME, nextActive).apply()
        return true
    }

    fun setSpatialEnabled(enabled: Boolean) {
        val current = _effectsState.value
        val updated = current.copy(spatialEnabled = enabled)
        _effectsState.value = withSpatialDerived(updated)
        prefs.edit().putBoolean(KEY_SPATIAL_ENABLED, enabled).apply()
        applyEffectState()
    }

    fun setChannelSeparated(enabled: Boolean) {
        val state = _effectsState.value
        val updated = if (enabled) {
            state.copy(
                channelSeparated = true,
                linkedChannelSpacingCm = linkedSpacingFromState(state)
            )
        } else {
            val mergedX = ((state.spatialLeftX + state.spatialRightX) / 2).coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM)
            val mergedY = ((state.spatialLeftY + state.spatialRightY) / 2).coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM)
            val mergedZ = ((state.spatialLeftZ + state.spatialRightZ) / 2).coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM)
            val mergedRadius =
                ((state.spatialLeftRadiusPercent + state.spatialRightRadiusPercent) / 2)
                    .coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM)
            val linked = buildLinkedChannelPair(
                centerX = mergedX,
                centerZ = mergedZ,
                spacingCm = linkedSpacingFromState(state),
                radiusCm = mergedRadius
            )
            state.copy(
                channelSeparated = false,
                spatialLeftX = linked.leftX,
                spatialLeftY = mergedY,
                spatialLeftZ = linked.leftZ,
                spatialRightX = linked.rightX,
                spatialRightY = mergedY,
                spatialRightZ = linked.rightZ,
                spatialLeftRadiusPercent = mergedRadius,
                spatialRightRadiusPercent = mergedRadius,
                linkedChannelSpacingCm = linked.spacingCm
            )
        }
        _effectsState.value = withSpatialDerived(updated)
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialPositionX(value: Int) {
        val state = _effectsState.value
        if (state.channelSeparated) {
            val radius = state.spatialLeftRadiusPercent.coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM)
            val clamped = value.coerceIn(-radius, radius)
            _effectsState.value = withSpatialDerived(state.copy(spatialLeftX = clamped, spatialRightX = clamped))
            persistSpatialPosition(_effectsState.value)
            applyEffectState()
            return
        }
        val radius = min(state.spatialLeftRadiusPercent, state.spatialRightRadiusPercent)
            .coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM)
        val clamped = value.coerceIn(-radius, radius)
        val center = effectiveSpatialCenter(state)
        val linked = buildLinkedChannelPair(
            centerX = clamped,
            centerZ = center.z,
            spacingCm = linkedSpacingFromState(state),
            radiusCm = radius
        )
        _effectsState.value = withSpatialDerived(
            state.copy(
                spatialLeftX = linked.leftX,
                spatialRightX = linked.rightX,
                spatialLeftZ = linked.leftZ,
                spatialRightZ = linked.rightZ,
                spatialLeftY = center.y,
                spatialRightY = center.y,
                spatialLeftRadiusPercent = radius,
                spatialRightRadiusPercent = radius,
                linkedChannelSpacingCm = linked.spacingCm
            )
        )
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialPositionY(value: Int) {
        val state = _effectsState.value
        val clamped = value.coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM)
        _effectsState.value = withSpatialDerived(state.copy(spatialLeftY = clamped, spatialRightY = clamped))
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialPositionZ(value: Int) {
        val state = _effectsState.value
        if (state.channelSeparated) {
            val radius = state.spatialLeftRadiusPercent.coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM)
            val clamped = value.coerceIn(-radius, radius)
            _effectsState.value = withSpatialDerived(state.copy(spatialLeftZ = clamped, spatialRightZ = clamped))
            persistSpatialPosition(_effectsState.value)
            applyEffectState()
            return
        }
        val radius = min(state.spatialLeftRadiusPercent, state.spatialRightRadiusPercent)
            .coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM)
        val clamped = value.coerceIn(-radius, radius)
        val center = effectiveSpatialCenter(state)
        val linked = buildLinkedChannelPair(
            centerX = center.x,
            centerZ = clamped,
            spacingCm = linkedSpacingFromState(state),
            radiusCm = radius
        )
        _effectsState.value = withSpatialDerived(
            state.copy(
                spatialLeftX = linked.leftX,
                spatialRightX = linked.rightX,
                spatialLeftZ = linked.leftZ,
                spatialRightZ = linked.rightZ,
                spatialLeftY = center.y,
                spatialRightY = center.y,
                spatialLeftRadiusPercent = radius,
                spatialRightRadiusPercent = radius,
                linkedChannelSpacingCm = linked.spacingCm
            )
        )
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialLeftPositionX(value: Int) {
        val radius = _effectsState.value.spatialLeftRadiusPercent.coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM)
        val clamped = value.coerceIn(-radius, radius)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(spatialLeftX = clamped))
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialLeftPositionY(value: Int) {
        val clamped = value.coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(spatialLeftY = clamped))
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialLeftPositionZ(value: Int) {
        val radius = _effectsState.value.spatialLeftRadiusPercent.coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM)
        val clamped = value.coerceIn(-radius, radius)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(spatialLeftZ = clamped))
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialRightPositionX(value: Int) {
        val radius = _effectsState.value.spatialRightRadiusPercent.coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM)
        val clamped = value.coerceIn(-radius, radius)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(spatialRightX = clamped))
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialRightPositionY(value: Int) {
        val clamped = value.coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(spatialRightY = clamped))
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialRightPositionZ(value: Int) {
        val radius = _effectsState.value.spatialRightRadiusPercent.coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM)
        val clamped = value.coerceIn(-radius, radius)
        _effectsState.value = withSpatialDerived(_effectsState.value.copy(spatialRightZ = clamped))
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialRadiusPercent(value: Int) {
        val clamped = value.coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM)
        val state = _effectsState.value
        val updated = if (state.channelSeparated) {
            state.copy(
                spatialLeftRadiusPercent = clamped,
                spatialRightRadiusPercent = clamped,
                spatialLeftX = state.spatialLeftX.coerceIn(-clamped, clamped),
                spatialLeftZ = state.spatialLeftZ.coerceIn(-clamped, clamped),
                spatialRightX = state.spatialRightX.coerceIn(-clamped, clamped),
                spatialRightZ = state.spatialRightZ.coerceIn(-clamped, clamped)
            )
        } else {
            val center = effectiveSpatialCenter(state)
            val linked = buildLinkedChannelPair(
                centerX = center.x,
                centerZ = center.z,
                spacingCm = linkedSpacingFromState(state),
                radiusCm = clamped
            )
            state.copy(
                spatialLeftRadiusPercent = clamped,
                spatialRightRadiusPercent = clamped,
                spatialLeftX = linked.leftX,
                spatialLeftY = center.y,
                spatialLeftZ = linked.leftZ,
                spatialRightX = linked.rightX,
                spatialRightY = center.y,
                spatialRightZ = linked.rightZ,
                linkedChannelSpacingCm = linked.spacingCm
            )
        }
        _effectsState.value = withSpatialDerived(updated)
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setLinkedChannelSpacingCm(value: Int) {
        val state = _effectsState.value
        val clamped = value.coerceIn(LINKED_CHANNEL_SPACING_MIN_CM, LINKED_CHANNEL_SPACING_MAX_CM)
        val updated = if (state.channelSeparated) {
            state.copy(linkedChannelSpacingCm = clamped)
        } else {
            val radius = min(state.spatialLeftRadiusPercent, state.spatialRightRadiusPercent)
                .coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM)
            val center = effectiveSpatialCenter(state)
            val linked = buildLinkedChannelPair(
                centerX = center.x,
                centerZ = center.z,
                spacingCm = clamped,
                radiusCm = radius
            )
            state.copy(
                linkedChannelSpacingCm = linked.spacingCm,
                spatialLeftRadiusPercent = radius,
                spatialRightRadiusPercent = radius,
                spatialLeftX = linked.leftX,
                spatialLeftY = center.y,
                spatialLeftZ = linked.leftZ,
                spatialRightX = linked.rightX,
                spatialRightY = center.y,
                spatialRightZ = linked.rightZ
            )
        }
        _effectsState.value = withSpatialDerived(updated)
        persistSpatialPosition(_effectsState.value)
        applyEffectState()
    }

    fun setSpatialLeftRadiusPercent(value: Int) {
        val clamped = value.coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM)
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
        val clamped = value.coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM)
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
        persistSpatialPosition(_effectsState.value)
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
        val current = _effectsState.value
        val updated = current.copy(surroundMode = clamped)
        _effectsState.value = withSpatialDerived(updated)
        prefs.edit().putInt(KEY_SURROUND_MODE, clamped).apply()
        applyEffectState()
    }

    fun setSurroundChannelGain(channel: Int, valuePercent: Int) {
        val clamped = valuePercent.coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT)
        val state = _effectsState.value
        val updated = when (state.surroundMode) {
            SURROUND_MODE_STEREO -> when (channel) {
                SURROUND_CHANNEL_FRONT_LEFT -> state.copy(surroundStereoFlPercent = clamped)
                SURROUND_CHANNEL_FRONT_RIGHT -> state.copy(surroundStereoFrPercent = clamped)
                else -> state
            }

            SURROUND_MODE_5_1 -> when (channel) {
                SURROUND_CHANNEL_FRONT_LEFT -> state.copy(surround51FlPercent = clamped)
                SURROUND_CHANNEL_FRONT_RIGHT -> state.copy(surround51FrPercent = clamped)
                SURROUND_CHANNEL_CENTER -> state.copy(surround51CPercent = clamped)
                SURROUND_CHANNEL_LFE -> state.copy(surround51LfePercent = clamped)
                SURROUND_CHANNEL_SIDE_LEFT -> state.copy(surround51SlPercent = clamped)
                SURROUND_CHANNEL_SIDE_RIGHT -> state.copy(surround51SrPercent = clamped)
                else -> state
            }

            SURROUND_MODE_7_1 -> when (channel) {
                SURROUND_CHANNEL_FRONT_LEFT -> state.copy(surround71FlPercent = clamped)
                SURROUND_CHANNEL_FRONT_RIGHT -> state.copy(surround71FrPercent = clamped)
                SURROUND_CHANNEL_CENTER -> state.copy(surround71CPercent = clamped)
                SURROUND_CHANNEL_LFE -> state.copy(surround71LfePercent = clamped)
                SURROUND_CHANNEL_SIDE_LEFT -> state.copy(surround71SlPercent = clamped)
                SURROUND_CHANNEL_SIDE_RIGHT -> state.copy(surround71SrPercent = clamped)
                SURROUND_CHANNEL_REAR_LEFT -> state.copy(surround71RlPercent = clamped)
                SURROUND_CHANNEL_REAR_RIGHT -> state.copy(surround71RrPercent = clamped)
                else -> state
            }

            else -> state
        }
        if (updated == state) {
            return
        }
        _effectsState.value = withSpatialDerived(updated)
        persistSurroundGains(_effectsState.value)
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
                        convolutionIrName = "IRS 瀵煎叆澶辫触"
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
                convolutionIrName = "鏈鍏?IRS",
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
        // Re-apply routing/exclusive before reading status, so UI reflects actual active state.
        applyPreferredOutputDevice()
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
        val usbDevices = audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.filter { it.isUsbOutputDevice() }
            ?.sortedBy { it.productName?.toString().orEmpty().lowercase() }
            .orEmpty()
        val usbOptions = usbDevices.map { UsbOutputOption(id = it.id, label = buildUsbDeviceLabel(it)) }
        val preferredUsbDevice = _settingsState.value.preferredUsbDeviceId
            ?.takeIf { id -> usbOptions.any { it.id == id } }
        val preferredUsbDeviceInfo = preferredUsbDevice?.let { id ->
            usbDevices.firstOrNull { it.id == id }
        }
        val usbDirectSampleRateOptions = OUTPUT_SAMPLE_RATE_OPTIONS_HZ.toList()
        val usbDirectBitDepthOptions = listOf(BIT_DEPTH_16, BIT_DEPTH_32_FLOAT)
        val deviceSampleRateOptions = preferredUsbDeviceInfo
            ?.sampleRates
            ?.filter { it > 0 }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        val deviceBitDepthOptions = preferredUsbDeviceInfo
            ?.let(::supportedDeviceBitDepths)
            .orEmpty()
        val usbDirectCapabilitySummary = buildUsbDirectCapabilitySummary(
            preferredUsbDeviceInfo,
            deviceSampleRateOptions,
            deviceBitDepthOptions
        )
        val activeRoute = preferredUsbDevice
            ?.let { id -> usbOptions.firstOrNull { it.id == id }?.label }
            ?: "系统默认"
        val usbExclusiveSupported = if (audioManager != null && preferredUsbDeviceInfo != null) {
            queryUsbExclusiveSupport(audioManager, preferredUsbDeviceInfo)
        } else {
            false
        }
        val appliedMixerAttr = if (audioManager != null && preferredUsbDeviceInfo != null) {
            getPreferredMixerAttributesOrNull(audioManager, preferredUsbDeviceInfo)
        } else {
            null
        }
        val mixerExclusiveActive = _settingsState.value.usbExclusiveModeEnabled &&
            preferredUsbDeviceInfo != null &&
            usbExclusiveSupported &&
            usbExclusiveActiveCached &&
            appliedMixerAttr?.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        val usbExclusiveActive = mixerExclusiveActive || usbHostDirectActiveCached
        val usbSrcBypassGuaranteed = usbExclusiveActive &&
            (usbSrcBypassGuaranteedCached || usbHostDirectActiveCached)
        val usbCompatibilityActive = _settingsState.value.usbExclusiveModeEnabled &&
            preferredUsbDeviceInfo != null &&
            !usbExclusiveActive &&
            usbCompatibilityActiveCached
        ensureBitPerfectBypassState(usbSrcBypassGuaranteed)
        val pipelineDetails = buildAudioPipelineDetails(
            audioManager = audioManager,
            preferredUsbDeviceInfo = preferredUsbDeviceInfo,
            systemOutputSampleRateHz = sampleRate,
            systemOutputFramesPerBuffer = frames,
            offloadSupported = offload
        )

        _settingsState.value = _settingsState.value.copy(
            outputSampleRateHz = sampleRate,
            outputFramesPerBuffer = frames,
            offloadSupported = offload,
            usbOutputOptions = usbOptions,
            preferredUsbDeviceId = preferredUsbDevice,
            preferredUsbDirectSampleRateHz = _settingsState.value.preferredOutputSampleRateHz,
            preferredUsbDirectBitDepth = _settingsState.value.preferredBitDepth,
            activeOutputRouteLabel = activeRoute,
            usbExclusiveSupported = usbExclusiveSupported,
            usbExclusiveActive = usbExclusiveActive,
            usbSrcBypassGuaranteed = usbSrcBypassGuaranteed,
            usbHostDirectSupported = usbHostDirectSupportedCached,
            usbHostDirectActive = usbHostDirectActiveCached,
            usbCompatibilityActive = usbCompatibilityActive,
            usbResolvedSampleRateHz = usbResolvedSampleRateHzCached,
            usbResolvedBitDepth = usbResolvedBitDepthCached,
            usbDirectSampleRateOptionsHz = usbDirectSampleRateOptions,
            usbDirectBitDepthOptions = usbDirectBitDepthOptions,
            usbDirectCapabilitySummary = usbDirectCapabilitySummary,
            audioPipelineDetails = pipelineDetails
        )
    }

    private fun updateAudioPipelineDetailsSnapshot() {
        if (!initialized) {
            return
        }
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        refreshBluetoothCodecInfo(audioManager)
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
        val preferredUsbDeviceInfo = _settingsState.value.preferredUsbDeviceId?.let { preferredId ->
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.firstOrNull { it.id == preferredId && it.isUsbOutputDevice() }
        }
        _settingsState.value = _settingsState.value.copy(
            audioPipelineDetails = buildAudioPipelineDetails(
                audioManager = audioManager,
                preferredUsbDeviceInfo = preferredUsbDeviceInfo,
                systemOutputSampleRateHz = sampleRate,
                systemOutputFramesPerBuffer = frames,
                offloadSupported = offload
            )
        )
    }

    private fun buildUsbDirectCapabilitySummary(
        device: AudioDeviceInfo?,
        sampleRates: List<Int>,
        bitDepths: List<Int>
    ): String {
        if (device == null) {
            return "未选择外部 DAC"
        }
        val ratesLabel = sampleRates
            .takeIf { it.isNotEmpty() }
            ?.joinToString { "${it / 1000.0}k" }
            ?: "未报告"
        val bitDepthLabel = bitDepths
            .takeIf { it.isNotEmpty() }
            ?.joinToString { "${it}-bit" }
            ?: "未报告"
        return "设备能力：采样率[$ratesLabel] 位深[$bitDepthLabel]"
    }

    private fun usbResamplerAlgorithmLabel(algorithm: Int): String {
        return when (algorithm) {
            USB_RESAMPLER_ALGORITHM_NEAREST -> "Nearest"
            USB_RESAMPLER_ALGORITHM_CUBIC -> "Cubic"
            USB_RESAMPLER_ALGORITHM_SOXR_HQ -> "SoXr HQ"
            else -> "Linear"
        }
    }

    private fun encodingBitDepthLabel(encoding: Int): String? {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> "16bit"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24bit"
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> "32bit"
            else -> null
        }
    }

    private fun formatStreamInfo(currentPlayer: ExoPlayer?, playback: PlaybackUiState): StreamBannerInfo {
        val format = resolveSelectedAudioFormat(currentPlayer)
        val settings = _settingsState.value
        val left = encodingBitDepthLabel(format?.pcmEncoding ?: Format.NO_VALUE)
            ?: format?.bitrate?.takeIf { it > 0 }?.let { "${(it / 1000f).roundToInt()}k" }
            ?: "--"
        val right = format?.sampleRate?.takeIf { it > 0 }?.let {
            val khz = it / 1000f
            if (khz % 1f == 0f) "${khz.roundToInt()}k" else String.format("%.1fk", khz)
        } ?: "--"
        val hostRouteLabel = usbHostDirectRouteLabelCached.substringBefore(' ').trim()
        val isIsoDac = settings.usbHostDirectActive && hostRouteLabel.equals("ISO", ignoreCase = true)
        val useDirectDacTheme = settings.usbHostDirectActive
        val useDacAccent = settings.usbExclusiveActive || settings.usbCompatibilityActive || settings.usbHostDirectActive
        val bluetoothCodecLabel = bluetoothCodecLabelCached
        val center = when {
            isIsoDac -> appContext.getString(R.string.stream_mode_iso_dac)
            settings.usbHostDirectActive -> appContext.getString(R.string.stream_mode_direct_dac)
            settings.usbCompatibilityActive -> appContext.getString(R.string.stream_mode_compat_dac)
            settings.usbExclusiveActive -> appContext.getString(R.string.stream_mode_bitperfect_dac)
            !bluetoothCodecLabel.isNullOrBlank() -> bluetoothCodecLabel
            playback.isPlaying -> appContext.getString(R.string.stream_mode_android_audio)
            else -> ""
        }
        return StreamBannerInfo(
            visible = playback.isPlaying && playback.hasMedia,
            left = left,
            center = center,
            right = right,
            useDacAccent = useDacAccent,
            useIsoDacTheme = useDirectDacTheme
        )
    }

    private fun buildAudioPipelineDetails(
        audioManager: AudioManager?,
        preferredUsbDeviceInfo: AudioDeviceInfo?,
        systemOutputSampleRateHz: Int?,
        systemOutputFramesPerBuffer: Int?,
        offloadSupported: Boolean
    ): String {
        val currentPlayer = player
        val settings = _settingsState.value
        val effects = _effectsState.value
        val playback = _playbackState.value
        val now = DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis()).toString()
        val selectedAudioFormat = resolveSelectedAudioFormat(currentPlayer)
        val tracksSummary = resolveSelectedTrackSummary(currentPlayer)
        val outputDevices = audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.toList()
            .orEmpty()

        return buildString {
            appendLine("更新时间: $now")
            appendLine("播放状态: ${if (playback.isPlaying) "Playing" else "Paused/Idle"}  position=${playback.positionMs}ms/${playback.durationMs}ms  sessionId=${playback.audioSessionId}")
            appendLine("当前媒体: title=${playback.title}  subtitle=${playback.subtitle}  uri=${playback.mediaUri ?: "N/A"}")
            appendLine()
            appendLine("[音轨与解码]")
            appendLine("选中音轨: ${tracksSummary.ifBlank { "N/A" }}")
            appendLine("编码器MIME: ${selectedAudioFormat?.sampleMimeType ?: "N/A"}")
            appendLine("编码器codecs: ${selectedAudioFormat?.codecs ?: "N/A"}")
            appendLine("音轨采样率: ${selectedAudioFormat?.sampleRate?.takeIf { it > 0 } ?: "N/A"} Hz")
            appendLine("音轨声道数: ${selectedAudioFormat?.channelCount?.takeIf { it > 0 } ?: "N/A"}")
            appendLine("音轨码率: ${selectedAudioFormat?.bitrate?.takeIf { it > 0 } ?: "N/A"} bps")
            appendLine("音轨PCM编码: ${encodingToString(selectedAudioFormat?.pcmEncoding ?: Format.NO_VALUE)}")
            appendLine("解码器: ${lastAudioDecoderName ?: "N/A"}")
            appendLine("解码器初始化耗时: ${lastAudioDecoderInitDurationMs?.let { "${it}ms" } ?: "N/A"}")
            appendLine()
            appendLine("[处理与采样]")
            appendLine("HiFi: ${settings.hiFiMode}  Hi-Res API: ${settings.hiResApiEnabled}")
            appendLine("目标位深: ${settings.preferredBitDepth}-bit")
            appendLine("目标输出采样率: ${settings.preferredOutputSampleRateHz?.let { "${it}Hz" } ?: "Auto"}")
            appendLine("外部DAC目标位深: ${settings.preferredUsbDirectBitDepth?.let { "${it}-bit" } ?: "Auto"}")
            appendLine("外部DAC目标采样率: ${settings.preferredUsbDirectSampleRateHz?.let { "${it}Hz" } ?: "Auto"}")
            appendLine("外部DAC重采样算法: ${usbResamplerAlgorithmLabel(settings.preferredUsbResampleAlgorithm)}")
            appendLine("系统输出采样率: ${systemOutputSampleRateHz?.let { "${it}Hz" } ?: "N/A"}")
            appendLine("输出缓冲帧: ${systemOutputFramesPerBuffer ?: "N/A"}")
            appendLine("硬件Offload支持: $offloadSupported")
            appendLine("最大音频码率限制: ${settings.preferredMaxBitrateKbps?.let { "${it}kbps" } ?: "Unlimited"}")
            appendLine()
            appendLine("[信号处理链路]")
            appendLine("Equalizer: enabled=${effects.enabled} bands=${effects.eqBandLevelsMb.joinToString(prefix = "[", postfix = "]")}")
            appendLine(
                "Limiter=${effects.limiterEnabled} Pan=${effects.panPercent}% PanInvert=${effects.panInvertEnabled} " +
                "Mono=${effects.monoEnabled} VocalRemove=${effects.vocalRemovalEnabled} VocalKey=${effects.vocalKeyShiftSemitones}st VocalBand=${effects.vocalBandLowCutHz}-${effects.vocalBandHighCutHz}Hz PhaseInvert=${effects.phaseInvertEnabled} RightPhaseInvert=${effects.rightChannelPhaseInvertEnabled} Crossfeed=${effects.crossfeedPercent}%"
            )
            appendLine(
                "PlaybackSpeed=${effects.playbackSpeedPercent}% " +
                    "PitchComp=${effects.playbackSpeedPitchCompensationEnabled}"
            )
            appendLine("Spatial=${effects.spatialEnabled}  HRTF=${effects.hrtfEnabled} (db=${effects.hrtfUseDatabase}, blend=${effects.hrtfBlendPercent}%, crossfeed=${effects.hrtfCrossfeedPercent}%, externalization=${effects.hrtfExternalizationPercent}%)")
            appendLine("Convolution=${effects.convolutionEnabled}  IR=${effects.convolutionIrName}  Wet=${effects.convolutionWetPercent}%")
            appendLine()
            appendLine("[输出路由与API]")
            appendLine("播放引擎: Media3 ExoPlayer + DefaultAudioSink(AudioTrack)")
            appendLine("首选USB设备ID: ${settings.preferredUsbDeviceId ?: "Auto"}  当前路由: ${settings.activeOutputRouteLabel}")
            appendLine("USB独占开关: ${settings.usbExclusiveModeEnabled}  支持: ${settings.usbExclusiveSupported}  激活: ${settings.usbExclusiveActive}")
            appendLine("SRC旁路严格校验: ${settings.usbSrcBypassGuaranteed}")
            appendLine("USB Host直连能力: 支持=${settings.usbHostDirectSupported}  激活=${settings.usbHostDirectActive}")
            appendLine("USB Host能力说明: $usbHostCapabilityReasonCached")
            appendLine("USB Host backend: $usbHostDirectBackendLabelCached")
            appendLine("USB Host route: $usbHostDirectRouteLabelCached")
            appendLine("USB Host backend状态: ${usbHostDirectOutput?.debugStatus() ?: "inactive"}")
            appendLine("外部DAC能力: ${settings.usbDirectCapabilitySummary}")
            appendLine("BitPerfect旁路链路: $bitPerfectBypassActive")
            appendLine("USB兼容直通: ${settings.usbCompatibilityActive}  采样率: ${settings.usbResolvedSampleRateHz ?: "N/A"}  位深: ${settings.usbResolvedBitDepth ?: "N/A"}")
            appendLine("安卓版本: API ${Build.VERSION.SDK_INT}")
            appendLine()
            appendLine("[音频设备清单]")
            if (outputDevices.isEmpty()) {
                appendLine("无可用输出设备")
            } else {
                outputDevices.forEach { device ->
                    val rates = device.sampleRates.takeIf { it.isNotEmpty() }?.joinToString() ?: "N/A"
                    val channels = device.channelCounts.takeIf { it.isNotEmpty() }?.joinToString() ?: "N/A"
                    val enc = device.encodings.takeIf { it.isNotEmpty() }?.joinToString { encodingToString(it) } ?: "N/A"
                    appendLine(
                        "id=${device.id} type=${deviceTypeToString(device.type)} product=${device.productName ?: "N/A"} " +
                            "sink=${device.isSink} src=${device.isSource} rates=[$rates] channels=[$channels] enc=[$enc]"
                    )
                }
            }
            appendLine("HapticAudioEnabled=${settings.hapticAudioEnabled}")
            appendLine("HapticSoftwareShouldRun=${settings.hapticAudioEnabled && !bitPerfectBypassActive}")
            appendLine("HapticAudioDelayMs=${settings.hapticAudioDelayMs}")
            appendLine("SystemHapticGenerator=${hapticGenerator != null}")
            appendLine("HapticObjects driver=${hapticAudioDriver != null} processor=${hapticAudioProcessor != null}")
            appendLine("HapticDriver=${hapticAudioDriver?.debugStatus() ?: HapticAudioDriver.lastStatus()}")
            appendLine("HapticProcessor=${hapticAudioProcessor?.debugStatus() ?: "none"}")
            appendLine()
            appendLine("[USB Host设备枚举]")
            val usbManager = appContext.getSystemService(UsbManager::class.java)
            val usbDevices = usbManager?.deviceList?.values?.toList().orEmpty()
            if (usbDevices.isEmpty()) {
                appendLine("无USB设备")
            } else {
                usbDevices.forEach { usb ->
                    appendLine(
                        "usbDevice name=${usb.deviceName} vendorId=${usb.vendorId} productId=${usb.productId} " +
                            "class=${usbDeviceClassToString(usb.deviceClass)} interfaces=${usb.interfaceCount} " +
                            "perm=${usbManager?.hasPermission(usb) == true} product=${usb.productName ?: "N/A"}"
                    )
                    for (i in 0 until usb.interfaceCount) {
                        val intf = runCatching { usb.getInterface(i) }.getOrNull() ?: continue
                        appendLine(
                            "  intf#$i class=${usbDeviceClassToString(intf.interfaceClass)} sub=${intf.interfaceSubclass} " +
                                "proto=${intf.interfaceProtocol} eps=${intf.endpointCount}"
                        )
                        for (j in 0 until intf.endpointCount) {
                            val ep = runCatching { intf.getEndpoint(j) }.getOrNull() ?: continue
                            appendLine(
                                "    ep#$j dir=${if (ep.direction == UsbConstants.USB_DIR_OUT) "OUT" else "IN"} " +
                                    "type=${usbEndpointTypeToString(ep.type)} maxPacket=${ep.maxPacketSize}"
                            )
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && audioManager != null && preferredUsbDeviceInfo != null) {
                appendLine()
                appendLine("[USB MixerAttributes]")
                val attrs = runCatching { audioManager.getSupportedMixerAttributes(preferredUsbDeviceInfo) }.getOrDefault(emptyList())
                if (attrs.isEmpty()) {
                    appendLine("无可用MixerAttributes")
                } else {
                    attrs.forEach { attr ->
                        appendLine(
                            "behavior=${mixerBehaviorToString(attr.mixerBehavior)} " +
                                "sampleRate=${attr.format.sampleRate} " +
                                "encoding=${encodingToString(attr.format.encoding)} " +
                                "channelMask=${attr.format.channelMask}"
                        )
                    }
                }
            }
        }
    }

    private fun resolveSelectedAudioFormat(currentPlayer: ExoPlayer?): Format? {
        val tracks = currentPlayer?.currentTracks ?: return null
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (trackIndex in 0 until group.length) {
                if (group.isTrackSelected(trackIndex)) {
                    return group.getTrackFormat(trackIndex)
                }
            }
        }
        return null
    }

    private fun resolveSelectedTrackSummary(currentPlayer: ExoPlayer?): String {
        val tracks = currentPlayer?.currentTracks ?: return ""
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (trackIndex in 0 until group.length) {
                if (group.isTrackSelected(trackIndex)) {
                    val format = group.getTrackFormat(trackIndex)
                    val label = format.label?.takeIf { it.isNotBlank() } ?: "track#$trackIndex"
                    val language = format.language?.takeIf { it.isNotBlank() } ?: "und"
                    return "$label  lang=$language  roleFlags=${format.roleFlags}"
                }
            }
        }
        return ""
    }

    private fun mixerBehaviorToString(value: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return value.toString()
        }
        return when (value) {
            android.media.AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT -> "DEFAULT"
            android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT -> "BIT_PERFECT"
            else -> value.toString()
        }
    }

    private fun deviceTypeToString(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            else -> "TYPE_$type"
        }
    }

    private fun refreshBluetoothCodecInfo(audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)) {
        bluetoothCodecLabelCached = resolveActiveBluetoothCodecLabel(audioManager)
    }

    private fun resolveActiveBluetoothCodecLabel(audioManager: AudioManager?): String? {
        val bluetoothOutput = audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.isBluetoothOutputDevice() }
            ?: return null
        val fallbackLabel = bluetoothOutput.fallbackBluetoothLabel()
        if (bluetoothOutput.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
            return fallbackLabel
        }
        if (!hasBluetoothConnectPermission()) {
            return fallbackLabel
        }
        val a2dpProfile = bluetoothA2dpProfile ?: return fallbackLabel
        val connectedDevice = a2dpProfile.connectedDevices.firstOrNull { profileDevice ->
            bluetoothOutput.matchesBluetoothDevice(profileDevice)
        } ?: a2dpProfile.connectedDevices.firstOrNull()
        return connectedDevice?.let { readBluetoothCodecLabel(a2dpProfile, it) } ?: fallbackLabel
    }

    private fun readBluetoothCodecLabel(
        profile: BluetoothProfile,
        device: android.bluetooth.BluetoothDevice
    ): String? {
        val codecStatus = runCatching {
            profile.javaClass.getMethod("getCodecStatus", device.javaClass)
                .invoke(profile, device)
        }.getOrNull() ?: return null
        val codecConfig = runCatching {
            codecStatus.javaClass.getMethod("getCodecConfig").invoke(codecStatus)
        }.getOrNull() ?: return null
        val codecType = runCatching {
            codecConfig.javaClass.getMethod("getCodecType").invoke(codecConfig) as? Int
        }.getOrNull() ?: return null
        return bluetoothCodecTypeLabel(codecConfig, codecType)
    }

    private fun bluetoothCodecTypeLabel(codecConfig: Any, codecType: Int): String {
        val codecFields = codecConfig.javaClass.fields
            .filter { field ->
                field.type == Int::class.javaPrimitiveType &&
                    field.name.startsWith("SOURCE_CODEC_TYPE_")
            }
        val matchedField = codecFields.firstOrNull { field ->
            runCatching { field.getInt(null) == codecType }.getOrDefault(false)
        }
        val rawName = matchedField?.name
            ?.removePrefix("SOURCE_CODEC_TYPE_")
            ?.replace('_', ' ')
            ?.trim()
            .orEmpty()
        if (rawName.isBlank()) {
            return "Bluetooth"
        }
        return when (rawName.uppercase()) {
            "SBC" -> "SBC"
            "AAC" -> "AAC"
            "APTX" -> "aptX"
            "APTX HD" -> "aptX HD"
            "APTX ADAPTIVE" -> "aptX Adaptive"
            "LDAC" -> "LDAC"
            "LC3" -> "LC3"
            "OPUS" -> "Opus"
            else -> rawName.lowercase()
                .split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { part -> part.replaceFirstChar { ch -> ch.uppercase() } }
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun AudioDeviceInfo.isBluetoothOutputDevice(): Boolean {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> true
            else -> false
        }
    }

    private fun AudioDeviceInfo.fallbackBluetoothLabel(): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "LE Audio"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "LE Audio"
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "LE Broadcast"
            else -> "Bluetooth"
        }
    }

    private fun AudioDeviceInfo.matchesBluetoothDevice(device: android.bluetooth.BluetoothDevice): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            address.equals(device.address, ignoreCase = true)
        } else {
            productName?.toString()?.trim().orEmpty()
                .equals(device.name?.trim().orEmpty(), ignoreCase = true)
        }
    }

    private fun encodingToString(encoding: Int): String {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> "PCM_8BIT"
            AudioFormat.ENCODING_PCM_16BIT -> "PCM_16BIT"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM_24BIT_PACKED"
            AudioFormat.ENCODING_PCM_32BIT -> "PCM_32BIT"
            AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
            AudioFormat.ENCODING_MP3 -> "MP3"
            AudioFormat.ENCODING_AAC_LC -> "AAC_LC"
            AudioFormat.ENCODING_AAC_HE_V1 -> "AAC_HE_V1"
            AudioFormat.ENCODING_AAC_HE_V2 -> "AAC_HE_V2"
            AudioFormat.ENCODING_AAC_ELD -> "AAC_ELD"
            AudioFormat.ENCODING_AC3 -> "AC3"
            AudioFormat.ENCODING_E_AC3 -> "E_AC3"
            AudioFormat.ENCODING_DTS -> "DTS"
            AudioFormat.ENCODING_DTS_HD -> "DTS_HD"
            AudioFormat.ENCODING_INVALID -> "INVALID"
            else -> encoding.toString()
        }
    }

    private fun usbDeviceClassToString(deviceClass: Int): String {
        return when (deviceClass) {
            UsbConstants.USB_CLASS_AUDIO -> "AUDIO"
            UsbConstants.USB_CLASS_COMM -> "COMM"
            UsbConstants.USB_CLASS_HID -> "HID"
            UsbConstants.USB_CLASS_MASS_STORAGE -> "MASS_STORAGE"
            UsbConstants.USB_CLASS_PER_INTERFACE -> "PER_INTERFACE"
            UsbConstants.USB_CLASS_VENDOR_SPEC -> "VENDOR_SPEC"
            else -> deviceClass.toString()
        }
    }

    private fun usbEndpointTypeToString(type: Int): String {
        return when (type) {
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISO"
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
            else -> type.toString()
        }
    }

    fun release() {
        mainHandler.removeCallbacks(progressTicker)
        unregisterAudioDeviceMonitor()
        unregisterBluetoothCodecMonitor()
        unregisterUsbPermissionReceiver()
        usbHostDirectOutput?.close()
        usbHostDirectOutput = null
        player?.removeListener(playerListener)
        player?.removeAnalyticsListener(analyticsListener)
        player?.release()
        player = null
        lastAudioDecoderName = null
        lastAudioDecoderInitDurationMs = null
        playbackQueue.clear()
        sleepTimerEndElapsedMs = null
        hrtfBinauralProcessor = null
        convolutionReverbProcessor = null
        vocalIsolationProcessor = null
        stereoUtilityProcessor = null
        topBarVisualizationProcessor?.clearSnapshot()
        topBarVisualizationProcessor = null
        usbHostPassthroughProcessor = null
        releaseAudioEffects()
        AudioPlaybackService.stop(appContext)
        playbackServiceStarted = false
        initialized = false
    }

    fun writeUsbBypassPcm(
        bytes: ByteArray,
        sampleRateHz: Int = _settingsState.value.usbResolvedSampleRateHz
            ?: effectivePreferredUsbSampleRateHz()
            ?: 48_000,
        channelCount: Int = 2,
        encoding: Int = C.ENCODING_PCM_16BIT
    ): Boolean {
        val backend = usbHostDirectOutput ?: return false
        val format = AudioProcessor.AudioFormat(sampleRateHz, channelCount, encoding)
        return backend.writePcm(bytes, format)
    }

    fun isUsbBypassActive(): Boolean {
        return usbHostDirectActiveCached && usbHostDirectOutput != null
    }

    fun fillTopBarVisualizationSnapshot(
        mode: TopBarVisualizationMode,
        target: FloatArray
    ) {
        if (target.isEmpty()) {
            return
        }
        if (
            !_settingsState.value.showVisualizationEnabled ||
            !_playbackState.value.hasMedia ||
            !_playbackState.value.isPlaying
        ) {
            target.fill(0f)
            return
        }
        topBarVisualizationProcessor?.fillSnapshot(mode, target) ?: target.fill(0f)
    }

    fun fillEqVisualizationSpectrumSnapshot(target: FloatArray) {
        if (target.isEmpty()) {
            return
        }
        if (!_playbackState.value.hasMedia || !_playbackState.value.isPlaying) {
            target.fill(0f)
            return
        }
        topBarVisualizationProcessor?.fillEqSpectrumSnapshot(target) ?: target.fill(0f)
    }

    @UnstableApi
    private fun buildPlayer() {
        val settings = _settingsState.value
        val bypassAudioPipeline = bitPerfectBypassActive
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        hrtfBinauralProcessor = if (bypassAudioPipeline) null else HrtfBinauralProcessor()
        nativeSurroundProcessor = if (bypassAudioPipeline) null else NativeSurroundProcessor()
        convolutionReverbProcessor = if (bypassAudioPipeline) null else ConvolutionReverbProcessor()
        vocalIsolationProcessor = if (bypassAudioPipeline) null else VocalIsolationProcessor()
        stereoUtilityProcessor = if (bypassAudioPipeline) null else StereoUtilityProcessor()
        topBarVisualizationProcessor = TopBarVisualizationProcessor()
        hapticAudioDriver = HapticAudioDriver.createOrNull(
            context = appContext,
            delayMs = _settingsState.value.hapticAudioDelayMs
        )
        hapticAudioProcessor = hapticAudioDriver?.let { HapticAudioProcessor(it) }
        usbHostPassthroughProcessor = UsbHostPassthroughProcessor()
        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                val floatOutputEnabled = settings.hiResApiEnabled &&
                    settings.preferredBitDepth == BIT_DEPTH_32_FLOAT &&
                    enableFloatOutput
                val sinkBuilder = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(floatOutputEnabled)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                if (!bypassAudioPipeline) {
                    val processors = mutableListOf<AudioProcessor>(
                        vocalIsolationProcessor!!,
                        nativeSurroundProcessor!!,
                        hrtfBinauralProcessor!!,
                        convolutionReverbProcessor!!,
                        stereoUtilityProcessor!!,
                        topBarVisualizationProcessor!!
                    )
                    hapticAudioProcessor?.let(processors::add)
                    processors += usbHostPassthroughProcessor!!
                    sinkBuilder.setAudioProcessors(processors.toTypedArray())
                } else if (usbHostPassthroughProcessor != null && topBarVisualizationProcessor != null) {
                    val processors = mutableListOf<AudioProcessor>(
                        topBarVisualizationProcessor!!
                    )
                    hapticAudioProcessor?.let(processors::add)
                    processors += usbHostPassthroughProcessor!!
                    sinkBuilder.setAudioProcessors(processors.toTypedArray())
                }
                return sinkBuilder.build()
            }

            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                // Audio-only app: skip creating video renderers to avoid unnecessary codec probing/noise.
            }
        }

        player = ExoPlayer.Builder(appContext, renderersFactory)
            .build()
            .apply {
                setAudioAttributes(attrs, true)
                setHandleAudioBecomingNoisy(true)
                shuffleModeEnabled = prefs.getBoolean(KEY_PLAYBACK_SHUFFLE, false)
                repeatMode = prefs.getInt(KEY_PLAYBACK_REPEAT_MODE, Player.REPEAT_MODE_OFF)
                    .let { mode ->
                        when (mode) {
                            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }
                addListener(playerListener)
                addAnalyticsListener(analyticsListener)
            }
        applyTrackSelectionPreferences()
        applyHiFiMode(_settingsState.value.hiFiMode)
        applyPreferredOutputDevice()
        applyHapticAudioState()
        applyEffectState()
        syncPlaybackState()
    }

    private fun applyHiFiMode(enabled: Boolean) {
        val hiresEnabled = enabled && _settingsState.value.hiResApiEnabled
        player?.setSkipSilenceEnabled(false)
        val maybeMethod = player?.javaClass?.methods?.firstOrNull {
            it.name == "experimentalSetOffloadSchedulingEnabled" && it.parameterTypes.size == 1
        }
        runCatching {
            maybeMethod?.invoke(player, hiresEnabled)
        }
    }

    private fun applyTrackSelectionPreferences() {
        val currentPlayer = player ?: return
        val maxBitrate = _settingsState.value.preferredMaxBitrateKbps
        val paramsBuilder = currentPlayer.trackSelectionParameters.buildUpon()
        if (maxBitrate == null) {
            paramsBuilder.setMaxAudioBitrate(Int.MAX_VALUE)
        } else {
            paramsBuilder.setMaxAudioBitrate(maxBitrate * 1000)
        }
        currentPlayer.trackSelectionParameters = paramsBuilder.build()
    }

    private fun applyPreferredOutputDevice() {
        val currentPlayer = player ?: return
        val audioManager = appContext.getSystemService(AudioManager::class.java) ?: return
        val preferredDevice = _settingsState.value.preferredUsbDeviceId?.let { preferredId ->
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.id == preferredId && it.isUsbOutputDevice() }
        }
        val routeMethod = currentPlayer.javaClass.methods.firstOrNull { method ->
            (method.name == "setPreferredAudioDevice" || method.name == "experimentalSetPreferredAudioDevice") &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes.firstOrNull()?.let { AudioDeviceInfo::class.java.isAssignableFrom(it) } == true
        }
        val routed = runCatching {
            routeMethod?.invoke(currentPlayer, preferredDevice)
            true
        }.getOrDefault(false)
        usbPreferredRouteAppliedCached = if (preferredDevice == null) {
            true
        } else {
            routed && routeMethod != null
        }
        applyUsbExclusiveMode(audioManager, preferredDevice)
    }

    private fun queryUsbExclusiveSupport(audioManager: AudioManager, device: AudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }
        val supported = runCatching { audioManager.getSupportedMixerAttributes(device) }.getOrDefault(emptyList())
        return supported.any { it.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
    }

    private fun getPreferredMixerAttributesOrNull(
        audioManager: AudioManager,
        device: AudioDeviceInfo
    ): android.media.AudioMixerAttributes? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return null
        }
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return runCatching { audioManager.getPreferredMixerAttributes(attrs, device) }.getOrNull()
    }

    private fun applyUsbExclusiveMode(audioManager: AudioManager, preferredDevice: AudioDeviceInfo?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hostDirectRequested = _settingsState.value.usbExclusiveModeEnabled && preferredDevice != null
            usbExclusiveSupportedCached = false
            usbExclusiveActiveCached = false
            usbSrcBypassGuaranteedCached = false
            val hostCapability = queryUsbHostDirectCapability(preferredDevice)
            usbHostDirectSupportedCached = hostCapability.supported
            usbHostDirectDesiredCached = hostDirectRequested && usbHostDirectSupportedCached
            usbHostCapabilityReasonCached = hostCapability.reason
            usbHostDirectActiveCached = false
            applyUsbCompatibilityMode(preferredDevice)
            updateUsbHostDirectTransport(preferredDevice)
            return
        }
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val usbDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isUsbOutputDevice() }
        val enabled = _settingsState.value.usbExclusiveModeEnabled
        usbExclusiveSupportedCached = false
        usbExclusiveActiveCached = false
        usbSrcBypassGuaranteedCached = false
        usbHostDirectDesiredCached = false
        usbCompatibilityActiveCached = false
        usbResolvedSampleRateHzCached = null
        usbResolvedBitDepthCached = null
        val hostCapability = queryUsbHostDirectCapability(preferredDevice)
        usbHostDirectSupportedCached = hostCapability.supported
        usbHostCapabilityReasonCached = hostCapability.reason
        usbHostDirectActiveCached = false

        usbDevices.forEach { device ->
            runCatching { audioManager.clearPreferredMixerAttributes(attrs, device) }
        }
        if (!enabled || preferredDevice == null) {
            updateUsbHostDirectTransport(preferredDevice)
            return
        }

        usbHostDirectDesiredCached = usbHostDirectSupportedCached

        val supported = runCatching { audioManager.getSupportedMixerAttributes(preferredDevice) }.getOrDefault(emptyList())
        val bitPerfectCandidates = supported.filter {
            it.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        }
        val preferredSampleRate = effectivePreferredUsbSampleRateHz()
        val preferredBitDepth = effectivePreferredUsbBitDepth()
        usbExclusiveSupportedCached = bitPerfectCandidates.isNotEmpty()
        if (bitPerfectCandidates.isNotEmpty()) {
            val candidates = bitPerfectCandidates.sortedWith(
                compareBy<android.media.AudioMixerAttributes>(
                    { candidate ->
                        if (isMixerEncodingCompatibleWithBitDepth(candidate.format.encoding, preferredBitDepth)) 0 else 1
                    },
                    { candidate -> sampleRateDistanceScore(candidate.format.sampleRate, preferredSampleRate) },
                    { candidate -> mixerEncodingPreferenceScore(candidate.format.encoding, preferredBitDepth) }
                )
            )
            for (candidate in candidates) {
                val setSuccess = runCatching {
                    audioManager.setPreferredMixerAttributes(attrs, preferredDevice, candidate)
                    true
                }.getOrDefault(false)
                if (!setSuccess) {
                    continue
                }
                val applied = getPreferredMixerAttributesOrNull(audioManager, preferredDevice)
                usbExclusiveActiveCached = isBitPerfectMixerApplied(
                    applied = applied,
                    requested = candidate,
                    preferredBitDepth = preferredBitDepth
                )
                usbSrcBypassGuaranteedCached =
                    usbExclusiveActiveCached &&
                        applied?.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                        usbPreferredRouteAppliedCached
                if (usbExclusiveActiveCached) {
                    break
                }
            }
        }
        if (!usbExclusiveActiveCached) {
            applyUsbCompatibilityMode(preferredDevice)
        }
        updateUsbHostDirectTransport(preferredDevice)
    }

    private fun applyUsbCompatibilityMode(preferredDevice: AudioDeviceInfo?) {
        usbCompatibilityActiveCached = false
        usbResolvedSampleRateHzCached = null
        usbResolvedBitDepthCached = null
        if (!_settingsState.value.usbExclusiveModeEnabled || preferredDevice == null) {
            return
        }

        val desiredRate = effectivePreferredUsbSampleRateHz()
            ?: resolveSelectedAudioFormat(player)?.sampleRate?.takeIf { it > 0 }
        val resolvedTarget = resolveUsbTargetForDevice(
            device = preferredDevice,
            desiredSampleRateHz = desiredRate,
            desiredBitDepth = effectivePreferredUsbBitDepth()
        )
        val resolvedRate = resolvedTarget.sampleRateHz
        val resolvedBitDepth = resolvedTarget.bitDepth

        usbResolvedSampleRateHzCached = resolvedRate
        usbResolvedBitDepthCached = resolvedBitDepth
        usbCompatibilityActiveCached =
            usbPreferredRouteAppliedCached &&
            resolvedRate != null &&
            resolvedBitDepth != null
        if (usbCompatibilityActiveCached) {
            maybeShowUsbTransportToast(
                signature = "compat:${preferredDevice.id}:$resolvedRate:$resolvedBitDepth",
                message = buildString {
                    append("DAC通信模式：系统兼容输出")
                    resolvedRate?.let { append(" · ${it}Hz") }
                    resolvedBitDepth?.let { append(" · ${it}-bit") }
                }
            )
        }
    }

    private fun selectUsbSampleRate(device: AudioDeviceInfo, desiredSampleRateHz: Int?): Int? {
        val rates = device.sampleRates
            .filter { it > 0 }
            .distinct()
            .sorted()
        if (rates.isEmpty()) {
            return desiredSampleRateHz ?: effectivePreferredUsbSampleRateHz() ?: 48_000
        }
        val target = desiredSampleRateHz ?: return rates.maxOrNull()
        return when {
            rates.contains(target) -> target
            target > rates.last() -> rates.last()
            target < rates.first() -> rates.first()
            else -> rates.firstOrNull { it >= target } ?: rates.last()
        }
    }

    private fun effectivePreferredUsbSampleRateHz(): Int? {
        return _settingsState.value.preferredOutputSampleRateHz
    }

    private fun effectivePreferredUsbBitDepth(): Int {
        return _settingsState.value.preferredBitDepth
    }

    private fun selectUsbBitDepth(device: AudioDeviceInfo, preferredBitDepth: Int): Int? {
        val supportedDepths = supportedDeviceBitDepths(device)
        if (supportedDepths.isEmpty()) {
            return preferredBitDepth
        }
        return when {
            supportedDepths.contains(preferredBitDepth) -> preferredBitDepth
            preferredBitDepth > supportedDepths.last() -> supportedDepths.last()
            preferredBitDepth < supportedDepths.first() -> supportedDepths.first()
            else -> supportedDepths.firstOrNull { it >= preferredBitDepth } ?: supportedDepths.last()
        }
    }

    private fun resolveUsbTargetForDevice(
        device: AudioDeviceInfo,
        desiredSampleRateHz: Int?,
        desiredBitDepth: Int
    ): UsbResolvedTarget {
        return UsbResolvedTarget(
            sampleRateHz = selectUsbSampleRate(device, desiredSampleRateHz),
            bitDepth = selectUsbBitDepth(device, desiredBitDepth)
        )
    }

    private fun supportedDeviceBitDepths(device: AudioDeviceInfo): List<Int> {
        val encodings = device.encodings.toSet()
        return buildList {
            if (encodings.contains(AudioFormat.ENCODING_PCM_16BIT)) {
                add(BIT_DEPTH_16)
            }
            if (encodings.contains(AudioFormat.ENCODING_PCM_24BIT_PACKED)) {
                add(24)
            }
            if (
                encodings.contains(AudioFormat.ENCODING_PCM_FLOAT) ||
                encodings.contains(AudioFormat.ENCODING_PCM_32BIT)
            ) {
                add(BIT_DEPTH_32_FLOAT)
            }
        }.distinct().sorted()
    }

    private fun resolveUsbTransportTargetSampleRateHz(device: AudioDeviceInfo?): Int? {
        val desiredSampleRateHz = effectivePreferredUsbSampleRateHz() ?: return null
        val supportedRates = device
            ?.sampleRates
            ?.filter { it > 0 }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        if (supportedRates.isEmpty()) {
            return desiredSampleRateHz
        }
        return desiredSampleRateHz.coerceIn(supportedRates.first(), supportedRates.last())
    }

    private fun resolveUsbTransportTargetBitDepth(device: AudioDeviceInfo?): Int {
        val desiredBitDepth = effectivePreferredUsbBitDepth()
        val supportedDepths = device?.let(::supportedDeviceBitDepths).orEmpty()
        if (supportedDepths.isEmpty()) {
            return desiredBitDepth
        }
        return desiredBitDepth.coerceIn(supportedDepths.first(), supportedDepths.last())
    }

    private fun isMixerEncodingCompatibleWithBitDepth(encoding: Int, preferredBitDepth: Int): Boolean {
        return when (preferredBitDepth) {
            BIT_DEPTH_16 -> encoding == AudioFormat.ENCODING_PCM_16BIT
            else -> encoding == AudioFormat.ENCODING_PCM_FLOAT || encoding == AudioFormat.ENCODING_PCM_32BIT
        }
    }

    private fun queryUsbHostDirectCapability(preferredDevice: AudioDeviceInfo?): UsbHostCapability {
        val usbManager = appContext.getSystemService(UsbManager::class.java)
            ?: return UsbHostCapability(false, "UsbManager unavailable")
        val dacs = findConnectedUsbAudioDevices(usbManager)
        if (dacs.isEmpty()) {
            return UsbHostCapability(false, "No connected USB audio device")
        }
        val preferredUsb = resolveUsbDeviceForHostDirect(usbManager, preferredDevice)
            ?: (dacs.firstOrNull { usbManager.hasPermission(it) } ?: dacs.first())
        if (!usbManager.hasPermission(preferredUsb)) {
            return UsbHostCapability(
                false,
                "USB permission not granted for ${preferredUsb.productName ?: preferredUsb.deviceName}"
            )
        }
        val route = findHostDirectUsbOutRoute(preferredUsb)
            ?: return UsbHostCapability(
                false,
                if (hasIsoOnlyUsbOutRoute(preferredUsb)) {
                    "Detected ISO-only USB DAC but no usable host-direct route was selected, fallback to system USB route"
                } else {
                    "No writable USB audio BULK OUT endpoint found"
                }
            )
        return UsbHostCapability(
            true,
            "Found USB audio ${route.typeLabel.uppercase()} OUT endpoint maxPacket=${route.endpoint.maxPacketSize} alt=${route.alternateSetting}"
        )
    }

    private fun updateUsbHostDirectTransport(preferredDevice: AudioDeviceInfo?) {
        if (!usbHostDirectDesiredCached || !usbHostDirectSupportedCached) {
            usbHostDirectActiveCached = false
            usbHostPassthroughProcessor?.attachSink(null)
            usbHostDirectOutput?.close()
            usbHostDirectOutput = null
            usbHostDirectBackendLabelCached = "none"
            usbHostDirectRouteLabelCached = "none"
            player?.volume = 1f
            return
        }
        val usbManager = appContext.getSystemService(UsbManager::class.java)
        if (usbManager == null) {
            usbHostCapabilityReasonCached = "UsbManager unavailable when starting host transport"
            usbHostDirectOutput?.close()
            usbHostDirectOutput = null
            usbHostDirectBackendLabelCached = "none"
            usbHostDirectRouteLabelCached = "none"
            return
        }
        val targetDevice = resolveUsbDeviceForHostDirect(usbManager, preferredDevice)
        if (targetDevice == null) {
            usbHostCapabilityReasonCached = "No USB DAC matched for host direct transport"
            usbHostDirectOutput?.close()
            usbHostDirectOutput = null
            usbHostDirectBackendLabelCached = "none"
            usbHostDirectRouteLabelCached = "none"
            return
        }
        if (!usbManager.hasPermission(targetDevice)) {
            usbHostDirectActiveCached = false
            usbHostPassthroughProcessor?.attachSink(null)
            usbHostCapabilityReasonCached =
                "USB permission missing for ${targetDevice.productName ?: targetDevice.deviceName}"
            usbHostDirectOutput?.close()
            usbHostDirectOutput = null
            usbHostDirectBackendLabelCached = "none"
            usbHostDirectRouteLabelCached = "none"
            return
        }
        val candidateRoutes = findHostDirectUsbOutRoutes(targetDevice)
        val route = candidateRoutes.firstOrNull()
        val existing = usbHostDirectOutput
        val transportTargetSampleRateHz = resolveUsbTransportTargetSampleRateHz(preferredDevice)
        val transportTargetBitDepth = resolveUsbTransportTargetBitDepth(preferredDevice)
        if (route != null && existing != null && existing.isOperational() && existing.isForTarget(targetDevice, route)) {
            usbHostDirectActiveCached = true
            usbSrcBypassGuaranteedCached = true
            usbResolvedSampleRateHzCached = transportTargetSampleRateHz
            usbResolvedBitDepthCached = transportTargetBitDepth
            existing.setTransportConfig(
                UsbTransportConfig(
                    targetSampleRateHz = transportTargetSampleRateHz,
                    targetBitDepth = transportTargetBitDepth,
                    resampleAlgorithm = _settingsState.value.preferredUsbResampleAlgorithm
                )
            )
            usbHostDirectBackendLabelCached = existing.javaClass.simpleName
            usbHostDirectRouteLabelCached =
                "type=${route.typeLabel} alt=${route.alternateSetting} intf=${route.interfaceNumber} endpoint=0x${route.endpoint.address.toString(16)} maxPacket=${route.endpoint.maxPacketSize}"
            usbHostCapabilityReasonCached = "USB Host direct PCM transport reused (${targetDevice.deviceName})"
            usbHostPassthroughProcessor?.attachSink(existing)
            player?.volume = 1f
            return
        }
        usbHostDirectActiveCached = false
        usbHostPassthroughProcessor?.attachSink(null)
        val output = usbHostDirectOutput ?: createUsbHostDirectBackend().also { usbHostDirectOutput = it }
        var started = false
        var startedRoute: UsbOutputRoute? = null
        val failures = mutableListOf<String>()
        for (candidate in candidateRoutes) {
            val ok = output.start(targetDevice, candidate)
            if (ok) {
                usbResolvedSampleRateHzCached = transportTargetSampleRateHz
                usbResolvedBitDepthCached = transportTargetBitDepth
                output.setTransportConfig(
                    UsbTransportConfig(
                        targetSampleRateHz = transportTargetSampleRateHz,
                        targetBitDepth = transportTargetBitDepth,
                        resampleAlgorithm = _settingsState.value.preferredUsbResampleAlgorithm
                    )
                )
                started = true
                startedRoute = candidate
                break
            }
            failures += "${candidate.typeLabel}@alt${candidate.alternateSetting}:${output.lastError}"
        }
        usbHostDirectActiveCached = started
        usbHostDirectBackendLabelCached = output.javaClass.simpleName
        usbHostDirectRouteLabelCached = startedRoute?.let {
            "type=${it.typeLabel} alt=${it.alternateSetting} intf=${it.interfaceNumber} endpoint=0x${it.endpoint.address.toString(16)} maxPacket=${it.endpoint.maxPacketSize}"
        } ?: route?.let {
            "type=${it.typeLabel} alt=${it.alternateSetting} intf=${it.interfaceNumber} endpoint=0x${it.endpoint.address.toString(16)} maxPacket=${it.endpoint.maxPacketSize}"
        } ?: "unknown"
        if (started) {
            usbSrcBypassGuaranteedCached = true
            val backendName = output.javaClass.simpleName
            usbHostCapabilityReasonCached = "USB Host direct PCM transport started via $backendName (${targetDevice.deviceName})"
            usbHostPassthroughProcessor?.attachSink(output)
            player?.volume = 1f
            startedRoute?.let { routeInfo ->
                maybeShowUsbTransportToast(
                    signature = "direct:${targetDevice.deviceId}:${routeInfo.toRouteKey()}",
                    message = buildString {
                        append("DAC通信模式：")
                        append(routeInfo.typeLabel.uppercase())
                        append(" 直连")
                        append(" · alt ")
                        append(routeInfo.alternateSetting)
                    }
                )
            }
        } else {
            usbSrcBypassGuaranteedCached = false
            usbHostPassthroughProcessor?.attachSink(null)
            usbHostCapabilityReasonCached = output.lastError.ifBlank {
                failures.joinToString(prefix = "Failed host-direct candidates: ").ifBlank {
                    "Failed to start USB Host direct PCM transport"
                }
            }
            player?.volume = 1f
        }
    }

    private fun createUsbHostDirectBackend(): UsbPcmOutputBackend {
        return if (NativeUsbBridge.isAvailable) {
            NativeUsbDirectOutput(appContext)
        } else {
            UsbHostDirectOutput(appContext)
        }
    }

    private fun refreshUsbDirectTransportConfigNow() {
        if (!initialized || !usbHostDirectDesiredCached) {
            return
        }
        val audioManager = appContext.getSystemService(AudioManager::class.java) ?: return
        val preferredDevice = _settingsState.value.preferredUsbDeviceId?.let { preferredId ->
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.id == preferredId && it.isUsbOutputDevice() }
        }
        updateUsbHostDirectTransport(preferredDevice)
    }

    private fun maybeShowUsbTransportToast(signature: String, message: String, category: String = "usb_transport") {
        val now = SystemClock.elapsedRealtime()
        if (signature == lastUsbTransportToastSignature && now - lastUsbTransportToastElapsedMs < 4_000L) {
            return
        }
        lastUsbTransportToastSignature = signature
        lastUsbTransportToastElapsedMs = now
        mainHandler.post {
            if (activeToastCategory == category) {
                activeToast?.cancel()
                activeToast = null
            }
            activeToastCategory = category
        }
    }

    private fun ensureUsbHostDirectReadyForPlayback(force: Boolean = false) {
        if (!_settingsState.value.usbExclusiveModeEnabled) {
            return
        }
        val currentPlayer = player ?: return
        if (currentPlayer.currentMediaItem == null) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastUsbHostDirectRecoveryAttemptElapsedMs < 1_500L) {
            return
        }
        val backendHealthy = usbHostDirectOutput?.isOperational() == true
        val needsRecovery = usbHostDirectDesiredCached && (!usbHostDirectActiveCached || !backendHealthy)
        if (!needsRecovery && !force) {
            return
        }
        lastUsbHostDirectRecoveryAttemptElapsedMs = now
        applyPreferredOutputDevice()
        refreshOutputInfo()
    }

    private fun resolveUsbDeviceForHostDirect(
        usbManager: UsbManager,
        preferredDevice: AudioDeviceInfo?
    ): UsbDevice? {
        val dacs = findConnectedUsbAudioDevices(usbManager)
        if (dacs.isEmpty()) {
            return null
        }
        val preferredName = preferredDevice?.productName?.toString()?.trim().orEmpty()
        val normalizedPreferred = preferredName.lowercase()
        val tokens = normalizedPreferred.split(Regex("[^a-z0-9]+"))
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()

        val scored = dacs.map { usb ->
            val name = usb.productName?.toString().orEmpty()
            val manufacturer = usb.manufacturerName?.toString().orEmpty()
            val joined = "${name.lowercase()} ${manufacturer.lowercase()} ${usb.deviceName.lowercase()}"
            var score = 0
            if (normalizedPreferred.isNotBlank() && joined.contains(normalizedPreferred)) {
                score += 100
            }
            score += tokens.count { token -> joined.contains(token) } * 10
            if (usbManager.hasPermission(usb)) {
                score += 5
            }
            usb to score
        }.sortedByDescending { it.second }

        return scored.firstOrNull()?.first
    }

    private fun sampleRateDistanceScore(candidateRate: Int, preferredRate: Int?): Int {
        if (preferredRate == null || candidateRate <= 0) {
            return 0
        }
        return kotlin.math.abs(candidateRate - preferredRate)
    }

    private fun mixerEncodingPreferenceScore(encoding: Int, preferredBitDepth: Int): Int {
        return when (preferredBitDepth) {
            BIT_DEPTH_16 -> when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> 0
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 1
                AudioFormat.ENCODING_PCM_32BIT -> 2
                AudioFormat.ENCODING_PCM_FLOAT -> 3
                else -> 4
            }
            else -> when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> 0
                AudioFormat.ENCODING_PCM_32BIT -> 1
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 2
                AudioFormat.ENCODING_PCM_16BIT -> 3
                else -> 4
            }
        }
    }

    private fun isBitPerfectMixerApplied(
        applied: android.media.AudioMixerAttributes?,
        requested: android.media.AudioMixerAttributes,
        preferredBitDepth: Int
    ): Boolean {
        if (applied == null) {
            return false
        }
        if (applied.mixerBehavior != android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) {
            return false
        }
        val appliedEncodingMatchesPreference = isMixerEncodingCompatibleWithBitDepth(
            applied.format.encoding,
            preferredBitDepth
        )
        return (applied.format.sampleRate == requested.format.sampleRate &&
            applied.format.encoding == requested.format.encoding) ||
            appliedEncodingMatchesPreference
    }

    private fun ensureBitPerfectBypassState(active: Boolean) {
        if (bitPerfectBypassActive == active) {
            return
        }
        bitPerfectBypassActive = active
        rebuildPlayerPreservingState()
    }

    @UnstableApi
    private fun rebuildPlayerPreservingState() {
        if (!initialized) {
            return
        }
        val snapshot = capturePlayerSnapshot()
        usbHostDirectOutput?.close()
        usbHostDirectOutput = null
        usbHostDirectBackendLabelCached = "none"
        usbHostDirectRouteLabelCached = "none"
        usbHostPassthroughProcessor?.attachSink(null)
        player?.removeListener(playerListener)
        player?.release()
        player = null
        releaseAudioEffects()
        buildPlayer()
        restorePlayerSnapshot(snapshot)
    }

    private fun capturePlayerSnapshot(): PlayerSnapshot {
        val currentPlayer = player
        return if (currentPlayer == null) {
            PlayerSnapshot()
        } else {
            PlayerSnapshot(
                mediaItems = (0 until currentPlayer.mediaItemCount).map { index -> currentPlayer.getMediaItemAt(index) },
                index = currentPlayer.currentMediaItemIndex.coerceAtLeast(0),
                positionMs = currentPlayer.currentPosition.coerceAtLeast(0L),
                playWhenReady = currentPlayer.playWhenReady
            )
        }
    }

    private fun restorePlayerSnapshot(snapshot: PlayerSnapshot) {
        val currentPlayer = player ?: return
        if (snapshot.mediaItems.isEmpty()) {
            syncPlaybackState()
            return
        }
        val index = snapshot.index.coerceIn(0, snapshot.mediaItems.lastIndex)
        currentPlayer.setMediaItems(snapshot.mediaItems, index, snapshot.positionMs.coerceAtLeast(0L))
        currentPlayer.prepare()
        if (snapshot.playWhenReady) {
            AudioPlaybackService.start(appContext)
            ensureUsbHostDirectReadyForPlayback(force = true)
            currentPlayer.play()
        }
    }

    private fun normalizeBitDepth(value: Int): Int {
        return if (value == BIT_DEPTH_16) BIT_DEPTH_16 else BIT_DEPTH_32_FLOAT
    }

    private fun normalizeOutputSampleRate(value: Int?): Int? {
        if (value == null) {
            return null
        }
        return value.takeIf { candidate -> OUTPUT_SAMPLE_RATE_OPTIONS_HZ.contains(candidate) }
    }

    private fun AudioDeviceInfo.isUsbOutputDevice(): Boolean {
        val knownUsbType = when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_DOCK -> true
            else -> false
        }
        val addressLooksUsb = runCatching { address }
            .getOrNull()
            ?.startsWith("usb:", ignoreCase = true)
            ?: false
        return (knownUsbType || addressLooksUsb) && isSink
    }

    private fun buildUsbDeviceLabel(device: AudioDeviceInfo): String {
        val product = device.productName?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "USB DAC"
        return "$product (#${device.id})"
    }

    private fun attachAudioEffects(audioSessionId: Int) {
        if (bitPerfectBypassActive) {
            releasePlatformAudioEffects()
            return
        }
        releasePlatformAudioEffects()
        currentAudioSessionId = audioSessionId
        val state = _effectsState.value
        val needsPlatformEffects = requiresPlatformAudioEffects(state)

        if (needsPlatformEffects) {
            equalizer = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
            bassBoost = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
            virtualizer = runCatching { Virtualizer(0, audioSessionId) }.getOrNull()
            loudnessEnhancer = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
            environmentalReverb = runCatching { EnvironmentalReverb(0, audioSessionId) }.getOrNull()
        }
        if (hapticAudioDriver == null) {
            hapticAudioDriver = HapticAudioDriver.createOrNull(
                context = appContext,
                delayMs = _settingsState.value.hapticAudioDelayMs
            )
        }
        if (hapticAudioProcessor == null) {
            hapticAudioProcessor = hapticAudioDriver?.let { HapticAudioProcessor(it) }
        }

        val eq = equalizer
        if (needsPlatformEffects && eq != null) {
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

        applyHapticAudioState()
        applyEffectState()
        syncPlaybackState()
    }

    private fun requiresPlatformAudioEffects(state: EffectsUiState): Boolean {
        return state.enabled && (
            state.eqEnabled ||
                state.spatialEnabled ||
                state.surroundMode != SURROUND_MODE_STEREO ||
                state.convolutionEnabled ||
                state.limiterEnabled ||
                state.panPercent != 0 ||
                state.panInvertEnabled ||
                state.monoEnabled ||
                state.vocalRemovalEnabled ||
                state.vocalKeyShiftSemitones != 0 ||
                state.phaseInvertEnabled ||
                state.rightChannelPhaseInvertEnabled ||
                state.crossfeedPercent > 0
            )
    }

    private fun releasePlatformAudioEffects() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudnessEnhancer?.release() }
        runCatching { environmentalReverb?.release() }
        runCatching { hapticGenerator?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        environmentalReverb = null
        hapticGenerator = null
        currentAudioSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun releaseAudioEffects() {
        releasePlatformAudioEffects()
        runCatching { hapticAudioProcessor?.setEnabled(false) }
        runCatching { hapticAudioDriver?.release() }
        hapticAudioProcessor = null
        hapticAudioDriver = null
    }

    private fun applyHapticAudioState() {
        val enabled = _settingsState.value.hapticAudioEnabled

        // 1) System HapticGenerator path: kicks in when the source already carries a haptic
        //    channel (e.g. ANDROID_HAPTIC=1 OGG on Xiaomi/HyperOS); harmless otherwise.
        applySystemHapticGenerator(enabled)

        // 2) Software fallback: derive a low-frequency envelope from PCM and drive the LRA
        //    directly. Active alongside (1) — on devices that ignore HapticGenerator this is
        //    the only path that produces vibration, on devices that honour it the user gets
        //    consistent feedback for every track instead of only haptic-tagged ones.
        applySoftwareHapticAudio(enabled)
        updateAudioPipelineDetailsSnapshot()
    }

    private fun applySystemHapticGenerator(enabled: Boolean) {
        if (!enabled || bitPerfectBypassActive) {
            runCatching { hapticGenerator?.release() }
            hapticGenerator = null
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        val sessionId = player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) {
            return
        }
        val existing = hapticGenerator
        if (existing != null) {
            runCatching { existing.setEnabled(true) }
            return
        }
        hapticGenerator = runCatching {
            HapticGenerator.create(sessionId).apply {
                setEnabled(true)
            }
        }.getOrNull()
    }

    private fun applySoftwareHapticAudio(enabled: Boolean) {
        // Disable the software path when bit-perfect bypass is active — that mode exists
        // specifically to avoid touching the PCM stream, and tapping samples is fine but
        // generating motor IPC concurrently with USB direct output causes audible jitter on
        // some devices.
        val shouldRun = enabled && !bitPerfectBypassActive
        if (shouldRun && hapticAudioDriver == null) {
            hapticAudioDriver = HapticAudioDriver.createOrNull(
                context = appContext,
                delayMs = _settingsState.value.hapticAudioDelayMs
            )
        }
        if (shouldRun && hapticAudioProcessor == null) {
            hapticAudioProcessor = hapticAudioDriver?.let { HapticAudioProcessor(it) }
        }
        hapticAudioProcessor?.setEnabled(shouldRun)
        hapticAudioDriver?.setEnabled(shouldRun)
    }

    private fun applyEffectState() {
        val state = _effectsState.value
        applyPlaybackSpeed(state)
        if (bitPerfectBypassActive) {
            player?.volume = 1f
            runCatching { equalizer?.enabled = false }
            runCatching { bassBoost?.enabled = false }
            runCatching { virtualizer?.enabled = false }
            runCatching { loudnessEnhancer?.enabled = false }
            runCatching { environmentalReverb?.enabled = false }
            updateVocalIsolationProcessor(state.copy(enabled = false))
            updateSurroundProcessor(state.copy(enabled = false, surroundMode = SURROUND_MODE_STEREO))
            updateStereoUtilityProcessor(state.copy(enabled = false))
            return
        }
        if (!requiresPlatformAudioEffects(state)) {
            player?.volume = 1f
            if (
                equalizer != null ||
                bassBoost != null ||
                virtualizer != null ||
                loudnessEnhancer != null ||
                environmentalReverb != null
            ) {
                releasePlatformAudioEffects()
                val sessionId = player?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
                if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                    currentAudioSessionId = sessionId
                }
                applyHapticAudioState()
            }
            updateVocalIsolationProcessor(state.copy(enabled = false))
            updateSurroundProcessor(state.copy(enabled = false, surroundMode = SURROUND_MODE_STEREO))
            updateHrtfProcessor(state.copy(enabled = false, spatialEnabled = false))
            updateConvolutionProcessor(state.copy(enabled = false))
            updateStereoUtilityProcessor(state.copy(enabled = false))
            val derived = withSpatialDerived(state)
            if (derived != state) {
                _effectsState.value = derived
            }
            return
        }
        val model = buildSpatialModel(state)
        val finalCurve = composeFinalEqCurveMb(state, model)

        updateVocalIsolationProcessor(state)
        updateSurroundProcessor(state)
        updateHrtfProcessor(state)
        updateConvolutionProcessor(state)
        updateStereoUtilityProcessor(state)

        player?.volume = if (state.enabled && state.spatialEnabled) {
            model.gainLinear.coerceIn(0.08f, 1f)
        } else {
            1f
        }

        runCatching {
            equalizer?.enabled = state.enabled && state.eqEnabled
            val eq = equalizer ?: return@runCatching
            val hardwareBandCount = eq.numberOfBands.toInt()
            for (hardwareBand in 0 until hardwareBandCount) {
                val centerHz = eq.getCenterFreq(hardwareBand.toShort()).toFloat() / 1000f
                val value = interpolateEqLevelMb(
                    targetHz = centerHz,
                    anchorFreqHz = state.eqBandFrequenciesHz,
                    levelsMb = finalCurve,
                    qTimes100 = state.eqBandQTimes100
                )
                    .coerceIn(state.eqBandLevelMinMb, state.eqBandLevelMaxMb)
                eq.setBandLevel(hardwareBand.toShort(), value.toShort())
            }
        }

        runCatching {
            bassBoost?.enabled = false
        }

        runCatching {
            virtualizer?.enabled = false
        }

        runCatching {
            loudnessEnhancer?.enabled = false
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
        val sanitized = enforceHrtfSourceBounds(state)
        val rightX = sanitized.spatialRightX
        val rightY = sanitized.spatialRightY
        val rightZ = sanitized.spatialRightZ
        val leftSourcePose = buildHrtfSourcePose(
            xCm = sanitized.spatialLeftX,
            yCm = sanitized.spatialLeftY,
            zCm = sanitized.spatialLeftZ,
            headRadiusMm = sanitized.hrtfHeadRadiusMm
        )
        val rightSourcePose = buildHrtfSourcePose(
            xCm = rightX,
            yCm = rightY,
            zCm = rightZ,
            headRadiusMm = sanitized.hrtfHeadRadiusMm
        )
        hrtfBinauralProcessor?.updateConfig(
            HrtfRenderConfig(
                enabled = sanitized.enabled && sanitized.hrtfEnabled,
                spatialEnabled = sanitized.spatialEnabled,
                channelSeparated = sanitized.channelSeparated,
                leftXNorm = leftSourcePose.xNorm,
                leftYNorm = leftSourcePose.yNorm,
                leftZNorm = leftSourcePose.zNorm,
                rightXNorm = rightSourcePose.xNorm,
                rightYNorm = rightSourcePose.yNorm,
                rightZNorm = rightSourcePose.zNorm,
                leftDistanceMeters = leftSourcePose.distanceMeters,
                rightDistanceMeters = rightSourcePose.distanceMeters,
                roomDampingNorm = sanitized.roomDamping / 100f,
                wetMixNorm = sanitized.wetMix / 100f,
                headRadiusMeters = sanitized.hrtfHeadRadiusMm / 1000f,
                blend = sanitized.hrtfBlendPercent / 100f,
                crossfeed = sanitized.hrtfCrossfeedPercent / 100f,
                externalization = sanitized.hrtfExternalizationPercent / 100f,
                useHrtfDatabase = sanitized.hrtfUseDatabase,
                surroundMode = SURROUND_MODE_STEREO,
                frontLeftGain = 1f,
                frontRightGain = 1f,
                centerGain = 1f,
                lfeGain = 1f,
                surroundLeftGain = 1f,
                surroundRightGain = 1f,
                rearLeftGain = 1f,
                rearRightGain = 1f
            )
        )
    }

    private fun updateSurroundProcessor(state: EffectsUiState) {
        val surroundGains = activeSurroundChannelGains(state)
        nativeSurroundProcessor?.updateConfig(
            enabled = state.enabled && state.hrtfEnabled && state.surroundMode != SURROUND_MODE_STEREO,
            surroundMode = state.surroundMode,
            frontLeftGain = surroundGains.frontLeft,
            frontRightGain = surroundGains.frontRight,
            centerGain = surroundGains.center,
            lfeGain = surroundGains.lfe,
            surroundLeftGain = surroundGains.sideLeft,
            surroundRightGain = surroundGains.sideRight,
            rearLeftGain = surroundGains.rearLeft,
            rearRightGain = surroundGains.rearRight
        )
    }

    private fun activeSurroundChannelGains(state: EffectsUiState): SurroundChannelGains {
        fun toGain(valuePercent: Int): Float {
            return valuePercent.coerceIn(SURROUND_GAIN_MIN_PERCENT, SURROUND_GAIN_MAX_PERCENT) / 100f
        }
        return when (state.surroundMode) {
            SURROUND_MODE_5_1 -> SurroundChannelGains(
                frontLeft = toGain(state.surround51FlPercent),
                frontRight = toGain(state.surround51FrPercent),
                center = toGain(state.surround51CPercent),
                lfe = toGain(state.surround51LfePercent),
                sideLeft = toGain(state.surround51SlPercent),
                sideRight = toGain(state.surround51SrPercent),
                rearLeft = 1f,
                rearRight = 1f
            )

            SURROUND_MODE_7_1 -> SurroundChannelGains(
                frontLeft = toGain(state.surround71FlPercent),
                frontRight = toGain(state.surround71FrPercent),
                center = toGain(state.surround71CPercent),
                lfe = toGain(state.surround71LfePercent),
                sideLeft = toGain(state.surround71SlPercent),
                sideRight = toGain(state.surround71SrPercent),
                rearLeft = toGain(state.surround71RlPercent),
                rearRight = toGain(state.surround71RrPercent)
            )

            else -> SurroundChannelGains(
                frontLeft = toGain(state.surroundStereoFlPercent),
                frontRight = toGain(state.surroundStereoFrPercent),
                center = 1f,
                lfe = 1f,
                sideLeft = 1f,
                sideRight = 1f,
                rearLeft = 1f,
                rearRight = 1f
            )
        }
    }

    private fun updateConvolutionProcessor(state: EffectsUiState) {
        convolutionReverbProcessor?.updateConfig(
            enabled = state.enabled && state.convolutionEnabled && !state.convolutionIrUri.isNullOrBlank(),
            wetMix = state.convolutionWetPercent / 100f
        )
    }

    private fun updateStereoUtilityProcessor(state: EffectsUiState) {
        stereoUtilityProcessor?.updateConfig(
            limiterEnabled = state.enabled && state.limiterEnabled,
            panBalance = (state.panPercent / 100f).coerceIn(-1f, 1f),
            panInvertEnabled = state.enabled && state.panInvertEnabled,
            monoEnabled = state.enabled && state.monoEnabled,
            vocalRemovalEnabled = state.enabled && state.vocalRemovalEnabled,
            phaseInvertEnabled = state.enabled && state.phaseInvertEnabled,
            rightChannelPhaseInvertEnabled = state.enabled && state.rightChannelPhaseInvertEnabled,
            crossfeedMix = state.crossfeedPercent / 100f
        )
    }

    private fun updateVocalIsolationProcessor(state: EffectsUiState) {
        vocalIsolationProcessor?.updateConfig(
            vocalRemovalEnabled = state.enabled && state.vocalRemovalEnabled,
            vocalKeyShiftSemitones = state.vocalKeyShiftSemitones,
            vocalBandLowCutHz = state.vocalBandLowCutHz,
            vocalBandHighCutHz = state.vocalBandHighCutHz
        )
    }

    private fun applyPlaybackSpeed(state: EffectsUiState) {
        val baseSpeed = (state.playbackSpeedPercent / 100f).coerceIn(0.5f, 2.0f)
        val basePitch = resolvePitchForSpeed(state, baseSpeed)
        val speed = transientPlaybackSpeedOverride ?: baseSpeed
        val pitch = transientPlaybackPitchOverride ?: basePitch
        val currentPlayer = player ?: return
        val currentParams = currentPlayer.playbackParameters
        if (
            kotlin.math.abs(currentParams.speed - speed) < 0.009f &&
            kotlin.math.abs(currentParams.pitch - pitch) < 0.009f
        ) {
            return
        }
        currentPlayer.setPlaybackParameters(PlaybackParameters(speed, pitch))
    }

    private fun resolvePitchForSpeed(state: EffectsUiState, speed: Float): Float {
        return if (state.playbackSpeedPitchCompensationEnabled) 1f else speed
    }

    private fun withSpatialDerived(state: EffectsUiState): EffectsUiState {
        val normalized = enforceHrtfSourceBounds(state)
        val model = buildSpatialModel(normalized)
        return normalized.copy(
            derivedDistanceMeters = model.distanceMeters,
            derivedGainDb = model.gainDb
        )
    }

    private fun enforceHrtfSourceBounds(state: EffectsUiState): EffectsUiState {
        val headRadiusCm = (state.hrtfHeadRadiusMm / 10f).coerceIn(7f, 11f)
        val minimumDistanceCm = (headRadiusCm + HRTF_SOURCE_MARGIN_CM).coerceAtMost(SPATIAL_RADIUS_MAX_CM.toFloat())

        fun clampSource(x: Int, y: Int, z: Int, radiusCm: Int): SpatialVector {
            val radius = radiusCm.coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM).toFloat()
            var sourceX = x.toFloat().coerceIn(-radius, radius)
            var sourceY = y.toFloat().coerceIn(SPATIAL_COORD_MIN_CM.toFloat(), SPATIAL_COORD_MAX_CM.toFloat())
            var sourceZ = z.toFloat().coerceIn(-radius, radius)
            val minDistance = minimumDistanceCm.coerceAtMost(radius)
            val distance = sqrt(sourceX * sourceX + sourceY * sourceY + sourceZ * sourceZ)
            if (distance < minDistance) {
                if (distance < 0.0001f) {
                    sourceX = 0f
                    sourceY = 0f
                    sourceZ = minDistance
                } else {
                    val scale = minDistance / distance
                    sourceX *= scale
                    sourceY *= scale
                    sourceZ *= scale
                }
            }
            return SpatialVector(
                x = sourceX.roundToInt().coerceIn(-radiusCm, radiusCm),
                y = sourceY.roundToInt().coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM),
                z = sourceZ.roundToInt().coerceIn(-radiusCm, radiusCm)
            )
        }

        return if (state.channelSeparated) {
            val left = clampSource(
                x = state.spatialLeftX,
                y = state.spatialLeftY,
                z = state.spatialLeftZ,
                radiusCm = state.spatialLeftRadiusPercent
            )
            val right = clampSource(
                x = state.spatialRightX,
                y = state.spatialRightY,
                z = state.spatialRightZ,
                radiusCm = state.spatialRightRadiusPercent
            )
            state.copy(
                spatialLeftX = left.x,
                spatialLeftY = left.y,
                spatialLeftZ = left.z,
                spatialRightX = right.x,
                spatialRightY = right.y,
                spatialRightZ = right.z
            )
        } else {
            val linkedRadius = min(state.spatialLeftRadiusPercent, state.spatialRightRadiusPercent)
                .coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM)
            val center = effectiveSpatialCenter(state)
            val linkedPair = buildLinkedChannelPair(
                centerX = center.x,
                centerZ = center.z,
                spacingCm = linkedSpacingFromState(state),
                radiusCm = linkedRadius
            )
            val left = clampSource(
                x = linkedPair.leftX,
                y = center.y,
                z = linkedPair.leftZ,
                radiusCm = linkedRadius
            )
            val right = clampSource(
                x = linkedPair.rightX,
                y = center.y,
                z = linkedPair.rightZ,
                radiusCm = linkedRadius
            )
            val alignedY = ((left.y + right.y) / 2).coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM)
            state.copy(
                channelSeparated = false,
                spatialLeftRadiusPercent = linkedRadius,
                spatialRightRadiusPercent = linkedRadius,
                linkedChannelSpacingCm = abs(right.x - left.x)
                    .coerceIn(LINKED_CHANNEL_SPACING_MIN_CM, LINKED_CHANNEL_SPACING_MAX_CM),
                spatialLeftX = left.x,
                spatialLeftY = alignedY,
                spatialLeftZ = left.z,
                spatialRightX = right.x,
                spatialRightY = alignedY,
                spatialRightZ = right.z
            )
        }
    }

    private fun buildHrtfSourcePose(
        xCm: Int,
        yCm: Int,
        zCm: Int,
        headRadiusMm: Int
    ): HrtfSourcePose {
        val headRadiusMeters = (headRadiusMm / 1000f).coerceIn(0.07f, 0.11f)
        val minimumDistanceMeters = (headRadiusMeters + HRTF_SOURCE_MARGIN_CM / 100f).coerceIn(0.07f, 0.16f)
        var x = xCm / 100f
        var y = yCm / 100f
        var z = zCm / 100f
        var distance = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        if (distance < minimumDistanceMeters) {
            if (distance < 0.0001f) {
                x = 0f
                y = 0f
                z = minimumDistanceMeters
            } else {
                val scale = minimumDistanceMeters / distance
                x *= scale
                y *= scale
                z *= scale
            }
            distance = minimumDistanceMeters
        }

        val inverseDistance = 1f / distance.coerceAtLeast(0.0001f)
        return HrtfSourcePose(
            xNorm = (x * inverseDistance).coerceIn(-1f, 1f),
            yNorm = (y * inverseDistance).coerceIn(-1f, 1f),
            zNorm = (z * inverseDistance).coerceIn(-1f, 1f),
            distanceMeters = distance.coerceIn(minimumDistanceMeters, HRTF_MAX_SOURCE_DISTANCE_METERS)
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

        val minimumDistanceMeters = (state.hrtfHeadRadiusMm / 1000f).coerceIn(0.07f, 0.11f)
        val distanceMeters = sqrt((x * x + y * y + z * z).toDouble()).toFloat().coerceIn(minimumDistanceMeters, 4f)
        val inverseSquare = 1f / (1f + (distanceMeters / 0.95f).pow(2))
        val compression = state.perceptualCompression / 100f
        val compressedGain = inverseSquare.pow(1f - 0.75f * compression)
        val gainLinear = (0.22f + compressedGain * 0.78f).coerceIn(0.06f, 1f)
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
        return SpatialVector(
            x = ((state.spatialLeftX + state.spatialRightX) / 2).coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM),
            y = ((state.spatialLeftY + state.spatialRightY) / 2).coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM),
            z = ((state.spatialLeftZ + state.spatialRightZ) / 2).coerceIn(SPATIAL_COORD_MIN_CM, SPATIAL_COORD_MAX_CM)
        )
    }

    private fun linkedSpacingFromState(state: EffectsUiState): Int {
        val fromState = state.linkedChannelSpacingCm
            .coerceIn(LINKED_CHANNEL_SPACING_MIN_CM, LINKED_CHANNEL_SPACING_MAX_CM)
        if (fromState > 0) {
            return fromState
        }
        val fromPositions = abs(state.spatialRightX - state.spatialLeftX)
        return fromPositions.coerceIn(LINKED_CHANNEL_SPACING_MIN_CM, LINKED_CHANNEL_SPACING_MAX_CM)
    }

    private fun buildLinkedChannelPair(
        centerX: Int,
        centerZ: Int,
        spacingCm: Int,
        radiusCm: Int
    ): LinkedChannelPair {
        val radius = radiusCm.coerceIn(SPATIAL_RADIUS_MIN_CM, SPATIAL_RADIUS_MAX_CM)
        val clampedSpacing = spacingCm
            .coerceIn(LINKED_CHANNEL_SPACING_MIN_CM, LINKED_CHANNEL_SPACING_MAX_CM)
            .coerceAtMost(radius * 2)
        val half = clampedSpacing / 2f
        val clampedCenterX = centerX.toFloat().coerceIn(-radius + half, radius - half)
        val clampedCenterZ = centerZ.toFloat().coerceIn(-radius.toFloat(), radius.toFloat())
        val leftX = (clampedCenterX - half).roundToInt().coerceIn(-radius, radius)
        val rightX = (clampedCenterX + half).roundToInt().coerceIn(-radius, radius)
        val z = clampedCenterZ.roundToInt().coerceIn(-radius, radius)
        return LinkedChannelPair(
            leftX = leftX,
            leftZ = z,
            rightX = rightX,
            rightZ = z,
            spacingCm = abs(rightX - leftX).coerceIn(LINKED_CHANNEL_SPACING_MIN_CM, LINKED_CHANNEL_SPACING_MAX_CM)
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
            .putInt(KEY_LINKED_CHANNEL_SPACING_CM, state.linkedChannelSpacingCm)
            .putInt(KEY_SPATIAL_X, center.x)
            .putInt(KEY_SPATIAL_Y, center.y)
            .putInt(KEY_SPATIAL_Z, center.z)
            .apply()
    }

    private fun persistSurroundGains(state: EffectsUiState) {
        prefs.edit()
            .putInt(KEY_SURROUND_GAIN_STEREO_FL, state.surroundStereoFlPercent)
            .putInt(KEY_SURROUND_GAIN_STEREO_FR, state.surroundStereoFrPercent)
            .putInt(KEY_SURROUND_GAIN_51_FL, state.surround51FlPercent)
            .putInt(KEY_SURROUND_GAIN_51_FR, state.surround51FrPercent)
            .putInt(KEY_SURROUND_GAIN_51_C, state.surround51CPercent)
            .putInt(KEY_SURROUND_GAIN_51_LFE, state.surround51LfePercent)
            .putInt(KEY_SURROUND_GAIN_51_SL, state.surround51SlPercent)
            .putInt(KEY_SURROUND_GAIN_51_SR, state.surround51SrPercent)
            .putInt(KEY_SURROUND_GAIN_71_FL, state.surround71FlPercent)
            .putInt(KEY_SURROUND_GAIN_71_FR, state.surround71FrPercent)
            .putInt(KEY_SURROUND_GAIN_71_C, state.surround71CPercent)
            .putInt(KEY_SURROUND_GAIN_71_LFE, state.surround71LfePercent)
            .putInt(KEY_SURROUND_GAIN_71_SL, state.surround71SlPercent)
            .putInt(KEY_SURROUND_GAIN_71_SR, state.surround71SrPercent)
            .putInt(KEY_SURROUND_GAIN_71_RL, state.surround71RlPercent)
            .putInt(KEY_SURROUND_GAIN_71_RR, state.surround71RrPercent)
            .apply()
    }

    private data class SpatialVector(
        val x: Int,
        val y: Int,
        val z: Int
    )

    private data class LinkedChannelPair(
        val leftX: Int,
        val leftZ: Int,
        val rightX: Int,
        val rightZ: Int,
        val spacingCm: Int
    )

    private data class HrtfSourcePose(
        val xNorm: Float,
        val yNorm: Float,
        val zNorm: Float,
        val distanceMeters: Float
    )

    private data class SurroundChannelGains(
        val frontLeft: Float,
        val frontRight: Float,
        val center: Float,
        val lfe: Float,
        val sideLeft: Float,
        val sideRight: Float,
        val rearLeft: Float,
        val rearRight: Float
    )

    private data class ImpulseResponseAsset(
        val name: String,
        val samples: FloatArray
    )

    private data class PlayerSnapshot(
        val mediaItems: List<MediaItem> = emptyList(),
        val index: Int = 0,
        val positionMs: Long = 0L,
        val playWhenReady: Boolean = false
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
                        convolutionIrName = "IRS 鍔犺浇澶辫触"
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
            throw IllegalArgumentException("浠呮敮鎸?.irs 鏂囦欢")
        }

        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(16 * 1024)
            val out = ByteArrayOutputStream()
            var read = input.read(buffer)
            var total = 0
            while (read > 0) {
                total += read
                if (total > 8 * 1024 * 1024) {
                    throw IllegalArgumentException("IRS 鏂囦欢杩囧ぇ")
                }
                out.write(buffer, 0, read)
                read = input.read(buffer)
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("鏃犳硶璇诲彇 IRS 鏂囦欢")

        val rawImpulse = parseWavImpulse(bytes) ?: parseRawPcmImpulse(bytes)
        val prepared = prepareImpulse(rawImpulse)
        if (prepared.isEmpty()) {
            throw IllegalArgumentException("IRS 鏁版嵁涓虹┖")
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
        return state.eqBandLevelsMb.mapIndexed { index, baseLevel ->
            val freq = state.eqBandFrequenciesHz.getOrElse(index) { 1000 }
            if (!state.spatialEnabled) {
                return@mapIndexed baseLevel.coerceIn(state.eqBandLevelMinMb, state.eqBandLevelMaxMb)
            }
            val freqFloat = freq.toFloat().coerceAtLeast(20f)
            val lowWeight = 1f / (1f + (freqFloat / 380f).pow(1.6f))
            val highWeight = 1f / (1f + (2200f / freqFloat).pow(1.45f))
            val spatialMb = model.lowTiltMb * lowWeight + model.highTiltMb * highWeight
            (baseLevel + spatialMb).toInt().coerceIn(state.eqBandLevelMinMb, state.eqBandLevelMaxMb)
        }
    }

    private fun interpolateEqLevelMb(
        targetHz: Float,
        anchorFreqHz: List<Int>,
        levelsMb: List<Int>,
        qTimes100: List<Int>
    ): Int {
        if (anchorFreqHz.isEmpty() || levelsMb.isEmpty()) {
            return 0
        }
        val size = minOf(anchorFreqHz.size, levelsMb.size)
        if (size == 1) {
            return levelsMb.first()
        }
        val clampedHz = targetHz.coerceIn(20f, 20_000f)
        val ln2 = ln(2.0).toFloat()
        var weightedSum = 0f
        var weightTotal = 0f
        var nearestValue = levelsMb.first()
        var nearestDistance = Float.MAX_VALUE
        for (index in 0 until size) {
            val centerHz = anchorFreqHz[index].toFloat().coerceIn(20f, 20_000f)
            val level = levelsMb[index]
            val q = (qTimes100.getOrElse(index) { 100 } / 100f).coerceIn(0.2f, 12f)
            val sigmaOct = (1.1f / q).coerceIn(0.08f, 1.8f)
            val distanceOct = abs(ln(clampedHz / centerHz) / ln2)
            if (distanceOct < nearestDistance) {
                nearestDistance = distanceOct
                nearestValue = level
            }
            val weight = kotlin.math.exp(-0.5f * (distanceOct / sigmaOct).pow(2))
            weightedSum += level * weight
            weightTotal += weight
        }
        if (weightTotal <= 0.0001f) {
            return nearestValue
        }
        return (weightedSum / weightTotal).roundToInt()
    }

    private fun builtInEqPresets(): List<EqPreset> {
        val freqs = DEFAULT_EQ_BAND_FREQUENCIES_HZ
        val q = DEFAULT_EQ_BAND_Q_TIMES100
        return listOf(
            EqPreset(
                name = "Flat",
                config = EqBandConfig(freqs, List(freqs.size) { 0 }, q),
                builtIn = true
            ),
            EqPreset(
                name = "Bass Boost",
                config = EqBandConfig(freqs, listOf(520, 450, 260, 120, 0, -60, -120, -160), q),
                builtIn = true
            ),
            EqPreset(
                name = "Vocal",
                config = EqBandConfig(freqs, listOf(-220, -120, 80, 260, 340, 300, 90, -90), q),
                builtIn = true
            ),
            EqPreset(
                name = "Treble Boost",
                config = EqBandConfig(freqs, listOf(-120, -80, -20, 40, 120, 220, 360, 500), q),
                builtIn = true
            ),
            EqPreset(
                name = "V Shape",
                config = EqBandConfig(freqs, listOf(400, 320, 160, -80, -120, 80, 260, 380), q),
                builtIn = true
            )
        )
    }

    private fun findEqPresetByName(name: String): EqPreset? {
        val normalized = name.trim()
        if (normalized.isBlank()) return null
        return builtInEqPresets().firstOrNull { it.name.equals(normalized, ignoreCase = true) }
            ?: eqCustomPresets.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
    }

    private fun buildEqPresetNames(custom: List<EqPreset>): List<String> {
        return buildInEqPresetNames() + custom.map { it.name }.sorted()
    }

    private fun buildInEqPresetNames(): List<String> = builtInEqPresets().map { it.name }

    private fun parseCustomEqPresets(rawJson: String?): List<EqPreset> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(rawJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val node = arr.optJSONObject(i) ?: continue
                    val name = node.optString("name").trim()
                    if (name.isBlank()) continue
                    val bands = node.optJSONArray("bands") ?: continue
                    val frequencies = mutableListOf<Int>()
                    val levels = mutableListOf<Int>()
                    val qs = mutableListOf<Int>()
                    for (j in 0 until bands.length()) {
                        val band = bands.optJSONObject(j) ?: continue
                        val freq = band.optInt("freq", -1).coerceIn(20, 20_000)
                        if (freq <= 0) continue
                        frequencies += freq
                        levels += band.optInt("gain", 0).coerceIn(-1200, 1200)
                        qs += band.optInt("q", 100).coerceIn(20, 1200)
                    }
                    if (frequencies.isNotEmpty()) {
                        add(EqPreset(name, EqBandConfig(frequencies, levels, qs), builtIn = false))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistCustomEqPresets(custom: List<EqPreset>) {
        val arr = JSONArray()
        custom.forEach { preset ->
            val presetObj = JSONObject()
            presetObj.put("name", preset.name)
            val bandsArr = JSONArray()
            val size = minOf(
                preset.config.frequenciesHz.size,
                preset.config.levelsMb.size,
                preset.config.qTimes100.size
            )
            for (index in 0 until size) {
                val band = JSONObject()
                band.put("freq", preset.config.frequenciesHz[index].coerceIn(20, 20_000))
                band.put("gain", preset.config.levelsMb[index].coerceIn(-1200, 1200))
                band.put("q", preset.config.qTimes100[index].coerceIn(20, 1200))
                bandsArr.put(band)
            }
            presetObj.put("bands", bandsArr)
            arr.put(presetObj)
        }
        prefs.edit().putString(KEY_EQ_CUSTOM_PRESETS_JSON, arr.toString()).apply()
    }

    private fun markEqPresetAsCustom(state: EffectsUiState): EffectsUiState {
        val names = buildEqPresetNames(eqCustomPresets)
        return state.copy(eqActivePresetName = EQ_PRESET_CUSTOM_NAME, eqPresetNames = names)
    }

    private fun parseEqBandConfig(rawJson: String?, fallbackLevels: List<Int>): EqBandConfig {
        if (!rawJson.isNullOrBlank()) {
            val parsed = runCatching {
                val arr = JSONArray(rawJson)
                val frequencies = mutableListOf<Int>()
                val levels = mutableListOf<Int>()
                val qs = mutableListOf<Int>()
                for (i in 0 until arr.length()) {
                    val node = arr.optJSONObject(i) ?: continue
                    val freq = node.optInt("freq", -1).coerceIn(20, 20_000)
                    val gain = node.optInt("gain", 0).coerceIn(-1200, 1200)
                    val q = node.optInt("q", 100).coerceIn(20, 1200)
                    if (freq <= 0) continue
                    frequencies += freq
                    levels += gain
                    qs += q
                }
                if (frequencies.isEmpty()) {
                    null
                } else {
                    EqBandConfig(
                        frequenciesHz = frequencies,
                        levelsMb = levels,
                        qTimes100 = qs
                    )
                }
            }.getOrNull()
            if (parsed != null) {
                return parsed
            }
        }
        val normalizedLevels = fallbackLevels
            .take(DEFAULT_EQ_BAND_FREQUENCIES_HZ.size)
            .ifEmpty { List(DEFAULT_EQ_BAND_FREQUENCIES_HZ.size) { 0 } }
            .map { it.coerceIn(-1200, 1200) }
        return EqBandConfig(
            frequenciesHz = DEFAULT_EQ_BAND_FREQUENCIES_HZ,
            levelsMb = normalizedLevels,
            qTimes100 = DEFAULT_EQ_BAND_Q_TIMES100
        )
    }

    private fun persistEqBandConfig(state: EffectsUiState) {
        val size = minOf(
            state.eqBandFrequenciesHz.size,
            state.eqBandLevelsMb.size,
            state.eqBandQTimes100.size
        )
        val arr = JSONArray()
        for (index in 0 until size) {
            val node = JSONObject()
            node.put("freq", state.eqBandFrequenciesHz[index].coerceIn(20, 20_000))
            node.put("gain", state.eqBandLevelsMb[index].coerceIn(state.eqBandLevelMinMb, state.eqBandLevelMaxMb))
            node.put("q", state.eqBandQTimes100[index].coerceIn(20, 1200))
            arr.put(node)
        }
        val editor = prefs.edit()
            .putString(KEY_EQ_BAND_CONFIG_JSON, arr.toString())
            .putString(KEY_EQ_ACTIVE_PRESET_NAME, state.eqActivePresetName)
        for (index in 0 until DEFAULT_EQ_BAND_FREQUENCIES_HZ.size) {
            val level = state.eqBandLevelsMb.getOrElse(index) { 0 }
            editor.putInt("$KEY_EQ_BAND_LEVEL_PREFIX$index", level.coerceIn(state.eqBandLevelMinMb, state.eqBandLevelMaxMb))
        }
        editor.apply()
    }

    private fun restoreLastPlaybackSession(restorePlaybackState: Boolean) {
        val queueJson = prefs.getString(KEY_LAST_QUEUE_JSON, null).orEmpty()
        if (queueJson.isBlank()) {
            return
        }
        val entries = runCatching {
            val arr = JSONArray(queueJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: continue
                    add(item to uri)
                }
            }
        }.getOrDefault(emptyList())
        if (entries.isEmpty()) {
            clearLastPlaybackSession()
            return
        }

        val items = entries.map { (obj, uriString) ->
            val title = obj.optString("title")
            val artist = obj.optString("artist")
            val album = obj.optString("album")
            val artwork = obj.optString("artwork").takeIf { it.isNotBlank() }?.let(Uri::parse)
            val mediaId = obj.optString("mediaId").ifBlank { uriString.hashCode().toString() }
            val metadata = MediaMetadata.Builder()
                .setTitle(title.takeIf { it.isNotBlank() })
                .setArtist(artist.takeIf { it.isNotBlank() })
                .setAlbumTitle(album.takeIf { it.isNotBlank() })
                .setArtworkUri(artwork)
                .build()
            MediaItem.Builder()
                .setUri(Uri.parse(uriString))
                .setMediaId(mediaId)
                .setMediaMetadata(metadata)
                .build()
        }

        val currentPlayer = player ?: return
        val index = prefs.getInt(KEY_LAST_QUEUE_INDEX, 0).coerceIn(0, items.lastIndex)
        val positionMs = if (restorePlaybackState) {
            prefs.getLong(KEY_LAST_POSITION_MS, 0L).coerceAtLeast(0L)
        } else {
            0L
        }
        val playWhenReady = if (restorePlaybackState) {
            prefs.getBoolean(KEY_LAST_PLAY_WHEN_READY, true)
        } else {
            false
        }
        playbackQueue.clear()
        playbackQueue.addAll(items.mapIndexed { i, mediaItem ->
            val metadata = mediaItem.mediaMetadata
            LibraryTrack(
                id = mediaItem.mediaId.toLongOrNull() ?: (mediaItem.localConfiguration?.uri?.hashCode() ?: i).toLong(),
                uri = mediaItem.localConfiguration?.uri ?: Uri.EMPTY,
                title = metadata.title?.toString().orEmpty().ifBlank { "本地音频" },
                artist = metadata.artist?.toString().orEmpty().ifBlank { "未知艺术家" },
                album = metadata.albumTitle?.toString().orEmpty().ifBlank { "未知专辑" },
                durationMs = 0L,
                albumId = 0L,
                artworkUri = metadata.artworkUri,
                discNumber = 1,
                trackNumber = 0
            )
        })

        currentPlayer.setMediaItems(items, index, positionMs)
        currentPlayer.prepare()
        currentPlayer.playWhenReady = playWhenReady
        if (playWhenReady) {
            AudioPlaybackService.start(appContext)
            playbackServiceStarted = true
            currentPlayer.play()
        } else {
            currentPlayer.pause()
        }
        syncPlaybackState()
    }

    private fun persistPlaybackSessionIfNeeded(currentPlayer: ExoPlayer, hasMedia: Boolean, queueIndex: Int) {
        if (!_settingsState.value.rememberPlaybackTrackEnabled) {
            clearLastPlaybackSession()
            return
        }
        if (!hasMedia || currentPlayer.mediaItemCount <= 0) {
            clearLastPlaybackSession()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val positionMs = currentPlayer.currentPosition.coerceAtLeast(0L)
        val signature = buildString {
            append(currentPlayer.mediaItemCount)
            append('|')
            append(queueIndex)
            append('|')
            append(currentPlayer.playWhenReady)
            append('|')
            append(currentPlayer.currentMediaItem?.mediaId.orEmpty())
        }
        val positionDelta = kotlin.math.abs(positionMs - lastPersistPositionMs)
        val shouldPersist = signature != lastPersistSignature ||
            positionDelta >= 1500L ||
            now - lastPersistElapsedRealtimeMs >= 7000L
        if (!shouldPersist) {
            return
        }

        val queueJson = JSONArray().apply {
            for (i in 0 until currentPlayer.mediaItemCount) {
                val item = currentPlayer.getMediaItemAt(i)
                val uri = item.localConfiguration?.uri?.toString().orEmpty()
                if (uri.isBlank()) continue
                val metadata = item.mediaMetadata
                put(
                    JSONObject().apply {
                        put("uri", uri)
                        put("mediaId", item.mediaId)
                        put("title", metadata.title?.toString().orEmpty())
                        put("artist", metadata.artist?.toString().orEmpty())
                        put("album", metadata.albumTitle?.toString().orEmpty())
                        put("artwork", metadata.artworkUri?.toString().orEmpty())
                    }
                )
            }
        }
        if (queueJson.length() <= 0) {
            clearLastPlaybackSession()
            return
        }
        val editor = prefs.edit()
            .putString(KEY_LAST_QUEUE_JSON, queueJson.toString())
            .putInt(KEY_LAST_QUEUE_INDEX, queueIndex.coerceAtLeast(0))
        if (_settingsState.value.rememberPlaybackSessionEnabled) {
            editor.putLong(KEY_LAST_POSITION_MS, positionMs)
            editor.putBoolean(KEY_LAST_PLAY_WHEN_READY, currentPlayer.playWhenReady)
        } else {
            editor.remove(KEY_LAST_POSITION_MS)
            editor.remove(KEY_LAST_PLAY_WHEN_READY)
        }
        editor.apply()
        lastPersistSignature = signature
        lastPersistPositionMs = positionMs
        lastPersistElapsedRealtimeMs = now
    }

    private fun clearLastPlaybackSession() {
        if (prefs.contains(KEY_LAST_QUEUE_JSON).not() &&
            prefs.contains(KEY_LAST_QUEUE_INDEX).not() &&
            prefs.contains(KEY_LAST_POSITION_MS).not() &&
            prefs.contains(KEY_LAST_PLAY_WHEN_READY).not()
        ) {
            return
        }
        prefs.edit()
            .remove(KEY_LAST_QUEUE_JSON)
            .remove(KEY_LAST_QUEUE_INDEX)
            .remove(KEY_LAST_POSITION_MS)
            .remove(KEY_LAST_PLAY_WHEN_READY)
            .apply()
        lastPersistSignature = null
        lastPersistPositionMs = -1L
        lastPersistElapsedRealtimeMs = 0L
    }

    private fun clearLastPlaybackState() {
        if (prefs.contains(KEY_LAST_POSITION_MS).not() &&
            prefs.contains(KEY_LAST_PLAY_WHEN_READY).not()
        ) {
            return
        }
        prefs.edit()
            .remove(KEY_LAST_POSITION_MS)
            .remove(KEY_LAST_PLAY_WHEN_READY)
            .apply()
        lastPersistPositionMs = -1L
        lastPersistElapsedRealtimeMs = 0L
    }

    private fun syncPlaybackState(errorMessage: String? = null) {
        val currentPlayer = player ?: return
        val hasMedia = currentPlayer.currentMediaItem != null
        val queueSize = currentPlayer.mediaItemCount
        val queueIndex = if (hasMedia) currentPlayer.currentMediaItemIndex.coerceAtLeast(0) else -1
        val sleepRemainingMs = sleepTimerEndElapsedMs?.let { end ->
            (end - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } ?: 0L
        val metadata = currentPlayer.currentMediaItem?.mediaMetadata
        val title = metadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "鏈€夋嫨闊抽"
        val artist = metadata?.artist?.toString().orEmpty()
        val album = metadata?.albumTitle?.toString().orEmpty()
        val subtitle = when {
            !hasMedia -> "鏀寔 FLAC / WAV / MP3 / AAC"
            artist.isNotBlank() || album.isNotBlank() -> {
                listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")
            }

            _settingsState.value.hiFiMode -> "Hi-Fi 输出模式已开启"
            else -> "标准音频模式"
        }
        val banner = formatStreamInfo(currentPlayer, _playbackState.value.copy(
            hasMedia = hasMedia,
            playWhenReady = currentPlayer.playWhenReady,
            isPlaying = currentPlayer.isPlaying,
            title = title,
            subtitle = subtitle
        ))
        if (!hasMedia || !currentPlayer.isPlaying) {
            topBarVisualizationProcessor?.clearSnapshot()
        }

        _playbackState.value = _playbackState.value.copy(
            title = title,
            subtitle = subtitle,
            artist = artist,
            album = album,
            mediaUri = currentPlayer.currentMediaItem?.localConfiguration?.uri,
            artworkUri = metadata?.artworkUri,
            hasMedia = hasMedia,
            playWhenReady = currentPlayer.playWhenReady,
            isPlaying = currentPlayer.isPlaying,
            durationMs = if (currentPlayer.duration > 0L) currentPlayer.duration else 0L,
            positionMs = currentPlayer.currentPosition.coerceAtLeast(0L),
            bufferedPositionMs = currentPlayer.bufferedPosition.coerceAtLeast(0L),
            audioSessionId = currentPlayer.audioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET } ?: 0,
            errorMessage = errorMessage,
            queueSize = queueSize,
            queueIndex = queueIndex,
            hasPrevious = currentPlayer.hasPreviousMediaItem() || currentPlayer.currentPosition > 3_000L,
            hasNext = currentPlayer.hasNextMediaItem(),
            shuffleEnabled = currentPlayer.shuffleModeEnabled,
            repeatMode = currentPlayer.repeatMode,
            sleepTimerRemainingMs = sleepRemainingMs,
            streamInfoVisible = banner.visible,
            streamInfoLeft = banner.left,
            streamInfoCenter = banner.center,
            streamInfoRight = banner.right,
            streamInfoUseDacAccent = banner.useDacAccent,
            streamInfoUseIsoDacTheme = banner.useIsoDacTheme
        )

        if (_settingsState.value.rememberPlaybackTrackEnabled) {
            if (hasMedia) {
                persistPlaybackSessionIfNeeded(currentPlayer, hasMedia, queueIndex)
            }
        } else {
            clearLastPlaybackSession()
        }

        if (hasMedia && !playbackServiceStarted) {
            AudioPlaybackService.start(appContext)
            playbackServiceStarted = true
        } else if (!hasMedia && playbackServiceStarted) {
            AudioPlaybackService.stop(appContext)
            playbackServiceStarted = false
        }
    }

    private fun checkSleepTimer() {
        val endTime = sleepTimerEndElapsedMs ?: return
        if (SystemClock.elapsedRealtime() < endTime) {
            return
        }
        sleepTimerEndElapsedMs = null
        player?.pause()
    }

    private fun queryTracks(scanFolderRelativePaths: List<String>): List<LibraryTrack> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 10000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val relativePathPrefixes = scanFolderRelativePaths.mapNotNull { buildRelativePathPrefix(it) }
        val tracks = mutableListOf<LibraryTrack>()

        appContext.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val trackIndex = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val dateAddedIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val relativePathIndex = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val relativePath = if (relativePathIndex >= 0) cursor.getString(relativePathIndex).orEmpty() else ""
                if (relativePathPrefixes.isNotEmpty()) {
                    val matched = relativePath.isNotBlank() && relativePathPrefixes.any { prefix ->
                        relativePath.startsWith(prefix, ignoreCase = true)
                    }
                    if (!matched) {
                        continue
                    }
                }

                val id = cursor.getLong(idIndex)
                val albumId = cursor.getLong(albumIdIndex)
                val trackRaw = if (trackIndex >= 0) cursor.getInt(trackIndex) else 0
                val discNumber = when {
                    trackRaw >= 1000 -> (trackRaw / 1000).coerceAtLeast(1)
                    else -> 1
                }
                val trackNumber = when {
                    trackRaw >= 1000 -> (trackRaw % 1000).coerceAtLeast(0)
                    else -> trackRaw.coerceAtLeast(0)
                }
                val contentUri = ContentUris.withAppendedId(uri, id)
                val artworkUri = if (albumId > 0) {
                    ContentUris.withAppendedId(albumArtBaseUri, albumId)
                } else {
                    null
                }
                val dateAddedEpochSeconds = if (dateAddedIndex >= 0) cursor.getLong(dateAddedIndex) else 0L
                tracks += LibraryTrack(
                    id = id,
                    uri = contentUri,
                    title = cursor.getString(titleIndex).orEmpty().ifBlank { "未知标题" },
                    artist = cursor.getString(artistIndex).orEmpty().ifBlank { "未知艺术家" },
                    album = cursor.getString(albumIndex).orEmpty().ifBlank { "未知专辑" },
                    durationMs = cursor.getLong(durationIndex),
                    albumId = albumId,
                    artworkUri = artworkUri,
                    dateAddedEpochSeconds = dateAddedEpochSeconds,
                    playCount = trackPlayCounts[id] ?: 0,
                    discNumber = discNumber,
                    trackNumber = trackNumber
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

    private fun buildScanFolderLabel(relativePath: String?): String {
        val normalized = normalizeScanFolderRelativePath(relativePath)
        return if (normalized == null) {
            appContext.getString(R.string.library_scan_folder_default)
        } else {
            appContext.getString(R.string.library_scan_folder_format, normalized)
        }
    }

    private fun buildRelativePathPrefix(relativePath: String?): String? {
        val normalized = normalizeScanFolderRelativePath(relativePath)
        return if (normalized == null) null else "$normalized/"
    }

    private fun normalizeScanFolderRelativePath(relativePath: String?): String? {
        return relativePath
            ?.replace('\\', '/')
            ?.trim()
            ?.trim('/')
            ?.takeIf { it.isNotBlank() }
    }

    private fun buildScanFolderLabel(relativePaths: List<String>): String {
        val normalizedPaths = mergeScanFolderRelativePaths(emptyList(), relativePaths)
        return when {
            normalizedPaths.isEmpty() -> appContext.getString(R.string.library_scan_folder_default)
            normalizedPaths.size == 1 -> appContext.getString(
                R.string.library_scan_folder_format,
                normalizedPaths.first()
            )
            else -> appContext.getString(
                R.string.library_scan_folder_multi_format,
                normalizedPaths.size,
                normalizedPaths.take(2).joinToString("、")
            )
        }
    }

    private fun mergeScanFolderRelativePaths(existing: List<String>, appended: List<String>): List<String> {
        val merged = linkedSetOf<String>()
        (existing + appended).forEach { rawPath ->
            val normalized = normalizeScanFolderRelativePath(rawPath) ?: return@forEach
            if (merged.none { it.equals(normalized, ignoreCase = true) }) {
                merged += normalized
            }
        }
        return merged.toList()
    }

    private fun serializeScanFolderRelativePaths(relativePaths: List<String>): String {
        val arr = JSONArray()
        mergeScanFolderRelativePaths(emptyList(), relativePaths).forEach { arr.put(it) }
        return arr.toString()
    }

    private fun parseScanFolderRelativePaths(rawJson: String?, fallback: List<String> = emptyList()): List<String> {
        if (rawJson.isNullOrBlank()) {
            return mergeScanFolderRelativePaths(emptyList(), fallback)
        }
        val parsed = runCatching {
            val arr = JSONArray(rawJson)
            buildList {
                for (index in 0 until arr.length()) {
                    add(arr.optString(index))
                }
            }
        }.getOrElse { fallback }
        return mergeScanFolderRelativePaths(emptyList(), parsed)
    }

    private fun incrementTrackPlayCount(trackId: Long?) {
        val id = trackId ?: return
        val next = (trackPlayCounts[id] ?: 0) + 1
        trackPlayCounts[id] = next
        persistTrackPlayCounts()
    }

    private fun persistTrackPlayCounts() {
        val json = JSONObject()
        trackPlayCounts.toSortedMap().forEach { (trackId, count) ->
            if (count > 0) {
                json.put(trackId.toString(), count)
            }
        }
        prefs.edit().putString(KEY_TRACK_PLAY_COUNTS_JSON, json.toString()).apply()
    }

    private fun parseTrackPlayCounts(raw: String?): Map<Long, Int> {
        if (raw.isNullOrBlank()) {
            return emptyMap()
        }
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val result = linkedMapOf<Long, Int>()
        val iterator = parsed.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            val id = key.toLongOrNull() ?: continue
            val count = parsed.optInt(key, 0).coerceAtLeast(0)
            if (count > 0) {
                result[id] = count
            }
        }
        return result
    }
}
