package com.canefe.story.bridge

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NpcSpawnIntentRefusalTest {
    @Test
    fun `refuses non-uuid characterId`() {
        val reason = IntentExecutor.npcSpawnRefusalReason(characterId = "not-a-uuid", hasRecord = true)
        assertEquals("characterId is not a canonical UUID", reason)
    }

    @Test
    fun `refuses valid uuid with no character record`() {
        val reason = IntentExecutor.npcSpawnRefusalReason(
            characterId = "11111111-1111-1111-1111-111111111111",
            hasRecord = false,
        )
        assertEquals("no CharacterRecord for characterId", reason)
    }

    @Test
    fun `allows valid uuid with a record`() {
        val reason = IntentExecutor.npcSpawnRefusalReason(
            characterId = "11111111-1111-1111-1111-111111111111",
            hasRecord = true,
        )
        assertNull(reason)
    }
}
