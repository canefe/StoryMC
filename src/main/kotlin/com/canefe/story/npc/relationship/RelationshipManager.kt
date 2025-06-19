package com.canefe.story.npc.relationship

import com.canefe.story.Story
import com.canefe.story.conversation.Conversation
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.npc.memory.Memory
import com.canefe.story.util.EssentialsUtils
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue
import kotlin.text.get
import kotlin.times

class RelationshipManager(private val plugin: Story) {
	// Stores relationships as: sourceId -> (targetId -> Relationship)
	private val relationships = ConcurrentHashMap<String, MutableMap<String, Relationship>>()
	private val dataFolder = File(plugin.dataFolder, "relationships")

	init {
		dataFolder.mkdirs()
		// Load existing relationships from files
		loadAllRelationships()
	}

	fun load() {
		// Load all relationships from files
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
	fun getRelationship(sourceId: String, targetName: String): Relationship {
		val sourceRelationships = relationships.computeIfAbsent(sourceId) { ConcurrentHashMap() }
		return sourceRelationships.computeIfAbsent(targetName) {
			Relationship(targetName = targetName)
		}
	}

	/**
	 * Builds a relationship context string for Characters in the conversation
	 *
	 * @param relationships Map of relationships for the Character
	 * @param conversation The current conversation
	 * @return A formatted string containing relationship information relevant to the conversation
	 */
	fun buildRelationshipContext(
		character: String,
		relationships: Map<String, Relationship>,
		conversation: Conversation,
	): String = relationships.values
		.filter { rel ->
			// Only include relationships with entities that are in the conversation
			conversation.npcs.any {
				it.name.lowercase() == rel.targetName.lowercase() &&
					it.name.lowercase() != character.lowercase()
			} ||
				conversation.players?.any { playerId ->
					val player = Bukkit.getPlayer(playerId)
					player != null && EssentialsUtils.getNickname(player.name).lowercase() == rel.targetName.lowercase()
				} == true
		}.joinToString("\n") { rel ->
			"$character and ${rel.targetName}'s relationship is ${rel.type} with a score of ${rel.score}." +
				" Traits: ${if (rel.traits.isNotEmpty()) rel.traits.joinToString(", ") else "none"}."
		}

	/**
	 * Gets all relationships for an entity
	 */
	fun getAllRelationships(sourceId: String): Map<String, Relationship> {
		val allRelationships = relationships[sourceId] ?: return emptyMap()

		// Filter out relationships where target doesn't have valid NPCData
		return allRelationships.filterKeys { targetName ->
			// Keep only relationships where either:
			// 1. The target has valid NPC data, or
			// 2. The target is a known player name
			plugin.npcDataManager.getNPCData(targetName) != null ||
				Bukkit.getOfflinePlayer(targetName).hasPlayedBefore()
		}
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

	fun updateRelationshipFromMemory(memory: Memory, npcName: String) {
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
		// Use AI to evaluate the emotional impact of the memory
		// Get the response synchronously with a timeout
		val prompt =
			"""
			You are a relationship analyzer that ONLY returns a single word.
			Based on this memory, return ONLY a single word between 'negative' (negative impact) and 'positive' (positive impact).
			DO NOT include any explanation or text - ONLY return the word.

			Memory: ${memory.content}
			""".trimIndent()

		// Get the impact value from AI response and scale it by memory power
		// If AI fails, fall back to the mathematical formula
		return try {
			// Get the response synchronously with a timeout
			val response =
				plugin
					.getAIResponse(
						listOf(
							ConversationMessage("system", prompt),
						),
						lowCost = true,
					).get(15, java.util.concurrent.TimeUnit.SECONDS)

			val calculatedValue = (memory.significance - 2.5)
			val impact =
				when {
					response?.trim()?.lowercase() == "negative" -> -calculatedValue.absoluteValue
					response?.trim()?.lowercase() == "positive" -> calculatedValue.absoluteValue
					else -> calculatedValue
				}
			impact * memory.power
		} catch (e: Exception) {
			plugin.logger.warning("Failed to get AI impact calculation: ${e.message}")
			(memory.significance - 2.5) * memory.power
		}
	}

	/**
	 * Find potential relationship targets mentioned in memory content
	 */
	private fun findRelationshipTargets(content: String): List<String> {
		// Try AI-based extraction first
		val aiTargets = extractTargetsWithAI(content)
		if (aiTargets.isNotEmpty()) {
			return aiTargets
		}

		// Fall back to regex matching if AI fails or returns empty
		val targets = mutableListOf<String>()
		val allNpcs = plugin.npcDataManager.getAllNPCNames()
		val allPlayers = Bukkit.getOnlinePlayers().map { it.name }

		// Pattern matching fallback for NPCs
		for (npcName in allNpcs) {
			val pattern = "\\b${Regex.escape(npcName)}\\b"
			if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(content)) {
				targets.add(npcName)
			}
		}

		// Pattern matching fallback for players
		for (playerName in allPlayers) {
			val pattern = "\\b${Regex.escape(playerName)}\\b"
			if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(content)) {
				targets.add(playerName)
			}
		}

		return targets
	}

