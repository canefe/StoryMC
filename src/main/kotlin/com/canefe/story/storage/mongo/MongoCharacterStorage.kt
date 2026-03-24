package com.canefe.story.storage.mongo

import com.canefe.story.api.character.CharacterRecord
import com.canefe.story.storage.MongoClientManager
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import kotlinx.serialization.json.Json
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
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun findById(characterId: String): CharacterRecord? {
        val doc = collection.find(Filters.eq("_id", characterId)).first() ?: return null
        return deserialize(doc)
    }

    fun findByName(name: String): CharacterRecord? {
        val doc = collection.find(Filters.regex("name", "^${Regex.escape(name)}$", "i")).first() ?: return null
        return deserialize(doc)
    }

    fun findAll(): List<CharacterRecord> = collection.find().mapNotNull { deserialize(it) }.toList()

    fun save(record: CharacterRecord) {
        val doc = serialize(record)
        collection.replaceOne(
            Filters.eq("_id", record.id),
            doc,
            ReplaceOptions().upsert(true),
        )
    }

    fun delete(characterId: String) {
        collection.deleteOne(Filters.eq("_id", characterId))
    }

    private fun serialize(record: CharacterRecord): Document {
        val jsonStr = json.encodeToString(CharacterRecord.serializer(), record)
        val doc = Document.parse(jsonStr)
        // MongoDB uses _id as primary key
        doc["_id"] = record.id
        doc.remove("id")
        return doc
    }

    private fun deserialize(doc: Document): CharacterRecord? {
        return try {
            // Map _id back to id field
            val id = doc.getString("_id") ?: return null
            doc["id"] = id
            json.decodeFromString(CharacterRecord.serializer(), doc.toJson())
        } catch (e: Exception) {
            logger.warning("Failed to deserialize character document: ${e.message}")
            null
        }
    }
}
