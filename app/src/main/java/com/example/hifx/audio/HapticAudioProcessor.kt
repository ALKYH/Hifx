package com.example.hifx.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure pass-through Media3 [AudioProcessor] that derives a low-frequency envelope from the
 * decoded PCM stream and forwards it to a [HapticAudioDriver] at ~50 Hz.
 *
 * Mirrors the system behaviour of OGG files tagged with `ANDROID_HAPTIC=1`, where the bass
 * frequencies of one channel are routed to the linear motor. We synthesise an equivalent
 * drive signal in software so any source (FLAC/MP3/streaming) can vibrate the LRA.
 *
 * Pipeline per-frame:
 *   1. Mix multi-channel PCM down to mono.
 *   2. 2nd-order Butterworth low-pass at [LOWPASS_HZ] → bass-only signal.
 *   3. Full-wave rectify, then asymmetric one-pole envelope follower (fast attack, slow
 *      release) → smooth `[0..1]` envelope.
 *   4. Decimate to ENVELOPE_HZ and submit to the driver.
 *
 * The processor never alters the audio — it only reads samples and copies them through. It
 * is designed to coexist with [androidx.media3.common.audio.AudioProcessor] downstream of
 * other DSP, so any volume / EQ / spatialiser changes are reflected in what the user hears
 * AND in what drives the motor.
 */
internal class HapticAudioProcessor(
    private val driver: HapticAudioDriver
) : BaseAudioProcessor() {

    private var configuredFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var sampleRateHz: Int = 0
    private var channelCount: Int = 0

    // Biquad low-pass coefficients (Direct Form I)
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var z1 = 0.0; private var z2 = 0.0

    // Envelope follower state (one-pole, asymmetric)
    private var envelopeState: Float = 0f
    private var attackCoef: Float = 0f
    private var releaseCoef: Float = 0f

    // Decimation state
    private var samplesPerEnvelopeFrame: Int = 1
    private var sampleCounterInFrame: Int = 0
    private var peakInFrame: Float = 0f

    @Volatile private var enabled: Boolean = false

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            envelopeState = 0f
            peakInFrame = 0f
            sampleCounterInFrame = 0
            driver.submitEnvelope(0f)
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configuredFormat = inputAudioFormat
        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        configureFilters()
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    private fun configureFilters() {
        if (sampleRateHz <= 0) return
        // 2nd-order Butterworth LPF (RBJ cookbook), Q = 1/sqrt(2)
        val q = 1.0 / sqrt(2.0)
        val w0 = 2.0 * PI * LOWPASS_HZ / sampleRateHz.toDouble()
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = ((1.0 - cosW0) / 2.0) / a0
        b1 = (1.0 - cosW0) / a0
        b2 = ((1.0 - cosW0) / 2.0) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha) / a0
        z1 = 0.0
        z2 = 0.0

        // Envelope follower coefficients in [0,1] (one-pole, asymmetric)
        attackCoef = onePoleCoef(ATTACK_MS, sampleRateHz)
        releaseCoef = onePoleCoef(RELEASE_MS, sampleRateHz)

        // Decimate to ENVELOPE_HZ; clamp so a frame always contains at least one sample.
        samplesPerEnvelopeFrame = (sampleRateHz / ENVELOPE_HZ).coerceAtLeast(1)
        sampleCounterInFrame = 0
        peakInFrame = 0f
        envelopeState = 0f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        if (enabled && sampleRateHz > 0 && channelCount > 0) {
            val analysis = inputBuffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
            when (configuredFormat.encoding) {
                C.ENCODING_PCM_16BIT -> analyzePcm16(analysis)
                C.ENCODING_PCM_FLOAT -> analyzeFloat(analysis)
                else -> Unit // Other encodings are not configured; we forward only.
            }
        }
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    private fun analyzePcm16(buffer: ByteBuffer) {
        val frameSizeBytes = channelCount * 2
        val totalBytes = buffer.remaining() - (buffer.remaining() % frameSizeBytes)
        var i = 0
        while (i < totalBytes) {
            var mix = 0
            var c = 0
            while (c < channelCount) {
                mix += buffer.short.toInt()
                c++
                i += 2
            }
            val mono = (mix.toFloat() / channelCount.toFloat()) / 32768f
            consumeSample(mono)
        }
    }

    private fun analyzeFloat(buffer: ByteBuffer) {
        val frameSizeBytes = channelCount * 4
        val totalBytes = buffer.remaining() - (buffer.remaining() % frameSizeBytes)
        var i = 0
        while (i < totalBytes) {
            var mix = 0f
            var c = 0
            while (c < channelCount) {
                mix += buffer.float
                c++
                i += 4
            }
            consumeSample(mix / channelCount.toFloat())
        }
    }

    private fun consumeSample(monoSample: Float) {
        // Biquad LPF (Direct Form II Transposed)
        val input = monoSample.toDouble()
        val output = b0 * input + z1
        z1 = b1 * input - a1 * output + z2
        z2 = b2 * input - a2 * output
        val low = output.toFloat()

        // Envelope follower
        val rectified = abs(low)
        val coef = if (rectified > envelopeState) attackCoef else releaseCoef
        envelopeState += coef * (rectified - envelopeState)

        // Per-frame peak so brief transients aren't lost by decimation.
        if (envelopeState > peakInFrame) peakInFrame = envelopeState

        sampleCounterInFrame++
        if (sampleCounterInFrame >= samplesPerEnvelopeFrame) {
            // Apply a soft ceiling and modest pre-amp; bass content rarely exceeds -6 dBFS
            // so we lift it to use more of the [0..1] driver range.
            val boosted = (peakInFrame * BASS_PREAMP).coerceIn(0f, 1f)
            driver.submitEnvelope(boosted)
            sampleCounterInFrame = 0
            peakInFrame = 0f
        }
    }

    override fun onFlush() {
        z1 = 0.0; z2 = 0.0
        envelopeState = 0f
        peakInFrame = 0f
        sampleCounterInFrame = 0
        driver.submitEnvelope(0f)
    }

    override fun onReset() {
        configuredFormat = AudioProcessor.AudioFormat.NOT_SET
        z1 = 0.0; z2 = 0.0
        envelopeState = 0f
        peakInFrame = 0f
        sampleCounterInFrame = 0
        driver.submitEnvelope(0f)
    }

    companion object {
        // Cutoff for the low-pass that defines our "bass" band. 140 Hz roughly matches the
        // band that vendor implementations route to the LRA when an OGG carries
        // ANDROID_HAPTIC=1 (LRA resonance is typically 150–200 Hz; we want the energy below
        // that to drive sustained vibrations rather than buzz).
        private const val LOWPASS_HZ = 140.0

        // Envelope follower time constants (RBJ-style one-pole)
        private const val ATTACK_MS = 8.0
        private const val RELEASE_MS = 90.0

        // Output rate of envelope frames sent to the driver. 50 Hz balances responsiveness
        // against IPC overhead and matches the throttle used by HapticAudioDriver.
        private const val ENVELOPE_HZ = 50

        // Bass content sits well below 0 dBFS even on hot masters; lift it before passing to
        // the driver. Values above 1.0 are safe — the driver also clamps and curves.
        private const val BASS_PREAMP = 1.8f

        private fun onePoleCoef(timeMs: Double, sampleRate: Int): Float {
            // 1 - exp(-1 / (timeSec * sampleRate))
            val timeSec = timeMs / 1000.0
            val coef = 1.0 - exp(-1.0 / (timeSec * sampleRate.toDouble()))
            return coef.toFloat().coerceIn(0f, 1f)
        }
    }
}
