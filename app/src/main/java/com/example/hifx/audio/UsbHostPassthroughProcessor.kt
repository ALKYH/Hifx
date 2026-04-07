package com.example.hifx.audio

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

internal class UsbHostPassthroughProcessor : BaseAudioProcessor() {
    @Volatile
    private var sink: UsbHostDirectOutput? = null
    private var configuredFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET

    fun attachSink(output: UsbHostDirectOutput?) {
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

internal class UsbHostDirectOutput(
    private val context: Context
) {
    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    private var running = false

    private val packetQueue = ArrayBlockingQueue<ByteArray>(24)
    private var worker: Thread? = null
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var outEndpoint: UsbEndpoint? = null

    fun start(device: UsbDevice): Boolean {
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
        val pair = findAudioOutInterfaceAndEndpoint(device)
        if (pair == null) {
            connection.close()
            lastError = "No USB audio OUT endpoint found"
            return false
        }
        val (intf, endpoint) = pair
        val claimed = runCatching { connection.claimInterface(intf, true) }.getOrDefault(false)
        if (!claimed) {
            connection.close()
            lastError = "Failed to claim USB interface ${intf.id}"
            return false
        }

        this.connection = connection
        this.claimedInterface = intf
        this.outEndpoint = endpoint
        this.running = true
        this.worker = Thread({
            pumpLoop()
        }, "hifx-usb-host-pcm").also { it.start() }
        lastError = ""
        return true
    }

    fun writePcm(bytes: ByteArray, audioFormat: AudioProcessor.AudioFormat) {
        if (!running) {
            return
        }
        if (audioFormat.encoding != C.ENCODING_PCM_16BIT || audioFormat.channelCount != 2) {
            return
        }
        if (!packetQueue.offer(bytes)) {
            packetQueue.poll()
            packetQueue.offer(bytes)
        }
    }

    fun close() {
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

    private fun findAudioOutInterfaceAndEndpoint(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val intf = runCatching { device.getInterface(i) }.getOrNull() ?: continue
            for (j in 0 until intf.endpointCount) {
                val endpoint = runCatching { intf.getEndpoint(j) }.getOrNull() ?: continue
                if (endpoint.direction != UsbConstants.USB_DIR_OUT) {
                    continue
                }
                val transferOk = endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                if (transferOk) {
                    return intf to endpoint
                }
            }
        }
        return null
    }
}
