package com.assistant.nova.core.wake

import android.content.Context
import com.assistant.nova.core.NovaPrefs

/**
 * Finds the wake-word models bundled in assets/wakeword. Any extra classifier dropped
 * in that folder is picked up automatically: e.g. a trained "hey_nova.onnx" (see README).
 */
object WakeWords {
    const val JARVIS = "hey_jarvis"
    const val NOVA = "hey_nova"

    private const val DIR = "wakeword"
    private const val MEL = "melspectrogram.onnx"
    private const val EMB = "embedding_model.onnx"

    fun label(id: String): String = when (id) {
        JARVIS -> "Hey Jarvis"
        NOVA -> "Hey Nova"
        else -> id.split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    /** id -> asset path for every wake word bundled in the app. */
    fun bundled(context: Context): Map<String, String> =
        context.assets.list(DIR).orEmpty()
            .filter { it.endsWith(".onnx") && it != MEL && it != EMB }
            .associate { idFor(it) to "$DIR/$it" }

    /** "hey_jarvis_v0.1.onnx" -> "hey_jarvis" */
    fun idFor(file: String): String =
        file.removeSuffix(".onnx").replace(Regex("_v\\d+(\\.\\d+)*$"), "")

    fun enabled(context: Context, prefs: NovaPrefs): Map<String, String> =
        bundled(context).filterKeys {
            when (it) {
                JARVIS -> prefs.wakeJarvis
                NOVA -> prefs.wakeNova
                else -> true
            }
        }

    fun load(context: Context, models: Map<String, String>): WakeWordEngine {
        fun read(path: String) = context.assets.open(path).use { it.readBytes() }
        return WakeWordEngine(
            read("$DIR/$MEL"),
            read("$DIR/$EMB"),
            models.mapValues { read(it.value) },
        )
    }
}
