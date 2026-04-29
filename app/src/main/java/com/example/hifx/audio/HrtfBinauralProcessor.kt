package com.example.hifx.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

internal data class HrtfRenderConfig(
    val enabled: Boolean = true,
    val spatialEnabled: Boolean = false,
    val channelSeparated: Boolean = false,
    val leftXNorm: Float = 0f,
    val leftYNorm: Float = 0f,
    val leftZNorm: Float = 0f,
    val rightXNorm: Float = 0f,
    val rightYNorm: Float = 0f,
    val rightZNorm: Float = 0f,
    val leftDistanceMeters: Float = 1f,
    val rightDistanceMeters: Float = 1f,
    val roomDampingNorm: Float = 0.35f,
    val wetMixNorm: Float = 0.4f,
    val headRadiusMeters: Float = 0.087f,
    val blend: Float = 0.78f,
    val crossfeed: Float = 0.28f,
    val externalization: Float = 0.55f,
    val useHrtfDatabase: Boolean = true,
    val surroundMode: Int = 0,
    val frontLeftGain: Float = 1f,
    val frontRightGain: Float = 1f,
    val centerGain: Float = 1f,
    val lfeGain: Float = 1f,
    val surroundLeftGain: Float = 1f,
    val surroundRightGain: Float = 1f,
    val rearLeftGain: Float = 1f,
    val rearRightGain: Float = 1f
)

private data class HrtfBin(
    val itdScale: Float,
    val farShadowDb: Float,
    val farCutoffHz: Float,
    val farNotchHz: Float,
    val farNotchDepthDb: Float
)

private data class BiquadCoefficients(
    val b0: Float,
    val b1: Float,
    val b2: Float,
    val a1: Float,
    val a2: Float
)

private data class HrtfCoefficients(
    val leftDelaySamples: Float,
    val rightDelaySamples: Float,
    val leftGain: Float,
    val rightGain: Float,
    val leftLowGain: Float,
    val rightLowGain: Float,
    val leftHighGain: Float,
    val rightHighGain: Float,
    val leftLpA: Float,
    val rightLpA: Float,
    val crossfeedAmount: Float,
    val crossfeedDelaySamples: Float,
    val earlyMix: Float,
    val earlyDelaySamples: Float,
    val azimuthPan: Float,
    val lateralFocus: Float,
    val leftNotch: BiquadCoefficients,
    val rightNotch: BiquadCoefficients,
    val leftNotchMix: Float,
    val rightNotchMix: Float
)

private data class HrtfRuntimeCache(
    val surroundMode: Int,
    val frontLeftCoeff: HrtfCoefficients,
    val frontRightCoeff: HrtfCoefficients,
    val centerCoeff: HrtfCoefficients?,
    val lfeCoeff: HrtfCoefficients?,
    val surroundLeftCoeff: HrtfCoefficients?,
    val surroundRightCoeff: HrtfCoefficients?,
    val rearLeftCoeff: HrtfCoefficients?,
    val rearRightCoeff: HrtfCoefficients?,
    val frontLeftChannelGain: Float,
    val frontRightChannelGain: Float,
    val centerChannelGain: Float,
    val lfeChannelGain: Float,
    val surroundLeftChannelGain: Float,
    val surroundRightChannelGain: Float,
    val rearLeftChannelGain: Float,
    val rearRightChannelGain: Float,
    val blend: Float,
    val headroom: Float,
    val bassManagementCoeff: Float,
    val centerHpCoeff: Float,
    val centerLpCoeff: Float,
    val surroundHpCoeff: Float,
    val surroundLpCoeff: Float,
    val rearHpCoeff: Float,
    val rearLpCoeff: Float,
    val sideGain: Float,
    val modeCompensation: Float
)

private class SourceRenderState {
    val sourceDelay = FloatArray(8192)
    val leftHistory = FloatArray(4096)
    val rightHistory = FloatArray(4096)
    var sourceWriteIndex = 0
    var earWriteIndex = 0
    var leftLpState = 0f
    var rightLpState = 0f
    var leftNotchX1 = 0f
    var leftNotchX2 = 0f
    var leftNotchY1 = 0f
    var leftNotchY2 = 0f
    var rightNotchX1 = 0f
    var rightNotchX2 = 0f
    var rightNotchY1 = 0f
    var rightNotchY2 = 0f

    fun clear() {
        sourceDelay.fill(0f)
        leftHistory.fill(0f)
        rightHistory.fill(0f)
        sourceWriteIndex = 0
        earWriteIndex = 0
        leftLpState = 0f
        rightLpState = 0f
        leftNotchX1 = 0f
        leftNotchX2 = 0f
        leftNotchY1 = 0f
        leftNotchY2 = 0f
        rightNotchX1 = 0f
        rightNotchX2 = 0f
        rightNotchY1 = 0f
        rightNotchY2 = 0f
    }
}

internal class HrtfBinauralProcessor : BaseAudioProcessor() {
    @Volatile
    private var config = HrtfRenderConfig()
    @Volatile
    private var pendingStateReset = false

    private var wasProcessing = false

    private val leftSourceState = SourceRenderState()
    private val rightSourceState = SourceRenderState()
    private val centerSourceState = SourceRenderState()
    private val lfeSourceState = SourceRenderState()
    private val surroundLeftState = SourceRenderState()
    private val surroundRightState = SourceRenderState()
    private val rearLeftState = SourceRenderState()
    private val rearRightState = SourceRenderState()

    private var lfeMonoState = 0f
    private var lfeSecondState = 0f
    private var bassMgmtLeftState = 0f
    private var bassMgmtRightState = 0f
    private var centerHighPassState = 0f
    private var centerBandLowPassState = 0f
    private var surroundHighPassState = 0f
    private var surroundBandLowPassState = 0f
    private var rearHighPassState = 0f
    private var rearBandLowPassState = 0f
    private var limiterGain = 1f
    private var wetAutoGain = 1f
    @Volatile
    private var configuredSampleRate = 0
    @Volatile
    private var configVersion = 0
    private var cachedRuntimeVersion = -1
    private var cachedRuntimeSampleRate = 0
    private var runtimeCache: HrtfRuntimeCache? = null

