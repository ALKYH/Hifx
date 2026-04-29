package com.alky.hifx.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import com.alky.hifx.PlayerActivity
import com.alky.hifx.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AudioPlaybackService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationManager: NotificationManager
    private lateinit var mediaSession: MediaSessionCompat
    private var isForegroundStarted = false
    private var lastNotificationToken = ""
    private var lastArtworkUri: String? = null
    private var lastArtworkBitmap: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        AudioEngine.initialize(applicationContext)
        notificationManager = getSystemService(NotificationManager::class.java)
        setupMediaSession()
        createChannelIfNeeded()
        serviceScope.launch {
            AudioEngine.playbackState.collect { state ->
                renderNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Intent.ACTION_MEDIA_BUTTON == intent?.action) {
            MediaButtonReceiver.handleIntent(mediaSession, intent)
            return START_STICKY
        }
        when (intent?.action) {
            ACTION_PLAY -> AudioEngine.play()
            ACTION_PAUSE -> AudioEngine.pause()
            ACTION_TOGGLE_PLAY_PAUSE -> AudioEngine.togglePlayPause()
            ACTION_SKIP_PREVIOUS -> AudioEngine.skipToPreviousTrack()
            ACTION_SKIP_NEXT -> AudioEngine.skipToNextTrack()
            ACTION_SEEK_BACKWARD -> AudioEngine.seekBy(-10_000L)
            ACTION_SEEK_FORWARD -> AudioEngine.seekBy(10_000L)
            ACTION_STOP_PLAYBACK -> AudioEngine.clearCurrentTrack()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun renderNotification(state: PlaybackUiState) {
        updateMediaSessionState(state)
        if (!state.hasMedia) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            isForegroundStarted = false
            lastNotificationToken = ""
            return
        }

        val progressSecond = if (state.durationMs > 0L) state.positionMs / 1000L else -1L
        val token = "${state.title}|${state.subtitle}|${state.isPlaying}|$progressSecond|${state.durationMs / 1000L}"
        if (token == lastNotificationToken) {
            return
        }
        lastNotificationToken = token
        val artworkBitmap = resolveArtworkBitmap(state.artworkUri)

        val contentIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val previousIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        )
        val playPauseIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackStateCompat.ACTION_PLAY_PAUSE
        )
        val nextIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        )
        val stopIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackStateCompat.ACTION_STOP
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(state.title)
            .setContentText(state.subtitle)
            .setLargeIcon(artworkBitmap)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(state.isPlaying)
            .setDeleteIntent(stopIntent)
            .addAction(
                android.R.drawable.ic_media_previous,
                getString(R.string.action_previous_track),
                previousIntent
            )
            .addAction(
                if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (state.isPlaying) getString(R.string.action_pause) else getString(R.string.action_play),
                playPauseIntent
            )
            .addAction(
                android.R.drawable.ic_media_next,
                getString(R.string.action_next_track),
                nextIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_stop),
                stopIntent
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        if (state.durationMs > 0L) {
            builder.setProgress(state.durationMs.toInt(), state.positionMs.toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }

        val notification = builder.build()
        if (!isForegroundStarted) {
            startForeground(NOTIFICATION_ID, notification)
            isForegroundStarted = true
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun setupMediaSession() {
        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            20,
            Intent(this, PlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSessionCompat(this, "HiFXMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
            )
            setSessionActivity(sessionActivityIntent)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    AudioEngine.play()
                }

                override fun onPause() {
                    AudioEngine.pause()
                }

                override fun onStop() {
                    AudioEngine.clearCurrentTrack()
                }

                override fun onSeekTo(pos: Long) {
                    AudioEngine.seekTo(pos)
                }

                override fun onSkipToNext() {
                    AudioEngine.skipToNextTrack()
                }

                override fun onSkipToPrevious() {
                    AudioEngine.skipToPreviousTrack()
                }

                override fun onFastForward() {
                    AudioEngine.seekBy(10_000L)
                }

                override fun onRewind() {
                    AudioEngine.seekBy(-10_000L)
                }
            })
            isActive = true
        }
    }

    private fun updateMediaSessionState(state: PlaybackUiState) {
        val playbackState = when {
            !state.hasMedia -> PlaybackStateCompat.STATE_NONE
            state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }

        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_FAST_FORWARD or
            PlaybackStateCompat.ACTION_REWIND

        val position = if (state.durationMs > 0L) {
            state.positionMs.coerceIn(0L, state.durationMs)
        } else {
            PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        }

        val speed = if (state.isPlaying) 1f else 0f
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(playbackState, position, speed, SystemClock.elapsedRealtime())
                .build()
        )

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, state.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, state.subtitle)

        val artworkBitmap = resolveArtworkBitmap(state.artworkUri)
        if (artworkBitmap != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artworkBitmap)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artworkBitmap)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artworkBitmap)
        }

        if (state.durationMs > 0L) {
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
        }
        mediaSession.setMetadata(metadataBuilder.build())
        mediaSession.isActive = state.hasMedia
    }

    private fun resolveArtworkBitmap(artworkUri: android.net.Uri?): Bitmap? {
        val key = artworkUri?.toString()
        if (key == lastArtworkUri) {
            return lastArtworkBitmap
        }
        lastArtworkUri = key
        if (artworkUri == null) {
            lastArtworkBitmap = null
            return null
        }
        val bitmap = runCatching {
            contentResolver.openInputStream(artworkUri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
        lastArtworkBitmap = bitmap
        return bitmap
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_music),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_music_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "hifx_playback_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_TOGGLE_PLAY_PAUSE = "com.alky.hifx.action.TOGGLE_PLAY_PAUSE"
        const val ACTION_PLAY = "com.alky.hifx.action.PLAY"
        const val ACTION_PAUSE = "com.alky.hifx.action.PAUSE"
        const val ACTION_SKIP_PREVIOUS = "com.alky.hifx.action.SKIP_PREVIOUS"
        const val ACTION_SKIP_NEXT = "com.alky.hifx.action.SKIP_NEXT"
        const val ACTION_SEEK_BACKWARD = "com.alky.hifx.action.SEEK_BACKWARD"
        const val ACTION_SEEK_FORWARD = "com.alky.hifx.action.SEEK_FORWARD"
        const val ACTION_STOP_PLAYBACK = "com.alky.hifx.action.STOP_PLAYBACK"

        fun start(context: Context) {
            val intent = Intent(context, AudioPlaybackService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioPlaybackService::class.java))
        }
    }
}
