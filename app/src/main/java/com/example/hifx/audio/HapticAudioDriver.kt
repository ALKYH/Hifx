package com.example.hifx.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Drives the device's linear resonant actuator (LRA) from a low-frequency audio envelope.
 *
 * Design goals:
 *  - Stable, glitch-free output even on dropped/late envelope frames.
 *  - Never block the audio thread: all dispatch happens on a dedicated background handler.
 *  - Prefer vendor fast-paths (HyperOS / MIUI / OPPO / vivo) that expose continuous-amplitude
 *    APIs, then fall back to AOSP [VibrationEffect] composition. We mirror the behaviour of
 *    OGG files tagged with the `ANDROID_HAPTIC=1` Vorbis comment, where the system feeds the
 *    bass channel into the motor; here we synthesise the same drive signal from any source.
 *
 * The driver receives an envelope value in `[0f, 1f]` at a fixed cadence (≈50 Hz) from
 * [HapticAudioProcessor]. It applies hysteresis, slew limiting and a min-interval cooldown
 * before emitting a vibration command, so very quiet passages do not chatter the motor and
 * very loud passages do not over-saturate it.
 */
internal class HapticAudioDriver private constructor(
    context: Context,
    private val vibrator: Vibrator
) {

    private val appContext: Context = context.applicationContext
    private val workerThread = HandlerThread("HifxHapticAudio").apply {
        priority = Thread.NORM_PRIORITY + 1
        start()
    }
    private val handler = Handler(workerThread.looper)

    @Volatile private var enabled: Boolean = false
    @Volatile private var released: Boolean = false

    /** Last envelope sample we acted on, in `[0f, 1f]`. */
    private var lastEnvelope: Float = 0f
    /** Last amplitude we sent to the motor, in `[0, 255]`. */
    private var lastAmplitude: Int = 0
    /** Uptime (ms) when we last issued a command — used to throttle. */
    private var lastDispatchUptimeMs: Long = 0L
    /** Uptime (ms) until which the most recent vibration is expected to be active. */
    private var activeUntilUptimeMs: Long = 0L

    private val vendorBackend: VendorBackend? = VendorBackend.detect(appContext, vibrator)
    private val supportsAmplitudeControl: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()

    private val mediaAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    fun setEnabled(value: Boolean) {
        if (released) return
        if (enabled == value) return
        enabled = value
        if (!value) {
            handler.post { stopInternal() }
        }
    }

    /**
     * Submit one envelope sample. May be called from the audio thread; this method does not
     * block — the actual vibrator IPC runs on the worker handler.
     */
    fun submitEnvelope(envelope: Float) {
        if (!enabled || released) return
        val clamped = envelope.coerceIn(0f, 1f)
        // Slew limit: prevent abrupt jumps that would feel like clicks rather than rumble.
        val slewed = slewLimit(previous = lastEnvelope, target = clamped)
        lastEnvelope = slewed
        handler.post { dispatchInternal(slewed) }
    }

    fun release() {
        if (released) return
        released = true
        enabled = false
        handler.post {
            stopInternal()
            workerThread.quitSafely()
        }
    }

    // -- internal --------------------------------------------------------------------------

    private fun slewLimit(previous: Float, target: Float): Float {
        val maxRise = 0.45f   // can ramp up fast (snappy bass hit)
        val maxFall = 0.20f   // decay must be slower so it feels musical, not stuttery
        val delta = target - previous
        return when {
            delta > maxRise -> previous + maxRise
            delta < -maxFall -> previous - maxFall
            else -> target
        }
    }

    private fun dispatchInternal(envelope: Float) {
        if (!enabled || released) return

        // Map envelope to [0,255] amplitude using a perceptual curve that emphasises the
        // bottom of the range (bass usually sits low after RMS averaging).
        val amplitude = perceptualToAmplitude(envelope)
        val now = SystemClock.uptimeMillis()

        // Hysteresis: if the change is small AND the motor still has time on the clock,
        // skip dispatch entirely to avoid IPC storms.
        val amplitudeDelta = kotlin.math.abs(amplitude - lastAmplitude)
        val stillActive = now < activeUntilUptimeMs
        if (amplitude == 0 && lastAmplitude == 0) {
            return
        }
        if (stillActive && amplitudeDelta < AMPLITUDE_DELTA_THRESHOLD) {
            return
        }
        // Throttle to MIN_DISPATCH_INTERVAL_MS even if amplitude is volatile.
        if (now - lastDispatchUptimeMs < MIN_DISPATCH_INTERVAL_MS &&
            amplitudeDelta < AMPLITUDE_DELTA_THRESHOLD_LARGE
        ) {
            return
        }

        if (amplitude == 0) {
            stopInternal()
            return
        }

        val durationMs = nextDurationMs(envelope)
        val ok = vendorBackend?.tryVibrate(amplitude, durationMs)
            ?: dispatchAosp(amplitude, durationMs)

        if (ok) {
            lastAmplitude = amplitude
            lastDispatchUptimeMs = now
            // Add a small overlap so back-to-back commands don't leave audible gaps.
            activeUntilUptimeMs = now + durationMs + DISPATCH_OVERLAP_MS
        }
    }

    private fun dispatchAosp(amplitude: Int, durationMs: Long): Boolean {
        return runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && supportsAmplitudeControl -> {
                    val effect = VibrationEffect.createOneShot(durationMs, amplitude)
                    vibrateWithAttributes(effect)
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    // No amplitude control: gate by amplitude threshold to avoid buzzing on
                    // every tiny change.
                    if (amplitude < AMPLITUDE_FALLBACK_GATE) return@runCatching false
                    val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                    vibrateWithAttributes(effect)
                }

                else -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
            true
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun vibrateWithAttributes(effect: VibrationEffect) {
        // Tag commands as MEDIA so DnD/silent profiles handle them like the system would
        // for an ANDROID_HAPTIC OGG (which is also classified under USAGE_MEDIA).
        vibrator.vibrate(effect, mediaAudioAttributes)
    }

    private fun stopInternal() {
        if (lastAmplitude == 0 && activeUntilUptimeMs == 0L) return
        runCatching { vibrator.cancel() }
        lastAmplitude = 0
        activeUntilUptimeMs = 0L
    }

    /**
     * Convert a perceptual envelope `[0,1]` to a vibrator amplitude in `[0,255]` with
     * a soft toe and a hard ceiling so loud passages don't peg the motor.
     */
    private fun perceptualToAmplitude(envelope: Float): Int {
        if (envelope <= ENVELOPE_NOISE_FLOOR) return 0
        val shaped = ((envelope - ENVELOPE_NOISE_FLOOR) / (1f - ENVELOPE_NOISE_FLOOR))
            .coerceIn(0f, 1f)
        // gamma 0.65 → emphasise low-mid envelope, ceiling at 220/255 to stay below thermal limit.
        val curved = Math.pow(shaped.toDouble(), 0.65).toFloat()
        return (curved * 220f).roundToInt().coerceIn(1, 255)
    }

    private fun nextDurationMs(envelope: Float): Long {
        // Stronger bass = longer pulse; clamps avoid both jitter and runaway pulses.
        val base = MIN_DURATION_MS + ((MAX_DURATION_MS - MIN_DURATION_MS) * envelope).roundToInt()
        return max(MIN_DURATION_MS, min(MAX_DURATION_MS, base)).toLong()
    }

    // -- vendor fast paths -----------------------------------------------------------------

    /**
     * Tries reflective access to vendor-specific continuous-amplitude APIs. None are
     * documented; we keep the code defensive so a missing class on stock AOSP is a no-op.
     */
    private class VendorBackend private constructor(
        private val invoker: (amplitude: Int, durationMs: Long) -> Boolean
    ) {
        fun tryVibrate(amplitude: Int, durationMs: Long): Boolean = runCatching {
            invoker(amplitude, durationMs)
        }.getOrDefault(false)

        companion object {
            fun detect(context: Context, vibrator: Vibrator): VendorBackend? {
                detectMiui(context)?.let { return it }
                detectOplusLinearMotor(context)?.let { return it }
                detectVivoFf(context)?.let { return it }
                return null
            }

            /**
             * HyperOS / MIUI: [miui.util.HapticFeedbackUtil] exposes a `performExtHapticFeedback`
             * that accepts a strength int. Newer ROMs also expose
             * `MiuiVibrator#linearMotorVibrate(int amplitude, int durationMs)` via
             * [android.os.SystemVibrator] subclassing — we probe for both.
             */
            private fun detectMiui(context: Context): VendorBackend? {
                // Path A: miui.util.HapticFeedbackUtil
                val utilClass = runCatching { Class.forName("miui.util.HapticFeedbackUtil") }
                    .getOrNull()
                if (utilClass != null) {
                    val ctor = runCatching {
                        utilClass.getConstructor(Context::class.java, Boolean::class.javaPrimitiveType)
                    }.getOrNull()
                    val instance = runCatching { ctor?.newInstance(context, false) }.getOrNull()
                    val perform = runCatching {
                        utilClass.getMethod(
                            "performExtHapticFeedback",
                            Int::class.javaPrimitiveType
                        )
                    }.getOrNull()
                    if (instance != null && perform != null) {
                        return VendorBackend { amplitude, _ ->
                            // 200+ are MIUI's "rich" effects; map our amplitude into the
                            // documented strength range used by the music haptics on Xiaomi.
                            val strength = miuiStrengthFromAmplitude(amplitude)
                            perform.invoke(instance, strength)
                            true
                        }
                    }
                }
                // Path B: reflectively reachable linearMotorVibrate on SystemVibrator subclass
                val systemVibratorClass = runCatching {
                    Class.forName("android.os.SystemVibrator")
                }.getOrNull()
                val linearMotorMethod = systemVibratorClass?.declaredMethods?.firstOrNull {
                    it.name.equals("linearMotorVibrate", ignoreCase = true) &&
                        it.parameterTypes.size >= 2
                }
                if (linearMotorMethod != null) {
                    linearMotorMethod.isAccessible = true
                    val service = context.getSystemService(Context.VIBRATOR_SERVICE)
                    return VendorBackend { amplitude, durationMs ->
                        linearMotorMethod.invoke(service, amplitude, durationMs.toInt())
                        true
                    }
                }
                return null
            }

            private fun miuiStrengthFromAmplitude(amplitude: Int): Int = when {
                amplitude >= 200 -> 0   // HapticFeedbackConstants.MIUI_TAP_HEAVY-equivalent
                amplitude >= 140 -> 1
                amplitude >= 90 -> 2
                amplitude >= 50 -> 3
                else -> 4
            }

            /**
             * OPPO ColorOS / OnePlus OxygenOS: `com.oplus.os.LinearmotorVibrator` (varies by
             * OS version). Method signature is `vibrate(int level, int amplitude, int frequency)`
             * on newer builds.
             */
            private fun detectOplusLinearMotor(context: Context): VendorBackend? {
                val candidates = listOf(
                    "com.oplus.os.LinearmotorVibrator",
                    "com.oneplus.os.LinearmotorVibrator"
                )
                for (className in candidates) {
                    val cls = runCatching { Class.forName(className) }.getOrNull() ?: continue
                    val service = runCatching {
                        context.getSystemService("linearmotor")
                    }.getOrNull() ?: continue
                    val vibrate = cls.declaredMethods.firstOrNull {
                        it.name == "vibrate" && it.parameterTypes.size == 3
                    } ?: continue
                    vibrate.isAccessible = true
                    return VendorBackend { amplitude, _ ->
                        // (level=2 short, amplitude 0..255, frequency in Hz; LRAs are tuned ~170Hz)
                        vibrate.invoke(service, /* level */ 2, amplitude, /* freq */ 170)
                        true
                    }
                }
                return null
            }

            /**
             * vivo FunTouch / Origin OS: `android.os.FtFeature` + `Vibrator.vibratorPro` paths
             * exist on newer devices. Their signatures shift, so we only attempt the most
             * commonly seen one.
             */
            private fun detectVivoFf(context: Context): VendorBackend? {
                val vibratorService = context.getSystemService(Context.VIBRATOR_SERVICE) ?: return null
                val method = vibratorService.javaClass.declaredMethods.firstOrNull {
                    it.name == "vibratorPro" && it.parameterTypes.size >= 3
                } ?: return null
                method.isAccessible = true
                return VendorBackend { amplitude, durationMs ->
                    method.invoke(
                        vibratorService,
                        /* effectId */ -1,
                        /* amplitude */ amplitude,
                        /* freq */ 170,
                        durationMs
                    )
                    true
                }
            }
        }
    }

    companion object {
        // Envelope under this fraction is treated as silence to avoid motor whine on near-zero
        // signals. Tuned against typical streaming masters where the bass noise floor sits at
        // about -50 dBFS after the low-pass.
        private const val ENVELOPE_NOISE_FLOOR = 0.06f

        // Minimum gap between successive dispatches. The motor can comfortably handle ~50 Hz
        // updates; anything tighter than this just generates redundant IPC.
        private const val MIN_DISPATCH_INTERVAL_MS = 18L

        // Per-pulse duration limits. Pulses overlap by [DISPATCH_OVERLAP_MS] so the LRA never
        // fully decays between commands during sustained bass.
        private const val MIN_DURATION_MS = 28
        private const val MAX_DURATION_MS = 90
        private const val DISPATCH_OVERLAP_MS = 6L

        // If amplitude moved by less than this, treat as "no change" (hysteresis).
        private const val AMPLITUDE_DELTA_THRESHOLD = 12
        // Above this delta, allow an early dispatch even if we're inside the throttle window.
        private const val AMPLITUDE_DELTA_THRESHOLD_LARGE = 60
        // For devices without amplitude control, gate by minimum amplitude so we don't buzz
        // for every quiet kick.
        private const val AMPLITUDE_FALLBACK_GATE = 80

        fun createOrNull(context: Context): HapticAudioDriver? {
            val vibrator = resolveVibrator(context) ?: return null
            if (!vibrator.hasVibrator()) return null
            return HapticAudioDriver(context, vibrator)
        }

        private fun resolveVibrator(context: Context): Vibrator? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
    }
}
