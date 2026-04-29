#include <jni.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

namespace {

constexpr int kStereo = 0;
constexpr int kSurround51 = 1;
constexpr int kSurround71 = 2;

constexpr int kFrontLeft = 0;
constexpr int kFrontRight = 1;
constexpr int kCenter = 2;
constexpr int kLfe = 3;
constexpr int kSideLeft = 4;
constexpr int kSideRight = 5;
constexpr int kRearLeft = 6;
constexpr int kRearRight = 7;
constexpr int kSpeakerCount = 8;

constexpr float kPi = 3.14159265358979323846f;
constexpr int kDelayBufferSize = 8192;
constexpr int kDelayBufferMask = kDelayBufferSize - 1;

inline float clamp_unit(float value) {
    return std::clamp(value, -1.0f, 1.0f);
}

inline float short_to_float(int16_t sample) {
    return static_cast<float>(sample) / 32768.0f;
}

inline int16_t float_to_short(float sample) {
    const float clamped = clamp_unit(sample);
    if (clamped >= 0.0f) {
        return static_cast<int16_t>(std::min(32767.0f, clamped * 32767.0f));
    }
    return static_cast<int16_t>(std::max(-32768.0f, clamped * 32768.0f));
}

struct SpeakerProfile {
    float left_delay_samples = 0.0f;
    float right_delay_samples = 0.0f;
    float left_gain = 1.0f;
    float right_gain = 1.0f;
    float left_lp_coeff = 1.0f;
    float right_lp_coeff = 1.0f;
};

struct SpeakerState {
    std::array<float, kDelayBufferSize> source_delay {};
    int write_index = 0;
    float left_lp_state = 0.0f;
    float right_lp_state = 0.0f;

    void clear() {
        source_delay.fill(0.0f);
        write_index = 0;
        left_lp_state = 0.0f;
        right_lp_state = 0.0f;
    }
};

float one_pole_coeff(float cutoff_hz, int sample_rate_hz) {
    if (cutoff_hz <= 0.0f || sample_rate_hz <= 0) {
        return 1.0f;
    }
    const float omega = 2.0f * kPi * cutoff_hz / static_cast<float>(sample_rate_hz);
    return std::clamp(omega / (omega + 1.0f), 0.02f, 1.0f);
}

SpeakerProfile build_profile(float azimuth_deg, int sample_rate_hz) {
    SpeakerProfile profile;
    const float azimuth_rad = azimuth_deg * kPi / 180.0f;
    const float lateral = std::sin(azimuth_rad);
    const float abs_lateral = std::fabs(lateral);
    const float itd_seconds = (0.00023f + 0.00010f * abs_lateral) * lateral;
    const float itd_samples = itd_seconds * static_cast<float>(sample_rate_hz);

    if (itd_samples >= 0.0f) {
        profile.left_delay_samples = 0.0f;
        profile.right_delay_samples = itd_samples;
    } else {
        profile.left_delay_samples = -itd_samples;
        profile.right_delay_samples = 0.0f;
    }

    const float near_gain = 0.92f + (1.0f - abs_lateral) * 0.08f;
    const float far_gain = 0.30f + (1.0f - abs_lateral) * 0.28f;
    const float far_cutoff_hz = 2400.0f + (1.0f - abs_lateral) * 3600.0f;
    const float far_lp = one_pole_coeff(far_cutoff_hz, sample_rate_hz);

    if (lateral >= 0.0f) {
        profile.left_gain = far_gain;
        profile.right_gain = near_gain;
        profile.left_lp_coeff = far_lp;
        profile.right_lp_coeff = 1.0f;
    } else {
        profile.left_gain = near_gain;
        profile.right_gain = far_gain;
        profile.left_lp_coeff = 1.0f;
        profile.right_lp_coeff = far_lp;
    }
    return profile;
}

float read_delayed(const SpeakerState& state, float delay_samples) {
    float read_index = static_cast<float>(state.write_index) - delay_samples;
    while (read_index < 0.0f) {
        read_index += static_cast<float>(kDelayBufferSize);
    }
    while (read_index >= static_cast<float>(kDelayBufferSize)) {
        read_index -= static_cast<float>(kDelayBufferSize);
    }
    const int index_a = static_cast<int>(read_index) & kDelayBufferMask;
    const int index_b = (index_a + 1) & kDelayBufferMask;
    const float frac = read_index - std::floor(read_index);
    return state.source_delay[index_a] * (1.0f - frac) + state.source_delay[index_b] * frac;
}

class NativeSurroundEngine {
public:
    void configure(int sample_rate_hz, int channel_count) {
        sample_rate_hz_ = sample_rate_hz > 0 ? sample_rate_hz : 48000;
        channel_count_ = channel_count > 0 ? channel_count : 2;
        rebuild_profiles();
        flush();
    }

