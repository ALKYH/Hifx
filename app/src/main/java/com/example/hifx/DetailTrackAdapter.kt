package com.example.hifx

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.hifx.audio.LibraryTrack
import com.example.hifx.util.toTimeString

class DetailTrackAdapter(
    private val showAlbumInMeta: Boolean,
    private val onTrackClick: (LibraryTrack) -> Unit
) : RecyclerView.Adapter<DetailTrackAdapter.TrackViewHolder>() {

    private val tracks = mutableListOf<LibraryTrack>()

    fun submitTracks(items: List<LibraryTrack>) {
        tracks.clear()
        tracks.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_detail_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(tracks[position], showAlbumInMeta, onTrackClick)
    }

    override fun getItemCount(): Int = tracks.size

    class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val index: TextView = itemView.findViewById(R.id.text_detail_index)
        private val title: TextView = itemView.findViewById(R.id.text_detail_title)
        private val meta: TextView = itemView.findViewById(R.id.text_detail_meta)
        private val duration: TextView = itemView.findViewById(R.id.text_detail_duration)

        fun bind(track: LibraryTrack, showAlbumInMeta: Boolean, onTrackClick: (LibraryTrack) -> Unit) {
            index.text = if (track.trackNumber > 0) track.trackNumber.toString() else "•"
            title.text = track.title
            meta.text = if (showAlbumInMeta) {
                if (track.discNumber > 1) "CD ${track.discNumber} · ${track.album}" else track.album
            } else {
                if (track.discNumber > 1) "CD ${track.discNumber} · ${track.artist}" else track.artist
            }
            duration.text = track.durationMs.toTimeString()
            itemView.setOnClickListener { onTrackClick(track) }
        }
    }
}
