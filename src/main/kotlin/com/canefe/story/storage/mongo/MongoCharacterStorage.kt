package com.canefe.story.storage.mongo

import com.canefe.story.api.character.CharacterRecord
import com.canefe.story.api.character.CharacterRecord.CharacterType
import com.canefe.story.storage.MongoClientManager
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document
import java.util.logging.Logger

/**
 * MongoDB storage for the `characters` collection — the shared source of truth
 * for character identity across all frontends. No frontend-specific fields.
 */
class MongoCharacterStorage(
    private val mongoClient: MongoClientManager,
    private val logger: Logger,
) {
    private val collection get() = mongoClient.getCollection("characters")

    fun findById(characterId: String): CharacterRecord? {
        val doc = collection.find(Filters.eq("_id", characterId)).first() ?: return null
        return documentToRecord(doc)
    }

    fun findByName(name: String): CharacterRecord? {
        val doc = collection.find(Filters.regex("name", "^${Regex.escape(name)}$", "i")).first() ?: return null
        return documentToRecord(doc)
    }

    fun findAll(): List<CharacterRecord> = collection.find().mapNotNull { documentToRecord(it) }.toList()

    fun save(record: CharacterRecord) {
        val doc = recordToDocument(record)
        collection.replaceOne(
            Filters.eq("_id", record.id),
            doc,
            ReplaceOptions().upsert(true),
        )
    }

    fun delete(characterId: String) {
        collection.deleteOne(Filters.eq("_id", characterId))
    }

    private fun recordToDocument(record: CharacterRecord): Document =
        Document().apply {
            put("_id", record.id)
            put("name", record.name)
            put("race", record.race)
            put("appearance", record.appearance)
            put("traits", record.traits)
            put("type", record.type.name.lowercase())
        }

    private fun documentToRecord(doc: Document): CharacterRecord? {
        return try {
            CharacterRecord(
                id = doc.getString("_id"),
                name = doc.getString("name") ?: return null,
                race = doc.getString("race"),
                appearance = doc.getString("appearance") ?: "",
                traits = doc.getList("traits", String::class.java) ?: emptyList(),
                type =
                    doc.getString("type")?.let {
                        try {
                            CharacterType.valueOf(it.uppercase())
                        } catch (_: Exception) {
                            CharacterType.NPC
                        }
                    } ?: CharacterType.NPC,
            )
        } catch (e: Exception) {
            logger.warning("Failed to deserialize character document: ${e.message}")
            null
        }
    }
}
