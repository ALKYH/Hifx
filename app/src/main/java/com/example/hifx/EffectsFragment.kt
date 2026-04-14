package com.example.hifx

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hifx.audio.AudioEngine
import com.example.hifx.audio.EffectsUiState
import com.example.hifx.databinding.FragmentEffectsBinding
import com.example.hifx.ui.EqCurveView
import com.example.hifx.ui.SpatialPadView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

class EffectsFragment : Fragment() {
    private var _binding: FragmentEffectsBinding? = null
    private val binding get() = _binding!!

    private var internalUpdating = false
    private var channelPanelExpanded = false
    private var surroundGainPanelExpanded = false
    private var selectedHandle: SpatialPadView.Handle = SpatialPadView.Handle.LEFT
    private var selectedEqBandIndex: Int = 0
    private var currentTabPosition: Int = 0
    private var lastEqDotsSignature: String = ""
    private val speedRepeatHandler = Handler(Looper.getMainLooper())
    private var speedRepeatTask: Runnable? = null

    companion object {
        private const val EQ_FREQ_MIN_HZ = 20f
        private const val EQ_FREQ_MAX_HZ = 20_000f
        private const val EQ_FREQ_SLIDER_MIN = 0f
        private const val EQ_FREQ_SLIDER_MAX = 1000f
    }

