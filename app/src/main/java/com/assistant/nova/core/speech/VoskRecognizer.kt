package com.assistant.nova.core.speech

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fully offline speech-to-text using the bundled Vosk Indian-English model
 * (assets/model-en-in). Used when there's no internet and the phone has no offline
 * Google speech pack, or when "Offline only" is chosen in settings.
 *
 * The model takes ~1 s to load and some memory, so it is unloaded after a few
 * minutes without use.
 */
class VoskRecognizer(private val context: Context, private val scope: CoroutineScope) {

    @Volatile private var model: Model? = null
    private val mutex = Mutex()
    private var unloadJob: Job? = null

    val isLoaded: Boolean get() = model != null

    suspend fun load(): Model = mutex.withLock {
        model?.let { return it }
        val m = suspendCancellableCoroutine<Model> { cont ->
            // First run copies the model out of the APK (~55 MB, a few seconds); later runs just load it
            StorageService.unpack(
                context.applicationContext, "model-en-in", "model",
                { loaded -> if (cont.isActive) cont.resume(loaded) },
                { e: IOException -> if (cont.isActive) cont.resumeWithException(e) },
            )
        }
        model = m
        m
    }

    /**
     * Listens on an already-open [mic] until the user finishes a sentence.
     * Gives up after [silenceStartMs] if nobody starts talking, or after [maxMs] in total.
     */
    /** A fresh streaming recognizer for the no-wake-word mode (caller closes it). */
    suspend fun newRecognizer(): Recognizer = Recognizer(load(), 16_000f).also {
        it.setMaxAlternatives(ALTERNATIVES)
        unloadJob?.cancel()
    }

    /** A recognizer that only knows the phrases in [grammarJson] (plus "[unk]"); caller closes it. */
    suspend fun newGrammarRecognizer(grammarJson: String): Recognizer =
        Recognizer(load(), 16_000f, grammarJson).also { unloadJob?.cancel() }

    /**
     * Recognises recorded [audio] again, allowing only the phrases in [grammarJson]
     * (plus "[unk]"). Used to re-check a contact name against the user's real contacts.
     */
    suspend fun recognizeWith(grammarJson: String, audio: ShortArray): List<String> {
        val m = load()
        return withContext(Dispatchers.Default) {
            Recognizer(m, 16_000f, grammarJson).use { rec ->
                val parts = ArrayList<String>()
                var i = 0
                while (i < audio.size) {
                    val n = minOf(1600, audio.size - i)
                    // A phrase Vosk finishes by itself must be read now, or it's lost
                    if (rec.acceptWaveForm(audio.copyOfRange(i, i + n), n)) parts += text(rec.result)
                    i += n
                }
                parts += text(rec.finalResult)
                listOf(parts.filter { it.isNotBlank() }.joinToString(" ")).filter { it.isNotBlank() }
            }
        }.also { scheduleUnload() }
    }

    /** Pull the (top) text out of a Vosk JSON result. */
    fun text(json: String, key: String = "text"): String = texts(json, key).firstOrNull().orEmpty()

    /** Every guess in a Vosk result, best first. Handles both plain and "alternatives" results. */
    fun texts(json: String, key: String = "text"): List<String> = try {
        val o = JSONObject(json)
        val alts = o.optJSONArray("alternatives")
        if (alts != null) {
            (0 until alts.length()).map { alts.getJSONObject(it).optString(key, "").trim() }.filter { it.isNotEmpty() }
        } else {
            listOfNotNull(o.optString(key, "").trim().takeIf { it.isNotEmpty() })
        }
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        /** Guesses per sentence; Nova keeps the first one that is a real command. */
        const val ALTERNATIVES = 4
    }

    suspend fun listen(
        mic: Mic,
        onPartial: (String) -> Unit,
        maxMs: Long = 8_000,
        silenceStartMs: Long = 4_500,
        /** Receives the raw audio as it's heard (used to check the speaker's voice). */
        capture: ((ShortArray, Int) -> Unit)? = null,
        /** Chooses among the guesses (best first). Default: the top guess. */
        pick: (List<String>) -> String? = { it.firstOrNull() },
        /**
         * Whether what's been heard so far is a whole command. While it isn't ("turn off…"),
         * a short pause doesn't end the listening: Nova waits [continueMs] for the rest.
         */
        isComplete: (String) -> Boolean = { true },
        continueMs: Long = 1_600,
    ): String {
        val m = load()
        unloadJob?.cancel()
        try {
            return withContext(Dispatchers.Default) {
                Recognizer(m, 16_000f).use { rec ->
                    rec.setMaxAlternatives(ALTERNATIVES)
                    val buf = ShortArray(1600) // 100 ms
                    val start = SystemClock.elapsedRealtime()
                    var heard = false
                    var text: String? = null
                    // Vosk ends a phrase at every ~0.5 s pause: "turn off" … "bluetooth" arrive as two
                    val parts = ArrayList<String>()
                    var lastSpeech = start
                    while (isActive) {
                        val n = mic.read(buf)
                        if (n <= 0) break
                        capture?.invoke(buf, n)
                        val now = SystemClock.elapsedRealtime()
                        if (rec.acceptWaveForm(buf, n)) {
                            val t = pick(texts(rec.result))
                            if (!t.isNullOrBlank()) {
                                parts += t
                                lastSpeech = now
                                val soFar = parts.joinToString(" ")
                                if (isComplete(soFar)) {
                                    text = soFar; break
                                }
                            }
                        } else {
                            val p = field(rec.partialResult, "partial")
                            if (p.isNotBlank()) {
                                heard = true
                                lastSpeech = now
                                onPartial((parts + p).joinToString(" "))
                            }
                        }
                        val elapsed = now - start
                        if (!heard && parts.isEmpty() && elapsed > silenceStartMs) break
                        // Part of a command heard, then nothing more for a while: that's all there is
                        if (parts.isNotEmpty() && now - lastSpeech > continueMs) break
                        if (elapsed > maxMs + if (parts.isNotEmpty()) 3_000 else 0) break
                    }
                    text ?: (parts + listOfNotNull(pick(texts(rec.finalResult))))
                        .filter { it.isNotBlank() }.joinToString(" ")
                }
            }
        } finally {
            scheduleUnload()
        }
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(3 * 60_000L)
            mutex.withLock {
                model?.close()
                model = null
            }
        }
    }

    fun close() {
        unloadJob?.cancel()
        model?.close()
        model = null
    }

    private fun field(json: String, key: String): String =
        try { JSONObject(json).optString(key, "") } catch (_: Exception) { "" }
}
