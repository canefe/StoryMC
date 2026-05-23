package com.canefe.story.bridge

import io.mockk.mockk
import org.bukkit.plugin.java.JavaPlugin
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Regression test for the "DM Override Gate" integration bug: DMControlToggleEvent
 * was NOT registered in [WebSocketTransport.serializeEvent]'s `when` block, so it
 * fell through to the `else -> {"raw": ...}` fallback. The event reached story-go
 * with empty data ({"raw":"dm.control.toggle"}) — missing characterId and grabbed —
 * leaving the grab registry un-updatable and the whole feature silently inert.
 *
 * This exercises the REAL [WebSocketTransport.serializeEvent] (made `internal` for
 * test access), so it fails before the registration arm is added and passes after.
 */
class DMControlToggleSerializationTest {
    private val transport = WebSocketTransport(mockk<JavaPlugin>(relaxed = true), "ws://test")

    @Test
    fun `serializeEvent emits real characterId and grabbed (not raw fallback)`() {
        val element = transport.serializeEvent(DMControlToggleEvent(characterId = "npc-123", grabbed = true))
        val obj = element.jsonObject

        assertFalse(obj.containsKey("raw"), "must not hit the {\"raw\":...} fallback: $obj")
        assertEquals("npc-123", obj["characterId"]?.jsonPrimitive?.content, "characterId field: $obj")
        assertEquals("true", obj["grabbed"]?.jsonPrimitive?.content, "grabbed field: $obj")
    }

    @Test
    fun `DMControlToggleEvent eventType is dm dot control dot toggle`() {
        assertEquals("dm.control.toggle", DMControlToggleEvent("x", false).eventType)
    }
}
