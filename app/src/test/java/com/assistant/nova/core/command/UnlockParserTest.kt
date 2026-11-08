package com.assistant.nova.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockParserTest {

    private fun p(s: String, phrase: String = "unlock") = CommandParser.parse(s, phrase)

    @Test fun `default trigger with spoken digits`() {
        assertEquals(Command.Unlock("1234"), p("Hey Jarvis unlock one two three four"))
        assertEquals(Command.Unlock("1234"), p("unlock 1 2 3 4"))
        assertEquals(Command.Unlock("1234"), p("unlock 1234"))
        assertEquals(Command.Unlock("905511"), p("unlock nine zero double five one one"))
        assertEquals(Command.Unlock("2468"), p("unlock my pin is two four six eight"))
    }

    @Test fun `sound-alike words from the recognizer`() {
        assertEquals(Command.Unlock("4281"), p("unlock for to ate won"))
        assertEquals(Command.Unlock("1030"), p("unlock one oh three zero"))
    }

    @Test fun `custom trigger phrase`() {
        assertEquals(Command.Unlock("9987"), p("hey jarvis open sesame nine nine eight seven", "open sesame"))
        assertEquals(Command.Unlock("5555"), p("good morning boss 5 5 5 5", "good morning boss"))
        // Same words without digits after them are normal commands, not unlock attempts
        assertEquals(Command.OpenApp("sesame street"), p("open sesame street", "open sesame"))
    }

    @Test fun `trigger without a pin`() {
        assertEquals(Command.Unlock(""), p("unlock"))
        assertEquals(Command.Unlock(""), p("Hey Jarvis, unlock."))
    }

    @Test fun `other commands are unaffected`() {
        assertEquals(Command.Navigate(NavAction.LOCK), p("lock the phone"))
        assertEquals(Command.SetFeature(Feature.WIFI, OnOff.ON), p("turn on wifi"))
        assertEquals(Command.Call("mom"), p("call mom"))
        assertTrue(p("unlock the door please") !is Command.Unlock)
    }

    @Test fun `pin never shows up in text`() {
        assertFalse(Command.Unlock("1234").toString().contains("1234"))
        assertEquals("unlock • • • •", Redact.digits("unlock one two three four"))
        assertEquals("unlock ••••", Redact.digits("unlock 1234"))
        assertEquals("unlock • • • •", Redact.digits("unlock for to ate won", aggressive = true))
        assertEquals("go to settings", Redact.digits("go to settings"))
    }
}
