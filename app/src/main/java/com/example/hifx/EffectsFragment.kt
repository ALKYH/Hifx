package com.example.hifx

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hifx.audio.AudioEngine
import com.example.hifx.audio.EffectsUiState
import com.example.hifx.databinding.FragmentEffectsBinding
import com.example.hifx.databinding.ItemEqBandVerticalBinding
import com.example.hifx.ui.SpatialPadView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

class EffectsFragment : Fragment() {
    private var _binding: FragmentEffectsBinding? = null
    private val binding get() = _binding!!

    private var internalUpdating = false
    private var channelPanelExpanded = false
    private var selectedHandle: SpatialPadView.Handle = SpatialPadView.Handle.LEFT
    private lateinit var eqBandBindings: List<ItemEqBandVerticalBinding>

    private val irPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                if (!isIrsFile(uri)) {
                    Toast.makeText(requireContext(), getString(R.string.effects_irs_file_required), Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                runCatching {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                AudioEngine.importConvolutionIr(uri)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEffectsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        eqBandBindings = listOf(
            binding.band1,
            binding.band2,
            binding.band3,
            binding.band4,
            binding.band5,
            binding.band6,
            binding.band7,
            binding.band8
        )
        setupControls()
        setupTabs()
        observeEffectState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupControls() {
        binding.switchEffectsEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (!internalUpdating) {
                AudioEngine.setEffectsEnabled(isChecked)
            }
        }
        binding.switchSpatialEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (!internalUpdating) {
                AudioEngine.setSpatialEnabled(isChecked)
            }
        }

        binding.cardChannelPosition.setOnClickListener {
            channelPanelExpanded = !channelPanelExpanded
            updateChannelPanelVisibility()
        }
        binding.switchChannelSeparated.setOnCheckedChangeListener { _, checked ->
            if (!internalUpdating) {
                AudioEngine.setChannelSeparated(checked)
            }
        }

        binding.buttonSelectLeft.setOnClickListener {
            if (!internalUpdating) {
                updateSelectedHandle(SpatialPadView.Handle.LEFT, fromUser = true)
            }
        }
        binding.buttonSelectRight.setOnClickListener {
            if (!internalUpdating) {
                updateSelectedHandle(SpatialPadView.Handle.RIGHT, fromUser = true)
            }
        }

        binding.toggleSurroundMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || internalUpdating) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.button_surround_stereo -> AudioEngine.setSurroundMode(AudioEngine.SURROUND_MODE_STEREO)
                R.id.button_surround_5_1 -> AudioEngine.setSurroundMode(AudioEngine.SURROUND_MODE_5_1)
                R.id.button_surround_7_1 -> AudioEngine.setSurroundMode(AudioEngine.SURROUND_MODE_7_1)
            }
        }

        bindSlider(binding.sliderBass) { AudioEngine.setBassStrength(it) }
        bindSlider(binding.sliderVirtualizer) { AudioEngine.setVirtualizerStrength(it) }
        bindSlider(binding.sliderLoudness) { AudioEngine.setLoudnessGainMb(it) }

        binding.switchConvolutionEnabled.setOnCheckedChangeListener { _, checked ->
            if (!internalUpdating) {
                AudioEngine.setConvolutionEnabled(checked)
            }
        }
        binding.buttonImportIrs.setOnClickListener {
            irPickerLauncher.launch(arrayOf("*/*"))
        }
        binding.buttonClearIrs.setOnClickListener {
            AudioEngine.clearConvolutionIr()
        }
        bindSlider(binding.sliderConvolutionWet) { AudioEngine.setConvolutionWetPercent(it) }

        bindSlider(binding.sliderRadiusSelected) { AudioEngine.setSpatialRadiusPercent(it) }
        bindSlider(binding.sliderPosXSelected) { applySelectedX(it) }
        bindSlider(binding.sliderPosYSelected) { applySelectedY(it) }
        bindSlider(binding.sliderPosZSelected) { applySelectedZ(it) }

        binding.spatialPadDual.onSelectionChanged = { selected, fromUser ->
            if (fromUser && !internalUpdating) {
                updateSelectedHandle(selected, fromUser = true)
            }
        }
        binding.spatialPadDual.onPositionChanged = { leftX, leftZ, rightX, rightZ, selected, fromUser ->
            if (fromUser && !internalUpdating) {
                val state = AudioEngine.effectsState.value
                if (!state.channelSeparated) {
                    AudioEngine.setSpatialPositionX(leftX)
                    AudioEngine.setSpatialPositionZ(leftZ)
                } else {
                    if (selected == SpatialPadView.Handle.LEFT) {
                        AudioEngine.setSpatialLeftPositionX(leftX)
                        AudioEngine.setSpatialLeftPositionZ(leftZ)
                    } else {
                        AudioEngine.setSpatialRightPositionX(rightX)
                        AudioEngine.setSpatialRightPositionZ(rightZ)
                    }
                }
            }
        }

        eqBandBindings.forEachIndexed { index, bandBinding ->
            val slider = bandBinding.seekEq
            slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser && !internalUpdating) {
                    val state = AudioEngine.effectsState.value
                    val mb = progressToMb(value.roundToInt(), state)
                    AudioEngine.setEqBandLevel(index, mb)
                }
            }
        }
    }

    private fun setupTabs() {
        binding.tabEffects.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                renderEffectTab(tab?.position ?: 0)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })
        renderEffectTab(0)
    }

    private fun renderEffectTab(position: Int) {
        binding.panelEq.isVisible = position == 0
        binding.panelReverb.isVisible = position == 1
        binding.panelOther.isVisible = position == 2
    }

    private fun observeEffectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AudioEngine.effectsState.collect { state ->
                    renderEffectState(state)
                }
            }
        }
    }

    private fun renderEffectState(state: EffectsUiState) {
        internalUpdating = true

        binding.switchEffectsEnabled.isChecked = state.enabled
        binding.effectsContainer.alpha = if (state.enabled) 1f else 0.45f
        binding.switchSpatialEnabled.isChecked = state.spatialEnabled
        binding.progressReverbMeter.progress = state.realtimeReverbMeterPercent
        binding.textReverbMeterValue.text = getString(
            R.string.effects_reverb_meter_value,
            state.realtimeReverbMeterPercent
        )

        binding.sliderBass.value = state.bassStrength.toFloat()
        binding.sliderVirtualizer.value = state.virtualizerStrength.toFloat()
        binding.sliderLoudness.value = state.loudnessGainMb.toFloat()
        binding.textBassValue.text = getString(R.string.effect_percent_value, state.bassStrength / 10)
        binding.textVirtualizerValue.text = getString(R.string.effect_percent_value, state.virtualizerStrength / 10)
        binding.textLoudnessValue.text = getString(R.string.effect_gain_value, state.loudnessGainMb)

        binding.textSpatialDistance.text = getString(
            R.string.effects_spatial_distance_value,
            "%.2f".format(state.derivedDistanceMeters)
        )
        binding.textSpatialGain.text = getString(
            R.string.effects_spatial_gain_value,
            "%.2f".format(state.derivedGainDb)
        )
        binding.textChannelModeValue.text = if (state.channelSeparated) {
            getString(R.string.effects_channel_mode_split)
        } else {
            getString(R.string.effects_channel_mode_linked)
        }
        binding.textChannelPositionValue.text = buildChannelSummary(state)
        binding.switchConvolutionEnabled.isChecked = state.convolutionEnabled
        binding.textConvolutionIrName.text = getString(
            R.string.effects_convolution_ir_name_value,
            state.convolutionIrName
        )
        binding.sliderConvolutionWet.value = state.convolutionWetPercent.toFloat()
        binding.textConvolutionWetValue.text = getString(
            R.string.effects_convolution_wet_value,
            state.convolutionWetPercent
        )
        binding.buttonClearIrs.isEnabled = !state.convolutionIrUri.isNullOrBlank()
        binding.switchConvolutionEnabled.isEnabled = !state.convolutionIrUri.isNullOrBlank()

        renderSurroundMode(state.surroundMode)
        renderChannelPanel(state)

        eqBandBindings.forEachIndexed { index, bandBinding ->
            val slider = bandBinding.seekEq
            val gainView = bandBinding.textEqGain
            val freqView = bandBinding.textEqFreq

            val level = state.eqBandLevelsMb.getOrElse(index) { 0 }
            val maxProgress = state.eqBandLevelMaxMb - state.eqBandLevelMinMb
            if (slider.valueFrom != 0f) {
                slider.valueFrom = 0f
            }
            if (slider.valueTo != maxProgress.toFloat()) {
                slider.valueTo = maxProgress.toFloat()
            }
            slider.value = mbToProgress(level, state).toFloat()

            gainView.text = getString(R.string.effects_gain_db_value, level / 100f)
            val freq = state.eqBandFrequenciesHz.getOrElse(index) { 0 }
            freqView.text = if (freq >= 1000) {
                getString(R.string.effects_freq_khz_value, freq / 1000f)
            } else {
                getString(R.string.effects_freq_hz_value, freq)
            }
        }

        internalUpdating = false
    }

    private fun renderSurroundMode(mode: Int) {
        val buttonId = when (mode) {
            AudioEngine.SURROUND_MODE_5_1 -> R.id.button_surround_5_1
            AudioEngine.SURROUND_MODE_7_1 -> R.id.button_surround_7_1
            else -> R.id.button_surround_stereo
        }
        if (binding.toggleSurroundMode.checkedButtonId != buttonId) {
            binding.toggleSurroundMode.check(buttonId)
        }
    }

    private fun renderChannelPanel(state: EffectsUiState) {
        binding.switchChannelSeparated.isChecked = state.channelSeparated
        updateChannelPanelVisibility()

        val displayRadius = max(state.spatialLeftRadiusPercent, state.spatialRightRadiusPercent).coerceIn(20, 100)
        binding.spatialPadDual.setLinkedMode(!state.channelSeparated)
        binding.spatialPadDual.setControlRadiusPercent(displayRadius)
        binding.spatialPadDual.setHandles(
            state.spatialLeftX,
            state.spatialLeftZ,
            state.spatialRightX,
            state.spatialRightZ
        )
        binding.spatialPadDual.setSelectedHandle(selectedHandle)

        binding.textPosXzDual.text = getString(
            R.string.effects_pos_xz_dual_value,
            state.spatialLeftX,
            state.spatialLeftZ,
            state.spatialRightX,
            state.spatialRightZ
        )
        binding.textRadiusSelected.text = getString(R.string.effects_radius_scale_value, displayRadius)
        binding.sliderRadiusSelected.value = displayRadius.toFloat()

        val selectedX = if (selectedHandle == SpatialPadView.Handle.LEFT) state.spatialLeftX else state.spatialRightX
        val selectedY = if (selectedHandle == SpatialPadView.Handle.LEFT) state.spatialLeftY else state.spatialRightY
        val selectedZ = if (selectedHandle == SpatialPadView.Handle.LEFT) state.spatialLeftZ else state.spatialRightZ
        val selectedLabel = if (selectedHandle == SpatialPadView.Handle.LEFT) {
            getString(R.string.effects_channel_left_short)
        } else {
            getString(R.string.effects_channel_right_short)
        }
        bindAxisSliderRange(binding.sliderPosXSelected, displayRadius, selectedX)
        bindAxisSliderRange(binding.sliderPosZSelected, displayRadius, selectedZ)
        binding.sliderPosYSelected.value = selectedY.toFloat()

        binding.textPosXSelected.text = getString(R.string.effects_pos_x_selected_value, selectedLabel, selectedX)
        binding.textPosYSelected.text = getString(R.string.effects_pos_y_selected_value, selectedLabel, selectedY)
        binding.textPosZSelected.text = getString(R.string.effects_pos_z_selected_value, selectedLabel, selectedZ)

        renderSelectedChannelButtons()
    }

    private fun renderSelectedChannelButtons() {
        styleChannelButton(binding.buttonSelectLeft, selectedHandle == SpatialPadView.Handle.LEFT)
        styleChannelButton(binding.buttonSelectRight, selectedHandle == SpatialPadView.Handle.RIGHT)
    }

    private fun styleChannelButton(button: MaterialButton, selected: Boolean) {
        val stroke = (if (selected) 2f else 1f) * resources.displayMetrics.density
        button.strokeWidth = stroke.roundToInt()
        button.alpha = if (selected) 1f else 0.78f
    }

    private fun updateSelectedHandle(handle: SpatialPadView.Handle, fromUser: Boolean) {
        if (selectedHandle == handle) {
            return
        }
        selectedHandle = handle
        binding.spatialPadDual.setSelectedHandle(handle)
        renderSelectedChannelButtons()
        if (fromUser) {
            renderChannelPanel(AudioEngine.effectsState.value)
        }
    }

    private fun applySelectedX(value: Int) {
        val state = AudioEngine.effectsState.value
        if (!state.channelSeparated) {
            AudioEngine.setSpatialPositionX(value)
            return
        }
        if (selectedHandle == SpatialPadView.Handle.LEFT) {
            AudioEngine.setSpatialLeftPositionX(value)
        } else {
            AudioEngine.setSpatialRightPositionX(value)
        }
    }

    private fun applySelectedY(value: Int) {
        val state = AudioEngine.effectsState.value
        if (!state.channelSeparated) {
            AudioEngine.setSpatialPositionY(value)
            return
        }
        if (selectedHandle == SpatialPadView.Handle.LEFT) {
            AudioEngine.setSpatialLeftPositionY(value)
        } else {
            AudioEngine.setSpatialRightPositionY(value)
        }
    }

    private fun applySelectedZ(value: Int) {
        val state = AudioEngine.effectsState.value
        if (!state.channelSeparated) {
            AudioEngine.setSpatialPositionZ(value)
            return
        }
        if (selectedHandle == SpatialPadView.Handle.LEFT) {
            AudioEngine.setSpatialLeftPositionZ(value)
        } else {
            AudioEngine.setSpatialRightPositionZ(value)
        }
    }

    private fun updateChannelPanelVisibility() {
        binding.layoutChannelPositionPanel.isVisible = channelPanelExpanded
    }

    private fun bindAxisSliderRange(slider: Slider, radius: Int, value: Int) {
        val clampedRadius = radius.coerceIn(20, 100).toFloat()
        if (slider.valueFrom != -clampedRadius) {
            slider.valueFrom = -clampedRadius
        }
        if (slider.valueTo != clampedRadius) {
            slider.valueTo = clampedRadius
        }
        slider.value = value.coerceIn(-radius, radius).toFloat()
    }

    private fun buildChannelSummary(state: EffectsUiState): String {
        return if (!state.channelSeparated) {
            getString(
                R.string.effects_channel_position_summary_linked,
                state.spatialLeftX,
                state.spatialLeftY,
                state.spatialLeftZ
            )
        } else {
            getString(
                R.string.effects_channel_position_summary_split,
                state.spatialLeftX,
                state.spatialLeftY,
                state.spatialLeftZ,
                state.spatialRightX,
                state.spatialRightY,
                state.spatialRightZ
            )
        }
    }

    private fun mbToProgress(valueMb: Int, state: EffectsUiState): Int {
        return (valueMb - state.eqBandLevelMinMb).coerceIn(0, state.eqBandLevelMaxMb - state.eqBandLevelMinMb)
    }

    private fun progressToMb(progress: Int, state: EffectsUiState): Int {
        return (state.eqBandLevelMinMb + progress).coerceIn(state.eqBandLevelMinMb, state.eqBandLevelMaxMb)
    }

    private fun bindSlider(slider: Slider, onUserChange: (Int) -> Unit) {
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !internalUpdating) {
                onUserChange(value.roundToInt())
            }
        }
    }

    private fun isIrsFile(uri: Uri): Boolean {
        val nameFromResolver = runCatching {
            requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        }.getOrNull()
        val fallback = uri.lastPathSegment
        val fileName = nameFromResolver ?: fallback ?: return false
        return fileName.endsWith(".irs", ignoreCase = true)
    }
}
