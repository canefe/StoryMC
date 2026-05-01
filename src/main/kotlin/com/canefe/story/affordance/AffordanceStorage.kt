package com.canefe.story.affordance

import com.canefe.story.storage.MongoClientManager
import org.bson.Document
import org.bson.types.ObjectId

class AffordanceStorage(private val mongo: MongoClientManager) {
    private val col get() = mongo.getCollection("affordances")

    fun save(record: AffordanceRecord): AffordanceRecord {
        val doc = Document()
            .append("affordanceTypeId", record.affordanceTypeId)
            .append("name", record.name)
            .append("x", record.x)
            .append("y", record.y)
            .append("z", record.z)
            .append("world", record.world)
            .append("frontend", Document(record.frontend))

        if (record.id.isBlank()) {
            col.insertOne(doc)
            return record.copy(id = doc.getObjectId("_id").toHexString())
        } else {
            doc.append("_id", ObjectId(record.id))
            col.replaceOne(Document("_id", ObjectId(record.id)), doc)
            return record
        }
    }

    fun delete(id: String): Boolean {
        val result = col.deleteOne(Document("_id", ObjectId(id)))
        return result.deletedCount > 0
    }

    fun findAll(): List<AffordanceRecord> =
        col.find().map { it.toRecord() }.toList()

    fun findByTypeId(typeId: String): List<AffordanceRecord> =
        col.find(Document("affordanceTypeId", typeId)).map { it.toRecord() }.toList()

    private fun Document.toRecord(): AffordanceRecord {
        @Suppress("UNCHECKED_CAST")
        return AffordanceRecord(
            id = getObjectId("_id").toHexString(),
            affordanceTypeId = getString("affordanceTypeId") ?: "",
            name = getString("name") ?: "",
            x = getDouble("x") ?: 0.0,
            y = getDouble("y") ?: 0.0,
            z = getDouble("z") ?: 0.0,
            world = getString("world") ?: "world",
            frontend = (get("frontend") as? Document)?.toMap() as? Map<String, Any> ?: emptyMap(),
        )
    }
}
