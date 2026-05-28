package com.canefe.story.bridge.combat

import com.canefe.story.bridge.BridgeMessage
import com.canefe.story.bridge.CombatAttackResolvedEvent
import com.canefe.story.bridge.CombatPlayerAttackEvent
import com.canefe.story.bridge.WebSocketTransport
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bukkit.plugin.java.JavaPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Pins the on-the-wire contract for the sim-authoritative combat round-trip:
 *
 *  - Outbound: [CombatPlayerAttackEvent] must serialize through
 *    [WebSocketTransport.serializeEvent] with real fields (not the
 *    `{"raw": ...}` fallback), matching the snake_case-canonical shape in
 *    `story-sim/docs/2026-05-27-combat-player-attack-contract.md`.
 *  - Inbound: [CombatAttackResolvedEvent] must decode from the camelCase JSON
 *    the sim emits per `docs/2026-05-27-combat-attack-resolved-contract.md`.
 */
class CombatRoundTripWireTest {
    private val transport = WebSocketTransport(mockk<JavaPlugin>(relaxed = true), "ws://test")
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `combat player_attack serializes with real fields (not raw fallback)`() {
        val event = CombatPlayerAttackEvent(
            attackerId = "char-bob",
            attackerName = "Bob",
            defenderId = "char-alice",
            defenderName = "Alice",
            weaponItem = "iron_dagger",
            tick = 42L,
        )
        assertEquals("combat.player_attack", event.eventType)

        val obj = transport.serializeEvent(event).jsonObject

        assertFalse(obj.containsKey("raw"), "must not hit the {\"raw\":...} fallback: $obj")
        assertEquals("char-bob", obj["attackerId"]?.jsonPrimitive?.content)
        assertEquals("Bob", obj["attackerName"]?.jsonPrimitive?.content)
        assertEquals("char-alice", obj["defenderId"]?.jsonPrimitive?.content)
        assertEquals("Alice", obj["defenderName"]?.jsonPrimitive?.content)
        assertEquals("iron_dagger", obj["weaponItem"]?.jsonPrimitive?.content)
        assertEquals("42", obj["tick"]?.jsonPrimitive?.content)
    }

    @Test
    fun `combat player_attack with null weapon omits or nulls weaponItem`() {
        val event = CombatPlayerAttackEvent(
            attackerId = "char-bob",
            attackerName = "Bob",
            defenderId = null,
            defenderName = "Alice",
            weaponItem = null,
        )

        val obj = transport.serializeEvent(event).jsonObject
        assertFalse(obj.containsKey("raw"))
        // weaponItem may be omitted or explicitly null — sim accepts both.
        val weapon = obj["weaponItem"]
        if (weapon != null) {
            assertEquals("null", weapon.toString(), "weaponItem must be null when unarmed")
        }
        assertEquals("Bob", obj["attackerName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `inbound combat attack_resolved JSON decodes to CombatAttackResolvedEvent`() {
        val payload = """
            {
              "attackerId": "char-bob",
              "attackerName": "Bob",
              "defenderId": "char-alice",
              "defenderName": "Alice",
              "weaponSource": "item:iron_dagger",
              "outcome": "hit",
              "damageDealt": 7.0,
              "damageType": "sharp",
              "targetPart": "left_arm",
              "cascadedParts": ["left_hand"],
              "killed": false,
              "tick": 12345
            }
        """.trimIndent()

        val event = json.decodeFromString<CombatAttackResolvedEvent>(payload)
        assertEquals("combat.attack_resolved", event.eventType)
        assertEquals("char-bob", event.attackerId)
        assertEquals("Alice", event.defenderName)
        assertEquals("hit", event.outcome)
        assertEquals(7.0f, event.damageDealt)
        assertEquals("left_arm", event.targetPart)
        assertEquals(listOf("left_hand"), event.cascadedParts)
        assertFalse(event.killed)
        assertEquals(12345L, event.tick)
    }

    @Test
    fun `miss outcome decodes with zero damage and null target_part`() {
        val payload = """
            {
              "attackerId": "char-bob",
              "attackerName": "Bob",
              "defenderId": "char-alice",
              "defenderName": "Alice",
              "weaponSource": "natural:fist",
              "outcome": "miss",
              "damageDealt": 0.0,
              "damageType": "generic",
              "targetPart": null,
              "cascadedParts": [],
              "killed": false,
              "tick": 12346
            }
        """.trimIndent()

        val event = json.decodeFromString<CombatAttackResolvedEvent>(payload)
        assertEquals("miss", event.outcome)
        assertEquals(0.0f, event.damageDealt)
        assertEquals(null, event.targetPart)
        assertEquals(emptyList(), event.cascadedParts)
    }

    @Test
    fun `bridge envelope round-trip preserves combat player_attack type`() {
        val event = CombatPlayerAttackEvent(
            attackerId = "char-bob",
            attackerName = "Bob",
            defenderId = "char-alice",
            defenderName = "Alice",
        )
        val data = transport.serializeEvent(event).jsonObject
        val envelope = BridgeMessage(type = event.eventType, data = data, source = "story")
        val serialized = json.encodeToString(BridgeMessage.serializer(), envelope)

        val decoded = json.decodeFromString(BridgeMessage.serializer(), serialized)
        assertNotNull(decoded)
        assertEquals("combat.player_attack", decoded.type)
        assertEquals("Bob", decoded.data["attackerName"]?.jsonPrimitive?.content)
    }
}
