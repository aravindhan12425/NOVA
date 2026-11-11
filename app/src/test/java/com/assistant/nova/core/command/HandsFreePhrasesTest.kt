package com.assistant.nova.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreePhrasesTest {

    @Test fun `every phrase is a command Nova understands`() {
        for (p in HandsFreePhrases.FIXED) {
            val c = CommandParser.parseAll(p, "unlock")
            assertTrue("“$p” -> $c", c.none { it is Command.Unknown })
        }
        assertEquals(Command.SetFeature(Feature.WIFI, OnOff.ON), CommandParser.parse("turn on why fi"))
        assertEquals(Command.SetFeature(Feature.BLUETOOTH, OnOff.OFF), CommandParser.parse("switch off the blue tooth"))
        assertEquals(Command.SetFeature(Feature.FLASHLIGHT, OnOff.ON), CommandParser.parse("turn on the flashlight"))
    }

    @Test fun `every word is in the offline model's vocabulary`() {
        val fst = java.io.File("src/main/assets/model-en-in/graph/Gr.fst").readBytes()
        fun known(w: String): Boolean {
            val needle = byteArrayOf(w.length.toByte(), 0, 0, 0) + w.toByteArray()
            return (0..fst.size - needle.size).any { i -> needle.indices.all { fst[i + it] == needle[it] } }
        }
        val words = (HandsFreePhrases.FIXED + listOf("hey jarvis", "hey nova", "please"))
            .flatMap { it.split(' ') }.toSet()
        val missing = words.filterNot(::known)
        assertTrue("Not in the model: $missing", missing.isEmpty())
    }

    @Test fun `accepting what was heard`() {
        assertEquals("turn on the flashlight", HandsFreePhrases.accept("turn on the flashlight"))
        assertEquals("turn on the flashlight", HandsFreePhrases.accept("[unk] turn on the flashlight"))
        assertEquals("next song", HandsFreePhrases.accept("hey jarvis next song"))
        assertEquals("good night", HandsFreePhrases.accept("good night", listOf("Good night")))
        assertNull(HandsFreePhrases.accept("[unk]"))
        assertNull(HandsFreePhrases.accept("[unk] [unk] turn on the flashlight"))
        assertNull(HandsFreePhrases.accept("turn on the flashlight [unk]"))
        assertNull(HandsFreePhrases.accept("[unk] pause")) // "...on pause" in a sentence
        assertNull(HandsFreePhrases.accept("good night")) // not a routine here
    }
}
