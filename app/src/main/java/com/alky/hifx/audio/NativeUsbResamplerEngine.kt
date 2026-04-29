package com.alky.hifx.audio

internal class NativeUsbResamplerEngine {
    private var handle: Long = if (NativeUsbResamplerBridge.isAvailable) {
        runCatching { NativeUsbResamplerBridge.nativeCreate() }.getOrDefault(0L)
    } else {
        0L
    }

    private var configuredInputRateHz: Int = 0
    private var configuredOutputRateHz: Int = 0
    private var configuredAlgorithm: Int = USB_RESAMPLER_ALGORITHM_LINEAR

    fun processIfNeeded(
        input: ByteArray,
        inputSampleRateHz: Int,
        outputSampleRateHz: Int,
        algorithm: Int
    ): ByteArray? {
        if (input.isEmpty() || handle == 0L) {
            return null
        }
        if (inputSampleRateHz <= 0 || outputSampleRateHz <= 0 || inputSampleRateHz == outputSampleRateHz) {
            return input
        }
        if (
            configuredInputRateHz != inputSampleRateHz ||
            configuredOutputRateHz != outputSampleRateHz ||
            configuredAlgorithm != algorithm
        ) {
            NativeUsbResamplerBridge.nativeConfigure(handle, inputSampleRateHz, outputSampleRateHz, algorithm)
            configuredInputRateHz = inputSampleRateHz
            configuredOutputRateHz = outputSampleRateHz
            configuredAlgorithm = algorithm
        }
        return runCatching {
            NativeUsbResamplerBridge.nativeProcessPcm16Stereo(handle, input)
        }.getOrNull()
    }

    fun reset() {
        val localHandle = handle
        if (localHandle != 0L) {
            runCatching { NativeUsbResamplerBridge.nativeReset(localHandle) }
        }
        configuredInputRateHz = 0
        configuredOutputRateHz = 0
        configuredAlgorithm = USB_RESAMPLER_ALGORITHM_LINEAR
    }

    fun release() {
        val localHandle = handle
        handle = 0L
        if (localHandle != 0L) {
            runCatching { NativeUsbResamplerBridge.nativeRelease(localHandle) }
        }
        configuredInputRateHz = 0
        configuredOutputRateHz = 0
        configuredAlgorithm = USB_RESAMPLER_ALGORITHM_LINEAR
    }
}

private object NativeUsbResamplerBridge {
    val isAvailable: Boolean by lazy {
        runCatching {
            System.loadLibrary("hifxaudio")
            true
        }.getOrDefault(false)
    }

    external fun nativeCreate(): Long
    external fun nativeConfigure(handle: Long, inputSampleRateHz: Int, outputSampleRateHz: Int, algorithm: Int)
    external fun nativeProcessPcm16Stereo(handle: Long, input: ByteArray): ByteArray
    external fun nativeReset(handle: Long)
    external fun nativeRelease(handle: Long)
}
