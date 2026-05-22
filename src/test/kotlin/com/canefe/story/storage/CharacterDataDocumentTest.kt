package com.canefe.story.storage

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CharacterDataDocumentTest {
    @Test
    fun roundTripsSnapshot() {
        val doc = CharacterDataDocument(
            id = "npc_x",
            needValues = mapOf("hunger" to 30.0),
            traits = listOf("Generous"),
        )
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val s = json.encodeToString(CharacterDataDocument.serializer(), doc)
        val back = json.decodeFromString(CharacterDataDocument.serializer(), s)
        assertEquals(doc, back)
    }
}
