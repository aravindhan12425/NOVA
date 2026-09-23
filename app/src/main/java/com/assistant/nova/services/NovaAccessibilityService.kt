package com.assistant.nova.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import android.os.Handler
import android.os.Looper

class NovaAccessibilityService : AccessibilityService() {

    data class PendingCommand(val keyword: String, val targetState: Boolean?)

    companion object {
        var instance: NovaAccessibilityService? = null
        
        fun lockScreen(context: android.content.Context? = null) {
            performAction(GLOBAL_ACTION_LOCK_SCREEN, context)
        }

        fun toggleQuickSetting(context: android.content.Context? = null, keyword: String, targetState: Boolean? = null) {
            if (instance == null) {
                context?.let {
                    Toast.makeText(it, "Please enable Nova AI in Accessibility Settings", Toast.LENGTH_LONG).show()
                }
            } else {
                if (commandQueue.isEmpty()) {
                    instance?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                }
                commandQueue.add(PendingCommand(keyword, targetState))
            }
        }

        private val commandQueue = mutableListOf<PendingCommand>()

        private fun performAction(action: Int, context: android.content.Context?) {
            if (instance == null) {
                context?.let {
                    Toast.makeText(it, "Please enable Nova AI in Accessibility Settings", Toast.LENGTH_LONG).show()
                }
            } else {
                instance?.performGlobalAction(action)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (commandQueue.isEmpty()) return
        val rootNode = rootInActiveWindow ?: return

        val command = commandQueue[0]
        
        if (processCommand(rootNode, command)) {
            commandQueue.removeAt(0)
            
            if (commandQueue.isEmpty()) {
                handler.postDelayed({
                    instance?.performGlobalAction(GLOBAL_ACTION_BACK)
                }, 1500)
            }
        }
    }

    private fun processCommand(rootNode: AccessibilityNodeInfo, command: PendingCommand): Boolean {
        val node = findNodeByText(rootNode, command.keyword) ?: return false
        
        // Comprehensive state check
        val isOn = isNodeEnabled(node)
        
        // Debug Toast (Optional, but helpful for development)
        // Toast.makeText(this, "${command.keyword} is ${if(isOn) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()

        if (command.targetState != null && command.targetState == isOn) {
            return true 
        }

        return clickNode(node)
    }

    private fun isNodeEnabled(node: AccessibilityNodeInfo): Boolean {
        val description = node.contentDescription?.toString()?.lowercase() ?: ""
        val nodeText = node.text?.toString()?.lowercase() ?: ""
        val fullInfo = "$description $nodeText"
        
        // Check text/description
        if (fullInfo.contains("on") || fullInfo.contains("enabled") || fullInfo.contains("active")) return true
        if (fullInfo.contains("off") || fullInfo.contains("disabled") || fullInfo.contains("inactive")) return false
        
        // Check flags
        if (node.isSelected || node.isChecked) return true
        
        // Check parent/children for flags (some UIs put the state on a child or parent)
        node.parent?.let { if (it.isSelected || it.isChecked) return true }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { if (it.isSelected || it.isChecked) return true }
        }
        
        return false
    }

    private fun findNodeByText(rootNode: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val queue = mutableListOf(rootNode)
        val search = text.lowercase().replace("-", "").replace(" ", "")
        
        while (queue.isNotEmpty()) {
            val node = queue.removeAt(0)
            val contentDesc = node.contentDescription?.toString()?.lowercase()?.replace("-", "")?.replace(" ", "") ?: ""
            val nodeText = node.text?.toString()?.lowercase()?.replace("-", "")?.replace(" ", "") ?: ""
            
            if (contentDesc.contains(search) || nodeText.contains(search)) {
                return node
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // Look for a clickable icon first (OnePlus specific)
        if (node.childCount > 0) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child?.isClickable == true) {
                    child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }

        var target = node
        if (target.isClickable) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        
        while (target.parent != null) {
            target = target.parent
            if (target.isClickable) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }
}
