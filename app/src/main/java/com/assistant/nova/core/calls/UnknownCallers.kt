package com.assistant.nova.core.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** One call from the phone's call history. [type] uses CallLog.Calls' numbers (1 in, 2 out, 3 missed…). */
data class CallRecord(
    val number: String,
    val cachedName: String?,
    val type: Int,
    val date: Long,
    val durationSec: Long,
    val location: String? = null,
)

/** All calls with one number that isn't in the contacts. */
data class UnknownCaller(
    /** Last 10 digits: the same person whether they called as "+91 98…" or "098…". */
    val key: String,
    /** The number as the phone showed it on the latest call. */
    val number: String,
    val calls: Int,
    val incoming: Int,
    val outgoing: Int,
    val missed: Int,
    val talkSec: Long,
    val lastCall: Long,
    val location: String?,
    /** The user's own name for it in Nova ("Repair shop"), if any. */
    val label: String?,
)

/**
 * Numbers from the call history that aren't saved contacts, one row per number, so a shop or
 * delivery person called days ago is easy to find again. Pure logic is separate from the
 * Android queries ([load]) so it can be tested on a PC.
 */
object UnknownCallers {
    const val INCOMING = 1
    const val OUTGOING = 2
    const val MISSED = 3
    const val REJECTED = 5

    enum class Sort { RECENT, MOST_CALLS, LONGEST_TALK }

    /** Digits only, last 10 ("+91 98400 12345" and "098400 12345" match). Empty = no usable number. */
    fun key(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length < 5) "" else digits.takeLast(10)
    }

    fun group(records: List<CallRecord>, contactKeys: Set<String>, labels: Map<String, String> = emptyMap()): List<UnknownCaller> =
        records
            .filter { it.cachedName.isNullOrBlank() }
            .groupBy { key(it.number) }
            .filterKeys { it.isNotEmpty() && it !in contactKeys }
            .map { (k, calls) ->
                val latest = calls.maxBy { it.date }
                UnknownCaller(
                    key = k,
                    number = latest.number,
                    calls = calls.size,
                    incoming = calls.count { it.type == INCOMING },
                    outgoing = calls.count { it.type == OUTGOING },
                    missed = calls.count { it.type == MISSED || it.type == REJECTED },
                    talkSec = calls.sumOf { it.durationSec },
                    lastCall = latest.date,
                    location = calls.firstNotNullOfOrNull { c -> c.location?.takeIf { it.isNotBlank() } },
                    label = labels[k],
                )
            }
            .let { sort(it, Sort.RECENT) }

    fun sort(list: List<UnknownCaller>, sort: Sort): List<UnknownCaller> = when (sort) {
        Sort.RECENT -> list.sortedByDescending { it.lastCall }
        Sort.MOST_CALLS -> list.sortedWith(compareByDescending<UnknownCaller> { it.calls }.thenByDescending { it.lastCall })
        Sort.LONGEST_TALK -> list.sortedWith(compareByDescending<UnknownCaller> { it.talkSec }.thenByDescending { it.lastCall })
    }

    /** "98400" finds the number; "repair" finds a label; "chennai" finds a location. */
    fun search(list: List<UnknownCaller>, query: String): List<UnknownCaller> {
        val q = query.trim()
        if (q.isEmpty()) return list
        val digits = q.filter { it.isDigit() }
        return list.filter { u ->
            (digits.length >= 2 && digits.length == q.count { !it.isWhitespace() && it != '+' && it != '-' } &&
                u.key.contains(digits.takeLast(10))) ||
                u.label?.contains(q, ignoreCase = true) == true ||
                u.location?.contains(q, ignoreCase = true) == true
        }
    }

    // ── Android ─────────────────────────────────────────────────────────────

    private fun granted(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun canRead(context: Context) =
        granted(context, Manifest.permission.READ_CALL_LOG) && granted(context, Manifest.permission.READ_CONTACTS)

    /** Reads the call history (latest 3000 calls) and contacts. Call off the main thread. */
    fun load(context: Context): List<UnknownCaller> {
        if (!canRead(context)) return emptyList()
        val cr = context.contentResolver
        val contactKeys = HashSet<String>()
        cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
            ?.use { c -> while (c.moveToNext()) key(c.getString(0).orEmpty()).takeIf { it.isNotEmpty() }?.let(contactKeys::add) }
        val records = ArrayList<CallRecord>()
        cr.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
                CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.GEOCODED_LOCATION,
            ),
            null, null, "${CallLog.Calls.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && records.size < 3_000) {
                records += CallRecord(
                    number = c.getString(0).orEmpty(),
                    cachedName = c.getString(1),
                    type = c.getInt(2),
                    date = c.getLong(3),
                    durationSec = c.getLong(4),
                    location = c.getString(5),
                )
            }
        }
        return group(records, contactKeys, NumberLabels(context).all())
    }
}

/** The user's own names for numbers that aren't contacts ("Repair shop"). Kept only in Nova. */
class NumberLabels(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("nova_number_labels", Context.MODE_PRIVATE)

    fun all(): Map<String, String> = p.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun set(number: String, label: String) {
        val k = UnknownCallers.key(number)
        if (k.isEmpty()) return
        if (label.isBlank()) p.edit().remove(k).apply() else p.edit().putString(k, label.trim()).apply()
    }

    /** "call the repair shop": the number whose name matches best, or null. */
    fun find(name: String): Pair<String, String>? {
        val q = name.lowercase().removePrefix("the ").trim()
        if (q.isEmpty()) return null
        val all = all()
        val hit = all.entries.firstOrNull { it.value.lowercase() == q }
            ?: all.entries.firstOrNull { it.value.lowercase().contains(q) || q.contains(it.value.lowercase()) }
        return hit?.let { it.key to it.value }
    }
}
