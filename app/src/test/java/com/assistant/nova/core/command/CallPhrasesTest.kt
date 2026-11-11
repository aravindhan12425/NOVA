package com.assistant.nova.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPhrasesTest {

    @Test fun `every phrase is a call command`() {
        for (p in CallPhrases.PHRASES) {
            val c = CommandParser.parse(p)
            assertTrue("“$p” -> $c", c is Command.CallControl)
        }
        for (p in listOf("end the call", "cut it", "call cut", "hang up", "disconnect", "cut the phone")) {
            assertEquals(p, InCallAction.END, (CommandParser.parse(p) as Command.CallControl).action)
        }
    }

    @Test fun `accepted when said on its own or after a couple of words`() {
        assertEquals("end the call", CallPhrases.accept("end the call"))
        assertEquals("cut the call", CallPhrases.accept("hey jarvis cut the call"))
        assertEquals("end the call", CallPhrases.accept("[unk] end the call"))
        assertEquals("speaker on", CallPhrases.accept("[unk] [unk] speaker on"))
        assertEquals("disconnect", CallPhrases.accept("disconnect"))
    }

    @Test fun `what was heard on a real call`() {
        // From the owner's call log: these were ignored before
        assertEquals("turn on the call recording", CallPhrases.accept("turn on the call recording"))
        assertEquals(InCallAction.RECORD_START, (CommandParser.parse("turn on the call recording") as Command.CallControl).action)
        assertEquals(InCallAction.RECORD_STOP, (CommandParser.parse("turn off the call recording") as Command.CallControl).action)
        assertEquals("finish the call", CallPhrases.accept("finish the call"))
        // Single words stay ignored: they're too easy to say while talking
        assertNull(CallPhrases.accept("recording"))
        assertNull(CallPhrases.accept("finish"))
        assertNull(CallPhrases.accept("nova"))
    }

    @Test fun `ignored when it is just conversation`() {
        assertNull(CallPhrases.accept(""))
        assertNull(CallPhrases.accept("[unk]"))
        assertNull(CallPhrases.accept("[unk] [unk] [unk]"))
        assertNull(CallPhrases.accept("end the call [unk]"))
        assertNull(CallPhrases.accept("[unk] disconnect")) // "it'll disconnect"
        assertNull(CallPhrases.accept("[unk] [unk] [unk] end the call"))
        assertNull(CallPhrases.accept("end the [unk] call"))
    }

    @Test fun `every word is in the offline model's vocabulary`() {
        // Vosk silently drops grammar words it doesn't know, which would break that phrase
        val fst = java.io.File("src/main/assets/model-en-in/graph/Gr.fst").readBytes()
        fun known(w: String): Boolean {
            val len = w.length
            val needle = byteArrayOf(len.toByte(), 0, 0, 0) + w.toByteArray()
            return (0..fst.size - needle.size).any { i -> needle.indices.all { fst[i + it] == needle[it] } }
        }
        val words = Regex("\"([^\"]+)\"").findAll(CallPhrases.grammarJson()).map { it.groupValues[1] }
            .filter { it != "[unk]" }.flatMap { it.split(' ') }.toSet()
        val missing = words.filterNot(::known)
        assertTrue("Not in the model: $missing", missing.isEmpty())
    }

    @Test fun `grammar is valid json`() {
        val g = CallPhrases.grammarJson()
        assertTrue(g.startsWith("[\"end the call\"") && g.endsWith("\"[unk]\"]"))
    }
}
