package com.canefe.story.api.character

import com.canefe.story.api.StoryNPC
import com.canefe.story.storage.mongo.MongoCharacterStorage
import com.canefe.story.storage.mongo.MongoFrontendConfigStorage
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetByStoryNpcTest {
    private data class RegistryWithLogs(
        val registry: CharacterRegistry,
        val records: MutableList<LogRecord>,
    )

    private fun registry(records: List<CharacterRecord>): RegistryWithLogs {
        val charStorage = mockk<MongoCharacterStorage>(relaxed = true)
        val feStorage = mockk<MongoFrontendConfigStorage>(relaxed = true)
        every { charStorage.findAll() } returns records
        every { feStorage.findAllByFrontend(any()) } returns emptyList()

        // Create a logger with a capturing handler
        val logRecords = mutableListOf<LogRecord>()
        val logger = Logger.getLogger("GetByStoryNpcTest-" + UUID.randomUUID())
        logger.useParentHandlers = false
        logger.level = Level.ALL
        logger.addHandler(object : Handler() {
            override fun publish(record: LogRecord) {
                logRecords.add(record)
            }

            override fun flush() {}

            override fun close() {}
        })

        val reg = CharacterRegistry(charStorage, feStorage, logger)
        reg.loadAll()
        return RegistryWithLogs(reg, logRecords)
    }

    private fun npcWithCharacterId(name: String, charId: String?): StoryNPC {
        val npc = mockk<StoryNPC>(relaxed = true)
        every { npc.name } returns name
        every { npc.uniqueId } returns UUID.randomUUID()
        every { npc.id } returns -1
        every { npc.characterId } returns charId
        return npc
    }

    @Test
    fun `resolves by characterId PDC, not by name`() {
        val rec = CharacterRecord(id = "char-abc", name = "Tobin")
        val (reg, logRecords) = registry(listOf(rec))
        // NPC reports a DIFFERENT name than the record but the right characterId.
        val npc = npcWithCharacterId(name = "WrongName", charId = "char-abc")
        assertEquals(rec, reg.getByStoryNPC(npc))
        // Should not have triggered NAME fallback warning
        assertTrue(logRecords.none { it.level == Level.WARNING && it.message.contains("NAME fallback") })
    }

    @Test
    fun `falls back to name when characterId is null`() {
        val rec = CharacterRecord(id = "char-xyz", name = "Maja")
        val (reg, logRecords) = registry(listOf(rec))
        val npc = npcWithCharacterId(name = "Maja", charId = null)
        assertEquals(rec, reg.getByStoryNPC(npc))
        // Should have logged a WARNING containing "NAME fallback"
        assertTrue(logRecords.any { it.level == Level.WARNING && it.message.contains("NAME fallback") })
    }
}
