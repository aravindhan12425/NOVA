package com.assistant.nova.core.command

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchMusicDictionaryParserTest {

    private fun p(s: String) = CommandParser.parse(s, "unlock")

    @Test fun `web search`() {
        assertEquals(Command.Search("black clover the anime"), p("Can you give me a search result on Black Clover the anime"))
        assertEquals(Command.Search("black clover"), p("search Black Clover"))
        assertEquals(Command.Search("black clover", browser = "brave"), p("search black clover on Brave"))
        assertEquals(Command.Search("weather in chennai", browser = "chrome"), p("google weather in Chennai in chrome"))
        assertEquals(Command.Search("best anime 2026"), p("look up best anime 2026"))
        assertEquals(Command.Search("biryani recipe"), p("search the internet for biryani recipe"))
        assertEquals(Command.Search("naruto opening", SearchSite.YOUTUBE), p("search naruto opening on youtube"))
        assertEquals(Command.Search("lofi music", SearchSite.YOUTUBE), p("play lofi music on YouTube"))
        assertEquals(Command.Search("asta", SearchSite.YOUTUBE), p("search youtube for asta"))
    }

    @Test fun `word meanings`() {
        assertEquals(Command.Define("vilest"), p("Can you tell me the meaning of vilest"))
        assertEquals(Command.Define("vilest"), p("what is the meaning of vilest"))
        assertEquals(Command.Define("serendipity"), p("what does serendipity mean"))
        assertEquals(Command.Define("ephemeral"), p("define ephemeral"))
        assertEquals(Command.Define("ubiquitous"), p("meaning of the word ubiquitous"))
        assertEquals(Command.Define("vilest"), p("vilest meaning"))
        assertEquals(Command.Define("black hole"), p("what's the definition of black hole"))
    }

    @Test fun `play songs from the phone`() {
        assertEquals(Command.PlayMusic(), p("play my songs"))
        assertEquals(Command.PlayMusic(), p("play my downloaded music"))
        assertEquals(Command.PlayMusic(), p("play songs from my sd card"))
        assertEquals(Command.PlayMusic(), p("shuffle"))
        assertEquals(Command.PlayMusic("naruto opening"), p("Hey Jarvis play Naruto opening"))
        assertEquals(Command.PlayMusic("blue bird"), p("play the song blue bird"))
        assertEquals(Command.PlayMusic("blue bird", "ikimonogakari"), p("play blue bird by ikimonogakari"))
        assertEquals(Command.PlayMusic(artist = "anirudh"), p("play songs by Anirudh"))
    }

    @Test fun `identify a song playing nearby`() {
        for (s in listOf(
            "what's this song", "whats that song", "Hey Jarvis find the song", "find this song", "which song is this",
            "what song is this", "what song is playing", "what's that song called", "identify this song",
            "search this song", "shazam this", "song search", "what's playing", "recognise the music",
        )) {
            assertEquals("“$s”", Command.IdentifySong, p(s))
        }
        // Not song recognition
        assertEquals(Command.PlayMusic("blue bird"), p("play the song blue bird"))
        assertEquals(Command.Search("songs of 2024"), p("search songs of 2024"))
        assertEquals(Command.Media(MediaAction.PLAY), p("play the song"))
    }

    @Test fun `plain media buttons still work`() {
        assertEquals(Command.Media(MediaAction.PLAY), p("play"))
        assertEquals(Command.Media(MediaAction.PLAY), p("play music"))
        assertEquals(Command.Media(MediaAction.PLAY), p("play the movie"))
        assertEquals(Command.Media(MediaAction.NEXT), p("play next song"))
        assertEquals(Command.Media(MediaAction.PAUSE), p("Nova pause"))
        assertEquals(Command.Media(MediaAction.PAUSE), p("pause the movie"))
        assertEquals(Command.Media(MediaAction.PAUSE), p("stop the movie"))
        // "stop" alone is Cancel; the executor turns it into pause when something is playing
        assertEquals(Command.Cancel, p("Hey Jarvis stop"))
    }
}
