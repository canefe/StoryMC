package com.canefe.story.storage.mongo

import com.canefe.story.npc.data.NPCData
import com.canefe.story.npc.memory.Memory
import com.canefe.story.storage.MongoClientManager
import com.canefe.story.storage.NpcStorage
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document
import java.time.Instant
import java.util.logging.Logger

class MongoNpcStorage(
    private val mongoClient: MongoClientManager,
    private val logger: Logger,
) : NpcStorage {
    private val collection get() = mongoClient.getCollection("npcs")

    override fun loadNpcData(npcName: String): NPCData? {
        val key = resolveNpcKey(npcName) ?: return null
        val doc = collection.find(Filters.eq("filename", key)).first() ?: return null
        return documentToNpcData(doc)
    }

    override fun saveNpcData(
        npcName: String,
        npcData: NPCData,
    ) {
        val key = resolveNpcKey(npcName) ?: npcName.replace(" ", "_").lowercase()
        val doc = npcDataToDocument(key, npcData)
        collection.replaceOne(
            Filters.eq("filename", key),
            doc,
            ReplaceOptions().upsert(true),
        )
    }

    override fun deleteNpc(npcName: String) {
        val key = resolveNpcKey(npcName) ?: npcName
        collection.deleteOne(Filters.eq("filename", key))
    }

    override fun getAllNpcNames(): List<String> =
        collection
            .find()
            .projection(Document("filename", 1))
            .map { it.getString("filename") }
            .toList()

    override fun loadNpcMemories(npcName: String): MutableList<Memory> {
        val key = resolveNpcKey(npcName) ?: return mutableListOf()
        val doc =
            collection
                .find(Filters.eq("filename", key))
                .projection(Document("memories", 1))
                .first() ?: return mutableListOf()

        return parseMemories(doc)
    }

    override fun findNpcByDisplayHandle(displayHandle: String): String? {
        val doc =
            collection
                .find(Filters.eq("displayHandle", displayHandle))
                .projection(Document("filename", 1))
                .first()
        return doc?.getString("filename")
    }

    override fun resolveNpcKey(npcName: String): String? {
        // Try by display handle first
        findNpcByDisplayHandle(npcName)?.let { return it }

        // Try normalized filename
        val normalizedFileName = npcName.replace(" ", "_").lowercase()
        if (collection.find(Filters.eq("filename", normalizedFileName)).first() != null) {
            return normalizedFileName
        }

        // Try original name
        if (collection.find(Filters.eq("filename", npcName)).first() != null) {
            return npcName
        }

        return null
    }

    private fun npcDataToDocument(
        filename: String,
        npcData: NPCData,
    ): Document {
        val memoriesList =
            npcData.memory.filter { it.id.isNotBlank() }.map { memory ->
                Document()
                    .append("id", memory.id)
                    .append("content", memory.content)
                    .append("realCreatedAt", memory.realCreatedAt.toString())
                    .append("gameCreatedAt", memory.gameCreatedAt)
                    .append("power", memory.power)
                    .append("lastAccessed", memory.lastAccessed)
                    .append("significance", memory.significance)
            }

        return Document()
            .append("filename", filename)
            .append("name", npcData.name)
            .append("role", npcData.role)
            .append("location", npcData.storyLocation?.name ?: npcData.locationName)
            .append("context", npcData.context)
            .append("appearance", npcData.appearance)
            .append("avatar", npcData.avatar)
            .append("customVoice", npcData.customVoice)
            .append("generic", npcData.generic)
            .append("randomPathing", npcData.randomPathing)
            .append("knowledgeCategories", npcData.knowledgeCategories)
            .append("nameBank", npcData.nameBank)
            .append("npcId", npcData.npcId)
            .append("anchorKey", npcData.anchorKey)
            .append("canonicalName", npcData.canonicalName)
            .append("displayHandle", npcData.displayHandle)
            .append("callsign", npcData.callsign)
            .append(
                "skills",
                if (npcData.skills.isNotEmpty()) org.bson.Document(npcData.skills as Map<String, Any>) else null,
            ).append("memories", memoriesList)
    }

    private fun documentToNpcData(doc: Document): NPCData {
        val npcData =
            NPCData(
                name = doc.getString("name") ?: "",
                role = doc.getString("role") ?: "",
                storyLocation = null,
                context = doc.getString("context") ?: "",
            )

        npcData.appearance = doc.getString("appearance") ?: ""
        npcData.avatar = doc.getString("avatar") ?: ""
        npcData.customVoice = doc.getString("customVoice")
        npcData.generic = doc.getBoolean("generic", false)
        npcData.randomPathing = doc.getBoolean("randomPathing", true)
        npcData.knowledgeCategories = doc.getList("knowledgeCategories", String::class.java) ?: emptyList()
        npcData.nameBank = doc.getString("nameBank")
        npcData.npcId = doc.getString("npcId")
        npcData.anchorKey = doc.getString("anchorKey")
        npcData.canonicalName = doc.getString("canonicalName")
        npcData.displayHandle = doc.getString("displayHandle")
        npcData.callsign = doc.getString("callsign")
        npcData.locationName = doc.getString("location")

        val skillsDoc = doc.get("skills", org.bson.Document::class.java)
        npcData.skills =
            if (skillsDoc != null) {
                skillsDoc.entries.associate { it.key to (it.value as Number).toInt() }.toMutableMap()
            } else {
                mutableMapOf()
            }

        npcData.memory = parseMemories(doc)

        return npcData
    }

    private fun parseMemories(doc: Document): MutableList<Memory> {
        val memories = mutableListOf<Memory>()
        val memoriesList = doc.getList("memories", Document::class.java) ?: return memories

        for (memDoc in memoriesList) {
            val content = memDoc.getString("content") ?: continue
            val id = memDoc.getString("id") ?: continue

            val realCreatedAt =
                try {
                    Instant.parse(memDoc.getString("realCreatedAt"))
                } catch (_: Exception) {
                    Instant.now()
                }

            memories.add(
                Memory(
                    id = id,
                    content = content,
                    realCreatedAt = realCreatedAt,
                    gameCreatedAt = memDoc.getLong("gameCreatedAt") ?: 0L,
                    power = memDoc.getDouble("power") ?: 1.0,
                    lastAccessed = memDoc.getLong("lastAccessed") ?: 0L,
                    _significance = memDoc.getDouble("significance") ?: 1.0,
                ),
            )
        }

        return memories
    }
}