    private val eqPointColors by lazy {
        listOf(
            Color.parseColor("#43A047"),
            Color.parseColor("#1E88E5"),
            Color.parseColor("#F4511E"),
            Color.parseColor("#8E24AA"),
            Color.parseColor("#00897B"),
            Color.parseColor("#EF5350")
        )
    }

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
        setupControls()
        setupTabs()
        observeEffectState()
    }

    override fun onDestroyView() {
        stopSpeedAdjustRepeat()
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
                if (isChecked) {
                    AudioEngine.setSurroundMode(AudioEngine.SURROUND_MODE_STEREO)
                }
                AudioEngine.setSpatialEnabled(isChecked)
            }
        }
        binding.switchEqEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (!internalUpdating) {
                AudioEngine.setEqEnabled(isChecked)
            }
        }
        binding.switchSurroundEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (!internalUpdating) {
                setSurroundEnabled(isChecked)
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
            AudioEngine.setSpatialEnabled(false)
            when (checkedId) {
                R.id.button_surround_5_1 -> AudioEngine.setSurroundMode(AudioEngine.SURROUND_MODE_5_1)
                R.id.button_surround_7_1 -> AudioEngine.setSurroundMode(AudioEngine.SURROUND_MODE_7_1)
            }
        }
        binding.buttonToggleSurroundGainPanel.setOnClickListener {
            surroundGainPanelExpanded = !surroundGainPanelExpanded
            updateSurroundGainPanelVisibility()
        }
        bindSlider(binding.sliderSurroundGainFl) {
            AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_FRONT_LEFT, it)
        }
        bindSlider(binding.sliderSurroundGainFr) {
            AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_FRONT_RIGHT, it)
        }
        bindSlider(binding.sliderSurroundGainC) {
            AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_CENTER, it)
        }
        bindSlider(binding.sliderSurroundGainLfe) {
            AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_LFE, it)
        }
        bindSlider(binding.sliderSurroundGainSl) {
            AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_SIDE_LEFT, it)
        }
        bindSlider(binding.sliderSurroundGainSr) {
            AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_SIDE_RIGHT, it)
        }
        bindSlider(binding.sliderSurroundGainRl) {
            AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_REAR_LEFT, it)
        }
        bindSlider(binding.sliderSurroundGainRr) {
            AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_REAR_RIGHT, it)
        }

        binding.switchLimiterEnabled.setOnCheckedChangeListener { _, checked ->
            if (!internalUpdating) {
                AudioEngine.setLimiterEnabled(checked)
            }
        }
        bindSlider(binding.sliderPan) { AudioEngine.setPanPercent(it) }
        binding.switchPanInvertEnabled.setOnCheckedChangeListener { _, checked ->
            if (!internalUpdating) {
                AudioEngine.setPanInvertEnabled(checked)
            }
        }
        binding.switchMonoEnabled.setOnCheckedChangeListener { _, checked ->
            if (!internalUpdating) {
                AudioEngine.setMonoEnabled(checked)
            }
        }
        binding.switchPhaseInvertEnabled.setOnCheckedChangeListener { _, checked ->
            if (!internalUpdating) {
                AudioEngine.setPhaseInvertEnabled(checked)
            }
        }
        bindSlider(binding.sliderCrossfeed) { AudioEngine.setCrossfeedPercent(it) }
        binding.switchPlaybackSpeedPitchCompensationEnabled.setOnCheckedChangeListener { _, checked ->
            if (!internalUpdating) {
                AudioEngine.setPlaybackSpeedPitchCompensationEnabled(checked)
            }
        }
        setupSpeedAdjustButtons()

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
        bindSlider(binding.sliderChannelSpacing) { AudioEngine.setLinkedChannelSpacingCm(it) }
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
                    val centerX = ((leftX + rightX) / 2)
                    val centerZ = ((leftZ + rightZ) / 2)
                    AudioEngine.setSpatialPositionX(centerX)
                    AudioEngine.setSpatialPositionZ(centerZ)
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

        binding.eqCurveView.onPointClick = { index ->
            if (!internalUpdating) {
                selectedEqBandIndex = index
                renderEqControls(AudioEngine.effectsState.value)
            }
        }
        binding.buttonEqAddPoint.setOnClickListener {
            if (internalUpdating) return@setOnClickListener
            val state = AudioEngine.effectsState.value
            val nextFreq = if (state.eqBandFrequenciesHz.isEmpty()) {
                1000
            } else {
                (state.eqBandFrequenciesHz.maxOrNull() ?: 1000) + 500
            }.coerceIn(20, 20_000)
            AudioEngine.addEqBandPoint(frequencyHz = nextFreq, qTimes100 = 100, levelMb = 0)
            selectedEqBandIndex = AudioEngine.effectsState.value.eqBandFrequenciesHz.lastIndex
        }
        binding.buttonEqDeletePoint.setOnClickListener {
            if (internalUpdating) return@setOnClickListener
            AudioEngine.removeEqBandPoint(selectedEqBandIndex)
            selectedEqBandIndex = selectedEqBandIndex.coerceAtMost(AudioEngine.effectsState.value.eqBandFrequenciesHz.lastIndex)
        }
        binding.inputEqPresetName.setOnItemClickListener { parent, _, position, _ ->
            if (internalUpdating) return@setOnItemClickListener
            val selected = parent.getItemAtPosition(position)?.toString().orEmpty().trim()
            if (selected.isNotBlank()) {
                AudioEngine.applyEqPreset(selected)
            }
        }
        binding.inputEqPresetName.threshold = 0
        binding.inputEqPresetName.setOnClickListener {
            binding.inputEqPresetName.showDropDown()
        }
        binding.inputEqPresetName.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.inputEqPresetName.showDropDown()
        }
        binding.buttonEqSavePreset.setOnClickListener {
            if (internalUpdating) return@setOnClickListener
            showSaveEqPresetDialog()
        }
        binding.buttonEqDeletePreset.setOnClickListener {
            if (internalUpdating) return@setOnClickListener
            val presetName = binding.inputEqPresetName.text?.toString().orEmpty().trim()
            if (presetName.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.effects_eq_preset_delete_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (AudioEngine.isBuiltInEqPreset(presetName)) {
                Toast.makeText(requireContext(), getString(R.string.effects_eq_preset_delete_builtin_denied), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val deleted = AudioEngine.deleteEqPreset(presetName)
            if (deleted) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.effects_eq_preset_delete_confirm, presetName),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(requireContext(), getString(R.string.effects_eq_preset_delete_empty), Toast.LENGTH_SHORT).show()
            }
        }
        binding.sliderEqFreq.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !internalUpdating) {
                AudioEngine.setEqBandFrequency(selectedEqBandIndex, sliderValueToFrequencyHz(value))
            }
        }
        binding.sliderEqQ.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !internalUpdating) {
                AudioEngine.setEqBandQ(selectedEqBandIndex, value.roundToInt())
            }
        }
        binding.sliderEqGain.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !internalUpdating) {
                AudioEngine.setEqBandLevel(selectedEqBandIndex, value.roundToInt())
            }
        }
    }

    private fun setupTabs() {
        binding.tabEffects.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                renderEffectTab(tab?.position ?: 0, animate = true)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })
        renderEffectTab(0, animate = false)
    }

    private fun renderEffectTab(position: Int, animate: Boolean) {
        val panels = listOf(binding.panelEq, binding.panelReverb, binding.panelOther)
        if (!animate || position == currentTabPosition) {
            panels.forEachIndexed { index, panel ->
                panel.isVisible = index == position
                panel.alpha = 1f
                panel.translationY = 0f
            }
            currentTabPosition = position
            return
        }
        val offsetY = dp(12f).toFloat()
        panels.forEachIndexed { index, panel ->
            val show = index == position
            panel.animate().cancel()
            if (show) {
                if (!panel.isVisible) {
                    panel.alpha = 0f
                    panel.translationY = offsetY
                    panel.isVisible = true
                }
                panel.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220L)
                    .start()
            } else if (panel.isVisible) {
                panel.animate()
                    .alpha(0f)
                    .translationY(offsetY * 0.7f)
                    .setDuration(160L)
                    .withEndAction {
                        panel.isVisible = false
                        panel.alpha = 1f
                        panel.translationY = 0f
                    }
                    .start()
            }
        }
        currentTabPosition = position
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
        binding.switchEqEnabled.isChecked = state.eqEnabled
        binding.switchSpatialEnabled.isChecked = state.spatialEnabled
        val surroundEnabled = state.surroundMode != AudioEngine.SURROUND_MODE_STEREO
        binding.switchSurroundEnabled.isChecked = surroundEnabled
        binding.progressReverbMeter.progress = state.realtimeReverbMeterPercent
        binding.textReverbMeterValue.text = getString(
            R.string.effects_reverb_meter_value,
            state.realtimeReverbMeterPercent
        )

        binding.switchLimiterEnabled.isChecked = state.limiterEnabled
        binding.sliderPan.value = state.panPercent.toFloat()
        binding.textPanValue.text = panValueLabel(state.panPercent)
        binding.switchPanInvertEnabled.isChecked = state.panInvertEnabled
        binding.switchMonoEnabled.isChecked = state.monoEnabled
        binding.switchPhaseInvertEnabled.isChecked = state.phaseInvertEnabled
        binding.sliderCrossfeed.value = state.crossfeedPercent.toFloat()
        binding.textCrossfeedValue.text = getString(
            R.string.effects_crossfeed_value_format,
            state.crossfeedPercent
        )
        binding.switchPlaybackSpeedPitchCompensationEnabled.isChecked = state.playbackSpeedPitchCompensationEnabled
        binding.textPlaybackSpeedValue.text = getString(
            R.string.effects_playback_speed_value,
            state.playbackSpeedPercent / 100f
        )

        binding.textSpatialDistance.text = getString(
            R.string.effects_spatial_distance_value_cm,
            "%.1f".format(state.derivedDistanceMeters * 100f)
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
        renderSurroundChannelGains(state)
        renderChannelPanel(state)

        renderEqControls(state)

        internalUpdating = false
    }

    private fun panValueLabel(panPercent: Int): String {
        return when {
            panPercent > 0 -> getString(R.string.effects_pan_value_right, panPercent)
            panPercent < 0 -> getString(R.string.effects_pan_value_left, -panPercent)
            else -> getString(R.string.effects_pan_value_center)
        }
    }

    private fun renderEqControls(state: EffectsUiState) {
        val eqInteractive = state.enabled && state.eqEnabled
        animateAlphaIfNeeded(binding.eqCurveView, if (eqInteractive) 1f else 0.55f)
        animateAlphaIfNeeded(binding.cardEqParams, if (eqInteractive) 1f else 0.65f)
        binding.buttonEqAddPoint.isEnabled = eqInteractive
        binding.buttonEqSavePreset.isEnabled = eqInteractive
        binding.buttonEqDeletePreset.isEnabled = eqInteractive
        binding.inputEqPresetName.isEnabled = eqInteractive
        if (state.eqBandFrequenciesHz.isEmpty()) {
            setEqParamsVisible(visible = false)
            binding.layoutEqPointDots.removeAllViews()
            lastEqDotsSignature = ""
            binding.eqCurveView.setData(emptyList(), -1)
            return
        }
        if (selectedEqBandIndex !in state.eqBandFrequenciesHz.indices) {
            selectedEqBandIndex = state.eqBandFrequenciesHz.lastIndex.coerceAtLeast(0)
        }
        setEqParamsVisible(visible = true)
        binding.buttonEqDeletePoint.isEnabled = state.eqBandFrequenciesHz.size > 1
        val presetAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, state.eqPresetNames)
        binding.inputEqPresetName.setAdapter(presetAdapter)
        val currentPresetText = binding.inputEqPresetName.text?.toString().orEmpty()
        if (currentPresetText != state.eqActivePresetName) {
            binding.inputEqPresetName.setText(state.eqActivePresetName, false)
        }

        val points = state.eqBandFrequenciesHz.indices.map { index ->
            EqCurveView.EqPoint(
                frequencyHz = state.eqBandFrequenciesHz.getOrElse(index) { 1000 },
                gainMb = state.eqBandLevelsMb.getOrElse(index) { 0 },
                qTimes100 = state.eqBandQTimes100.getOrElse(index) { 100 },
                color = eqPointColors[index % eqPointColors.size]
            )
        }
        binding.eqCurveView.setData(points, selectedEqBandIndex)
        rebuildEqPointDots(state)

        val freq = state.eqBandFrequenciesHz[selectedEqBandIndex]
        val gainMb = state.eqBandLevelsMb.getOrElse(selectedEqBandIndex) { 0 }
        val q100 = state.eqBandQTimes100.getOrElse(selectedEqBandIndex) { 100 }
        binding.textEqSelectedPoint.text = getString(R.string.effects_eq_selected_point, selectedEqBandIndex + 1)
        binding.textEqFreqValue.text = if (freq >= 1000) {
            getString(R.string.effects_eq_frequency_value_khz, freq / 1000f)
        } else {
            getString(R.string.effects_eq_frequency_value_hz, freq)
        }
        binding.textEqQValue.text = getString(R.string.effects_eq_q_value, q100 / 100f)
        binding.textEqGainValue.text = getString(R.string.effects_eq_gain_db_value, gainMb / 100f)

        if (binding.sliderEqGain.valueFrom != state.eqBandLevelMinMb.toFloat()) {
            binding.sliderEqGain.valueFrom = state.eqBandLevelMinMb.toFloat()
        }
        if (binding.sliderEqGain.valueTo != state.eqBandLevelMaxMb.toFloat()) {
            binding.sliderEqGain.valueTo = state.eqBandLevelMaxMb.toFloat()
        }
        binding.sliderEqFreq.value = frequencyHzToSliderValue(freq)
        binding.sliderEqQ.value = q100.toFloat()
        binding.sliderEqGain.value = gainMb.toFloat()
    }

    private fun rebuildEqPointDots(state: EffectsUiState) {
        val signature = "${state.eqBandFrequenciesHz.joinToString(",")}|$selectedEqBandIndex"
        if (signature == lastEqDotsSignature) return
        lastEqDotsSignature = signature
        val container = binding.layoutEqPointDots
        container.removeAllViews()
        state.eqBandFrequenciesHz.indices.forEach { index ->
            val selected = index == selectedEqBandIndex
            val dot = ImageView(requireContext()).apply {
                val size = dp(20f)
                layoutParams = LinearLayout.LayoutParams(size, size).also { lp ->
                    lp.marginEnd = dp(8f)
                }
                alpha = if (selected) 1f else 0.75f
                scaleX = if (selected) 1.14f else 1f
                scaleY = if (selected) 1.14f else 1f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(eqPointColors[index % eqPointColors.size])
                    if (selected) {
                        setStroke(dp(2f), ContextCompat.getColor(requireContext(), android.R.color.white))
                    } else {
                        setStroke(dp(1f), ContextCompat.getColor(requireContext(), android.R.color.transparent))
                    }
                }
                setOnClickListener {
                    if (selectedEqBandIndex != index) {
                        selectedEqBandIndex = index
                        renderEqControls(AudioEngine.effectsState.value)
                    }
                }
            }
            if (selected) {
                dot.scaleX = 0.88f
                dot.scaleY = 0.88f
                dot.animate()
                    .scaleX(1.14f)
                    .scaleY(1.14f)
                    .setDuration(180L)
                    .start()
            }
            container.addView(dot)
        }
    }

    private fun renderSurroundMode(mode: Int) {
        val surroundEnabled = mode != AudioEngine.SURROUND_MODE_STEREO
        val buttonId = when (mode) {
            AudioEngine.SURROUND_MODE_5_1 -> R.id.button_surround_5_1
            AudioEngine.SURROUND_MODE_7_1 -> R.id.button_surround_7_1
            else -> R.id.button_surround_5_1
        }
        if (binding.toggleSurroundMode.checkedButtonId != buttonId) {
            binding.toggleSurroundMode.check(buttonId)
        }
        binding.toggleSurroundMode.isEnabled = surroundEnabled
        binding.buttonToggleSurroundGainPanel.isEnabled = surroundEnabled
        binding.cardSurroundSettings.alpha = if (surroundEnabled) 1f else 0.72f

        val showRear = mode == AudioEngine.SURROUND_MODE_7_1

        setSurroundGainControlVisible(
            textView = binding.textSurroundGainC,
            slider = binding.sliderSurroundGainC,
            visible = surroundEnabled
        )
        setSurroundGainControlVisible(
            textView = binding.textSurroundGainLfe,
            slider = binding.sliderSurroundGainLfe,
            visible = surroundEnabled
        )
        setSurroundGainControlVisible(
            textView = binding.textSurroundGainSl,
            slider = binding.sliderSurroundGainSl,
            visible = surroundEnabled
        )
        setSurroundGainControlVisible(
            textView = binding.textSurroundGainSr,
            slider = binding.sliderSurroundGainSr,
            visible = surroundEnabled
        )
        setSurroundGainControlVisible(
            textView = binding.textSurroundGainRl,
            slider = binding.sliderSurroundGainRl,
            visible = surroundEnabled && showRear
        )
        setSurroundGainControlVisible(
            textView = binding.textSurroundGainRr,
            slider = binding.sliderSurroundGainRr,
            visible = surroundEnabled && showRear
        )
    }

    private fun renderSurroundChannelGains(state: EffectsUiState) {
        val mode = state.surroundMode
        val frontLeft: Int
        val frontRight: Int
        val center: Int
        val lfe: Int
        val sideLeft: Int
        val sideRight: Int
        val rearLeft: Int
        val rearRight: Int
        when (mode) {
            AudioEngine.SURROUND_MODE_5_1 -> {
                frontLeft = state.surround51FlPercent
                frontRight = state.surround51FrPercent
                center = state.surround51CPercent
                lfe = state.surround51LfePercent
                sideLeft = state.surround51SlPercent
                sideRight = state.surround51SrPercent
                rearLeft = 100
                rearRight = 100
            }

            AudioEngine.SURROUND_MODE_7_1 -> {
                frontLeft = state.surround71FlPercent
                frontRight = state.surround71FrPercent
                center = state.surround71CPercent
                lfe = state.surround71LfePercent
                sideLeft = state.surround71SlPercent
                sideRight = state.surround71SrPercent
                rearLeft = state.surround71RlPercent
                rearRight = state.surround71RrPercent
            }

            else -> {
                frontLeft = state.surround51FlPercent
                frontRight = state.surround51FrPercent
                center = state.surround51CPercent
                lfe = state.surround51LfePercent
                sideLeft = state.surround51SlPercent
                sideRight = state.surround51SrPercent
                rearLeft = 100
                rearRight = 100
            }
        }

        binding.sliderSurroundGainFl.value = frontLeft.toFloat()
        binding.sliderSurroundGainFr.value = frontRight.toFloat()
        binding.sliderSurroundGainC.value = center.toFloat()
        binding.sliderSurroundGainLfe.value = lfe.toFloat()
        binding.sliderSurroundGainSl.value = sideLeft.toFloat()
        binding.sliderSurroundGainSr.value = sideRight.toFloat()
        binding.sliderSurroundGainRl.value = rearLeft.toFloat()
        binding.sliderSurroundGainRr.value = rearRight.toFloat()

        renderSurroundGainLabel(binding.textSurroundGainFl, R.string.effects_surround_channel_fl, frontLeft)
        renderSurroundGainLabel(binding.textSurroundGainFr, R.string.effects_surround_channel_fr, frontRight)
        renderSurroundGainLabel(binding.textSurroundGainC, R.string.effects_surround_channel_c, center)
        renderSurroundGainLabel(binding.textSurroundGainLfe, R.string.effects_surround_channel_lfe, lfe)
        renderSurroundGainLabel(binding.textSurroundGainSl, R.string.effects_surround_channel_sl, sideLeft)
        renderSurroundGainLabel(binding.textSurroundGainSr, R.string.effects_surround_channel_sr, sideRight)
        renderSurroundGainLabel(binding.textSurroundGainRl, R.string.effects_surround_channel_rl, rearLeft)
        renderSurroundGainLabel(binding.textSurroundGainRr, R.string.effects_surround_channel_rr, rearRight)

        updateSurroundGainPanelVisibility()
    }

    private fun renderSurroundGainLabel(view: android.widget.TextView, channelRes: Int, value: Int) {
        view.text = getString(
            R.string.effects_surround_gain_channel_value,
            getString(channelRes),
            value
        )
    }

    private fun setSurroundGainControlVisible(
        textView: android.widget.TextView,
        slider: Slider,
        visible: Boolean
    ) {
        textView.isVisible = visible
        slider.isVisible = visible
    }

    private fun renderChannelPanel(state: EffectsUiState) {
        binding.switchChannelSeparated.isChecked = state.channelSeparated
        updateChannelPanelVisibility()
        binding.layoutChannelSelector.isVisible = state.channelSeparated
        binding.layoutChannelSpacing.isVisible = !state.channelSeparated

        val displayRadius = max(state.spatialLeftRadiusPercent, state.spatialRightRadiusPercent).coerceIn(20, 120)
        binding.spatialPadDual.setLinkedMode(!state.channelSeparated)
        binding.spatialPadDual.setControlRadiusCm(displayRadius)
        binding.spatialPadDual.setLinkedChannelSpacingCm(state.linkedChannelSpacingCm)
        binding.spatialPadDual.setHeadRadiusCm(state.hrtfHeadRadiusMm / 10f)
        binding.spatialPadDual.setHandles(
            state.spatialLeftX,
            state.spatialLeftZ,
            state.spatialRightX,
            state.spatialRightZ
        )
        binding.spatialPadDual.setSelectedHandle(selectedHandle)

        binding.textPosXzDual.text = getString(
            R.string.effects_pos_xz_dual_value_cm,
            state.spatialLeftX,
            state.spatialLeftZ,
            state.spatialRightX,
            state.spatialRightZ
        )
        binding.textRadiusSelected.text = getString(R.string.effects_radius_scale_value_cm, displayRadius)
        binding.sliderRadiusSelected.value = displayRadius.toFloat()
        val maxSpacing = (displayRadius * 2).coerceAtLeast(0)
        if (binding.sliderChannelSpacing.valueFrom != 0f) {
            binding.sliderChannelSpacing.valueFrom = 0f
        }
        if (binding.sliderChannelSpacing.valueTo != maxSpacing.toFloat()) {
            binding.sliderChannelSpacing.valueTo = maxSpacing.toFloat()
        }
        val spacingForDisplay = state.linkedChannelSpacingCm.coerceIn(0, maxSpacing)
        binding.sliderChannelSpacing.value = spacingForDisplay.toFloat()
        binding.textChannelSpacingValue.text = getString(
            R.string.effects_channel_spacing_value_cm,
            spacingForDisplay
        )

        val selectedX = if (!state.channelSeparated) {
            (state.spatialLeftX + state.spatialRightX) / 2
        } else if (selectedHandle == SpatialPadView.Handle.LEFT) {
            state.spatialLeftX
        } else {
            state.spatialRightX
        }
        val selectedY = if (!state.channelSeparated) {
            (state.spatialLeftY + state.spatialRightY) / 2
        } else if (selectedHandle == SpatialPadView.Handle.LEFT) {
            state.spatialLeftY
        } else {
            state.spatialRightY
        }
        val selectedZ = if (!state.channelSeparated) {
            (state.spatialLeftZ + state.spatialRightZ) / 2
        } else if (selectedHandle == SpatialPadView.Handle.LEFT) {
            state.spatialLeftZ
        } else {
            state.spatialRightZ
        }
        val selectedLabel = if (!state.channelSeparated) {
            getString(R.string.effects_channel_linked_short)
        } else if (selectedHandle == SpatialPadView.Handle.LEFT) {
            getString(R.string.effects_channel_left_short)
        } else {
            getString(R.string.effects_channel_right_short)
        }
        bindAxisSliderRange(binding.sliderPosXSelected, displayRadius, selectedX)
        bindAxisSliderRange(binding.sliderPosZSelected, displayRadius, selectedZ)
        binding.sliderPosYSelected.value = selectedY.toFloat()

        binding.textPosXSelected.text = getString(R.string.effects_pos_x_selected_value_cm, selectedLabel, selectedX)
        binding.textPosYSelected.text = getString(R.string.effects_pos_y_selected_value_cm, selectedLabel, selectedY)
        binding.textPosZSelected.text = getString(R.string.effects_pos_z_selected_value_cm, selectedLabel, selectedZ)

        if (state.channelSeparated) {
            renderSelectedChannelButtons()
        }
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

    private fun updateSurroundGainPanelVisibility() {
        val surroundEnabled = AudioEngine.effectsState.value.surroundMode != AudioEngine.SURROUND_MODE_STEREO
        binding.layoutSurroundGainPanel.isVisible = surroundGainPanelExpanded && surroundEnabled
        binding.buttonToggleSurroundGainPanel.text = getString(
            if (surroundGainPanelExpanded) {
                R.string.effects_surround_gain_collapse
            } else {
                R.string.effects_surround_gain_expand
            }
        )
    }

    private fun setSurroundEnabled(enabled: Boolean) {
        if (enabled) {
            AudioEngine.setSpatialEnabled(false)
            val current = AudioEngine.effectsState.value.surroundMode
            val target = if (current == AudioEngine.SURROUND_MODE_7_1) {
                AudioEngine.SURROUND_MODE_7_1
            } else {
                AudioEngine.SURROUND_MODE_5_1
            }
            AudioEngine.setSurroundMode(target)
        } else {
            AudioEngine.setSurroundMode(AudioEngine.SURROUND_MODE_STEREO)
        }
    }

    private fun bindAxisSliderRange(slider: Slider, radius: Int, value: Int) {
        val clampedRadius = radius.coerceIn(20, 120).toFloat()
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
                R.string.effects_channel_position_summary_linked_cm,
                (state.spatialLeftX + state.spatialRightX) / 2,
                (state.spatialLeftY + state.spatialRightY) / 2,
                (state.spatialLeftZ + state.spatialRightZ) / 2,
                state.linkedChannelSpacingCm
            )
        } else {
            getString(
                R.string.effects_channel_position_summary_split_cm,
                state.spatialLeftX,
                state.spatialLeftY,
                state.spatialLeftZ,
                state.spatialRightX,
                state.spatialRightY,
                state.spatialRightZ
            )
        }
    }

    private fun setupSpeedAdjustButtons() {
        binding.buttonPlaybackSpeedDown.setOnClickListener {
            if (!internalUpdating) {
                AudioEngine.adjustPlaybackSpeedByStep(-1)
            }
        }
        binding.buttonPlaybackSpeedUp.setOnClickListener {
            if (!internalUpdating) {
                AudioEngine.adjustPlaybackSpeedByStep(1)
            }
        }

        binding.buttonPlaybackSpeedDown.setOnLongClickListener {
            if (internalUpdating) return@setOnLongClickListener false
            startSpeedAdjustRepeat(-1)
            true
        }
        binding.buttonPlaybackSpeedUp.setOnLongClickListener {
            if (internalUpdating) return@setOnLongClickListener false
            startSpeedAdjustRepeat(1)
            true
        }

        val touchStopListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> stopSpeedAdjustRepeat()
            }
            false
        }
        binding.buttonPlaybackSpeedDown.setOnTouchListener(touchStopListener)
        binding.buttonPlaybackSpeedUp.setOnTouchListener(touchStopListener)
    }

    private fun startSpeedAdjustRepeat(step: Int) {
        stopSpeedAdjustRepeat()
        val task = object : Runnable {
            override fun run() {
                if (_binding == null || internalUpdating) return
                AudioEngine.adjustPlaybackSpeedByStep(step)
                speedRepeatHandler.postDelayed(this, 90L)
            }
        }
        speedRepeatTask = task
        speedRepeatHandler.postDelayed(task, 220L)
    }

    private fun stopSpeedAdjustRepeat() {
        speedRepeatTask?.let { speedRepeatHandler.removeCallbacks(it) }
        speedRepeatTask = null
    }

    private fun bindSlider(slider: Slider, onUserChange: (Int) -> Unit) {
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !internalUpdating) {
                onUserChange(value.roundToInt())
            }
        }
    }

    private fun showSaveEqPresetDialog() {
        val currentName = binding.inputEqPresetName.text?.toString().orEmpty()
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.effects_eq_preset_name_hint)
            setText(if (currentName == getString(R.string.effects_eq_custom_name)) "" else currentName)
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.effects_eq_preset_save_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.effects_eq_preset_save_confirm) { _, _ ->
                val name = input.text?.toString().orEmpty().trim()
                if (name.isBlank()) {
                    Toast.makeText(requireContext(), getString(R.string.effects_eq_preset_name_hint), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                AudioEngine.saveCurrentEqAsPreset(name)
            }
            .show()
    }

    private fun frequencyHzToSliderValue(frequencyHz: Int): Float {
        val hz = frequencyHz.toFloat().coerceIn(EQ_FREQ_MIN_HZ, EQ_FREQ_MAX_HZ)
        val logMin = ln(EQ_FREQ_MIN_HZ)
        val logMax = ln(EQ_FREQ_MAX_HZ)
        val ratio = ((ln(hz) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
        return EQ_FREQ_SLIDER_MIN + (EQ_FREQ_SLIDER_MAX - EQ_FREQ_SLIDER_MIN) * ratio
    }

    private fun sliderValueToFrequencyHz(sliderValue: Float): Int {
        val ratio = ((sliderValue - EQ_FREQ_SLIDER_MIN) / (EQ_FREQ_SLIDER_MAX - EQ_FREQ_SLIDER_MIN)).coerceIn(0f, 1f)
        val frequency = EQ_FREQ_MIN_HZ * (EQ_FREQ_MAX_HZ / EQ_FREQ_MIN_HZ).pow(ratio)
        return frequency.roundToInt().coerceIn(EQ_FREQ_MIN_HZ.toInt(), EQ_FREQ_MAX_HZ.toInt())
    }

    private fun setEqParamsVisible(visible: Boolean) {
        val card = binding.cardEqParams
        if (visible == card.isVisible) return
        card.animate().cancel()
        if (visible) {
            card.alpha = 0f
            card.scaleY = 0.96f
            card.isVisible = true
            card.animate()
                .alpha(1f)
                .scaleY(1f)
                .setDuration(180L)
                .start()
        } else {
            card.animate()
                .alpha(0f)
                .scaleY(0.96f)
                .setDuration(140L)
                .withEndAction {
                    card.isVisible = false
                    card.alpha = 1f
                    card.scaleY = 1f
                }
                .start()
        }
    }

    private fun animateAlphaIfNeeded(view: View, targetAlpha: Float) {
        if (abs(view.alpha - targetAlpha) < 0.02f) return
        view.animate()
            .alpha(targetAlpha)
            .setDuration(150L)
            .start()
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

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()
}
