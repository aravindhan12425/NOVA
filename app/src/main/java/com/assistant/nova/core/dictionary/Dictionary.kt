package com.assistant.nova.core.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Offline English dictionary (WordNet 3.0, ~155,000 words) bundled in the app.
 * The database is copied out of the APK on first use (~15 MB) and read from disk,
 * so it doesn't sit in memory.
 */
class Dictionary(private val context: Context) {

    data class Sense(val pos: String, val definition: String)

    /** [asked] = what the user said, [lemma] = the dictionary form actually found. */
    data class Entry(val asked: String, val lemma: String, val senses: List<Sense>) {
        /** Short spoken answer: the two most common meanings. */
        fun spoken(): String = buildString {
            if (lemma != asked) append("${asked.cap()}, from $lemma. ")
            val first = senses.first()
            append("${lemma.cap()}, ${posName(first.pos)}: ${first.definition}.")
            senses.getOrNull(1)?.let { append(" It can also mean: ${it.definition}.") }
        }

        fun display(): String =
            (if (lemma != asked) "$asked → $lemma\n" else "$lemma\n") +
                senses.take(3).joinToString("\n") { "${posName(it.pos)}: ${it.definition}" }

        private fun String.cap() = replaceFirstChar { it.uppercase() }
    }

    companion object {
        private const val ASSET = "dictionary/wordnet.db"
        private const val DB_NAME = "wordnet.db"
        private const val VERSION = 1

        fun posName(pos: String) = when (pos) {
            "n" -> "noun"
            "v" -> "verb"
            "a", "s" -> "adjective"
            "r" -> "adverb"
            else -> pos
        }
    }

    private val db: SQLiteDatabase by lazy {
        val file = context.getDatabasePath(DB_NAME)
        val prefs = context.getSharedPreferences("nova_dictionary", Context.MODE_PRIVATE)
        if (!file.exists() || prefs.getInt("version", 0) != VERSION) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(file)
            prefs.edit().putInt("version", VERSION).apply()
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    /** Looks up [word] (any form: "vilest", "went", "black holes"). Call off the main thread. */
    fun lookup(word: String): Entry? {
        val cleaned = word.trim().lowercase()
        if (cleaned.isEmpty()) return null
        for (candidate in Morphy.candidates(cleaned, ::exceptions)) {
            val senses = senses(candidate)
            if (senses.isNotEmpty()) return Entry(cleaned, candidate, senses)
        }
        return null
    }

    private fun exceptions(form: String): List<String> =
        db.rawQuery("SELECT base FROM exc WHERE form = ?", arrayOf(form)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun senses(lemma: String): List<Sense> =
        // Most common part of speech first (the one with the most senses)
        db.rawQuery("SELECT pos, defs FROM entries WHERE lemma = ? ORDER BY senses DESC", arrayOf(lemma)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val pos = c.getString(0)
                    c.getString(1).split('\n').forEach { add(Sense(pos, it)) }
                }
            }
        }
}
