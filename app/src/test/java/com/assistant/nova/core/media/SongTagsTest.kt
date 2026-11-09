package com.assistant.nova.core.media

import com.assistant.nova.core.command.Adjust
import com.assistant.nova.core.command.AudioStream
import com.assistant.nova.core.command.Command
import com.assistant.nova.core.command.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Song details below are real ones from the owner's phone (title, artist, album). */
class SongTagsTest {
    private fun tags(title: String, artist: String = "", album: String = title, folder: String = "Music") =
        SongTags.of(title, artist, album, folder)

    @Test fun `tamil songs`() {
        for (t in listOf(
            "#Railin Oligal #இரயிலின் ஒலிகள்# WhatsApp status|Ashok|Keerthi|Happy Married Life|Govind vasanth",
            "Chilla Chilla - Thunivu Song (Tamil) | Ajith Kumar | H Vinoth | Anirudh | Ghibran",
            "LEO - Naa Ready | Thalapathy Vijay | Lokesh Kanagaraj | Anirudh Ravichander",
            "VIKRAM - Porkanda Singam | Kamal Haasan | Vijay Sethupathi | Lokesh Kanagaraj | Anirudh",
            "Ponni Nadhi - | PS1 Tamil | Mani Ratnam | AR Rahman | Karthi | Ponniyin Selvan Part-1",
            "3 - Idhazhin Oram Video | Dhanush, Shruti | Anirudh",
            "Mayakkiriye - | Mugen Rao | Aathmika | Anirudh Ravichander | AniVee | Jimmyrudh",
            "Railin Oligal | Blue Star | Ashok Selvan, Keerthi | Govind Vasantha | S.Jaya Kumar | Pa.Ranjith",
            "KGF Chapter 2 Songs Jukebox (Tamil) _ Rocking Star Yash _ Prashanth Neel _ Ravi",
            "Jawan: Vandha Edam Song |Shah Rukh Khan |Atlee |Anirudh |Nayanthara |Vijay Sethupathi |Deepika",
        )) assertTrue(t, SongTags.TAMIL in tags(t))
        assertTrue(SongTags.TAMIL in tags("Anbae Peranbae :: isaiminida.net", "Sid Sriram, Shreya Ghoshal", "NGK"))
        assertTrue(SongTags.TAMIL in tags("Surviva - MassTamilan.com", "Anirudh Ravichander, Yogi B", "Vivegam - MassTamilan.com"))
    }

    @Test fun `other indian languages`() {
        assertEquals(SongTags.HINDI, tags("Jawan: Chaleya (Hindi) | Shah Rukh Khan | Nayanthara | Atlee | Anirudh | Arijit S").first())
        assertTrue(SongTags.HINDI in tags("Dheere Dheere Se Meri Zindagi Video Song (OFFICIAL) Hrithik Roshan, Sonam Kapoor | Yo Yo Honey Singh"))
        assertTrue(SongTags.HINDI in tags("Nadiyon Paar - Full Song - Roohi | Janhvi Kapoor| Sachin-Jigar | Shamur | Rashmeet K"))
        assertTrue(SongTags.TELUGU in tags("Buttabomma - MassTamilan.io", "Armaan Malik - MassTamilan.io", "Ala Vaikunthapurramuloo - MassTamilan.io"))
        assertTrue(SongTags.MALAYALAM in tags("Kudukku | Love Action Drama Song| Nivin Pauly,Nayanthara|Vineeth Sreenivasan|Shaan Rahman"))
        assertTrue(SongTags.PUNJABI in tags("Shubh - No Love | Eda Ni Chlde Pyar Sohniye | New Punjabi Song 2022"))
    }

    @Test fun `english and anime`() {
        assertEquals(setOf(SongTags.ENGLISH), tags("Bleeding Out", "Imagine Dragons", "Night Visions"))
        assertEquals(setOf(SongTags.ENGLISH), tags("Ashes", "Céline Dion", "Deadpool 2"))
        assertEquals(setOf(SongTags.ENGLISH), tags("Cruel Summer", "Taylor Swift", "Lover", "ENGLISH MUSIC"))
        // Not a Tamil song, even though "Tamally" sounds like it
        assertEquals(setOf(SongTags.ENGLISH), tags("Callin' U (Tamally Maak)", "Elyanna", "Callin' U"))
        assertEquals(setOf(SongTags.ANIME), tags("Black Clover Opening 1 Full『Haruka Mirai』by Kankaku Piero |"))
        assertEquals(setOf(SongTags.ANIME), tags("キタニタツヤ「永遠」×TVアニメ『BLEACH 千年血戦篇』ANIMATION MV"))
        assertTrue(SongTags.ENGLISH in tags("My Hero Academia - HEROES - FULL ENDING - [ENGLISH COVER by NateWantsToBattle]"))
    }

    @Test fun `what the user asks for`() {
        for (q in listOf("tamil", "a tamil", "tamil songs", "some tamil music", "Tamil song", "thamizh")) {
            assertEquals(q, SongTags.TAMIL, SongTags.requested(q))
        }
        assertEquals(SongTags.ANIME, SongTags.requested("anime songs"))
        assertEquals(SongTags.HINDI, SongTags.requested("bollywood"))
        assertNull(SongTags.requested("believer"))
        assertNull(SongTags.requested("tamil love songs"))
        // The parser hands the music library something requested() understands
        for (s in listOf("play a tamil song", "play tamil songs", "play some tamil music", "play tamil", "play songs in tamil")) {
            val c = CommandParser.parse(s) as Command.PlayMusic
            assertEquals(s, SongTags.TAMIL, SongTags.requested(c.query ?: c.folder.orEmpty()))
        }
    }

    @Test fun `sound means volume`() {
        val down = Command.Volume(Adjust.DOWN, AudioStream.MEDIA)
        for (s in listOf("decrease the sound", "reduce the sound", "lower the music", "sound down", "decrease the song volume")) {
            assertEquals(s, down, CommandParser.parse(s))
        }
        assertEquals(Command.Volume(Adjust.UP, AudioStream.MEDIA), CommandParser.parse("increase the sound"))
    }
}
