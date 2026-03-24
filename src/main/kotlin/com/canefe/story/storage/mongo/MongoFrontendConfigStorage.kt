package com.canefe.story.storage.mongo

import com.canefe.story.api.character.FrontendConfig
import com.canefe.story.storage.MongoClientManager
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document
import java.util.logging.Logger

/**
 * MongoDB storage for the `frontend_config` collection.
 * Stores per-character, per-frontend configuration (avatar, displayHandle,
 * citizensNpcId, minecraftUuid, etc.).
 */
class MongoFrontendConfigStorage(
    private val mongoClient: MongoClientManager,
    private val logger: Logger,
) {
    private val collection get() = mongoClient.getCollection("frontend_config")

    fun find(
        characterId: String,
        frontend: String,
    ): FrontendConfig? {
        val doc =
            collection
                .find(
                    Filters.and(
                        Filters.eq("characterId", characterId),
                        Filters.eq("frontend", frontend),
                    ),
                ).first() ?: return null
        return documentToConfig(doc)
    }

    fun findAllByFrontend(frontend: String): List<FrontendConfig> =
        collection
            .find(Filters.eq("frontend", frontend))
            .mapNotNull { documentToConfig(it) }
            .toList()

    fun findAllByCharacter(characterId: String): List<FrontendConfig> =
        collection
            .find(Filters.eq("characterId", characterId))
            .mapNotNull { documentToConfig(it) }
            .toList()

    fun findByProperty(
        frontend: String,
        key: String,
        value: Any,
    ): FrontendConfig? {
        val doc =
            collection
                .find(
                    Filters.and(
                        Filters.eq("frontend", frontend),
                        Filters.eq("properties.$key", value),
                    ),
                ).first() ?: return null
        return documentToConfig(doc)
    }

    fun save(config: FrontendConfig) {
        val doc = configToDocument(config)
        collection.replaceOne(
            Filters.and(
                Filters.eq("characterId", config.characterId),
                Filters.eq("frontend", config.frontend),
            ),
            doc,
            ReplaceOptions().upsert(true),
        )
    }

    fun delete(
        characterId: String,
        frontend: String,
    ) {
        collection.deleteOne(
            Filters.and(
                Filters.eq("characterId", characterId),
                Filters.eq("frontend", frontend),
            ),
        )
    }

    fun deleteAllForCharacter(characterId: String) {
        collection.deleteMany(Filters.eq("characterId", characterId))
    }

    private fun configToDocument(config: FrontendConfig): Document =
        Document().apply {
            put("characterId", config.characterId)
            put("frontend", config.frontend)
            put("properties", Document(config.properties.mapValues { (_, v) -> v }))
        }

    private fun documentToConfig(doc: Document): FrontendConfig? {
        return try {
            val propsDoc = doc.get("properties", Document::class.java) ?: Document()
            FrontendConfig(
                characterId = doc.getString("characterId") ?: return null,
                frontend = doc.getString("frontend") ?: return null,
                properties = propsDoc.toMap(),
            )
        } catch (e: Exception) {
            logger.warning("Failed to deserialize frontend_config document: ${e.message}")
            null
        }
    }
}
