package com.assistant.nova.core.command

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyParserTest {
    private fun p(s: String) = CommandParser.parse(s, "unlock")

    @Test fun `liked songs`() {
        for (s in listOf(
            "play songs from my liked list", "play my liked songs", "play liked songs on spotify",
            "play songs from liked songs folder remodified", "can you play my favourite songs",
        )) assertEquals(s, Command.Spotify(liked = true), p(s))
    }

    @Test fun `moods and hits`() {
        assertEquals(Command.Spotify("party hits"), p("im in party mood so play something so much energetic"))
        assertEquals(Command.Spotify("energetic hits"), p("play something energetic"))
        assertEquals(Command.Spotify("party hits"), p("play party songs"))
        assertEquals(Command.Spotify("Tamil party hits"), p("play tamil party songs"))
        assertEquals(Command.Spotify("Tamil love songs"), p("play some tamil love songs"))
        assertEquals(Command.Spotify("chill songs"), p("play something chill"))
        assertEquals(Command.Spotify("top hits"), p("play top hits from any language"))
        assertEquals(Command.Spotify("top tamil hits"), p("play top tamil hits"))
        assertEquals(Command.Spotify("anirudh hits"), p("play anirudh hits"))
    }

    @Test fun `named spotify`() {
        assertEquals(Command.Spotify("believer"), p("play believer on spotify"))
        assertEquals(Command.Spotify("anirudh songs"), p("play anirudh songs from spotify"))
        assertEquals(Command.Spotify(null), p("play music on spotify"))
    }

    @Test fun `phone music is untouched`() {
        assertEquals(Command.PlayMusic("a tamil"), p("play a tamil song"))
        assertEquals(Command.PlayMusic("believer"), p("play believer"))
        assertEquals(Command.PlayMusic("love me like you do"), p("play love me like you do"))
        assertEquals(Command.PlayMusic(folder = "english music"), p("play music from english music"))
    }
}
