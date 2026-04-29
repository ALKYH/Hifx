#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr int kRingBufferSize = 8192;
constexpr int kRingBufferMask = kRingBufferSize - 1;
constexpr float kTwoPi = kPi * 2.0f;

inline float clamp_float(float value, float min_value, float max_value) {
    return std::max(min_value, std::min(max_value, value));
}

inline float short_to_float(int16_t value) {
    return clamp_float(static_cast<float>(value) / 32768.0f, -1.0f, 1.0f);
}

inline int16_t float_to_short(float value) {
    const float clamped = clamp_float(value, -1.0f, 1.0f);
    int sample = static_cast<int>(clamped * 32767.0f);
    sample = std::max<int>(
        std::numeric_limits<int16_t>::min(),
        std::min<int>(std::numeric_limits<int16_t>::max(), sample)
    );
    return static_cast<int16_t>(sample);
}

struct BiquadFilter {
    float b0 = 1.0f;
    float b1 = 0.0f;
    float b2 = 0.0f;
    float a1 = 0.0f;
    float a2 = 0.0f;
    float x1 = 0.0f;
    float x2 = 0.0f;
    float y1 = 0.0f;
    float y2 = 0.0f;

    float process(float input) {
        const float output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = input;
        y2 = y1;
        y1 = output;
        return output;
    }

    void reset() {
        x1 = 0.0f;
        x2 = 0.0f;
        y1 = 0.0f;
        y2 = 0.0f;
    }

    static BiquadFilter pass_through() {
        return BiquadFilter{};
    }
};

inline BiquadFilter normalize_biquad(float b0, float b1, float b2, float a0, float a1, float a2) {
    const float safe_a0 = std::abs(a0) < 1.0e-6f ? 1.0f : a0;
    BiquadFilter filter;
    filter.b0 = b0 / safe_a0;
    filter.b1 = b1 / safe_a0;
    filter.b2 = b2 / safe_a0;
    filter.a1 = a1 / safe_a0;
    filter.a2 = a2 / safe_a0;
    return filter;
}

inline BiquadFilter build_low_pass(float sample_rate_hz, float cutoff_hz, float q) {
    const float omega = 2.0f * kPi * cutoff_hz / sample_rate_hz;
    const float alpha = std::max(1.0e-6f, std::sin(omega) / (2.0f * std::max(0.1f, q)));
    const float cos_omega = std::cos(omega);
    const float raw_b0 = (1.0f - cos_omega) * 0.5f;
    const float raw_b1 = 1.0f - cos_omega;
    const float raw_b2 = (1.0f - cos_omega) * 0.5f;
    const float raw_a0 = 1.0f + alpha;
    const float raw_a1 = -2.0f * cos_omega;
    const float raw_a2 = 1.0f - alpha;
    return normalize_biquad(raw_b0, raw_b1, raw_b2, raw_a0, raw_a1, raw_a2);
}

inline BiquadFilter build_high_pass(float sample_rate_hz, float cutoff_hz, float q) {
    const float omega = 2.0f * kPi * cutoff_hz / sample_rate_hz;
    const float alpha = std::max(1.0e-6f, std::sin(omega) / (2.0f * std::max(0.1f, q)));
    const float cos_omega = std::cos(omega);
    const float raw_b0 = (1.0f + cos_omega) * 0.5f;
    const float raw_b1 = -(1.0f + cos_omega);
    const float raw_b2 = (1.0f + cos_omega) * 0.5f;
    const float raw_a0 = 1.0f + alpha;
    const float raw_a1 = -2.0f * cos_omega;
    const float raw_a2 = 1.0f - alpha;
    return normalize_biquad(raw_b0, raw_b1, raw_b2, raw_a0, raw_a1, raw_a2);
}

class DualDelayPitchShifter {
public:
    explicit DualDelayPitchShifter(int sample_rate_hz) {
        configure_sample_rate(sample_rate_hz);
    }

    void configure_sample_rate(int sample_rate_hz) {
        window_samples_ = compute_window_samples(sample_rate_hz);
        base_delay_samples_ = window_samples_ + 8.0f;
        update_phase_increment();
        reset();
    }

    void set_pitch_semitones(int semitones) {
        pitch_ratio_ = std::pow(2.0f, static_cast<float>(semitones) / 12.0f);
        update_phase_increment();
    }

    float process_sample(float input) {
        ring_buffer_[write_index_] = input;
        if (std::abs(pitch_ratio_ - 1.0f) < 0.0001f) {
            write_index_ = (write_index_ + 1) & kRingBufferMask;
            return input;
        }

        phase_ += phase_increment_;
        if (phase_ >= 1.0f) {
            phase_ -= 1.0f;
        }
        const float phase_b = std::fmod(phase_ + 0.5f, 1.0f);
        const float delay_a = compute_delay_samples(phase_);
        const float delay_b = compute_delay_samples(phase_b);
        const float sample_a = read_delayed(delay_a);
        const float sample_b = read_delayed(delay_b);
        const float gain_a = hann_window(phase_);
        const float gain_b = hann_window(phase_b);
        const float output = clamp_float(sample_a * gain_a + sample_b * gain_b, -1.0f, 1.0f);

        write_index_ = (write_index_ + 1) & kRingBufferMask;
        return output;
    }

