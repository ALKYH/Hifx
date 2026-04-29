package com.alky.hifx.audio

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

private const val ENABLE_EXPERIMENTAL_USB_ISO_DIRECT = true
private const val PREFER_HOST_DIRECT_ISO_ROUTE = true

internal class UsbHostPassthroughProcessor : BaseAudioProcessor() {
    @Volatile
    private var sink: UsbPcmOutputBackend? = null
    private var configuredFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET

    fun attachSink(output: UsbPcmOutputBackend?) {
        sink = output
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configuredFormat = inputAudioFormat
        return if (
            inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.channelCount == 2
        ) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }
        val remaining = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(remaining)
        val mirrorBytes = ByteArray(remaining)
        val mirror = inputBuffer.duplicate()
        mirror.get(mirrorBytes)
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
        sink?.writePcm(mirrorBytes, configuredFormat)
    }

    override fun onFlush() = Unit

    override fun onReset() {
        sink = null
        configuredFormat = AudioProcessor.AudioFormat.NOT_SET
    }
}

internal interface UsbPcmOutputBackend {
    val lastError: String
    fun start(device: UsbDevice, route: UsbOutputRoute): Boolean
    fun setTransportConfig(config: UsbTransportConfig)
    fun writePcm(bytes: ByteArray, audioFormat: AudioProcessor.AudioFormat): Boolean
    fun isOperational(): Boolean
    fun isForTarget(device: UsbDevice, route: UsbOutputRoute?): Boolean
    fun debugStatus(): String
    fun close()
}

internal data class UsbTransportConfig(
    val targetSampleRateHz: Int?,
    val targetBitDepth: Int?,
    val resampleAlgorithm: Int
)

internal data class UsbMappedTransportFormat(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val encoding: Int,
    val note: String
)

private data class NativeUsbQueuedPacket(
    val bytes: ByteArray,
    val inputSampleRateHz: Int,
    val outputSampleRateHz: Int,
    val resampleAlgorithm: Int
)

internal const val USB_RESAMPLER_ALGORITHM_NEAREST = 0
internal const val USB_RESAMPLER_ALGORITHM_LINEAR = 1
internal const val USB_RESAMPLER_ALGORITHM_CUBIC = 2
internal const val USB_RESAMPLER_ALGORITHM_SOXR_HQ = 3

internal class UsbHostDirectOutput(
    private val context: Context
) : UsbPcmOutputBackend {
    @Volatile
    override var lastError: String = ""
        private set

    @Volatile
    private var running = false

    private val packetQueue = ArrayBlockingQueue<ByteArray>(24)
    private var worker: Thread? = null
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var outEndpoint: UsbEndpoint? = null
    private var currentDeviceId: Int? = null
    private var currentRouteKey: String? = null
    private var transportConfig: UsbTransportConfig = UsbTransportConfig(null, null, USB_RESAMPLER_ALGORITHM_LINEAR)
    private val resampler = NativeUsbResamplerEngine()

    override fun start(device: UsbDevice, route: UsbOutputRoute): Boolean {
        close()
        val usbManager = context.getSystemService(UsbManager::class.java)
        if (usbManager == null) {
            lastError = "UsbManager unavailable"
            return false
        }
        if (!usbManager.hasPermission(device)) {
            lastError = "No USB permission for ${device.deviceName}"
            return false
        }
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            lastError = "Failed to open USB device ${device.deviceName}"
            return false
        }
        val intf = route.usbInterface
        val endpoint = route.endpoint
        val claimed = runCatching { connection.claimInterface(intf, true) }.getOrDefault(false)
        if (!claimed) {
            connection.close()
            lastError = "Failed to claim USB interface ${intf.id}"
            return false
        }
        runCatching { connection.setInterface(intf) }

        this.connection = connection
        this.claimedInterface = intf
        this.outEndpoint = endpoint
        this.currentDeviceId = device.deviceId
        this.currentRouteKey = route.toRouteKey()
        this.running = true
        this.worker = Thread({
            pumpLoop()
        }, "hifx-usb-host-pcm").also { it.start() }
        lastError = ""
        return true
    }

    override fun writePcm(bytes: ByteArray, audioFormat: AudioProcessor.AudioFormat): Boolean {
        if (!running) {
            return false
        }
        if (audioFormat.encoding != C.ENCODING_PCM_16BIT || audioFormat.channelCount != 2) {
            return false
        }
        val mapped = mapUsbTransportFormat(audioFormat, transportConfig)
        val payload = resampler.processIfNeeded(
            input = bytes,
            inputSampleRateHz = audioFormat.sampleRate,
            outputSampleRateHz = mapped.sampleRateHz,
            algorithm = transportConfig.resampleAlgorithm
        ) ?: bytes
        if (!packetQueue.offer(payload)) {
            packetQueue.poll()
            packetQueue.offer(payload)
        }
        return true
    }

    override fun setTransportConfig(config: UsbTransportConfig) {
        transportConfig = config
        resampler.reset()
    }

    override fun close() {
        running = false
        worker?.interrupt()
        worker = null
        packetQueue.clear()

        val connection = this.connection
        val intf = this.claimedInterface
        if (connection != null && intf != null) {
            runCatching { connection.releaseInterface(intf) }
        }
        runCatching { connection?.close() }
        this.connection = null
        this.claimedInterface = null
        this.outEndpoint = null
        this.currentDeviceId = null
        this.currentRouteKey = null
        resampler.release()
    }

    override fun debugStatus(): String {
        val endpoint = outEndpoint
        return "backend=UsbHostDirectOutput running=$running queue=${packetQueue.size}/24 endpointType=${endpoint?.type ?: "N/A"} maxPacket=${endpoint?.maxPacketSize ?: "N/A"} lastError=${lastError.ifBlank { "none" }}"
    }

    override fun isOperational(): Boolean = running && connection != null && outEndpoint != null

    override fun isForTarget(device: UsbDevice, route: UsbOutputRoute?): Boolean {
        return currentDeviceId == device.deviceId && currentRouteKey == route?.toRouteKey()
    }

    private fun pumpLoop() {
        while (running) {
            try {
                val packet = packetQueue.take()
                writePacket(packet)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                lastError = t.message ?: "USB host write failed"
                running = false
            }
        }
    }

    private fun writePacket(packet: ByteArray) {
        val connection = connection ?: return
        val endpoint = outEndpoint ?: return
        val chunkSize = (endpoint.maxPacketSize.coerceAtLeast(192) * 8).coerceIn(192, 8192)
        var offset = 0
        while (offset < packet.size && running) {
            val size = minOf(chunkSize, packet.size - offset)
            val sent = connection.bulkTransfer(endpoint, packet, offset, size, 100)
            if (sent <= 0) {
                lastError = "bulkTransfer failed, ret=$sent endpointType=${endpoint.type}"
                running = false
                break
            }
            offset += sent
        }
    }

}

