package com.assistant.nova.core.dictionary

import org.junit.Assert.assertTrue
import org.junit.Test

class MorphyTest {
    private val exceptions = mapOf("went" to listOf("go"), "better" to listOf("good", "well"))
    private fun c(w: String) = Morphy.candidates(w) { exceptions[it].orEmpty() }

    @Test fun `finds dictionary forms`() {
        assertTrue(c("vilest").contains("vile"))
        assertTrue(c("running").contains("run"))
        assertTrue(c("babies").contains("baby"))
        assertTrue(c("biggest").contains("big"))
        assertTrue(c("churches").contains("church"))
        assertTrue(c("went").contains("go"))
        assertTrue(c("better").contains("good"))
        assertTrue(c("ephemeral").first() == "ephemeral")
    }
}
