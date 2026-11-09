package com.assistant.nova.core.voiceid

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Voice ID: turns a few seconds of speech into a 256-number "voice print" with the
 * offline WeSpeaker ResNet34 model. Two prints from the same person point in a similar
 * direction (high [similarity]); different people score lower. Nothing leaves the phone,
 * and only the print is stored, never the recording. Pure JVM so it can be unit tested.
 */
class SpeakerEmbedder(model: ByteArray) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val session = env.createSession(model, options)
    private val input = session.inputNames.first()

    companion object {
        /** Less than this much speech isn't enough to recognise a voice. */
        const val MIN_SECONDS = 0.8

        fun similarity(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot // both are unit length
        }

        fun normalize(v: FloatArray): FloatArray {
            var n = 0.0
            for (x in v) n += x * x
            val len = sqrt(n).toFloat().coerceAtLeast(1e-9f)
            return FloatArray(v.size) { v[it] / len }
        }

        /** Combines several prints into one profile. */
        fun average(prints: List<FloatArray>): FloatArray {
            val sum = FloatArray(prints.first().size)
            for (p in prints) for (i in sum.indices) sum[i] += p[i]
            return normalize(sum)
        }
    }

    /** Voice print for 16 kHz mono 16-bit audio, or null if there's too little of it. */
    fun embed(samples: ShortArray, count: Int = samples.size): FloatArray? {
        if (count < Fbank.SAMPLE_RATE * MIN_SECONDS) return null
        val audio = FloatArray(count) { samples[it].toFloat() }
        val feats = Fbank.meanNormalize(Fbank.compute(audio))
        if (feats.isEmpty()) return null
        val buf = FloatBuffer.allocate(feats.size * Fbank.BINS)
        for (f in feats) buf.put(f)
        buf.rewind()
        OnnxTensor.createTensor(env, buf, longArrayOf(1, feats.size.toLong(), Fbank.BINS.toLong())).use { t ->
            session.run(mapOf(input to t)).use { r ->
                val fb = (r[0] as OnnxTensor).floatBuffer
                return normalize(FloatArray(fb.remaining()).also { fb.get(it) })
            }
        }
    }

    override fun close() {
        session.close()
        options.close()
    }
}
