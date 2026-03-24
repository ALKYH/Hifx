package com.example.hifx

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.hifx.audio.LibraryTrack
import com.example.hifx.util.loadArtworkOrDefault
import com.example.hifx.util.toTimeString

sealed interface LibraryListRow {
    data class Header(val title: String) : LibraryListRow
    data class TrackRow(val track: LibraryTrack) : LibraryListRow
}

class MusicLibraryAdapter(
    private val onTrackClick: (LibraryTrack) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<LibraryListRow>()

    fun submitRows(newRows: List<LibraryListRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (rows[position]) {
            is LibraryListRow.Header -> VIEW_TYPE_HEADER
            is LibraryListRow.TrackRow -> VIEW_TYPE_TRACK
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_library_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_track, parent, false)
            TrackViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is LibraryListRow.Header -> (holder as HeaderViewHolder).bind(row)
            is LibraryListRow.TrackRow -> (holder as TrackViewHolder).bind(row.track, onTrackClick)
        }
    }

    override fun getItemCount(): Int = rows.size

    private class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.text_header)

        fun bind(header: LibraryListRow.Header) {
            title.text = header.title
        }
    }

    private class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ImageView = itemView.findViewById(R.id.image_track_cover)
        private val title: TextView = itemView.findViewById(R.id.text_track_title)
        private val subtitle: TextView = itemView.findViewById(R.id.text_track_subtitle)
        private val duration: TextView = itemView.findViewById(R.id.text_track_duration)

        fun bind(track: LibraryTrack, onTrackClick: (LibraryTrack) -> Unit) {
            cover.loadArtworkOrDefault(track.artworkUri)
            title.text = track.title
            subtitle.text = itemView.context.getString(R.string.track_subtitle_format, track.artist, track.album)
            duration.text = track.durationMs.toTimeString()
            itemView.setOnClickListener { onTrackClick(track) }
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 1
        private const val VIEW_TYPE_TRACK = 2
    }
}
