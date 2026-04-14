package com.example.hifx

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.hifx.audio.AudioEngine
import com.example.hifx.audio.SettingsUiState
import com.example.hifx.databinding.FragmentSettingsBinding
import com.example.hifx.util.AppHaptics
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var internalUpdating = false
    private var selectedSection: SettingsSection? = null
    private var usbSpinnerIds: List<Int?> = listOf(null)
    private var latestAudioPipelineDetails: String = ""
    private val outputSampleRateOptionsHz = listOf<Int?>(null, 44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
    private val bitDepthOptions = listOf(16, 32)
    private val bitrateOptionsKbps = listOf<Int?>(null, 320, 512, 768, 1024, 1536, 3072, 6144, 9216)

    private val openTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                AudioEngine.setScanFolderUri(uri)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupMenuActions()
        setupBackNavigation()
        setupSettingActions()
        val versionName = runCatching {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrDefault("1.0")
        binding.textAboutVersion.text = getString(
            R.string.settings_about_version,
            versionName
        )
        renderRootMenu()
        observeSettings()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupMenuActions() {
        binding.buttonMenuAppearance.setOnClickListener {
            AppHaptics.click(it)
            openSection(SettingsSection.APPEARANCE)
        }
        binding.buttonMenuPlayback.setOnClickListener {
            AppHaptics.click(it)
            openSection(SettingsSection.PLAYBACK)
        }
        binding.buttonMenuLyrics.setOnClickListener {
            AppHaptics.click(it)
            openSection(SettingsSection.LYRICS)
        }
        binding.buttonMenuLibrary.setOnClickListener {
            AppHaptics.click(it)
            openSection(SettingsSection.LIBRARY)
        }
        binding.buttonMenuOther.setOnClickListener {
            AppHaptics.click(it)
            openSection(SettingsSection.OTHER)
        }
        binding.buttonMenuAbout.setOnClickListener {
            AppHaptics.click(it)
            openSection(SettingsSection.ABOUT)
        }
        binding.buttonSubmenuBack.setOnClickListener {
            AppHaptics.click(it)
            renderRootMenu()
        }
    }

    private fun setupBackNavigation() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (selectedSection != null) {
                        renderRootMenu()
                        return
                    }
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        )
    }

    private fun setupSettingActions() {
        binding.buttonSelectScanFolder.setOnClickListener {
            AppHaptics.click(it)
            openTreeLauncher.launch(null)
        }
        binding.buttonClearScanFolder.setOnClickListener {
            AppHaptics.click(it)
            AudioEngine.setScanFolderUri(null)
        }
        binding.buttonRescanLibrary.setOnClickListener {
            AppHaptics.click(it)
            AudioEngine.refreshLibrary()
        }
        binding.switchHiFi.setOnCheckedChangeListener { _, isChecked ->
            if (!internalUpdating) {
                AppHaptics.click(requireContext())
                AudioEngine.setHiFiMode(isChecked)
            }
        }
        binding.switchHiResApi.setOnCheckedChangeListener { _, isChecked ->
            if (!internalUpdating) {
                AppHaptics.click(requireContext())
                AudioEngine.setHiResApiEnabled(isChecked)
            }
        }
        binding.switchRememberPlayback.setOnCheckedChangeListener { _, isChecked ->
            if (!internalUpdating) {
                AppHaptics.click(requireContext())
                AudioEngine.setRememberPlaybackSessionEnabled(isChecked)
            }
        }
        binding.switchHapticFeedback.setOnCheckedChangeListener { _, isChecked ->
            if (!internalUpdating) {
                AudioEngine.setHapticFeedbackEnabled(isChecked)
                if (isChecked) {
                    AppHaptics.click(requireContext())
                }
            }
        }
        binding.switchShowLyricsPanel.setOnCheckedChangeListener { _, isChecked ->
            if (!internalUpdating) {
                AppHaptics.click(requireContext())
                AudioEngine.setShowLyricsPanelEnabled(isChecked)
            }
        }
        binding.switchBackgroundDynamic.setOnCheckedChangeListener { _, isChecked ->
            if (!internalUpdating) {
                AppHaptics.click(requireContext())
                AudioEngine.setBackgroundDynamicEnabled(isChecked)
            }
        }
        binding.sliderBackgroundBlur.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || internalUpdating) {
                return@addOnChangeListener
            }
            AudioEngine.setBackgroundBlurStrength(value.roundToInt())
        }
        binding.sliderBackgroundOpacity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || internalUpdating) {
                return@addOnChangeListener
            }
            AudioEngine.setBackgroundOpacityPercent(value.roundToInt())
        }
        binding.sliderLyricsFontSize.addOnChangeListener { _, value, fromUser ->
            val sizeSp = value.roundToInt()
            binding.textLyricsFontSizeValue.text = getString(
                R.string.settings_lyrics_font_size_value_format,
                sizeSp
            )
            binding.textLyricsFontPreview.textSize = sizeSp.toFloat()
            if (!fromUser || internalUpdating) {
                return@addOnChangeListener
            }
            AudioEngine.setLyricsFontSizeSp(sizeSp)
        }
        binding.switchLyricsBold.setOnCheckedChangeListener { _, isChecked ->
            binding.textLyricsFontPreview.typeface =
                if (isChecked) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (!internalUpdating) {
                AppHaptics.click(requireContext())
                AudioEngine.setLyricsBoldEnabled(isChecked)
            }
        }
        binding.sliderLyricsGlowIntensity.addOnChangeListener { _, value, fromUser ->
            val intensity = value.roundToInt()
            binding.textLyricsGlowIntensityValue.text = getString(
                R.string.settings_lyrics_glow_intensity_value_format,
                intensity
            )
            if (!fromUser || internalUpdating) {
                return@addOnChangeListener
            }
            AudioEngine.setLyricsGlowIntensityPercent(intensity)
        }
        binding.switchUsbExclusive.setOnCheckedChangeListener { _, isChecked ->
            if (!internalUpdating) {
                AppHaptics.click(requireContext())
                AudioEngine.setUsbExclusiveModeEnabled(isChecked)
            }
        }
        setupAudioSelectionControls()
        binding.buttonRefreshDeviceInfo.setOnClickListener {
            AppHaptics.click(it)
            AudioEngine.refreshOutputInfo()
        }
        binding.buttonShowAudioPipelineDetails.setOnClickListener {
            AppHaptics.click(it)
            showAudioPipelineDetailsDialog()
        }
        binding.groupThemeMode.setOnCheckedChangeListener { _, checkedId ->
            if (internalUpdating) {
                return@setOnCheckedChangeListener
            }
            val mode = when (checkedId) {
                R.id.radio_theme_light -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.radio_theme_dark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AudioEngine.setThemeMode(mode)
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    private fun setupAudioSelectionControls() {
        binding.spinnerBitDepth.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            bitDepthOptions.map { "${it}-bit" }
        )
        binding.spinnerBitDepth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!internalUpdating) {
                    AudioEngine.setPreferredBitDepth(bitDepthOptions.getOrElse(position) { 32 })
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.spinnerOutputSampleRate.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            outputSampleRateOptionsHz.map { sampleRate ->
                if (sampleRate == null) {
                    getString(R.string.settings_output_sample_rate_auto)
                } else {
                    "${sampleRate / 1000.0} kHz"
                }
            }
        )
        binding.spinnerOutputSampleRate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!internalUpdating) {
                    AudioEngine.setPreferredOutputSampleRateHz(outputSampleRateOptionsHz.getOrNull(position))
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.spinnerMaxBitrate.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            bitrateOptionsKbps.map { kbps ->
                if (kbps == null) getString(R.string.settings_bitrate_unlimited) else "$kbps kbps"
            }
        )
        binding.spinnerMaxBitrate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!internalUpdating) {
                    AudioEngine.setPreferredMaxAudioBitrateKbps(bitrateOptionsKbps.getOrNull(position))
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.spinnerUsbDac.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!internalUpdating) {
                    AudioEngine.setPreferredUsbOutputDeviceId(usbSpinnerIds.getOrNull(position))
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AudioEngine.settingsState.collect { state ->
                    renderSettings(state)
                }
            }
        }
    }

    private fun renderSettings(state: SettingsUiState) {
        internalUpdating = true
        binding.textScanFolder.text = state.scanFolderLabel
        binding.switchHiFi.isChecked = state.hiFiMode
        binding.switchHiResApi.isChecked = state.hiResApiEnabled
        binding.switchRememberPlayback.isChecked = state.rememberPlaybackSessionEnabled
        binding.switchHapticFeedback.isChecked = state.hapticFeedbackEnabled
        binding.switchShowLyricsPanel.isChecked = state.showLyricsPanelEnabled
        binding.switchBackgroundDynamic.isChecked = state.backgroundDynamicEnabled
        binding.sliderBackgroundBlur.value = state.backgroundBlurStrength.toFloat()
        binding.sliderBackgroundOpacity.value = state.backgroundOpacityPercent.toFloat()
        binding.textBackgroundBlurValue.text = getString(
            R.string.settings_background_blur_value_format,
            state.backgroundBlurStrength
        )
        binding.textBackgroundOpacityValue.text = getString(
            R.string.settings_background_opacity_value_format,
            state.backgroundOpacityPercent
        )
        binding.sliderLyricsFontSize.value = state.lyricsFontSizeSp.toFloat()
        binding.textLyricsFontSizeValue.text = getString(
            R.string.settings_lyrics_font_size_value_format,
            state.lyricsFontSizeSp
        )
        binding.textLyricsFontPreview.textSize = state.lyricsFontSizeSp.toFloat()
        binding.switchLyricsBold.isChecked = state.lyricsBoldEnabled
        binding.textLyricsFontPreview.typeface =
            if (state.lyricsBoldEnabled) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        binding.sliderLyricsGlowIntensity.value = state.lyricsGlowIntensityPercent.toFloat()
        binding.textLyricsGlowIntensityValue.text = getString(
            R.string.settings_lyrics_glow_intensity_value_format,
            state.lyricsGlowIntensityPercent
        )
        binding.textSampleRate.text = getString(
            R.string.settings_sample_rate_value,
            state.outputSampleRateHz?.toString() ?: getString(R.string.unknown_value)
        )
        binding.textFramesPerBuffer.text = getString(
            R.string.settings_buffer_value,
            state.outputFramesPerBuffer?.toString() ?: getString(R.string.unknown_value)
        )
        binding.textOffloadSupport.text = getString(
            R.string.settings_offload_value,
            if (state.offloadSupported) getString(R.string.supported) else getString(R.string.not_supported)
        )
        val bitDepthIndex = bitDepthOptions.indexOf(state.preferredBitDepth).coerceAtLeast(0)
        binding.spinnerBitDepth.setSelection(bitDepthIndex, false)
        val sampleRateIndex = outputSampleRateOptionsHz.indexOf(state.preferredOutputSampleRateHz).coerceAtLeast(0)
        binding.spinnerOutputSampleRate.setSelection(sampleRateIndex, false)
        val bitrateIndex = bitrateOptionsKbps.indexOf(state.preferredMaxBitrateKbps).coerceAtLeast(0)
        binding.spinnerMaxBitrate.setSelection(bitrateIndex, false)
        renderUsbOptions(state)
        binding.textActiveRoute.text = getString(
            R.string.settings_active_route_value,
            state.activeOutputRouteLabel
        )
        binding.switchUsbExclusive.isChecked = state.usbExclusiveModeEnabled
        val hasSelectedUsb = state.preferredUsbDeviceId != null
        val canToggleExclusive = hasSelectedUsb
        binding.switchUsbExclusive.isEnabled = canToggleExclusive
        binding.textUsbExclusiveState.text = when {
            !hasSelectedUsb -> getString(R.string.settings_usb_exclusive_state_no_device)
            state.usbExclusiveActive && state.usbSrcBypassGuaranteed ->
                getString(R.string.settings_usb_exclusive_state_active)
            state.usbExclusiveActive ->
                getString(R.string.settings_usb_exclusive_state_active_unverified)
            state.usbCompatibilityActive -> getString(
                R.string.settings_usb_exclusive_state_compat_active,
                state.usbResolvedSampleRateHz?.toString() ?: getString(R.string.unknown_value),
                state.usbResolvedBitDepth?.toString() ?: getString(R.string.unknown_value)
            )
            !state.usbExclusiveSupported -> getString(R.string.settings_usb_exclusive_state_not_supported)
            state.usbExclusiveModeEnabled -> getString(R.string.settings_usb_exclusive_state_inactive)
            else -> getString(R.string.settings_usb_exclusive_state_default)
        }
        latestAudioPipelineDetails = state.audioPipelineDetails
        binding.textHiFiHint.text = if (state.hiFiMode) {
            getString(R.string.settings_hifi_on_hint)
        } else {
            getString(R.string.settings_hifi_off_hint)
        }
        when (state.themeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> binding.radioThemeLight.isChecked = true
            AppCompatDelegate.MODE_NIGHT_YES -> binding.radioThemeDark.isChecked = true
            else -> binding.radioThemeSystem.isChecked = true
        }
        internalUpdating = false
    }

    private fun renderUsbOptions(state: SettingsUiState) {
        usbSpinnerIds = listOf(null) + state.usbOutputOptions.map { it.id }
        val labels = listOf(getString(R.string.settings_route_auto)) + state.usbOutputOptions.map { it.label }
        binding.spinnerUsbDac.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        val selectedIndex = usbSpinnerIds.indexOf(state.preferredUsbDeviceId).takeIf { it >= 0 } ?: 0
        binding.spinnerUsbDac.setSelection(selectedIndex, false)
    }

    private fun showAudioPipelineDetailsDialog() {
        val raw = latestAudioPipelineDetails.ifBlank { getString(R.string.settings_pipeline_details_empty) }
        val explanation = buildAudioPipelineExplanation(raw)
        val message = buildString {
            append(getString(R.string.settings_pipeline_explain_header))
            append("\n")
            append(explanation)
            append("\n\n")
            append(getString(R.string.settings_pipeline_raw_header))
            append("\n")
            append(raw)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_pipeline_dialog_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
    private fun buildAudioPipelineExplanation(raw: String): String {
        if (raw.isBlank() || raw == getString(R.string.settings_pipeline_details_empty)) {
            return "当前没有可解释的链路数据，请先播放音频并点击“刷新设备输出信息”。"
        }
        val points = mutableListOf<String>()
        raw.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.contains("选中音轨") -> points += "选中音轨：当前真正参与播放的轨道。"
                line.contains("MIME") -> points += "编码格式：源文件编码类型（FLAC/AAC/MP3 等）。"
                line.contains("音轨采样率") -> points += "音轨采样率：文件内采样率，不一定等于实际输出采样率。"
                line.contains("音轨声道数") -> points += "音轨声道数：源轨道声道信息（2声道/多声道）。"
                line.contains("音轨码率") -> points += "音轨码率：压缩质量指标之一。"
                line.startsWith("解码器:") -> points += "解码器：当前实际被选中的解码器实现。"
                line.contains("目标输出采样率") -> points += "目标输出采样率：你的偏好目标值。"
                line.contains("系统输出采样率") -> points += "系统输出采样率：当前实际输出参数。"
                line.contains("Offload") -> points += "Offload支持：设备能力，非当前路径的绝对证明。"
                line.contains("USB独占开关") -> points += "USB独占：需同时看开关/支持/激活，激活=true 才表示已生效。"
                line.contains("USB兼容直通") -> points += "USB兼容直通：用于不支持独占的外置小尾巴，表示已锁定USB路由并按设备能力协商格式。"
                line.startsWith("Equalizer:") -> points += "EQ链路：展示是否启用及各频段增益。"
                line.startsWith("Convolution=") -> points += "卷积链路：展示 IR 与湿度参数，确认卷积是否生效。"
                line.startsWith("id=") -> points += "设备清单：系统枚举到的输出设备能力原始数据。"
            }
        }
        return if (points.isEmpty()) {
            "已显示原始链路信息。可重点关注：音轨采样率、系统输出采样率、USB独占激活状态、解码器。"
        } else {
            "• " + points.distinct().joinToString(separator = "\n• ")
        }
    }
    private fun openSection(section: SettingsSection) {
        selectedSection = section
        binding.layoutRootMenu.visibility = View.GONE
        binding.layoutSubmenuHeader.visibility = View.VISIBLE
        binding.textSubmenuTitle.setText(section.titleResId)
        renderVisibleSection(section)
    }

    private fun renderRootMenu() {
        selectedSection = null
        binding.layoutSubmenuHeader.visibility = View.GONE
        binding.layoutRootMenu.visibility = View.VISIBLE
        renderVisibleSection(null)
    }

    private fun renderVisibleSection(section: SettingsSection?) {
        binding.sectionAppearance.visibility = if (section == SettingsSection.APPEARANCE) View.VISIBLE else View.GONE
        binding.sectionPlayback.visibility = if (section == SettingsSection.PLAYBACK) View.VISIBLE else View.GONE
        binding.sectionLyrics.visibility = if (section == SettingsSection.LYRICS) View.VISIBLE else View.GONE
        binding.sectionLibrary.visibility = if (section == SettingsSection.LIBRARY) View.VISIBLE else View.GONE
        binding.sectionOther.visibility = if (section == SettingsSection.OTHER) View.VISIBLE else View.GONE
        binding.sectionAbout.visibility = if (section == SettingsSection.ABOUT) View.VISIBLE else View.GONE
    }

    private enum class SettingsSection {
        APPEARANCE,
        PLAYBACK,
        LYRICS,
        LIBRARY,
        OTHER,
        ABOUT;

        val titleResId: Int
            get() = when (this) {
                APPEARANCE -> R.string.settings_appearance_title
                PLAYBACK -> R.string.settings_playback_title
                LYRICS -> R.string.settings_lyrics_title
                LIBRARY -> R.string.settings_library_title
                OTHER -> R.string.settings_other_title
                ABOUT -> R.string.settings_about_title
            }
    }
}

