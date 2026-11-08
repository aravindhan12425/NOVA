package com.assistant.nova.core.dictionary

import java.util.Locale

/**
 * WordNet's rules for finding a word's dictionary form: "vilest" -> "vile",
 * "running" -> "run", "babies" -> "baby", plus irregular forms ("went" -> "go")
 * from the exception lists. Pure Kotlin so it can be unit tested.
 */
object Morphy {

    private val RULES = listOf(
        // nouns
        "ses" to "s", "xes" to "x", "zes" to "z", "ches" to "ch", "shes" to "sh", "men" to "man", "ies" to "y", "s" to "",
        // verbs
        "ies" to "y", "es" to "e", "es" to "", "ed" to "e", "ed" to "", "ing" to "e", "ing" to "",
        // adjectives
        "est" to "e", "est" to "", "er" to "e", "er" to "",
    )

    /** Possible dictionary forms of [word], most likely first (the word itself first). */
    fun candidates(word: String, exceptions: (String) -> List<String>): List<String> {
        val w = word.lowercase(Locale.ROOT).trim()
        val out = linkedSetOf(w)
        out += exceptions(w)
        for ((suffix, replacement) in RULES) {
            if (w.length > suffix.length + 1 && w.endsWith(suffix)) out += w.dropLast(suffix.length) + replacement
        }
        // "biggest" -> "bigg" -> "big", "running" -> "runn" -> "run"
        out.toList().forEach { c ->
            if (c.length >= 3 && c[c.length - 1] == c[c.length - 2] && c != w) out += c.dropLast(1)
        }
        return out.toList()
    }
}
