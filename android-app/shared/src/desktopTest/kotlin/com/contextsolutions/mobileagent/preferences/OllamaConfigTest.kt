package com.contextsolutions.mobileagent.preferences

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PR #56 — [OllamaConfig] URL building, configured-gate, and model selection. */
class OllamaConfigTest {

    @Test
    fun isConfiguredRequiresHostPortAndChatModel() {
        assertFalse(OllamaConfig.EMPTY.isConfigured)
        assertFalse(OllamaConfig(host = "1.2.3.4", port = 11434).isConfigured)
        assertFalse(OllamaConfig(host = "1.2.3.4", chatModel = "m").isConfigured)
        assertTrue(OllamaConfig(host = "1.2.3.4", port = 11434, chatModel = "m").isConfigured)
    }

    @Test
    fun baseUrlPrependsHttpScheme() {
        assertEquals("http://192.168.1.50:11434", OllamaConfig(host = "192.168.1.50", port = 11434).baseUrl())
        assertNull(OllamaConfig(host = "", port = 11434).baseUrl())
        assertNull(OllamaConfig(host = "x", port = null).baseUrl())
    }

    @Test
    fun baseUrlPreservesExplicitScheme() {
        assertEquals("https://ollama.local:443", OllamaConfig(host = "https://ollama.local", port = 443).baseUrl())
    }

    @Test
    fun modelForPicksVisionOnlyForImageTurnsWhenSet() {
        val both = OllamaConfig(host = "h", port = 1, chatModel = "chat", visionModel = "vis")
        assertEquals("chat", both.modelFor(hasImage = false))
        assertEquals("vis", both.modelFor(hasImage = true))

        // Blank vision model → image turns fall back to the chat model.
        val chatOnly = OllamaConfig(host = "h", port = 1, chatModel = "chat")
        assertEquals("chat", chatOnly.modelFor(hasImage = true))
    }
}