    void update_config(
        bool enabled,
        int surround_mode,
        float front_left_gain,
        float front_right_gain,
        float center_gain,
        float lfe_gain,
        float surround_left_gain,
        float surround_right_gain,
        float rear_left_gain,
        float rear_right_gain
    ) {
        enabled_ = enabled;
        surround_mode_ = std::clamp(surround_mode, kStereo, kSurround71);
        speaker_gains_[kFrontLeft] = std::clamp(front_left_gain, 0.0f, 2.0f);
        speaker_gains_[kFrontRight] = std::clamp(front_right_gain, 0.0f, 2.0f);
        speaker_gains_[kCenter] = std::clamp(center_gain, 0.0f, 2.0f);
        speaker_gains_[kLfe] = std::clamp(lfe_gain, 0.0f, 2.0f);
        speaker_gains_[kSideLeft] = std::clamp(surround_left_gain, 0.0f, 2.0f);
        speaker_gains_[kSideRight] = std::clamp(surround_right_gain, 0.0f, 2.0f);
        speaker_gains_[kRearLeft] = std::clamp(rear_left_gain, 0.0f, 2.0f);
        speaker_gains_[kRearRight] = std::clamp(rear_right_gain, 0.0f, 2.0f);
        rebuild_profiles();
    }

    void flush() {
        for (auto& state : speaker_states_) {
            state.clear();
        }
        diffuse_l_state_ = 0.0f;
        diffuse_r_state_ = 0.0f;
        lfe_state_ = 0.0f;
    }

    void process(
        const int16_t* input,
        int16_t* output,
        int frame_count
    ) {
        if (!enabled_ || surround_mode_ == kStereo || input == nullptr || output == nullptr || frame_count <= 0) {
            std::copy(input, input + frame_count * 2, output);
            return;
        }

        for (int frame = 0; frame < frame_count; ++frame) {
            const float in_left = short_to_float(input[frame * 2]);
            const float in_right = short_to_float(input[frame * 2 + 1]);
            const float mono = (in_left + in_right) * 0.5f;
            const float side = (in_left - in_right) * 0.5f;

            const float front_left = in_left * speaker_gains_[kFrontLeft];
            const float front_right = in_right * speaker_gains_[kFrontRight];
            const float center = mono * 0.78f * speaker_gains_[kCenter];

            lfe_state_ += (mono - lfe_state_) * lfe_coeff_;
            const float lfe = lfe_state_ * 0.92f * speaker_gains_[kLfe];

            diffuse_l_state_ += ((side + mono * 0.16f) - diffuse_l_state_) * diffuse_coeff_;
            diffuse_r_state_ += (((-side) + mono * 0.16f) - diffuse_r_state_) * diffuse_coeff_;

            const float side_left = diffuse_l_state_ * 0.86f * speaker_gains_[kSideLeft];
            const float side_right = diffuse_r_state_ * 0.86f * speaker_gains_[kSideRight];
            const float rear_left = diffuse_l_state_ * 0.66f * speaker_gains_[kRearLeft];
            const float rear_right = diffuse_r_state_ * 0.66f * speaker_gains_[kRearRight];

            float out_left = 0.0f;
            float out_right = 0.0f;

            render_speaker(kFrontLeft, front_left, out_left, out_right);
            render_speaker(kFrontRight, front_right, out_left, out_right);
            render_speaker(kCenter, center, out_left, out_right);
            render_speaker(kLfe, lfe, out_left, out_right);
            render_speaker(kSideLeft, side_left, out_left, out_right);
            render_speaker(kSideRight, side_right, out_left, out_right);
            if (surround_mode_ == kSurround71) {
                render_speaker(kRearLeft, rear_left, out_left, out_right);
                render_speaker(kRearRight, rear_right, out_left, out_right);
            }

            const float dry_mix = 0.18f;
            output[frame * 2] = float_to_short(clamp_unit(out_left + in_left * dry_mix));
            output[frame * 2 + 1] = float_to_short(clamp_unit(out_right + in_right * dry_mix));
        }
    }

private:
    void rebuild_profiles() {
        profiles_[kFrontLeft] = build_profile(-30.0f, sample_rate_hz_);
        profiles_[kFrontRight] = build_profile(30.0f, sample_rate_hz_);
        profiles_[kCenter] = build_profile(0.0f, sample_rate_hz_);
        profiles_[kLfe] = build_profile(0.0f, sample_rate_hz_);
        profiles_[kSideLeft] = build_profile(-110.0f, sample_rate_hz_);
        profiles_[kSideRight] = build_profile(110.0f, sample_rate_hz_);
        profiles_[kRearLeft] = build_profile(-150.0f, sample_rate_hz_);
        profiles_[kRearRight] = build_profile(150.0f, sample_rate_hz_);
        lfe_coeff_ = one_pole_coeff(120.0f, sample_rate_hz_);
        diffuse_coeff_ = one_pole_coeff(3400.0f, sample_rate_hz_);
    }

