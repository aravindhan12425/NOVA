package com.assistant.nova.core.voiceid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the real voice-ID model on two different voices (Windows TTS "David" and "Zira",
 * 6 different sentences each, in src/test/resources/voiceid).
 */
class SpeakerEmbedderTest {

    private val model = File("src/main/assets/voiceid/wespeaker_en_voxceleb_resnet34.onnx")
    private val dir = File("src/test/resources/voiceid")

    private fun readWav(f: File): ShortArray {
        val bytes = f.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "data") return ShortArray(minOf(size, bytes.size - pos - 8) / 2) { bb.getShort(pos + 8 + it * 2) }
            pos += 8 + size + (size and 1)
        }
        error("no data in $f")
    }

    @Test fun `filterbank has the expected shape`() {
        val oneSecond = FloatArray(16_000) { (kotlin.math.sin(it * 0.05) * 3000).toFloat() }
        val feats = Fbank.compute(oneSecond)
        assertEquals(98, feats.size) // 1 + (16000 - 400) / 160
        assertEquals(80, feats[0].size)
    }

    @Test fun `same voice matches, different voice does not`() {
        SpeakerEmbedder(model.readBytes()).use { e ->
            val david = (0..5).map { e.embed(readWav(File(dir, "david_$it.wav")))!! }
            val zira = (0..5).map { e.embed(readWav(File(dir, "zira_$it.wav")))!! }

            // Enrol with 3 sentences, test with the other 3 (like the real app)
            val davidProfile = SpeakerEmbedder.average(david.take(3))
            val ziraProfile = SpeakerEmbedder.average(zira.take(3))
            val sameD = david.drop(3).map { SpeakerEmbedder.similarity(davidProfile, it) }
            val sameZ = zira.drop(3).map { SpeakerEmbedder.similarity(ziraProfile, it) }
            val crossDZ = zira.drop(3).map { SpeakerEmbedder.similarity(davidProfile, it) }
            val crossZD = david.drop(3).map { SpeakerEmbedder.similarity(ziraProfile, it) }
            println("same voice:      David %s, Zira %s".format(sameD.fmt(), sameZ.fmt()))
            println("different voice: Zira vs David profile %s, David vs Zira profile %s".format(crossDZ.fmt(), crossZD.fmt()))

            val worstSame = (sameD + sameZ).min()
            val bestCross = (crossDZ + crossZD).max()
            assertTrue("same-voice score $worstSame should beat different-voice score $bestCross", worstSame > bestCross + 0.1f)
        }
    }

    @Test fun `too little audio gives no voice print`() {
        SpeakerEmbedder(model.readBytes()).use { e -> assertNull(e.embed(ShortArray(8_000))) }
    }

    private fun List<Float>.fmt() = joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }
}
