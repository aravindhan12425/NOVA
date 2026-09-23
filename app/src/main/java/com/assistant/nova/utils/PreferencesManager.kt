package com.assistant.nova.utils

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)

    fun getWakeWord(): String {
        return prefs.getString("wake_word", "Nova") ?: "Nova"
    }

    fun setWakeWord(word: String) {
        prefs.edit().putString("wake_word", word).apply()
    }
}
