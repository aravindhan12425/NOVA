package com.assistant.nova

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.assistant.nova.core.speech.AndroidRecognizer
import com.assistant.nova.services.NovaListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only (adb holds the DUMP permission this needs; ordinary apps can't). From a PC:
 *   adb shell am broadcast -a com.assistant.nova.debug.COMMAND -p com.assistant.nova --es text "play party songs"
 *     runs the text as if it had been spoken (like the "Try" buttons)
 *   adb shell am broadcast -a com.assistant.nova.debug.COMMAND -p com.assistant.nova --es wav clip.wav
 *     only writes down a 16 kHz mono WAV from the app's files folder with the phone's recognizer
 *     (tests the recording re-check; nothing is run)
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra("cover")) {
            val svc = com.assistant.nova.services.NovaAccessibilityService.instance
            if (intent.getBooleanExtra("cover", false)) svc?.showPrivacyCurtain() else svc?.hidePrivacyCurtain()
            Log.i("NovaDebug", "Cover ${if (intent.getBooleanExtra("cover", false)) "shown" else "hidden"} (service ${svc != null})")
            return
        }
        if (intent.getBooleanExtra("support", false)) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    Log.i("NovaDebug", "Speech support: " + AndroidRecognizer(context).supportReport())
                } finally {
                    pending.finish()
                }
            }
            return
        }
        if (intent.getBooleanExtra("live", false)) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    val rec = AndroidRecognizer(context)
                    intent.getStringExtra("lang")?.let { rec.language = it }
                    intent.getStringExtra("pkg")?.let { rec.preferredPackage = it }
                    val t = android.os.SystemClock.elapsedRealtime()
                    val r = rec.listen(offline = true) {}
                    Log.i("NovaDebug", "Live offline (${rec.language}): $r after ${android.os.SystemClock.elapsedRealtime() - t} ms")
                } finally {
                    pending.finish()
                }
            }
            return
        }
        intent.getStringExtra("wav")?.let { name ->
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    val file = File(context.getExternalFilesDir(null), name)
                    val bytes = file.readBytes()
                    val pcm = ShortArray((bytes.size - 44) / 2) { i ->
                        ((bytes[44 + 2 * i].toInt() and 0xFF) or (bytes[45 + 2 * i].toInt() shl 8)).toShort()
                    }
                    val rec = AndroidRecognizer(context)
                    intent.getStringExtra("lang")?.let { rec.language = it }
                    intent.getStringExtra("pkg")?.let { rec.preferredPackage = it }
                    rec.clipsEnabled = true
                    if (intent.hasExtra("pace")) rec.feedDelayMs = intent.getIntExtra("pace", 25).toLong()
                    Log.i("NovaDebug", "Pack for ${rec.language}: ${rec.hasOnDevicePack()}")
                    val r = rec.transcribe(pcm, onlineAllowed = intent.getBooleanExtra("online", true))
                    Log.i("NovaDebug", "Transcribed $name: $r")
                } catch (e: Exception) {
                    Log.e("NovaDebug", "Transcribe failed", e)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        val text = intent.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: return
        NovaListenerService.runText(context, text)
    }
}
