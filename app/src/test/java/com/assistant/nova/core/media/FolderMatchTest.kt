package com.assistant.nova.core.media

import com.assistant.nova.core.command.Command
import com.assistant.nova.core.command.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Finding the music folder the user names, however they describe where it is. */
class FolderMatchTest {
    // The folders on the user's phone (SD card)
    private val folders = listOf("Music", "ENGLISH MUSIC")

    /** What the parser makes of the sentence, then which folder that leads to. */
    private fun folderFor(sentence: String): String? {
        val c = CommandParser.parse(sentence, "unlock") as Command.PlayMusic
        return MusicLibrary.matchFolder(c.folder ?: c.query.orEmpty(), folders)
    }

    @Test fun `english music however it is said`() {
        for (s in listOf(
            "play music from audio in files in english musics",
            "play music from audio in files in english music's",
            "play music from files audio english musics",
            "play songs from audio files english music",
            "play music from files in audio in english music",
            "play music in english musics folder",
            "can you play music from the english music folder in files",
            "play music from fails audio english music",
            "could you play songs from english music",
            "play english musics",
            "play music from english songs",
        )) assertEquals("“$s”", "ENGLISH MUSIC", folderFor(s))
    }

    @Test fun `the plain music folder`() {
        assertEquals("Music", folderFor("play music from my music folder"))
        assertEquals("Music", folderFor("play songs from audio music"))
        assertEquals("Music", folderFor("play music from files audio music folder"))
    }

    @Test fun `no such folder`() {
        assertNull(MusicLibrary.matchFolder("tamil hits", folders))
        assertNull(MusicLibrary.matchFolder("in files in", folders))
    }
}
