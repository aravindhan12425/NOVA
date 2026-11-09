package com.assistant.nova.core.command

import com.assistant.nova.core.Routines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseOneTest {

    @Test fun `call answers`() {
        assertEquals(CallAnswer.Answer, CallAnswer.parse("answer"))
        assertEquals(CallAnswer.Answer, CallAnswer.parse("pick up"))
        assertEquals(CallAnswer.Answer, CallAnswer.parse("yes"))
        assertEquals(CallAnswer.Reject(null), CallAnswer.parse("reject"))
        assertEquals(CallAnswer.Reject(null), CallAnswer.parse("cut the call"))
        assertEquals(CallAnswer.Reject("I'll call back"), CallAnswer.parse("reject and text I'll call back"))
        assertEquals(CallAnswer.Reject("I'm in a meeting"), CallAnswer.parse("decline and say I'm in a meeting"))
        assertEquals(CallAnswer.Reject("I'm driving"), CallAnswer.parse("tell them I'm driving"))
        // Free text after "send": exactly what the user asked for
        assertEquals(CallAnswer.Reject("I'm busy"), CallAnswer.parse("reject and send I'm busy"))
        assertEquals(CallAnswer.Reject("I'm busy"), CallAnswer.parse("Reject and send I'm busy."))
        assertEquals(CallAnswer.Reject("I'll call you back in 10 minutes"), CallAnswer.parse("cut it and send I'll call you back in 10 minutes"))
        assertEquals(CallAnswer.Reject("Driving, will call later"), CallAnswer.parse("decline and send a message saying driving, will call later"))
        assertEquals(CallAnswer.Reject("I'm in class"), CallAnswer.parse("send I'm in class and reject"))
        assertEquals(CallAnswer.Reject("Call me after 6"), CallAnswer.parse("Hey Jarvis reject send call me after 6"))
        assertEquals(CallAnswer.Reject("Busy now, talk later"), CallAnswer.parse("reject and send busy now, talk later"))
        assertEquals(CallAnswer.Silence, CallAnswer.parse("silence"))
        assertNull(CallAnswer.parse("what's the weather"))
    }

    private val names = Routines.DEFAULTS.map { it.name }
    private fun p(s: String) = CommandParser.parse(s, "unlock", names)

    @Test fun `routines by phrase`() {
        assertEquals(Command.RunRoutine("good night"), p("Hey Jarvis good night"))
        assertEquals(Command.RunRoutine("good night"), p("goodnight"))
        assertEquals(Command.RunRoutine("good night"), p("start good night routine"))
        assertEquals(Command.RunRoutine("i'm driving"), p("I'm driving"))
        assertEquals(Command.RunRoutine("movie time"), p("movie time mode"))
        assertEquals(Command.RunRoutine("good morning"), p("good morning"))
        // Partial phrases and ordinary commands don't trigger routines
        assertTrue(p("night") !is Command.RunRoutine)
        assertEquals(Command.Info(InfoQuery.TIME), p("what's the time"))
        assertEquals(Command.SetFeature(Feature.WIFI, OnOff.OFF), p("turn off wifi"))
    }

    @Test fun `every default routine action is understood`() {
        for (r in Routines.DEFAULTS) for (a in r.actions) {
            assertTrue("“$a” in ${r.name}", CommandParser.parseAll(a).none { it is Command.Unknown })
        }
    }

    @Test fun `music folders and hands-free switch`() {
        assertEquals(Command.PlayMusic(folder = "english music"), p("play musics from files audio english music"))
        assertEquals(Command.PlayMusic(folder = "english music"), p("play music from Files-Audio-English music"))
        assertEquals(Command.PlayMusic(folder = "tamil"), p("play songs in my tamil folder"))
        assertEquals(Command.PlayMusic(folder = "english music"), p("play english music folder"))
        assertEquals(Command.PlayMusic(artist = "anirudh"), p("play songs by Anirudh"))
        assertEquals(Command.PlayMusic(), p("play my songs"))
        assertEquals(Command.SetHandsFree(OnOff.ON), p("hands free on"))
        assertEquals(Command.SetHandsFree(OnOff.OFF), p("Hey Jarvis turn off hands-free mode"))
        assertEquals(Command.SetHandsFree(OnOff.ON), p("listen without hey jarvis"))
    }

    @Test fun `camera`() {
        assertEquals(Command.Camera(CameraAction.PHOTO), p("take a photo"))
        assertEquals(Command.Camera(CameraAction.PHOTO), p("Hey Jarvis click a picture"))
        assertEquals(Command.Camera(CameraAction.SELFIE), p("take a selfie"))
        assertEquals(Command.Camera(CameraAction.SELFIE), p("selfie"))
        assertEquals(Command.Camera(CameraAction.VIDEO, 10), p("record a video for 10 seconds"))
        assertEquals(Command.Camera(CameraAction.VIDEO, 60), p("take a video for one minute"))
        assertEquals(Command.Camera(CameraAction.VIDEO), p("record a video"))
        // Call recording phrases are unchanged
        assertEquals(Command.CallControl(InCallAction.RECORD_START), p("record the call"))
    }

    @Test fun `call assistant switch`() {
        assertEquals(Command.SetCallAssistant(OnOff.ON), p("turn on call assistant"))
        assertEquals(Command.SetCallAssistant(OnOff.OFF), p("Hey Jarvis turn off call assistant"))
        assertEquals(Command.SetCallAssistant(OnOff.ON), p("call mode on"))
        assertEquals(Command.SetCallAssistant(OnOff.OFF), p("disable call announcements"))
        assertEquals(Command.SetCallAssistant(OnOff.TOGGLE), p("call assistant"))
        // Calling people is unaffected
        assertEquals(Command.Call("mom"), p("call mom"))
    }

    @Test fun `controlling a connected call`() {
        fun c(s: String) = (p(s) as Command.CallControl).action
        assertEquals(InCallAction.END, c("end the call"))
        assertEquals(InCallAction.END, c("Hey Jarvis cut the call"))
        assertEquals(InCallAction.END, c("hang up"))
        assertEquals(Command.CallControl(InCallAction.END, "I'm busy"), CommandParser.parse("end the call and send I'm busy"))
        assertEquals(Command.CallControl(InCallAction.END, "Talk later"), CommandParser.parse("Hey Jarvis cut the call and send talk later"))
        // Normal message commands are not affected
        assertEquals(Command.Message("dad", "im late", Channel.SMS), p("text dad that I'm late"))
        assertEquals(InCallAction.RECORD_START, c("record the call"))
        assertEquals(InCallAction.RECORD_START, c("start recording"))
        assertEquals(InCallAction.RECORD_STOP, c("stop recording"))
        assertEquals(InCallAction.RECORD_STOP, c("stop the call recording"))
        assertEquals(InCallAction.MUTE, c("mute the call"))
        assertEquals(InCallAction.MUTE, c("mute my mic"))
        assertEquals(InCallAction.UNMUTE, c("unmute"))
        assertEquals(InCallAction.SPEAKER_ON, c("speaker on"))
        assertEquals(InCallAction.SPEAKER_ON, c("put it on speaker"))
        assertEquals(InCallAction.SPEAKER_OFF, c("turn off the speaker"))
        assertEquals(InCallAction.HOLD, c("put the call on hold"))
        assertEquals(InCallAction.UNHOLD, c("resume the call"))
        // Plain "mute" is still the volume command (Nova turns it into mic mute only during a call)
        assertEquals(Command.Volume(Adjust.MUTE), p("mute"))
        // Only call commands (and harmless ones) are allowed during a call
        assertTrue(CommandParser.allowedDuringCall(p("end the call")))
        assertTrue(CommandParser.allowedDuringCall(p("what's the time")))
        assertTrue(!CommandParser.allowedDuringCall(p("call mom")))
        assertTrue(!CommandParser.allowedDuringCall(p("turn off wifi")))
        assertTrue(!CommandParser.allowedDuringCall(p("unlock 1 2 3 4")))
    }

    @Test fun `timer control`() {
        assertEquals(Command.TimerControl(TimerAction.STOP), p("turn off the timer"))
        assertEquals(Command.TimerControl(TimerAction.STOP), p("cancel the timer"))
        assertEquals(Command.TimerControl(TimerAction.STOP), p("stop timer"))
        assertEquals(Command.TimerControl(TimerAction.STOP), p("delete the 2 minute timer"))
        assertEquals(Command.TimerControl(TimerAction.PAUSE), p("pause the timer"))
        assertEquals(Command.TimerControl(TimerAction.RESUME), p("resume the timer"))
        assertEquals(Command.Timer(120), p("set a timer for 2 minutes"))
        assertEquals(Command.Timer(0), p("start a timer"))
    }

    @Test fun `a new instruction is never glued onto a question`() {
        // What actually happened on the phone: "cancel the timer" became "set a timer for turn off the timer"
        val r = { prefix: String, answer: String -> CommandParser.resolveFollowUp(prefix, answer, "unlock", names) }
        assertEquals("turn off the timer", r("set a timer for ", "turn off the timer"))
        assertEquals("just turn off the 2 minutes timer you said", r("set a timer for ", "just turn off the 2 minutes timer you said"))
        assertEquals("set a timer for five minutes", r("set a timer for ", "five minutes"))
        assertEquals("call mom", r("call ", "mom"))
        // Message text that happens to look like a command still goes into the message
        assertEquals("whatsapp Rahul saying call me back", r("whatsapp Rahul saying ", "call me back"))
        // A complete different command as the answer runs on its own
        assertEquals("what's the time", r("set a timer for ", "what's the time"))
    }

    @Test fun `follow-up answers complete the command`() {
        assertEquals(Command.Timer(300), p("set a timer for " + "five minutes"))
        assertEquals(Command.Alarm(6, 30), p("set an alarm for " + "6 30 am"))
        assertEquals(Command.Call("mom"), p("call " + "mom"))
        assertEquals(Command.Alarm(18, 0, "pay the bill"), p("remind me to pay the bill at " + "6 pm"))
        assertEquals(Command.Message("rahul", "on my way", Channel.WHATSAPP), p("whatsapp Rahul saying " + "on my way"))
        assertEquals(Command.Search("black clover"), p("search " + "black clover"))
        assertEquals(Command.AddNote("buy milk"), p("take a note " + "buy milk"))
    }
}
