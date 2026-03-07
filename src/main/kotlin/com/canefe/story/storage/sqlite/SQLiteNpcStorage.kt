package com.canefe.story.storage.sqlite

import com.canefe.story.npc.data.NPCData
import com.canefe.story.npc.memory.Memory
import com.canefe.story.storage.NpcStorage
import com.canefe.story.storage.SQLiteManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Instant
import java.util.logging.Logger

class SQLiteNpcStorage(
    private val sqlite: SQLiteManager,
    private val logger: Logger,
) : NpcStorage {
    private val gson = Gson()

    override fun loadNpcData(npcName: String): NPCData? {
        val key = resolveNpcKey(npcName) ?: return null
        val conn = sqlite.getConnection()
        val stmt = conn.prepareStatement("SELECT * FROM npcs WHERE filename = ?")
        stmt.setString(1, key)
        val rs = stmt.executeQuery()
        if (!rs.next()) {
            rs.close()
            stmt.close()
            return null
        }

        val npcData =
            NPCData(
                name = rs.getString("name") ?: "",
                role = rs.getString("role") ?: "",
                storyLocation = null,
                context = rs.getString("context") ?: "",
            )

        npcData.locationName = rs.getString("location") ?: "Village"
        npcData.appearance = rs.getString("appearance") ?: ""
        npcData.avatar = rs.getString("avatar") ?: ""
        npcData.customVoice = rs.getString("custom_voice")
        npcData.generic = rs.getInt("generic") == 1
        npcData.randomPathing = rs.getInt("random_pathing") == 1
        npcData.nameBank = rs.getString("name_bank")
        npcData.npcId = rs.getString("npc_id")
        npcData.anchorKey = rs.getString("anchor_key")
        npcData.canonicalName = rs.getString("canonical_name")
        npcData.displayHandle = rs.getString("display_handle")
        npcData.callsign = rs.getString("callsign")

        val categoriesJson = rs.getString("knowledge_categories")
        npcData.knowledgeCategories =
            if (categoriesJson != null) {
                gson.fromJson(categoriesJson, object : TypeToken<List<String>>() {}.type)
            } else {
                emptyList()
            }

        rs.close()
        stmt.close()

        npcData.memory = loadNpcMemories(key)

        return npcData
    }

    override fun saveNpcData(
        npcName: String,
        npcData: NPCData,
    ) {
        val key = resolveNpcKey(npcName) ?: npcName.replace(" ", "_").lowercase()
        val conn = sqlite.getConnection()

        val stmt =
            conn.prepareStatement(
                """REPLACE INTO npcs (filename, name, role, location, context, appearance, avatar,
               custom_voice, generic, random_pathing, knowledge_categories, name_bank, npc_id,
               anchor_key, canonical_name, display_handle, callsign)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            )

        stmt.setString(1, key)
        stmt.setString(2, npcData.name)
        stmt.setString(3, npcData.role)
        stmt.setString(4, npcData.storyLocation?.name ?: npcData.locationName)
        stmt.setString(5, npcData.context)
        stmt.setString(6, npcData.appearance)
        stmt.setString(7, npcData.avatar)
        stmt.setString(8, npcData.customVoice)
        stmt.setInt(9, if (npcData.generic) 1 else 0)
        stmt.setInt(10, if (npcData.randomPathing) 1 else 0)
        stmt.setString(11, gson.toJson(npcData.knowledgeCategories))
        stmt.setString(12, npcData.nameBank)
        stmt.setString(13, npcData.npcId)
        stmt.setString(14, npcData.anchorKey)
        stmt.setString(15, npcData.canonicalName)
        stmt.setString(16, npcData.displayHandle)
        stmt.setString(17, npcData.callsign)
        stmt.executeUpdate()
        stmt.close()

        // Delete and reinsert memories
        val delStmt = conn.prepareStatement("DELETE FROM npc_memories WHERE npc_filename = ?")
        delStmt.setString(1, key)
        delStmt.executeUpdate()
        delStmt.close()

        val memStmt =
            conn.prepareStatement(
                """INSERT INTO npc_memories (npc_filename, memory_id, content, real_created_at,
               game_created_at, power, last_accessed, significance)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            )

        for (memory in npcData.memory.filter { it.id.isNotBlank() }) {
            memStmt.setString(1, key)
            memStmt.setString(2, memory.id)
            memStmt.setString(3, memory.content)
            memStmt.setString(4, memory.realCreatedAt.toString())
            memStmt.setLong(5, memory.gameCreatedAt)
            memStmt.setDouble(6, memory.power)
            memStmt.setLong(7, memory.lastAccessed)
            memStmt.setDouble(8, memory.significance)
            memStmt.addBatch()
        }

        memStmt.executeBatch()
        memStmt.close()
    }

    override fun deleteNpc(npcName: String) {
        val key = resolveNpcKey(npcName) ?: npcName
        val conn = sqlite.getConnection()

        val memStmt = conn.prepareStatement("DELETE FROM npc_memories WHERE npc_filename = ?")
        memStmt.setString(1, key)
        memStmt.executeUpdate()
        memStmt.close()

        val npcStmt = conn.prepareStatement("DELETE FROM npcs WHERE filename = ?")
        npcStmt.setString(1, key)
        npcStmt.executeUpdate()
        npcStmt.close()
    }

    override fun getAllNpcNames(): List<String> {
        val conn = sqlite.getConnection()
        val stmt = conn.prepareStatement("SELECT filename FROM npcs")
        val rs = stmt.executeQuery()
        val names = mutableListOf<String>()
        while (rs.next()) {
            names.add(rs.getString("filename"))
        }
        rs.close()
        stmt.close()
        return names
    }

    override fun loadNpcMemories(npcName: String): MutableList<Memory> {
        val key = resolveNpcKey(npcName) ?: return mutableListOf()
        val conn = sqlite.getConnection()
        val stmt = conn.prepareStatement("SELECT * FROM npc_memories WHERE npc_filename = ?")
        stmt.setString(1, key)
        val rs = stmt.executeQuery()

        val memories = mutableListOf<Memory>()
        while (rs.next()) {
            val realCreatedAt =
                try {
                    Instant.parse(rs.getString("real_created_at"))
                } catch (_: Exception) {
                    Instant.now()
                }

            memories.add(
                Memory(
                    id = rs.getString("memory_id"),
                    content = rs.getString("content"),
                    realCreatedAt = realCreatedAt,
                    gameCreatedAt = rs.getLong("game_created_at"),
                    power = rs.getDouble("power"),
                    lastAccessed = rs.getLong("last_accessed"),
                    _significance = rs.getDouble("significance"),
                ),
            )
        }

        rs.close()
        stmt.close()
        return memories
    }

    override fun findNpcByDisplayHandle(displayHandle: String): String? {
        val conn = sqlite.getConnection()
        val stmt = conn.prepareStatement("SELECT filename FROM npcs WHERE display_handle = ?")
        stmt.setString(1, displayHandle)
        val rs = stmt.executeQuery()
        val result = if (rs.next()) rs.getString("filename") else null
        rs.close()
        stmt.close()
        return result
    }

    override fun resolveNpcKey(npcName: String): String? {
        // Try by display handle first
        findNpcByDisplayHandle(npcName)?.let { return it }

        // Try normalized filename
        val normalizedFileName = npcName.replace(" ", "_").lowercase()
        val conn = sqlite.getConnection()

        val normStmt = conn.prepareStatement("SELECT filename FROM npcs WHERE filename = ?")
        normStmt.setString(1, normalizedFileName)
        val normRs = normStmt.executeQuery()
        if (normRs.next()) {
            val result = normRs.getString("filename")
            normRs.close()
            normStmt.close()
            return result
        }
        normRs.close()
        normStmt.close()

        // Try original name
        val origStmt = conn.prepareStatement("SELECT filename FROM npcs WHERE filename = ?")
        origStmt.setString(1, npcName)
        val origRs = origStmt.executeQuery()
        if (origRs.next()) {
            val result = origRs.getString("filename")
            origRs.close()
            origStmt.close()
            return result
        }
        origRs.close()
        origStmt.close()

        return null
    }
}
