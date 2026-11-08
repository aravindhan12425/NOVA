package com.assistant.nova.core.action

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.assistant.nova.core.command.FuzzyMatcher

data class ContactEntry(val name: String, val number: String)

/** Contacts on the phone, cached for a few minutes. Read locally: nothing leaves the phone. */
class ContactsRepo(private val context: Context) {
    private var cache: List<ContactEntry>? = null
    private var cachedAt = 0L

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun all(): List<ContactEntry> {
        val now = SystemClock.elapsedRealtime()
        cache?.let { if (now - cachedAt < 5 * 60_000) return it }
        if (!hasPermission()) return emptyList()
        val out = LinkedHashMap<String, ContactEntry>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            ),
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)?.trim().orEmpty()
                val number = c.getString(1)?.trim().orEmpty()
                if (name.isNotEmpty() && number.isNotEmpty() && name !in out) out[name] = ContactEntry(name, number)
            }
        }
        return out.values.toList().also { cache = it; cachedAt = now }
    }

    fun find(query: String, minScore: Double = 0.62): FuzzyMatcher.Match<ContactEntry>? =
        FuzzyMatcher.best(query, all(), minScore) { it.name }
}

data class AppEntry(val label: String, val packageName: String)

/** Apps that appear in the launcher. */
class AppsRepo(private val context: Context) {
    private var cache: List<AppEntry>? = null
    private var cachedAt = 0L

    fun all(): List<AppEntry> {
        val now = SystemClock.elapsedRealtime()
        cache?.let { if (now - cachedAt < 10 * 60_000) return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
        return apps.also { cache = it; cachedAt = now }
    }

    fun find(query: String): FuzzyMatcher.Match<AppEntry>? =
        FuzzyMatcher.best(query, all(), 0.7) { it.label }

    fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) {
        false
    }
}
