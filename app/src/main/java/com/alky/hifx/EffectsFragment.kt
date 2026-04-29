package com.alky.hifx

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
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alky.hifx.audio.AudioEngine
import com.alky.hifx.audio.EffectsUiState
import com.alky.hifx.databinding.FragmentEffectsBinding
import com.alky.hifx.ui.EqCurveView
import com.alky.hifx.ui.KnobView
import com.alky.hifx.ui.PagedHorizontalScrollView
import com.alky.hifx.ui.SpatialPadView
import com.alky.hifx.util.AppHaptics
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var effectsPager: PagedHorizontalScrollView? = null
    private var effectsPagesRow: LinearLayout? = null
    private var lastEqBottomSpacerHeight = Int.MIN_VALUE
    private var surroundGainHeaderIndicator: TextView? = null
    private val eqBottomInsetTrackedOverlays = mutableListOf<View>()
    private val eqBottomInsetLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateEqBottomSpacer()
    }
    private var effectsMasterToggleGlow: GradientDrawable? = null
    private var effectsMasterToggleCore: GradientDrawable? = null
    private var vocalRemovalSwitch: MaterialSwitch? = null
    private var rightChannelPhaseInvertSwitch: MaterialSwitch? = null
    private var vocalKeySectionView: View? = null
    private var vocalBandSectionView: View? = null
    private var vocalKeyValueView: android.widget.TextView? = null
    private var vocalKeyDownButton: MaterialButton? = null
    private var vocalKeyUpButton: MaterialButton? = null
    private var vocalBandLowValueView: android.widget.TextView? = null
    private var vocalBandHighValueView: android.widget.TextView? = null
    private var vocalBandLowKnob: KnobView? = null
    private var vocalBandHighKnob: KnobView? = null
    private var panKnobControl: com.alky.hifx.EffectsFragment.KnobControl? = null
    private var speedKnobControl: com.alky.hifx.EffectsFragment.KnobControl? = null
    private var crossfeedKnobControl: com.alky.hifx.EffectsFragment.KnobControl? = null
    private var convolutionWetKnobControl: com.alky.hifx.EffectsFragment.KnobControl? = null
    private var channelSpacingKnobControl: com.alky.hifx.EffectsFragment.KnobControl? = null
    private var radiusKnobControl: com.alky.hifx.EffectsFragment.KnobControl? = null
    private var posXKnobControl: com.alky.hifx.EffectsFragment.KnobControl? = null
    private var posYKnobControl: com.alky.hifx.EffectsFragment.KnobControl? = null
    private var posZKnobControl: com.alky.hifx.EffectsFragment.KnobControl? = null
    private val surroundGainKnobControls = linkedMapOf<Int, com.alky.hifx.EffectsFragment.KnobControl>()
    private val speedRepeatHandler = Handler(Looper.getMainLooper())
    private var speedRepeatTask: Runnable? = null
    private val eqSpectrumBuffer = FloatArray(64)

    private data class KnobControl(
        val container: View,
        val knob: KnobView,
        val valueView: TextView
    )

    private data class CollapsibleSection(
        val body: ViewGroup,
        val indicator: TextView,
        var expanded: Boolean
    )

    companion object {
        private const val EQ_FREQ_MIN_HZ = 20f
        private const val EQ_FREQ_MAX_HZ = 20_000f
        private const val EQ_FREQ_SLIDER_MIN = 0f
        private const val EQ_FREQ_SLIDER_MAX = 1000f
        private const val EQ_CURVE_FIXED_HEIGHT_DP = 180f
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
                    Toast.makeText(requireContext(), getString(com.alky.hifx.R.string.effects_irs_file_required), Toast.LENGTH_SHORT).show()
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
        setupEffectsPager()
        ensureVocalRemovalSwitch()
        ensureVocalKeyControls()
        ensureVocalBandControls()
        ensureRightChannelPhaseInvertSwitch()
        ensurePanKnobControl()
        ensurePlaybackSpeedKnobControl()
        ensureCrossfeedKnobControl()
        ensureConvolutionKnobControl()
        ensureChannelKnobControls()
        ensureSurroundGainKnobControls()
        setupEffectsSectionHeaders()
        setupSurroundGainCollapseHeader()
        setupVocalControlsSection()
        setupPanAndSpeedSections()
        setupControls()
        setupEqBottomInsetTracking()
        setupEqViewportSizing()
        observeEffectState()
    }

    override fun onDestroyView() {
        stopSpeedAdjustRepeat()
        clearEqBottomInsetTracking()
        super.onDestroyView()
        _binding = null
    }

    private fun setupEffectsPager() {
        if (effectsPager != null) return
        currentTabPosition = 0
        binding.tabEffects.isVisible = false
        val container = binding.effectsContainer
        val panels = listOf(binding.panelEq, binding.panelReverb, binding.panelOther)
        container.removeAllViews()

        val pagesRow = LinearLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.HORIZONTAL
        }
        panels.forEach { panel ->
            (panel.parent as? ViewGroup)?.removeView(panel)
            panel.isVisible = true
            panel.alpha = 1f
            panel.translationY = 0f
            val pageHost: View = if (panel === binding.panelEq) {
                panel
            } else {
                ScrollView(requireContext()).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    isFillViewport = true
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    addView(
                        panel,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
            }
            pagesRow.addView(
                pageHost,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        val pager = PagedHorizontalScrollView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(pagesRow)
            pageCount = panels.size
            onPageChanged = { page -> handleEffectsPageChanged(page) }
        }
        container.addView(pager)
        effectsPager = pager
        effectsPagesRow = pagesRow
        container.doOnLayout {
            val width = it.width
            val height = it.height
            if (width <= 0 || height <= 0) return@doOnLayout
            pagesRow.children.forEach { page ->
                val params = page.layoutParams as LinearLayout.LayoutParams
                if (params.width != width) {
                    params.width = width
                }
                if (params.height != height) {
                    params.height = height
                }
                page.layoutParams = params
            }
            pager.snapToPage(currentTabPosition, smooth = false)
        }
        pager.post {
            if (isAdded) {
                renderEffectTab(position = 0, animate = false)
            }
        }
    }

    private fun updateEffectsPageHeight() {
        effectsPager?.requestLayout()
    }

        private fun setupEffectsSectionHeaders() {
        (binding.panelEq.getChildAt(0) as? TextView)?.isVisible = true
        binding.panelEq.getChildAt(1)?.isVisible = false
        binding.switchEqEnabled.isVisible = true

        val spatialParent = binding.switchSpatialEnabled.parent as LinearLayout
        if (spatialParent.findViewWithTag<View>("spatial_section_header") == null) {
            val titleView = spatialParent.getChildAt(0)
            titleView?.isVisible = false
            installCollapsibleSection(
                parent = spatialParent,
                title = getString(com.alky.hifx.R.string.effects_reverb_title),
                toggleView = binding.switchSpatialEnabled,
                showTitle = false,
                bodyViews = spatialParent.children.filter { child ->
                    child !== titleView && child !== binding.switchSpatialEnabled
                }.toList(),
                startExpanded = false,
                headerTag = "spatial_section_header"
            )
        }

        val surroundParent = binding.switchSurroundEnabled.parent as LinearLayout
        if (surroundParent.findViewWithTag<View>("surround_section_header") == null) {
            val titleView = surroundParent.getChildAt(0)
            titleView?.isVisible = false
            installCollapsibleSection(
                parent = surroundParent,
                title = getString(com.alky.hifx.R.string.effects_surround_mode_title),
                toggleView = binding.switchSurroundEnabled,
                showTitle = false,
                bodyViews = surroundParent.children.filter { child ->
                    child !== titleView && child !== binding.switchSurroundEnabled
                }.toList(),
                startExpanded = false,
                headerTag = "surround_section_header"
            )
        }

        val convolutionParent = binding.switchConvolutionEnabled.parent as LinearLayout
        if (convolutionParent.findViewWithTag<View>("convolution_section_header") == null) {
            val titleView = convolutionParent.getChildAt(0)
            titleView?.isVisible = false
            installCollapsibleSection(
                parent = convolutionParent,
                title = getString(com.alky.hifx.R.string.effects_convolution_title),
                toggleView = binding.switchConvolutionEnabled,
                showTitle = false,
                bodyViews = convolutionParent.children.filter { child ->
                    child !== titleView && child !== binding.switchConvolutionEnabled
                }.toList(),
                startExpanded = false,
                headerTag = "convolution_section_header"
            )
        }
    }

    private fun installCollapsibleSection(
        parent: LinearLayout,
        title: String,
        toggleView: View?,
        showTitle: Boolean = true,
        clearToggleText: Boolean = false,
        fillAvailableHeight: Boolean = false,
        bodyViews: List<View>,
        startExpanded: Boolean = false,
        headerTag: String? = null
    ) {
        val body = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (fillAvailableHeight) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
                if (fillAvailableHeight) 1f else 0f
            )
            orientation = LinearLayout.VERTICAL
        }
        bodyViews.forEach { view ->
            parent.removeView(view)
            body.addView(view)
        }

        val indicator = TextView(requireContext()).apply {
            text = if (startExpanded) "▾" else "▸"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            alpha = 0.82f
        }

        val header = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            tag = headerTag
            gravity = android.view.Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(44f)
            setPadding(0, 0, 0, dp(6f))
        }
        if (showTitle) {
            header.addView(TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = title
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            })
        }
        toggleView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            if (clearToggleText && it is MaterialSwitch) {
                it.text = ""
            }
            header.addView(it, LinearLayout.LayoutParams(
                if (showTitle) ViewGroup.LayoutParams.WRAP_CONTENT else 0,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (!showTitle) {
                    weight = 1f
                }
                marginEnd = dp(8f)
            })
        }
        header.addView(indicator)

        val section = com.alky.hifx.EffectsFragment.CollapsibleSection(
            body = body,
            indicator = indicator,
            expanded = startExpanded
        )
        body.isVisible = startExpanded
        header.setOnClickListener {
            section.expanded = !section.expanded
            body.isVisible = section.expanded
            indicator.text = if (section.expanded) "▾" else "▸"
            body.post { updateEffectsPageHeight() }
        }

        parent.addView(header, 0)
        parent.addView(body, 1)
    }

    private fun setupVocalControlsSection() {
        val parent = binding.panelOther
        if (parent.findViewWithTag<View>("vocal_section") != null) return

        val insertIndex = parent.indexOfChild(binding.switchPhaseInvertEnabled).coerceAtLeast(0)
        val vocalBodyViews = listOfNotNull(
            vocalRemovalSwitch,
            vocalKeySectionView,
            vocalBandSectionView
        )
        if (vocalBodyViews.isEmpty()) return

        val section = createInlineCollapsibleSection(
            title = getString(com.alky.hifx.R.string.effects_vocal_controls_title),
            bodyViews = vocalBodyViews,
            startExpanded = false
        ).apply { tag = "vocal_section" }
        parent.addView(section, insertIndex)
    }

    private fun setupPanAndSpeedSections() {
        val parent = binding.panelOther
        if (parent.findViewWithTag<View>("pan_section") == null) {
            val panTitleIndex = (parent.indexOfChild(binding.textPanValue) - 1).coerceAtLeast(0)
            val panTitleView = parent.getChildAt(panTitleIndex)
            val crossfeedTitleIndex = (parent.indexOfChild(binding.textCrossfeedValue) - 1).coerceAtLeast(0)
            val crossfeedTitleView = parent.getChildAt(crossfeedTitleIndex)
            val insertIndex = panTitleIndex
            val panBodyViews = listOfNotNull(
                panKnobControl?.container,
                binding.switchPanInvertEnabled,
                crossfeedKnobControl?.container
            )
            panTitleView.isVisible = false
            crossfeedTitleView.isVisible = false
            val section = createInlineCollapsibleSection(
                title = getString(com.alky.hifx.R.string.effects_pan_title),
                bodyViews = panBodyViews,
                startExpanded = false
            ).apply { tag = "pan_section" }
            parent.addView(section, insertIndex)
        }

        if (parent.findViewWithTag<View>("speed_section") == null) {
            val speedRow = binding.buttonPlaybackSpeedDown.parent as View
            val speedTitleIndex = (parent.indexOfChild(speedRow) - 1).coerceAtLeast(0)
            val speedTitleView = parent.getChildAt(speedTitleIndex)
            val insertIndex = speedTitleIndex
            speedRow.isVisible = false
            speedTitleView.isVisible = false
            val section = createInlineCollapsibleSection(
                title = getString(com.alky.hifx.R.string.effects_playback_speed_title),
                bodyViews = listOfNotNull(
                    speedKnobControl?.container,
                    binding.switchPlaybackSpeedPitchCompensationEnabled
                ),
                startExpanded = false
            ).apply { tag = "speed_section" }
            parent.addView(section, insertIndex)
        }
    }

        private fun createInlineCollapsibleSection(
        title: String,
        bodyViews: List<View>,
        startExpanded: Boolean = false
    ): View {
        val body = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }
        bodyViews.forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            body.addView(view)
        }
        val indicator = TextView(requireContext()).apply {
            text = if (startExpanded) "▾" else "▸"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            alpha = 0.82f
        }
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8f)
            }
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(requireContext(), com.alky.hifx.R.drawable.bg_eq_curve_panel)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))

            addView(LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    text = title
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                })
                addView(indicator)
                setOnClickListener {
                    body.isVisible = !body.isVisible
                    indicator.text = if (body.isVisible) "▾" else "▸"
                    body.post { updateEffectsPageHeight() }
                }
            })
            body.isVisible = startExpanded
            addView(body)
        }
    }

        private fun setupSurroundGainCollapseHeader() {
        if (surroundGainHeaderIndicator != null) return
        binding.buttonToggleSurroundGainPanel.isVisible = false
        binding.textSurroundGainHint.isVisible = false
        val parent = binding.buttonToggleSurroundGainPanel.parent as LinearLayout
        val insertIndex = parent.indexOfChild(binding.buttonToggleSurroundGainPanel).coerceAtLeast(0)
        val indicator = TextView(requireContext()).apply {
            text = if (surroundGainPanelExpanded) "▾" else "▸"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            alpha = 0.82f
        }
        val header = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8f)
            }
            gravity = android.view.Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(40f)
            setOnClickListener {
                surroundGainPanelExpanded = !surroundGainPanelExpanded
                updateSurroundGainPanelVisibility()
                indicator.text = if (surroundGainPanelExpanded) "▾" else "▸"
            }
            addView(TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = getString(com.alky.hifx.R.string.effects_surround_gain_title)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            })
            addView(indicator)
        }
        parent.addView(header, insertIndex)
        surroundGainHeaderIndicator = indicator
    }

    private fun setupControls() {
        binding.layoutEffectsMasterToggle.setOnClickListener {
            if (internalUpdating) return@setOnClickListener
            AppHaptics.click(it)
            AudioEngine.setEffectsEnabled(!AudioEngine.effectsState.value.enabled)
        }
        binding.switchSpatialEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (!internalUpdating) {
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
            when (checkedId) {
                com.alky.hifx.R.id.button_surround_5_1 -> AudioEngine.setSurroundMode(AudioEngine.SURROUND_MODE_5_1)
                com.alky.hifx.R.id.button_surround_7_1 -> AudioEngine.setSurroundMode(AudioEngine.SURROUND_MODE_7_1)
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
        vocalRemovalSwitch?.setOnCheckedChangeListener { _, checked ->
            if (!internalUpdating) {
                AudioEngine.setVocalRemovalEnabled(checked)
            }
        }
        vocalKeyDownButton?.setOnClickListener {
            if (!internalUpdating) {
                AudioEngine.adjustVocalKeyShiftSemitonesByStep(-1)
            }
        }
        vocalKeyUpButton?.setOnClickListener {
            if (!internalUpdating) {
                AudioEngine.adjustVocalKeyShiftSemitonesByStep(1)
            }
        }
        vocalBandLowKnob?.onValueChange = { value, fromUser ->
            if (fromUser && !internalUpdating) {
                AudioEngine.setVocalBandLowCutHz(value.toInt())
            }
        }
        vocalBandHighKnob?.onValueChange = { value, fromUser ->
            if (fromUser && !internalUpdating) {
                AudioEngine.setVocalBandHighCutHz(value.toInt())
            }
        }
        binding.switchPhaseInvertEnabled.setOnCheckedChangeListener { _, checked ->
            if (!internalUpdating) {
                AudioEngine.setPhaseInvertEnabled(checked)
            }
        }
        rightChannelPhaseInvertSwitch?.setOnCheckedChangeListener { _, checked ->
            if (!internalUpdating) {
                AudioEngine.setRightChannelPhaseInvertEnabled(checked)
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
                if (checked && AudioEngine.effectsState.value.convolutionIrUri.isNullOrBlank()) {
                    internalUpdating = true
                    binding.switchConvolutionEnabled.isChecked = false
                    internalUpdating = false
                    irPickerLauncher.launch(arrayOf("*/*"))
                    return@setOnCheckedChangeListener
                }
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
                Toast.makeText(requireContext(), getString(com.alky.hifx.R.string.effects_eq_preset_delete_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (AudioEngine.isBuiltInEqPreset(presetName)) {
                Toast.makeText(requireContext(), getString(com.alky.hifx.R.string.effects_eq_preset_delete_builtin_denied), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val deleted = AudioEngine.deleteEqPreset(presetName)
            if (deleted) {
                Toast.makeText(
                    requireContext(),
                    getString(com.alky.hifx.R.string.effects_eq_preset_delete_confirm, presetName),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(requireContext(), getString(com.alky.hifx.R.string.effects_eq_preset_delete_empty), Toast.LENGTH_SHORT).show()
            }
        }
        binding.knobEqFreq.onValueChange = { value, fromUser ->
            if (fromUser && !internalUpdating) {
                AudioEngine.setEqBandFrequency(selectedEqBandIndex, sliderValueToFrequencyHz(value))
            }
        }
        binding.knobEqQ.onValueChange = { value, fromUser ->
            if (fromUser && !internalUpdating) {
                AudioEngine.setEqBandQ(selectedEqBandIndex, value.roundToInt())
            }
        }
        binding.knobEqGain.onValueChange = { value, fromUser ->
            if (fromUser && !internalUpdating) {
                AudioEngine.setEqBandLevel(selectedEqBandIndex, value.roundToInt())
            }
        }
    }

    private fun ensureVocalRemovalSwitch() {
        if (vocalRemovalSwitch != null) return
        val container = binding.panelOther
        val insertIndex = container.indexOfChild(binding.switchPhaseInvertEnabled).coerceAtLeast(0)
        val switchView = MaterialSwitch(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8f)
            }
            text = getString(com.alky.hifx.R.string.effects_vocal_removal_switch)
        }
        container.addView(switchView, insertIndex)
        vocalRemovalSwitch = switchView
    }

    private fun ensureVocalKeyControls() {
        if (vocalKeySectionView != null) return
        val container = binding.panelOther
        val anchorIndex = container.indexOfChild(binding.switchPhaseInvertEnabled).coerceAtLeast(0)

        val titleView = android.widget.TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10f)
            }
            text = getString(com.alky.hifx.R.string.effects_vocal_key_title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        }

        val row = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6f)
            }
            gravity = android.view.Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }

        val downButton = MaterialButton(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f))
            text = "-"
            minWidth = dp(48f)
            insetTop = 0
            insetBottom = 0
        }

        val valueView = android.widget.TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10f)
                marginEnd = dp(10f)
            }
            background = ContextCompat.getDrawable(requireContext(), com.alky.hifx.R.drawable.bg_eq_curve_panel)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        }

        val upButton = MaterialButton(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f))
            text = "+"
            minWidth = dp(48f)
            insetTop = 0
            insetBottom = 0
        }

        row.addView(downButton)
        row.addView(valueView)
        row.addView(upButton)

        val sectionView = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(row)
        }

        container.addView(sectionView, anchorIndex)
        vocalKeySectionView = sectionView
        vocalKeyDownButton = downButton
        vocalKeyUpButton = upButton
        vocalKeyValueView = valueView
    }

    private fun ensureRightChannelPhaseInvertSwitch() {
        if (rightChannelPhaseInvertSwitch != null) return
        val container = binding.panelOther
        val phaseInvertIndex = container.indexOfChild(binding.switchPhaseInvertEnabled)
        val insertIndex = if (phaseInvertIndex >= 0) phaseInvertIndex + 1 else container.childCount
        val switchView = MaterialSwitch(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8f)
            }
            text = getString(com.alky.hifx.R.string.effects_right_channel_phase_invert_switch)
        }
        container.addView(switchView, insertIndex)
        rightChannelPhaseInvertSwitch = switchView
    }

    private fun ensureVocalBandControls() {
        if (vocalBandSectionView != null) return
        val container = binding.panelOther
        val phaseInvertIndex = container.indexOfChild(binding.switchPhaseInvertEnabled)
        val anchorIndex = if (phaseInvertIndex >= 0) phaseInvertIndex + 1 else container.childCount

        val titleView = android.widget.TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10f)
            }
            text = getString(com.alky.hifx.R.string.effects_vocal_band_title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        }

        val knobRow = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10f)
            }
            orientation = LinearLayout.HORIZONTAL
        }

        val lowValueView = android.widget.TextView(requireContext()).apply {
            gravity = android.view.Gravity.CENTER
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }

        val lowKnob = KnobView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(116f), dp(116f))
            valueFrom = 60f
            valueTo = 2000f
            stepSize = 10f
        }

        val highValueView = android.widget.TextView(requireContext()).apply {
            gravity = android.view.Gravity.CENTER
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }

        val highKnob = KnobView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(116f), dp(116f))
            valueFrom = 800f
            valueTo = 8000f
            stepSize = 10f
        }

        knobRow.addView(
            createVocalBandKnobGroup(
                title = getString(com.alky.hifx.R.string.effects_vocal_band_low_knob_title),
                knob = lowKnob,
                valueView = lowValueView,
                addEndMargin = true
            )
        )
        knobRow.addView(
            createVocalBandKnobGroup(
                title = getString(com.alky.hifx.R.string.effects_vocal_band_high_knob_title),
                knob = highKnob,
                valueView = highValueView,
                addEndMargin = false
            )
        )

        val sectionView = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(knobRow)
        }

        container.addView(sectionView, anchorIndex)

        vocalBandSectionView = sectionView
        vocalBandLowValueView = lowValueView
        vocalBandHighValueView = highValueView
        vocalBandLowKnob = lowKnob
        vocalBandHighKnob = highKnob
    }

    private fun createVocalBandKnobGroup(
        title: String,
        knob: KnobView,
        valueView: android.widget.TextView,
        addEndMargin: Boolean
    ): View {
        return LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (addEndMargin) {
                    marginEnd = dp(12f)
                }
            }
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            background = ContextCompat.getDrawable(requireContext(), com.alky.hifx.R.drawable.bg_eq_curve_panel)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))

            addView(android.widget.TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = title
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            })
            addView(knob)
            addView(valueView.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(4f)
                }
            })
        }
    }

    private fun ensurePanKnobControl() {
        if (panKnobControl != null) return
        binding.textPanValue.isVisible = false
        binding.sliderPan.isVisible = false
        panKnobControl = attachKnobCard(
            parent = binding.panelOther,
            anchor = binding.sliderPan,
            title = getString(com.alky.hifx.R.string.effects_pan_knob_title),
            valueFrom = -100f,
            valueTo = 100f,
            stepSize = 1f,
            defaultValue = 0f,
            knobSizeDp = 118f,
            onUserChange = { AudioEngine.setPanPercent(it.toInt()) }
        )
    }

    private fun ensureCrossfeedKnobControl() {
        if (crossfeedKnobControl != null) return
        binding.textCrossfeedValue.isVisible = false
        binding.sliderCrossfeed.isVisible = false
        crossfeedKnobControl = attachKnobCard(
            parent = binding.panelOther,
            anchor = binding.sliderCrossfeed,
            title = getString(com.alky.hifx.R.string.effects_crossfeed_knob_title),
            valueFrom = 0f,
            valueTo = 100f,
            stepSize = 1f,
            defaultValue = 0f,
            knobSizeDp = 118f,
            onUserChange = { AudioEngine.setCrossfeedPercent(it.toInt()) }
        )
    }

    private fun ensurePlaybackSpeedKnobControl() {
        if (speedKnobControl != null) return
        binding.textPlaybackSpeedValue.isVisible = false
        val speedRow = binding.buttonPlaybackSpeedDown.parent as LinearLayout
        val parent = speedRow.parent as LinearLayout
        speedKnobControl = attachKnobCard(
            parent = parent,
            anchor = speedRow,
            title = getString(com.alky.hifx.R.string.effects_playback_speed_knob_title),
            valueFrom = 50f,
            valueTo = 200f,
            stepSize = 1f,
            defaultValue = 100f,
            knobSizeDp = 118f,
            onUserChange = { AudioEngine.setPlaybackSpeedPercent(it.toInt()) }
        )
    }

    private fun ensureConvolutionKnobControl() {
        if (convolutionWetKnobControl != null) return
        binding.textConvolutionWetValue.isVisible = false
        binding.sliderConvolutionWet.isVisible = false
        val parent = binding.sliderConvolutionWet.parent as LinearLayout
        convolutionWetKnobControl = attachKnobCard(
            parent = parent,
            anchor = binding.sliderConvolutionWet,
            title = getString(com.alky.hifx.R.string.effects_convolution_wet_knob_title),
            valueFrom = 0f,
            valueTo = 100f,
            stepSize = 1f,
            defaultValue = 35f,
            knobSizeDp = 118f,
            onUserChange = { AudioEngine.setConvolutionWetPercent(it.toInt()) }
        )
    }

    private fun ensureChannelKnobControls() {
        if (channelSpacingKnobControl != null) return
        binding.layoutChannelSpacing.isVisible = false
        binding.textRadiusSelected.isVisible = false
        binding.sliderRadiusSelected.isVisible = false
        binding.textPosXSelected.isVisible = false
        binding.sliderPosXSelected.isVisible = false
        binding.textPosYSelected.isVisible = false
        binding.sliderPosYSelected.isVisible = false
        binding.textPosZSelected.isVisible = false
        binding.sliderPosZSelected.isVisible = false

        val panel = binding.layoutChannelPositionPanel
        val insertIndex = panel.indexOfChild(binding.layoutChannelSpacing).coerceAtLeast(0)
        val grid = createKnobGrid(columnCount = 2, topMarginDp = 10f)
        panel.addView(grid, insertIndex)

        channelSpacingKnobControl = createGridKnobControl(
            grid = grid,
            title = getString(com.alky.hifx.R.string.effects_channel_spacing_knob_title),
            valueFrom = 0f,
            valueTo = 240f,
            stepSize = 1f,
            defaultValue = 24f,
            knobSizeDp = 92f,
            onUserChange = { AudioEngine.setLinkedChannelSpacingCm(it.toInt()) }
        )
        radiusKnobControl = createGridKnobControl(
            grid = grid,
            title = getString(com.alky.hifx.R.string.effects_radius_knob_title),
            valueFrom = 20f,
            valueTo = 120f,
            stepSize = 1f,
            defaultValue = 100f,
            knobSizeDp = 92f,
            onUserChange = { AudioEngine.setSpatialRadiusPercent(it.toInt()) }
        )
        posXKnobControl = createGridKnobControl(
            grid = grid,
            title = getString(com.alky.hifx.R.string.effects_axis_x_knob_title),
            valueFrom = -120f,
            valueTo = 120f,
            stepSize = 1f,
            defaultValue = 0f,
            knobSizeDp = 92f,
            onUserChange = { applySelectedX(it.toInt()) }
        )
        posYKnobControl = createGridKnobControl(
            grid = grid,
            title = getString(com.alky.hifx.R.string.effects_axis_y_knob_title),
            valueFrom = -120f,
            valueTo = 120f,
            stepSize = 1f,
            defaultValue = 0f,
            knobSizeDp = 92f,
            onUserChange = { applySelectedY(it.toInt()) }
        )
        posZKnobControl = createGridKnobControl(
            grid = grid,
            title = getString(com.alky.hifx.R.string.effects_axis_z_knob_title),
            valueFrom = -120f,
            valueTo = 120f,
            stepSize = 1f,
            defaultValue = 0f,
            knobSizeDp = 92f,
            onUserChange = { applySelectedZ(it.toInt()) }
        )
    }

    private fun ensureSurroundGainKnobControls() {
        if (surroundGainKnobControls.isNotEmpty()) return
        val parent = binding.layoutSurroundGainPanel
        val grid = createKnobGrid(columnCount = 4, topMarginDp = 10f)
        val insertIndex = parent.indexOfChild(binding.textSurroundGainFl).let { index ->
            if (index >= 0) index else parent.childCount
        }
        parent.addView(grid, insertIndex)

        hideSurroundGainSliderViews()

        surroundGainKnobControls[AudioEngine.SURROUND_CHANNEL_FRONT_LEFT] = createGridKnobControl(
            grid = grid,
            title = getString(com.alky.hifx.R.string.effects_surround_channel_fl_short),
            valueFrom = 0f,
            valueTo = 200f,
            stepSize = 1f,
            defaultValue = 100f,
            knobSizeDp = 78f,
            onUserChange = { AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_FRONT_LEFT, it.toInt()) }
        )
        surroundGainKnobControls[AudioEngine.SURROUND_CHANNEL_FRONT_RIGHT] = createGridKnobControl(
            grid = grid,
            title = getString(com.alky.hifx.R.string.effects_surround_channel_fr_short),
            valueFrom = 0f,
            valueTo = 200f,
            stepSize = 1f,
            defaultValue = 100f,
            knobSizeDp = 78f,
            onUserChange = { AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_FRONT_RIGHT, it.toInt()) }
        )
        surroundGainKnobControls[AudioEngine.SURROUND_CHANNEL_CENTER] = createGridKnobControl(
            grid = grid,
            title = getString(com.alky.hifx.R.string.effects_surround_channel_c_short),
            valueFrom = 0f,
            valueTo = 200f,
            stepSize = 1f,
            defaultValue = 100f,
            knobSizeDp = 78f,
            onUserChange = { AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_CENTER, it.toInt()) }
        )
        surroundGainKnobControls[AudioEngine.SURROUND_CHANNEL_LFE] = createGridKnobControl(
            grid = grid,
            title = getString(com.alky.hifx.R.string.effects_surround_channel_lfe_short),
            valueFrom = 0f,
            valueTo = 200f,
            stepSize = 1f,
            defaultValue = 100f,
            knobSizeDp = 78f,
            onUserChange = { AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_LFE, it.toInt()) }
        )
        surroundGainKnobControls[AudioEngine.SURROUND_CHANNEL_SIDE_LEFT] = createGridKnobControl(
            grid = grid,
            title = getString(com.alky.hifx.R.string.effects_surround_channel_sl_short),
            valueFrom = 0f,
            valueTo = 200f,
            stepSize = 1f,
            defaultValue = 100f,
            knobSizeDp = 78f,
            onUserChange = { AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_SIDE_LEFT, it.toInt()) }
        )
        surroundGainKnobControls[AudioEngine.SURROUND_CHANNEL_SIDE_RIGHT] = createGridKnobControl(
            grid = grid,
            title = getString(com.alky.hifx.R.string.effects_surround_channel_sr_short),
            valueFrom = 0f,
            valueTo = 200f,
            stepSize = 1f,
            defaultValue = 100f,
            knobSizeDp = 78f,
            onUserChange = { AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_SIDE_RIGHT, it.toInt()) }
        )
        surroundGainKnobControls[AudioEngine.SURROUND_CHANNEL_REAR_LEFT] = createGridKnobControl(
            grid = grid,
            title = getString(com.alky.hifx.R.string.effects_surround_channel_rl_short),
            valueFrom = 0f,
            valueTo = 200f,
            stepSize = 1f,
            defaultValue = 100f,
            knobSizeDp = 78f,
            onUserChange = { AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_REAR_LEFT, it.toInt()) }
        )
        surroundGainKnobControls[AudioEngine.SURROUND_CHANNEL_REAR_RIGHT] = createGridKnobControl(
            grid = grid,
            title = getString(com.alky.hifx.R.string.effects_surround_channel_rr_short),
            valueFrom = 0f,
            valueTo = 200f,
            stepSize = 1f,
            defaultValue = 100f,
            knobSizeDp = 78f,
            onUserChange = { AudioEngine.setSurroundChannelGain(AudioEngine.SURROUND_CHANNEL_REAR_RIGHT, it.toInt()) }
        )
    }

    private fun hideSurroundGainSliderViews() {
        listOf(
            binding.textSurroundGainFl,
            binding.sliderSurroundGainFl,
            binding.textSurroundGainFr,
            binding.sliderSurroundGainFr,
            binding.textSurroundGainC,
            binding.sliderSurroundGainC,
            binding.textSurroundGainLfe,
            binding.sliderSurroundGainLfe,
            binding.textSurroundGainSl,
            binding.sliderSurroundGainSl,
            binding.textSurroundGainSr,
            binding.sliderSurroundGainSr,
            binding.textSurroundGainRl,
            binding.sliderSurroundGainRl,
            binding.textSurroundGainRr,
            binding.sliderSurroundGainRr
        ).forEach { it.isVisible = false }
    }

    private fun attachKnobCard(
        parent: LinearLayout,
        anchor: View,
        title: String,
        valueFrom: Float,
        valueTo: Float,
        stepSize: Float,
        defaultValue: Float,
        knobSizeDp: Float,
        onUserChange: (Float) -> Unit
    ): com.alky.hifx.EffectsFragment.KnobControl {
        val control = createKnobControl(
            title = title,
            valueFrom = valueFrom,
            valueTo = valueTo,
            stepSize = stepSize,
            defaultValue = defaultValue,
            knobSizeDp = knobSizeDp,
            cardMarginTopDp = 6f,
            onUserChange = onUserChange
        )
        val anchorIndex = parent.indexOfChild(anchor)
        val insertIndex = if (anchorIndex >= 0) anchorIndex + 1 else parent.childCount
        parent.addView(control.container, insertIndex)
        return control
    }

    private fun createKnobGrid(columnCount: Int, topMarginDp: Float): GridLayout {
        return GridLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(topMarginDp)
            }
            this.columnCount = columnCount
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
    }

    private fun createGridKnobControl(
        grid: GridLayout,
        title: String,
        valueFrom: Float,
        valueTo: Float,
        stepSize: Float,
        defaultValue: Float,
        knobSizeDp: Float,
        onUserChange: (Float) -> Unit
    ): com.alky.hifx.EffectsFragment.KnobControl {
        val control = createKnobControl(
            title = title,
            valueFrom = valueFrom,
            valueTo = valueTo,
            stepSize = stepSize,
            defaultValue = defaultValue,
            knobSizeDp = knobSizeDp,
            cardMarginTopDp = 0f,
            onUserChange = onUserChange
        )
        val params = GridLayout.LayoutParams().apply {
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(0, 0, dp(8f), dp(8f))
        }
        grid.addView(control.container, params)
        return control
    }

    private fun createKnobControl(
        title: String,
        valueFrom: Float,
        valueTo: Float,
        stepSize: Float,
        defaultValue: Float,
        knobSizeDp: Float,
        cardMarginTopDp: Float,
        onUserChange: (Float) -> Unit
    ): com.alky.hifx.EffectsFragment.KnobControl {
        val valueView = TextView(requireContext()).apply {
            gravity = android.view.Gravity.CENTER
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }
        val knob = KnobView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(knobSizeDp), dp(knobSizeDp))
            this.valueFrom = valueFrom
            this.valueTo = valueTo
            this.stepSize = stepSize
            this.defaultValue = defaultValue
            value = defaultValue
            onValueChange = { rawValue, fromUser ->
                if (fromUser && !internalUpdating) {
                    onUserChange(rawValue)
                }
            }
        }
        val container = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (cardMarginTopDp > 0f) {
                    topMargin = dp(cardMarginTopDp)
                }
            }
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            background = ContextCompat.getDrawable(requireContext(), com.alky.hifx.R.drawable.bg_eq_curve_panel)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))

            addView(TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                text = title
                gravity = android.view.Gravity.CENTER
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            })
            addView(knob)
            addView(valueView.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(4f)
                }
            })
        }
        return com.alky.hifx.EffectsFragment.KnobControl(container = container, knob = knob, valueView = valueView)
    }

    private fun renderEffectTab(position: Int, animate: Boolean) {
        currentTabPosition = position.coerceIn(0, 2)
        effectsPager?.snapToPage(currentTabPosition, smooth = animate)
        refreshEqSpectrumVisibility()
        updateEffectsPageHeight()
    }

    private fun handleEffectsPageChanged(page: Int) {
        currentTabPosition = page
        refreshEqSpectrumVisibility()
    }

    private fun refreshEqSpectrumVisibility() {
        val binding = _binding ?: return
        if (currentTabPosition == 0) {
            return
        }
        eqSpectrumBuffer.fill(0f)
        binding.eqCurveView.setSpectrum(eqSpectrumBuffer)
    }

    private fun observeEffectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AudioEngine.effectsState.collect { state ->
                        renderEffectState(state)
                    }
                }
                launch {
                    var spectrumVisible = false
                    while (isActive) {
                        if (currentTabPosition == 0) {
                            AudioEngine.fillEqVisualizationSpectrumSnapshot(eqSpectrumBuffer)
                            binding.eqCurveView.setSpectrum(eqSpectrumBuffer)
                            spectrumVisible = true
                            delay(33L)
                        } else {
                            if (spectrumVisible) {
                                eqSpectrumBuffer.fill(0f)
                                binding.eqCurveView.setSpectrum(eqSpectrumBuffer)
                                spectrumVisible = false
                            }
                            delay(180L)
                        }
                    }
                }
            }
        }
    }

    private fun renderEffectState(state: EffectsUiState) {
        internalUpdating = true

        renderEffectsMasterToggle(state.enabled)
        binding.effectsContainer.alpha = if (state.enabled) 1f else 0.45f
        binding.switchEqEnabled.isChecked = state.eqEnabled
        binding.switchSpatialEnabled.isChecked = state.spatialEnabled
        val surroundEnabled = state.surroundMode != AudioEngine.SURROUND_MODE_STEREO
        binding.switchSurroundEnabled.isChecked = surroundEnabled

        binding.switchLimiterEnabled.isChecked = state.limiterEnabled
        binding.sliderPan.value = state.panPercent.toFloat()
        binding.textPanValue.text = panValueLabel(state.panPercent)
        panKnobControl?.knob?.value = state.panPercent.toFloat()
        panKnobControl?.valueView?.text = panValueLabel(state.panPercent)
        binding.switchPanInvertEnabled.isChecked = state.panInvertEnabled
        binding.switchMonoEnabled.isChecked = state.monoEnabled
        vocalRemovalSwitch?.isChecked = state.vocalRemovalEnabled
        vocalKeyValueView?.text = getString(
            com.alky.hifx.R.string.effects_vocal_key_value_format,
            state.vocalKeyShiftSemitones
        )
        vocalKeyDownButton?.isEnabled = state.vocalKeyShiftSemitones > -24
        vocalKeyUpButton?.isEnabled = state.vocalKeyShiftSemitones < 24
        vocalBandLowKnob?.valueTo = (state.vocalBandHighCutHz - 100).coerceAtLeast(60).toFloat()
        vocalBandLowKnob?.value = state.vocalBandLowCutHz.toFloat()
        vocalBandHighKnob?.valueFrom = (state.vocalBandLowCutHz + 100).coerceAtMost(8000).toFloat()
        vocalBandHighKnob?.value = state.vocalBandHighCutHz.toFloat()
        vocalBandLowValueView?.text = getString(
            com.alky.hifx.R.string.effects_vocal_band_low_value_format,
            state.vocalBandLowCutHz
        )
        vocalBandHighValueView?.text = getString(
            com.alky.hifx.R.string.effects_vocal_band_high_value_format,
            state.vocalBandHighCutHz
        )
        binding.switchPhaseInvertEnabled.isChecked = state.phaseInvertEnabled
        rightChannelPhaseInvertSwitch?.isChecked = state.rightChannelPhaseInvertEnabled
        binding.sliderCrossfeed.value = state.crossfeedPercent.toFloat()
        binding.textCrossfeedValue.text = getString(
            com.alky.hifx.R.string.effects_crossfeed_value_format,
            state.crossfeedPercent
        )
        crossfeedKnobControl?.knob?.value = state.crossfeedPercent.toFloat()
        crossfeedKnobControl?.valueView?.text = getString(
            com.alky.hifx.R.string.effects_crossfeed_knob_value_format,
            state.crossfeedPercent
        )
        binding.switchPlaybackSpeedPitchCompensationEnabled.isChecked = state.playbackSpeedPitchCompensationEnabled
        binding.textPlaybackSpeedValue.text = getString(
            com.alky.hifx.R.string.effects_playback_speed_value,
            state.playbackSpeedPercent / 100f
        )
        speedKnobControl?.knob?.value = state.playbackSpeedPercent.toFloat()
        speedKnobControl?.valueView?.text = getString(
            com.alky.hifx.R.string.effects_playback_speed_value,
            state.playbackSpeedPercent / 100f
        )

        binding.textSpatialDistance.text = getString(
            com.alky.hifx.R.string.effects_spatial_distance_value_cm,
            "%.1f".format(state.derivedDistanceMeters * 100f)
        )
        binding.textSpatialGain.text = getString(
            com.alky.hifx.R.string.effects_spatial_gain_value,
            "%.2f".format(state.derivedGainDb)
        )
        binding.textChannelModeValue.text = if (state.channelSeparated) {
            getString(com.alky.hifx.R.string.effects_channel_mode_split)
        } else {
            getString(com.alky.hifx.R.string.effects_channel_mode_linked)
        }
        binding.textChannelPositionValue.text = buildChannelSummary(state)
        binding.switchConvolutionEnabled.isChecked = state.convolutionEnabled
        binding.textConvolutionIrName.text = getString(
            com.alky.hifx.R.string.effects_convolution_ir_name_value,
            state.convolutionIrName
        )
        binding.sliderConvolutionWet.value = state.convolutionWetPercent.toFloat()
        binding.textConvolutionWetValue.text = getString(
            com.alky.hifx.R.string.effects_convolution_wet_value,
            state.convolutionWetPercent
        )
        convolutionWetKnobControl?.knob?.value = state.convolutionWetPercent.toFloat()
        convolutionWetKnobControl?.valueView?.text = getString(
            com.alky.hifx.R.string.effects_convolution_wet_knob_value_format,
            state.convolutionWetPercent
        )
        binding.buttonClearIrs.isEnabled = !state.convolutionIrUri.isNullOrBlank()

        renderSurroundMode(state.surroundMode)
        renderSurroundChannelGains(state)
        renderChannelPanel(state)

        renderEqControls(state)

        internalUpdating = false
    }

    private fun renderEffectsMasterToggle(enabled: Boolean) {
        ensureEffectsMasterToggleVisuals()
        val primary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        val onSurface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val surface = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)

        val strokeWidth = if (enabled) dp(2f) else dp(1.5f)
        binding.cardEffectsMasterToggle.strokeWidth = strokeWidth
        binding.cardEffectsMasterToggle.strokeColor = if (enabled) primary else MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorOutlineVariant
        )
        binding.cardEffectsMasterToggle.setCardBackgroundColor(
            if (enabled) blendColors(surface, primary, 0.12f) else surface
        )

        effectsMasterToggleCore?.setColor(if (enabled) primary else onSurfaceVariant)
        binding.viewEffectsMasterCore.alpha = if (enabled) 0.95f else 0.3f
        binding.cardEffectsMasterToggle.scaleX = 1f
        binding.cardEffectsMasterToggle.scaleY = 1f
        binding.cardEffectsMasterToggle.translationY = 0f

        binding.textEffectsMasterTitle.setTextColor(if (enabled) onSurface else onSurface)
        binding.textEffectsMasterSubtitle.setTextColor(if (enabled) blendColors(primary, Color.WHITE, 0.22f) else onSurfaceVariant)
        binding.textEffectsMasterSubtitle.text = getString(
            if (enabled) com.alky.hifx.R.string.effects_master_subtitle_on else com.alky.hifx.R.string.effects_master_subtitle_off
        )
        binding.textEffectsMasterState.text = getString(
            if (enabled) com.alky.hifx.R.string.effects_master_state_on else com.alky.hifx.R.string.effects_master_state_off
        )
        binding.textEffectsMasterState.setTextColor(if (enabled) primary else onSurfaceVariant)
        binding.layoutEffectsMasterToggle.alpha = if (enabled) 1f else 0.94f
    }

    private fun panValueLabel(panPercent: Int): String {
        return when {
            panPercent > 0 -> getString(com.alky.hifx.R.string.effects_pan_value_right, panPercent)
            panPercent < 0 -> getString(com.alky.hifx.R.string.effects_pan_value_left, -panPercent)
            else -> getString(com.alky.hifx.R.string.effects_pan_value_center)
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

        val freq = state.eqBandFrequenciesHz[selectedEqBandIndex]
        val gainMb = state.eqBandLevelsMb.getOrElse(selectedEqBandIndex) { 0 }
        val q100 = state.eqBandQTimes100.getOrElse(selectedEqBandIndex) { 100 }
        binding.textEqSelectedPoint.text = "节点 ${selectedEqBandIndex + 1} / ${state.eqBandFrequenciesHz.size}"
        binding.textEqFreqValue.text = formatEqFrequencyValue(freq)
        binding.textEqQValue.text = formatEqQValue(q100)
        binding.textEqGainValue.text = formatEqGainValue(gainMb)

        binding.knobEqFreq.value = frequencyHzToSliderValue(freq)
        binding.knobEqQ.value = q100.toFloat()
        if (binding.knobEqGain.valueFrom != state.eqBandLevelMinMb.toFloat()) {
            binding.knobEqGain.valueFrom = state.eqBandLevelMinMb.toFloat()
        }
        if (binding.knobEqGain.valueTo != state.eqBandLevelMaxMb.toFloat()) {
            binding.knobEqGain.valueTo = state.eqBandLevelMaxMb.toFloat()
        }
        binding.knobEqGain.value = gainMb.toFloat()
    }

    private fun updateEqBottomSpacer() {
        val binding = _binding ?: return
        val density = context?.resources?.displayMetrics?.density ?: return
        val card = activity?.findViewById<View>(com.alky.hifx.R.id.mini_player_card)
        val handle = activity?.findViewById<View>(com.alky.hifx.R.id.view_mini_handle)
        val coverView = when {
            card?.isVisible == true -> card
            handle?.isVisible == true -> handle
            else -> null
        }
        val overlayHeight = coverView?.height?.takeIf { it > 0 } ?: 0
        val overlayMargins = (coverView?.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin + it.bottomMargin
        } ?: 0
        val targetHeight = (overlayHeight + overlayMargins + (8f * density).roundToInt())
            .coerceAtLeast((28f * density).roundToInt())
        if (lastEqBottomSpacerHeight == targetHeight) {
            return
        }
        lastEqBottomSpacerHeight = targetHeight
        binding.viewEqBottomSpacer.updateLayoutParams<LinearLayout.LayoutParams> {
            if (height != targetHeight) {
                height = targetHeight
            }
        }
    }

    private fun setupEqBottomInsetTracking() {
        clearEqBottomInsetTracking()
        binding.root.post {
            if (_binding != null) {
                updateEqBottomSpacer()
            }
        }
        listOfNotNull(
            activity?.findViewById<View>(com.alky.hifx.R.id.mini_player_card),
            activity?.findViewById<View>(com.alky.hifx.R.id.view_mini_handle)
        ).forEach { overlay ->
            overlay.addOnLayoutChangeListener(eqBottomInsetLayoutListener)
            eqBottomInsetTrackedOverlays += overlay
        }
    }

    private fun clearEqBottomInsetTracking() {
        eqBottomInsetTrackedOverlays.forEach { overlay ->
            overlay.removeOnLayoutChangeListener(eqBottomInsetLayoutListener)
        }
        eqBottomInsetTrackedOverlays.clear()
    }

    private fun setupEqViewportSizing() {
        binding.eqCurveView.updateLayoutParams<LinearLayout.LayoutParams> {
            val fixedHeight = dp(com.alky.hifx.EffectsFragment.Companion.EQ_CURVE_FIXED_HEIGHT_DP)
            if (height != fixedHeight || weight != 0f) {
                height = fixedHeight
                weight = 0f
            }
        }
    }

    private fun renderSurroundMode(mode: Int) {
        val surroundEnabled = mode != AudioEngine.SURROUND_MODE_STEREO
        val buttonId = when (mode) {
            AudioEngine.SURROUND_MODE_5_1 -> com.alky.hifx.R.id.button_surround_5_1
            AudioEngine.SURROUND_MODE_7_1 -> com.alky.hifx.R.id.button_surround_7_1
            else -> com.alky.hifx.R.id.button_surround_5_1
        }
        if (binding.toggleSurroundMode.checkedButtonId != buttonId) {
            binding.toggleSurroundMode.check(buttonId)
        }
        binding.toggleSurroundMode.isEnabled = surroundEnabled
        binding.buttonToggleSurroundGainPanel.isEnabled = surroundEnabled
        binding.cardSurroundSettings.alpha = if (surroundEnabled) 1f else 0.72f

        val showRear = mode == AudioEngine.SURROUND_MODE_7_1

        setSurroundGainKnobVisible(AudioEngine.SURROUND_CHANNEL_FRONT_LEFT, surroundEnabled)
        setSurroundGainKnobVisible(AudioEngine.SURROUND_CHANNEL_FRONT_RIGHT, surroundEnabled)
        setSurroundGainKnobVisible(AudioEngine.SURROUND_CHANNEL_CENTER, surroundEnabled)
        setSurroundGainKnobVisible(AudioEngine.SURROUND_CHANNEL_LFE, surroundEnabled)
        setSurroundGainKnobVisible(AudioEngine.SURROUND_CHANNEL_SIDE_LEFT, surroundEnabled)
        setSurroundGainKnobVisible(AudioEngine.SURROUND_CHANNEL_SIDE_RIGHT, surroundEnabled)
        setSurroundGainKnobVisible(AudioEngine.SURROUND_CHANNEL_REAR_LEFT, surroundEnabled && showRear)
        setSurroundGainKnobVisible(AudioEngine.SURROUND_CHANNEL_REAR_RIGHT, surroundEnabled && showRear)
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

        renderSurroundGainKnob(AudioEngine.SURROUND_CHANNEL_FRONT_LEFT, frontLeft)
        renderSurroundGainKnob(AudioEngine.SURROUND_CHANNEL_FRONT_RIGHT, frontRight)
        renderSurroundGainKnob(AudioEngine.SURROUND_CHANNEL_CENTER, center)
        renderSurroundGainKnob(AudioEngine.SURROUND_CHANNEL_LFE, lfe)
        renderSurroundGainKnob(AudioEngine.SURROUND_CHANNEL_SIDE_LEFT, sideLeft)
        renderSurroundGainKnob(AudioEngine.SURROUND_CHANNEL_SIDE_RIGHT, sideRight)
        renderSurroundGainKnob(AudioEngine.SURROUND_CHANNEL_REAR_LEFT, rearLeft)
        renderSurroundGainKnob(AudioEngine.SURROUND_CHANNEL_REAR_RIGHT, rearRight)

        updateSurroundGainPanelVisibility()
    }

    private fun renderSurroundGainKnob(channel: Int, value: Int) {
        surroundGainKnobControls[channel]?.let { control ->
            control.knob.value = value.toFloat()
            control.valueView.text = getString(com.alky.hifx.R.string.effects_surround_gain_knob_value_format, value)
        }
    }

    private fun setSurroundGainKnobVisible(channel: Int, visible: Boolean) {
        surroundGainKnobControls[channel]?.container?.isVisible = visible
    }

    private fun renderChannelPanel(state: EffectsUiState) {
        binding.switchChannelSeparated.isChecked = state.channelSeparated
        updateChannelPanelVisibility()
        binding.layoutChannelSelector.isVisible = state.channelSeparated
        binding.layoutChannelSpacing.isVisible = !state.channelSeparated
        channelSpacingKnobControl?.container?.isVisible = !state.channelSeparated

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
            com.alky.hifx.R.string.effects_pos_xz_dual_value_cm,
            state.spatialLeftX,
            state.spatialLeftZ,
            state.spatialRightX,
            state.spatialRightZ
        )
        binding.textRadiusSelected.text = getString(com.alky.hifx.R.string.effects_radius_scale_value_cm, displayRadius)
        binding.sliderRadiusSelected.value = displayRadius.toFloat()
        radiusKnobControl?.knob?.value = displayRadius.toFloat()
        radiusKnobControl?.valueView?.text = getString(
            com.alky.hifx.R.string.effects_radius_knob_value_format,
            displayRadius
        )
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
            com.alky.hifx.R.string.effects_channel_spacing_value_cm,
            spacingForDisplay
        )
        channelSpacingKnobControl?.knob?.valueFrom = 0f
        channelSpacingKnobControl?.knob?.valueTo = maxSpacing.toFloat()
        channelSpacingKnobControl?.knob?.value = spacingForDisplay.toFloat()
        channelSpacingKnobControl?.valueView?.text = getString(
            com.alky.hifx.R.string.effects_channel_spacing_knob_value_format,
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
            getString(com.alky.hifx.R.string.effects_channel_linked_short)
        } else if (selectedHandle == SpatialPadView.Handle.LEFT) {
            getString(com.alky.hifx.R.string.effects_channel_left_short)
        } else {
            getString(com.alky.hifx.R.string.effects_channel_right_short)
        }
        bindAxisSliderRange(binding.sliderPosXSelected, displayRadius, selectedX)
        bindAxisSliderRange(binding.sliderPosZSelected, displayRadius, selectedZ)
        binding.sliderPosYSelected.value = selectedY.toFloat()
        posXKnobControl?.knob?.valueFrom = -displayRadius.toFloat()
        posXKnobControl?.knob?.valueTo = displayRadius.toFloat()
        posXKnobControl?.knob?.value = selectedX.toFloat()
        posXKnobControl?.valueView?.text = getString(com.alky.hifx.R.string.effects_axis_knob_value_format, selectedX)
        posYKnobControl?.knob?.valueFrom = -displayRadius.toFloat()
        posYKnobControl?.knob?.valueTo = displayRadius.toFloat()
        posYKnobControl?.knob?.value = selectedY.toFloat()
        posYKnobControl?.valueView?.text = getString(com.alky.hifx.R.string.effects_axis_knob_value_format, selectedY)
        posZKnobControl?.knob?.valueFrom = -displayRadius.toFloat()
        posZKnobControl?.knob?.valueTo = displayRadius.toFloat()
        posZKnobControl?.knob?.value = selectedZ.toFloat()
        posZKnobControl?.valueView?.text = getString(com.alky.hifx.R.string.effects_axis_knob_value_format, selectedZ)

        binding.textPosXSelected.text = getString(com.alky.hifx.R.string.effects_pos_x_selected_value_cm, selectedLabel, selectedX)
        binding.textPosYSelected.text = getString(com.alky.hifx.R.string.effects_pos_y_selected_value_cm, selectedLabel, selectedY)
        binding.textPosZSelected.text = getString(com.alky.hifx.R.string.effects_pos_z_selected_value_cm, selectedLabel, selectedZ)

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
        surroundGainHeaderIndicator?.text = if (surroundGainPanelExpanded) "▾" else "▸"
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
                com.alky.hifx.R.string.effects_channel_position_summary_linked_cm,
                (state.spatialLeftX + state.spatialRightX) / 2,
                (state.spatialLeftY + state.spatialRightY) / 2,
                (state.spatialLeftZ + state.spatialRightZ) / 2,
                state.linkedChannelSpacingCm
            )
        } else {
            getString(
                com.alky.hifx.R.string.effects_channel_position_summary_split_cm,
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
            hint = getString(com.alky.hifx.R.string.effects_eq_preset_name_hint)
            setText(if (currentName == getString(com.alky.hifx.R.string.effects_eq_custom_name)) "" else currentName)
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(com.alky.hifx.R.string.effects_eq_preset_save_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(com.alky.hifx.R.string.effects_eq_preset_save_confirm) { _, _ ->
                val name = input.text?.toString().orEmpty().trim()
                if (name.isBlank()) {
                    Toast.makeText(requireContext(), getString(com.alky.hifx.R.string.effects_eq_preset_name_hint), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                AudioEngine.saveCurrentEqAsPreset(name)
            }
            .show()
    }

    private fun frequencyHzToSliderValue(frequencyHz: Int): Float {
        val hz = frequencyHz.toFloat().coerceIn(
            com.alky.hifx.EffectsFragment.Companion.EQ_FREQ_MIN_HZ,
            com.alky.hifx.EffectsFragment.Companion.EQ_FREQ_MAX_HZ
        )
        val logMin = ln(com.alky.hifx.EffectsFragment.Companion.EQ_FREQ_MIN_HZ)
        val logMax = ln(com.alky.hifx.EffectsFragment.Companion.EQ_FREQ_MAX_HZ)
        val ratio = ((ln(hz) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
        return com.alky.hifx.EffectsFragment.Companion.EQ_FREQ_SLIDER_MIN + (com.alky.hifx.EffectsFragment.Companion.EQ_FREQ_SLIDER_MAX - com.alky.hifx.EffectsFragment.Companion.EQ_FREQ_SLIDER_MIN) * ratio
    }

    private fun sliderValueToFrequencyHz(sliderValue: Float): Int {
        val ratio = ((sliderValue - com.alky.hifx.EffectsFragment.Companion.EQ_FREQ_SLIDER_MIN) / (com.alky.hifx.EffectsFragment.Companion.EQ_FREQ_SLIDER_MAX - com.alky.hifx.EffectsFragment.Companion.EQ_FREQ_SLIDER_MIN)).coerceIn(0f, 1f)
        val frequency = com.alky.hifx.EffectsFragment.Companion.EQ_FREQ_MIN_HZ * (com.alky.hifx.EffectsFragment.Companion.EQ_FREQ_MAX_HZ / com.alky.hifx.EffectsFragment.Companion.EQ_FREQ_MIN_HZ).pow(ratio)
        return frequency.roundToInt().coerceIn(com.alky.hifx.EffectsFragment.Companion.EQ_FREQ_MIN_HZ.toInt(), com.alky.hifx.EffectsFragment.Companion.EQ_FREQ_MAX_HZ.toInt())
    }

    private fun formatEqFrequencyValue(frequencyHz: Int): String {
        return when {
            frequencyHz >= 10_000 -> String.format("%.1f kHz", frequencyHz / 1000f)
            frequencyHz >= 1000 -> String.format("%.2f kHz", frequencyHz / 1000f)
            else -> "$frequencyHz Hz"
        }
    }

    private fun formatEqQValue(qTimes100: Int): String {
        return String.format("Q %.2f", qTimes100 / 100f)
    }

    private fun formatEqGainValue(gainMb: Int): String {
        return String.format("%+.1f dB", gainMb / 100f)
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

    private fun ensureEffectsMasterToggleVisuals() {
        if (effectsMasterToggleGlow == null) {
            effectsMasterToggleGlow = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28f).toFloat()
            }
        }
        if (effectsMasterToggleCore == null) {
            effectsMasterToggleCore = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(5f).toFloat()
            }
            binding.viewEffectsMasterCore.background = effectsMasterToggleCore
        }
        val primary = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
        effectsMasterToggleGlow?.colors = intArrayOf(
            adjustAlpha(primary, 0.26f),
            adjustAlpha(primary, 0.1f),
            Color.TRANSPARENT
        )
        effectsMasterToggleGlow?.gradientType = GradientDrawable.RADIAL_GRADIENT
        effectsMasterToggleGlow?.gradientRadius = dp(140f).toFloat()
    }

    private fun blendColors(from: Int, to: Int, ratio: Float): Int {
        val t = ratio.coerceIn(0f, 1f)
        val inv = 1f - t
        val a = ((Color.alpha(from) * inv) + (Color.alpha(to) * t)).roundToInt().coerceIn(0, 255)
        val r = ((Color.red(from) * inv) + (Color.red(to) * t)).roundToInt().coerceIn(0, 255)
        val g = ((Color.green(from) * inv) + (Color.green(to) * t)).roundToInt().coerceIn(0, 255)
        val b = ((Color.blue(from) * inv) + (Color.blue(to) * t)).roundToInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
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
