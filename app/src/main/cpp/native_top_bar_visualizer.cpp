#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <vector>

namespace {

constexpr int MODE_LEVEL_METER = 0;
constexpr int MODE_WAVEFORM = 1;
constexpr int MODE_BARS = 2;
constexpr int MODE_ANALOG_METER = 3;
constexpr float PI = 3.14159265358979323846f;
constexpr int EQ_SPECTRUM_BIN_COUNT = 64;
constexpr int EQ_SPECTRUM_WINDOW_SIZE = 512;
constexpr int EQ_SPECTRUM_HOP_SIZE = 128;
constexpr int WAVEFORM_HISTORY_SIZE = 1024;
constexpr int BAR_HISTORY_SIZE = 512;
constexpr int BAR_BIN_COUNT = 48;
constexpr int DOWNSAMPLE_STRIDE = 12;
constexpr float LEVEL_RELEASE = 0.955f;
constexpr float ANALOG_LEVEL_ATTACK = 0.42f;
constexpr float ANALOG_LEVEL_RELEASE = 0.84f;
constexpr float BAR_ATTACK = 0.35f;
constexpr float BAR_RELEASE = 0.92f;
constexpr float BAR_CAP_RELEASE = 0.975f;
constexpr float EQ_SPECTRUM_ATTACK = 0.42f;
constexpr float EQ_SPECTRUM_RELEASE = 0.9f;

struct NativeTopBarVisualizer {
    std::mutex history_mutex;
    std::array<float, WAVEFORM_HISTORY_SIZE> waveform_history {};
    std::array<float, BAR_HISTORY_SIZE> bar_history {};
    std::array<float, BAR_BIN_COUNT> bar_bins {};
    std::array<float, BAR_BIN_COUNT> bar_caps {};
    std::array<float, EQ_SPECTRUM_WINDOW_SIZE> spectrum_history {};
    std::array<float, EQ_SPECTRUM_BIN_COUNT> eq_spectrum_bins {};
    int waveform_write_index = 0;
    int bar_write_index = 0;
    int spectrum_write_index = 0;
    int downsample_counter = 0;
    int spectrum_hop_counter = 0;
    int sample_rate_hz = 48'000;
    float waveform_accumulator = 0.0f;
    float energy_accumulator = 0.0f;
    float transient_accumulator = 0.0f;
    float left_energy_accumulator = 0.0f;
    float right_energy_accumulator = 0.0f;
    float left_peak_accumulator = 0.0f;
    float right_peak_accumulator = 0.0f;
    float previous_mono = 0.0f;
    std::atomic<float> left_level {0.0f};
    std::atomic<float> right_level {0.0f};
    std::atomic<float> analog_left_level {0.0f};
    std::atomic<float> analog_right_level {0.0f};

    void reset() {
        std::lock_guard<std::mutex> lock(history_mutex);
        waveform_history.fill(0.0f);
        bar_history.fill(0.0f);
        bar_bins.fill(0.0f);
        bar_caps.fill(0.0f);
        spectrum_history.fill(0.0f);
        eq_spectrum_bins.fill(0.0f);
        waveform_write_index = 0;
        bar_write_index = 0;
        spectrum_write_index = 0;
        downsample_counter = 0;
        spectrum_hop_counter = 0;
        waveform_accumulator = 0.0f;
        energy_accumulator = 0.0f;
        transient_accumulator = 0.0f;
        left_energy_accumulator = 0.0f;
        right_energy_accumulator = 0.0f;
        left_peak_accumulator = 0.0f;
        right_peak_accumulator = 0.0f;
        previous_mono = 0.0f;
        left_level.store(0.0f);
        right_level.store(0.0f);
        analog_left_level.store(0.0f);
        analog_right_level.store(0.0f);
    }

