package com.example.hifx

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.hifx.audio.AudioEngine
import com.example.hifx.audio.LibraryTrack
import com.example.hifx.audio.MediaLibraryUiState
import com.example.hifx.databinding.FragmentPlaybackBinding
import com.example.hifx.util.AppHaptics
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class PlaybackFragment : Fragment() {
    companion object {
        private var savedLibraryListState: Parcelable? = null
    }

    private var _binding: FragmentPlaybackBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: MusicLibraryAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private var selectedTab: LibraryTab = LibraryTab.ALL
    private var searchQuery: String = ""
    private var currentPlayableTracks: List<com.example.hifx.audio.LibraryTrack> = emptyList()
    private var letterToPosition: Map<String, Int> = emptyMap()
    private var indexHintLabels: Map<String, String> = emptyMap()
    private var searchPanelExpanded = false
    private var tabsPanelExpanded = false
    private var currentSortMode: LibrarySortMode = LibrarySortMode.ALPHABETICAL
    private var randomTrackOrderIds: List<Long> = emptyList()
    private var searchPanelAnimator: ValueAnimator? = null
    private var tabsPanelAnimator: ValueAnimator? = null
    private var miniPlayerLayoutChangeListener: View.OnLayoutChangeListener? = null
    private var miniPlayerPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var miniPlayerCardView: View? = null
    private var miniPlayerHandleView: View? = null
    private var imeVisible = false
    private var pendingLibraryListState: Parcelable? = null
    private var libraryListRestoreScheduled = false
    private var lastIndexHapticLetter: String? = null
    private var lastIndexHapticUptimeMs: Long = 0L
    private val indexLetters: List<String> = buildList {
        for (code in 'A'.code..'Z'.code) add(code.toChar().toString())
        add("#")
    }
    private val panelExpandInterpolator = PathInterpolatorCompat.create(0.18f, 0.9f, 0.2f, 1f)
    private val panelCollapseInterpolator = PathInterpolatorCompat.create(0.32f, 0f, 0.67f, 0f)
    private val indexHapticMinIntervalMs = 24L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                _binding?.buttonRequestPermission?.visibility = View.GONE
                AudioEngine.refreshLibrary()
            } else {
                renderPermissionState()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaybackBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        pendingLibraryListState = savedLibraryListState
        savedLibraryListState = null
        libraryListRestoreScheduled = false
        adapter = MusicLibraryAdapter(
            onTrackClick = { track ->
                AppHaptics.click(requireContext())
                val startIndex = currentPlayableTracks.indexOfFirst { it.id == track.id }
                if (startIndex >= 0) {
                    AudioEngine.playTrackList(currentPlayableTracks, startIndex)
                } else {
                    AudioEngine.playTrack(track)
                }
                if (AudioEngine.shouldOpenPlayerOnTrackPlay()) {
                    startActivity(Intent(requireContext(), PlayerActivity::class.java))
                }
            },
            onEntityClick = { row ->
                AppHaptics.click(requireContext())
                when (row.entityType) {
                    LibraryEntityType.ALBUM -> {
                        (activity as? MainActivity)?.openAlbumDetail(row.title)
                    }

                    LibraryEntityType.ARTIST -> {
                        (activity as? MainActivity)?.openArtistDetail(row.title)
                    }
                }
            }
        )
        layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerLibrary.layoutManager = layoutManager
        binding.recyclerLibrary.itemAnimator = null
        binding.recyclerLibrary.adapter = adapter
        setupTopCollapsibleControls()
        setupKeyboardBottomNavSync()
        attachMiniPlayerAnchorTracking()
        setupAlphabetIndex()
        setupTabs()
        setupSortButton()
        binding.editLibrarySearch.doAfterTextChanged { editable ->
            searchQuery = editable?.toString().orEmpty()
            renderRows(AudioEngine.libraryState.value)
        }
        binding.buttonRequestPermission.setOnClickListener {
            AppHaptics.click(it)
            requestReadAudioPermission()
        }
        observeLibraryState()
        renderPermissionState()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTopBarTitle(getString(R.string.page_playback))
        if (hasReadAudioPermission()) {
            AudioEngine.refreshLibrary()
        }
    }

    override fun onDestroyView() {
        savedLibraryListState = binding.recyclerLibrary.layoutManager?.onSaveInstanceState()
        pendingLibraryListState = null
        libraryListRestoreScheduled = false
        searchPanelAnimator?.cancel()
        tabsPanelAnimator?.cancel()
        detachMiniPlayerAnchorTracking()
        (activity as? MainActivity)?.setBottomNavHiddenForKeyboard(false)
        searchPanelAnimator = null
        tabsPanelAnimator = null
        super.onDestroyView()
        _binding = null
    }

    private fun setupTopCollapsibleControls() {
        applyPanelState(binding.layoutSearchPanel, expanded = false)
        applyPanelState(binding.layoutTabsPanel, expanded = false)
        searchPanelExpanded = false
        tabsPanelExpanded = false
        updateTopControlButtons()

        binding.buttonToggleSearch.setOnClickListener {
            AppHaptics.click(it)
            searchPanelExpanded = !searchPanelExpanded
            animatePanel(
                panel = binding.layoutSearchPanel,
                expanding = searchPanelExpanded,
                previousAnimator = searchPanelAnimator,
                onAnimatorChanged = { searchPanelAnimator = it }
            )
            if (!searchPanelExpanded) {
                binding.editLibrarySearch.clearFocus()
            } else {
                binding.editLibrarySearch.requestFocus()
            }
            syncBottomNavWithKeyboard()
            updateTopControlButtons()
        }
        binding.buttonToggleTabs.setOnClickListener {
            AppHaptics.click(it)
            tabsPanelExpanded = !tabsPanelExpanded
            animatePanel(
                panel = binding.layoutTabsPanel,
                expanding = tabsPanelExpanded,
                previousAnimator = tabsPanelAnimator,
                onAnimatorChanged = { tabsPanelAnimator = it }
            )
            updateTopControlButtons()
        }
        binding.editLibrarySearch.setOnFocusChangeListener { _, _ ->
            syncBottomNavWithKeyboard()
        }
    }

    private fun setupKeyboardBottomNavSync() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            syncBottomNavWithKeyboard()
            insets
        }
        val rootView = binding.root
        rootView.post {
            _binding?.root?.takeIf { it === rootView }?.let(ViewCompat::requestApplyInsets)
        }
    }

    private fun syncBottomNavWithKeyboard() {
        val hide = searchPanelExpanded && imeVisible
        (activity as? MainActivity)?.setBottomNavHiddenForKeyboard(hide)
    }

    private fun attachMiniPlayerAnchorTracking() {
        val hostActivity = activity ?: return
        val card = hostActivity.findViewById<View>(R.id.mini_player_card)
        val handle = hostActivity.findViewById<View>(R.id.view_mini_handle)
        miniPlayerCardView = card
        miniPlayerHandleView = handle
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateFloatingControlsBottomSpacing()
        }
        miniPlayerLayoutChangeListener = listener
        binding.root.addOnLayoutChangeListener(listener)
        card?.addOnLayoutChangeListener(listener)
        handle?.addOnLayoutChangeListener(listener)
        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            updateFloatingControlsBottomSpacing()
            true
        }
        miniPlayerPreDrawListener = preDrawListener
        binding.root.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        val floatingControls = binding.layoutFloatingTopControls
        floatingControls.post {
            _binding?.layoutFloatingTopControls
                ?.takeIf { it === floatingControls }
                ?.let { updateFloatingControlsBottomSpacing() }
        }
    }

    private fun detachMiniPlayerAnchorTracking() {
        val currentBinding = _binding
        miniPlayerLayoutChangeListener?.let { listener ->
            currentBinding?.root?.removeOnLayoutChangeListener(listener)
            miniPlayerCardView?.removeOnLayoutChangeListener(listener)
            miniPlayerHandleView?.removeOnLayoutChangeListener(listener)
        }
        miniPlayerPreDrawListener?.let { preDraw ->
            val observer = currentBinding?.root?.viewTreeObserver
            if (observer?.isAlive == true) {
                observer.removeOnPreDrawListener(preDraw)
            }
        }
        miniPlayerLayoutChangeListener = null
        miniPlayerPreDrawListener = null
        miniPlayerCardView = null
        miniPlayerHandleView = null
    }

    private fun updateFloatingControlsBottomSpacing() {
        val currentBinding = _binding ?: return
        val controls = currentBinding.layoutFloatingTopControls
        val parent = controls.parent as? View ?: return
        val lp = controls.layoutParams as? FrameLayout.LayoutParams ?: return
        val anchor = when {
            miniPlayerCardView?.visibility == View.VISIBLE -> miniPlayerCardView
            miniPlayerHandleView?.visibility == View.VISIBLE -> miniPlayerHandleView
            else -> null
        }
        val baseBottom = dp(12f)
        val nextBottom = if (anchor == null || !anchor.isShown || parent.height <= 0) {
            baseBottom
        } else {
            val anchorLoc = IntArray(2)
            val parentLoc = IntArray(2)
            anchor.getLocationOnScreen(anchorLoc)
            parent.getLocationOnScreen(parentLoc)
            val anchorTopInParent = anchorLoc[1] - parentLoc[1]
            (parent.height - anchorTopInParent + dp(8f)).coerceAtLeast(baseBottom)
        }
        if (lp.bottomMargin != nextBottom) {
            lp.bottomMargin = nextBottom
            controls.layoutParams = lp
        }
    }

    private fun updateTopControlButtons() {
        binding.buttonToggleSearch.alpha = if (searchPanelExpanded) 1f else 0.72f
        binding.buttonSortMode.alpha = if (currentSortMode == LibrarySortMode.ALPHABETICAL) 0.72f else 1f
        binding.buttonToggleTabs.alpha = if (tabsPanelExpanded) 1f else 0.72f
        binding.buttonToggleSearch.contentDescription = if (searchPanelExpanded) {
            getString(R.string.library_toggle_search_collapse)
        } else {
            getString(R.string.library_toggle_search_expand)
        }
        binding.buttonSortMode.contentDescription = getString(
            R.string.library_sort_button
        ) + "：" + getString(currentSortMode.labelRes)
        binding.buttonToggleTabs.contentDescription = if (tabsPanelExpanded) {
            getString(R.string.library_toggle_tabs_collapse)
        } else {
            getString(R.string.library_toggle_tabs_expand)
        }
    }

    private fun setupSortButton() {
        binding.buttonSortMode.setOnClickListener { view ->
            AppHaptics.click(view)
            val popup = PopupMenu(requireContext(), view)
            popup.menu.add(Menu.NONE, LibrarySortMode.ALPHABETICAL.ordinal, Menu.NONE, getString(LibrarySortMode.ALPHABETICAL.labelRes))
            popup.menu.add(Menu.NONE, LibrarySortMode.RECENT_ADDED.ordinal, Menu.NONE, getString(LibrarySortMode.RECENT_ADDED.labelRes))
            popup.menu.add(Menu.NONE, LibrarySortMode.MOST_PLAYED.ordinal, Menu.NONE, getString(LibrarySortMode.MOST_PLAYED.labelRes))
            popup.menu.add(Menu.NONE, LibrarySortMode.SHUFFLED.ordinal, Menu.NONE, getString(LibrarySortMode.SHUFFLED.labelRes))
            popup.setOnMenuItemClickListener { item ->
                val mode = LibrarySortMode.entries.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
                if (currentSortMode != mode) {
                    currentSortMode = mode
                    if (mode == LibrarySortMode.SHUFFLED) {
                        randomTrackOrderIds = emptyList()
                    }
                    updateTopControlButtons()
                    renderRows(AudioEngine.libraryState.value)
                }
                true
            }
            popup.show()
        }
    }

    private fun animatePanel(
        panel: View,
        expanding: Boolean,
        previousAnimator: ValueAnimator?,
        onAnimatorChanged: (ValueAnimator?) -> Unit
    ) {
        previousAnimator?.cancel()
        if (expanding) {
            val targetHeight = measureExpandedHeight(panel)
            panel.pivotY = 0f
            panel.pivotX = panel.width * 0.5f
            panel.visibility = View.VISIBLE
            panel.alpha = 0f
            panel.scaleY = 0.86f
            panel.translationY = -dp(12f).toFloat()
            panel.layoutParams = panel.layoutParams.apply { height = 0 }
            val animator = ValueAnimator.ofInt(0, targetHeight).apply {
                duration = 320L
                interpolator = panelExpandInterpolator
                addUpdateListener { valueAnimator ->
                    panel.layoutParams = panel.layoutParams.apply {
                        height = valueAnimator.animatedValue as Int
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var canceled = false

                    override fun onAnimationCancel(animation: Animator) {
                        canceled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        onAnimatorChanged(null)
                        if (!canceled) {
                            panel.layoutParams = panel.layoutParams.apply {
                                height = ViewGroup.LayoutParams.WRAP_CONTENT
                            }
                            panel.alpha = 1f
                            panel.scaleY = 1f
                            panel.translationY = 0f
                        }
                    }
                })
            }
            onAnimatorChanged(animator)
            panel.animate()
                .alpha(1f)
                .scaleY(1f)
                .translationY(0f)
                .setInterpolator(panelExpandInterpolator)
                .setDuration(300L)
                .start()
            animator.start()
            return
        }

        if (panel.visibility != View.VISIBLE) {
            onAnimatorChanged(null)
            return
        }
        val startHeight = panel.height.takeIf { it > 0 } ?: measureExpandedHeight(panel)
        panel.pivotY = 0f
        panel.pivotX = panel.width * 0.5f
        panel.layoutParams = panel.layoutParams.apply { height = startHeight }
        val animator = ValueAnimator.ofInt(startHeight, 0).apply {
            duration = 260L
            interpolator = panelCollapseInterpolator
            addUpdateListener { valueAnimator ->
                panel.layoutParams = panel.layoutParams.apply {
                    height = valueAnimator.animatedValue as Int
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onAnimatorChanged(null)
                    panel.visibility = View.GONE
                    panel.layoutParams = panel.layoutParams.apply {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    panel.alpha = 1f
                    panel.scaleY = 1f
                    panel.translationY = 0f
                }
            })
        }
        onAnimatorChanged(animator)
        panel.animate()
            .alpha(0f)
            .scaleY(0.92f)
            .translationY(-dp(10f).toFloat())
            .setInterpolator(panelCollapseInterpolator)
            .setDuration(220L)
            .start()
        animator.start()
    }

    private fun applyPanelState(panel: View, expanded: Boolean) {
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        panel.layoutParams = panel.layoutParams.apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        panel.alpha = 1f
        panel.scaleY = 1f
        panel.translationY = 0f
    }

    private fun measureExpandedHeight(panel: View): Int {
        val parent = panel.parent as? View
        val parentWidth = parent?.width?.takeIf { it > 0 } ?: binding.root.width
        val width = (parentWidth - (parent?.paddingLeft ?: 0) - (parent?.paddingRight ?: 0)).coerceAtLeast(1)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        panel.measure(widthSpec, heightSpec)
        return panel.measuredHeight.coerceAtLeast(1)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun setupTabs() {
        binding.tabLibrary.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                AppHaptics.click(requireContext())
                selectedTab = when (tab?.position ?: 0) {
                    1 -> LibraryTab.ALBUM
                    2 -> LibraryTab.ARTIST
                    else -> LibraryTab.ALL
                }
                renderRows(AudioEngine.libraryState.value)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })
    }

    private fun observeLibraryState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AudioEngine.libraryState.collect { state ->
                    renderRows(state)
                }
            }
        }
    }

    private fun renderRows(state: MediaLibraryUiState) {
        binding.progressLibrary.visibility = if (state.loading) View.VISIBLE else View.GONE

        val normalizedQuery = searchQuery.trim()
        val rows: List<LibraryListRow>
        val playableTracks: List<com.example.hifx.audio.LibraryTrack>
        when (selectedTab) {
            LibraryTab.ALL -> {
                val tracks = sortTracks(state.tracks.filter { trackMatchesQuery(it, normalizedQuery) })
                rows = tracks.map { LibraryListRow.TrackRow(it) }
                playableTracks = tracks
            }

            LibraryTab.ALBUM -> {
                val sections = filterSectionsByQuery(state.albums, normalizedQuery)
                rows = buildSectionRows(sections)
                playableTracks = emptyList()
            }

            LibraryTab.ARTIST -> {
                val sections = filterSectionsByQuery(state.artists, normalizedQuery)
                rows = buildSectionRows(sections)
                playableTracks = emptyList()
            }
        }
        currentPlayableTracks = playableTracks
        adapter.submitRows(rows, showPlayCount = currentSortMode == LibrarySortMode.MOST_PLAYED && selectedTab == LibraryTab.ALL)
        rebuildIndex(rows)
        if (pendingLibraryListState != null && rows.isNotEmpty() && !libraryListRestoreScheduled) {
            val restoreState = pendingLibraryListState
            pendingLibraryListState = null
            libraryListRestoreScheduled = true
            binding.recyclerLibrary.layoutManager?.onRestoreInstanceState(restoreState)
            val recyclerView = binding.recyclerLibrary
            recyclerView.post {
                if (_binding?.recyclerLibrary === recyclerView) {
                    libraryListRestoreScheduled = false
                }
            }
        }

        val hasRows = rows.isNotEmpty()
        binding.layoutEmpty.visibility = if (hasRows || state.loading) View.GONE else View.VISIBLE
        binding.viewAlphaIndex.visibility = if (hasRows && shouldShowIndex()) View.VISIBLE else View.GONE
        binding.textEmpty.text = when {
            !hasReadAudioPermission() -> getString(R.string.library_permission_required)
            state.errorMessage != null -> getString(R.string.library_empty_with_error, state.errorMessage)
            else -> getString(R.string.library_empty)
        }
    }

    private fun buildSectionRows(sections: List<com.example.hifx.audio.TrackSection>): List<LibraryListRow> {
        val type = when (selectedTab) {
            LibraryTab.ALBUM -> LibraryEntityType.ALBUM
            LibraryTab.ARTIST -> LibraryEntityType.ARTIST
            else -> LibraryEntityType.ALBUM
        }
        return sections.map { section ->
            val subtitle = getString(R.string.library_entity_count, section.tracks.size)
            LibraryListRow.EntityRow(
                entityType = type,
                title = section.title,
                subtitle = subtitle,
                artworkUri = section.tracks.firstOrNull()?.artworkUri
            )
        }
    }

    private fun filterSectionsByQuery(
        sections: List<com.example.hifx.audio.TrackSection>,
        query: String
    ): List<com.example.hifx.audio.TrackSection> {
        if (query.isBlank()) {
            return sections
        }
        return sections.mapNotNull { section ->
            val sectionMatched = section.title.contains(query, ignoreCase = true)
            val filteredTracks = if (sectionMatched) {
                section.tracks
            } else {
                section.tracks.filter { trackMatchesQuery(it, query) }
            }
            if (filteredTracks.isEmpty()) {
                null
            } else {
                section.copy(tracks = filteredTracks)
            }
        }
    }

    private fun trackMatchesQuery(track: com.example.hifx.audio.LibraryTrack, query: String): Boolean {
        if (query.isBlank()) {
            return true
        }
        return track.title.contains(query, ignoreCase = true) ||
            track.artist.contains(query, ignoreCase = true) ||
            track.album.contains(query, ignoreCase = true)
    }

    private fun renderPermissionState() {
        val currentBinding = _binding ?: return
        val granted = hasReadAudioPermission()
        currentBinding.buttonRequestPermission.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted) {
            AudioEngine.refreshLibrary()
        } else {
            currentBinding.layoutEmpty.visibility = View.VISIBLE
            currentBinding.textEmpty.text = getString(R.string.library_permission_required)
            currentBinding.viewAlphaIndex.visibility = View.GONE
            currentBinding.textIndexHint.visibility = View.GONE
        }
    }

    private fun setupAlphabetIndex() {
        binding.viewAlphaIndex.onLetterTouch = { letter, touching ->
            if (letterToPosition.isEmpty()) {
                binding.textIndexHint.visibility = View.GONE
                if (!touching) {
                    lastIndexHapticLetter = null
                }
            } else {
                val targetPosition = resolveIndexTargetPosition(letter)
                if (targetPosition >= 0) {
                    layoutManager.scrollToPositionWithOffset(targetPosition, 0)
                    maybeTriggerIndexScrubHaptic(letter, touching)
                }
                binding.textIndexHint.text = indexHintLabels[letter] ?: letter
                binding.textIndexHint.visibility = if (touching) View.VISIBLE else View.GONE
                if (!touching) {
                    lastIndexHapticLetter = null
                }
            }
        }
        binding.recyclerLibrary.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_DRAGGING) {
                    binding.textIndexHint.visibility = View.GONE
                }
            }
        })
    }

    private fun rebuildIndex(rows: List<LibraryListRow>) {
        if (rows.isEmpty()) {
            letterToPosition = emptyMap()
            indexHintLabels = emptyMap()
            return
        }
        if (selectedTab != LibraryTab.ALL || currentSortMode == LibrarySortMode.ALPHABETICAL) {
            binding.viewAlphaIndex.setEntries(indexLetters, indexLetters)
            rebuildAlphabetIndex(rows)
            return
        }
        if (currentSortMode == LibrarySortMode.RECENT_ADDED) {
            rebuildRecentAddedIndex(rows)
            return
        }
        binding.viewAlphaIndex.setEntries(emptyList(), emptyList())
        letterToPosition = emptyMap()
        indexHintLabels = emptyMap()
    }

    private fun rebuildAlphabetIndex(rows: List<LibraryListRow>) {
        val map = linkedMapOf<String, Int>()
        rows.forEachIndexed { index, row ->
            val source = when (row) {
                is LibraryListRow.Header -> row.title
                is LibraryListRow.TrackRow -> row.track.title
                is LibraryListRow.EntityRow -> row.title
            }
            val key = source.toAlphabetIndexKey()
            if (!map.containsKey(key)) {
                map[key] = index
            }
        }
        letterToPosition = map
        indexHintLabels = map.keys.associateWith { it }
    }

    private fun rebuildRecentAddedIndex(rows: List<LibraryListRow>) {
        val keys = mutableListOf<String>()
        val labels = mutableListOf<String>()
        val positions = linkedMapOf<String, Int>()
        val hints = linkedMapOf<String, String>()
        val seenLabels = mutableSetOf<String>()
        rows.forEachIndexed { index, row ->
            val track = (row as? LibraryListRow.TrackRow)?.track ?: return@forEachIndexed
            val label = track.dateAddedEpochSeconds.toRecentIndexLabel()
            if (label.isBlank()) {
                return@forEachIndexed
            }
            if (seenLabels.add(label)) {
                val token = "recent_$index"
                keys += token
                labels += ""
                positions[token] = index
                hints[token] = label
            }
        }
        binding.viewAlphaIndex.setEntries(keys, labels)
        letterToPosition = positions
        indexHintLabels = hints
    }

    private fun resolveIndexTargetPosition(letter: String): Int {
        letterToPosition[letter]?.let { return it }
        val targetIndex = indexLetters.indexOf(letter).takeIf { it >= 0 } ?: 0
        for (index in targetIndex + 1 until indexLetters.size) {
            val fallback = letterToPosition[indexLetters[index]]
            if (fallback != null) return fallback
        }
        for (index in targetIndex - 1 downTo 0) {
            val fallback = letterToPosition[indexLetters[index]]
            if (fallback != null) return fallback
        }
        return -1
    }

    private fun maybeTriggerIndexScrubHaptic(letter: String, touching: Boolean) {
        if (!touching) {
            return
        }
        if (letter == lastIndexHapticLetter) {
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastIndexHapticUptimeMs < indexHapticMinIntervalMs) {
            return
        }
        lastIndexHapticLetter = letter
        lastIndexHapticUptimeMs = now
        AppHaptics.scrubTick(binding.viewAlphaIndex)
    }

    private fun String.toAlphabetIndexKey(): String {
        val first = this.trim().firstOrNull() ?: return "#"
        val upper = first.uppercaseChar()
        return if (upper in 'A'..'Z') upper.toString() else "#"
    }

    private fun sortTracks(tracks: List<LibraryTrack>): List<LibraryTrack> {
        return when (currentSortMode) {
            LibrarySortMode.ALPHABETICAL -> tracks.sortedBy { it.title.lowercase() }
            LibrarySortMode.RECENT_ADDED -> tracks.sortedWith(
                compareByDescending<LibraryTrack> { it.dateAddedEpochSeconds }
                    .thenBy { it.title.lowercase() }
            )
            LibrarySortMode.MOST_PLAYED -> tracks.sortedWith(
                compareByDescending<LibraryTrack> { it.playCount }
                    .thenBy { it.title.lowercase() }
            )
            LibrarySortMode.SHUFFLED -> {
                val ids = tracks.map { it.id }
                if (randomTrackOrderIds.size != ids.size || !randomTrackOrderIds.containsAll(ids)) {
                    randomTrackOrderIds = ids.shuffled()
                }
                val order = randomTrackOrderIds.withIndex().associate { it.value to it.index }
                tracks.sortedBy { order[it.id] ?: Int.MAX_VALUE }
            }
        }
    }

    private fun shouldShowIndex(): Boolean {
        return selectedTab == LibraryTab.ALL && currentSortMode != LibrarySortMode.MOST_PLAYED && currentSortMode != LibrarySortMode.SHUFFLED ||
            selectedTab != LibraryTab.ALL
    }

    private fun Long.toRecentIndexLabel(): String {
        if (this <= 0L) return ""
        return DateUtils.formatDateTime(
            requireContext(),
            this * 1000L,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR
        )
    }

    private fun requestReadAudioPermission() {
        permissionLauncher.launch(requiredReadAudioPermission())
    }

    private fun hasReadAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            requiredReadAudioPermission()
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredReadAudioPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private enum class LibraryTab {
        ALL,
        ALBUM,
        ARTIST
    }

    private enum class LibrarySortMode(val labelRes: Int) {
        ALPHABETICAL(R.string.library_sort_alpha),
        RECENT_ADDED(R.string.library_sort_recent),
        MOST_PLAYED(R.string.library_sort_most_played),
        SHUFFLED(R.string.library_sort_shuffle)
    }
}
