package com.example.hifx

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hifx.audio.AudioEngine
import com.example.hifx.audio.LibraryTrack
import kotlinx.coroutines.launch

class ArtistDetailFragment : Fragment() {
    private lateinit var artistName: String
    private lateinit var textTitle: TextView
    private lateinit var textMeta: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: DetailTrackAdapter
    private var currentTracks: List<LibraryTrack> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        artistName = requireArguments().getString(ARG_ARTIST_NAME).orEmpty()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_artist_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textTitle = view.findViewById(R.id.text_artist_title)
        textMeta = view.findViewById(R.id.text_artist_meta)
        recycler = view.findViewById(R.id.recycler_artist_tracks)
        adapter = DetailTrackAdapter(showAlbumInMeta = true) { track ->
            val idx = currentTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            AudioEngine.playTrackList(currentTracks, idx)
            startActivity(Intent(requireContext(), PlayerActivity::class.java))
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        observeState()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTopBarTitle(artistName)
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AudioEngine.libraryState.collect { state ->
                    val section = state.artists.firstOrNull { it.title == artistName }
                    val tracks = section?.tracks
                        ?.sortedWith(compareBy<LibraryTrack> { it.album.lowercase() }.thenBy { it.discNumber }.thenBy { it.trackNumber }.thenBy { it.title.lowercase() })
                        .orEmpty()
                    currentTracks = tracks
                    adapter.submitTracks(tracks)
                    textTitle.text = artistName
                    textMeta.text = getString(R.string.library_entity_count, tracks.size)
                }
            }
        }
    }

    companion object {
        private const val ARG_ARTIST_NAME = "arg_artist_name"

        fun newInstance(artistName: String): ArtistDetailFragment {
            return ArtistDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ARTIST_NAME, artistName)
                }
            }
        }
    }
}