    void render_speaker(int speaker_index, float sample, float& out_left, float& out_right) {
        auto& state = speaker_states_[speaker_index];
        const auto& profile = profiles_[speaker_index];
        state.source_delay[state.write_index] = sample;

        float left = read_delayed(state, profile.left_delay_samples) * profile.left_gain;
        float right = read_delayed(state, profile.right_delay_samples) * profile.right_gain;

        state.left_lp_state += (left - state.left_lp_state) * profile.left_lp_coeff;
        state.right_lp_state += (right - state.right_lp_state) * profile.right_lp_coeff;

        out_left += state.left_lp_state;
        out_right += state.right_lp_state;
        state.write_index = (state.write_index + 1) & kDelayBufferMask;
    }

    int sample_rate_hz_ = 48000;
    int channel_count_ = 2;
    bool enabled_ = false;
    int surround_mode_ = kStereo;
    std::array<float, kSpeakerCount> speaker_gains_ {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
    std::array<SpeakerProfile, kSpeakerCount> profiles_ {};
    std::array<SpeakerState, kSpeakerCount> speaker_states_ {};
    float diffuse_l_state_ = 0.0f;
    float diffuse_r_state_ = 0.0f;
    float lfe_state_ = 0.0f;
    float lfe_coeff_ = 1.0f;
    float diffuse_coeff_ = 1.0f;
};

NativeSurroundEngine* from_handle(jlong handle) {
    return reinterpret_cast<NativeSurroundEngine*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_alky_hifx_audio_NativeSurroundBridge_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new NativeSurroundEngine());
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeSurroundBridge_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete from_handle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeSurroundBridge_nativeConfigure(
    JNIEnv*,
    jobject,
    jlong handle,
    jint sample_rate_hz,
    jint channel_count
) {
    auto* engine = from_handle(handle);
    if (engine == nullptr) return;
    engine->configure(static_cast<int>(sample_rate_hz), static_cast<int>(channel_count));
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeSurroundBridge_nativeUpdateConfig(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean enabled,
    jint surround_mode,
    jfloat front_left_gain,
    jfloat front_right_gain,
    jfloat center_gain,
    jfloat lfe_gain,
    jfloat surround_left_gain,
    jfloat surround_right_gain,
    jfloat rear_left_gain,
    jfloat rear_right_gain
) {
    auto* engine = from_handle(handle);
    if (engine == nullptr) return;
    engine->update_config(
        enabled == JNI_TRUE,
        static_cast<int>(surround_mode),
        static_cast<float>(front_left_gain),
        static_cast<float>(front_right_gain),
        static_cast<float>(center_gain),
        static_cast<float>(lfe_gain),
        static_cast<float>(surround_left_gain),
        static_cast<float>(surround_right_gain),
        static_cast<float>(rear_left_gain),
        static_cast<float>(rear_right_gain)
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeSurroundBridge_nativeProcess(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject input_buffer,
    jobject output_buffer,
    jint input_bytes
) {
    auto* engine = from_handle(handle);
    if (engine == nullptr || input_bytes <= 0) return;

    auto* input = static_cast<int16_t*>(env->GetDirectBufferAddress(input_buffer));
    auto* output = static_cast<int16_t*>(env->GetDirectBufferAddress(output_buffer));
    if (input == nullptr || output == nullptr) return;

    engine->process(input, output, static_cast<int>(input_bytes) / 4);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeSurroundBridge_nativeFlush(JNIEnv*, jobject, jlong handle) {
    auto* engine = from_handle(handle);
    if (engine == nullptr) return;
    engine->flush();
}
