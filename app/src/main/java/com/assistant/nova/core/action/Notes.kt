package com.assistant.nova.core.action

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Voice notes ("take a note buy milk"), kept only on the phone. */
class Notes(context: Context) {
    data class Note(val text: String, val time: Long)

    private val prefs = context.applicationContext.getSharedPreferences("nova_notes", Context.MODE_PRIVATE)

    fun all(): List<Note> = try {
        val arr = JSONArray(prefs.getString("notes", "[]"))
        List(arr.length()) { i -> arr.getJSONObject(i).let { Note(it.getString("text"), it.getLong("time")) } }
    } catch (_: Exception) {
        emptyList()
    }

    fun add(text: String) = save(all() + Note(text, System.currentTimeMillis()))

    fun clear() = save(emptyList())

    private fun save(notes: List<Note>) {
        val arr = JSONArray()
        notes.takeLast(200).forEach { arr.put(JSONObject().put("text", it.text).put("time", it.time)) }
        prefs.edit().putString("notes", arr.toString()).apply()
    }
}
