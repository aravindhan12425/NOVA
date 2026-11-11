package com.assistant.nova.core.privacy

import kotlin.math.abs
import kotlin.math.max

/** One face from the front camera, in the upright image's pixels. */
data class SeenFace(
    /** Same number for the same person across frames (null if the detector couldn't track it). */
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
}

/**
 * Decides when someone other than the owner is looking at the screen ("shoulder surfing").
 *
 * - The owner holds the phone, so their face is the biggest one: everyone else is "other".
 * - Faces that just pass by, look elsewhere, or are too far away to read the screen don't count:
 *   an "other" face must be turned towards the phone, with eyes open, for [lookMs].
 * - One alert per person, repeated only if they keep looking after [repeatMs]; never more often
 *   than [cooldownMs] in total (crowds shouldn't make the phone buzz all the time).
 *
 * Pure Kotlin so it can be tested on a PC. Not thread safe.
 */
class OnlookerDetector(
    private val lookMs: Long = 1_500,
    private val gapMs: Long = 800,
    private val cooldownMs: Long = 15_000,
    private val repeatMs: Long = 60_000,
    private val maxYaw: Float = 25f,
    private val maxPitch: Float = 25f,
    /** Smaller than this share of the picture's width = too far away to read the screen. */
    private val minWidthShare: Float = 0.05f,
) {
    private val lookingSince = HashMap<Int, Long>()
    private val lastLooking = HashMap<Int, Long>()
    private val alertedAt = HashMap<Int, Long>()
    private var lastAlert = Long.MIN_VALUE / 2

    /** Is this face turned towards the phone with its eyes open? */
    fun isLooking(f: SeenFace, frameWidth: Int): Boolean {
        if (frameWidth <= 0 || f.width / frameWidth < minWidthShare) return false
        if (abs(f.yaw) > maxYaw || abs(f.pitch) > maxPitch) return false
        val eyes = listOfNotNull(f.leftEyeOpen, f.rightEyeOpen)
        return eyes.isEmpty() || eyes.max() >= 0.4f
    }

    /**
     * Feed one frame's faces. Returns true when the user should be warned now.
     * [now] is a monotonic clock in milliseconds.
     */
    fun update(faces: List<SeenFace>, frameWidth: Int, now: Long): Boolean {
        val owner = faces.maxByOrNull { it.width }
        var alert = false
        for (f in faces) {
            if (f === owner) continue
            val id = f.id ?: continue // can't tell if it's the same person over time
            if (!isLooking(f, frameWidth)) continue
            lastLooking[id] = now
            val since = lookingSince.getOrPut(id) { now }
            val already = alertedAt[id]
            val due = already == null || now - already >= repeatMs
            if (now - since >= lookMs && due && now - lastAlert >= cooldownMs) {
                alert = true
                alertedAt[id] = now
                lastAlert = now
            }
        }
        // Someone who looked away (or left) starts from zero next time
        val it = lookingSince.keys.iterator()
        while (it.hasNext()) {
            val id = it.next()
            if (now - (lastLooking[id] ?: 0L) > gapMs) {
                it.remove()
                lastLooking.remove(id)
            }
        }
        alertedAt.entries.removeAll { now - it.value > max(repeatMs, cooldownMs) * 2 }
        return alert
    }

    fun reset() {
        lookingSince.clear()
        lastLooking.clear()
        alertedAt.clear()
    }
}
