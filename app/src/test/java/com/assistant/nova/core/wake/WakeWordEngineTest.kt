package com.assistant.nova.core.wake

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Runs the real openWakeWord models on recordings made with Windows text-to-speech
 * (src/test/resources/audio): "Hey Jarvis" must trigger, ordinary sentences must not.
 */
class WakeWordEngineTest {

    private val assets = File("src/main/assets/wakeword")
    private val audioDir = File("src/test/resources/audio")
    private val threshold = 0.5f

    private fun engine() = WakeWordEngine(
        File(assets, "melspectrogram.onnx").readBytes(),
        File(assets, "embedding_model.onnx").readBytes(),
        mapOf("hey_jarvis" to File(assets, "hey_jarvis_v0.1.onnx").readBytes()),
    )

    /** Reads the PCM samples from a 16 kHz mono 16-bit WAV file. */
    private fun readWav(f: File): ShortArray {
        val bytes = f.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "data") {
                val n = minOf(size, bytes.size - pos - 8) / 2
                return ShortArray(n) { bb.getShort(pos + 8 + it * 2) }
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in $f")
    }

    /** Adds quiet room noise so the silence gate sees realistic input. */
    private fun withNoise(s: ShortArray, level: Int, seed: Int) = Random(seed).let { r ->
        ShortArray(s.size) { (s[it] + r.nextInt(-level, level + 1)).coerceIn(-32768, 32767).toShort() }
    }

    private fun maxScore(e: WakeWordEngine, audio: ShortArray): Float {
        e.reset()
        var max = 0f
        var i = 0
        while (i < audio.size) { // feed in mic-sized reads
            val n = minOf(WakeWordEngine.CHUNK, audio.size - i)
            val res = e.process(audio.copyOfRange(i, i + n))
            res["hey_jarvis"]?.let { if (it > max) max = it }
            i += n
        }
        return max
    }

    private fun files(prefix: String) =
        audioDir.listFiles { f -> f.name.startsWith(prefix) && f.extension == "wav" }!!.sortedBy { it.name }

    @Test fun `hey jarvis triggers and other speech does not`() {
        engine().use { e ->
            for (gate in listOf(false, true)) {
                e.silenceGate = gate
                val pos = files("pos_").map { it.name to maxScore(e, readWav(it)) }
                val neg = files("neg_").map { it.name to maxScore(e, readWav(it)) }
                println("gate=$gate positives: ${pos.joinToString { "%s=%.2f".format(it.first, it.second) }}")
                println("gate=$gate negatives: ${neg.joinToString { "%s=%.2f".format(it.first, it.second) }}")
                val hits = pos.count { it.second >= threshold }
                assertTrue("gate=$gate: only $hits/${pos.size} 'Hey Jarvis' clips detected", hits >= pos.size - 1)
                neg.forEach { (name, s) -> assertTrue("gate=$gate: false trigger on $name ($s)", s < threshold) }
            }
        }
    }

    @Test fun `no warm-up needed right after a command`() {
        engine().use { e ->
            for (f in files("pos_")) {
                val clip = readWav(f)
                // Keep only the words plus 0.2 s before and 0.3 s after
                val first = clip.indexOfFirst { kotlin.math.abs(it.toInt()) > 500 }
                val last = clip.indexOfLast { kotlin.math.abs(it.toInt()) > 500 }
                val start = (first - WakeWordEngine.SAMPLE_RATE / 5).coerceAtLeast(0)
                val end = (last + WakeWordEngine.SAMPLE_RATE * 3 / 10).coerceAtMost(clip.size)
                val tight = withNoise(clip.copyOfRange(start, end), level = 40, seed = 3)
                val s = maxScore(e, tight) // maxScore() resets the engine first, like after a command
                assertTrue("${f.name}: wake word right after reset scored only $s", s >= threshold)
            }
        }
    }

    @Test fun `works with background noise and after long silence`() {
        engine().use { e ->
            val silence = ShortArray(WakeWordEngine.SAMPLE_RATE * 5) // 5 s: engine goes to sleep
            val clip = readWav(files("pos_").first())
            val audio = withNoise(silence + clip, level = 40, seed = 1)
            val s = maxScore(e, audio)
            println("after silence score=$s")
            assertTrue("missed wake word after silence ($s)", s >= threshold)

            val noisy = withNoise(clip, level = 400, seed = 2)
            val s2 = maxScore(e, noisy)
            println("noisy score=$s2")
            assertTrue("missed wake word in noise ($s2)", s2 >= threshold)
        }
    }
}
