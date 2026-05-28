package com.canefe.story.bridge

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NpcSpawnIntentRefusalTest {
    @Test
    fun `refuses blank characterId`() {
        val reason = IntentExecutor.npcSpawnRefusalReason(characterId = "", hasRecord = true)
        assertEquals("characterId is blank", reason)
    }

    @Test
    fun `refuses characterId with no character record`() {
        val reason = IntentExecutor.npcSpawnRefusalReason(
            characterId = "jonas_57d91261",
            hasRecord = false,
        )
        assertEquals("no CharacterRecord for characterId", reason)
    }

    @Test
    fun `allows slug-style id with a record`() {
        val reason = IntentExecutor.npcSpawnRefusalReason(
            characterId = "jonas_57d91261",
            hasRecord = true,
        )
        assertNull(reason)
    }

    @Test
    fun `allows uuid with a record`() {
        val reason = IntentExecutor.npcSpawnRefusalReason(
            characterId = "11111111-1111-1111-1111-111111111111",
            hasRecord = true,
        )
        assertNull(reason)
    }
}
