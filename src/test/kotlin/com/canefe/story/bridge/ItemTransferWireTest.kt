package com.canefe.story.bridge

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemTransferWireTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun npcItemTransferJsonDecodesIntoIntent() {
        val payload = """
            {"fromCharacterId":"a","toCharacterId":"b","item":"bread","qty":3,"reason":"trade"}
        """.trimIndent()
        val intent = json.decodeFromString<NpcItemTransferIntent>(payload)
        assertEquals("a", intent.fromCharacterId)
        assertEquals("b", intent.toCharacterId)
        assertEquals("bread", intent.item)
        assertEquals(3, intent.qty)
        assertEquals("trade", intent.reason)
        assertEquals("npc.item_transfer", intent.eventType)
    }

    @Test
    fun reasonDefaultsToGiveWhenAbsent() {
        val intent = json.decodeFromString<NpcItemTransferIntent>(
            """{"fromCharacterId":"a","toCharacterId":"b","item":"coin","qty":1}""",
        )
        assertEquals("give", intent.reason)
    }

    @Test
    fun npcStateDecodesOptionalActionFields() {
        val intent = json.decodeFromString<NpcStateIntent>(
            """{"characterId":"c","name":"N","x":1.0,"y":2.0,"z":3.0,"actionId":"buy_item","actionLabel":"Buying"}""",
        )
        assertEquals("buy_item", intent.actionId)
        assertEquals("Buying", intent.actionLabel)
    }

    @Test
    fun npcStateActionFieldsNullWhenAbsent() {
        val intent = json.decodeFromString<NpcStateIntent>(
            """{"characterId":"c","name":"N","x":1.0,"y":2.0,"z":3.0}""",
        )
        assertEquals(null, intent.actionId)
        assertEquals(null, intent.actionLabel)
    }
}
