package com.alky.hifx.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.ViewGroup
import android.widget.ImageView
import com.alky.hifx.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

fun Long.toTimeString(): String {
    val totalSeconds = (coerceAtLeast(0L) / 1000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

fun ImageView.loadArtworkOrDefault(uri: Uri?) {
    if (uri == null) {
        setImageResource(R.drawable.ic_music_note)
        setTag(R.id.tag_artwork_applied_uri, null)
        setTag(R.id.tag_artwork_request_uri, null)
        return
    }

    val request = ArtworkLoader.buildRequest(this, uri)
    val appliedKey = getTag(R.id.tag_artwork_applied_uri) as? String
    val requestKey = getTag(R.id.tag_artwork_request_uri) as? String
    if (request.uriKey == appliedKey && request.cacheKey == requestKey) {
        return
    }
    setTag(R.id.tag_artwork_request_uri, request.cacheKey)

    ArtworkLoader.getBitmap(request.cacheKey)?.let { cached ->
        setImageBitmap(cached)
        setTag(R.id.tag_artwork_applied_uri, request.uriKey)
        return
    }
    // Keep existing drawable until new artwork is decoded to avoid placeholder flicker.
    ArtworkLoader.decodeAsync(this, request)
}

private data class ArtworkRequest(
    val uri: Uri,
    val uriKey: String,
    val cacheKey: String,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
    val preferredConfig: Bitmap.Config
)

private data class PendingTarget(
    val imageView: WeakReference<ImageView>,
    val expectedRequestKey: String
)

private object ArtworkLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(resolveCacheSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }
    private val inFlightRequests = LinkedHashMap<String, MutableList<PendingTarget>>()

    fun getBitmap(key: String): Bitmap? = cache.get(key)

    fun buildRequest(imageView: ImageView, uri: Uri): ArtworkRequest {
        val decodeMaxPx = (imageView.getTag(R.id.tag_artwork_decode_max_px) as? Int)
            ?.coerceIn(MIN_DECODE_DIMENSION_PX, MAX_DECODE_DIMENSION_PX)
            ?: MAX_DECODE_DIMENSION_PX
        val preferRgb565 = imageView.getTag(R.id.tag_artwork_decode_rgb565) == true
        val targetWidth = resolveTargetDimensionPx(
            primary = imageView.width,
            secondary = imageView.measuredWidth,
            layoutParam = imageView.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            fallback = imageView.resources.displayMetrics.widthPixels,
            maxDimensionPx = decodeMaxPx
        )
        val targetHeight = resolveTargetDimensionPx(
            primary = imageView.height,
            secondary = imageView.measuredHeight,
            layoutParam = imageView.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            fallback = imageView.resources.displayMetrics.heightPixels,
            maxDimensionPx = decodeMaxPx
        )
        val bucketWidth = bucketSizePx(targetWidth)
        val bucketHeight = bucketSizePx(targetHeight)
        val preferredConfig = if (preferRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        return ArtworkRequest(
            uri = uri,
            uriKey = uri.toString(),
            cacheKey = uri.toString() + "#" + bucketWidth + "x" + bucketHeight + "#" + preferredConfig.name,
            targetWidthPx = bucketWidth,
            targetHeightPx = bucketHeight,
            preferredConfig = preferredConfig
        )
    }

    fun decodeAsync(imageView: ImageView, request: ArtworkRequest) {
        val resolver = imageView.context.applicationContext.contentResolver
        val pendingTarget = PendingTarget(
            imageView = WeakReference(imageView),
            expectedRequestKey = request.cacheKey
        )
        val shouldLaunch = synchronized(inFlightRequests) {
            val waiters = inFlightRequests.getOrPut(request.cacheKey) { mutableListOf() }
            waiters += pendingTarget
            waiters.size == 1
        }
        if (!shouldLaunch) {
            return
        }
        scope.launch {
            val bitmap = runCatching {
                decodeSampledBitmap(
                    resolver = resolver,
                    request = request
                )
            }.getOrNull()
            if (bitmap != null) {
                cache.put(request.cacheKey, bitmap)
            }
            val targets = synchronized(inFlightRequests) {
                inFlightRequests.remove(request.cacheKey).orEmpty()
            }
            mainHandler.post {
                targets.forEach { target ->
                    val targetView = target.imageView.get() ?: return@forEach
                    val currentRequestKey = targetView.getTag(R.id.tag_artwork_request_uri) as? String
                    if (currentRequestKey != target.expectedRequestKey) {
                        return@forEach
                    }
                    if (bitmap != null) {
                        targetView.setImageBitmap(bitmap)
                        targetView.setTag(R.id.tag_artwork_applied_uri, request.uriKey)
                    } else if (targetView.drawable == null) {
                        targetView.setImageResource(R.drawable.ic_music_note)
                        targetView.setTag(R.id.tag_artwork_applied_uri, null)
                    }
                }
            }
        }
    }

    private fun decodeSampledBitmap(
        resolver: android.content.ContentResolver,
        request: ArtworkRequest
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        resolver.openInputStream(request.uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return resolver.openInputStream(request.uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                requestedWidth = request.targetWidthPx,
                requestedHeight = request.targetHeightPx
            )
            inPreferredConfig = request.preferredConfig
        }
        return resolver.openInputStream(request.uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
    }

    private fun calculateInSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        requestedWidth: Int,
        requestedHeight: Int
    ): Int {
        var sampleSize = 1
        if (sourceHeight <= requestedHeight && sourceWidth <= requestedWidth) {
            return sampleSize
        }
        while (
            sourceHeight / (sampleSize * 2) >= requestedHeight &&
            sourceWidth / (sampleSize * 2) >= requestedWidth
        ) {
            sampleSize *= 2
            if (sampleSize >= 32) {
                break
            }
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun resolveTargetDimensionPx(
        primary: Int,
        secondary: Int,
        layoutParam: Int,
        fallback: Int,
        maxDimensionPx: Int
    ): Int {
        return listOf(primary, secondary, layoutParam)
            .firstOrNull { it > 0 }
            ?.coerceAtMost(maxDimensionPx)
            ?: fallback.coerceIn(MIN_DECODE_DIMENSION_PX, maxDimensionPx)
    }

    private fun bucketSizePx(sizePx: Int): Int {
        val bounded = sizePx.coerceIn(MIN_DECODE_DIMENSION_PX, MAX_DECODE_DIMENSION_PX)
        return ((bounded + CACHE_BUCKET_STEP_PX - 1) / CACHE_BUCKET_STEP_PX) * CACHE_BUCKET_STEP_PX
    }

    private fun resolveCacheSizeBytes(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return (maxMemory / 8).coerceIn(8 * 1024 * 1024, 48 * 1024 * 1024)
    }
    private const val MIN_DECODE_DIMENSION_PX = 64
    private const val MAX_DECODE_DIMENSION_PX = 1440
    private const val CACHE_BUCKET_STEP_PX = 64
}
