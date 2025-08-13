package com.bodakesatish.ganeshaarties

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.ui.PlayerNotificationManager

private const val PLAYBACK_NOTIFICATION_ID = 123
private const val PLAYBACK_NOTIFICATION_CHANNEL_ID = "music_playback_channel"

@UnstableApi
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var exoPlayer: ExoPlayer
    private var playerNotificationManager: PlayerNotificationManager? = null
    private var isServiceCurrentlyInForeground = false

    override fun onCreate() {
        super.onCreate()
        isServiceCurrentlyInForeground = false

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL) // Keep CPU awake during playback
            .build().apply {
                addListener(internalPlayerListener)
            }

        val sessionActivityPendingIntent = packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
            PendingIntent.getActivity(
                this, 0, sessionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivityPendingIntent!!)
            .build()

        initializeNotificationManager()
    }

    private fun initializeNotificationManager() {
        createNotificationChannelIfNeeded()

        playerNotificationManager = PlayerNotificationManager.Builder(
            this,
            PLAYBACK_NOTIFICATION_ID,
            PLAYBACK_NOTIFICATION_CHANNEL_ID
        )
            .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: Player): CharSequence {
                    // Title for notification - use MediaItem's metadata if available
                    // The MainActivity should be setting this when creating MediaItems
                    val mediaItemTitle = player.currentMediaItem?.mediaMetadata?.title
                    return mediaItemTitle ?: getString(R.string.no_aarti_selected)
                }

                override fun createCurrentContentIntent(player: Player): PendingIntent? {
                    // Intent to launch when notification is clicked
                    return packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
                        PendingIntent.getActivity(
                            this@MusicService, 0, sessionIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    }
                }

                override fun getCurrentContentText(player: Player): CharSequence? {
                    // Optional: Subtext for the notification
                    return null // Or player.currentMediaItem?.mediaMetadata?.artist for example
                }

                override fun getCurrentLargeIcon(
                    player: Player,
                    callback: PlayerNotificationManager.BitmapCallback
                ): Bitmap? {
                    return null
                }

                // Optional: You can provide a bitmap for the notification icon
                // override fun getCurrentLargeIcon(player: Player, callback: PlayerNotificationManager.BitmapCallback): Bitmap? = null
            })
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
                    if (ongoing && !isServiceCurrentlyInForeground) {
                        ContextCompat.startForegroundService(
                            applicationContext,
                            Intent(applicationContext, this@MusicService.javaClass)
                        )
                        startForeground(notificationId, notification)
                        isServiceCurrentlyInForeground = true
                    } else if (!ongoing && isServiceCurrentlyInForeground) {
                        stopForeground(STOP_FOREGROUND_REMOVE) // Or STOP_FOREGROUND_DETACH
                        isServiceCurrentlyInForeground = false
                    }
                }

                override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isServiceCurrentlyInForeground = false
                    stopSelf() // Stop the service if the notification is dismissed
                }
            })
            .setChannelNameResourceId(R.string.notification_channel_name) // Name for user settings
            .setChannelDescriptionResourceId(R.string.notification_channel_description) // Description for user settings
            .build().apply {
                setPlayer(exoPlayer)
                setMediaSessionToken(mediaSession?.sessionCompatToken!!)
                setUseNextActionInCompactView(true)
                setUsePreviousActionInCompactView(true)
                setUseStopAction(false) // Using pause/play instead of stop
                setPriority(NotificationCompat.PRIORITY_LOW) // For ongoing media
            }
    }


    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW // Low importance for media playback
            val channel = NotificationChannel(PLAYBACK_NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                // Configure other channel properties here if needed (e.g., sound, vibration)
                setSound(null, null) // No sound for the notification itself, only for playback
                enableVibration(false)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private val internalPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                if (isServiceCurrentlyInForeground) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isServiceCurrentlyInForeground = false
                }
                // Optionally stop the service if the playlist ends and nothing is playing
                // if (!exoPlayer.playWhenReady && exoPlayer.mediaItemCount == 0) {
                // stopSelf()
                // }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                // If paused and if configured, allow the service to be stopped if notification is swiped away
                // This is handled by onNotificationCancelled and the foreground service logic.
                // However, if not in foreground, and paused, consider stopping self after a delay.
                if (!isServiceCurrentlyInForeground) {
                    // stopSelf() // Consider with a delay if desired
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // This is where you manage the foreground service lifecycle based on playback.
            // PlayerNotificationManager handles a lot of this, but you can add custom logic.
            if (!isPlaying && exoPlayer.playbackState != Player.STATE_BUFFERING) {
                // Player is paused or stopped, and not just buffering
                if (isServiceCurrentlyInForeground) {
                    // If you want the service to stop immediately when paused and notification is removed,
                    // PlayerNotificationManager's onNotificationCancelled will handle stopSelf.
                    // If you want to keep it alive for a while after pausing:
                    // stopForeground(STOP_FOREGROUND_DETACH); // Detaches notification, service still runs
                    // isServiceInForeground = false;
                    // Then use a Handler to stopSelf() after a timeout if playback doesn't resume.
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!exoPlayer.isPlaying && !isServiceCurrentlyInForeground) {
            stopSelf() // Stop service if app is swiped away and not playing
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession?.run {
            player.release() // Release ExoPlayer
            release()       // Release MediaSession
            mediaSession = null
        }
        playerNotificationManager?.setPlayer(null) // Detach player from notification manager
    }
}