    void reset() {
        std::fill(ring_buffer_.begin(), ring_buffer_.end(), 0.0f);
        write_index_ = 0;
        phase_ = 0.0f;
    }

private:
    std::vector<float> ring_buffer_ = std::vector<float>(kRingBufferSize, 0.0f);
    int write_index_ = 0;
    float phase_ = 0.0f;
    float pitch_ratio_ = 1.0f;
    float phase_increment_ = 0.0f;
    float window_samples_ = 256.0f;
    float base_delay_samples_ = window_samples_ + 8.0f;

    static float compute_window_samples(int sample_rate_hz) {
        return clamp_float(static_cast<float>(sample_rate_hz) * 0.03f, 256.0f, 2048.0f);
    }

    void update_phase_increment() {
        if (std::abs(pitch_ratio_ - 1.0f) < 0.0001f) {
            phase_increment_ = 0.0f;
        } else {
            phase_increment_ = std::max(
                1.0f / 16384.0f,
                std::abs(1.0f - pitch_ratio_) / std::max(1.0f, window_samples_)
            );
        }
    }

    float compute_delay_samples(float local_phase) const {
        const float sweep = pitch_ratio_ >= 1.0f
            ? (1.0f - local_phase) * window_samples_
            : local_phase * window_samples_;
        return base_delay_samples_ + sweep;
    }

    float read_delayed(float delay_samples) const {
        float wrapped = static_cast<float>(write_index_) - delay_samples;
        while (wrapped < 0.0f) {
            wrapped += static_cast<float>(kRingBufferSize);
        }
        while (wrapped >= static_cast<float>(kRingBufferSize)) {
            wrapped -= static_cast<float>(kRingBufferSize);
        }
        const int index_a = static_cast<int>(wrapped) & kRingBufferMask;
        const int index_b = (index_a + 1) & kRingBufferMask;
        const float frac = wrapped - static_cast<float>(static_cast<int>(wrapped));
        return ring_buffer_[index_a] * (1.0f - frac) + ring_buffer_[index_b] * frac;
    }

    static float hann_window(float local_phase) {
        return clamp_float(0.5f - 0.5f * std::cos(local_phase * kTwoPi), 0.0f, 1.0f);
    }
};

class NativeVocalIsolationEngine {
public:
    NativeVocalIsolationEngine() : pitch_shifter_(sample_rate_hz_) {}

    void configure(int sample_rate_hz, int channel_count) {
        sample_rate_hz_ = sample_rate_hz > 0 ? sample_rate_hz : 48000;
        channel_count_ = channel_count;
        rebuild_filters();
        pitch_shifter_.configure_sample_rate(sample_rate_hz_);
        pitch_shifter_.set_pitch_semitones(vocal_key_shift_semitones_);
    }

    void update_config(bool vocal_removal_enabled, int key_shift_semitones, int low_cut_hz, int high_cut_hz) {
        vocal_removal_enabled_ = vocal_removal_enabled;
        vocal_key_shift_semitones_ = std::max(-24, std::min(24, key_shift_semitones));
        const int normalized_low = std::max(60, std::min(7900, low_cut_hz));
        const int normalized_high = std::max(normalized_low + 100, std::min(8000, high_cut_hz));
        const bool band_changed =
            vocal_band_low_cut_hz_ != normalized_low || vocal_band_high_cut_hz_ != normalized_high;
        vocal_band_low_cut_hz_ = normalized_low;
        vocal_band_high_cut_hz_ = normalized_high;
        pitch_shifter_.set_pitch_semitones(vocal_key_shift_semitones_);
        if (band_changed) {
            rebuild_filters();
        }
    }

    void process(const int16_t* input, int16_t* output, int frame_count) {
        const int key_shift = vocal_key_shift_semitones_;
        const bool should_remove_original_vocal = vocal_removal_enabled_ || key_shift != 0;
        const bool should_reinject_shifted_vocal = key_shift != 0;
        if (!should_remove_original_vocal && !should_reinject_shifted_vocal) {
            std::copy(input, input + frame_count * 2, output);
            return;
        }

        for (int frame_index = 0; frame_index < frame_count; ++frame_index) {
            const float left = short_to_float(input[frame_index * 2]);
            const float right = short_to_float(input[frame_index * 2 + 1]);

            const float filtered_left = left_low_pass_.process(left_high_pass_.process(left));
            const float filtered_right = right_low_pass_.process(right_high_pass_.process(right));
            const float isolated_vocal = extract_centered_vocal(filtered_left, filtered_right);

            float output_left = left;
            float output_right = right;
            if (should_remove_original_vocal) {
                output_left -= isolated_vocal;
                output_right -= isolated_vocal;
            }
            if (should_reinject_shifted_vocal) {
                const float shifted_vocal = pitch_shifter_.process_sample(isolated_vocal);
                output_left += shifted_vocal;
                output_right += shifted_vocal;
            }

            output[frame_index * 2] = float_to_short(output_left);
            output[frame_index * 2 + 1] = float_to_short(output_right);
        }
    }

