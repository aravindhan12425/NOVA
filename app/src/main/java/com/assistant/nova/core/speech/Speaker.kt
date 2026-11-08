package com.assistant.nova.core.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.assistant.nova.core.NovaPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Nova's voice: the phone's text-to-speech, using the voice and speed chosen in
 * Settings, or else the best-quality English voice that works offline.
 */
class Speaker(context: Context) {

    companion object {
        /** Offline English voices on this phone, best first: Indian English, highest quality, fastest. */
        fun offlineVoices(tts: TextToSpeech): List<Voice> = try {
            tts.voices.orEmpty()
                .filter {
                    !it.isNetworkConnectionRequired && it.locale.language == "en" &&
                        !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                }
                .sortedWith(
                    compareByDescending<Voice> { it.locale.country == "IN" }
                        .thenByDescending { it.quality }
                        .thenBy { it.latency }
                        .thenBy { it.name },
                )
        } catch (_: Exception) {
            emptyList() // some engines throw from getVoices()
        }

        /** "English (India) · voice ENE · high quality" */
        fun label(v: Voice): String {
            val code = v.name.substringAfterLast("-x-", "").substringBefore('-').uppercase().ifEmpty { v.name }
            val quality = when {
                v.quality >= Voice.QUALITY_VERY_HIGH -> "very high quality"
                v.quality >= Voice.QUALITY_HIGH -> "high quality"
                else -> "normal quality"
            }
            return "${v.locale.getDisplayName(Locale.ENGLISH)} · voice $code · $quality"
        }

        /** Applies the user's voice and speed to [tts] (also used by the Settings "Test voice" button). */
        fun applyPrefs(tts: TextToSpeech, prefs: NovaPrefs) {
            tts.setSpeechRate(prefs.voiceRate)
            tts.setPitch(1.0f)
            val chosen = prefs.voiceName.takeIf { it.isNotEmpty() }
                ?.let { name -> runCatching { tts.voices?.firstOrNull { it.name == name } }.getOrNull() }
                ?: offlineVoices(tts).firstOrNull()
            if (chosen != null && tts.voice?.name != chosen.name) runCatching { tts.voice = chosen }
        }
    }

    private val prefs = NovaPrefs(context)
    private val ready = CompletableDeferred<Boolean>()
    private val waiting = ConcurrentHashMap<String, () -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            configure()
            ready.complete(true)
        } else {
            ready.complete(false)
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { waiting.remove(it)?.invoke() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { waiting.remove(it)?.invoke() } }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { waiting.remove(it)?.invoke() } }
        })
    }

    private fun configure() {
        val r = tts.setLanguage(Locale("en", "IN"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.getDefault())
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        applyPrefs(tts, prefs)
    }

    /** Speaks [text] and returns when finished (or after a safety timeout). */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        val ok = withTimeoutOrNull(3_000) { ready.await() } ?: false
        if (!ok) return
        applyPrefs(tts, prefs) // pick up changes made in Settings
        withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                val id = UUID.randomUUID().toString()
                waiting[id] = { if (cont.isActive) cont.resume(Unit) }
                val res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                if (res != TextToSpeech.SUCCESS) {
                    waiting.remove(id)
                    if (cont.isActive) cont.resume(Unit)
                }
                cont.invokeOnCancellation { tts.stop(); waiting.remove(id) }
            }
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
