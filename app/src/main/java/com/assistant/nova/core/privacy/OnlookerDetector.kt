package com.assistant.nova.core.privacy

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** One face from the front camera, in the upright image's pixels. */
data class SeenFace(
    /** The detector's own id for this person (not relied on: see [OnlookerDetector]). */
    val id: Int?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Head turned left/right, degrees (0 = facing the camera). */
    val yaw: Float,
    /** Head tilted up/down, degrees (0 = facing the camera). */
    val pitch: Float,
    /** 0..1, null if unknown. */
    val leftEyeOpen: Float? = null,
    val rightEyeOpen: Float? = null,
) {
    val width: Float get() = right - left
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    /**
     * True when [other] is this face detected a second time: a smaller box whose middle lies
     * inside this one. (A person peeking over the owner's shoulder sits beside the owner's face,
     * maybe touching it, but their middle isn't inside it.)
     */
    fun contains(other: SeenFace): Boolean =
        other.width < width * 0.7f && other.centerX in left..right && other.centerY in top..bottom
}

/**
 * Decides when someone other than the owner is looking at the screen ("shoulder surfing").
 *
 * - The owner holds the phone, so their face is the biggest one: everyone else is "other".
 * - Faces that just pass by, look elsewhere, or are too far away to read the screen don't count:
 *   an "other" face must be turned towards the phone, with eyes open, for [lookMs].
 * - People are followed by where their face is from one picture to the next (the detector's
 *   own ids sometimes stop coming, which made it go quiet until restarted).
 * - One alert per person, repeated only if they keep looking after [repeatMs]; never more often
 *   than [cooldownMs] in total.
 *
 * Pure Kotlin so it can be tested on a PC. Not thread safe.
 */
class OnlookerDetector(
    private val lookMs: Long = 1_000,
    private val gapMs: Long = 800,
    private val cooldownMs: Long = 10_000,
    private val repeatMs: Long = 30_000,
    private val maxYaw: Float = 30f,
    private val maxPitch: Float = 30f,
    /** Smaller than this share of the picture's width = too far away to read the screen. */
    private val minWidthShare: Float = 0.05f,
) {
    private class Track(val id: Int, var x: Float, var y: Float, var lastSeen: Long, var lookingSince: Long?, var alertedAt: Long?)

    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private var lastAlert = Long.MIN_VALUE / 2

    /** Why a face doesn't count as looking (null = it does). For the log. */
    fun notLookingBecause(f: SeenFace, frameWidth: Int): String? {
        if (frameWidth <= 0 || f.width / frameWidth < minWidthShare) return "too far away"
        if (abs(f.yaw) > maxYaw) return "turned sideways (%.0f°)".format(f.yaw)
        if (abs(f.pitch) > maxPitch) return "looking up/down (%.0f°)".format(f.pitch)
        val eyes = listOfNotNull(f.leftEyeOpen, f.rightEyeOpen)
        if (eyes.isNotEmpty() && eyes.max() < 0.4f) return "eyes closed"
        return null
    }

    fun isLooking(f: SeenFace, frameWidth: Int): Boolean = notLookingBecause(f, frameWidth) == null

    /** The faces other than the owner (the biggest), minus the owner's own face detected twice. */
    fun others(faces: List<SeenFace>): List<SeenFace> {
        val owner = faces.maxByOrNull { it.width } ?: return emptyList()
        return faces.filter { it !== owner && !owner.contains(it) }
    }

    /** The faces that count as "someone else looking" in this frame. */
    fun onlookers(faces: List<SeenFace>, frameWidth: Int): List<SeenFace> = others(faces).filter { isLooking(it, frameWidth) }

    /**
     * Feed one frame's faces. Returns true when the user should be warned now.
     * [now] is a monotonic clock in milliseconds.
     */
    fun update(faces: List<SeenFace>, frameWidth: Int, now: Long): Boolean {
        var alert = false
        val used = HashSet<Track>()
        for (f in others(faces)) {
            // The same person = the nearest face seen a moment ago, not too far from here
            val near = tracks
                .filter { it !in used && now - it.lastSeen <= gapMs }
                .minByOrNull { hypot(it.x - f.centerX, it.y - f.centerY) }
                ?.takeIf { hypot(it.x - f.centerX, it.y - f.centerY) <= max(f.width, frameWidth * 0.15f) }
            val t = near ?: Track(nextId++, f.centerX, f.centerY, now, null, null).also { tracks += it }
            used += t
            t.x = f.centerX; t.y = f.centerY; t.lastSeen = now
            if (!isLooking(f, frameWidth)) {
                t.lookingSince = null
                continue
            }
            val since = t.lookingSince ?: now.also { t.lookingSince = it }
            val due = t.alertedAt?.let { now - it >= repeatMs } ?: true
            if (now - since >= lookMs && due && now - lastAlert >= cooldownMs) {
                alert = true
                t.alertedAt = now
                lastAlert = now
            }
        }
        // People who left (or looked away long enough) start from zero next time
        tracks.removeAll { now - it.lastSeen > gapMs }
        return alert
    }

    fun reset() {
        tracks.clear()
    }
}
