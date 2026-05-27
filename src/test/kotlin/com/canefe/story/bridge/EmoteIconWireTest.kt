package com.canefe.story.bridge

import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bukkit.plugin.java.JavaPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Verifies [NPCEmoteIconIntent] round-trips correctly on the WebSocket wire:
 *
 *  1. Outbound: [WebSocketTransport.serializeEvent] must NOT fall through to the
 *     `{raw: ...}` fallback — it must emit real `characterId` / `emoteId` fields.
 *  2. Inbound: the data class must decode from the JSON payload produced by story-go.
 */
class EmoteIconWireTest {

    private val transport = WebSocketTransport(mockk<JavaPlugin>(relaxed = true), "ws://test")

    @Test
    fun `NPCEmoteIconIntent serializes with real fields (not raw fallback)`() {
        val intent = NPCEmoteIconIntent(characterId = "npc_42", emoteId = "laugh")
        assertEquals("npc.emote_icon", intent.eventType)

        val element = transport.serializeEvent(intent)
        val obj = element.jsonObject

        assertFalse(obj.containsKey("raw"), "must not hit the {\"raw\":...} fallback: $obj")
        assertEquals("npc_42", obj["characterId"]?.jsonPrimitive?.content, "characterId field: $obj")
        assertEquals("laugh", obj["emoteId"]?.jsonPrimitive?.content, "emoteId field: $obj")
    }

    @Test
    fun `inbound npc_emote_icon JSON decodes to NPCEmoteIconIntent`() {
        val json = Json { ignoreUnknownKeys = true }
        val payload = """{"characterId":"npc_7","emoteId":"shock"}"""
        val intent = json.decodeFromString<NPCEmoteIconIntent>(payload)
        assertEquals("npc_7", intent.characterId)
        assertEquals("shock", intent.emoteId)
        assertEquals("npc.emote_icon", intent.eventType)
    }
}
