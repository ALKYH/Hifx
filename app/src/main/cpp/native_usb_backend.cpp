
#include <jni.h>

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>

#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

namespace {

constexpr int kUsbEndpointTransferTypeIso = 1;
constexpr int kUsbEndpointTransferTypeBulk = 2;

extern "C" jlong Java_com_alky_hifx_audio_NativeUsbResamplerBridge_nativeCreate(JNIEnv*, jobject);
extern "C" void Java_com_alky_hifx_audio_NativeUsbResamplerBridge_nativeConfigure(JNIEnv*, jobject, jlong, jint, jint, jint);
extern "C" jbyteArray Java_com_alky_hifx_audio_NativeUsbResamplerBridge_nativeProcessPcm16Stereo(JNIEnv*, jobject, jlong, jbyteArray);
extern "C" void Java_com_alky_hifx_audio_NativeUsbResamplerBridge_nativeReset(JNIEnv*, jobject, jlong);
extern "C" void Java_com_alky_hifx_audio_NativeUsbResamplerBridge_nativeRelease(JNIEnv*, jobject, jlong);

struct NativeUsbBackend {
    JavaVM* vm = nullptr;
    jobject connection = nullptr;
    jobject endpoint = nullptr;
    jmethodID bulk_transfer = nullptr;
    int packet_size = 0;
    int endpoint_type = 0;
    unsigned char endpoint_address = 0;
    int file_descriptor = -1;
    int interface_number = 0;
    int alternate_setting = 0;
    jlong resampler_handle = 0L;
    int resampler_input_rate_hz = 0;
    int resampler_output_rate_hz = 0;
    int resampler_algorithm = -1;
    std::string last_error;
};

NativeUsbBackend* from_handle(jlong handle) {
    return reinterpret_cast<NativeUsbBackend*>(handle);
}

std::string errno_message(const char* prefix) {
    return std::string(prefix) + ": errno=" + std::to_string(errno) + " (" + std::strerror(errno) + ")";
}

int write_bulk_via_java(JNIEnv* env, NativeUsbBackend* backend, jbyteArray pcm_bytes, jint timeout_ms) {
    if (backend == nullptr || backend->connection == nullptr || backend->endpoint == nullptr || backend->bulk_transfer == nullptr) {
        return -1;
    }
    const jsize total_size = env->GetArrayLength(pcm_bytes);
    jint offset = 0;
    while (offset < total_size) {
        const jint chunk_size = std::min<jint>(std::max(192, backend->packet_size * 8), total_size - offset);
        const jint sent = env->CallIntMethod(
            backend->connection,
            backend->bulk_transfer,
            backend->endpoint,
            pcm_bytes,
            offset,
            chunk_size,
            timeout_ms
        );
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            backend->last_error = "JNI bulkTransfer threw";
            return -1;
        }
        if (sent <= 0) {
            backend->last_error = "bulkTransfer failed";
            return sent;
        }
        offset += sent;
    }
    backend->last_error.clear();
    return total_size;
}

int ensure_resampler_configured(
    JNIEnv* env,
    NativeUsbBackend* backend,
    jint input_sample_rate_hz,
    jint output_sample_rate_hz,
    jint algorithm
) {
    if (backend == nullptr) {
        return -1;
    }
    if (backend->resampler_handle == 0L) {
        backend->resampler_handle = Java_com_alky_hifx_audio_NativeUsbResamplerBridge_nativeCreate(env, nullptr);
        if (backend->resampler_handle == 0L) {
            backend->last_error = "Failed to create native resampler";
            return -1;
        }
    }
    if (
        backend->resampler_input_rate_hz != input_sample_rate_hz ||
        backend->resampler_output_rate_hz != output_sample_rate_hz ||
        backend->resampler_algorithm != algorithm
    ) {
        Java_com_alky_hifx_audio_NativeUsbResamplerBridge_nativeConfigure(
            env,
            nullptr,
            backend->resampler_handle,
            input_sample_rate_hz,
            output_sample_rate_hz,
            algorithm
        );
        backend->resampler_input_rate_hz = input_sample_rate_hz;
        backend->resampler_output_rate_hz = output_sample_rate_hz;
        backend->resampler_algorithm = algorithm;
    }
    return 0;
}

