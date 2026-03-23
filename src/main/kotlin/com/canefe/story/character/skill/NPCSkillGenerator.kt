package com.canefe.story.character.skill

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.npc.data.NPCData
import com.google.gson.JsonParser
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Generates skill levels for NPCs on demand using LLM, based on their role and context.
 * Results are cached in memory and persisted to NPC data in storage.
 */
class NPCSkillGenerator(
    private val plugin: Story,
) {
    // In-flight generation futures to avoid duplicate LLM calls for the same NPC
    private val pendingGenerations = ConcurrentHashMap<String, CompletableFuture<Map<String, Int>>>()

    /**
     * Get or generate skills for an NPC. Returns cached skills if available,
     * otherwise generates via LLM and saves to storage.
     */
    fun getOrGenerateSkills(npcName: String): CompletableFuture<Map<String, Int>> {
        val npcData =
            plugin.npcDataManager.getNPCData(npcName)
                ?: return CompletableFuture.completedFuture(emptyMap())

        // Already has skills
        if (npcData.skills.isNotEmpty()) {
            return CompletableFuture.completedFuture(npcData.skills)
        }

        // Deduplicate in-flight requests
        return pendingGenerations.computeIfAbsent(npcName.lowercase()) {
            generateSkills(npcData).whenComplete { _, _ ->
                pendingGenerations.remove(npcName.lowercase())
            }
        }
    }

    private fun generateSkills(npcData: NPCData): CompletableFuture<Map<String, Int>> {
        val availableSkills = getAvailableSkillList()
        if (availableSkills.isEmpty()) {
            return CompletableFuture.completedFuture(emptyMap())
        }

        val prompt =
            plugin.promptService.getNpcSkillGenerationPrompt(
                npcName = npcData.name,
                role = npcData.role,
                context = npcData.context,
                availableSkills = availableSkills.joinToString(", "),
            )

        return plugin
            .getAIResponse(
                listOf(ConversationMessage("system", prompt)),
                lowCost = true,
            ).thenApply { response ->
                val skills = parseSkillResponse(response, availableSkills)

                if (skills.isNotEmpty()) {
                    // Save to NPC data
                    npcData.skills = skills.toMutableMap()
                    plugin.npcDataManager.saveNPCData(npcData.name, npcData)

                    if (plugin.config.debugMessages) {
                        plugin.logger.info("Generated skills for ${npcData.name}: $skills")
                    }
                }

                skills
            }
    }

    private fun parseSkillResponse(
        response: String?,
        availableSkills: List<String>,
    ): Map<String, Int> {
        if (response.isNullOrBlank()) return emptyMap()

        return try {
            val cleaned =
                response
                    .trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

            val json = JsonParser.parseString(cleaned).asJsonObject
            val skills = mutableMapOf<String, Int>()

            for (skill in availableSkills) {
                val key = skill.lowercase()
                if (json.has(key)) {
                    skills[key] = json.get(key).asInt.coerceIn(1, 30)
                }
            }

            skills
        } catch (e: Exception) {
            if (plugin.config.debugMessages) {
                plugin.logger.warning("Failed to parse NPC skill generation response: ${e.message}")
            }
            emptyMap()
        }
    }

    /**
     * Get the list of available skills from the player skill provider.
     */
    fun getAvailableSkillList(): List<String> {
        // Get skills from any online player's provider, or use cached list
        val player = plugin.server.onlinePlayers.firstOrNull()
        if (player != null) {
            val provider = plugin.skillManager.createProviderForCharacter(player.uniqueId, true)
            val skills = provider.getAllSkills()
            if (skills.isNotEmpty()) return skills
        }
        return emptyList()
    }
}
