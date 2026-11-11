package com.assistant.nova.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlookerDetectorTest {
    private val w = 480

    // The owner: big face, close to the phone
    private val owner = SeenFace(1, 120f, 150f, 360f, 420f, yaw = 3f, pitch = 5f, leftEyeOpen = 0.9f, rightEyeOpen = 0.9f)

    private fun stranger(id: Int = 2, yaw: Float = 5f, pitch: Float = 0f, eyes: Float = 0.9f, size: Float = 70f) =
        SeenFace(id, 20f, 30f, 20f + size, 30f + size, yaw, pitch, eyes, eyes)

    /** Feeds the same faces every 400 ms for [ms]; returns how many alerts were raised. */
    private fun run(d: OnlookerDetector, faces: List<SeenFace>, ms: Long, start: Long = 0): Int {
        var alerts = 0
        var t = start
        while (t <= start + ms) {
            if (d.update(faces, w, t)) alerts++
            t += 400
        }
        return alerts
    }

    @Test fun `someone looking over the shoulder for a while`() {
        val d = OnlookerDetector()
        assertEquals(1, run(d, listOf(owner, stranger()), 3_000))
    }

    @Test fun `a quick glance is not enough`() {
        val d = OnlookerDetector()
        assertEquals(0, run(d, listOf(owner, stranger()), 800))
    }

    @Test fun `works without the detector's ids`() {
        val d = OnlookerDetector()
        assertEquals(1, run(d, listOf(owner, stranger().copy(id = null)), 3_000))
    }

    @Test fun `someone right beside the owner still counts`() {
        val d = OnlookerDetector()
        // Their face box touches the owner's (heads together), but its middle is outside it
        val beside = SeenFace(null, 330f, 160f, 450f, 290f, yaw = 10f, pitch = 5f, leftEyeOpen = 0.8f, rightEyeOpen = 0.8f)
        assertEquals(1, run(d, listOf(owner, beside), 3_000))
    }

    @Test fun `the owner alone never triggers`() {
        val d = OnlookerDetector()
        assertEquals(0, run(d, listOf(owner), 10_000))
        // Even when the owner's face is small (phone on a stand)
        assertEquals(0, run(d, listOf(stranger(id = 9)), 10_000))
    }

    @Test fun `crowds that are not looking are ignored`() {
        val d = OnlookerDetector()
        val crowd = listOf(
            owner,
            stranger(2, yaw = 60f), // looking sideways
            stranger(3, pitch = 40f), // looking down at their own phone
            stranger(4, eyes = 0.05f), // eyes closed (sleeping on the bus)
            stranger(5, size = 15f), // far away, can't read the screen
        )
        assertEquals(0, run(d, crowd, 10_000))
    }

    @Test fun `one alert per person, not a buzz every second`() {
        val d = OnlookerDetector()
        assertEquals(1, run(d, listOf(owner, stranger()), 30_000))
        // Still looking: reminded every 30 s
        assertEquals(1, run(d, listOf(owner, stranger()), 29_000, start = 30_400))
    }

    @Test fun `the owner's face detected twice is not a second person`() {
        val d = OnlookerDetector()
        val inside = SeenFace(7, 200f, 250f, 280f, 330f, yaw = 2f, pitch = 3f, leftEyeOpen = 0.9f, rightEyeOpen = 0.9f)
        assertEquals(0, run(d, listOf(owner, inside), 10_000))
    }

    @Test fun `looking away resets the timer`() {
        val d = OnlookerDetector()
        assertFalse(d.update(listOf(owner, stranger()), w, 0))
        assertFalse(d.update(listOf(owner, stranger()), w, 600))
        assertFalse(d.update(listOf(owner), w, 1_600)) // looked away
        assertFalse(d.update(listOf(owner, stranger()), w, 2_000))
        assertFalse(d.update(listOf(owner, stranger()), w, 2_600))
        assertTrue(d.update(listOf(owner, stranger()), w, 3_000))
    }
}
