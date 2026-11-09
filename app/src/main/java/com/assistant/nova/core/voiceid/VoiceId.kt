package com.assistant.nova.core.voiceid

import android.content.Context

/**
 * The user's voice profile (only the 256-number voice print and a match threshold, never a
 * recording) plus the model to check new speech against it. Stored on the phone only.
 */
class VoiceId(private val context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("nova_voiceid", Context.MODE_PRIVATE)

    @Volatile private var embedder: SpeakerEmbedder? = null

    @Synchronized
    private fun model(): SpeakerEmbedder =
        embedder ?: SpeakerEmbedder(context.assets.open("voiceid/wespeaker_en_voxceleb_resnet34.onnx").use { it.readBytes() })
            .also { embedder = it }

    val isEnrolled: Boolean get() = prefs.contains("profile")

    companion object {
        const val MAX_THRESHOLD = 0.45f
    }

    /** Score needed to count as the user's voice; set from their own enrolment recordings. */
    // Enrolment recordings (quiet room, same distance) match each other far better than everyday
    // speech does, so the bar is capped at MAX. Measured on the owner's phone: everyday commands
    // score ~0.45-0.6 (short ones lowest); other people's voices usually stay under 0.3.
    var threshold: Float
        get() = prefs.getFloat("threshold", 0.5f).coerceAtMost(MAX_THRESHOLD)
        set(v) = prefs.edit().putFloat("threshold", v.coerceIn(0.3f, 0.8f)).apply()

    private fun profile(): FloatArray? =
        prefs.getString("profile", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()?.takeIf { it.size == 256 }

    /** Voice print of [audio] (16 kHz), or null if it's too short. Slow (~0.2 s): call off the main thread. */
    fun print(audio: ShortArray, count: Int = audio.size): FloatArray? = model().embed(audio, count)

    /**
     * Saves a profile from several enrolment prints. The threshold sits a safe margin below
     * how similar the user's own recordings were to each other.
     */
    fun enroll(prints: List<FloatArray>): Float {
        val profile = SpeakerEmbedder.average(prints)
        val own = prints.map { SpeakerEmbedder.similarity(profile, it) }
        val t = (own.average().toFloat() - 0.2f).coerceIn(0.4f, MAX_THRESHOLD)
        prefs.edit().putString("profile", profile.joinToString(",")).apply()
        threshold = t
        return t
    }

    /** How much [audio] sounds like the enrolled user (−1…1), or null if not enrolled / too short. */
    fun score(audio: ShortArray, count: Int = audio.size): Float? {
        val p = profile() ?: return null
        val e = print(audio, count) ?: return null
        return SpeakerEmbedder.similarity(p, e)
    }

    fun matches(audio: ShortArray, count: Int = audio.size): Boolean = (score(audio, count) ?: -1f) >= threshold

    fun delete() = prefs.edit().clear().apply()

    fun close() {
        embedder?.close()
        embedder = null
    }
}
