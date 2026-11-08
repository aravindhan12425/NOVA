package com.assistant.nova.core.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.assistant.nova.core.wake.WakeWordEngine

/** 16 kHz mono 16-bit microphone, the format both the wake-word engine and Vosk expect. */
class Mic {
    @Volatile private var rec: AudioRecord? = null
    private var openedAt = 0L
    private var openMs = 0L

    /** Milliseconds the mic was open since the last call (for the battery report). */
    @Synchronized
    fun consumeOpenMs(): Long {
        val now = android.os.SystemClock.elapsedRealtime()
        if (rec != null) {
            openMs += now - openedAt; openedAt = now
        }
        return openMs.also { openMs = 0 }
    }

    val isOpen: Boolean get() = rec != null

    /** Audio session of Nova's own recording, to tell it apart from other apps' recordings. */
    val sessionId: Int get() = rec?.audioSessionId ?: -1

    @SuppressLint("MissingPermission") // checked before the service starts
    @Synchronized
    fun open(): Boolean {
        if (rec != null) return true
        val rate = WakeWordEngine.SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, WakeWordEngine.CHUNK * 2 * 4),
            )
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release(); return false
        }
        try {
            r.startRecording()
        } catch (e: Exception) {
            r.release(); return false
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release(); return false
        }
        rec = r
        openedAt = android.os.SystemClock.elapsedRealtime()
        return true
    }

    /** Blocking read. Returns samples read, or a negative number if the mic is closed/failed. */
    fun read(buf: ShortArray, count: Int = buf.size): Int = rec?.read(buf, 0, count) ?: -1

    @Synchronized
    fun close() {
        rec?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
            openMs += android.os.SystemClock.elapsedRealtime() - openedAt
        }
        rec = null
    }
}
