package com.canefe.story.storage.mongo

import com.canefe.story.storage.MongoClientManager
import com.canefe.story.storage.SessionDocument
import com.canefe.story.storage.SessionStorage
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document

class MongoSessionStorage(
    private val mongoClient: MongoClientManager,
) : SessionStorage {
    private val collection get() = mongoClient.getCollection("sessions")

    override fun saveSession(
        sessionId: String,
        document: SessionDocument,
    ) {
        val doc = toDocument(document)
        collection.replaceOne(
            Filters.eq("sessionId", sessionId),
            doc,
            ReplaceOptions().upsert(true),
        )
    }

    override fun loadSession(sessionId: String): SessionDocument? {
        val doc = collection.find(Filters.eq("sessionId", sessionId)).first() ?: return null
        return fromDocument(doc)
    }

    override fun updateSession(
        sessionId: String,
        document: SessionDocument,
    ) {
        saveSession(sessionId, document)
    }

    private fun toDocument(session: SessionDocument): Document =
        Document()
            .append("sessionId", session.sessionId)
            .append("startTime", session.startTime)
            .append("endTime", session.endTime)
            .append("players", session.players)
            .append("history", session.history)
            .append("active", session.active)

    private fun fromDocument(doc: Document): SessionDocument =
        SessionDocument(
            sessionId = doc.getString("sessionId") ?: "",
            startTime = doc.getLong("startTime") ?: 0L,
            endTime = doc.getLong("endTime"),
            players = doc.getList("players", String::class.java) ?: emptyList(),
            history = doc.getString("history") ?: "",
            active = doc.getBoolean("active", true),
        )
}
