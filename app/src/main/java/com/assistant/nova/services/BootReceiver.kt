package com.assistant.nova.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.assistant.nova.core.NovaPrefs

/**
 * Resumes listening after a reboot or app update.
 *
 * Android 14+ doesn't let apps start the microphone from the background, so this may
 * be refused (the old version crashed here). Nova then shows a "Tap to start
 * listening" notification instead. When Nova's accessibility service is on, it also
 * tries again from there.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = NovaPrefs(context)
        if (!prefs.alwaysOn || !prefs.startOnBoot) return
        if (!NovaListenerService.start(context, NovaListenerService.ACTION_BOOT)) {
            Notifications.showTapToStart(context)
        }
    }
}
