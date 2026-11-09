package com.assistant.nova

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.assistant.nova.services.NovaListenerService

/**
 * Debug builds only. From a PC:
 *   adb shell am broadcast -a com.assistant.nova.debug.COMMAND -p com.assistant.nova --es text "play party songs"
 * Runs the text as if it had been spoken (like the "Try" buttons).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: return
        NovaListenerService.runText(context, text)
    }
}
