package com.assistant.nova.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.assistant.nova.core.NovaPrefs
import com.assistant.nova.core.command.Feature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Lets Nova do what Android doesn't allow apps to do directly: tap Quick Settings tiles,
 * press WhatsApp's Send button, lock the screen, take screenshots and open apps from the
 * background.
 *
 * It only looks at the screen while running a command, and only inside the system UI or
 * WhatsApp. Old version problems fixed here: a failed toggle no longer stays queued
 * forever (it used to tap matching text later in other apps), and on/off is read from
 * the tile's real state instead of guessing from words like "connected".
 */
class NovaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: NovaAccessibilityService? = null
            private set

        private const val SYSTEM_UI = "com.android.systemui"

        /** Compact labels ("wifi", "mobiledata"…) a tile may carry, best first. */
        private val TILE_NAMES: Map<Feature, List<String>> = mapOf(
            Feature.WIFI to listOf("wifi", "wlan", "internet"),
            Feature.BLUETOOTH to listOf("bluetooth"),
            Feature.MOBILE_DATA to listOf("mobiledata", "cellulardata", "mobilenetwork", "data"),
            Feature.AIRPLANE to listOf("airplanemode", "aeroplanemode", "flightmode", "airplane", "aeroplane"),
            Feature.HOTSPOT to listOf("hotspot", "personalhotspot", "wifihotspot", "tethering"),
            Feature.AUTO_ROTATE to listOf("autorotate", "autorotation", "screenrotation", "rotate", "rotation", "portrait"),
            Feature.DND to listOf("donotdisturb", "dnd"),
            Feature.FLASHLIGHT to listOf("flashlight", "torch"),
            Feature.LOCATION to listOf("location", "gps"),
            Feature.BATTERY_SAVER to listOf("batterysaver", "powersaving", "powersaver", "batterysaving", "energysaver"),
            Feature.NFC to listOf("nfc"),
            Feature.DARK_MODE to listOf("darkmode", "darktheme", "nightmode"),
        )

        /** Tiles that contain another feature's name and must not be confused with it. */
        private val TILE_EXCLUDE: Map<Feature, List<String>> = mapOf(
            Feature.WIFI to listOf("hotspot", "calling", "direct"),
            Feature.MOBILE_DATA to listOf("saver", "roaming", "usage"),
            Feature.DARK_MODE to listOf("nightlight"),
            Feature.LOCATION to listOf("sharing"),
        )
    }

    /** DONE = switched (confirmed where possible), NO_EFFECT = tapped but nothing changed. */
    enum class QsResult { DONE, ALREADY, NOT_FOUND, NO_EFFECT }

    private val mutex = Mutex()
    private val tag = "NovaA11y"

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // After a reboot Android starts this service early: a good moment to resume listening
        val prefs = NovaPrefs(this)
        if (prefs.alwaysOn && prefs.startOnBoot && !NovaListenerService.isRunning) {
            NovaListenerService.start(this, NovaListenerService.ACTION_BOOT)
        }
    }

    // Nova reads the screen on demand while running a command; it ignores the event stream.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    // ── Quick Settings ──────────────────────────────────────────────────────

    /**
     * Opens Quick Settings, finds the tile for [feature] (looking through up to 3 pages),
     * taps it unless it is already in the [desired] state, checks that it really changed,
     * and closes the panel again.
     *
     * Big tiles (like Wi-Fi on OxygenOS 15) are several overlapping buttons: the whole tile,
     * the round icon that toggles, and a label that opens settings. Nova tries the most
     * specific part first and moves on if the switch didn't change.
     *
     * [systemState] reads the real on/off state from Android where possible (null = unknown).
     */
    suspend fun setQuickSetting(feature: Feature, desired: Boolean?, systemState: () -> Boolean?): QsResult = mutex.withLock {
        withContext(Dispatchers.Main) {
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            delay(700)
            try {
                val candidates = findTileCandidates(feature)
                if (candidates.isEmpty()) {
                    Log.w(tag, "$feature: no tile found")
                    return@withContext QsResult.NOT_FOUND
                }
                candidates.forEach { Log.i(tag, "$feature candidate: ${describe(it)}") }
                fun stateNow(node: AccessibilityNodeInfo): Boolean? =
                    systemState() ?: run { node.refresh(); tileState(node) }
                    ?: candidates.firstNotNullOfOrNull { c -> c.refresh(); tileState(c) }

                val before = stateNow(candidates.first())
                Log.i(tag, "$feature: before=$before desired=$desired")
                if (desired != null && before == desired) return@withContext QsResult.ALREADY

                for (node in candidates) {
                    val tapped = click(node)
                    Log.i(tag, "$feature: tapped=${tapped} ${describe(node)}")
                    if (!tapped) continue
                    // Wait up to 2.5 s for the switch to change (Wi-Fi takes a moment)
                    val deadline = SystemClock.uptimeMillis() + 2_500
                    while (SystemClock.uptimeMillis() < deadline) {
                        delay(250)
                        val now = stateNow(node)
                        val changed = when {
                            now == null || before == null -> true // can't tell: trust the tap
                            desired != null -> now == desired
                            else -> now != before
                        }
                        if (changed) {
                            Log.i(tag, "$feature: switched (now=$now)")
                            return@withContext QsResult.DONE
                        }
                    }
                    Log.w(tag, "$feature: no change after tapping ${describe(node)}")
                }
                QsResult.NO_EFFECT
            } finally {
                closeShade()
            }
        }
    }

    private fun describe(n: AccessibilityNodeInfo): String {
        val r = Rect().also { n.getBoundsInScreen(it) }
        return "${n.className?.toString()?.substringAfterLast('.')} desc='${n.contentDescription}' " +
            "text='${n.text}' clickable=${n.isClickable} checkable=${n.isCheckable}/${n.isChecked} bounds=$r"
    }

    /** Matching tile parts, best first: up to 3 pages of Quick Settings are searched. */
    private suspend fun findTileCandidates(f: Feature): List<AccessibilityNodeInfo> {
        repeat(3) {
            val deadline = SystemClock.uptimeMillis() + 1_500
            while (SystemClock.uptimeMillis() < deadline) {
                val found = tileCandidates(f)
                if (found.isNotEmpty()) return found
                delay(250)
            }
            // Not on this page: swipe the Quick Settings pager forward and look again
            if (!scrollQuickSettings()) return emptyList()
            delay(600)
        }
        return emptyList()
    }

    private fun systemUiRoots(): List<AccessibilityNodeInfo> {
        val roots = try {
            windows.mapNotNull { it.root }
        } catch (_: Exception) {
            emptyList()
        }
        val sys = roots.filter { it.packageName?.toString()?.contains("systemui") == true }
        return sys.ifEmpty { listOfNotNull(rootInActiveWindow).filter { it.packageName == SYSTEM_UI } }
    }

    private fun compact(s: CharSequence?): String =
        s?.toString()?.lowercase(Locale.ROOT)?.filter { it.isLetterOrDigit() } ?: ""

    /**
     * Every clickable part of the tile for [f], best first: best label match, then the
     * smallest area (the round icon before the whole tile). Parts that open settings
     * ("Open Wi-Fi settings.") are skipped.
     */
    private fun tileCandidates(f: Feature): List<AccessibilityNodeInfo> {
        val names = TILE_NAMES.getValue(f)
        val exclude = TILE_EXCLUDE[f].orEmpty()
        data class Hit(val node: AccessibilityNodeInfo, val score: Int, val area: Long)
        val hits = ArrayList<Hit>()
        for (root in systemUiRoots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 3_000) {
                val node = queue.removeFirst()
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
                if (!node.isClickable || !node.isVisibleToUser) continue
                val desc = compact(node.contentDescription)
                val text = compact(node.text)
                if (desc.isEmpty() && text.isEmpty()) continue
                if (desc.startsWith("open") || desc.endsWith("settings")) continue
                if (exclude.any { desc.contains(it) || text.contains(it) }) continue
                if (f == Feature.MOBILE_DATA && looksLikeMobileDataTile(node)) {
                    val r = Rect().also { node.getBoundsInScreen(it) }
                    hits += Hit(node, 25, r.width().toLong() * r.height())
                    continue
                }
                for ((rank, name) in names.withIndex()) {
                    val s = when {
                        desc == name || text == name -> 30
                        desc.startsWith(name) || text.startsWith(name) -> 20
                        desc.contains(name) || text.contains(name) -> 10
                        else -> 0
                    }
                    if (s > 0) {
                        val r = Rect().also { node.getBoundsInScreen(it) }
                        hits += Hit(node, s - rank, r.width().toLong() * r.height())
                        break
                    }
                }
            }
        }
        val top = hits.maxOfOrNull { it.score } ?: return emptyList()
        // Clickable label-less icons inside the best tile (e.g. the round Wi-Fi icon) also count
        return hits.filter { it.score >= top - 10 }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.area })
            .map { it.node }
            .take(4)
    }

    /**
     * OxygenOS labels the mobile data tile with the carrier and data used ("ar, 20.87 GB")
     * instead of "Mobile data".
     */
    private fun looksLikeMobileDataTile(node: AccessibilityNodeInfo): Boolean {
        val raw = node.contentDescription?.toString().orEmpty()
        if (Regex("\\d+(?:\\.\\d+)?\\s?(?:GB|MB|KB)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return true
        val tm = getSystemService(android.telephony.TelephonyManager::class.java) ?: return false
        val carriers = listOfNotNull(tm.networkOperatorName, tm.simOperatorName).map(::compact).filter { it.length >= 2 }
        val desc = compact(raw)
        return carriers.any { desc == it || desc.startsWith(it) }
    }

    /** Reads whether a tile is on: real checked/state info first, then whole words like "On"/"Off". */
    private fun tileState(node: AccessibilityNodeInfo): Boolean? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            words(node.stateDescription)?.let { return it }
        }
        if (node.isCheckable) return node.isChecked
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            if (c.isCheckable) return c.isChecked
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) words(c.stateDescription)?.let { return it }
        }
        node.parent?.let { p -> if (p.isCheckable) return p.isChecked }
        return words("${node.contentDescription ?: ""} ${node.text ?: ""}")
    }

    private fun words(s: CharSequence?): Boolean? {
        val w = s?.toString()?.lowercase(Locale.ROOT)?.split(Regex("[^a-z]+"))?.toSet() ?: return null
        return when {
            w.any { it in setOf("off", "disabled", "inactive", "unavailable") } -> false
            w.any { it in setOf("on", "enabled", "active") } -> true
            else -> null
        }
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        // Some tiles put the clickable target on a child icon
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            if (c.isClickable) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var p = node.parent
        while (p != null) {
            if (p.isClickable) return p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            p = p.parent
        }
        return false
    }

    private fun scrollQuickSettings(): Boolean {
        for (root in systemUiRoots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                if (n.isScrollable && n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) {
                    return n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
            }
        }
        return false
    }

    /**
     * Closes Quick Settings. OxygenOS 15 ignores Android's "dismiss shade" action, so Nova
     * checks that the panel really closed and presses Back (at most twice) if it didn't.
     */
    private suspend fun closeShade() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            delay(350)
        }
        repeat(2) {
            if (!shadeOpen()) return
            Log.i(tag, "Quick Settings still open: pressing Back")
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(350)
        }
    }

    /** Quick Settings is open when the system UI shows on/off switches (the status bar never does). */
    private fun shadeOpen(): Boolean = systemUiRoots().any { root ->
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 3_000) {
            val n = queue.removeFirst()
            if (n.isVisibleToUser) {
                val id = n.viewIdResourceName.orEmpty()
                if (n.isCheckable || id.endsWith("qs_edit_button") || id.endsWith("quicksettings_settings_button")) return@any true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        false
    }

    // ── Voice unlock ────────────────────────────────────────────────────────

    enum class UnlockResult { UNLOCKED, WRONG_PIN, NO_KEYPAD }

    /**
     * Types [pin] on the lock-screen PIN pad (brought up by UnlockActivity), presses
     * Enter if the phone doesn't unlock by itself, and reports what happened.
     * The PIN is only held in memory for this call.
     */
    suspend fun enterPin(pin: String, isLocked: () -> Boolean): UnlockResult = mutex.withLock {
        withContext(Dispatchers.Main) {
            val keys = waitForKeypad(isLocked)
                ?: return@withContext if (!isLocked()) UnlockResult.UNLOCKED else UnlockResult.NO_KEYPAD
            for (c in pin) {
                val key = keys[c] ?: return@withContext UnlockResult.NO_KEYPAD
                click(key)
                delay(110)
            }
            // Many phones (OnePlus included) unlock as soon as the right PIN is complete
            if (waitUnlocked(isLocked, 900)) return@withContext UnlockResult.UNLOCKED
            findEnterKey()?.let { click(it) }
            if (waitUnlocked(isLocked, 2_500)) {
                UnlockResult.UNLOCKED
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK) // close the PIN pad
                UnlockResult.WRONG_PIN
            }
        }
    }

    private suspend fun waitUnlocked(isLocked: () -> Boolean, ms: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < deadline) {
            if (!isLocked()) return true
            delay(150)
        }
        return !isLocked()
    }

    private suspend fun waitForKeypad(isLocked: () -> Boolean, timeoutMs: Long = 5_000): Map<Char, AccessibilityNodeInfo>? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!isLocked()) return null // unlocked already (no PIN set, or face unlock)
            val keys = keypad()
            if (keys.size == 10) return keys
            delay(200)
        }
        return null
    }

    /** Finds the 0-9 buttons of the lock-screen PIN pad. */
    private fun keypad(): Map<Char, AccessibilityNodeInfo> {
        val best = HashMap<Char, Pair<AccessibilityNodeInfo, Int>>()
        val idRe = Regex("key([0-9])$")
        for (root in systemUiRoots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 3_000) {
                val node = queue.removeFirst()
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
                if (!node.isVisibleToUser) continue
                val idDigit = node.viewIdResourceName?.let { idRe.find(it)?.groupValues?.get(1)?.first() }
                // Keys read "2" or "2ABC"; a clock ("12:45") or date never looks like that
                val label = listOf(compact(node.contentDescription), compact(node.text)).firstOrNull {
                    it.isNotEmpty() && it.length <= 5 && it[0].isDigit() && it.drop(1).all(Char::isLetter)
                }
                val digit = idDigit ?: label?.first() ?: continue
                val score = (if (idDigit != null) 4 else 0) + (if (node.isClickable) 2 else 0) + (if (label?.length == 1) 1 else 0)
                if (score > (best[digit]?.second ?: -1)) best[digit] = node to score
            }
        }
        return best.mapValues { it.value.first }
    }

    private fun findEnterKey(): AccessibilityNodeInfo? {
        val names = setOf("enter", "ok", "done", "confirm", "go", "unlock")
        for (root in systemUiRoots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
                if (!n.isVisibleToUser) continue
                if (n.viewIdResourceName?.endsWith("key_enter") == true) return n
                if (compact(n.contentDescription) in names || compact(n.text) in names) return n
            }
        }
        return null
    }

    // ── Buttons by label ────────────────────────────────────────────────────

    /** True when a visible element labelled one of [labels] is on screen. */
    fun hasLabel(labels: List<String>): Boolean = findByLabel(labels) != null

    /** A button found on screen: its label (lowercase, no spaces) and on/off state if it has one. */
    data class Button(val label: String, val checked: Boolean?)

    fun findButton(labels: List<String>): Button? {
        val n = findByLabel(labels) ?: return null
        val label = compact(n.contentDescription).ifEmpty { compact(n.text) }
        val checked = when {
            n.isCheckable -> n.isChecked
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && n.stateDescription != null ->
                words(n.stateDescription)
            else -> null
        }
        return Button(label, checked)
    }

    /** Every visible label in windows of apps whose package contains [pkgPart] (for the logs). */
    fun visibleLabels(pkgPart: String): List<String> {
        val out = ArrayList<String>()
        val roots = try { windows.mapNotNull { it.root } } catch (_: Exception) { emptyList() } + listOfNotNull(rootInActiveWindow)
        for (root in roots.filter { it.packageName?.contains(pkgPart) == true }) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 2_000) {
                val n = queue.removeFirst()
                for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
                val l = (n.contentDescription ?: n.text)?.toString()
                if (n.isVisibleToUser && !l.isNullOrBlank()) out += "$l${if (n.isCheckable) "[${n.isChecked}]" else ""}"
            }
        }
        return out.distinct()
    }

    /**
     * Waits up to [timeoutMs] for a button labelled one of [labels] to appear (e.g. after
     * opening another app) and clicks it.
     */
    suspend fun waitAndClick(labels: List<String>, timeoutMs: Long = 3_000): Boolean = withContext(Dispatchers.Main) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            findByLabel(labels)?.let { if (click(it)) return@withContext true }
            delay(250)
        }
        false
    }

    private fun findByLabel(labels: List<String>): AccessibilityNodeInfo? {
        val wanted = labels.map { compact(it) }.toSet()
        val roots = try {
            windows.mapNotNull { it.root }
        } catch (_: Exception) {
            emptyList()
        } + listOfNotNull(rootInActiveWindow)
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 3_000) {
                val n = queue.removeFirst()
                for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
                if (n.isVisibleToUser && (compact(n.contentDescription) in wanted || compact(n.text) in wanted)) return n
            }
        }
        return null
    }

    /** Clicks the first visible button whose label is one of [labels] (e.g. "decline" on the call screen). */
    fun clickByLabel(labels: List<String>): Boolean {
        val wanted = labels.map { compact(it) }.toSet()
        val roots = try {
            windows.mapNotNull { it.root }
        } catch (_: Exception) {
            emptyList()
        } + listOfNotNull(rootInActiveWindow)
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 3_000) {
                val n = queue.removeFirst()
                for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
                if (!n.isVisibleToUser) continue
                if (compact(n.contentDescription) in wanted || compact(n.text) in wanted) {
                    if (click(n)) return true
                }
            }
        }
        return false
    }

    // ── WhatsApp ────────────────────────────────────────────────────────────

    /** Waits for WhatsApp's chat screen (opened with the message filled in) and presses Send. */
    suspend fun pressWhatsAppSend(pkg: String, timeoutMs: Long = 10_000): Boolean = withContext(Dispatchers.Main) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val roots = try {
                windows.mapNotNull { it.root }
            } catch (_: Exception) {
                emptyList()
            } + listOfNotNull(rootInActiveWindow)
            for (root in roots) {
                if (root.packageName?.toString() != pkg) continue
                val byId = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull { it.isVisibleToUser }
                val target = byId ?: findByDescription(root, "send")
                if (target != null && click(target)) return@withContext true
            }
            delay(300)
        }
        false
    }

    private fun findByDescription(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.contentDescription?.toString()?.equals(desc, ignoreCase = true) == true && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return null
    }
}
