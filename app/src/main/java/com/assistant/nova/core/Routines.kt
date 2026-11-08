package com.assistant.nova.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A routine: one phrase ("good night") that runs several ordinary Nova commands in order.
 * Actions are written exactly like spoken commands, so anything Nova understands can be used.
 */
data class Routine(val name: String, val actions: List<String>)

class Routines(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nova_routines", Context.MODE_PRIVATE)

    companion object {
        /** Starting examples; the user can edit or delete them. */
        val DEFAULTS = listOf(
            Routine("good night", listOf("turn on do not disturb", "turn off wifi", "brightness 10 percent", "when is my next alarm")),
            Routine("good morning", listOf("turn off do not disturb", "turn on wifi", "brightness 60 percent", "what's the date", "how much battery")),
            Routine("i'm driving", listOf("turn on bluetooth", "volume 8")),
            Routine("movie time", listOf("turn on do not disturb", "brightness 30 percent")),
        )
    }

    fun all(): List<Routine> {
        val json = prefs.getString("routines", null) ?: return DEFAULTS.also { save(it) }
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val a = o.getJSONArray("actions")
                Routine(o.getString("name"), List(a.length()) { a.getString(it) })
            }
        } catch (_: Exception) {
            DEFAULTS
        }
    }

    fun names(): List<String> = all().map { it.name }

    fun find(name: String): Routine? = all().firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun save(routines: List<Routine>) {
        val arr = JSONArray()
        routines.forEach { r ->
            arr.put(JSONObject().put("name", r.name.trim()).put("actions", JSONArray(r.actions.map { it.trim() }.filter { it.isNotEmpty() })))
        }
        prefs.edit().putString("routines", arr.toString()).apply()
    }

    fun upsert(old: String?, routine: Routine) {
        val list = all().filterNot { old != null && it.name.equals(old, ignoreCase = true) }
            .filterNot { it.name.equals(routine.name, ignoreCase = true) }
        save(list + routine)
    }

    fun delete(name: String) = save(all().filterNot { it.name.equals(name, ignoreCase = true) })
}
