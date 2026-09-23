package com.assistant.nova.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.assistant.nova.NovaApplication
import com.assistant.nova.R
import android.os.Bundle
import com.assistant.nova.utils.VoiceToTextParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class NovaForegroundService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var voiceParser: VoiceToTextParser
    private var isListening = false

    override fun onCreate() {
        super.onCreate()
        // Initialize parser with Application context
        voiceParser = VoiceToTextParser(application)
        // Register a listener to forward results via broadcast
        voiceParser.setRecognitionListener(object : VoiceToTextParser.RecognitionListenerAdapter() {
            override fun onResults(results: Bundle?) {
                val txt = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                sendBroadcast(Intent(ACTION_COMMAND).apply {
                    putExtra(EXTRA_TEXT, txt)
                })
                stopListening()
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start as foreground service
        startForeground(NOTIFICATION_ID, buildNotification())
        // No immediate listening; wait for user action via notification
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, NovaForegroundService::class.java).apply { action = ACTION_TAP_LISTEN },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, NovaApplication.CHANNEL_ID)
            .setContentTitle("Nova AI")
            .setContentText("Tap to speak a command")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .addAction(R.mipmap.ic_launcher, "Listen", tapIntent)
            .build()
    }

    private fun startListening() {
        if (isListening) return
        isListening = true
        voiceParser.startListening()
    }

    private fun stopListening() {
        if (!isListening) return
        isListening = false
        voiceParser.stopListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 101
        const val ACTION_TAP_LISTEN = "com.assistant.nova.ACTION_TAP_LISTEN"
        const val ACTION_COMMAND = "com.assistant.nova.ACTION_COMMAND"
        const val EXTRA_TEXT = "extra_text"
    }
}
