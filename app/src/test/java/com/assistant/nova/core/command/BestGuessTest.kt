package com.assistant.nova.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Choosing among the speech recognizer's guesses for one sentence. */
class BestGuessTest {

    @Test fun `top guess wins when it is a command`() {
        assertEquals("turn on wifi", CommandParser.bestOf(listOf("turn on wifi", "turn on why fight")))
    }

    @Test fun `a later guess is used when the top one is not a command`() {
        assertEquals(
            "end the call",
            CommandParser.bestOf(listOf("and the gall", "end the call", "and the call")),
        )
        assertEquals(
            "switch on bluetooth",
            CommandParser.bestOf(listOf("which on blue tune", "switch on bluetooth")),
        )
    }

    @Test fun `falls back to the top guess`() {
        assertEquals("blah blah", CommandParser.bestOf(listOf("blah blah", "bla bla")))
        assertEquals("hello world", CommandParser.bestOf(listOf("  ", "hello world")))
        assertNull(CommandParser.bestOf(emptyList()))
    }
}