    void flush() {
        reset_dsp_state();
    }

private:
    bool vocal_removal_enabled_ = false;
    int vocal_key_shift_semitones_ = 0;
    int vocal_band_low_cut_hz_ = 140;
    int vocal_band_high_cut_hz_ = 4200;
    int sample_rate_hz_ = 48000;
    int channel_count_ = 2;
    BiquadFilter left_high_pass_ = BiquadFilter::pass_through();
    BiquadFilter left_low_pass_ = BiquadFilter::pass_through();
    BiquadFilter right_high_pass_ = BiquadFilter::pass_through();
    BiquadFilter right_low_pass_ = BiquadFilter::pass_through();
    DualDelayPitchShifter pitch_shifter_;

    void reset_dsp_state() {
        left_high_pass_.reset();
        left_low_pass_.reset();
        right_high_pass_.reset();
        right_low_pass_.reset();
        pitch_shifter_.reset();
    }

    void rebuild_filters() {
        const float low_cut = clamp_float(static_cast<float>(vocal_band_low_cut_hz_), 60.0f, 7900.0f);
        const float high_cut = clamp_float(
            static_cast<float>(vocal_band_high_cut_hz_),
            low_cut + 100.0f,
            8000.0f
        );
        left_high_pass_ = build_high_pass(static_cast<float>(sample_rate_hz_), low_cut, 0.707f);
        left_low_pass_ = build_low_pass(static_cast<float>(sample_rate_hz_), high_cut, 0.707f);
        right_high_pass_ = build_high_pass(static_cast<float>(sample_rate_hz_), low_cut, 0.707f);
        right_low_pass_ = build_low_pass(static_cast<float>(sample_rate_hz_), high_cut, 0.707f);
        reset_dsp_state();
    }

    static float extract_centered_vocal(float filtered_left, float filtered_right) {
        const float center = (filtered_left + filtered_right) * 0.5f;
        const float abs_left = std::abs(filtered_left);
        const float abs_right = std::abs(filtered_right);
        const float energy = abs_left + abs_right + 1.0e-5f;
        const float similarity = 1.0f - clamp_float(std::abs(filtered_left - filtered_right) / energy, 0.0f, 1.0f);
        const float balance = clamp_float(
            std::min(abs_left, abs_right) / std::max(std::max(abs_left, abs_right), 1.0e-5f),
            0.0f,
            1.0f
        );
        const float confidence = clamp_float(similarity * balance, 0.0f, 1.0f);
        return clamp_float(center * confidence, -1.0f, 1.0f);
    }
};

inline NativeVocalIsolationEngine* from_handle(jlong handle) {
    return reinterpret_cast<NativeVocalIsolationEngine*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_hifx_audio_NativeVocalIsolationBridge_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new NativeVocalIsolationEngine());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeVocalIsolationBridge_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete from_handle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeVocalIsolationBridge_nativeConfigure(
    JNIEnv*,
    jobject,
    jlong handle,
    jint sample_rate_hz,
    jint channel_count
) {
    if (auto* engine = from_handle(handle)) {
        engine->configure(sample_rate_hz, channel_count);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeVocalIsolationBridge_nativeUpdateConfig(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean vocal_removal_enabled,
    jint vocal_key_shift_semitones,
    jint vocal_band_low_cut_hz,
    jint vocal_band_high_cut_hz
) {
    if (auto* engine = from_handle(handle)) {
        engine->update_config(
            vocal_removal_enabled == JNI_TRUE,
            vocal_key_shift_semitones,
            vocal_band_low_cut_hz,
            vocal_band_high_cut_hz
        );
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeVocalIsolationBridge_nativeProcess(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject input_buffer,
    jobject output_buffer,
    jint input_bytes
) {
    auto* engine = from_handle(handle);
    if (engine == nullptr || input_buffer == nullptr || output_buffer == nullptr || input_bytes <= 0) {
        return;
    }
    auto* input = static_cast<int16_t*>(env->GetDirectBufferAddress(input_buffer));
    auto* output = static_cast<int16_t*>(env->GetDirectBufferAddress(output_buffer));
    if (input == nullptr || output == nullptr) {
        return;
    }
    const int frame_count = input_bytes / 4;
    engine->process(input, output, frame_count);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeVocalIsolationBridge_nativeFlush(JNIEnv*, jobject, jlong handle) {
    if (auto* engine = from_handle(handle)) {
        engine->flush();
    }
}
