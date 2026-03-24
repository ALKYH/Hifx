package com.example.hifx.util

import android.net.Uri
import android.widget.ImageView
import com.example.hifx.R

fun Long.toTimeString(): String {
    val totalSeconds = (coerceAtLeast(0L) / 1000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

fun ImageView.loadArtworkOrDefault(uri: Uri?) {
    if (uri == null) {
        setImageResource(R.drawable.ic_music_note)
        return
    }
    runCatching {
        setImageURI(uri)
    }.onFailure {
        setImageResource(R.drawable.ic_music_note)
    }
    if (drawable == null) {
        setImageResource(R.drawable.ic_music_note)
    }
}
