package com.canefe.story.storage.mongo

import com.canefe.story.npc.relationship.Relationship
import com.canefe.story.storage.MongoClientManager
import com.canefe.story.storage.RelationshipStorage
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document
import java.util.concurrent.ConcurrentHashMap

class MongoRelationshipStorage(
    private val mongoClient: MongoClientManager,
) : RelationshipStorage {
    private val collection get() = mongoClient.getCollection("relationships")

    override fun loadAllRelationships(): Map<String, MutableMap<String, Relationship>> {
        val relationships = mutableMapOf<String, MutableMap<String, Relationship>>()

        for (doc in collection.find()) {
            val sourceId = doc.getString("sourceId") ?: continue
            val targetName = doc.getString("targetName") ?: continue

            val relationship =
                Relationship(
                    targetName = targetName,
                    type = doc.getString("type") ?: "acquaintance",
                    score = doc.getDouble("score") ?: 0.0,
                    traits = (doc.getList("traits", String::class.java) ?: emptyList()).toMutableSet(),
                    memoryIds = (doc.getList("memoryIds", String::class.java) ?: emptyList()).toMutableList(),
                )

            val sourceRelationships = relationships.computeIfAbsent(sourceId) { ConcurrentHashMap() }
            sourceRelationships[targetName] = relationship
        }

        return relationships
    }

    override fun saveRelationship(
        sourceId: String,
        relationships: Map<String, Relationship>,
    ) {
        // Delete existing relationships for this source, then insert all
        collection.deleteMany(Filters.eq("sourceId", sourceId))

        for ((_, relationship) in relationships) {
            val doc =
                Document()
                    .append("sourceId", sourceId)
                    .append("targetName", relationship.targetName)
                    .append("type", relationship.type)
                    .append("score", relationship.score)
                    .append("traits", relationship.traits.toList())
                    .append("memoryIds", relationship.memoryIds)

            collection.replaceOne(
                Filters.and(
                    Filters.eq("sourceId", sourceId),
                    Filters.eq("targetName", relationship.targetName),
                ),
                doc,
                ReplaceOptions().upsert(true),
            )
        }
    }
}
