package com.assistant.nova.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLibraryTest {
    @Test
    fun songFoldersAreKept() {
        assertTrue(MusicLibrary.isSongFolder("Music/"))
        assertTrue(MusicLibrary.isSongFolder("ENGLISH MUSIC/"))
        assertTrue(MusicLibrary.isSongFolder("Music/Tamil hits/"))
        assertTrue(MusicLibrary.isSongFolder(null))
    }

    @Test
    fun recordingsAndVoiceNotesAreSkipped() {
        assertFalse(MusicLibrary.isSongFolder("Music/Recordings/Standard Recordings/"))
        assertFalse(MusicLibrary.isSongFolder("Music/Recordings/Call Recordings/"))
        assertFalse(MusicLibrary.isSongFolder("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202605/"))
        assertFalse(MusicLibrary.isSongFolder("Recordings/"))
    }
}
