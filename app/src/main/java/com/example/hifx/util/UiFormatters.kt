package com.example.hifx.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.example.hifx.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun Long.toTimeString(): String {
    val totalSeconds = (coerceAtLeast(0L) / 1000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

fun ImageView.loadArtworkOrDefault(uri: Uri?) {
    val requestKey = uri?.toString()
    val appliedKey = getTag(R.id.tag_artwork_applied_uri) as? String
    if (requestKey == appliedKey) {
        return
    }
    setTag(R.id.tag_artwork_request_uri, requestKey)

    if (uri == null) {
        setImageResource(R.drawable.ic_music_note)
        setTag(R.id.tag_artwork_applied_uri, null)
        return
    }

    if (requestKey != null) {
        ArtworkLoader.getBitmap(requestKey)?.let { cached ->
            setImageBitmap(cached)
            setTag(R.id.tag_artwork_applied_uri, requestKey)
            return
        }
        // Keep existing drawable until new artwork is decoded to avoid placeholder flicker.
        ArtworkLoader.decodeAsync(this, uri, requestKey)
    }
}

private object ArtworkLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    fun getBitmap(key: String): Bitmap? = cache.get(key)

    fun decodeAsync(imageView: ImageView, uri: Uri, requestKey: String) {
        val resolver = imageView.context.applicationContext.contentResolver
        scope.launch {
            val bitmap = runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }.getOrNull()
            if (bitmap != null) {
                cache.put(requestKey, bitmap)
            }
            mainHandler.post {
                val currentRequestKey = imageView.getTag(R.id.tag_artwork_request_uri) as? String
                if (currentRequestKey != requestKey) {
                    return@post
                }
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    imageView.setTag(R.id.tag_artwork_applied_uri, requestKey)
                } else if (imageView.drawable == null) {
                    imageView.setImageResource(R.drawable.ic_music_note)
                    imageView.setTag(R.id.tag_artwork_applied_uri, null)
                }
            }
        }
    }
}
