package com.example.hifx

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hifx.audio.AudioEngine
import com.example.hifx.audio.SettingsUiState
import com.example.hifx.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var internalUpdating = false
    private var selectedSection: SettingsSection = SettingsSection.LIBRARY

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
        setupSettingActions()
        val versionName = runCatching {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrDefault("1.0")
        binding.textAboutVersion.text = getString(
            R.string.settings_about_version,
            versionName
        )
        renderSection(SettingsSection.LIBRARY)
        observeSettings()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupMenuActions() {
        binding.buttonMenuLibrary.setOnClickListener { renderSection(SettingsSection.LIBRARY) }
        binding.buttonMenuAudio.setOnClickListener { renderSection(SettingsSection.AUDIO) }
        binding.buttonMenuTheme.setOnClickListener { renderSection(SettingsSection.THEME) }
        binding.buttonMenuAbout.setOnClickListener { renderSection(SettingsSection.ABOUT) }
    }

    private fun setupSettingActions() {
        binding.buttonSelectScanFolder.setOnClickListener {
            openTreeLauncher.launch(null)
        }
        binding.buttonClearScanFolder.setOnClickListener {
            AudioEngine.setScanFolderUri(null)
        }
        binding.buttonRescanLibrary.setOnClickListener {
            AudioEngine.refreshLibrary()
        }
        binding.switchHiFi.setOnCheckedChangeListener { _, isChecked ->
            if (!internalUpdating) {
                AudioEngine.setHiFiMode(isChecked)
            }
        }
        binding.buttonRefreshDeviceInfo.setOnClickListener {
            AudioEngine.refreshOutputInfo()
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

    private fun renderSection(section: SettingsSection) {
        selectedSection = section
        binding.sectionLibrary.visibility = if (section == SettingsSection.LIBRARY) View.VISIBLE else View.GONE
        binding.sectionAudio.visibility = if (section == SettingsSection.AUDIO) View.VISIBLE else View.GONE
        binding.sectionTheme.visibility = if (section == SettingsSection.THEME) View.VISIBLE else View.GONE
        binding.sectionAbout.visibility = if (section == SettingsSection.ABOUT) View.VISIBLE else View.GONE

        updateMenuSelectedStyle(binding.buttonMenuLibrary, section == SettingsSection.LIBRARY)
        updateMenuSelectedStyle(binding.buttonMenuAudio, section == SettingsSection.AUDIO)
        updateMenuSelectedStyle(binding.buttonMenuTheme, section == SettingsSection.THEME)
        updateMenuSelectedStyle(binding.buttonMenuAbout, section == SettingsSection.ABOUT)
    }

    private fun updateMenuSelectedStyle(button: com.google.android.material.button.MaterialButton, selected: Boolean) {
        button.alpha = if (selected) 1f else 0.65f
    }

    private enum class SettingsSection {
        LIBRARY,
        AUDIO,
        THEME,
        ABOUT
    }
}
