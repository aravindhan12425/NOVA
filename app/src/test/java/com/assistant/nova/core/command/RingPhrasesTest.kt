package com.assistant.nova.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingPhrasesTest {

    @Test fun `each phrase means the right thing`() {
        for (p in RingPhrases.ANSWER) assertEquals(p, CallAnswer.Answer, CallAnswer.parse(p))
        for (p in RingPhrases.REJECT + RingPhrases.REJECT_AND_SEND) assertEquals(p, CallAnswer.Reject(null), CallAnswer.parse(p))
        for (p in RingPhrases.SILENCE) assertEquals(p, CallAnswer.Silence, CallAnswer.parse(p))
    }

    @Test fun `every word is in the offline model's vocabulary`() {
        val fst = java.io.File("src/main/assets/model-en-in/graph/Gr.fst").readBytes()
        fun known(w: String): Boolean {
            val needle = byteArrayOf(w.length.toByte(), 0, 0, 0) + w.toByteArray()
            return (0..fst.size - needle.size).any { i -> needle.indices.all { fst[i + it] == needle[it] } }
        }
        val missing = (RingPhrases.ALL + "hey jarvis").flatMap { it.split(' ') }.toSet().filterNot(::known)
        assertTrue("Not in the model: $missing", missing.isEmpty())
    }

    @Test fun `heard over the ringtone`() {
        assertEquals("answer", RingPhrases.accept("[unk] answer"))
        assertEquals("pick up the call", RingPhrases.accept("[unk] [unk] pick up the call"))
        assertEquals("answer the call", RingPhrases.accept("hey jarvis answer the call"))
        assertNull(RingPhrases.accept("[unk]"))
        assertNull(RingPhrases.accept("answer [unk]"))
    }
}
