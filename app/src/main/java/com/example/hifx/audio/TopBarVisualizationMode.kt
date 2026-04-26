package com.example.hifx.audio

enum class TopBarVisualizationMode(
    val prefValue: String,
    val nativeMode: Int
) {
    AUDIO_INFO("audio_info", -1),
    LEVEL_METER("level_meter", 0),
    ANALOG_METER("analog_meter", 0),
    WAVEFORM("waveform", 1),
    BARS("bars", 2);

    companion object {
        fun fromPrefValue(value: String?): TopBarVisualizationMode {
            return values().firstOrNull { it.prefValue == value } ?: AUDIO_INFO
        }
    }
}
