package com.assistant.nova.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.assistant.nova.MainActivity
import com.assistant.nova.R

object Notifications {
    const val CHANNEL_LISTENING = "nova_listening"
    const val CHANNEL_ALERTS = "nova_alerts"
    const val CHANNEL_MUSIC = "nova_music"
    const val ID_LISTENING = 101
    private const val ID_TAP_TO_START = 102
    private const val ID_MUSIC = 103

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENING, "Listening", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Nova is listening for the wake word"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MUSIC, "Music player", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Play, pause, next and previous for songs Nova plays, also on the lock screen"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Needs your attention", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "For example when Android stopped Nova and it needs a tap to start again"
            },
        )
    }

    private fun openApp(context: Context, startListening: Boolean = false): PendingIntent =
        PendingIntent.getActivity(
            context, if (startListening) 1 else 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_START_LISTENING, startListening),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun listening(context: Context, text: String): Notification {
        val talk = PendingIntent.getForegroundService(
            context, 10,
            Intent(context, NovaListenerService::class.java).setAction(NovaListenerService.ACTION_TALK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, 11,
            Intent(context, NovaListenerService::class.java).setAction(NovaListenerService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle("Nova")
            .setContentText(text)
            .setContentIntent(openApp(context))
            .addAction(0, "Talk now", talk)
            .addAction(0, "Stop listening", stop)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun update(context: Context, text: String) {
        if (!canPost(context)) return
        NotificationManagerCompat.from(context).notify(ID_LISTENING, listening(context, text))
    }

    private fun mediaAction(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context, requestCode,
            Intent(context, NovaListenerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Music controls for songs Nova plays: in the notification shade, and on the lock screen
     * (the media session makes Android show the full player there).
     */
    fun showMusic(context: Context, token: android.media.session.MediaSession.Token, title: String, artist: String, playing: Boolean) {
        if (!canPost(context)) return
        val playPause = if (playing) {
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_media_pause), "Pause",
                mediaAction(context, NovaListenerService.ACTION_MEDIA_PLAY_PAUSE, 21),
            ).build()
        } else {
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_media_play), "Play",
                mediaAction(context, NovaListenerService.ACTION_MEDIA_PLAY_PAUSE, 21),
            ).build()
        }
        val n = Notification.Builder(context, CHANNEL_MUSIC)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle(title)
            .setContentText(artist.ifBlank { "Nova" })
            .setSubText("Playing in Nova")
            .setContentIntent(openApp(context))
            .setDeleteIntent(mediaAction(context, NovaListenerService.ACTION_MEDIA_STOP, 24))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_media_previous), "Previous",
                    mediaAction(context, NovaListenerService.ACTION_MEDIA_PREVIOUS, 20),
                ).build(),
            )
            .addAction(playPause)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_media_next), "Next",
                    mediaAction(context, NovaListenerService.ACTION_MEDIA_NEXT, 22),
                ).build(),
            )
            .setStyle(Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 2))
            .build()
        NotificationManagerCompat.from(context).notify(ID_MUSIC, n)
    }

    fun cancelMusic(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_MUSIC)
    }

    /** Android didn't let Nova use the microphone from the background (e.g. right after a reboot). */
    fun showTapToStart(context: Context) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle("Nova isn't listening")
            .setContentText("Tap to start listening again")
            .setContentIntent(openApp(context, startListening = true))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_TAP_TO_START, n)
    }

    fun clearTapToStart(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_TAP_TO_START)
    }

    private fun canPost(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
}
