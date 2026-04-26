#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {

constexpr int kAlgoNearest = 0;
constexpr int kAlgoLinear = 1;
constexpr int kAlgoCubic = 2;

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

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_hifx_audio_NativeUsbResamplerBridge_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new NativeUsbResampler());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeUsbResamplerBridge_nativeConfigure(
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
    resampler->source_position = 0.0;
    resampler->history.clear();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_hifx_audio_NativeUsbResamplerBridge_nativeProcessPcm16Stereo(
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
    std::vector<int16_t> input_pcm(static_cast<size_t>(input_size / 2));
    env->GetByteArrayRegion(input, 0, input_size, reinterpret_cast<jbyte*>(input_pcm.data()));

    std::vector<StereoFrame> frames;
    frames.reserve(resampler->history.size() + static_cast<size_t>(input_frame_count));
    frames.insert(frames.end(), resampler->history.begin(), resampler->history.end());
    for (int i = 0; i < input_frame_count; ++i) {
        frames.push_back(StereoFrame{
            short_to_float(input_pcm[i * 2]),
            short_to_float(input_pcm[i * 2 + 1])
        });
    }

    if (frames.size() < 2 || resampler->input_rate_hz == resampler->output_rate_hz) {
        jbyteArray output = env->NewByteArray(input_size);
        env->SetByteArrayRegion(output, 0, input_size, reinterpret_cast<const jbyte*>(input_pcm.data()));
        resampler->history.assign(frames.end() - std::min<size_t>(frames.size(), 4), frames.end());
        return output;
    }

    const double step = static_cast<double>(resampler->input_rate_hz) / static_cast<double>(resampler->output_rate_hz);
    std::vector<int16_t> output_pcm;
    output_pcm.reserve(static_cast<size_t>((input_frame_count / step) + 8) * 2U);

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
        output_pcm.push_back(float_to_short(sample.left));
        output_pcm.push_back(float_to_short(sample.right));
        resampler->source_position += step;
    }

    const size_t keep_count = std::min<size_t>(frames.size(), 4);
    resampler->history.assign(frames.end() - keep_count, frames.end());
    resampler->source_position -= static_cast<double>(frames.size() - keep_count);
    if (resampler->source_position < 0.0) {
        resampler->source_position = 0.0;
    }

    const jsize output_size = static_cast<jsize>(output_pcm.size() * sizeof(int16_t));
    jbyteArray output = env->NewByteArray(output_size);
    env->SetByteArrayRegion(output, 0, output_size, reinterpret_cast<const jbyte*>(output_pcm.data()));
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeUsbResamplerBridge_nativeReset(JNIEnv*, jobject, jlong handle) {
    auto* resampler = from_handle(handle);
    if (resampler == nullptr) {
        return;
    }
    resampler->source_position = 0.0;
    resampler->history.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeUsbResamplerBridge_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete from_handle(handle);
}
