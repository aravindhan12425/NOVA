package com.assistant.nova.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothAdapter
import android.os.BatteryManager
import android.provider.Settings
import android.hardware.camera2.CameraManager
import android.widget.Toast
import com.assistant.nova.services.NovaAccessibilityService

class CommandProcessor(private val context: Context) {

    private var isFlashlightOn = false

    // Maps sound-alike words to their actual intended keywords
    private val aliasMap = mapOf(
        "ie" to "wi-fi",
        "y-fi" to "wi-fi",
        "yify" to "wi-fi",
        "i" to "wi-fi",
        "hi" to "wi-fi",
        "low" to "lock",
        "log" to "lock",
        "look" to "lock",
        "flashing" to "flashlight",
        "flush" to "flashlight",
        "tour" to "torch"
    )

    fun processCommand(phrases: List<String>): String {
        // We scan all alternative phrases provided by the recognizer
        for (phrase in phrases) {
            val command = preprocessPhrase(phrase)
            
            // Check for multi-command
            if (command.contains(" and ")) {
                val parts = command.split(" and ")
                var result = ""
                for (part in parts) {
                    result += executeSingleCommand(part.trim()) + ". "
                }
                return result.trim()
            }

            val result = executeSingleCommand(command)
            if (result != "I heard: $command") {
                return result // If we matched a real command, stop and return it
            }
        }
        
        return "I heard: ${phrases.getOrNull(0) ?: "nothing"}"
    }

    private fun preprocessPhrase(phrase: String): String {
        var processed = phrase.lowercase().trim()
        // Replace aliases
        aliasMap.forEach { (alias, target) ->
            if (processed.contains(alias)) {
                processed = processed.replace(alias, target)
            }
        }
        return processed
    }

    private fun executeSingleCommand(command: String): String {
        return when {
            command.contains("lock") && command.contains("screen") -> {
                NovaAccessibilityService.lockScreen(context)
                "Screen locked"
            }
            (command.contains("wifi") || command.contains("wi-fi") || command.contains("internet")) -> {
                val target = !command.contains("off") && !command.contains("disable")
                NovaAccessibilityService.toggleQuickSetting(context, "wi-fi", target)
                "Toggling Wi-Fi"
            }
            (command.contains("bluetooth") || command.contains("blue-tooth")) -> {
                val target = !command.contains("off") && !command.contains("disable")
                NovaAccessibilityService.toggleQuickSetting(context, "bluetooth", target)
                "Toggling Bluetooth"
            }
            (command.contains("rotate") || command.contains("rotation")) -> {
                val target = !command.contains("off") && !command.contains("disable")
                NovaAccessibilityService.toggleQuickSetting(context, "auto rotate", target)
                "Toggling Auto-Rotate"
            }
            (command.contains("airplane") || command.contains("aeroplane")) -> {
                val target = !command.contains("off") && !command.contains("disable")
                NovaAccessibilityService.toggleQuickSetting(context, "airplane", target)
                "Toggling Airplane Mode"
            }
            (command.contains("flashlight") || command.contains("torch")) -> {
                val enable = !command.contains("off") && !command.contains("stop")
                setFlashlight(enable)
            }
            command.contains("battery") -> getBatteryLevel()
            command.contains("settings") -> openSettings()
            
            command.contains("whatsapp") && (command.contains("send") || command.contains("message")) -> {
                val message = extractMessage(command)
                sendWhatsappMessage(message)
                "Opening WhatsApp"
            }

            command.startsWith("open ") -> openApp(command.removePrefix("open "))
            command.startsWith("call ") -> callContact(command.removePrefix("call "))
            
            else -> "I heard: $command"
        }
    }

    private fun extractMessage(command: String): String {
        return when {
            command.contains("message") -> command.substringAfter("message").trim()
            command.contains("say") -> command.substringAfter("say").trim()
            command.contains("send") -> command.substringAfter("send").trim()
            else -> "Hello"
        }
    }

    private fun setFlashlight(enable: Boolean): String {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
            isFlashlightOn = enable
            "Flashlight ${if (enable) "on" else "off"}"
        } catch (e: Exception) {
            "Flashlight not available"
        }
    }

    private fun sendWhatsappMessage(message: String): String {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            `package` = "com.whatsapp"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(sendIntent)
        return "WhatsApp message ready"
    }

    private fun openApp(appName: String): String {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val search = appName.lowercase().trim()
        
        for (app in packages) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            val packageName = app.packageName.lowercase()
            
            if (label.contains(search) || packageName.contains(search)) {
                val intent = pm.getLaunchIntentForPackage(app.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return "Opening $label"
                }
            }
        }
        return "I couldn't find $appName"
    }

    private fun callContact(name: String): String {
        val intent = Intent(Intent.ACTION_DIAL)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Opening dialer for $name"
    }

    private fun getBatteryLevel(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Battery level: $level%"
    }

    private fun openSettings(): String {
        val intent = Intent(Settings.ACTION_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Opening settings"
    }
}