internal class NativeUsbDirectOutput(
    private val context: Context
) : UsbPcmOutputBackend {
    @Volatile
    override var lastError: String = ""
        private set

    private var nativeHandle: Long = 0L
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var endpoint: UsbEndpoint? = null
    private var selectedRoute: UsbOutputRoute? = null
    private var currentDeviceId: Int? = null
    @Volatile
    private var running = false
    private var worker: Thread? = null
    private val packetQueue = ArrayBlockingQueue<NativeUsbQueuedPacket>(48)
    private val queuedBytes = AtomicInteger(0)
    private val droppedPackets = AtomicInteger(0)
    private val submittedPackets = AtomicInteger(0)
    private var transportConfig: UsbTransportConfig = UsbTransportConfig(null, null, USB_RESAMPLER_ALGORITHM_LINEAR)

    override fun start(device: UsbDevice, route: UsbOutputRoute): Boolean {
        close()
        if (!NativeUsbBridge.isAvailable) {
            lastError = "Native USB backend unavailable"
            return false
        }
        val usbManager = context.getSystemService(UsbManager::class.java)
        if (usbManager == null) {
            lastError = "UsbManager unavailable"
            return false
        }
        if (!usbManager.hasPermission(device)) {
            lastError = "No USB permission for ${device.deviceName}"
            return false
        }
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            lastError = "Failed to open USB device ${device.deviceName}"
            return false
        }
        val claimed = runCatching { connection.claimInterface(route.usbInterface, true) }.getOrDefault(false)
        if (!claimed) {
            connection.close()
            lastError = "Failed to claim USB interface ${route.usbInterface.id}"
            return false
        }
        val altApplied = runCatching { connection.setInterface(route.usbInterface) }.getOrDefault(false)
        if (!altApplied) {
            runCatching { connection.releaseInterface(route.usbInterface) }
            connection.close()
            lastError = "Failed to switch USB interface alt=${route.alternateSetting}"
            return false
        }
        val fileDescriptor = runCatching { connection.fileDescriptor }.getOrDefault(-1)
        val handle = runCatching {
            NativeUsbBridge.nativeCreate(
                connection = connection,
                endpoint = route.endpoint,
                packetSize = route.endpoint.maxPacketSize,
                endpointType = route.endpoint.type,
                endpointAddress = route.endpoint.address,
                fileDescriptor = fileDescriptor,
                interfaceNumber = route.interfaceNumber,
                alternateSetting = route.alternateSetting
            )
        }.getOrDefault(0L)
        if (handle == 0L) {
            runCatching { connection.releaseInterface(route.usbInterface) }
            connection.close()
            lastError = "Failed to create native USB backend"
            return false
        }
        this.connection = connection
        this.claimedInterface = route.usbInterface
        this.endpoint = route.endpoint
        this.selectedRoute = route
        this.currentDeviceId = device.deviceId
        this.nativeHandle = handle
        this.running = true
        this.worker = Thread({
            pumpLoop()
        }, "hifx-native-usb-pcm").also { it.start() }
        this.lastError = ""
        return true
    }

    override fun writePcm(bytes: ByteArray, audioFormat: AudioProcessor.AudioFormat): Boolean {
        if (!running || nativeHandle == 0L) {
            return false
        }
        if (audioFormat.encoding != C.ENCODING_PCM_16BIT || audioFormat.channelCount != 2) {
            lastError = "Unsupported format encoding=${audioFormat.encoding} channels=${audioFormat.channelCount}"
            return false
        }
        val mapped = mapUsbTransportFormat(audioFormat, transportConfig)
        val packet = NativeUsbQueuedPacket(
            bytes = bytes,
            inputSampleRateHz = audioFormat.sampleRate,
            outputSampleRateHz = mapped.sampleRateHz,
            resampleAlgorithm = transportConfig.resampleAlgorithm
        )
        if (!packetQueue.offer(packet)) {
            val removed = packetQueue.poll()
            if (removed != null) {
                queuedBytes.addAndGet(-removed.bytes.size)
            }
            droppedPackets.incrementAndGet()
            if (!packetQueue.offer(packet)) {
                lastError = "Native USB queue saturated"
                return false
            }
        }
        queuedBytes.addAndGet(packet.bytes.size)
        return true
    }

    override fun setTransportConfig(config: UsbTransportConfig) {
        transportConfig = config
    }

    private fun pumpLoop() {
        while (running) {
            try {
                val packet = packetQueue.take()
                queuedBytes.addAndGet(-packet.bytes.size)
                val handle = nativeHandle
                if (handle == 0L) {
                    continue
                }
                val written = runCatching {
                    NativeUsbBridge.nativeWrite(
                        handle = handle,
                        pcmBytes = packet.bytes,
                        inputSampleRateHz = packet.inputSampleRateHz,
                        outputSampleRateHz = packet.outputSampleRateHz,
                        resampleAlgorithm = packet.resampleAlgorithm,
                        timeoutMs = 100
                    )
                }.getOrElse {
                    lastError = it.message ?: "Native USB write failed"
                    -1
                }
                if (written <= 0) {
                    lastError = runCatching { NativeUsbBridge.nativeGetLastError(handle) }
                        .getOrDefault(lastError.ifBlank { "Native USB write failed" })
                    running = false
                    break
                }
                submittedPackets.incrementAndGet()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                lastError = t.message ?: "Native USB worker failed"
                running = false
                break
            }
        }
    }

    override fun debugStatus(): String {
        val route = selectedRoute
        val endpoint = endpoint
        return "backend=NativeUsbDirectOutput running=$running queue=${packetQueue.size}/48 queuedBytes=${queuedBytes.get()} submitted=${submittedPackets.get()} dropped=${droppedPackets.get()} routeType=${route?.typeLabel ?: "N/A"} alt=${route?.alternateSetting ?: "N/A"} endpointType=${endpoint?.type ?: "N/A"} maxPacket=${endpoint?.maxPacketSize ?: "N/A"} lastError=${lastError.ifBlank { "none" }}"
    }

    override fun isOperational(): Boolean = running && nativeHandle != 0L && connection != null && endpoint != null

    override fun isForTarget(device: UsbDevice, route: UsbOutputRoute?): Boolean {
        return currentDeviceId == device.deviceId && selectedRoute?.toRouteKey() == route?.toRouteKey()
    }

    override fun close() {
        running = false
        worker?.interrupt()
        worker = null
        packetQueue.clear()
        queuedBytes.set(0)
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) {
            runCatching { NativeUsbBridge.nativeClose(handle) }
        }
        val connection = this.connection
        val claimedInterface = this.claimedInterface
        if (connection != null && claimedInterface != null) {
            runCatching { connection.releaseInterface(claimedInterface) }
        }
        runCatching { connection?.close() }
        this.connection = null
        this.claimedInterface = null
        this.endpoint = null
        this.selectedRoute = null
        this.currentDeviceId = null
        if (lastError.isBlank()) {
            lastError = "closed"
        }
    }
}