    void push_sample(float left, float right) {
        left = std::clamp(left, -1.0f, 1.0f);
        right = std::clamp(right, -1.0f, 1.0f);
        const float left_peak = std::fabs(left);
        const float right_peak = std::fabs(right);

        float previous_left = left_level.load();
        while (!left_level.compare_exchange_weak(
            previous_left,
            std::max(left_peak, previous_left * LEVEL_RELEASE)
        )) {
        }

        float previous_right = right_level.load();
        while (!right_level.compare_exchange_weak(
            previous_right,
            std::max(right_peak, previous_right * LEVEL_RELEASE)
        )) {
        }

        const float mono = (left + right) * 0.5f;
        spectrum_history[spectrum_write_index] = mono;
        spectrum_write_index = (spectrum_write_index + 1) % static_cast<int>(spectrum_history.size());
        spectrum_hop_counter += 1;
        waveform_accumulator += mono;
        energy_accumulator += std::fabs(mono);
        transient_accumulator += std::fabs(mono - previous_mono);
        left_energy_accumulator += left * left;
        right_energy_accumulator += right * right;
        left_peak_accumulator = std::max(left_peak_accumulator, left_peak);
        right_peak_accumulator = std::max(right_peak_accumulator, right_peak);
        previous_mono = mono;
        downsample_counter += 1;
        if (downsample_counter < DOWNSAMPLE_STRIDE) {
            return;
        }

        const float waveform_value = std::clamp(
            waveform_accumulator / static_cast<float>(downsample_counter),
            -1.0f,
            1.0f
        );
        const float bar_value = std::clamp(
            energy_accumulator / static_cast<float>(downsample_counter),
            0.0f,
            1.0f
        );
        const float transient_value = std::clamp(
            transient_accumulator / static_cast<float>(downsample_counter),
            0.0f,
            1.0f
        );
        const float left_rms = std::sqrt(
            left_energy_accumulator / static_cast<float>(downsample_counter)
        );
        const float right_rms = std::sqrt(
            right_energy_accumulator / static_cast<float>(downsample_counter)
        );
        const float left_analog_target = std::clamp(left_rms * 0.78f + left_peak_accumulator * 0.22f, 0.0f, 1.0f);
        const float right_analog_target = std::clamp(right_rms * 0.78f + right_peak_accumulator * 0.22f, 0.0f, 1.0f);
        const float previous_left_analog = analog_left_level.load();
        const float previous_right_analog = analog_right_level.load();
        analog_left_level.store(
            left_analog_target > previous_left_analog
                ? previous_left_analog + (left_analog_target - previous_left_analog) * ANALOG_LEVEL_ATTACK
                : previous_left_analog * ANALOG_LEVEL_RELEASE + left_analog_target * (1.0f - ANALOG_LEVEL_RELEASE)
        );
        analog_right_level.store(
            right_analog_target > previous_right_analog
                ? previous_right_analog + (right_analog_target - previous_right_analog) * ANALOG_LEVEL_ATTACK
                : previous_right_analog * ANALOG_LEVEL_RELEASE + right_analog_target * (1.0f - ANALOG_LEVEL_RELEASE)
        );

        {
            std::lock_guard<std::mutex> lock(history_mutex);
            waveform_history[waveform_write_index] = waveform_value;
            bar_history[bar_write_index] = bar_value;
            waveform_write_index = (waveform_write_index + 1) % static_cast<int>(waveform_history.size());
            bar_write_index = (bar_write_index + 1) % static_cast<int>(bar_history.size());
            update_bar_bins(bar_value, transient_value);
            if (spectrum_hop_counter >= EQ_SPECTRUM_HOP_SIZE) {
                update_eq_spectrum_bins();
                spectrum_hop_counter = 0;
            }
        }

        waveform_accumulator = 0.0f;
        energy_accumulator = 0.0f;
        transient_accumulator = 0.0f;
        left_energy_accumulator = 0.0f;
        right_energy_accumulator = 0.0f;
        left_peak_accumulator = 0.0f;
        right_peak_accumulator = 0.0f;
        downsample_counter = 0;
    }

    void update_bar_bins(float energy, float transient) {
        for (int index = 0; index < BAR_BIN_COUNT; ++index) {
            const float ratio = static_cast<float>(index) / static_cast<float>(BAR_BIN_COUNT - 1);
            const float weighting = std::pow(1.0f - ratio, 1.5f) * 0.7f + ratio * 0.9f;
            const float animated = 0.55f + 0.45f * std::sin(
                static_cast<float>(bar_write_index) * (0.07f + ratio * 0.035f) + ratio * 4.2f
            );
            const float target = std::clamp(
                energy * weighting + transient * (0.2f + ratio * 0.95f) * animated,
                0.0f,
                1.0f
            );
            const float previous = bar_bins[index];
            bar_bins[index] = target > previous
                ? previous + (target - previous) * BAR_ATTACK
                : previous * BAR_RELEASE + target * (1.0f - BAR_RELEASE);
            const float previous_cap = bar_caps[index];
            bar_caps[index] = std::max(bar_bins[index], previous_cap * BAR_CAP_RELEASE);
        }
    }

