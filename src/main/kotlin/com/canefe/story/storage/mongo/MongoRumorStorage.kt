package com.canefe.story.storage.mongo

import com.canefe.story.information.Rumor
import com.canefe.story.storage.MongoClientManager
import com.canefe.story.storage.RumorStorage
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions

class MongoRumorStorage(
    private val mongoClient: MongoClientManager,
) : RumorStorage {
    private val collection get() = mongoClient.getTypedCollection("rumors", Rumor::class.java)

    override fun loadAllRumors(): List<Rumor> = collection.find().toList()

    override fun loadRumor(id: String): Rumor? = collection.find(Filters.eq("id", id)).first()

    override fun loadRumorsByLocation(location: String): List<Rumor> =
        collection.find(Filters.eq("location", location)).toList()

    override fun saveRumor(rumor: Rumor) {
        collection.replaceOne(
            Filters.eq("id", rumor.id),
            rumor,
            ReplaceOptions().upsert(true),
        )
    }

    override fun deleteRumor(id: String): Boolean {
        val result = collection.deleteOne(Filters.eq("id", id))
        return result.deletedCount > 0
    }
}
