package com.canefe.story.bridge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

class AuthoringIntentWireTest {

    @Test
    fun npcKnowUsesSnakeCaseKeys() {
        val j = Json.encodeToString(NpcKnowIntent("npc_x", "market"))
        assertTrue(j.contains("\"character_id\""), "expected character_id, got: $j")
        assertTrue(j.contains("\"location_id\""), "expected location_id, got: $j")
    }

    @Test
    fun locationSpawnUsesSnakeCaseInstanceName() {
        val j = Json.encodeToString(
            LocationSpawnIntent(id = "loc1", instanceName = "inn_cellar", x = 0.0, y = 64.0, z = 0.0),
        )
        assertTrue(j.contains("\"instance_name\""), "expected instance_name, got: $j")
    }

    @Test
    fun locationSpawnCarriesTemplate() {
        val j = Json.encodeToString(
            LocationSpawnIntent(
                id = "The Prancing Pony",
                instanceName = "The Prancing Pony",
                template = "tavern_common_room",
                x = 0.0, y = 64.0, z = 0.0,
            ),
        )
        assertTrue(j.contains("\"template\":\"tavern_common_room\""), "expected template, got: $j")
    }

    @Test
    fun npcSetOffersUsesSnakeCaseCharacterId() {
        val j = Json.encodeToString(NpcSetOffersIntent("char1", "Merchant", "[]"))
        assertTrue(j.contains("\"character_id\""), "expected character_id, got: $j")
    }

    @Test
    fun npcGiveItemUsesSnakeCaseCharacterId() {
        val j = Json.encodeToString(NpcGiveItemIntent("char1", "Smith", "iron_sword", 1))
        assertTrue(j.contains("\"character_id\""), "expected character_id, got: $j")
    }

    @Test
    fun npcGiveTraitUsesSnakeCaseCharacterId() {
        val j = Json.encodeToString(NpcGiveTraitIntent("char1", "Elder", "wise"))
        assertTrue(j.contains("\"character_id\""), "expected character_id, got: $j")
    }

    @Test
    fun npcSetNeedUsesSnakeCaseCharacterId() {
        val j = Json.encodeToString(NpcSetNeedIntent("char1", "Guard", "hunger", 0.5))
        assertTrue(j.contains("\"character_id\""), "expected character_id, got: $j")
    }

    @Test
    fun npcSetStatUsesSnakeCaseCharacterId() {
        val j = Json.encodeToString(NpcSetStatIntent("char1", "Warrior", "strength", 10.0))
        assertTrue(j.contains("\"character_id\""), "expected character_id, got: $j")
    }
}