    void update_eq_spectrum_bins() {
        const float nyquist = std::max(1.0f, static_cast<float>(sample_rate_hz) * 0.5f);
        const float min_frequency = 20.0f;
        const float max_frequency = std::min(20'000.0f, nyquist * 0.96f);
        if (max_frequency <= min_frequency) {
            eq_spectrum_bins.fill(0.0f);
            return;
        }

        std::array<float, EQ_SPECTRUM_WINDOW_SIZE> windowed {};
        for (int index = 0; index < EQ_SPECTRUM_WINDOW_SIZE; ++index) {
            const int ring_index = (spectrum_write_index + index) % EQ_SPECTRUM_WINDOW_SIZE;
            const float sample = spectrum_history[ring_index];
            const float window = 0.5f - 0.5f * std::cos(
                2.0f * PI * static_cast<float>(index) /
                static_cast<float>(EQ_SPECTRUM_WINDOW_SIZE - 1)
            );
            windowed[index] = sample * window;
        }

        const float log_min = std::log(min_frequency);
        const float log_max = std::log(max_frequency);
        for (int bin = 0; bin < EQ_SPECTRUM_BIN_COUNT; ++bin) {
            const float ratio = EQ_SPECTRUM_BIN_COUNT == 1
                ? 0.0f
                : static_cast<float>(bin) / static_cast<float>(EQ_SPECTRUM_BIN_COUNT - 1);
            const float center_frequency = std::exp(log_min + (log_max - log_min) * ratio);
            float real = 0.0f;
            float imag = 0.0f;
            for (int sample_index = 0; sample_index < EQ_SPECTRUM_WINDOW_SIZE; ++sample_index) {
                const float phase = 2.0f * PI * center_frequency *
                    static_cast<float>(sample_index) / static_cast<float>(sample_rate_hz);
                real += windowed[sample_index] * std::cos(phase);
                imag -= windowed[sample_index] * std::sin(phase);
            }
            const float magnitude = std::sqrt(real * real + imag * imag) /
                static_cast<float>(EQ_SPECTRUM_WINDOW_SIZE);
            const float weighted = std::pow(ratio, 0.72f) * 0.18f + 0.82f;
            const float normalized = std::clamp(
                std::pow(magnitude * (8.5f + ratio * 13.0f) * weighted, 0.62f),
                0.0f,
                1.0f
            );
            const float previous = eq_spectrum_bins[bin];
            eq_spectrum_bins[bin] = normalized > previous
                ? previous + (normalized - previous) * EQ_SPECTRUM_ATTACK
                : previous * EQ_SPECTRUM_RELEASE + normalized * (1.0f - EQ_SPECTRUM_RELEASE);
        }
    }
};

NativeTopBarVisualizer* from_handle(jlong handle) {
    return reinterpret_cast<NativeTopBarVisualizer*>(handle);
}

template <size_t N>
void write_ring_snapshot(
    const std::array<float, N>& source,
    int write_index,
    jfloat* out,
    int size
) {
    const int source_size = static_cast<int>(N);
    if (size <= 0 || source_size <= 0) {
        return;
    }
    for (int index = 0; index < size; ++index) {
        const float ratio = size == 1
            ? 0.0f
            : static_cast<float>(index) / static_cast<float>(size - 1);
        const int sample_offset = static_cast<int>(ratio * static_cast<float>(source_size - 1));
        const int ring_index = (write_index + sample_offset) % source_size;
        out[index] = source[ring_index];
    }
}

void write_bar_snapshot(const NativeTopBarVisualizer& visualizer, jfloat* out, int size) {
    if (size <= 0) {
        return;
    }
    for (int index = 0; index < size; ++index) {
        const float ratio = size == 1
            ? 0.0f
            : static_cast<float>(index) / static_cast<float>(size - 1);
        const float source_index = ratio * static_cast<float>(BAR_BIN_COUNT - 1);
        const int left_index = static_cast<int>(source_index);
        const int right_index = std::min(BAR_BIN_COUNT - 1, left_index + 1);
        const float blend = source_index - static_cast<float>(left_index);
        const float base = visualizer.bar_bins[left_index] * (1.0f - blend) + visualizer.bar_bins[right_index] * blend;
        const float cap = visualizer.bar_caps[left_index] * (1.0f - blend) + visualizer.bar_caps[right_index] * blend;
        out[index] = std::clamp(base * 0.84f + cap * 0.16f, 0.0f, 1.0f);
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_alky_hifx_audio_NativeTopBarVisualizerBridge_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new NativeTopBarVisualizer());
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeTopBarVisualizerBridge_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete from_handle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeTopBarVisualizerBridge_nativeReset(JNIEnv*, jobject, jlong handle) {
    auto* visualizer = from_handle(handle);
    if (visualizer == nullptr) {
        return;
    }
    visualizer->reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeTopBarVisualizerBridge_nativeSetSampleRateHz(
    JNIEnv*,
    jobject,
    jlong handle,
    jint sample_rate_hz
) {
    auto* visualizer = from_handle(handle);
    if (visualizer == nullptr || sample_rate_hz <= 0) {
        return;
    }
    visualizer->sample_rate_hz = sample_rate_hz;
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeTopBarVisualizerBridge_nativeAnalyzePcm16(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject buffer,
    jint length_bytes,
    jint channel_count
) {
    auto* visualizer = from_handle(handle);
    auto* samples = reinterpret_cast<const int16_t*>(env->GetDirectBufferAddress(buffer));
    if (visualizer == nullptr || samples == nullptr || channel_count <= 0 || length_bytes <= 0) {
        return;
    }
    const int sample_count = length_bytes / static_cast<jint>(sizeof(int16_t));
    for (int index = 0; index + channel_count - 1 < sample_count; index += channel_count) {
        const float left = static_cast<float>(samples[index]) / 32768.0f;
        const float right = channel_count > 1
            ? static_cast<float>(samples[index + 1]) / 32768.0f
            : left;
        visualizer->push_sample(left, right);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeTopBarVisualizerBridge_nativeAnalyzeFloat(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject buffer,
    jint sample_count,
    jint channel_count
) {
    auto* visualizer = from_handle(handle);
    auto* samples = reinterpret_cast<const float*>(env->GetDirectBufferAddress(buffer));
    if (visualizer == nullptr || samples == nullptr || channel_count <= 0 || sample_count <= 0) {
        return;
    }
    for (int index = 0; index + channel_count - 1 < sample_count; index += channel_count) {
        const float left = samples[index];
        const float right = channel_count > 1 ? samples[index + 1] : left;
        visualizer->push_sample(left, right);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeTopBarVisualizerBridge_nativeFillSnapshot(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint mode,
    jfloatArray target
) {
    if (target == nullptr) {
        return;
    }
    const int safe_size = std::max(0, env->GetArrayLength(target));
    if (safe_size == 0) {
        return;
    }

    auto* visualizer = from_handle(handle);
    if (visualizer == nullptr) {
        return;
    }

    std::vector<jfloat> output(static_cast<size_t>(safe_size), 0.0f);
    if (mode == MODE_LEVEL_METER) {
        output[0] = visualizer->left_level.load();
        if (safe_size > 1) {
            output[1] = visualizer->right_level.load();
        }
    } else if (mode == MODE_ANALOG_METER) {
        output[0] = visualizer->analog_left_level.load();
        if (safe_size > 1) {
            output[1] = visualizer->analog_right_level.load();
        }
    } else {
        std::lock_guard<std::mutex> lock(visualizer->history_mutex);
        if (mode == MODE_WAVEFORM) {
            write_ring_snapshot(
                visualizer->waveform_history,
                visualizer->waveform_write_index,
                output.data(),
                safe_size
            );
        } else if (mode == MODE_BARS) {
            write_bar_snapshot(*visualizer, output.data(), safe_size);
        }
    }

    env->SetFloatArrayRegion(target, 0, safe_size, output.data());
}

extern "C" JNIEXPORT void JNICALL
Java_com_alky_hifx_audio_NativeTopBarVisualizerBridge_nativeFillEqSpectrumSnapshot(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloatArray target
) {
    if (target == nullptr) {
        return;
    }
    const int safe_size = std::max(0, env->GetArrayLength(target));
    if (safe_size == 0) {
        return;
    }

    auto* visualizer = from_handle(handle);
    if (visualizer == nullptr) {
        return;
    }

    std::vector<jfloat> output(static_cast<size_t>(safe_size), 0.0f);
    {
        std::lock_guard<std::mutex> lock(visualizer->history_mutex);
        for (int index = 0; index < safe_size; ++index) {
            const float ratio = safe_size == 1
                ? 0.0f
                : static_cast<float>(index) / static_cast<float>(safe_size - 1);
            const float source_index = ratio * static_cast<float>(EQ_SPECTRUM_BIN_COUNT - 1);
            const int left_index = static_cast<int>(source_index);
            const int right_index = std::min(EQ_SPECTRUM_BIN_COUNT - 1, left_index + 1);
            const float blend = source_index - static_cast<float>(left_index);
            output[index] = visualizer->eq_spectrum_bins[left_index] * (1.0f - blend) +
                visualizer->eq_spectrum_bins[right_index] * blend;
        }
    }
    env->SetFloatArrayRegion(target, 0, safe_size, output.data());
}
