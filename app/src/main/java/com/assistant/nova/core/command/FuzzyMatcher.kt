package com.assistant.nova.core.command

import java.util.Locale
import kotlin.math.max

/**
 * Matches a spoken name against contact names or app names, tolerating the
 * mistakes speech recognition makes ("arvind" vs "Aravind", "you tube" vs "YouTube")
 * and family words ("mom" finds a contact saved as "Amma").
 */
object FuzzyMatcher {

    data class Match<T>(val item: T, val label: String, val score: Double)

    private val RELATIONS: List<Set<String>> = listOf(
        setOf("mom", "mother", "mummy", "mommy", "mum", "amma", "maa", "ma", "mama", "ammi"),
        setOf("dad", "father", "daddy", "papa", "appa", "pa", "pappa", "abba", "pitaji"),
        setOf("brother", "bro", "anna", "bhaiya", "bhai", "thambi"),
        setOf("sister", "sis", "akka", "didi", "thangachi"),
        setOf("wife", "wifey", "mrs"),
        setOf("husband", "hubby"),
        setOf("grandma", "grandmother", "granny", "paati", "nani", "dadi", "ammamma", "ammachi"),
        setOf("grandpa", "grandfather", "thatha", "nana", "dada", "appachan"),
    )

    fun <T> best(query: String, items: List<T>, minScore: Double = 0.62, label: (T) -> String): Match<T>? {
        val variants = variants(query)
        var best: Match<T>? = null
        for (item in items) {
            val l = label(item)
            val s = variants.maxOf { score(it, l) }
            if (s >= minScore && (best == null || s > best.score)) best = Match(item, l, s)
        }
        return best
    }

    /** The query plus versions with family words swapped ("mom" -> "amma", "mummy", …). */
    fun variants(query: String): List<String> {
        val q = norm(query)
        val tokens = q.split(' ')
        val out = linkedSetOf(q)
        tokens.forEachIndexed { i, tk ->
            RELATIONS.firstOrNull { tk in it }?.forEach { alt ->
                out += tokens.toMutableList().also { it[i] = alt }.joinToString(" ")
            }
        }
        return out.toList()
    }

    fun score(query: String, candidate: String): Double {
        val qn = norm(query)
        val cn = norm(candidate)
        if (qn.isEmpty() || cn.isEmpty()) return 0.0
        if (qn == cn) return 1.0
        val qc = qn.replace(" ", "")
        val cc = cn.replace(" ", "")
        if (qc == cc) return 0.98

        val qt = qn.split(' ')
        val ct = cn.split(' ')
        if (qt.all { q -> ct.any { it == q } }) return 0.92 - 0.02 * (ct.size - qt.size).coerceAtLeast(0)
        if (qc.length >= 3 && cc.startsWith(qc)) return 0.85
        if (qc.length >= 3 && ct.any { it.startsWith(qc) }) return 0.8

        val qp = phonetic(qc)
        if (qp.length >= 3) {
            if (qp == phonetic(cc)) return 0.8
            if (ct.any { phonetic(it) == qp }) return 0.78
        }
        val sim = max(similarity(qc, cc), ct.maxOf { similarity(qc, it) } * 0.95)
        return sim * 0.9
    }

    fun norm(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").trim().replace(Regex("\\s+"), " ")

    /** Rough sound-alike key tuned for Indian names: aravind/arvind -> "arvnd", karthik/kartik -> "krtk". */
    fun phonetic(s: String): String {
        var x = s.lowercase(Locale.ROOT).filter { it in 'a'..'z' }
        if (x.isEmpty()) return ""
        listOf(
            "ph" to "f", "th" to "t", "dh" to "d", "bh" to "b", "kh" to "k", "gh" to "g",
            "sh" to "s", "ch" to "c", "ck" to "k", "q" to "k", "z" to "s", "w" to "v",
        ).forEach { (a, b) -> x = x.replace(a, b) }
        val first = x.first()
        val rest = x.drop(1).filter { it !in "aeiouyh" }
        val sb = StringBuilder().append(first)
        for (c in rest) if (sb.last() != c) sb.append(c)
        return sb.toString()
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