internal data class UsbOutputRoute(
    val usbInterface: UsbInterface,
    val endpoint: UsbEndpoint,
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val typeLabel: String
)

internal fun findPreferredUsbOutRoute(device: UsbDevice, preferIso: Boolean): UsbOutputRoute? {
    var bestIso: UsbOutputRoute? = null
    var bestBulk: UsbOutputRoute? = null
    for (i in 0 until device.interfaceCount) {
        val intf = runCatching { device.getInterface(i) }.getOrNull() ?: continue
        for (j in 0 until intf.endpointCount) {
            val endpoint = runCatching { intf.getEndpoint(j) }.getOrNull() ?: continue
            if (endpoint.direction != UsbConstants.USB_DIR_OUT) {
                continue
            }
            val route = UsbOutputRoute(
                usbInterface = intf,
                endpoint = endpoint,
                interfaceNumber = intf.id,
                alternateSetting = intf.alternateSetting,
                typeLabel = when (endpoint.type) {
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "iso"
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                    else -> endpoint.type.toString()
                }
            )
            when (endpoint.type) {
                UsbConstants.USB_ENDPOINT_XFER_ISOC -> {
                    if (bestIso == null || route.endpoint.maxPacketSize > bestIso.endpoint.maxPacketSize) {
                        bestIso = route
                    }
                }
                UsbConstants.USB_ENDPOINT_XFER_BULK -> {
                    if (bestBulk == null || route.endpoint.maxPacketSize > bestBulk.endpoint.maxPacketSize) {
                        bestBulk = route
                    }
                }
            }
        }
    }
    return if (preferIso) bestIso ?: bestBulk else bestBulk ?: bestIso
}

