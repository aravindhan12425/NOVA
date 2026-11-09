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

        /** Words that describe WHERE a folder is, not its name: "from audio in files in English musics". */
        private val PLACE_WORDS = setOf(
            "in", "from", "inside", "under", "of", "on", "at", "the", "my", "a", "files", "file", "fails", "manager",
            "audio", "audios", "folder", "folders", "directory", "storage", "internal", "sd", "card", "memory", "phone",
            "device", "downloads", "download", "section", "category", "tab",
        )

        /** "English musics" -> "english music": lowercase, punctuation gone, simple plurals dropped. */
        internal fun folderWords(s: String): List<String> =
            s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotBlank() }
                .map { w -> if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w }

        /**
         * Finds the folder the user means among [folders] (e.g. "Music", "ENGLISH MUSIC").
         * Copes with extra words around the name and plurals: "from audio in files in english musics",
         * "files audio english music folder", "english songs". Null if nothing fits.
         */
        fun matchFolder(spoken: String, folders: Collection<String>): String? {
            val said = folderWords(spoken).filter { it !in PLACE_WORDS }
            if (said.isEmpty()) {
                // Only place words ("play songs from audio"): a folder literally called that, if any
                return folders.firstOrNull { folderWords(it) == folderWords(spoken).takeLast(1) }
            }
            val named = folders.associateWith { folderWords(it) }.filterValues { it.isNotEmpty() }
            // 1. Exactly the folder's name
            named.entries.firstOrNull { it.value == said }?.let { return it.key }
            // 2. The folder's whole name appears in what was said: the longest such name wins
            //    ("in files in english music" contains both "music" and "english music")
            named.entries.filter { (_, w) -> said.windowed(w.size).any { it == w } }
                .maxByOrNull { it.value.size }?.let { return it.key }
            // 3. Close enough ("inglish music", "english songs")
            val text = said.joinToString(" ")
            return named.entries
                .map { it.key to maxOf(FuzzyMatcher.score(text, it.value.joinToString(" ")), songsAsMusic(text, it.value)) }
                .filter { it.second >= 0.7 }
                .maxByOrNull { it.second }?.first
        }

        /** "english songs" vs a folder "English music": songs/music/tracks mean the same here. */
        private fun songsAsMusic(said: String, folder: List<String>): Double {
            val same = Regex("\\b(?:song|track|mp3|music|audio)\\b")
            val a = same.replace(said, "music")
            val b = same.replace(folder.joinToString(" "), "music")
            return if (a == said && b == folder.joinToString(" ")) 0.0 else FuzzyMatcher.score(a, b)
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
            val f = matchFolder(folder, folders().keys) ?: return emptyList()
            return tracks.filter { it.folder == f }
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
        val byFolder = matchFolder(query, folders().keys)?.let { FuzzyMatcher.Match(it, it, 0.9) }
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
