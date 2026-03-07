package com.canefe.story.storage.sqlite

import com.canefe.story.npc.relationship.Relationship
import com.canefe.story.storage.RelationshipStorage
import com.canefe.story.storage.SQLiteManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SQLiteRelationshipStorage(
    private val sqlite: SQLiteManager,
) : RelationshipStorage {
    private val gson = Gson()

    override fun loadAllRelationships(): Map<String, MutableMap<String, Relationship>> {
        val result = mutableMapOf<String, MutableMap<String, Relationship>>()
        val conn = sqlite.getConnection()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT * FROM relationships")
            while (rs.next()) {
                val sourceId = rs.getString("source_id")
                val targetId = rs.getString("target_id")
                val targetName = rs.getString("target_name") ?: ""
                val type = rs.getString("type") ?: "acquaintance"
                val score = rs.getDouble("score")

                val traitsJson = rs.getString("traits") ?: "[]"
                val traits: MutableSet<String> =
                    gson.fromJson(
                        traitsJson,
                        object : TypeToken<MutableSet<String>>() {}.type,
                    )

                val memoryIdsJson = rs.getString("memory_ids") ?: "[]"
                val memoryIds: MutableList<String> =
                    gson.fromJson(
                        memoryIdsJson,
                        object : TypeToken<MutableList<String>>() {}.type,
                    )

                val relationship =
                    Relationship(
                        id = targetId,
                        targetName = targetName,
                        type = type,
                        score = score,
                        memoryIds = memoryIds,
                        traits = traits,
                    )

                result.getOrPut(sourceId) { mutableMapOf() }[targetId] = relationship
            }
        }
        return result
    }

    override fun saveRelationship(
        sourceId: String,
        relationships: Map<String, Relationship>,
    ) {
        val conn = sqlite.getConnection()
        conn.prepareStatement("DELETE FROM relationships WHERE source_id = ?").use { stmt ->
            stmt.setString(1, sourceId)
            stmt.executeUpdate()
        }

        conn
            .prepareStatement(
                "INSERT INTO relationships (source_id, target_id, target_name, type, score, traits, memory_ids) VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).use { stmt ->
                for ((targetId, rel) in relationships) {
                    stmt.setString(1, sourceId)
                    stmt.setString(2, targetId)
                    stmt.setString(3, rel.targetName)
                    stmt.setString(4, rel.type)
                    stmt.setDouble(5, rel.score)
                    stmt.setString(6, gson.toJson(rel.traits))
                    stmt.setString(7, gson.toJson(rel.memoryIds))
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
    }
}
