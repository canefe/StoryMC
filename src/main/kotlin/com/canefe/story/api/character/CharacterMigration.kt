package com.canefe.story.api.character

import com.canefe.story.Story
import com.canefe.story.storage.mongo.MongoCharacterStorage
import com.canefe.story.storage.mongo.MongoFrontendConfigStorage
import java.util.logging.Logger

/**
 * One-time migration: populates the `characters` and `frontend_config` collections
 * from the existing `npcs` collection when the characters collection is empty.
 */
object CharacterMigration {
    fun migrateIfNeeded(
        plugin: Story,
        characterStorage: MongoCharacterStorage,
        frontendConfigStorage: MongoFrontendConfigStorage,
        logger: Logger,
    ) {
        // Only migrate if characters collection is empty
        if (characterStorage.findAll().isNotEmpty()) return

        val allNpcNames = plugin.npcDataManager.getAllNPCNames()
        if (allNpcNames.isEmpty()) {
            logger.info("No existing NPC data to migrate to characters collection")
            return
        }

        logger.info("Migrating ${allNpcNames.size} NPCs to characters collection...")

        var migrated = 0
        for (npcName in allNpcNames) {
            val npcData = plugin.npcDataManager.getNPCData(npcName) ?: continue
            val characterId = CharacterId.generate(npcData.name)

            // Create character record
            val record =
                CharacterRecord(
                    id = characterId,
                    name = npcData.name,
                    race = null,
                    appearance = npcData.appearance,
                    traits = emptyList(),
                    type = CharacterRecord.CharacterType.NPC,
                )
            characterStorage.save(record)

            // Create Minecraft frontend config
            val frontendConfig =
                FrontendConfig.minecraft(
                    characterId = characterId,
                    citizensUuid =
                        npcData.npcId?.let {
                            try {
                                java.util.UUID.fromString(it)
                            } catch (_: Exception) {
                                null
                            }
                        },
                    avatar = npcData.avatar.ifBlank { null },
                    displayHandle = npcData.displayHandle,
                    randomPathing = npcData.randomPathing,
                )
            frontendConfigStorage.save(frontendConfig)

            migrated++
            logger.info("  Migrated NPC '${npcData.name}' → $characterId")
        }

        logger.info("Migration complete: $migrated characters created")
    }
}
