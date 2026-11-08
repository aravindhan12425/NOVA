package com.assistant.nova.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private fun p(s: String) = CommandParser.parse(s)
    private fun all(s: String) = CommandParser.parseAll(s)

    // The old parser replaced every letter "i" with "wi-fi"; none of these may toggle Wi-Fi.
    @Test fun `words containing i no longer toggle wifi`() {
        assertEquals(Command.SetFeature(Feature.FLASHLIGHT, OnOff.ON), p("turn on the flashlight"))
        assertEquals(Command.SetFeature(Feature.AUTO_ROTATE, OnOff.OFF), p("turn off rotation"))
        assertEquals(Command.SetFeature(Feature.AIRPLANE, OnOff.ON), p("airplane mode on"))
        assertEquals(Command.OpenApp("instagram"), p("open Instagram"))
        assertEquals(Command.Call("mike"), p("call Mike"))
        assertEquals(Command.OpenApp("settings"), p("open settings"))
    }

    @Test fun `feature toggles`() {
        assertEquals(Command.SetFeature(Feature.WIFI, OnOff.ON), p("Hey Jarvis turn on Wi-Fi"))
        assertEquals(Command.SetFeature(Feature.WIFI, OnOff.OFF), p("wifi off"))
        assertEquals(Command.SetFeature(Feature.WIFI, OnOff.OFF), p("switch off the wi fi please"))
        assertEquals(Command.SetFeature(Feature.BLUETOOTH, OnOff.ON), p("enable blue tooth"))
        assertEquals(Command.SetFeature(Feature.MOBILE_DATA, OnOff.OFF), p("turn off mobile data"))
        assertEquals(Command.SetFeature(Feature.MOBILE_DATA, OnOff.ON), p("net on"))
        assertEquals(Command.SetFeature(Feature.HOTSPOT, OnOff.ON), p("turn on hotspot"))
        assertEquals(Command.SetFeature(Feature.HOTSPOT, OnOff.OFF), p("turn off wifi hotspot"))
        assertEquals(Command.SetFeature(Feature.FLASHLIGHT, OnOff.OFF), p("torch off"))
        assertEquals(Command.SetFeature(Feature.FLASHLIGHT, OnOff.TOGGLE), p("flashlight"))
        assertEquals(Command.SetFeature(Feature.DND, OnOff.ON), p("turn on do not disturb"))
        assertEquals(Command.SetFeature(Feature.LOCATION, OnOff.OFF), p("turn off location"))
        assertEquals(Command.SetFeature(Feature.BATTERY_SAVER, OnOff.ON), p("turn on battery saver"))
        assertEquals(Command.SetFeature(Feature.DARK_MODE, OnOff.ON), p("enable dark mode"))
        assertEquals(Command.SetFeature(Feature.AIRPLANE, OnOff.OFF), p("turn off flight mode"))
    }

    @Test fun `several commands in one sentence`() {
        assertEquals(
            listOf(Command.SetFeature(Feature.WIFI, OnOff.ON), Command.SetFeature(Feature.BLUETOOTH, OnOff.ON)),
            all("turn on wifi and bluetooth"),
        )
        assertEquals(
            listOf(Command.SetFeature(Feature.WIFI, OnOff.OFF), Command.SetFeature(Feature.MOBILE_DATA, OnOff.ON)),
            all("turn off wifi and turn on mobile data"),
        )
        assertEquals(
            listOf(Command.SetFeature(Feature.FLASHLIGHT, OnOff.ON), Command.Volume(Adjust.UP)),
            all("torch on then volume up"),
        )
    }

    @Test fun `calls`() {
        assertEquals(Command.Call("mom"), p("call my mom"))
        assertEquals(Command.Call("aravind"), p("make a call to Aravind"))
        assertEquals(Command.Call("rahul"), p("call Rahul on mobile"))
        assertEquals(Command.Call("9876543210"), p("call 9876543210"))
        assertEquals(Command.Call("98775"), p("dial nine eight double seven five"))
        assertEquals(Command.Call(""), p("call"))
        assertEquals(Command.Call("dad and mom"), p("call dad and mom")) // never split names
    }

    @Test fun `messages`() {
        assertEquals(Command.Message("mom", "i am coming home", Channel.WHATSAPP), p("send a WhatsApp message to mom saying I am coming home"))
        assertEquals(Command.Message("mom", "bread and milk", Channel.WHATSAPP), p("message mom saying bread and milk"))
        assertEquals(Command.Message("dad", "im late", Channel.SMS), p("text dad that I'm late"))
        assertEquals(Command.Message("priya", "call me", Channel.SMS), p("send sms to Priya saying call me"))
        assertEquals(Command.Message("rahul", "hello", Channel.WHATSAPP), p("send message to Rahul on WhatsApp saying hello"))
        assertEquals(Command.Message("mom i am coming", null, Channel.WHATSAPP), p("whatsapp mom I am coming"))
        assertEquals(Command.Message("", null, Channel.WHATSAPP), p("WhatsApp"))
    }

    @Test fun `alarms and timers`() {
        assertEquals(Command.Alarm(6, 30), p("set an alarm for 6:30 am"))
        assertEquals(Command.Alarm(19, 0), p("set alarm at seven p m"))
        assertEquals(Command.Alarm(7, 15), p("wake me up at 7 15 in the morning"))
        assertEquals(Command.Alarm(21, 45), p("alarm 945 pm"))
        assertEquals(Command.Alarm(-1, -1), p("set an alarm"))
        assertEquals(Command.AlarmIn(20 * 60), p("set an alarm in 20 minutes"))
        assertEquals(Command.Timer(5 * 60), p("set a timer for five minutes"))
        assertEquals(Command.Timer(90 * 60), p("timer for 1 hour 30 minutes"))
        assertEquals(Command.Timer(30 * 60), p("set a timer for half an hour"))
        assertEquals(Command.Timer(0), p("start a timer"))
    }

    @Test fun `volume ringer brightness`() {
        assertEquals(Command.Volume(Adjust.UP), p("volume up"))
        assertEquals(Command.Volume(Adjust.DOWN), p("decrease the volume"))
        assertEquals(Command.Volume(Adjust.MUTE), p("mute"))
        assertEquals(Command.Volume(Adjust.MAX), p("volume full"))
        assertEquals(Command.Volume(Adjust.SET, AudioStream.MEDIA, 70), p("set volume to seven"))
        assertEquals(Command.Volume(Adjust.SET, AudioStream.MEDIA, 45), p("volume 45 percent"))
        assertEquals(Command.Volume(Adjust.UP, AudioStream.RING), p("increase ring volume"))
        assertEquals(Command.Volume(Adjust.DOWN, AudioStream.ALARM), p("lower the alarm volume"))
        assertEquals(Command.Ringer(RingerMode.SILENT), p("put my phone on silent"))
        assertEquals(Command.Ringer(RingerMode.VIBRATE), p("vibrate mode"))
        assertEquals(Command.Ringer(RingerMode.NORMAL), p("turn off silent mode"))
        assertEquals(Command.Brightness(Adjust.UP), p("increase brightness"))
        assertEquals(Command.Brightness(Adjust.DOWN), p("dim the screen"))
        assertEquals(Command.Brightness(Adjust.SET, 50), p("brightness 50 percent"))
        assertEquals(Command.Brightness(Adjust.MAX), p("brightness full"))
    }

    @Test fun `media navigation info`() {
        assertEquals(Command.Media(MediaAction.PAUSE), p("pause the music"))
        assertEquals(Command.Media(MediaAction.PLAY), p("play"))
        assertEquals(Command.Media(MediaAction.NEXT), p("next song"))
        assertEquals(Command.Media(MediaAction.PREVIOUS), p("previous track"))
        assertEquals(Command.Navigate(NavAction.LOCK), p("lock the phone"))
        assertEquals(Command.Navigate(NavAction.LOCK), p("lock"))
        assertEquals(Command.Navigate(NavAction.SCREENSHOT), p("take a screenshot"))
        assertEquals(Command.Navigate(NavAction.NOTIFICATIONS), p("open notifications"))
        assertEquals(Command.Navigate(NavAction.HOME), p("go home"))
        assertEquals(Command.Navigate(NavAction.BACK), p("go back"))
        assertEquals(Command.Info(InfoQuery.BATTERY), p("how much battery is left"))
        assertEquals(Command.Info(InfoQuery.TIME), p("what's the time"))
        assertEquals(Command.Info(InfoQuery.DATE), p("what is today's date"))
        assertEquals(Command.OpenApp("youtube"), p("open YouTube"))
        assertEquals(Command.OpenApp("play store"), p("launch the play store app"))
        assertEquals(Command.Help, p("what can you do"))
        assertEquals(Command.Cancel, p("never mind"))
        assertTrue(p("sing me a song about cats") is Command.Unknown || p("sing me a song about cats") is Command.Media)
        assertTrue(p("") is Command.Unknown)
    }
}