	private fun extractTargetsWithAI(content: String): List<String> {
		val allNpcs = plugin.npcDataManager.getAllNPCNames()
		val allPlayers = Bukkit.getOnlinePlayers().map { EssentialsUtils.getNickname(it.name) }
		val allEntities = (allNpcs + allPlayers).joinToString("\n")

		val prompt = """
        You are a character detection system. Given a text and a list of known character,
        identify which character are mentioned in the text. Return ONLY a JSON array of
        character names that are mentioned.

        Text: $content

        Known characters:
        $allEntities

        Format your response as a valid JSON array: ["Character1", "Character2"]
        IMPORTANT: Only include characters that are explicitly mentioned in the text.
		""".trimIndent()

		val messages = listOf(ConversationMessage("system", prompt))

		try {
			val response = plugin.getAIResponse(messages, lowCost = true)
				.get(10, java.util.concurrent.TimeUnit.SECONDS)

			// Parse JSON array from response
			if (!response.isNullOrBlank()) {
				// Extract JSON array part from response
				val jsonStart = response.indexOf('[')
				val jsonEnd = response.lastIndexOf(']') + 1

				if (jsonStart >= 0 && jsonEnd > jsonStart) {
					val jsonArray = response.substring(jsonStart, jsonEnd)
					try {
						val parser = org.json.simple.parser.JSONParser()
						val parsedArray = parser.parse(jsonArray) as org.json.simple.JSONArray

						return parsedArray.map { it.toString() }
					} catch (e: Exception) {
						plugin.logger.warning("Failed to parse AI response as JSON: ${e.message}")
					}
				}
			}
		} catch (e: Exception) {
			plugin.logger.warning("AI entity extraction failed: ${e.message}")
		}

		return emptyList()
	}

	// Track last ambient interaction times
	private val npcLastAmbientInteraction = HashMap<String, Long>()

	/**
	 * Generates a relationship label using AI based on the relationship's score and traits.
	 */
	fun generateRelationshipLabel(relationship: Relationship): CompletableFuture<String> {
		val score = relationship.score
		val traits = if (relationship.traits.isNotEmpty()) relationship.traits.joinToString(", ") else "none"
		val memorySummary = "No specific memories provided for this label generation task yet." // Placeholder

		val prompt =
			"""
			Based on the following relationship details, generate a concise 1-3 word label (e.g., 'Lovers', 'Boyfriend Girlfriends', 'Acquiatances', 'Friends', 'Close Friends', 'Romantic Interest', 'Sexual Tension').
			The label should reflect the overall sentiment and nature of the relationship.
			Details:
			Score (from -100 to 100, where higher is better): $score
			Current relationship type: ${relationship.type}
			Traits: $traits
			Recent memories: $memorySummary
			""".trimIndent()

		val messages =
			listOf(
				ConversationMessage("system", prompt),
			)

		return plugin.getAIResponse(messages).thenApply { response ->
			if (response.isNullOrBlank() || !isValidRelationshipLabel(response)) {
				relationship.type // Fallback to existing type if AI fails
			} else {
				response
			}
		}
	}

	// 4 word max validator boolean
	fun isValidRelationshipLabel(label: String): Boolean = label.split(" ").size <= 4
}
