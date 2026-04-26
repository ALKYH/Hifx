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
constexpr int WAVEFORM_HISTORY_SIZE = 1024;
constexpr int BAR_HISTORY_SIZE = 512;
constexpr int BAR_BIN_COUNT = 48;
constexpr int DOWNSAMPLE_STRIDE = 12;
constexpr float LEVEL_RELEASE = 0.955f;
constexpr float BAR_ATTACK = 0.35f;
constexpr float BAR_RELEASE = 0.92f;
constexpr float BAR_CAP_RELEASE = 0.975f;

struct NativeTopBarVisualizer {
    std::mutex history_mutex;
    std::array<float, WAVEFORM_HISTORY_SIZE> waveform_history {};
    std::array<float, BAR_HISTORY_SIZE> bar_history {};
    std::array<float, BAR_BIN_COUNT> bar_bins {};
    std::array<float, BAR_BIN_COUNT> bar_caps {};
    int waveform_write_index = 0;
    int bar_write_index = 0;
    int downsample_counter = 0;
    float waveform_accumulator = 0.0f;
    float energy_accumulator = 0.0f;
    float transient_accumulator = 0.0f;
    float previous_mono = 0.0f;
    std::atomic<float> left_level {0.0f};
    std::atomic<float> right_level {0.0f};

    void reset() {
        std::lock_guard<std::mutex> lock(history_mutex);
        waveform_history.fill(0.0f);
        bar_history.fill(0.0f);
        bar_bins.fill(0.0f);
        bar_caps.fill(0.0f);
        waveform_write_index = 0;
        bar_write_index = 0;
        downsample_counter = 0;
        waveform_accumulator = 0.0f;
        energy_accumulator = 0.0f;
        transient_accumulator = 0.0f;
        previous_mono = 0.0f;
        left_level.store(0.0f);
        right_level.store(0.0f);
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
        waveform_accumulator += mono;
        energy_accumulator += std::fabs(mono);
        transient_accumulator += std::fabs(mono - previous_mono);
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

        {
            std::lock_guard<std::mutex> lock(history_mutex);
            waveform_history[waveform_write_index] = waveform_value;
            bar_history[bar_write_index] = bar_value;
            waveform_write_index = (waveform_write_index + 1) % static_cast<int>(waveform_history.size());
            bar_write_index = (bar_write_index + 1) % static_cast<int>(bar_history.size());
            update_bar_bins(bar_value, transient_value);
        }

        waveform_accumulator = 0.0f;
        energy_accumulator = 0.0f;
        transient_accumulator = 0.0f;
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
Java_com_example_hifx_audio_NativeTopBarVisualizerBridge_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new NativeTopBarVisualizer());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeTopBarVisualizerBridge_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete from_handle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeTopBarVisualizerBridge_nativeReset(JNIEnv*, jobject, jlong handle) {
    auto* visualizer = from_handle(handle);
    if (visualizer == nullptr) {
        return;
    }
    visualizer->reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_hifx_audio_NativeTopBarVisualizerBridge_nativeAnalyzePcm16(
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
Java_com_example_hifx_audio_NativeTopBarVisualizerBridge_nativeAnalyzeFloat(
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
Java_com_example_hifx_audio_NativeTopBarVisualizerBridge_nativeFillSnapshot(
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
