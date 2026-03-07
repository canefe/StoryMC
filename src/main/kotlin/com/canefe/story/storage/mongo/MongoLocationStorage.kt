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
    private val collection get() = mongoClient.getCollection("locations")

    override fun loadAllLocations(): Map<String, LocationDocument> {
        val locations = mutableMapOf<String, LocationDocument>()
        for (doc in collection.find()) {
            val locDoc = documentToLocationDocument(doc)
            locations[locDoc.name] = locDoc
        }
        return locations
    }

    override fun loadLocation(name: String): LocationDocument? {
        val doc = collection.find(Filters.eq("name", name)).first() ?: return null
        return documentToLocationDocument(doc)
    }

    override fun saveLocation(location: LocationDocument) {
        val doc = locationDocumentToDocument(location)
        collection.replaceOne(
            Filters.eq("name", location.name),
            doc,
            ReplaceOptions().upsert(true),
        )
    }

    override fun deleteLocation(name: String) {
        collection.deleteOne(Filters.eq("name", name))
    }

    private fun locationDocumentToDocument(loc: LocationDocument): Document =
        Document()
            .append("name", loc.name)
            .append("context", loc.context)
            .append("parentLocationName", loc.parentLocationName)
            .append("world", loc.world)
            .append("x", loc.x)
            .append("y", loc.y)
            .append("z", loc.z)
            .append("yaw", loc.yaw)
            .append("pitch", loc.pitch)
            .append("allowedNPCs", loc.allowedNPCs)
            .append("hideTitle", loc.hideTitle)
            .append("randomPathingAction", loc.randomPathingAction)

    private fun documentToLocationDocument(doc: Document): LocationDocument =
        LocationDocument(
            name = doc.getString("name") ?: "",
            context = doc.getList("context", String::class.java) ?: emptyList(),
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
