package com.assistant.nova.core.wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Offline wake-word detector running the openWakeWord pipeline
 * (https://github.com/dscripka/openWakeWord) on ONNX Runtime:
 *
 *   16 kHz audio -> mel spectrogram (32 bins / 10 ms) -> speech embedding (96-d / 80 ms)
 *   -> one small classifier per wake word ("hey_jarvis", "hey_nova", …) -> score 0..1
 *
 * Battery: when the room has been quiet for 2 s the models stop running entirely and
 * only a loudness check runs on each 80 ms chunk. As soon as sound returns, the last
 * 2 s of audio are re-analysed in one go so a wake word spoken right after silence is
 * still caught.
 *
 * Pure JVM code (no Android classes) so it can be unit tested on a PC.
 * Not thread safe: call from one thread.
 */
class WakeWordEngine(
    melModel: ByteArray,
    embeddingModel: ByteArray,
    keywordModels: Map<String, ByteArray>,
) : AutoCloseable {

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 80 ms of audio: the step the models were trained on. */
        const val CHUNK = 1280
        private const val MEL_CONTEXT = 480 // 3 extra 10 ms hops so frames line up across chunks
        private const val MEL_BINS = 32
        private const val EMB_WINDOW = 76 // mel frames per embedding
        private const val EMB_STEP = 8 // mel frames per 80 ms chunk
        private const val EMB_DIM = 96
        private const val MAX_MEL_FRAMES = 400
        private const val RAW_KEEP = SAMPLE_RATE * 3
        private const val REBUILD_SAMPLES = SAMPLE_RATE * 2 + MEL_CONTEXT
        private const val QUIET_CHUNKS_BEFORE_SLEEP = 25 // 2 s
    }

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        // One thread: slower per call but far kinder to the battery than waking several cores
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val melSession = env.createSession(melModel, options)
    private val embSession = env.createSession(embeddingModel, options)
    private val melInput = melSession.inputNames.first()
    private val embInput = embSession.inputNames.first()

    private class Keyword(val name: String, val session: OrtSession, val input: String, val frames: Int)

    private val keywords: List<Keyword> = keywordModels.map { (name, bytes) ->
        val s = env.createSession(bytes, options)
        val input = s.inputNames.first()
        val shape = (s.inputInfo.getValue(input).info as TensorInfo).shape // [1, 16, 96]
        Keyword(name, s, input, shape.getOrNull(1)?.toInt()?.takeIf { it > 0 } ?: 16)
    }

    val keywordNames: List<String> get() = keywords.map { it.name }

    private val featuresNeeded = keywords.maxOfOrNull { it.frames } ?: 16

    private val raw = FloatArray(RAW_KEEP)
    private var rawCount = 0
    private var rawPos = 0
    private val pending = FloatArray(CHUNK)
    private var pendingCount = 0
    private val mel = ArrayList<FloatArray>()
    private val features = ArrayList<FloatArray>()
    private var quietChunks = 0
    private var sleeping = false

    /** Chunks quieter than this RMS (16-bit scale) count as silence. */
    var silenceRms = 90f

    /** When false the models run on every chunk (used by tests to measure raw accuracy). */
    var silenceGate = true

    /** True while the engine is skipping the models because the room is quiet. */
    val isSleeping: Boolean get() = sleeping

    /**
     * Feeds 16 kHz mono 16-bit audio of any length. Returns the highest score per wake
     * word produced while processing it (empty while asleep or still warming up).
     */
    fun process(samples: ShortArray, count: Int = samples.size): Map<String, Float> {
        val result = HashMap<String, Float>()
        var i = 0
        while (i < count) {
            val n = minOf(CHUNK - pendingCount, count - i)
            for (k in 0 until n) pending[pendingCount + k] = samples[i + k].toFloat()
            pendingCount += n
            i += n
            if (pendingCount == CHUNK) {
                pendingCount = 0
                processChunk(pending)?.forEach { (k, v) -> if (v > (result[k] ?: -1f)) result[k] = v }
            }
        }
        return result
    }

    /**
     * Forget all buffered audio, e.g. after a detection so the same words don't trigger twice.
     * The buffers are refilled with quiet background noise (as openWakeWord does), so a wake
     * word can be caught straight away instead of after ~2 s of warm-up.
     */
    fun reset() {
        rawCount = 0
        rawPos = 0
        pendingCount = 0
        mel.clear()
        features.clear()
        quietChunks = 0
        sleeping = false
        prime()
    }

    private val primeNoise: FloatArray by lazy {
        val r = java.util.Random(7)
        FloatArray(SAMPLE_RATE * 2) { (r.nextGaussian() * 30.0).toFloat() }
    }
    private val primeMel: List<FloatArray> by lazy { melspectrogram(primeNoise) }
    private val primeFeature: FloatArray by lazy {
        val saved = ArrayList(mel)
        mel.clear(); mel.addAll(primeMel)
        val f = embedding(mel.size)
        mel.clear(); mel.addAll(saved)
        f
    }

    private fun prime() {
        appendRaw(primeNoise.copyOfRange(primeNoise.size - CHUNK - MEL_CONTEXT, primeNoise.size))
        mel.addAll(primeMel.takeLast(EMB_WINDOW))
        repeat(featuresNeeded) { features.add(primeFeature) }
    }

    init {
        prime()
    }

    private fun processChunk(chunk: FloatArray): Map<String, Float>? {
        appendRaw(chunk)
        if (silenceGate) {
            val loud = rms(chunk) >= silenceRms
            quietChunks = if (loud) 0 else quietChunks + 1
            if (sleeping) {
                if (!loud) return null
                sleeping = false
                rebuildFromRaw()
                return score()
            }
            if (quietChunks >= QUIET_CHUNKS_BEFORE_SLEEP) {
                sleeping = true
                return null
            }
        }
        if (rawCount < CHUNK + MEL_CONTEXT) return null
        mel.addAll(melspectrogram(lastRaw(CHUNK + MEL_CONTEXT)))
        trimMel()
        if (mel.size >= EMB_WINDOW) {
            features.add(embedding(mel.size))
            trimFeatures()
        }
        return score()
    }

    /** Recomputes the mel frames and embeddings for the last 2 s in one pass (after silence). */
    private fun rebuildFromRaw() {
        mel.clear()
        features.clear()
        val n = minOf(rawCount, REBUILD_SAMPLES)
        if (n < CHUNK + MEL_CONTEXT) return
        mel.addAll(melspectrogram(lastRaw(n)))
        for (k in featuresNeeded - 1 downTo 0) {
            val end = mel.size - EMB_STEP * k
            if (end >= EMB_WINDOW) features.add(embedding(end))
        }
        trimMel()
        trimFeatures()
    }

    private fun score(): Map<String, Float> {
        val out = HashMap<String, Float>()
        for (kw in keywords) {
            if (features.size < kw.frames) continue
            val buf = FloatBuffer.allocate(kw.frames * EMB_DIM)
            for (f in features.size - kw.frames until features.size) buf.put(features[f])
            buf.rewind()
            OnnxTensor.createTensor(env, buf, longArrayOf(1, kw.frames.toLong(), EMB_DIM.toLong())).use { t ->
                kw.session.run(mapOf(kw.input to t)).use { r ->
                    out[kw.name] = (r[0] as OnnxTensor).floatBuffer.get(0)
                }
            }
        }
        return out
    }

    private fun melspectrogram(audio: FloatArray): List<FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())).use { t ->
            melSession.run(mapOf(melInput to t)).use { r ->
                val fb = (r[0] as OnnxTensor).floatBuffer
                val frames = fb.remaining() / MEL_BINS
                // Same scaling openWakeWord applies before the embedding model
                return List(frames) { f -> FloatArray(MEL_BINS) { b -> fb.get(f * MEL_BINS + b) / 10f + 2f } }
            }
        }
    }

    private fun embedding(endFrame: Int): FloatArray {
        val buf = FloatBuffer.allocate(EMB_WINDOW * MEL_BINS)
        for (f in endFrame - EMB_WINDOW until endFrame) buf.put(mel[f])
        buf.rewind()
        val shape = longArrayOf(1, EMB_WINDOW.toLong(), MEL_BINS.toLong(), 1)
        OnnxTensor.createTensor(env, buf, shape).use { t ->
            embSession.run(mapOf(embInput to t)).use { r ->
                val fb = (r[0] as OnnxTensor).floatBuffer
                return FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
    }

    private fun appendRaw(chunk: FloatArray) {
        for (v in chunk) {
            raw[rawPos] = v
            rawPos = (rawPos + 1) % RAW_KEEP
        }
        rawCount = minOf(rawCount + chunk.size, RAW_KEEP)
    }

    private fun lastRaw(n: Int): FloatArray =
        FloatArray(n) { i -> raw[(rawPos - n + i + RAW_KEEP) % RAW_KEEP] }

    private fun trimMel() {
        if (mel.size > MAX_MEL_FRAMES) mel.subList(0, mel.size - MAX_MEL_FRAMES).clear()
    }

    private fun trimFeatures() {
        val keep = featuresNeeded + 4
        if (features.size > keep) features.subList(0, features.size - keep).clear()
    }

    private fun rms(chunk: FloatArray): Float {
        var sum = 0.0
        for (v in chunk) sum += v * v
        return sqrt(sum / chunk.size).toFloat()
    }

    override fun close() {
        keywords.forEach { it.session.close() }
        melSession.close()
        embSession.close()
        options.close()
    }
}
