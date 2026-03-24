package com.canefe.story.storage.mongo

import com.canefe.story.storage.LocationDocument
import com.canefe.story.storage.LocationStorage
import com.canefe.story.storage.MongoClientManager
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document

class MongoLocationStorage(
    private val mongoClient: MongoClientManager,
) : LocationStorage {
    // Typed collection for writes
    private val typedCollection get() = mongoClient.getTypedCollection("locations", LocationDocument::class.java)

    // Raw collection for reads (handles migration from old context list format)
    private val rawCollection get() = mongoClient.getCollection("locations")

    override fun loadAllLocations(): Map<String, LocationDocument> {
        val locations = mutableMapOf<String, LocationDocument>()
        for (doc in rawCollection.find()) {
            val locDoc = documentToLocationDocument(doc)
            locations[locDoc.name] = locDoc
        }
        return locations
    }

    override fun loadLocation(name: String): LocationDocument? {
        val doc = rawCollection.find(Filters.eq("name", name)).first() ?: return null
        return documentToLocationDocument(doc)
    }

    override fun saveLocation(location: LocationDocument) {
        typedCollection.replaceOne(
            Filters.eq("name", location.name),
            location,
            ReplaceOptions().upsert(true),
        )
    }

    override fun deleteLocation(name: String) {
        typedCollection.deleteOne(Filters.eq("name", name))
    }

    private fun documentToLocationDocument(doc: Document): LocationDocument {
        // Migration: handle both old list format (context) and new string format (description)
        val description =
            when (val raw = doc.get("description") ?: doc.get("context")) {
                is List<*> -> (raw.filterIsInstance<String>()).joinToString(". ")
                is String -> raw
                else -> ""
            }
        return LocationDocument(
            name = doc.getString("name") ?: "",
            description = description,
            parentLocationName = doc.getString("parentLocationName"),
            world = doc.getString("world"),
            x = doc.getDouble("x") ?: 0.0,
            y = doc.getDouble("y") ?: 0.0,
            z = doc.getDouble("z") ?: 0.0,
            yaw = (doc.get("yaw") as? Number)?.toFloat() ?: 0f,
            pitch = (doc.get("pitch") as? Number)?.toFloat() ?: 0f,
            allowedNPCs = doc.getList("allowedNPCs", String::class.java) ?: emptyList(),
            hideTitle = doc.getBoolean("hideTitle", false),
            randomPathingAction = doc.getString("randomPathingAction"),
        )
    }
}
