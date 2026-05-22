package com.canefe.story.storage.mongo

import com.canefe.story.storage.CharacterDataDocument
import com.canefe.story.storage.MongoClientManager
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import kotlinx.serialization.json.Json
import org.bson.Document
import java.util.logging.Logger

class MongoCharacterDataStorage(
    private val mongoClient: MongoClientManager,
    private val logger: Logger,
) {
    private val collection get() = mongoClient.getCollection("character_data")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun findById(id: String): CharacterDataDocument? {
        val doc = collection.find(Filters.eq("_id", id)).first() ?: return null
        return deserialize(doc)
    }

    fun save(record: CharacterDataDocument) {
        val jsonStr = json.encodeToString(CharacterDataDocument.serializer(), record)
        val doc = Document.parse(jsonStr)
        doc["_id"] = record.id
        doc.remove("id")
        collection.replaceOne(Filters.eq("_id", record.id), doc, ReplaceOptions().upsert(true))
    }

    /** Read-modify-write helper so command handlers can update one field. */
    fun update(id: String, mutate: (CharacterDataDocument) -> CharacterDataDocument) {
        val current = findById(id) ?: CharacterDataDocument(id = id)
        save(mutate(current))
    }

    private fun deserialize(doc: Document): CharacterDataDocument? = try {
        val id = doc.getString("_id") ?: return null
        doc["id"] = id
        json.decodeFromString(CharacterDataDocument.serializer(), doc.toJson())
    } catch (e: Exception) {
        logger.warning("Failed to deserialize character_data: ${e.message}")
        null
    }
}
