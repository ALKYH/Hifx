package com.example.hifx.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class StereoUtilityProcessor : BaseAudioProcessor() {
    @Volatile
    private var limiterEnabled = false

    @Volatile
    private var panBalance = 0f

    @Volatile
    private var panInvertEnabled = false

    @Volatile
    private var monoEnabled = false

    @Volatile
    private var vocalRemovalEnabled = false

    @Volatile
    private var phaseInvertEnabled = false

    @Volatile
    private var rightChannelPhaseInvertEnabled = false

    @Volatile
    private var crossfeedMix = 0f

    private var limiterGain = 1f

    fun updateConfig(
        limiterEnabled: Boolean,
        panBalance: Float,
        panInvertEnabled: Boolean,
        monoEnabled: Boolean,
        vocalRemovalEnabled: Boolean,
        phaseInvertEnabled: Boolean,
        rightChannelPhaseInvertEnabled: Boolean,
        crossfeedMix: Float
    ) {
        this.limiterEnabled = limiterEnabled
        this.panBalance = panBalance.coerceIn(-1f, 1f)
        this.panInvertEnabled = panInvertEnabled
        this.monoEnabled = monoEnabled
        this.vocalRemovalEnabled = vocalRemovalEnabled
        this.phaseInvertEnabled = phaseInvertEnabled
        this.rightChannelPhaseInvertEnabled = rightChannelPhaseInvertEnabled
        this.crossfeedMix = crossfeedMix.coerceIn(0f, 1f)
        if (!limiterEnabled) {
            limiterGain = 1f
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }
        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val applyAny =
            limiterEnabled || abs(panBalance) > 0.0001f || panInvertEnabled || monoEnabled ||
                vocalRemovalEnabled || phaseInvertEnabled || rightChannelPhaseInvertEnabled || crossfeedMix > 0.0001f
        if (!applyAny) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val limiterThreshold = 0.93f
        val limiterRelease = 0.003f
        while (inputBuffer.remaining() >= 4) {
            var left = shortToFloat(inputBuffer.short)
            var right = shortToFloat(inputBuffer.short)

            if (abs(panBalance) > 0.0001f) {
                val leftGain = if (panBalance > 0f) (1f - panBalance) else 1f
                val rightGain = if (panBalance < 0f) (1f + panBalance) else 1f
                left *= leftGain
                right *= rightGain
            }

            if (panInvertEnabled) {
                val swapped = left
                left = right
                right = swapped
            }

            if (crossfeedMix > 0.0001f) {
                val dry = 1f - crossfeedMix
                val mixedLeft = left * dry + right * crossfeedMix
                val mixedRight = right * dry + left * crossfeedMix
                left = mixedLeft
                right = mixedRight
            }

            if (monoEnabled) {
                val mono = (left + right) * 0.5f
                left = mono
                right = mono
            }

            if (vocalRemovalEnabled) {
                val removed = ((right - left) * 0.5f).coerceIn(-1f, 1f)
                left = removed
                right = removed
            }

            if (phaseInvertEnabled) {
                left = -left
                right = -right
            }

            if (rightChannelPhaseInvertEnabled) {
                right = -right
            }

            if (limiterEnabled) {
                val peak = max(abs(left), abs(right))
                val targetGain = if (peak > limiterThreshold) {
                    (limiterThreshold / peak).coerceIn(0.05f, 1f)
                } else {
                    1f
                }
                limiterGain = if (targetGain < limiterGain) {
                    targetGain
                } else {
                    limiterGain + (targetGain - limiterGain) * limiterRelease
                }
                left *= limiterGain
                right *= limiterGain
            }

            outputBuffer.putShort(floatToShort(left))
            outputBuffer.putShort(floatToShort(right))
        }
        outputBuffer.flip()
    }

    override fun onFlush() {
        limiterGain = 1f
    }

    override fun onReset() {
        limiterGain = 1f
        limiterEnabled = false
        panBalance = 0f
        panInvertEnabled = false
        monoEnabled = false
        vocalRemovalEnabled = false
        phaseInvertEnabled = false
        rightChannelPhaseInvertEnabled = false
        crossfeedMix = 0f
    }

    private fun shortToFloat(value: Short): Float {
        return (value.toInt() / 32768f).coerceIn(-1f, 1f)
    }

    private fun floatToShort(value: Float): Short {
        val clamped = (max(-1f, min(1f, value)) * 32767f).toInt()
        return clamped.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
