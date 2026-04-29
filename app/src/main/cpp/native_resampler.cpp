#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "soxr.h"

namespace {

constexpr int kAlgoNearest = 0;
constexpr int kAlgoLinear = 1;
constexpr int kAlgoCubic = 2;
constexpr int kAlgoSoxrHq = 3;

struct StereoFrame {
    float left = 0.0f;
    float right = 0.0f;
};

struct NativeUsbResampler {
    int input_rate_hz = 48000;
    int output_rate_hz = 48000;
    int algorithm = kAlgoLinear;
    double source_position = 0.0;
    std::vector<StereoFrame> history;
    soxr_t soxr = nullptr;
    std::vector<int16_t> input_pcm;
    std::vector<int16_t> output_pcm;
    std::string last_error;
};

inline float short_to_float(int16_t value) {
    return std::max(-1.0f, std::min(1.0f, static_cast<float>(value) / 32768.0f));
}

inline int16_t float_to_short(float value) {
    const float clamped = std::max(-1.0f, std::min(1.0f, value));
    const int sample = static_cast<int>(clamped * 32767.0f);
    return static_cast<int16_t>(std::max<int>(INT16_MIN, std::min<int>(INT16_MAX, sample)));
}

StereoFrame sample_nearest(const std::vector<StereoFrame>& frames, double position) {
    const int index = std::clamp(static_cast<int>(std::llround(position)), 0, static_cast<int>(frames.size()) - 1);
    return frames[index];
}

StereoFrame sample_linear(const std::vector<StereoFrame>& frames, double position) {
    const int left_index = std::clamp(static_cast<int>(std::floor(position)), 0, static_cast<int>(frames.size()) - 1);
    const int right_index = std::clamp(left_index + 1, 0, static_cast<int>(frames.size()) - 1);
    const float fraction = static_cast<float>(position - std::floor(position));
    const StereoFrame& a = frames[left_index];
    const StereoFrame& b = frames[right_index];
    return StereoFrame{
        a.left + (b.left - a.left) * fraction,
        a.right + (b.right - a.right) * fraction
    };
}

float cubic_interp(float p0, float p1, float p2, float p3, float t) {
    const float a0 = -0.5f * p0 + 1.5f * p1 - 1.5f * p2 + 0.5f * p3;
    const float a1 = p0 - 2.5f * p1 + 2.0f * p2 - 0.5f * p3;
    const float a2 = -0.5f * p0 + 0.5f * p2;
    const float a3 = p1;
    return ((a0 * t + a1) * t + a2) * t + a3;
}

StereoFrame sample_cubic(const std::vector<StereoFrame>& frames, double position) {
    const int base = static_cast<int>(std::floor(position));
    const float t = static_cast<float>(position - std::floor(position));
    const int i0 = std::clamp(base - 1, 0, static_cast<int>(frames.size()) - 1);
    const int i1 = std::clamp(base, 0, static_cast<int>(frames.size()) - 1);
    const int i2 = std::clamp(base + 1, 0, static_cast<int>(frames.size()) - 1);
    const int i3 = std::clamp(base + 2, 0, static_cast<int>(frames.size()) - 1);
    return StereoFrame{
        cubic_interp(frames[i0].left, frames[i1].left, frames[i2].left, frames[i3].left, t),
        cubic_interp(frames[i0].right, frames[i1].right, frames[i2].right, frames[i3].right, t)
    };
}

NativeUsbResampler* from_handle(jlong handle) {
    return reinterpret_cast<NativeUsbResampler*>(handle);
}

void reset_manual_state(NativeUsbResampler* resampler) {
    if (resampler == nullptr) {
        return;
    }
    resampler->source_position = 0.0;
    resampler->history.clear();
}

void release_soxr_state(NativeUsbResampler* resampler) {
    if (resampler == nullptr || resampler->soxr == nullptr) {
        return;
    }
    soxr_delete(resampler->soxr);
    resampler->soxr = nullptr;
}

bool ensure_soxr_state(NativeUsbResampler* resampler) {
    if (resampler == nullptr) {
        return false;
    }
    if (resampler->soxr != nullptr) {
        return true;
    }
    soxr_error_t error = nullptr;
    const soxr_io_spec_t io_spec = soxr_io_spec(SOXR_INT16_I, SOXR_INT16_I);
    const soxr_quality_spec_t quality_spec = soxr_quality_spec(SOXR_HQ, 0);
    const soxr_runtime_spec_t runtime_spec = soxr_runtime_spec(1);
    resampler->soxr = soxr_create(
        static_cast<double>(resampler->input_rate_hz),
        static_cast<double>(resampler->output_rate_hz),
        2,
        &error,
        &io_spec,
        &quality_spec,
        &runtime_spec
    );
    if (error != nullptr || resampler->soxr == nullptr) {
        resampler->last_error = error != nullptr ? error : "soxr_create failed";
        release_soxr_state(resampler);
        return false;
    }
    return true;
}

bool process_with_soxr(
    JNIEnv* env,
    NativeUsbResampler* resampler,
    jbyteArray input,
    jsize input_size,
    int input_frame_count,
    jbyteArray* output_array
) {
    if (!ensure_soxr_state(resampler)) {
        return false;
    }
    resampler->input_pcm.resize(static_cast<size_t>(input_size / 2));
    env->GetByteArrayRegion(input, 0, input_size, reinterpret_cast<jbyte*>(resampler->input_pcm.data()));

    const double ratio = static_cast<double>(resampler->output_rate_hz) / static_cast<double>(resampler->input_rate_hz);
    const size_t estimated_output_frames = static_cast<size_t>(std::ceil(input_frame_count * ratio)) + 64U;
    resampler->output_pcm.assign(estimated_output_frames * 2U, 0);

    size_t total_input_done = 0;
    size_t total_output_done = 0;
    while (total_input_done < static_cast<size_t>(input_frame_count)) {
        size_t input_done = 0;
        size_t output_done = 0;
        const size_t output_capacity_frames = (resampler->output_pcm.size() / 2U) - total_output_done;
        if (output_capacity_frames == 0) {
            const size_t previous_size = resampler->output_pcm.size();
            resampler->output_pcm.resize(previous_size + std::max<size_t>(128U, previous_size / 2U + 2U));
            continue;
        }
        soxr_error_t error = soxr_process(
            resampler->soxr,
            resampler->input_pcm.data() + total_input_done * 2U,
            static_cast<size_t>(input_frame_count) - total_input_done,
            &input_done,
            resampler->output_pcm.data() + total_output_done * 2U,
            output_capacity_frames,
            &output_done
        );
        if (error != nullptr) {
            resampler->last_error = error;
            soxr_clear(resampler->soxr);
            return false;
        }
        total_input_done += input_done;
        total_output_done += output_done;
        if (input_done == 0 && output_done == 0) {
            break;
        }
    }

    const jsize output_size = static_cast<jsize>(total_output_done * 2U * sizeof(int16_t));
    *output_array = env->NewByteArray(output_size);
    if (*output_array == nullptr) {
        return false;
    }
    env->SetByteArrayRegion(
        *output_array,
        0,
        output_size,
        reinterpret_cast<const jbyte*>(resampler->output_pcm.data())
    );
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_alky_hifx_audio_NativeUsbResamplerBridge_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new NativeUsbResampler());
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeUsbResamplerBridge_nativeConfigure(
    JNIEnv*,
    jobject,
    jlong handle,
    jint input_sample_rate_hz,
    jint output_sample_rate_hz,
    jint algorithm
) {
    auto* resampler = from_handle(handle);
    if (resampler == nullptr) {
        return;
    }
    resampler->input_rate_hz = std::max(1, static_cast<int>(input_sample_rate_hz));
    resampler->output_rate_hz = std::max(1, static_cast<int>(output_sample_rate_hz));
    resampler->algorithm = algorithm;
    resampler->last_error.clear();
    release_soxr_state(resampler);
    reset_manual_state(resampler);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_alky_hifx_audio_NativeUsbResamplerBridge_nativeProcessPcm16Stereo(
    JNIEnv* env,
    jobject,
    jlong handle,
    jbyteArray input
) {
    auto* resampler = from_handle(handle);
    if (resampler == nullptr || input == nullptr) {
        return env->NewByteArray(0);
    }
    const jsize input_size = env->GetArrayLength(input);
    if (input_size <= 0) {
        return env->NewByteArray(0);
    }
    const int input_frame_count = input_size / 4;

    if (resampler->algorithm == kAlgoSoxrHq) {
        jbyteArray output = nullptr;
        if (process_with_soxr(env, resampler, input, input_size, input_frame_count, &output)) {
            return output;
        }
    }

    resampler->input_pcm.resize(static_cast<size_t>(input_size / 2));
    env->GetByteArrayRegion(input, 0, input_size, reinterpret_cast<jbyte*>(resampler->input_pcm.data()));

    std::vector<StereoFrame> frames;
    frames.reserve(resampler->history.size() + static_cast<size_t>(input_frame_count));
    frames.insert(frames.end(), resampler->history.begin(), resampler->history.end());
    for (int i = 0; i < input_frame_count; ++i) {
        frames.push_back(StereoFrame{
            short_to_float(resampler->input_pcm[i * 2]),
            short_to_float(resampler->input_pcm[i * 2 + 1])
        });
    }

    if (frames.size() < 2 || resampler->input_rate_hz == resampler->output_rate_hz) {
        jbyteArray output = env->NewByteArray(input_size);
        env->SetByteArrayRegion(output, 0, input_size, reinterpret_cast<const jbyte*>(resampler->input_pcm.data()));
        resampler->history.assign(frames.end() - std::min<size_t>(frames.size(), 4), frames.end());
        return output;
    }

    const double step = static_cast<double>(resampler->input_rate_hz) / static_cast<double>(resampler->output_rate_hz);
    resampler->output_pcm.clear();
    resampler->output_pcm.reserve(static_cast<size_t>((input_frame_count / step) + 8) * 2U);

    while (resampler->source_position + 1.0 < static_cast<double>(frames.size())) {
        StereoFrame sample;
        switch (resampler->algorithm) {
            case kAlgoNearest:
                sample = sample_nearest(frames, resampler->source_position);
                break;
            case kAlgoCubic:
                sample = sample_cubic(frames, resampler->source_position);
                break;
            case kAlgoLinear:
            default:
                sample = sample_linear(frames, resampler->source_position);
                break;
        }
        resampler->output_pcm.push_back(float_to_short(sample.left));
        resampler->output_pcm.push_back(float_to_short(sample.right));
        resampler->source_position += step;
    }

    const size_t keep_count = std::min<size_t>(frames.size(), 4);
    resampler->history.assign(frames.end() - keep_count, frames.end());
    resampler->source_position -= static_cast<double>(frames.size() - keep_count);
    if (resampler->source_position < 0.0) {
        resampler->source_position = 0.0;
    }

    const jsize output_size = static_cast<jsize>(resampler->output_pcm.size() * sizeof(int16_t));
    jbyteArray output = env->NewByteArray(output_size);
    env->SetByteArrayRegion(output, 0, output_size, reinterpret_cast<const jbyte*>(resampler->output_pcm.data()));
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeUsbResamplerBridge_nativeReset(JNIEnv*, jobject, jlong handle) {
    auto* resampler = from_handle(handle);
    if (resampler == nullptr) {
        return;
    }
    resampler->last_error.clear();
    if (resampler->soxr != nullptr) {
        soxr_clear(resampler->soxr);
    }
    reset_manual_state(resampler);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeUsbResamplerBridge_nativeRelease(JNIEnv*, jobject, jlong handle) {
    auto* resampler = from_handle(handle);
    release_soxr_state(resampler);
    delete resampler;
}
