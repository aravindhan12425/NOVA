package com.assistant.nova.assist

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.assistant.nova.core.NovaPrefs
import com.assistant.nova.services.NovaListenerService

/**
 * Present when Nova is chosen as the phone's digital assistant (Settings → Apps →
 * Default apps → Digital assistant app). Android binds it at boot, which also lets Nova
 * resume 24/7 listening after a restart without the "tap to start" notification.
 */
class NovaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        val prefs = NovaPrefs(this)
        if (prefs.alwaysOn && !NovaListenerService.isRunning) NovaListenerService.start(this)
    }
}

class NovaSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = NovaSession(this)
}

/** Long-press power (or home): Nova starts listening straight away, no wake word, no screen. */
class NovaSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        NovaListenerService.start(context, NovaListenerService.ACTION_TALK)
        hide()
    }
}
