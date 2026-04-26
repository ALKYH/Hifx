package com.example.hifx

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hifx.audio.AudioEngine
import com.example.hifx.audio.LibraryTrack
import com.example.hifx.util.loadArtworkOrDefault
import kotlinx.coroutines.launch

class AlbumDetailFragment : Fragment() {
    private lateinit var albumName: String
    private lateinit var imageCover: ImageView
    private lateinit var textTitle: TextView
    private lateinit var textMeta: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: DetailTrackAdapter
    private var currentTracks: List<LibraryTrack> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        albumName = requireArguments().getString(ARG_ALBUM_NAME).orEmpty()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_album_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        imageCover = view.findViewById(R.id.image_album_cover)
        textTitle = view.findViewById(R.id.text_album_title)
        textMeta = view.findViewById(R.id.text_album_meta)
        recycler = view.findViewById(R.id.recycler_album_tracks)
        adapter = DetailTrackAdapter(showAlbumInMeta = false) { track ->
            val idx = currentTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            AudioEngine.playTrackList(currentTracks, idx)
            startActivity(Intent(requireContext(), PlayerActivity::class.java))
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        recycler.isNestedScrollingEnabled = false
        observeState()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTopBarTitle(albumName)
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AudioEngine.libraryState.collect { state ->
                    val section = state.albums.firstOrNull { it.title == albumName }
                    val tracks = section?.tracks
                        ?.sortedWith(compareBy<LibraryTrack> { it.discNumber }.thenBy { it.trackNumber }.thenBy { it.title.lowercase() })
                        .orEmpty()
                    currentTracks = tracks
                    adapter.submitTracks(tracks)
                    textTitle.text = albumName
                    textMeta.text = getString(R.string.library_entity_count, tracks.size)
                    imageCover.loadArtworkOrDefault(tracks.firstOrNull()?.artworkUri)
                }
            }
        }
    }

    companion object {
        private const val ARG_ALBUM_NAME = "arg_album_name"

        fun newInstance(albumName: String): AlbumDetailFragment {
            return AlbumDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ALBUM_NAME, albumName)
                }
            }
        }
    }
}
