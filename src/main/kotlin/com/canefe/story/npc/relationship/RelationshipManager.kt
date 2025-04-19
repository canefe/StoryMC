package com.canefe.story.npc.relationship

import com.canefe.story.Story
import com.canefe.story.npc.memory.Memory
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class RelationshipManager(private val plugin: Story) {
	// Stores relationships as: sourceId -> (targetId -> Relationship)
	private val relationships = ConcurrentHashMap<String, MutableMap<String, Relationship>>()
	private val dataFolder = File(plugin.dataFolder, "relationships")

	init {
		dataFolder.mkdirs()
		loadAllRelationships()
	}

	/**
	 * Loads all relationship data from files
	 */
	fun loadAllRelationships() {
		relationships.clear()

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
	}

	/**
	 * Saves relationship data for a specific source
	 */
	fun saveRelationship(sourceId: String) {
		val sourceRelationships = relationships[sourceId] ?: return
		val file = File(dataFolder, "$sourceId.yml")
		val config = YamlConfiguration()

		sourceRelationships.forEach { (targetId, relationship) ->
			val path = targetId

			config.set("$path.name", relationship.targetName)
			config.set("$path.type", relationship.type)
			config.set("$path.score", relationship.score)
			config.set("$path.traits", relationship.traits.toList())
			config.set("$path.memoryIds", relationship.memoryIds)
		}

		config.save(file)
	}

	/**
	 * Gets a relationship between two entities (creates if it doesn't exist)
	 */
	fun getRelationship(
		sourceId: String,
		targetName: String,
	): Relationship {
		val sourceRelationships = relationships.computeIfAbsent(sourceId) { ConcurrentHashMap() }
		return sourceRelationships.computeIfAbsent(targetName) {
			Relationship(targetName = targetName)
		}
	}

	/**
	 * Gets all relationships for an entity
	 */
	fun getAllRelationships(sourceId: String): Map<String, Relationship> {
		return relationships[sourceId] ?: emptyMap()
	}

	/**
	 * Calculate impact of a message based on its content
	 * This is a simple implementation - could be replaced with proper sentiment analysis
	 */
	private fun calculateMessageImpact(message: String): Double {
		// Simple sentiment check - this should be improved with proper NLP
		val positiveWords = listOf("happy", "thanks", "good", "great", "love", "appreciate", "friend")
		val negativeWords = listOf("angry", "hate", "bad", "terrible", "awful", "dislike", "enemy")

		val words = message.lowercase().split(" ")

		var score = 0.0
		for (word in words) {
			when {
				positiveWords.any { word.contains(it) } -> score += 0.1
				negativeWords.any { word.contains(it) } -> score -= 0.1
			}
		}

		// Keep impact within bounds -1.0 to 1.0
		return score.coerceIn(-1.0, 1.0)
	}

	/**
	 * Generate events for NPCs that are near each other frequently
	 * This should be called periodically to simulate ambient relationship building
	 */
	fun processAmbientRelationships() {
		val npcNames = plugin.npcDataManager.getAllNPCNames()
		val npcs = mutableListOf<NPC>()
		// Loop Citizens registry
		for (npc in CitizensAPI.getNPCRegistry()) {
			val npcName = npc.name
			if (npcName == null || !npcs.contains(npc)) continue

			// Check if NPC is spawned
			val npcData = plugin.npcDataManager.getNPCData(npcName)
			if (npcData == null || !npc.isSpawned) continue

			// Add NPC to the list if not already present
			if (!npcs.contains(npc)) {
				npcs.add(npc)
			}
		}

		for (npc1 in npcs) {
			if (!npc1.isSpawned) continue

			// Find nearby NPCs
			for (npc2 in npcs) {
				if (!npc2.isSpawned || npc1 == npc2) continue

				// Check if NPCs are near each other
				if (npc1.entity.location.distanceSquared(npc2.entity.location) < 25) { // 5 blocks
					val relationship = getRelationship(npc1.name, npc2.name)
					val lastInteractionKey = "${npc1.name}-${npc2.name}"

					// Only record ambient interaction once per hour of game time
					val hourInGameTime = 72000L
					val currentTime = plugin.timeService.getCurrentGameTime()
					val lastInteraction = npcLastAmbientInteraction[lastInteractionKey] ?: 0L

					// When NPCs are near each other
					if (currentTime - lastInteraction > hourInGameTime) {
						npcLastAmbientInteraction[lastInteractionKey] = currentTime

						// Create memory about ambient interaction
						val memoryContent = "I spent some time near ${npc2.name} today."
						val memory =
							Memory(
								id = "ambient_${System.currentTimeMillis()}_${npc1.name}",
								content = memoryContent,
								gameCreatedAt = currentTime,
								lastAccessed = currentTime,
								power = 0.6,
								_significance = 1.5, // Low significance for ambient interaction
							)

						// Add memory to NPC data
						val npcData = plugin.npcDataManager.getNPCData(npc1.name) ?: return
						npcData.memory.add(memory)
						plugin.npcDataManager.saveNPCData(npc1.name, npcData)

						// Update relationship based on memory
						updateRelationshipFromMemory(memory, npc1.name)
					}
				}
			}
		}
	}

	fun updateRelationshipFromMemory(
		memory: Memory,
		npcName: String,
	) {
		// Identify potential relationship targets in the memory
		val potentialTargets = findRelationshipTargets(memory.content)

		// Calculate the relationship impact based on memory significance and power
		val relationshipImpact = calculateRelationshipImpact(memory)

		for (targetName in potentialTargets) {
			val relationship = getRelationship(npcName, targetName)

			// Update relationship score based on memory significance
			relationship.updateScore(relationshipImpact)

			// Store reference to the memory in the relationship
			if (!relationship.memoryIds.contains(memory.id)) {
				relationship.memoryIds.add(memory.id)
				// Keep only the last 10 memory references
				if (relationship.memoryIds.size > 10) {
					relationship.memoryIds.removeAt(0)
				}
			}

			// Save updated relationship
			saveRelationship(npcName)
		}
	}

	/**
	 * Calculate relationship impact from memory
	 */
	private fun calculateRelationshipImpact(memory: Memory): Double {
		// Scale based on significance and power
		// Higher significance = stronger impact on relationship
		return (memory.significance - 2.5) * memory.power
		// This gives negative impact for memories with significance < 2.5
		// and positive impact for memories with significance > 2.5
	}

	/**
	 * Find potential relationship targets mentioned in memory content
	 */
	private fun findRelationshipTargets(content: String): List<String> {
		val targets = mutableListOf<String>()

		// Get all registered NPCs and players to check for mentions
		val allNpcs = plugin.npcDataManager.getAllNPCNames()
		val allPlayers = Bukkit.getOnlinePlayers().map { it.name }

		// Prepare regex pattern to match whole words only
		for (npcName in allNpcs) {
			// Create regex that matches word boundaries around the name
			val pattern = "\\b${Regex.escape(npcName)}\\b"
			if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(content)) {
				targets.add(npcName)
			}
		}

		// Do the same for player names
		for (playerName in allPlayers) {
			val pattern = "\\b${Regex.escape(playerName)}\\b"
			if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(content)) {
				targets.add(playerName)
			}
		}

		return targets
	}

	// Track last ambient interaction times
	private val npcLastAmbientInteraction = HashMap<String, Long>()
}
