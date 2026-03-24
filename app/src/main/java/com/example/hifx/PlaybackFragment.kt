package com.example.hifx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.hifx.audio.AudioEngine
import com.example.hifx.audio.MediaLibraryUiState
import com.example.hifx.databinding.FragmentPlaybackBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class PlaybackFragment : Fragment() {
    private var _binding: FragmentPlaybackBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: MusicLibraryAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private var selectedTab: LibraryTab = LibraryTab.ALL
    private var letterToPosition: Map<String, Int> = emptyMap()
    private val indexLetters: List<String> = buildList {
        for (code in 'A'.code..'Z'.code) add(code.toChar().toString())
        add("#")
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                binding.buttonRequestPermission.visibility = View.GONE
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
        adapter = MusicLibraryAdapter { track ->
            AudioEngine.playTrack(track)
            startActivity(Intent(requireContext(), PlayerActivity::class.java))
        }
        layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerLibrary.layoutManager = layoutManager
        binding.recyclerLibrary.adapter = adapter
        setupAlphabetIndex()
        setupTabs()
        binding.buttonRequestPermission.setOnClickListener { requestReadAudioPermission() }
        observeLibraryState()
        renderPermissionState()
    }

    override fun onResume() {
        super.onResume()
        if (hasReadAudioPermission()) {
            AudioEngine.refreshLibrary()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupTabs() {
        binding.tabLibrary.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
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
        binding.textScanFolder.text = state.scanFolderLabel
        binding.progressLibrary.visibility = if (state.loading) View.VISIBLE else View.GONE

        val rows = when (selectedTab) {
            LibraryTab.ALL -> state.tracks.map { LibraryListRow.TrackRow(it) }
            LibraryTab.ALBUM -> buildSectionRows(state.albums)
            LibraryTab.ARTIST -> buildSectionRows(state.artists)
        }
        adapter.submitRows(rows)
        rebuildLetterIndex(rows)

        val hasRows = rows.isNotEmpty()
        binding.layoutEmpty.visibility = if (hasRows || state.loading) View.GONE else View.VISIBLE
        binding.viewAlphaIndex.visibility = if (hasRows) View.VISIBLE else View.GONE
        binding.textEmpty.text = when {
            !hasReadAudioPermission() -> getString(R.string.library_permission_required)
            state.errorMessage != null -> getString(R.string.library_empty_with_error, state.errorMessage)
            else -> getString(R.string.library_empty)
        }
    }

    private fun buildSectionRows(sections: List<com.example.hifx.audio.TrackSection>): List<LibraryListRow> {
        val rows = mutableListOf<LibraryListRow>()
        sections.forEach { section ->
            rows += LibraryListRow.Header(section.title)
            rows += section.tracks.map { LibraryListRow.TrackRow(it) }
        }
        return rows
    }

    private fun renderPermissionState() {
        val granted = hasReadAudioPermission()
        binding.buttonRequestPermission.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted) {
            AudioEngine.refreshLibrary()
        } else {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.textEmpty.text = getString(R.string.library_permission_required)
            binding.viewAlphaIndex.visibility = View.GONE
            binding.textIndexHint.visibility = View.GONE
        }
    }

    private fun setupAlphabetIndex() {
        binding.viewAlphaIndex.setLetters(indexLetters)
        binding.viewAlphaIndex.onLetterTouch = { letter, touching ->
            if (letterToPosition.isEmpty()) {
                binding.textIndexHint.visibility = View.GONE
            } else {
                val targetPosition = resolveIndexTargetPosition(letter)
                if (targetPosition >= 0) {
                    layoutManager.scrollToPositionWithOffset(targetPosition, 0)
                }
                binding.textIndexHint.text = letter
                binding.textIndexHint.visibility = if (touching) View.VISIBLE else View.GONE
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

    private fun rebuildLetterIndex(rows: List<LibraryListRow>) {
        if (rows.isEmpty()) {
            letterToPosition = emptyMap()
            return
        }
        val map = linkedMapOf<String, Int>()
        rows.forEachIndexed { index, row ->
            val source = when (row) {
                is LibraryListRow.Header -> row.title
                is LibraryListRow.TrackRow -> row.track.title
            }
            val key = source.toAlphabetIndexKey()
            if (!map.containsKey(key)) {
                map[key] = index
            }
        }
        letterToPosition = map
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

    private fun String.toAlphabetIndexKey(): String {
        val first = this.trim().firstOrNull() ?: return "#"
        val upper = first.uppercaseChar()
        return if (upper in 'A'..'Z') upper.toString() else "#"
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
}
