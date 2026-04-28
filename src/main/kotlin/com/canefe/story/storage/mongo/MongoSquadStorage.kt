package com.canefe.story.storage.mongo

import com.canefe.story.api.squad.SquadRecord
import com.canefe.story.storage.MongoClientManager
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import kotlinx.serialization.json.Json
import org.bson.Document
import java.util.logging.Logger

/**
 * MongoDB storage for the `squads` collection.
 */
class MongoSquadStorage(
    private val mongoClient: MongoClientManager,
    private val logger: Logger,
) {
    private val collection get() = mongoClient.getCollection("squads")
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun findById(id: String): SquadRecord? =
        collection.find(Filters.eq("_id", id)).first()?.let { deserialize(it) }

    fun findByOwnerAndName(ownerCharacterId: String, name: String): SquadRecord? {
        val doc =
            collection
                .find(
                    Filters.and(
                        Filters.eq("ownerCharacterId", ownerCharacterId),
                        Filters.regex("name", "^${Regex.escape(name)}$", "i"),
                    ),
                ).first() ?: return null
        return deserialize(doc)
    }

    fun findAllOwnedBy(ownerCharacterId: String): List<SquadRecord> =
        collection
            .find(Filters.eq("ownerCharacterId", ownerCharacterId))
            .mapNotNull { deserialize(it) }
            .toList()

    fun findAllSharedWith(characterId: String): List<SquadRecord> =
        collection
            .find(Filters.`in`("sharedWithCharacterIds", characterId))
            .mapNotNull { deserialize(it) }
            .toList()

    fun findAllContainingMember(characterId: String): List<SquadRecord> =
        collection
            .find(Filters.`in`("memberCharacterIds", characterId))
            .mapNotNull { deserialize(it) }
            .toList()

    fun findAll(): List<SquadRecord> = collection.find().mapNotNull { deserialize(it) }.toList()

    fun save(record: SquadRecord) {
        val doc = serialize(record)
        collection.replaceOne(
            Filters.eq("_id", record.id),
            doc,
            ReplaceOptions().upsert(true),
        )
    }

    fun delete(id: String) {
        collection.deleteOne(Filters.eq("_id", id))
    }

    private fun serialize(record: SquadRecord): Document {
        val jsonStr = json.encodeToString(SquadRecord.serializer(), record)
        val doc = Document.parse(jsonStr)
        doc["_id"] = record.id
        doc.remove("id")
        return doc
    }

    private fun deserialize(doc: Document): SquadRecord? =
        try {
            val id = doc.getString("_id") ?: return null
            doc["id"] = id
            json.decodeFromString(SquadRecord.serializer(), doc.toJson())
        } catch (e: Exception) {
            logger.warning("Failed to deserialize squad document: ${e.message}")
            null
        }
}
