package com.alky.hifx

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alky.hifx.audio.AudioEngine
import com.alky.hifx.audio.LibraryTrack
import kotlinx.coroutines.launch

class ArtistDetailFragment : Fragment() {
    private lateinit var artistName: String
    private lateinit var contentLayout: LinearLayout
    private lateinit var textTitle: TextView
    private lateinit var textMeta: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: DetailTrackAdapter
    private var currentTracks: List<LibraryTrack> = emptyList()
    private var miniPlayerLayoutChangeListener: View.OnLayoutChangeListener? = null
    private var miniPlayerCardView: View? = null
    private var miniPlayerHandleView: View? = null
    private var systemBottomInsetPx: Int = 0

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
        contentLayout = view.findViewById(R.id.layout_artist_content)
        textTitle = view.findViewById(R.id.text_artist_title)
        textMeta = view.findViewById(R.id.text_artist_meta)
        recycler = view.findViewById(R.id.recycler_artist_tracks)
        adapter = DetailTrackAdapter(showAlbumInMeta = true) { track ->
            val idx = currentTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            AudioEngine.playTrackList(currentTracks, idx)
            if (AudioEngine.shouldOpenPlayerOnTrackPlay()) {
                startActivity(Intent(requireContext(), PlayerActivity::class.java))
            }
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        attachMiniPlayerInsetTracking(view)
        observeState()
    }

    override fun onDestroyView() {
        detachMiniPlayerInsetTracking()
        super.onDestroyView()
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

    private fun attachMiniPlayerInsetTracking(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            systemBottomInsetPx = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            updateMiniPlayerBottomInset(root)
            insets
        }
        val hostActivity = activity ?: return
        val card = hostActivity.findViewById<View>(R.id.mini_player_card)
        val handle = hostActivity.findViewById<View>(R.id.view_mini_handle)
        miniPlayerCardView = card
        miniPlayerHandleView = handle
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateMiniPlayerBottomInset(root)
        }
        miniPlayerLayoutChangeListener = listener
        root.addOnLayoutChangeListener(listener)
        card?.addOnLayoutChangeListener(listener)
        handle?.addOnLayoutChangeListener(listener)
        root.post { updateMiniPlayerBottomInset(root) }
        ViewCompat.requestApplyInsets(root)
    }

    private fun detachMiniPlayerInsetTracking() {
        miniPlayerLayoutChangeListener?.let { listener ->
            view?.removeOnLayoutChangeListener(listener)
            miniPlayerCardView?.removeOnLayoutChangeListener(listener)
            miniPlayerHandleView?.removeOnLayoutChangeListener(listener)
        }
        miniPlayerLayoutChangeListener = null
        miniPlayerCardView = null
        miniPlayerHandleView = null
    }

    private fun updateMiniPlayerBottomInset(root: View) {
        val anchor = when {
            miniPlayerCardView?.visibility == View.VISIBLE -> miniPlayerCardView
            miniPlayerHandleView?.visibility == View.VISIBLE -> miniPlayerHandleView
            else -> null
        }
        val baseBottom = dp(8f) + systemBottomInsetPx
        val extraBottom = if (anchor == null || !anchor.isShown) {
            0
        } else {
            val anchorLoc = IntArray(2)
            val rootLoc = IntArray(2)
            anchor.getLocationOnScreen(anchorLoc)
            root.getLocationOnScreen(rootLoc)
            val anchorTopInRoot = anchorLoc[1] - rootLoc[1]
            (root.height - anchorTopInRoot + dp(8f)).coerceAtLeast(0)
        }
        contentLayout.updatePadding(bottom = baseBottom)
        recycler.updatePadding(bottom = dp(12f) + extraBottom)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

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
