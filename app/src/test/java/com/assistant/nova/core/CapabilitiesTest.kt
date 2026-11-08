package com.assistant.nova.core

import com.assistant.nova.core.command.Command
import com.assistant.nova.core.command.CommandParser
import com.assistant.nova.core.command.NavAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the "What Nova can do" screen honest: every example shown must really work. */
class CapabilitiesTest {

    private val phrase = "unlock"
    private val routines: List<String> = Routines.DEFAULTS.map { it.name }
    private val items: List<Capability> = Capabilities.all(phrase).flatMap { g -> g.items }
    private val all: List<Example> = items.flatMap { c -> c.examples }

    @Test fun `every example is understood`() {
        // "answer" / "reject" are only understood while the phone is ringing (tested in PhaseOneTest)
        for (ex in items.filter { Requirement.CALLS !in it.requires }.flatMap { it.examples }) {
            if (ex.text == "Hey Jarvis") continue // just the wake word
            val cmds = CommandParser.parseAll(ex.text, phrase, routines)
            assertTrue("Nova doesn't understand “${ex.text}”: $cmds", cmds.none { it is Command.Unknown })
        }
    }

    @Test fun `try buttons never do anything risky`() {
        for (ex in all.filter { it.tryable }) {
            for (c in CommandParser.parseAll(ex.text, phrase, routines)) {
                val risky = c is Command.Call || c is Command.Message || c is Command.Unlock ||
                    c is Command.Alarm || c is Command.AlarmIn || c is Command.Timer ||
                    (c is Command.Navigate && c.action == NavAction.LOCK)
                assertFalse("“${ex.text}” must not have a Try button ($c)", risky)
            }
        }
    }
}