internal fun findHostDirectUsbOutRoutes(device: UsbDevice): List<UsbOutputRoute> {
    val isoRoute = findPreferredUsbOutRoute(device, preferIso = true)
        ?.takeIf { it.endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC }
    val bulkRoute = findPreferredUsbOutRoute(device, preferIso = false)
        ?.takeIf { it.endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
    val ordered = linkedMapOf<String, UsbOutputRoute>()
    if (PREFER_HOST_DIRECT_ISO_ROUTE && ENABLE_EXPERIMENTAL_USB_ISO_DIRECT) {
        isoRoute?.let { ordered[it.toRouteKey()] = it }
        bulkRoute?.let { ordered[it.toRouteKey()] = it }
    } else {
        bulkRoute?.let { ordered[it.toRouteKey()] = it }
        if (ENABLE_EXPERIMENTAL_USB_ISO_DIRECT) {
            isoRoute?.let { ordered[it.toRouteKey()] = it }
        }
    }
    return ordered.values.toList()
}

internal fun findHostDirectUsbOutRoute(device: UsbDevice): UsbOutputRoute? {
    return findHostDirectUsbOutRoutes(device).firstOrNull()
}

internal fun hasIsoOnlyUsbOutRoute(device: UsbDevice): Boolean {
    val preferred = findPreferredUsbOutRoute(device, preferIso = true) ?: return false
    val bulk = findPreferredUsbOutRoute(device, preferIso = false)
    return preferred.endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && bulk?.endpoint?.type != UsbConstants.USB_ENDPOINT_XFER_BULK
}

internal fun UsbOutputRoute.toRouteKey(): String {
    return "${typeLabel}|${interfaceNumber}|${alternateSetting}|${endpoint.address}|${endpoint.maxPacketSize}"
}

internal fun mapUsbTransportFormat(
    inputFormat: AudioProcessor.AudioFormat,
    config: UsbTransportConfig
): UsbMappedTransportFormat {
    val sampleRateHz = config.targetSampleRateHz?.takeIf { it > 0 } ?: inputFormat.sampleRate
    val requestedBitDepth = config.targetBitDepth ?: 16
    val actualBitDepth = 16
    val note = if (requestedBitDepth > actualBitDepth) {
        "mapped ${requestedBitDepth}-bit request to 16-bit transport"
    } else {
        "native 16-bit transport"
    }
    return UsbMappedTransportFormat(
        sampleRateHz = sampleRateHz,
        bitDepth = actualBitDepth,
        encoding = C.ENCODING_PCM_16BIT,
        note = note
    )
}

internal object NativeUsbBridge {
    val isAvailable: Boolean by lazy {
        runCatching {
            System.loadLibrary("hifxaudio")
            true
        }.getOrDefault(false)
    }

    external fun nativeCreate(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        packetSize: Int,
        endpointType: Int,
        endpointAddress: Int,
        fileDescriptor: Int,
        interfaceNumber: Int,
        alternateSetting: Int
    ): Long
    external fun nativeWrite(
        handle: Long,
        pcmBytes: ByteArray,
        inputSampleRateHz: Int,
        outputSampleRateHz: Int,
        resampleAlgorithm: Int,
        timeoutMs: Int
    ): Int
    external fun nativeGetLastError(handle: Long): String
    external fun nativeClose(handle: Long)
}
