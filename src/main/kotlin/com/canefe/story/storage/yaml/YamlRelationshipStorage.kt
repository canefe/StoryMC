@file:Suppress("DEPRECATION")

package com.canefe.story.storage.yaml

import com.canefe.story.npc.relationship.Relationship
import com.canefe.story.storage.RelationshipStorage
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

@Deprecated("YAML storage is deprecated and has known bugs. Use MongoDB backend.")
class YamlRelationshipStorage(
    private val dataFolder: File,
    private val logger: Logger,
) : RelationshipStorage {
    init {
        dataFolder.mkdirs()
    }

    override fun loadAllRelationships(): Map<String, MutableMap<String, Relationship>> {
        val relationships = mutableMapOf<String, MutableMap<String, Relationship>>()

        dataFolder.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".yml")) {
                val sourceId = file.nameWithoutExtension
                val config = YamlConfiguration.loadConfiguration(file)
                val sourceRelationships = ConcurrentHashMap<String, Relationship>()

                config.getKeys(false).forEach { targetId ->
                    val section = config.getConfigurationSection(targetId) ?: return@forEach

                    val targetName = section.getString("name") ?: targetId
                    val type = section.getString("type") ?: "acquaintance"
                    val score = section.getDouble("score")
                    val traits = section.getStringList("traits").toMutableSet()

                    val relationship =
                        Relationship(
                            targetName = targetName,
                            type = type,
                            score = score,
                            traits = traits,
                        )

                    sourceRelationships[targetId] = relationship
                }

                relationships[sourceId] = sourceRelationships
            }
        }

        return relationships
    }

    override fun saveRelationship(
        sourceId: String,
        relationships: Map<String, Relationship>,
    ) {
        val file = File(dataFolder, "$sourceId.yml")
        val config = YamlConfiguration()

        relationships.forEach { (targetId, relationship) ->
            config.set("$targetId.name", relationship.targetName)
            config.set("$targetId.type", relationship.type)
            config.set("$targetId.score", relationship.score)
            config.set("$targetId.traits", relationship.traits.toList())
            config.set("$targetId.memoryIds", relationship.memoryIds)
        }

        config.save(file)
    }
}
