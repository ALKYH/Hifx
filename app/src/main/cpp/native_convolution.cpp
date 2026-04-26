#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

namespace {

constexpr int kDefaultBlockSize = 512;
constexpr float kPi = 3.14159265358979323846f;

inline float clamp_float(float value, float min_value, float max_value) {
    return std::max(min_value, std::min(max_value, value));
}

inline float short_to_float(int16_t value) {
    return clamp_float(static_cast<float>(value) / 32768.0f, -1.0f, 1.0f);
}

inline int16_t float_to_short(float value) {
    const float clamped = clamp_float(value, -1.0f, 1.0f);
    int sample = static_cast<int>(clamped * 32767.0f);
    sample = std::max<int>(std::numeric_limits<int16_t>::min(), std::min<int>(std::numeric_limits<int16_t>::max(), sample));
    return static_cast<int16_t>(sample);
}

inline float soft_saturate(float value) {
    constexpr float drive = 1.08f;
    const float scaled = clamp_float(value * drive, -4.0f, 4.0f);
    const float norm = std::max(0.0001f, static_cast<float>(std::tanh(drive)));
    return clamp_float(static_cast<float>(std::tanh(scaled)) / norm, -1.0f, 1.0f);
}

inline int next_power_of_two(int value) {
    int result = 1;
    while (result < value) {
        result <<= 1;
    }
    return result;
}

inline int select_block_size(int impulse_length) {
    if (impulse_length <= 192) return 256;
    if (impulse_length <= 768) return 512;
    if (impulse_length <= 2048) return 1024;
    return 2048;
}

void fft(std::vector<float>& real, std::vector<float>& imag, bool inverse) {
    const int n = static_cast<int>(real.size());
    if (n <= 1) {
        return;
    }

    int j = 0;
    for (int i = 1; i < n; ++i) {
        int bit = n >> 1;
        while ((j & bit) != 0) {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if (i < j) {
            std::swap(real[i], real[j]);
            std::swap(imag[i], imag[j]);
        }
    }

    int len = 2;
    while (len <= n) {
        const float angle = 2.0f * kPi / static_cast<float>(len) * (inverse ? 1.0f : -1.0f);
        const float w_len_cos = std::cos(angle);
        const float w_len_sin = std::sin(angle);

        int offset = 0;
        while (offset < n) {
            float w_cos = 1.0f;
            float w_sin = 0.0f;
            const int half = len / 2;
            for (int k = 0; k < half; ++k) {
                const int even_index = offset + k;
                const int odd_index = even_index + half;

                const float odd_real = real[odd_index];
                const float odd_imag = imag[odd_index];
                const float t_real = odd_real * w_cos - odd_imag * w_sin;
                const float t_imag = odd_real * w_sin + odd_imag * w_cos;

                const float u_real = real[even_index];
                const float u_imag = imag[even_index];

                real[even_index] = u_real + t_real;
                imag[even_index] = u_imag + t_imag;
                real[odd_index] = u_real - t_real;
                imag[odd_index] = u_imag - t_imag;

                const float next_cos = w_cos * w_len_cos - w_sin * w_len_sin;
                w_sin = w_cos * w_len_sin + w_sin * w_len_cos;
                w_cos = next_cos;
            }
            offset += len;
        }
        len <<= 1;
    }

    if (inverse) {
        const float inv_n = 1.0f / static_cast<float>(n);
        for (int index = 0; index < n; ++index) {
            real[index] *= inv_n;
            imag[index] *= inv_n;
        }
    }
}

class NativeConvolutionEngine {
public:
    void configure(int sample_rate, int channel_count) {
        sample_rate_ = sample_rate;
        channel_count_ = channel_count;
        clear_processing_state();
    }

    void update_config(bool enabled, float wet_mix) {
        const bool previous_enabled = enabled_;
        enabled_ = enabled;
        wet_mix_ = clamp_float(wet_mix, 0.0f, 1.0f);
        if (previous_enabled != enabled_) {
            clear_processing_state();
        }
    }

    void update_impulse(const float* samples, int size) {
        impulse_.assign(samples, samples + size);
        impulse_compensation_ = compute_impulse_compensation(impulse_);
        configure_plan();
        clear_processing_state();
    }

    void clear_impulse() {
        impulse_.clear();
        impulse_compensation_ = 1.0f;
        clear_plan();
        clear_processing_state();
    }

    void flush() {
        clear_processing_state();
    }

    float realtime_meter() const {
        return realtime_meter_;
    }

    bool can_process() const {
        return enabled_ && !impulse_.empty() && fft_size_ > 0 && !kernel_real_.empty();
    }

    void process(const int16_t* input, int16_t* output, int frame_count) {
        if (frame_count <= 0) {
            return;
        }
        if (!can_process()) {
            std::memcpy(output, input, static_cast<size_t>(frame_count) * 2U * sizeof(int16_t));
            clear_processing_state();
            was_active_ = false;
            return;
        }

        const bool local_enabled = can_process();
        if (local_enabled != was_active_) {
            clear_processing_state();
            was_active_ = local_enabled;
        }

        std::vector<float> input_left(frame_count);
        std::vector<float> input_right(frame_count);
        for (int index = 0; index < frame_count; ++index) {
            input_left[index] = short_to_float(input[index * 2]);
            input_right[index] = short_to_float(input[index * 2 + 1]);
        }

        std::vector<float> wet_left(frame_count);
        std::vector<float> wet_right(frame_count);
        convolve_channel_ola(input_left, overlap_tail_left_, wet_left);
        convolve_channel_ola(input_right, overlap_tail_right_, wet_right);

        const float wet = clamp_float(wet_mix_, 0.0f, 1.0f);
        const float dry = 1.0f - wet;
        const float ir_comp = clamp_float(impulse_compensation_, 0.02f, 1.0f);
        const float level_compensation = clamp_float(1.0f - wet * 0.32f, 0.58f, 1.0f);
        constexpr float limiter_threshold = 0.92f;
        constexpr float limiter_release = 0.0035f;
        constexpr float wet_limiter_threshold = 0.72f;
        constexpr float wet_limiter_attack = 0.28f;
        constexpr float wet_limiter_release = 0.0018f;
        float meter = realtime_meter_;
        constexpr float meter_attack = 0.18f;
        constexpr float meter_release = 0.025f;

        for (int index = 0; index < frame_count; ++index) {
            float wet_left_sample = wet_left[index] * wet * ir_comp;
            float wet_right_sample = wet_right[index] * wet * ir_comp;
            const float wet_peak = std::max(std::abs(wet_left_sample), std::abs(wet_right_sample));
            const float wet_target_gain = wet_peak > wet_limiter_threshold
                ? clamp_float(wet_limiter_threshold / wet_peak, 0.05f, 1.0f)
                : 1.0f;
            wet_auto_gain_ = wet_target_gain < wet_auto_gain_
                ? wet_auto_gain_ + (wet_target_gain - wet_auto_gain_) * wet_limiter_attack
                : wet_auto_gain_ + (wet_target_gain - wet_auto_gain_) * wet_limiter_release;

            wet_left_sample *= wet_auto_gain_;
            wet_right_sample *= wet_auto_gain_;

            const float mixed_left = (input_left[index] * dry + wet_left_sample) * level_compensation;
            const float mixed_right = (input_right[index] * dry + wet_right_sample) * level_compensation;

            const float wet_energy = clamp_float(std::max(std::abs(wet_left_sample), std::abs(wet_right_sample)), 0.0f, 1.5f);
            meter += (wet_energy - meter) * (wet_energy > meter ? meter_attack : meter_release);

            const float peak = std::max(std::abs(mixed_left), std::abs(mixed_right));
            const float target_gain = peak > limiter_threshold
                ? clamp_float(limiter_threshold / peak, 0.05f, 1.0f)
                : 1.0f;
            limiter_gain_ = target_gain < limiter_gain_
                ? target_gain
                : limiter_gain_ + (target_gain - limiter_gain_) * limiter_release;

            const float limited_left = mixed_left * limiter_gain_;
            const float limited_right = mixed_right * limiter_gain_;
            output[index * 2] = float_to_short(soft_saturate(limited_left));
            output[index * 2 + 1] = float_to_short(soft_saturate(limited_right));
        }

        realtime_meter_ = clamp_float(meter, 0.0f, 1.5f);
    }

private:
    void convolve_channel_ola(
        const std::vector<float>& input,
        std::vector<float>& overlap_tail,
        std::vector<float>& output
    ) {
        if (input.empty() || impulse_.empty() || fft_size_ <= 0 || kernel_real_.empty()) {
            output = input;
            return;
        }

        int offset = 0;
        while (offset < static_cast<int>(input.size())) {
            const int current_block_size = std::min(block_size_, static_cast<int>(input.size()) - offset);
            std::fill(fft_work_real_.begin(), fft_work_real_.end(), 0.0f);
            std::fill(fft_work_imag_.begin(), fft_work_imag_.end(), 0.0f);
            std::copy(
                input.begin() + offset,
                input.begin() + offset + current_block_size,
                fft_work_real_.begin()
            );

            fft(fft_work_real_, fft_work_imag_, false);
            for (int index = 0; index < fft_size_; ++index) {
                const float ar = fft_work_real_[index];
                const float ai = fft_work_imag_[index];
                const float br = kernel_real_[index];
                const float bi = kernel_imag_[index];
                fft_work_real_[index] = ar * br - ai * bi;
                fft_work_imag_[index] = ar * bi + ai * br;
            }
            fft(fft_work_real_, fft_work_imag_, true);

            for (int index = 0; index < tail_length_; ++index) {
                fft_work_real_[index] += overlap_tail[index];
            }
            for (int index = 0; index < current_block_size; ++index) {
                output[offset + index] = fft_work_real_[index];
            }
            if (tail_length_ > 0) {
                const int tail_read_start = current_block_size;
                for (int index = 0; index < tail_length_; ++index) {
                    overlap_tail[index] = fft_work_real_[tail_read_start + index];
                }
            }
            offset += current_block_size;
        }
    }

    void configure_plan() {
        if (impulse_.empty()) {
            clear_plan();
            return;
        }
        tail_length_ = std::max(static_cast<int>(impulse_.size()) - 1, 0);
        block_size_ = select_block_size(static_cast<int>(impulse_.size()));
        fft_size_ = next_power_of_two(block_size_ + tail_length_);

        kernel_real_.assign(fft_size_, 0.0f);
        kernel_imag_.assign(fft_size_, 0.0f);
        std::copy(impulse_.begin(), impulse_.end(), kernel_real_.begin());
        fft(kernel_real_, kernel_imag_, false);

        overlap_tail_left_.assign(tail_length_, 0.0f);
        overlap_tail_right_.assign(tail_length_, 0.0f);
        fft_work_real_.assign(fft_size_, 0.0f);
        fft_work_imag_.assign(fft_size_, 0.0f);
    }

    void clear_plan() {
        block_size_ = kDefaultBlockSize;
        fft_size_ = 0;
        tail_length_ = 0;
        kernel_real_.clear();
        kernel_imag_.clear();
        overlap_tail_left_.clear();
        overlap_tail_right_.clear();
        fft_work_real_.clear();
        fft_work_imag_.clear();
    }

    void clear_processing_state() {
        std::fill(overlap_tail_left_.begin(), overlap_tail_left_.end(), 0.0f);
        std::fill(overlap_tail_right_.begin(), overlap_tail_right_.end(), 0.0f);
        limiter_gain_ = 1.0f;
        wet_auto_gain_ = 1.0f;
        realtime_meter_ = 0.0f;
    }

    static float compute_impulse_compensation(const std::vector<float>& impulse) {
        if (impulse.empty()) {
            return 1.0f;
        }
        float l1 = 0.0f;
        float l2_sq = 0.0f;
        float peak = 0.0f;
        for (float sample : impulse) {
            const float a = std::abs(sample);
            l1 += a;
            l2_sq += sample * sample;
            peak = std::max(peak, a);
        }
        const float l2 = std::sqrt(std::max(l2_sq, 0.0f));
        const float expected_gain = std::max(1.0f, peak * 1.2f + l2 * 1.45f + l1 * 0.12f);
        return clamp_float(0.78f / expected_gain, 0.02f, 1.0f);
    }

    int sample_rate_ = 0;
    int channel_count_ = 0;
    bool enabled_ = false;
    float wet_mix_ = 0.35f;
    std::vector<float> impulse_;
    float impulse_compensation_ = 1.0f;
    float realtime_meter_ = 0.0f;
    int block_size_ = kDefaultBlockSize;
    int fft_size_ = 0;
    int tail_length_ = 0;
    std::vector<float> kernel_real_;
    std::vector<float> kernel_imag_;
    std::vector<float> overlap_tail_left_;
    std::vector<float> overlap_tail_right_;
    std::vector<float> fft_work_real_;
    std::vector<float> fft_work_imag_;
    float limiter_gain_ = 1.0f;
    float wet_auto_gain_ = 1.0f;
    bool was_active_ = false;
};

NativeConvolutionEngine* from_handle(jlong handle) {
    return reinterpret_cast<NativeConvolutionEngine*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_hifx_audio_NativeConvolutionBridge_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new NativeConvolutionEngine());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeConvolutionBridge_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete from_handle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeConvolutionBridge_nativeConfigure(
    JNIEnv*,
    jobject,
    jlong handle,
    jint sample_rate,
    jint channel_count
) {
    if (auto* engine = from_handle(handle)) {
        engine->configure(sample_rate, channel_count);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeConvolutionBridge_nativeUpdateConfig(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean enabled,
    jfloat wet_mix
) {
    if (auto* engine = from_handle(handle)) {
        engine->update_config(enabled == JNI_TRUE, wet_mix);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeConvolutionBridge_nativeUpdateImpulse(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloatArray samples
) {
    auto* engine = from_handle(handle);
    if (engine == nullptr) {
        return;
    }
    if (samples == nullptr) {
        engine->clear_impulse();
        return;
    }
    const jsize length = env->GetArrayLength(samples);
    std::vector<float> local(static_cast<size_t>(length));
    env->GetFloatArrayRegion(samples, 0, length, local.data());
    engine->update_impulse(local.data(), length);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_hifx_audio_NativeConvolutionBridge_nativeProcess(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject input_buffer,
    jobject output_buffer,
    jint input_bytes
) {
    auto* engine = from_handle(handle);
    if (engine == nullptr || input_buffer == nullptr || output_buffer == nullptr || input_bytes <= 0) {
        return 0.0f;
    }
    auto* input = static_cast<int16_t*>(env->GetDirectBufferAddress(input_buffer));
    auto* output = static_cast<int16_t*>(env->GetDirectBufferAddress(output_buffer));
    if (input == nullptr || output == nullptr) {
        return 0.0f;
    }
    const int frame_count = input_bytes / 4;
    engine->process(input, output, frame_count);
    return engine->realtime_meter();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeConvolutionBridge_nativeFlush(JNIEnv*, jobject, jlong handle) {
    if (auto* engine = from_handle(handle)) {
        engine->flush();
    }
}
