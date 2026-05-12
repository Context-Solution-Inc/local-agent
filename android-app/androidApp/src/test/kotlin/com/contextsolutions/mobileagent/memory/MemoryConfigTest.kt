package com.contextsolutions.mobileagent.memory

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryConfigTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun default_thresholds_match_pr_spec() {
        val d = MemoryConfig.DEFAULT.thresholds
        assertEquals(0.85f, d.autoSave, 1e-6f)
        assertEquals(0.15f, d.ask, 1e-6f)
        assertEquals(0.5f, d.category, 1e-6f)
    }

    @Test
    fun round_trips_through_json() {
        val raw = """
            {
              "model_version": "preflight_memory_shared_v1.0.0",
              "thresholds": { "auto_save": 0.85, "ask": 0.15, "category": 0.5 }
            }
        """.trimIndent()
        val parsed = json.decodeFromString(MemoryConfig.serializer(), raw)
        assertEquals(MemoryConfig.DEFAULT, parsed)
    }

    @Test
    fun rejects_ask_above_auto_save() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryThresholds(autoSave = 0.4f, ask = 0.5f, category = 0.5f)
        }
    }

    @Test
    fun rejects_out_of_range_thresholds() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryThresholds(autoSave = 1.5f, ask = 0.15f, category = 0.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemoryThresholds(autoSave = 0.85f, ask = -0.1f, category = 0.5f)
        }
    }
}
