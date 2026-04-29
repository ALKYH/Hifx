package com.alky.hifx

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alky.hifx.audio.AudioEngine
import com.alky.hifx.audio.LibraryTrack
import com.alky.hifx.util.loadArtworkOrDefault
import kotlinx.coroutines.launch

class AlbumDetailFragment : Fragment() {
    private lateinit var albumName: String
    private lateinit var imageCover: ImageView
    private lateinit var textTitle: TextView
    private lateinit var textMeta: TextView
    private lateinit var scrollView: NestedScrollView
    private lateinit var contentLayout: LinearLayout
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: DetailTrackAdapter
    private var currentTracks: List<LibraryTrack> = emptyList()
    private var miniPlayerLayoutChangeListener: View.OnLayoutChangeListener? = null
    private var miniPlayerCardView: View? = null
    private var miniPlayerHandleView: View? = null
    private var systemBottomInsetPx: Int = 0

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
        scrollView = view as NestedScrollView
        contentLayout = view.findViewById(R.id.layout_album_content)
        recycler = view.findViewById(R.id.recycler_album_tracks)
        adapter = DetailTrackAdapter(showAlbumInMeta = false) { track ->
            val idx = currentTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            AudioEngine.playTrackList(currentTracks, idx)
            if (AudioEngine.shouldOpenPlayerOnTrackPlay()) {
                startActivity(Intent(requireContext(), PlayerActivity::class.java))
            }
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        recycler.isNestedScrollingEnabled = false
        attachMiniPlayerInsetTracking()
        observeState()
    }

    override fun onDestroyView() {
        detachMiniPlayerInsetTracking()
        super.onDestroyView()
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

    private fun attachMiniPlayerInsetTracking() {
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { _, insets ->
            systemBottomInsetPx = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            updateMiniPlayerBottomInset()
            insets
        }
        val hostActivity = activity ?: return
        val card = hostActivity.findViewById<View>(R.id.mini_player_card)
        val handle = hostActivity.findViewById<View>(R.id.view_mini_handle)
        miniPlayerCardView = card
        miniPlayerHandleView = handle
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateMiniPlayerBottomInset()
        }
        miniPlayerLayoutChangeListener = listener
        scrollView.addOnLayoutChangeListener(listener)
        card?.addOnLayoutChangeListener(listener)
        handle?.addOnLayoutChangeListener(listener)
        scrollView.post { updateMiniPlayerBottomInset() }
        ViewCompat.requestApplyInsets(scrollView)
    }

    private fun detachMiniPlayerInsetTracking() {
        miniPlayerLayoutChangeListener?.let { listener ->
            scrollView.removeOnLayoutChangeListener(listener)
            miniPlayerCardView?.removeOnLayoutChangeListener(listener)
            miniPlayerHandleView?.removeOnLayoutChangeListener(listener)
        }
        miniPlayerLayoutChangeListener = null
        miniPlayerCardView = null
        miniPlayerHandleView = null
    }

    private fun updateMiniPlayerBottomInset() {
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
            scrollView.getLocationOnScreen(rootLoc)
            val anchorTopInRoot = anchorLoc[1] - rootLoc[1]
            (scrollView.height - anchorTopInRoot + dp(8f)).coerceAtLeast(0)
        }
        contentLayout.updatePadding(bottom = baseBottom + extraBottom)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

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