    fun updateConfig(newConfig: HrtfRenderConfig) {
        val previousConfig = config
        val previousProcessing =
            previousConfig.enabled && previousConfig.spatialEnabled && previousConfig.blend > 0.001f

        val clampedLeftX = newConfig.leftXNorm.coerceIn(-1f, 1f)
        val clampedLeftY = newConfig.leftYNorm.coerceIn(-1f, 1f)
        val clampedLeftZ = newConfig.leftZNorm.coerceIn(-1f, 1f)
        val clampedRightX = newConfig.rightXNorm.coerceIn(-1f, 1f)
        val clampedRightY = newConfig.rightYNorm.coerceIn(-1f, 1f)
        val clampedRightZ = newConfig.rightZNorm.coerceIn(-1f, 1f)

        val updated = newConfig.copy(
            leftXNorm = clampedLeftX,
            leftYNorm = clampedLeftY,
            leftZNorm = clampedLeftZ,
            rightXNorm = clampedRightX,
            rightYNorm = clampedRightY,
            rightZNorm = clampedRightZ,
            leftDistanceMeters = newConfig.leftDistanceMeters.coerceIn(0.07f, MAX_SOURCE_DISTANCE_METERS),
            rightDistanceMeters = newConfig.rightDistanceMeters.coerceIn(0.07f, MAX_SOURCE_DISTANCE_METERS),
            roomDampingNorm = newConfig.roomDampingNorm.coerceIn(0f, 1f),
            wetMixNorm = newConfig.wetMixNorm.coerceIn(0f, 1f),
            headRadiusMeters = newConfig.headRadiusMeters.coerceIn(0.07f, 0.11f),
            blend = newConfig.blend.coerceIn(0f, 1f),
            crossfeed = newConfig.crossfeed.coerceIn(0f, 1f),
            externalization = newConfig.externalization.coerceIn(0f, 1f),
            surroundMode = newConfig.surroundMode.coerceIn(0, 2),
            frontLeftGain = newConfig.frontLeftGain.coerceIn(0f, 2f),
            frontRightGain = newConfig.frontRightGain.coerceIn(0f, 2f),
            centerGain = newConfig.centerGain.coerceIn(0f, 2f),
            lfeGain = newConfig.lfeGain.coerceIn(0f, 2f),
            surroundLeftGain = newConfig.surroundLeftGain.coerceIn(0f, 2f),
            surroundRightGain = newConfig.surroundRightGain.coerceIn(0f, 2f),
            rearLeftGain = newConfig.rearLeftGain.coerceIn(0f, 2f),
            rearRightGain = newConfig.rearRightGain.coerceIn(0f, 2f)
        )
        config = updated
        configVersion += 1
        val nextProcessing = updated.enabled && updated.spatialEnabled && updated.blend > 0.001f
        if (previousProcessing != nextProcessing) {
            pendingStateReset = true
        }
        if (configuredSampleRate > 0) {
            rebuildRuntimeCache(updated, configuredSampleRate)
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        configuredSampleRate = inputAudioFormat.sampleRate
        rebuildRuntimeCache(config, configuredSampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }
        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        if (pendingStateReset) {
            clearInternalState()
            resetDynamicsState()
            pendingStateReset = false
            wasProcessing = false
        }

        val localConfig = config
        val shouldProcess = localConfig.enabled && localConfig.spatialEnabled && localConfig.blend > 0.001f
        if (shouldProcess != wasProcessing) {
            clearInternalState()
            resetDynamicsState()
            wasProcessing = shouldProcess
        }
        if (!shouldProcess) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }
        val runtime = ensureRuntimeCache(localConfig) ?: run {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val surroundMode = runtime.surroundMode
        val frontLeftCoeff = runtime.frontLeftCoeff
        val frontRightCoeff = runtime.frontRightCoeff
        val centerCoeff = runtime.centerCoeff
        val lfeCoeff = runtime.lfeCoeff
        val surroundLeftCoeff = runtime.surroundLeftCoeff
        val surroundRightCoeff = runtime.surroundRightCoeff
        val rearLeftCoeff = runtime.rearLeftCoeff
        val rearRightCoeff = runtime.rearRightCoeff
        val frontLeftChannelGain = runtime.frontLeftChannelGain
        val frontRightChannelGain = runtime.frontRightChannelGain
        val centerChannelGain = runtime.centerChannelGain
        val lfeChannelGain = runtime.lfeChannelGain
        val surroundLeftChannelGain = runtime.surroundLeftChannelGain
        val surroundRightChannelGain = runtime.surroundRightChannelGain
        val rearLeftChannelGain = runtime.rearLeftChannelGain
        val rearRightChannelGain = runtime.rearRightChannelGain
        val blend = runtime.blend
        val wetSourceGain = 0.62f
        val headroom = runtime.headroom
        val limiterThreshold = 0.90f
        val limiterRelease = 0.0042f
        val wetLimiterThreshold = 0.72f
        val wetLimiterAttack = 0.22f
        val wetLimiterRelease = 0.0018f
        val sampleRate = configuredSampleRate.coerceAtLeast(1)
        val bassManagementCoeff = runtime.bassManagementCoeff
        val centerHpCoeff = runtime.centerHpCoeff
        val centerLpCoeff = runtime.centerLpCoeff
        val surroundHpCoeff = runtime.surroundHpCoeff
        val surroundLpCoeff = runtime.surroundLpCoeff
        val rearHpCoeff = runtime.rearHpCoeff
        val rearLpCoeff = runtime.rearLpCoeff
        while (inputBuffer.remaining() >= 4) {
            val inLeft = shortToFloat(inputBuffer.short)
            val inRight = shortToFloat(inputBuffer.short)
            var wetOutLeft: Float
            var wetOutRight: Float
            if (surroundMode == 0) {
                val leftSourceWet = renderSource(
                    inputSample = inLeft,
                    coeff = frontLeftCoeff,
                    state = leftSourceState
                )
                val rightSourceWet = renderSource(
                    inputSample = inRight,
                    coeff = frontRightCoeff,
                    state = rightSourceState
                )
                wetOutLeft = (
                    leftSourceWet.first * frontLeftChannelGain +
                        rightSourceWet.first * frontRightChannelGain
                    ) * wetSourceGain
                wetOutRight = (
                    leftSourceWet.second * frontLeftChannelGain +
                        rightSourceWet.second * frontRightChannelGain
                    ) * wetSourceGain
            } else {
                bassMgmtLeftState = onePoleLowpass(inLeft, bassMgmtLeftState, bassManagementCoeff)
                bassMgmtRightState = onePoleLowpass(inRight, bassMgmtRightState, bassManagementCoeff)
                val frontLeftHigh = inLeft - bassMgmtLeftState
                val frontRightHigh = inRight - bassMgmtRightState
                val frontBassMono = (bassMgmtLeftState + bassMgmtRightState) * 0.5f

                val mono = (inLeft + inRight) * 0.5f
                centerHighPassState = onePoleLowpass(mono, centerHighPassState, centerHpCoeff)
                val centerHighPassed = mono - centerHighPassState
                centerBandLowPassState = onePoleLowpass(centerHighPassed, centerBandLowPassState, centerLpCoeff)
                val centerBand = centerBandLowPassState

                val ambience = (inLeft - inRight) * 0.5f
                surroundHighPassState = onePoleLowpass(ambience, surroundHighPassState, surroundHpCoeff)
                val surroundHighPassed = ambience - surroundHighPassState
                surroundBandLowPassState = onePoleLowpass(surroundHighPassed, surroundBandLowPassState, surroundLpCoeff)
                val surroundBand = surroundBandLowPassState

                val rearSeed = surroundBand * 0.68f + (mono - centerBand) * 0.32f
                rearHighPassState = onePoleLowpass(rearSeed, rearHighPassState, rearHpCoeff)
                val rearHighPassed = rearSeed - rearHighPassState
                rearBandLowPassState = onePoleLowpass(rearHighPassed, rearBandLowPassState, rearLpCoeff)
                val rearBand = rearBandLowPassState

                val centerIn = centerBand * 0.96f
                val lfeIn = extractLfe(frontBassMono * 1.05f, sampleRate) * 0.92f
                val frontLeftIn = frontLeftHigh * 0.88f + centerBand * 0.18f
                val frontRightIn = frontRightHigh * 0.88f + centerBand * 0.18f
                val surroundLeftIn = surroundBand * 0.92f + rearBand * 0.08f
                val surroundRightIn = -surroundBand * 0.92f - rearBand * 0.08f
                val rearLeftIn = rearBand * 0.82f + surroundBand * 0.10f
                val rearRightIn = -rearBand * 0.82f - surroundBand * 0.10f

                var accumulatorLeft = 0f
                var accumulatorRight = 0f

                fun mixSpeaker(sample: Float, coeff: HrtfCoefficients?, state: SourceRenderState, gain: Float) {
                    if (coeff == null || gain <= 0f || abs(sample) < 1e-5f) return
                    val rendered = renderSource(sample, coeff, state)
                    accumulatorLeft += rendered.first * gain
                    accumulatorRight += rendered.second * gain
                }

                mixSpeaker(frontLeftIn, frontLeftCoeff, leftSourceState, 0.66f * frontLeftChannelGain)
                mixSpeaker(frontRightIn, frontRightCoeff, rightSourceState, 0.66f * frontRightChannelGain)
                mixSpeaker(centerIn, centerCoeff, centerSourceState, 0.56f * centerChannelGain)
                mixSpeaker(lfeIn, lfeCoeff, lfeSourceState, 0.34f * lfeChannelGain)
                val sideGain = runtime.sideGain
                mixSpeaker(
                    surroundLeftIn,
                    surroundLeftCoeff,
                    surroundLeftState,
                    sideGain * surroundLeftChannelGain
                )
                mixSpeaker(
                    surroundRightIn,
                    surroundRightCoeff,
                    surroundRightState,
                    sideGain * surroundRightChannelGain
                )
                if (surroundMode == 2) {
                    mixSpeaker(rearLeftIn, rearLeftCoeff, rearLeftState, 0.30f * rearLeftChannelGain)
                    mixSpeaker(rearRightIn, rearRightCoeff, rearRightState, 0.30f * rearRightChannelGain)
                }

                val modeCompensation = runtime.modeCompensation
                wetOutLeft = accumulatorLeft * wetSourceGain * modeCompensation
                wetOutRight = accumulatorRight * wetSourceGain * modeCompensation
            }

            val wetPeak = max(abs(wetOutLeft), abs(wetOutRight))
            val wetTargetGain = if (wetPeak > wetLimiterThreshold) {
                (wetLimiterThreshold / wetPeak).coerceIn(0.05f, 1f)
            } else {
                1f
            }
            wetAutoGain = if (wetTargetGain < wetAutoGain) {
                wetAutoGain + (wetTargetGain - wetAutoGain) * wetLimiterAttack
            } else {
                wetAutoGain + (wetTargetGain - wetAutoGain) * wetLimiterRelease
            }
            wetOutLeft *= wetAutoGain
            wetOutRight *= wetAutoGain

            val mixedLeft = (inLeft * (1f - blend) + wetOutLeft * blend) * headroom
            val mixedRight = (inRight * (1f - blend) + wetOutRight * blend) * headroom

            val safeMixedLeft = sanitizeSample(mixedLeft)
            val safeMixedRight = sanitizeSample(mixedRight)
            val peak = max(abs(safeMixedLeft), abs(safeMixedRight))
            val targetLimiterGain = if (peak > limiterThreshold) {
                (limiterThreshold / peak).coerceIn(0.05f, 1f)
            } else {
                1f
            }
            limiterGain = if (targetLimiterGain < limiterGain) {
                targetLimiterGain
            } else {
                limiterGain + (targetLimiterGain - limiterGain) * limiterRelease
            }

            val outLeft = softSaturate(safeMixedLeft * limiterGain)
            val outRight = softSaturate(safeMixedRight * limiterGain)

            outputBuffer.putShort(floatToShort(outLeft))
            outputBuffer.putShort(floatToShort(outRight))
        }
        outputBuffer.flip()
    }

    override fun onFlush() {
        clearInternalState()
        resetDynamicsState()
        wasProcessing = false
        pendingStateReset = false
    }

    override fun onReset() {
        clearInternalState()
        resetDynamicsState()
        wasProcessing = false
        pendingStateReset = false
        configuredSampleRate = 0
        cachedRuntimeVersion = -1
        cachedRuntimeSampleRate = 0
        runtimeCache = null
    }

    private fun ensureRuntimeCache(localConfig: HrtfRenderConfig): HrtfRuntimeCache? {
        val sampleRate = configuredSampleRate
        if (sampleRate <= 0) {
            return null
        }
        if (cachedRuntimeVersion != configVersion || cachedRuntimeSampleRate != sampleRate || runtimeCache == null) {
            rebuildRuntimeCache(localConfig, sampleRate)
        }
        return runtimeCache
    }

    private fun rebuildRuntimeCache(localConfig: HrtfRenderConfig, sampleRate: Int) {
        if (sampleRate <= 0) {
            runtimeCache = null
            cachedRuntimeVersion = -1
            cachedRuntimeSampleRate = 0
            return
        }
        val surroundMode = localConfig.surroundMode.coerceIn(0, 2)
        val stereoLeftCoeff = buildCoefficients(
            xNorm = localConfig.leftXNorm,
            yNorm = localConfig.leftYNorm,
            zNorm = localConfig.leftZNorm,
            config = localConfig,
            sampleRate = sampleRate,
            sourceDistanceMeters = localConfig.leftDistanceMeters
        )
        val stereoRightCoeff = buildCoefficients(
            xNorm = localConfig.rightXNorm,
            yNorm = localConfig.rightYNorm,
            zNorm = localConfig.rightZNorm,
            config = localConfig,
            sampleRate = sampleRate,
            sourceDistanceMeters = localConfig.rightDistanceMeters
        )
        val frontDistanceMeters = (
            (localConfig.leftDistanceMeters + localConfig.rightDistanceMeters) * 0.5f
            ).coerceIn(0.07f, MAX_SOURCE_DISTANCE_METERS)
        fun coeffFromAzimuth(
            azimuthDeg: Float,
            yNorm: Float = 0f,
            distanceMeters: Float = frontDistanceMeters
        ): HrtfCoefficients {
            val radians = Math.toRadians(azimuthDeg.toDouble())
            return buildCoefficients(
                xNorm = sin(radians).toFloat().coerceIn(-1f, 1f),
                yNorm = yNorm.coerceIn(-1f, 1f),
                zNorm = cos(radians).toFloat().coerceIn(-1f, 1f),
                config = localConfig,
                sampleRate = sampleRate,
                sourceDistanceMeters = distanceMeters.coerceIn(0.07f, MAX_SOURCE_DISTANCE_METERS)
            )
        }

        val frontLeftCoeff = if (surroundMode == 0) stereoLeftCoeff else coeffFromAzimuth(-30f)
        val frontRightCoeff = if (surroundMode == 0) stereoRightCoeff else coeffFromAzimuth(30f)
        val centerCoeff = if (surroundMode != 0) {
            coeffFromAzimuth(0f, yNorm = 0.02f, distanceMeters = frontDistanceMeters * 0.98f)
        } else {
            null
        }
        val lfeCoeff = if (surroundMode != 0) {
            coeffFromAzimuth(0f, yNorm = -0.12f, distanceMeters = frontDistanceMeters * 1.06f)
        } else {
            null
        }
        val sideAzimuth = if (surroundMode == 1) 110f else 90f
        val surroundLeftCoeff = if (surroundMode != 0) {
            coeffFromAzimuth(-sideAzimuth, distanceMeters = frontDistanceMeters * 1.08f)
        } else {
            null
        }
        val surroundRightCoeff = if (surroundMode != 0) {
            coeffFromAzimuth(sideAzimuth, distanceMeters = frontDistanceMeters * 1.08f)
        } else {
            null
        }
        val rearLeftCoeff = if (surroundMode == 2) {
            coeffFromAzimuth(-150f, distanceMeters = frontDistanceMeters * 1.14f)
        } else {
            null
        }
        val rearRightCoeff = if (surroundMode == 2) {
            coeffFromAzimuth(150f, distanceMeters = frontDistanceMeters * 1.14f)
        } else {
            null
        }
        runtimeCache = HrtfRuntimeCache(
            surroundMode = surroundMode,
            frontLeftCoeff = frontLeftCoeff,
            frontRightCoeff = frontRightCoeff,
            centerCoeff = centerCoeff,
            lfeCoeff = lfeCoeff,
            surroundLeftCoeff = surroundLeftCoeff,
            surroundRightCoeff = surroundRightCoeff,
            rearLeftCoeff = rearLeftCoeff,
            rearRightCoeff = rearRightCoeff,
            frontLeftChannelGain = localConfig.frontLeftGain.coerceIn(0f, 2f),
            frontRightChannelGain = localConfig.frontRightGain.coerceIn(0f, 2f),
            centerChannelGain = localConfig.centerGain.coerceIn(0f, 2f),
            lfeChannelGain = localConfig.lfeGain.coerceIn(0f, 2f),
            surroundLeftChannelGain = localConfig.surroundLeftGain.coerceIn(0f, 2f),
            surroundRightChannelGain = localConfig.surroundRightGain.coerceIn(0f, 2f),
            rearLeftChannelGain = localConfig.rearLeftGain.coerceIn(0f, 2f),
            rearRightChannelGain = localConfig.rearRightGain.coerceIn(0f, 2f),
            blend = localConfig.blend.coerceIn(0f, 1f),
            headroom = (1f - 0.26f * localConfig.blend.coerceIn(0f, 1f)).coerceIn(0.68f, 1f),
            bassManagementCoeff = if (surroundMode != 0) onePoleCoefficient(90f, sampleRate) else 0f,
            centerHpCoeff = if (surroundMode != 0) onePoleCoefficient(140f, sampleRate) else 0f,
            centerLpCoeff = if (surroundMode != 0) onePoleCoefficient(6800f, sampleRate) else 0f,
            surroundHpCoeff = if (surroundMode != 0) onePoleCoefficient(180f, sampleRate) else 0f,
            surroundLpCoeff = if (surroundMode != 0) onePoleCoefficient(7200f, sampleRate) else 0f,
            rearHpCoeff = if (surroundMode != 0) onePoleCoefficient(220f, sampleRate) else 0f,
            rearLpCoeff = if (surroundMode != 0) onePoleCoefficient(5600f, sampleRate) else 0f,
            sideGain = if (surroundMode == 2) 0.36f else 0.50f,
            modeCompensation = if (surroundMode == 2) 0.62f else 0.70f
        )
        cachedRuntimeVersion = configVersion
        cachedRuntimeSampleRate = sampleRate
    }

    private fun clearInternalState() {
        leftSourceState.clear()
        rightSourceState.clear()
        centerSourceState.clear()
        lfeSourceState.clear()
        surroundLeftState.clear()
        surroundRightState.clear()
        rearLeftState.clear()
        rearRightState.clear()
        lfeMonoState = 0f
        lfeSecondState = 0f
        bassMgmtLeftState = 0f
        bassMgmtRightState = 0f
        centerHighPassState = 0f
        centerBandLowPassState = 0f
        surroundHighPassState = 0f
        surroundBandLowPassState = 0f
        rearHighPassState = 0f
        rearBandLowPassState = 0f
    }

    private fun resetDynamicsState() {
        limiterGain = 1f
        wetAutoGain = 1f
    }

    private fun extractLfe(mono: Float, sampleRate: Int): Float {
        val cutoff = 120f
        val coefficient = onePoleCoefficient(cutoff, sampleRate)
        lfeMonoState = onePoleLowpass(mono, lfeMonoState, coefficient)
        lfeSecondState = onePoleLowpass(lfeMonoState, lfeSecondState, coefficient)
        return lfeSecondState
    }

    private fun renderSource(
        inputSample: Float,
        coeff: HrtfCoefficients,
        state: SourceRenderState
    ): Pair<Float, Float> {
        state.sourceDelay[state.sourceWriteIndex] = inputSample

        val delayedLeft = readRing(state.sourceDelay, state.sourceWriteIndex, coeff.leftDelaySamples)
        val delayedRight = readRing(state.sourceDelay, state.sourceWriteIndex, coeff.rightDelaySamples)

        state.leftLpState = onePoleLowpass(delayedLeft, state.leftLpState, coeff.leftLpA)
        state.rightLpState = onePoleLowpass(delayedRight, state.rightLpState, coeff.rightLpA)
        val leftHigh = delayedLeft - state.leftLpState
        val rightHigh = delayedRight - state.rightLpState

        var shapedLeft =
            (state.leftLpState * coeff.leftLowGain + leftHigh * coeff.leftHighGain) * coeff.leftGain
        var shapedRight =
            (state.rightLpState * coeff.rightLowGain + rightHigh * coeff.rightHighGain) * coeff.rightGain

        val mid = (shapedLeft + shapedRight) * 0.5f
        val side = (shapedLeft - shapedRight) * 0.5f
        val sideGain = (1f + coeff.lateralFocus).coerceIn(1f, 1.9f)
        shapedLeft = mid + side * sideGain
        shapedRight = mid - side * sideGain

        shapedLeft = applyLeftNotch(shapedLeft, coeff, state)
        shapedRight = applyRightNotch(shapedRight, coeff, state)

        state.leftHistory[state.earWriteIndex] = shapedLeft
        state.rightHistory[state.earWriteIndex] = shapedRight
        val crossfedToLeft = readRing(state.rightHistory, state.earWriteIndex, coeff.crossfeedDelaySamples)
        val crossfedToRight = readRing(state.leftHistory, state.earWriteIndex, coeff.crossfeedDelaySamples)
        val early = readRing(state.sourceDelay, state.sourceWriteIndex, coeff.earlyDelaySamples)

        shapedLeft += crossfedToLeft * coeff.crossfeedAmount
        shapedRight += crossfedToRight * coeff.crossfeedAmount
        shapedLeft += early * coeff.earlyMix * (1f - coeff.azimuthPan * 0.2f)
        shapedRight += early * coeff.earlyMix * (1f + coeff.azimuthPan * 0.2f)

        state.sourceWriteIndex = (state.sourceWriteIndex + 1) % state.sourceDelay.size
        state.earWriteIndex = (state.earWriteIndex + 1) % state.leftHistory.size
        return shapedLeft to shapedRight
    }

    private fun buildCoefficients(
        xNorm: Float,
        yNorm: Float,
        zNorm: Float,
        config: HrtfRenderConfig,
        sampleRate: Int,
        sourceDistanceMeters: Float
    ): HrtfCoefficients {
        val azimuth = atan2(xNorm.toDouble(), zNorm.toDouble()).toFloat()
        val absAzimuthRad = abs(azimuth).coerceIn(0f, PI.toFloat())
        val absAzimuthDeg = Math.toDegrees(absAzimuthRad.toDouble()).toFloat().coerceIn(0f, 180f)
        val profile = resolveHrtfBin(absAzimuthDeg, config.useHrtfDatabase)

        val headRadius = config.headRadiusMeters.coerceIn(0.07f, 0.11f)
        val headRadiusNorm = ((headRadius - 0.07f) / 0.04f).coerceIn(0f, 1f)
        val rigidSphereItdSec = (headRadius / SPEED_OF_SOUND_MPS) * (absAzimuthRad + sin(absAzimuthRad))
        val itdSec = (rigidSphereItdSec * profile.itdScale).coerceIn(0f, 0.001f)
        val itdSamples = itdSec * sampleRate * abs(xNorm).coerceIn(0f, 1f)
        val leftDelaySamples = (itdSamples * xNorm.coerceIn(0f, 1f)).coerceAtLeast(0f)
        val rightDelaySamples = (itdSamples * (-xNorm).coerceIn(0f, 1f)).coerceAtLeast(0f)

        val minimumDistance = (headRadius + 0.005f).coerceIn(0.07f, 0.16f)
        val distanceMeters = sourceDistanceMeters.coerceIn(minimumDistance, MAX_SOURCE_DISTANCE_METERS)
        val distanceFactor = (
            (distanceMeters - minimumDistance) /
                (MAX_SOURCE_DISTANCE_METERS - minimumDistance).coerceAtLeast(0.001f)
            ).coerceIn(0f, 1f)
        val backFactor = (-zNorm).coerceIn(0f, 1f)
        val lateral = abs(xNorm).pow(0.85f).coerceIn(0f, 1f)
        val directionality = (0.72f + config.externalization * 0.34f + config.blend * 0.24f).coerceIn(0.7f, 1.35f)
        val shadowStrength =
            (0.5f + headRadiusNorm * 0.2f + config.externalization * 0.18f).coerceIn(0.45f, 0.95f)
        val farShadowDb = (
            profile.farShadowDb * shadowStrength +
                distanceFactor * 1.3f +
                backFactor * 1.8f +
                lateral * 1.6f * directionality
            ).coerceIn(0.8f, 24f)
        val nearBoostDb = min(4.8f, 0.8f + farShadowDb * (0.32f + 0.15f * directionality))

        val ildScale = (1f + lateral * (0.22f + config.externalization * 0.28f)).coerceIn(1f, 1.72f)
        val nearGain = dbToLinear(nearBoostDb * ildScale)
        val farGain = dbToLinear(-farShadowDb * ildScale)
        val rightWeight = ((xNorm + 1f) * 0.5f).coerceIn(0f, 1f)
        val leftWeight = 1f - rightWeight
        val leftGain = nearGain * leftWeight + farGain * rightWeight
        val rightGain = nearGain * rightWeight + farGain * leftWeight

        val farCutoff = (
            profile.farCutoffHz -
                backFactor * 620f -
                distanceFactor * 520f -
                lateral * 460f -
                headRadiusNorm * 260f
            ).coerceIn(850f, 9_500f)
        val nearCutoff = (
            18_600f +
                yNorm * 2400f -
                lateral * 650f +
                headRadiusNorm * 700f
            ).coerceIn(6500f, 20_000f)
        val leftCutoff = nearCutoff * leftWeight + farCutoff * rightWeight
        val rightCutoff = nearCutoff * rightWeight + farCutoff * leftWeight

        val leftLpA = onePoleCoefficient(leftCutoff, sampleRate)
        val rightLpA = onePoleCoefficient(rightCutoff, sampleRate)
        val frontWeight = ((zNorm + 1f) * 0.5f).coerceIn(0f, 1f)

        val farNotchHz = (
            profile.farNotchHz -
                lateral * 620f -
                backFactor * 460f +
                distanceFactor * 240f +
                yNorm * 380f
            ).coerceIn(6200f, 13_000f)
        val nearNotchHz = (
            profile.farNotchHz +
                1050f +
                frontWeight * 580f -
                backFactor * 260f +
                headRadiusNorm * 260f
            ).coerceIn(7200f, 15_500f)
        val leftNotchHz = nearNotchHz * leftWeight + farNotchHz * rightWeight
        val rightNotchHz = nearNotchHz * rightWeight + farNotchHz * leftWeight
        val notchQ = (
            2.6f +
                lateral * 2.9f +
                backFactor * 0.8f +
                config.externalization * 0.7f
            ).coerceIn(2.2f, 7.2f)
        val farNotchDepthDb = (
            profile.farNotchDepthDb *
                (0.92f + config.externalization * 0.36f) +
                lateral * 1.6f +
                backFactor * 1.2f +
                distanceFactor * 0.8f
            ).coerceIn(2.5f, 18f)
        val nearNotchDepthDb = (
            0.7f +
                lateral * 1.2f +
                (1f - backFactor) * 0.5f +
                frontWeight * 0.3f
            ).coerceIn(0.4f, 3.6f)
        val leftNotchDepthDb = nearNotchDepthDb * leftWeight + farNotchDepthDb * rightWeight
        val rightNotchDepthDb = nearNotchDepthDb * rightWeight + farNotchDepthDb * leftWeight
        val leftNotchMix = (1f - dbToLinear(-leftNotchDepthDb)).coerceIn(0f, 0.92f)
        val rightNotchMix = (1f - dbToLinear(-rightNotchDepthDb)).coerceIn(0f, 0.92f)
        val leftNotch = buildNotchBiquad(leftNotchHz, notchQ, sampleRate)
        val rightNotch = buildNotchBiquad(rightNotchHz, notchQ, sampleRate)

        val farHighAttenDb = (
            2.5f +
                farShadowDb * 0.45f +
                backFactor * 2.0f +
                lateral * 1.6f
            ).coerceIn(1.2f, 19f)
        val nearHighBoostDb = (
            0.4f +
                lateral * 2.4f +
                (1f - backFactor) * 0.7f +
                headRadiusNorm * 0.6f
            ).coerceIn(0f, 5f)
        val nearHighGain = dbToLinear(nearHighBoostDb)
        val farHighGain = dbToLinear(-farHighAttenDb)
        val leftHighGain = nearHighGain * leftWeight + farHighGain * rightWeight
        val rightHighGain = nearHighGain * rightWeight + farHighGain * leftWeight

        val farLowAttenDb = -(farShadowDb * 0.2f + backFactor * 0.8f).coerceIn(0f, 4.2f)
        val nearLowBoostDb = (nearBoostDb * 0.2f + headRadiusNorm * 0.4f).coerceIn(0f, 2.2f)
        val nearLowGain = dbToLinear(nearLowBoostDb)
        val farLowGain = dbToLinear(farLowAttenDb)
        val leftLowGain = nearLowGain * leftWeight + farLowGain * rightWeight
        val rightLowGain = nearLowGain * rightWeight + farLowGain * leftWeight

        val centerWeight = 1f - (absAzimuthDeg / 180f).coerceIn(0f, 1f)
        val lateralSuppression = (1f - lateral * 0.78f).coerceIn(0.15f, 1f)
        val rearSuppression = (1f - backFactor * 0.45f).coerceIn(0.35f, 1f)
        val crossfeedAmount = config.crossfeed *
            (0.12f + 0.27f * frontWeight) *
            (0.42f + 0.58f * centerWeight) *
            lateralSuppression *
            rearSuppression
        val crossfeedDelaySamples = (sampleRate * (0.00022f + 0.00018f * frontWeight))
            .coerceIn(1f, 96f)

        val earlyMix = config.externalization *
            (0.018f + config.wetMixNorm * 0.102f) *
            (1f + backFactor * 0.44f)
        val earlyDelaySamples = (sampleRate * (0.00145f + distanceFactor * 0.0038f + backFactor * 0.0012f))
            .coerceIn(4f, 360f)
        val lateralFocus = (
            lateral *
                (0.22f + config.externalization * 0.5f + config.blend * 0.18f) *
                (0.84f + backFactor * 0.28f)
            ).coerceIn(0f, 0.88f)

        return HrtfCoefficients(
            leftDelaySamples = leftDelaySamples,
            rightDelaySamples = rightDelaySamples,
            leftGain = leftGain,
            rightGain = rightGain,
            leftLowGain = leftLowGain.coerceIn(0.25f, 2.3f),
            rightLowGain = rightLowGain.coerceIn(0.25f, 2.3f),
            leftHighGain = leftHighGain.coerceIn(0.08f, 1.9f),
            rightHighGain = rightHighGain.coerceIn(0.08f, 1.9f),
            leftLpA = leftLpA,
            rightLpA = rightLpA,
            crossfeedAmount = crossfeedAmount.coerceIn(0f, 0.42f),
            crossfeedDelaySamples = crossfeedDelaySamples,
            earlyMix = earlyMix.coerceIn(0f, 0.24f),
            earlyDelaySamples = earlyDelaySamples,
            azimuthPan = xNorm.coerceIn(-1f, 1f),
            lateralFocus = lateralFocus,
            leftNotch = leftNotch,
            rightNotch = rightNotch,
            leftNotchMix = leftNotchMix,
            rightNotchMix = rightNotchMix
        )
    }

    private fun resolveHrtfBin(absAzimuthDeg: Float, useDatabase: Boolean): HrtfBin {
        if (!useDatabase) {
            val t = (absAzimuthDeg / 180f).coerceIn(0f, 1f)
            return HrtfBin(
                itdScale = 0.95f + t * 0.1f,
                farShadowDb = 1.2f + t.pow(1.2f) * 9f,
                farCutoffHz = 11_000f - t.pow(1.35f) * 7600f,
                farNotchHz = 9_300f - t * 900f,
                farNotchDepthDb = 4f + t.pow(1.15f) * 6.4f
            )
        }
        val bins = HRTF_DB_BINS
        if (absAzimuthDeg <= bins.first()) {
            return HRTF_DB_VALUES.first()
        }
        if (absAzimuthDeg >= bins.last()) {
            return HRTF_DB_VALUES.last()
        }
        for (index in 0 until bins.lastIndex) {
            val leftAzimuth = bins[index]
            val rightAzimuth = bins[index + 1]
            if (absAzimuthDeg in leftAzimuth..rightAzimuth) {
                val ratio = ((absAzimuthDeg - leftAzimuth) / (rightAzimuth - leftAzimuth)).coerceIn(0f, 1f)
                val left = HRTF_DB_VALUES[index]
                val right = HRTF_DB_VALUES[index + 1]
                return HrtfBin(
                    itdScale = lerp(left.itdScale, right.itdScale, ratio),
                    farShadowDb = lerp(left.farShadowDb, right.farShadowDb, ratio),
                    farCutoffHz = lerp(left.farCutoffHz, right.farCutoffHz, ratio),
                    farNotchHz = lerp(left.farNotchHz, right.farNotchHz, ratio),
                    farNotchDepthDb = lerp(left.farNotchDepthDb, right.farNotchDepthDb, ratio)
                )
            }
        }
        return HRTF_DB_VALUES.last()
    }

    private fun readRing(buffer: FloatArray, writeIndex: Int, delaySamples: Float): Float {
        val clampedDelay = delaySamples.coerceIn(0f, (buffer.size - 2).toFloat())
        val baseDelay = clampedDelay.toInt()
        val fraction = clampedDelay - baseDelay
        val idxA = mod(writeIndex - baseDelay, buffer.size)
        val idxB = mod(writeIndex - baseDelay - 1, buffer.size)
        val a = buffer[idxA]
        val b = buffer[idxB]
        return a * (1f - fraction) + b * fraction
    }

    private fun applyLeftNotch(
        input: Float,
        coeff: HrtfCoefficients,
        state: SourceRenderState
    ): Float {
        val notch = coeff.leftNotch
        val filtered = notch.b0 * input +
            notch.b1 * state.leftNotchX1 +
            notch.b2 * state.leftNotchX2 -
            notch.a1 * state.leftNotchY1 -
            notch.a2 * state.leftNotchY2
        state.leftNotchX2 = state.leftNotchX1
        state.leftNotchX1 = input
        state.leftNotchY2 = state.leftNotchY1
        state.leftNotchY1 = filtered
        return input * (1f - coeff.leftNotchMix) + filtered * coeff.leftNotchMix
    }

    private fun applyRightNotch(
        input: Float,
        coeff: HrtfCoefficients,
        state: SourceRenderState
    ): Float {
        val notch = coeff.rightNotch
        val filtered = notch.b0 * input +
            notch.b1 * state.rightNotchX1 +
            notch.b2 * state.rightNotchX2 -
            notch.a1 * state.rightNotchY1 -
            notch.a2 * state.rightNotchY2
        state.rightNotchX2 = state.rightNotchX1
        state.rightNotchX1 = input
        state.rightNotchY2 = state.rightNotchY1
        state.rightNotchY1 = filtered
        return input * (1f - coeff.rightNotchMix) + filtered * coeff.rightNotchMix
    }

    private fun buildNotchBiquad(centerHz: Float, q: Float, sampleRate: Int): BiquadCoefficients {
        val frequency = centerHz.coerceIn(40f, sampleRate * 0.45f)
        val quality = q.coerceIn(0.4f, 12f)
        val omega = (2.0 * PI * frequency / sampleRate).toFloat()
        val cosine = cos(omega)
        val alpha = (sin(omega) / (2f * quality)).coerceAtLeast(0.0001f)
        val a0 = 1f + alpha
        val invA0 = 1f / a0
        val b0 = 1f * invA0
        val b1 = (-2f * cosine) * invA0
        val b2 = 1f * invA0
        val a1 = (-2f * cosine) * invA0
        val a2 = (1f - alpha) * invA0
        return BiquadCoefficients(
            b0 = b0,
            b1 = b1,
            b2 = b2,
            a1 = a1,
            a2 = a2
        )
    }

    private fun onePoleLowpass(input: Float, prev: Float, coefficientA: Float): Float {
        val a = coefficientA.coerceIn(0f, 0.9999f)
        return (1f - a) * input + a * prev
    }

    private fun onePoleCoefficient(cutoffHz: Float, sampleRate: Int): Float {
        val fc = cutoffHz.coerceIn(20f, sampleRate * 0.45f)
        val omega = -2.0 * PI * fc / sampleRate
        return exp(omega).toFloat().coerceIn(0f, 0.9999f)
    }

    private fun dbToLinear(db: Float): Float {
        return 10f.pow(db / 20f)
    }

    private fun shortToFloat(value: Short): Float {
        return (value.toInt() / 32768f).coerceIn(-1f, 1f)
    }

    private fun floatToShort(value: Float): Short {
        val clamped = (max(-1f, min(1f, value)) * 32767f).toInt()
        return clamped.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun mod(value: Int, size: Int): Int {
        val result = value % size
        return if (result < 0) result + size else result
    }

    private fun lerp(left: Float, right: Float, ratio: Float): Float {
        return left + (right - left) * ratio
    }

    private fun softSaturate(value: Float): Float {
        val drive = 1.35f
        val scaled = (value * drive).coerceIn(-4f, 4f)
        val norm = tanh(drive.toDouble()).toFloat().coerceAtLeast(0.0001f)
        return (tanh(scaled.toDouble()).toFloat() / norm).coerceIn(-1f, 1f)
    }

    private fun sanitizeSample(value: Float): Float {
        return if (value.isFinite()) value else 0f
    }

    companion object {
        private const val SPEED_OF_SOUND_MPS = 343f
        private const val MAX_SOURCE_DISTANCE_METERS = 4f
        private val HRTF_DB_BINS = floatArrayOf(0f, 15f, 30f, 45f, 60f, 75f, 90f, 120f, 150f, 180f)

        private val HRTF_DB_VALUES = arrayOf(
            HrtfBin(
                itdScale = 0.985f,
                farShadowDb = 6.103f,
                farCutoffHz = 3030.3f,
                farNotchHz = 8717.2f,
                farNotchDepthDb = 8.763f
            ),
            HrtfBin(
                itdScale = 0.944f,
                farShadowDb = 7.634f,
                farCutoffHz = 3006.4f,
                farNotchHz = 8583.6f,
                farNotchDepthDb = 12f
            ),
            HrtfBin(
                itdScale = 0.902f,
                farShadowDb = 11.967f,
                farCutoffHz = 2027.6f,
                farNotchHz = 8879.6f,
                farNotchDepthDb = 12f
            ),
            HrtfBin(
                itdScale = 0.915f,
                farShadowDb = 16.465f,
                farCutoffHz = 1700f,
                farNotchHz = 8713.1f,
                farNotchDepthDb = 9.586f
            ),
            HrtfBin(
                itdScale = 0.959f,
                farShadowDb = 19.640f,
                farCutoffHz = 1700f,
                farNotchHz = 8478.9f,
                farNotchDepthDb = 12f
            ),
            HrtfBin(
                itdScale = 0.976f,
                farShadowDb = 21.363f,
                farCutoffHz = 1700f,
                farNotchHz = 11_083.6f,
                farNotchDepthDb = 12f
            ),
            HrtfBin(
                itdScale = 0.890f,
                farShadowDb = 21.090f,
                farCutoffHz = 1700f,
                farNotchHz = 11_743.6f,
                farNotchDepthDb = 12f
            ),
            HrtfBin(
                itdScale = 0.642f,
                farShadowDb = 16.069f,
                farCutoffHz = 1700f,
                farNotchHz = 9429.4f,
                farNotchDepthDb = 12f
            ),
            HrtfBin(
                itdScale = 0.355f,
                farShadowDb = 7.090f,
                farCutoffHz = 3208.8f,
                farNotchHz = 9700.3f,
                farNotchDepthDb = 10.430f
            ),
            HrtfBin(
                itdScale = 0.202f,
                farShadowDb = 2.342f,
                farCutoffHz = 6949.8f,
                farNotchHz = 8818.7f,
                farNotchDepthDb = 6.405f
            )
        )
    }
}
