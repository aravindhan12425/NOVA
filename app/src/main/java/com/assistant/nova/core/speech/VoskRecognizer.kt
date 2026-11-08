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
    suspend fun listen(
        mic: Mic,
        onPartial: (String) -> Unit,
        maxMs: Long = 8_000,
        silenceStartMs: Long = 4_500,
    ): String {
        val m = load()
        unloadJob?.cancel()
        try {
            return withContext(Dispatchers.Default) {
                Recognizer(m, 16_000f).use { rec ->
                    val buf = ShortArray(1600) // 100 ms
                    val start = SystemClock.elapsedRealtime()
                    var heard = false
                    var text: String? = null
                    while (isActive) {
                        val n = mic.read(buf)
                        if (n <= 0) break
                        if (rec.acceptWaveForm(buf, n)) {
                            val t = field(rec.result, "text")
                            if (t.isNotBlank()) {
                                text = t; break
                            }
                        } else {
                            val p = field(rec.partialResult, "partial")
                            if (p.isNotBlank()) {
                                heard = true; onPartial(p)
                            }
                        }
                        val elapsed = SystemClock.elapsedRealtime() - start
                        if (!heard && elapsed > silenceStartMs) break
                        if (elapsed > maxMs) break
                    }
                    text ?: field(rec.finalResult, "text")
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
