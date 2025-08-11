package com.bodakesatish.ganeshaarties

// In MusicService.kt
import android.app.Notification
import android.app.PendingIntent
import android.content.Context // For NotificationManager
import android.content.Intent
import android.os.Build
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.ui.PlayerNotificationManager

private const val YOUR_NOTIFICATION_ID = 123
private const val YOUR_NOTIFICATION_CHANNEL_ID = "music_playback_channel"

@UnstableApi // For Media3 components that might still be unstable
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var playerNotificationManager: PlayerNotificationManager? = null

    // To manually track if we called startForeground
    private var isServiceInForeground = false

    override fun onCreate() {
        super.onCreate()
        isServiceInForeground = false // Initialize

        player = ExoPlayer.Builder(this).build().apply {
            // Configure player
            addListener(servicePlayerListener)
        }

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(packageManager?.getLaunchIntentForPackage(packageName)?.let { sessionIntent ->
                PendingIntent.getActivity(
                    this, 0, sessionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } as PendingIntent)
            .build()

        setupNotificationManager()
    }

    private fun setupNotificationManager() {
        createNotificationChannel()

        playerNotificationManager = PlayerNotificationManager.Builder(
            this,
            YOUR_NOTIFICATION_ID,
            YOUR_NOTIFICATION_CHANNEL_ID
        )
            .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: Player): CharSequence {
                    return player.mediaMetadata.title ?: "Unknown Title"
                }

                override fun createCurrentContentIntent(player: Player): PendingIntent? {
                    val intent = packageManager?.getLaunchIntentForPackage(packageName)?.apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    return if (intent != null) {
                        PendingIntent.getActivity(
                            this@MusicService, 0, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    } else {
                        null
                    }
                }

                override fun getCurrentContentText(player: Player): CharSequence? {
                    return player.mediaMetadata.artist ?: "Unknown Artist"
                }

                override fun getCurrentLargeIcon(
                    player: Player,
                    callback: PlayerNotificationManager.BitmapCallback
                ): android.graphics.Bitmap? {
                    return null // Load album art asynchronously if needed
                }
            })
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                override fun onNotificationPosted(
                    notificationId: Int,
                    notification: Notification,
                    ongoing: Boolean // True if playback is active and notification shouldn't be dismissed
                ) {
                    if (ongoing) {
                        // Playback is active, notification should be ongoing.
                        // Make the service a foreground service.
                        startForeground(notificationId, notification)
                        isServiceInForeground = true
                    } else {
                        // Playback is not active (e.g., paused), notification can be dismissed.
                        // If the service is in foreground, remove it from foreground
                        // but keep the notification (so it can be swiped away).
                        if (isServiceInForeground) {
                            stopForeground(false) // false = don't remove notification yet
                            isServiceInForeground = false
                        }
                    }
                }

                override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                    // Called when notification is dismissed (e.g., user swipes it away)
                    // or when stopForeground(true) is called.
                    if (dismissedByUser) {
                        stopSelf() // Stop the service if the user dismissed the notification
                    }
                    isServiceInForeground = false // No longer in foreground
                }
            })
            .build().apply {
                setPlayer(this@MusicService.player)
                setMediaSessionToken(this@MusicService.mediaSession?.sessionCompatToken!!)
                setUseNextActionInCompactView(true)
                setUsePreviousActionInCompactView(true)
                // setSmallIcon(R.drawable.ic_notification_icon) // Set your small icon
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    private val servicePlayerListener = object : Player.Listener {
        // Player listener callbacks (onIsPlayingChanged, onPlaybackStateChanged)
        // PlayerNotificationManager usually handles most updates based on these.
        // If you had manual notification updates here, you'd call:
        // val notification = buildMyNotification()
        // val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        // notificationManager.notify(YOUR_NOTIFICATION_ID, notification)
        // And then manage startForeground/stopForeground based on isPlaying.
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (player.isPlaying || player.playbackState != Player.STATE_IDLE) {
            player.stop()
        }
        player.clearMediaItems()
        stopSelf() // Stop the service
        isServiceInForeground = false
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.release()
        playerNotificationManager?.setPlayer(null)
        player.release()
        isServiceInForeground = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_description)
            val importance = android.app.NotificationManager.IMPORTANCE_LOW // Use LOW to prevent sound on notification
            val channel =
                android.app.NotificationChannel(YOUR_NOTIFICATION_CHANNEL_ID, name, importance).apply {
                    description = descriptionText
                }
            val notificationManager: android.app.NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    // This is part of MediaSessionService. PlayerNotificationManager's NotificationListener
    // is the more modern way to handle when startForeground should be called for Media3.
    // However, if you *must* implement it or are not solely relying on PlayerNotificationManager
    // for foreground decisions:
    /*
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // This callback from MediaSessionService tells you if the session thinks it needs to be foreground.
        if (startInForegroundRequired && !isServiceInForeground) {
            // You would typically get the notification from PlayerNotificationManager or build it
            // val notification = playerNotificationManager?.getNotification() ?: buildMyOwnNotification()
            // startForeground(YOUR_NOTIFICATION_ID, notification)
            // isServiceInForeground = true
        } else if (!startInForegroundRequired && isServiceInForeground) {
            // stopForeground(false)
            // isServiceInForeground = false
        }
    }
    */
}