int write_iso_via_usbfs(JNIEnv* env, NativeUsbBackend* backend, jbyteArray pcm_bytes) {
    if (backend == nullptr || backend->file_descriptor < 0 || backend->endpoint_address == 0 || pcm_bytes == nullptr) {
        if (backend != nullptr) {
            backend->last_error = "usbfs isochronous backend not ready";
        }
        return -1;
    }

    const jsize total_size = env->GetArrayLength(pcm_bytes);
    if (total_size <= 0) {
        backend->last_error.clear();
        return 0;
    }

    const int packet_payload = std::max(1, backend->packet_size);
    const int packet_count = std::max(1, static_cast<int>((total_size + packet_payload - 1) / packet_payload));
    const size_t urb_size = sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc) * static_cast<size_t>(packet_count);
    auto* urb = static_cast<usbdevfs_urb*>(std::calloc(1, urb_size));
    if (urb == nullptr) {
        backend->last_error = "calloc failed for usbdevfs_urb";
        return -1;
    }

    auto* payload = static_cast<unsigned char*>(std::malloc(static_cast<size_t>(total_size)));
    if (payload == nullptr) {
        std::free(urb);
        backend->last_error = "malloc failed for iso payload";
        return -1;
    }
    env->GetByteArrayRegion(pcm_bytes, 0, total_size, reinterpret_cast<jbyte*>(payload));

    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = backend->endpoint_address;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = payload;
    urb->buffer_length = total_size;
    urb->number_of_packets = packet_count;

    int remaining = total_size;
    for (int index = 0; index < packet_count; ++index) {
        const int frame_length = std::min(packet_payload, remaining);
        urb->iso_frame_desc[index].length = static_cast<unsigned int>(frame_length);
        remaining -= frame_length;
    }

    if (ioctl(backend->file_descriptor, USBDEVFS_SUBMITURB, urb) != 0) {
        backend->last_error = errno_message("USBDEVFS_SUBMITURB failed");
        std::free(payload);
        std::free(urb);
        return -1;
    }

    void* completed = nullptr;
    if (ioctl(backend->file_descriptor, USBDEVFS_REAPURB, &completed) != 0) {
        backend->last_error = errno_message("USBDEVFS_REAPURB failed");
        ioctl(backend->file_descriptor, USBDEVFS_DISCARDURB, urb);
        std::free(payload);
        std::free(urb);
        return -1;
    }

    auto* completed_urb = static_cast<usbdevfs_urb*>(completed);
    int written = 0;
    if (completed_urb != nullptr && completed_urb->status == 0) {
        for (int index = 0; index < completed_urb->number_of_packets; ++index) {
            written += static_cast<int>(completed_urb->iso_frame_desc[index].actual_length);
        }
        backend->last_error.clear();
    } else {
        const int status = completed_urb != nullptr ? completed_urb->status : -1;
        backend->last_error = "Iso URB completed with status=" + std::to_string(status);
        written = -1;
    }

    std::free(payload);
    std::free(urb);
    return written;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_alky_hifx_audio_NativeUsbBridge_nativeCreate(
    JNIEnv* env,
    jobject,
    jobject connection,
    jobject endpoint,
    jint packet_size,
    jint endpoint_type,
    jint endpoint_address,
    jint file_descriptor,
    jint interface_number,
    jint alternate_setting
) {
    if (connection == nullptr || endpoint == nullptr) {
        return 0L;
    }
    auto* backend = new NativeUsbBackend();
    env->GetJavaVM(&backend->vm);
    backend->connection = env->NewGlobalRef(connection);
    backend->endpoint = env->NewGlobalRef(endpoint);
    backend->packet_size = std::max(1, packet_size);
    backend->endpoint_type = endpoint_type;
    backend->endpoint_address = static_cast<unsigned char>(endpoint_address & 0xFF);
    backend->file_descriptor = file_descriptor;
    backend->interface_number = interface_number;
    backend->alternate_setting = alternate_setting;

    jclass connection_class = env->GetObjectClass(connection);
    backend->bulk_transfer = env->GetMethodID(
        connection_class,
        "bulkTransfer",
        "(Landroid/hardware/usb/UsbEndpoint;[BIII)I"
    );
    env->DeleteLocalRef(connection_class);

    if (backend->connection == nullptr || backend->endpoint == nullptr) {
        if (backend->connection != nullptr) {
            env->DeleteGlobalRef(backend->connection);
        }
        if (backend->endpoint != nullptr) {
            env->DeleteGlobalRef(backend->endpoint);
        }
        delete backend;
        return 0L;
    }
    return reinterpret_cast<jlong>(backend);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_alky_hifx_audio_NativeUsbBridge_nativeWrite(
    JNIEnv* env,
    jobject,
    jlong handle,
    jbyteArray pcm_bytes,
    jint input_sample_rate_hz,
    jint output_sample_rate_hz,
    jint resample_algorithm,
    jint timeout_ms
) {
    auto* backend = from_handle(handle);
    if (backend == nullptr || pcm_bytes == nullptr) {
        return -1;
    }
    jbyteArray transport_bytes = pcm_bytes;
    if (
        input_sample_rate_hz > 0 &&
        output_sample_rate_hz > 0 &&
        input_sample_rate_hz != output_sample_rate_hz
    ) {
        if (ensure_resampler_configured(env, backend, input_sample_rate_hz, output_sample_rate_hz, resample_algorithm) != 0) {
            return -1;
        }
        transport_bytes = Java_com_alky_hifx_audio_NativeUsbResamplerBridge_nativeProcessPcm16Stereo(
            env,
            nullptr,
            backend->resampler_handle,
            pcm_bytes
        );
        if (transport_bytes == nullptr) {
            backend->last_error = "Native resampler returned null";
            return -1;
        }
    }
    int written = -1;
    if (backend->endpoint_type == kUsbEndpointTransferTypeIso) {
        written = write_iso_via_usbfs(env, backend, transport_bytes);
    } else {
        written = write_bulk_via_java(env, backend, transport_bytes, timeout_ms);
    }
    if (transport_bytes != pcm_bytes) {
        env->DeleteLocalRef(transport_bytes);
    }
    return written;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_alky_hifx_audio_NativeUsbBridge_nativeGetLastError(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    auto* backend = from_handle(handle);
    const std::string message = backend == nullptr ? "Native USB backend unavailable" : backend->last_error;
    return env->NewStringUTF(message.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeUsbBridge_nativeClose(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    auto* backend = from_handle(handle);
    if (backend == nullptr) {
        return;
    }
    if (backend->connection != nullptr) {
        env->DeleteGlobalRef(backend->connection);
        backend->connection = nullptr;
    }
    if (backend->endpoint != nullptr) {
        env->DeleteGlobalRef(backend->endpoint);
        backend->endpoint = nullptr;
    }
    if (backend->resampler_handle != 0L) {
        Java_com_alky_hifx_audio_NativeUsbResamplerBridge_nativeRelease(env, nullptr, backend->resampler_handle);
        backend->resampler_handle = 0L;
    }
    delete backend;
}
