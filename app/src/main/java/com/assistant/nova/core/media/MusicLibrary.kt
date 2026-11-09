package com.assistant.nova.core.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.assistant.nova.core.command.FuzzyMatcher

/** [folder] = the folder the file is in, e.g. "English music" for Audio/English music/song.mp3. */
data class Track(val id: Long, val title: String, val artist: String, val album: String, val uri: Uri, val folder: String = "")

/**
 * Songs stored on the phone, from Android's media library. That library already covers
 * internal storage AND the SD card. Nova only reads song details (title, artist,
 * album), never documents or other files.
 */
class MusicLibrary(private val context: Context) {
    private var cache: List<Track>? = null
    private var cachedAt = 0L

    companion object {
        /**
         * False for folders that hold recordings rather than songs: call / voice recorder files
         * (e.g. "Music/Recordings/Standard Recordings/") and app media like WhatsApp voice notes,
         * which Android still marks as "music". Without this, shuffle could play a recording.
         */
        fun isSongFolder(relativePath: String?): Boolean {
            val p = relativePath.orEmpty().lowercase()
            return !p.startsWith("android/") &&
                listOf("recording", "recorder", "callrec", "whatsapp", "voice note", "voice_note", "notification", "ringtone")
                    .none { it in p }
        }

        val PERMISSION: String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    private val collection: Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) // every storage volume, SD card included
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    /** All songs (at least 30 s long, so ringtones and voice notes are skipped). Call off the main thread. */
    fun all(): List<Track> {
        val now = SystemClock.elapsedRealtime()
        cache?.let { if (now - cachedAt < 10 * 60_000) return it }
        if (!hasPermission()) return emptyList()
        val tracks = ArrayList<Track>()
        context.contentResolver.query(
            collection,
            arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.RELATIVE_PATH,
            ),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (!isSongFolder(c.getString(4))) continue
                val id = c.getLong(0)
                tracks += Track(
                    id = id,
                    title = c.getString(1).orEmpty(),
                    artist = c.getString(2)?.takeUnless { it == MediaStore.UNKNOWN_STRING }.orEmpty(),
                    album = c.getString(3).orEmpty(),
                    uri = ContentUris.withAppendedId(collection, id),
                    // "Music/English music/" -> "English music"
                    folder = c.getString(4)?.trim('/')?.substringAfterLast('/').orEmpty(),
                )
            }
        }
        return tracks.also { cache = it; cachedAt = now }
    }

    /**
     * Finds what to play: one song by title (optionally by a given artist), or every song
     * by an artist. Returns the queue to play, or an empty list.
     */
    /** Folders that contain music, with how many songs each ("English music" to 23). */
    fun folders(): Map<String, Int> = all().filter { it.folder.isNotBlank() }.groupingBy { it.folder }.eachCount()

    fun find(query: String?, artist: String?, folder: String? = null): List<Track> {
        val tracks = all()
        if (tracks.isEmpty()) return emptyList()
        if (!folder.isNullOrBlank()) {
            // "play songs from English music folder": that folder's songs, in order
            val f = FuzzyMatcher.best(folder, folders().keys.toList(), 0.7) { it } ?: return emptyList()
            return tracks.filter { it.folder == f.item }
        }
        if (query.isNullOrBlank()) {
            if (artist.isNullOrBlank()) return tracks.shuffled()
            val byArtist = FuzzyMatcher.best(artist, tracks.map { it.artist }.distinct().filter { it.isNotBlank() }, 0.7) { it }
                ?: return emptyList()
            return tracks.filter { it.artist == byArtist.item }.shuffled()
        }
        val pool = if (artist.isNullOrBlank()) tracks else {
            val a = FuzzyMatcher.best(artist, tracks.map { it.artist }.distinct().filter { it.isNotBlank() }, 0.7) { it }
            if (a != null) tracks.filter { it.artist == a.item } else tracks
        }
        val song = FuzzyMatcher.best(query, pool, 0.7) { it.title }
        // Not a song title? Maybe it's an artist or album name ("play Anirudh", "play Naruto OST")
        val byArtist = FuzzyMatcher.best(query, tracks.map { it.artist }.distinct().filter { it.isNotBlank() }, 0.85) { it }
        val byAlbum = FuzzyMatcher.best(query, tracks.map { it.album }.distinct().filter { it.isNotBlank() }, 0.85) { it }
        val byFolder = FuzzyMatcher.best(query, folders().keys.toList(), 0.85) { it }
        val songScore = song?.score ?: 0.0
        return when {
            byFolder != null && byFolder.score > songScore -> tracks.filter { it.folder == byFolder.item }
            byArtist != null && byArtist.score > songScore -> tracks.filter { it.artist == byArtist.item }.shuffled()
            byAlbum != null && byAlbum.score > songScore -> tracks.filter { it.album == byAlbum.item }
            song != null -> listOf(song.item) + (pool - song.item).shuffled() // then keep playing other songs
            else -> emptyList()
        }
    }
}
