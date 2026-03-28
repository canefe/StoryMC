package com.canefe.story.storage.mongo

import com.canefe.story.information.WorldEvent
import com.canefe.story.storage.MongoClientManager
import com.canefe.story.storage.WorldEventStorage
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions

class MongoWorldEventStorage(
    private val mongoClient: MongoClientManager,
) : WorldEventStorage {
    private val collection get() = mongoClient.getTypedCollection("world_events", WorldEvent::class.java)

    override fun loadAllWorldEvents(): List<WorldEvent> = collection.find().toList()

    override fun loadWorldEvent(id: String): WorldEvent? = collection.find(Filters.eq("id", id)).first()

    override fun loadWorldEventsByLocation(location: String): List<WorldEvent> =
        collection.find(Filters.eq("location", location)).toList()

    override fun saveWorldEvent(event: WorldEvent) {
        collection.replaceOne(
            Filters.eq("id", event.id),
            event,
            ReplaceOptions().upsert(true),
        )
    }

    override fun deleteWorldEvent(id: String): Boolean {
        val result = collection.deleteOne(Filters.eq("id", id))
        return result.deletedCount > 0
    }
}
