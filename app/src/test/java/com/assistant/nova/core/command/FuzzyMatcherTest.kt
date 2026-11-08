package com.assistant.nova.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuzzyMatcherTest {

    private val contacts = listOf("Amma", "Appa", "Aravind Kumar", "Karthik R", "Priya College", "Rahul Sharma", "Raghul", "Dr. Mehta Clinic")
    private fun best(q: String) = FuzzyMatcher.best(q, contacts) { it }?.item

    @Test fun `exact and partial names`() {
        assertEquals("Rahul Sharma", best("rahul sharma"))
        assertEquals("Rahul Sharma", best("rahul"))
        assertEquals("Priya College", best("priya"))
        assertEquals("Dr. Mehta Clinic", best("mehta clinic"))
    }

    @Test fun `speech recognition misspellings`() {
        assertEquals("Aravind Kumar", best("arvind"))
        assertEquals("Karthik R", best("kartik"))
        assertEquals("Raghul", best("raghul"))
    }

    @Test fun `family words`() {
        assertEquals("Amma", best("mom"))
        assertEquals("Appa", best("dad"))
        assertEquals("Amma", best("mother"))
    }

    @Test fun `unknown names are rejected`() {
        assertNull(best("zebra"))
        assertNull(best("xyz"))
    }

    @Test fun `app names`() {
        val apps = listOf("YouTube", "YouTube Music", "WhatsApp", "Play Store", "Camera", "Calculator", "Instagram")
        assertEquals("YouTube", FuzzyMatcher.best("you tube", apps) { it }?.item)
        assertEquals("YouTube Music", FuzzyMatcher.best("youtube music", apps) { it }?.item)
        assertEquals("Instagram", FuzzyMatcher.best("insta", apps) { it }?.item)
        assertEquals("Calculator", FuzzyMatcher.best("calculator", apps) { it }?.item)
        assertEquals("WhatsApp", FuzzyMatcher.best("whatsapp", apps) { it }?.item)
    }
}
