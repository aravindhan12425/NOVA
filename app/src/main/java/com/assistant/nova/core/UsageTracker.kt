package com.assistant.nova.core

import android.content.Context
import android.os.Process
import com.assistant.nova.core.speech.Mic
import java.time.LocalDate

/**
 * Today's usage for the battery report: Nova's own CPU time, how long the microphone was
 * open, wake words and commands. Android doesn't let apps read the official per-app
 * battery numbers, so the percentage is an estimate; the app links to Android's page
 * for the exact figure.
 */
class UsageTracker(context: Context) {

    data class Day(val cpuMs: Long, val micMs: Long, val wakes: Int, val commands: Int) {
        /** Rough battery share: a busy CPU core ≈ 0.35 W, the microphone path ≈ 0.02 W. */
        fun estimatedPercent(capacityMah: Int): Double {
            val joules = cpuMs / 1000.0 * 0.35 + micMs / 1000.0 * 0.02
            val batteryJoules = capacityMah / 1000.0 * 3.87 * 3600
            return joules / batteryJoules * 100
        }
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("nova_usage", Context.MODE_PRIVATE)
    private var lastCpu = Process.getElapsedCpuTime()

    private fun rollOver() {
        val today = LocalDate.now().toString()
        if (prefs.getString("day", null) != today) {
            prefs.edit().clear().putString("day", today).apply()
        }
    }

    @Synchronized
    fun sample(mic: Mic) {
        rollOver()
        val cpu = Process.getElapsedCpuTime()
        val dCpu = (cpu - lastCpu).coerceAtLeast(0)
        lastCpu = cpu
        prefs.edit()
            .putLong("cpu", prefs.getLong("cpu", 0) + dCpu)
            .putLong("mic", prefs.getLong("mic", 0) + mic.consumeOpenMs())
            .apply()
    }

    @Synchronized
    fun countWake() {
        rollOver(); prefs.edit().putInt("wakes", prefs.getInt("wakes", 0) + 1).apply()
    }

    @Synchronized
    fun countCommand() {
        rollOver(); prefs.edit().putInt("commands", prefs.getInt("commands", 0) + 1).apply()
    }

    companion object {
        fun today(context: Context): Day {
            val p = context.applicationContext.getSharedPreferences("nova_usage", Context.MODE_PRIVATE)
            if (p.getString("day", null) != LocalDate.now().toString()) return Day(0, 0, 0, 0)
            return Day(p.getLong("cpu", 0), p.getLong("mic", 0), p.getInt("wakes", 0), p.getInt("commands", 0))
        }

        /** Battery size in mAh from Android's power profile (5000 on the Nord CE 3 Lite). */
        fun batteryCapacityMah(context: Context): Int = try {
            val cls = Class.forName("com.android.internal.os.PowerProfile")
            val profile = cls.getConstructor(Context::class.java).newInstance(context)
            (cls.getMethod("getBatteryCapacity").invoke(profile) as Double).toInt().takeIf { it in 1000..20000 } ?: 5000
        } catch (_: Exception) {
            5000
        }
    }
}
