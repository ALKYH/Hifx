package com.alky.hifx.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Coarse, low-frequency haptic extractor.
 *
 * Every ~50 ms we analyze only the low end of the original song and emit:
 *  - bassLevel: sustained low-frequency energy
 *  - kickStrength: transient rise in low-frequency energy, to simulate kick drum hits
 *
 * This avoids continuous dense feedback and keeps the haptic side focused on drum-like pulses.
 */
internal class HapticAudioProcessor(
    private val driver: HapticAudioDriver
) : BaseAudioProcessor() {

    private var configuredFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var sampleRateHz: Int = 0
    private var channelCount: Int = 0
    private var samplesPerFrame: Int = 1

    private var sampleCounter: Int = 0
    private var bassSumSquares: Float = 0f
    private var bassPeak: Float = 0f
    private var previousBassRms: Float = 0f
    private var previousBassLevel: Float = 0f

    private var lp1State: Float = 0f
    private var lp2State: Float = 0f
    private var lowPassAlpha: Float = 0f

    @Volatile private var enabled: Boolean = false
    @Volatile private var lastBassLevel: Float = 0f
    @Volatile private var lastKickStrength: Float = 0f
    @Volatile private var submittedCount: Long = 0L

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            resetAnalysisState()
            lastBassLevel = 0f
            lastKickStrength = 0f
            driver.submitPulse(0f, 0f)
        }
    }

    fun debugStatus(): String {
        return "enabled=$enabled sampleRate=$sampleRateHz channels=$channelCount frameSamples=$samplesPerFrame lastBass=$lastBassLevel lastKick=$lastKickStrength submitted=$submittedCount"
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configuredFormat = inputAudioFormat
        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        samplesPerFrame = ((sampleRateHz * FRAME_WINDOW_MS) / 1000).coerceAtLeast(1)
        configureLowPass()
        resetAnalysisState()
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        if (enabled && sampleRateHz > 0 && channelCount > 0) {
            val analysis = inputBuffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
            when (configuredFormat.encoding) {
                C.ENCODING_PCM_16BIT -> analyzePcm16(analysis)
                C.ENCODING_PCM_FLOAT -> analyzeFloat(analysis)
                else -> Unit
            }
        }
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    private fun configureLowPass() {
        if (sampleRateHz <= 0) return
        val normalized = (2.0 * PI * LOWPASS_HZ / sampleRateHz.toDouble()).coerceAtMost(0.95)
        lowPassAlpha = (normalized / (normalized + 1.0)).toFloat().coerceIn(0.01f, 0.8f)
        lp1State = 0f
        lp2State = 0f
    }

    private fun analyzePcm16(buffer: ByteBuffer) {
        val frameSizeBytes = channelCount * 2
        val totalBytes = buffer.remaining() - (buffer.remaining() % frameSizeBytes)
        var i = 0
        while (i < totalBytes) {
            var mix = 0
            repeat(channelCount) {
                mix += buffer.short.toInt()
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
            repeat(channelCount) {
                mix += buffer.float
                i += 4
            }
            consumeSample(mix / channelCount.toFloat())
        }
    }

    private fun consumeSample(sample: Float) {
        val mono = sample.coerceIn(-1f, 1f)
        lp1State += lowPassAlpha * (mono - lp1State)
        lp2State += lowPassAlpha * (lp1State - lp2State)
        val bass = lp2State
        val bassAbs = abs(bass)
        bassSumSquares += bassAbs * bassAbs
        if (bassAbs > bassPeak) {
            bassPeak = bassAbs
        }
        sampleCounter++
        if (sampleCounter >= samplesPerFrame) {
            emitFrame()
            resetFrameAccumulators()
        }
    }

    private fun emitFrame() {
        val count = sampleCounter.coerceAtLeast(1)
        val bassRms = kotlin.math.sqrt((bassSumSquares / count).coerceAtLeast(0f))
        val rawBassLevel = (
            bassRms * BASS_RMS_GAIN +
                bassPeak * BASS_PEAK_WEIGHT
            ).coerceIn(0f, 1f)
        val bassLevel = if (rawBassLevel >= BASS_GATE_THRESHOLD) rawBassLevel else 0f
        val rise = (bassLevel - previousBassLevel).coerceAtLeast(0f)
        val kickStrength = if (rise >= RISE_TRIGGER_THRESHOLD) {
            (rise * KICK_RISE_GAIN + bassPeak * KICK_PEAK_WEIGHT).coerceIn(0f, 1f)
        } else {
            0f
        }
        previousBassRms = bassRms
        previousBassLevel = bassLevel

        lastBassLevel = bassLevel
        lastKickStrength = kickStrength
        submittedCount++
        driver.submitPulse(bassLevel, kickStrength)
    }

    private fun resetFrameAccumulators() {
        sampleCounter = 0
        bassSumSquares = 0f
        bassPeak = 0f
    }

    private fun resetAnalysisState() {
        resetFrameAccumulators()
        previousBassRms = 0f
        previousBassLevel = 0f
        lp1State = 0f
        lp2State = 0f
    }

    override fun onFlush() {
        resetAnalysisState()
        driver.submitPulse(0f, 0f)
    }

    override fun onReset() {
        configuredFormat = AudioProcessor.AudioFormat.NOT_SET
        sampleRateHz = 0
        channelCount = 0
        samplesPerFrame = 1
        resetAnalysisState()
        driver.submitPulse(0f, 0f)
    }

    companion object {
        private const val FRAME_WINDOW_MS = 50
        private const val LOWPASS_HZ = 10.0
        private const val BASS_RMS_GAIN = 4.2f
        private const val BASS_PEAK_WEIGHT = 0.75f
        private const val BASS_GATE_THRESHOLD = 0.18f
        private const val KICK_RISE_GAIN = 8.4f
        private const val KICK_PEAK_WEIGHT = 0.55f
        private const val RISE_TRIGGER_THRESHOLD = 0.055f
    }
}
