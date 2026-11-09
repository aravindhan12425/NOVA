package com.assistant.nova.core.command

import org.junit.Assert.assertEquals
import org.junit.Test

/** Talking to Nova politely and naturally, not only in short orders. */
class PoliteSpeechTest {
    private fun p(s: String) = CommandParser.parse(s, "unlock")

    @Test fun `polite requests`() {
        val wifiOn = Command.SetFeature(Feature.WIFI, OnOff.ON)
        for (s in listOf(
            "can you please switch on wifi", "would you mind turning wifi on", "would you mind turning on the wifi",
            "could you turn the wifi on for me", "would you be kind enough to turn on wifi", "i would like you to turn on wifi",
            "do me a favour and turn on wifi", "please could you switch on wifi", "is it possible to turn on wifi",
            "i was wondering if you could turn on wifi", "dei nova turn on wifi", "turn on wifi da", "turn on wifi if you dont mind",
        )) assertEquals("“$s”", wifiOn, p(s))
        assertEquals(Command.Call("amma"), p("would you mind calling amma"))
        assertEquals(Command.Call("krishna"), p("call krishna nova"))
        assertEquals(Command.Media(MediaAction.NEXT), p("would you mind skipping this song"))
        assertEquals(Command.Alarm(6, 30), p("i was wondering if you could set an alarm for 6 30 am"))
    }

    @Test fun `polite music`() {
        assertEquals(Command.PlayMusic("believer"), p("would you mind playing believer"))
        assertEquals(Command.PlayMusic("believer"), p("can you play believer again"))
        assertEquals(Command.PlayMusic("believer"), p("play believer one more time"))
        assertEquals(Command.Media(MediaAction.PREVIOUS), p("play it again"))
        assertEquals(Command.Media(MediaAction.PREVIOUS), p("can you play that song again"))
        assertEquals(Command.PlayMusic(folder = "english musics"), p("can you play music in files audio english musics"))
        assertEquals(Command.Media(MediaAction.PLAY), p("could you play some music"))
        assertEquals(Command.Media(MediaAction.PLAY), p("resume"))
        assertEquals(InCallAction.UNHOLD, (p("resume the call") as Command.CallControl).action)
    }

    @Test fun `messages keep their own last words`() {
        assertEquals(Command.Message("krishna", "see you da", Channel.WHATSAPP), p("message krishna saying see you da"))
        assertEquals(Command.Message("amma", "thank you", Channel.WHATSAPP), p("send a message to amma saying thank you"))
        assertEquals(Command.Message("krishna", "come fast bro", Channel.WHATSAPP), p("message krishna saying come fast bro"))
    }

    @Test fun `goodnight as one word is the routine`() {
        val routines = listOf("good night", "good morning")
        assertEquals(Command.RunRoutine("good night"), CommandParser.parse("goodnight", "unlock", routines))
        assertEquals(Command.RunRoutine("good morning"), CommandParser.parse("Goodmorning", "unlock", routines))
        assertEquals(Command.RunRoutine("good night"), CommandParser.parse("good night", "unlock", routines))
    }

    @Test fun `misheard everyday words are not contact names`() {
        // "call dad" misheard offline as "call that" once rang "G Thatha"
        for (s in listOf("call that", "call me", "call this", "call him", "call it")) {
            assertEquals("“$s”", Command.Call(""), p(s))
        }
        assertEquals(Command.Call("dad"), p("call dad"))
        assertEquals(Command.Call("thatha"), p("call thatha"))
    }

    @Test fun `small talk`() {
        assertEquals(Command.Chat("You're welcome!"), p("thank you nova"))
        assertEquals(Command.Chat("You're welcome!"), p("thanks"))
        assertEquals(Command.Chat("Good morning!"), p("good morning nova"))
        assertEquals("I'm Nova, your assistant.", (p("who are you") as Command.Chat).reply)
        // Still a real command when there's more to it
        assertEquals(Command.SetFeature(Feature.WIFI, OnOff.ON), p("turn on wifi thank you"))
    }
}
