package com.canefe.story.npc.data

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.npc.memory.Memory
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * Utility class to migrate old NPC data format to the new memory-based system.
 */
class NPCDataMigrator(private val plugin: Story) {

    private fun NPCData.getOldConversationHistory(): List<ConversationMessage>? {
        // Only check YAML files for conversation history
        val npcName = this.name
        try {
            val npcFile = File(plugin.dataFolder, "npcs/${this.name}.yml")
            if (npcFile.exists()) {
                val config = YamlConfiguration.loadConfiguration(npcFile)
                val conversationList = config.getMapList("conversationHistory")
                    .map { map ->
                        ConversationMessage(
                            map["role"]?.toString() ?: "system",
                            map["content"]?.toString() ?: ""
                        )
                    }
                if (conversationList.isNotEmpty()) {
                    return conversationList
                }
            }
            return null
        } catch (e: Exception) {
            plugin.logger.warning("Error accessing old conversation history for $npcName: ${e.message}")
            return null
        }
    }

    /**
     * Checks if the NPC data is in old format (has conversationHistory but no memory)
     */
    fun isOldFormat(npcData: NPCData): Boolean {
        return npcData.getOldConversationHistory() != null && npcData.getOldConversationHistory()!!.isNotEmpty() &&
                (npcData.memory.isEmpty() || npcData.memory.size < 3)
    }

    /**
     * Migrates old NPC data format to the new memory-based system
     * @param npcData The NPC data to migrate
     * @return The migrated NPC data with memories
     */
    fun migrateToNewFormat(npcName: String, npcData: NPCData): CompletableFuture<NPCData> {
        val future = CompletableFuture<NPCData>()

        try {
            plugin.logger.info("Migrating old NPC data for $npcName to new memory format...")

            // Get the most recent conversations (up to 20)
            val recentConversations = npcData.getOldConversationHistory()?.takeLast(20) ?: emptyList<ConversationMessage>()

            // if the whole conversation history is less than 5, create new NPC context
            if (recentConversations.size < 5) {
                plugin.logger.info("Not enough conversation history found for $npcName, regenerating NPC data")
                val npcFile = File(plugin.dataFolder, "npcs/$npcName.yml")
                if (npcFile.exists()) {
                    npcFile.delete()
                    plugin.logger.info("Deleted old NPC file for $npcName")
                }

                // Generate new NPC data using the context generator
                val newNPCContext = plugin.npcContextGenerator.getOrCreateContextForNPC(npcName)
                if (newNPCContext != null) {
                    val newNPCData = NPCData(
                        newNPCContext.name,
                        newNPCContext.role,
                        newNPCContext.location,
                        context = newNPCContext.context,
                    )
                    future.complete(newNPCData)
                } else {
                    future.completeExceptionally(IllegalStateException("Failed to generate new NPC data for $npcName"))
                }
                return future
            }

            // Format the conversation history for AI analysis
            val messages = mutableListOf<ConversationMessage>()

            // Add system prompt
            messages.add(ConversationMessage(
                "system",
                """
                Generate meaningful memories for an NPC named $npcName based on their conversation history.
                
                Create 5-8 first-person memory entries covering the most important events and relationships.
                Each memory should be personal, detailed, and reflect the NPC's perspective and emotions.
                
                Format each memory entry as clear first-person reflections about specific events, people, or feelings.
                Focus on creating memories that define the character's personality, motivations, and relationships.
                Ensure memories reference specific characters, places, and events mentioned in the conversation history.
                The conversation history might be outdated, make sure they are tailored to any possible new location context.
                For example: Conversation history shows how Aaron seems trustable.
                But recent location context indicate that: Aaron is charged of murder, attempted murder and assault.
                IMPORTANT: Do NOT include meta-information like "memory_name:" or "significance:" in your response.
                Just provide the memory content paragraphs, separated by blank lines.
                """
            ))

            // Add relevant conversation history as context
            val conversationContext = recentConversations.joinToString("\n\n") { it.content }
            messages.add(ConversationMessage("user", "Here is the conversation history for $npcName:\n\n$conversationContext"))

            val locationContext = npcData.storyLocation?.context?.joinToString("\n\n") { it } ?: "No Location Context Available"
            messages.add(ConversationMessage("user", "Here is the location context for $npcName:\n\n $locationContext"))

            // Request AI to generate memories
            plugin.getAIResponse(messages).thenAccept { aiResponse ->
                if (aiResponse == null) {
                    plugin.logger.warning("Failed to get AI response for memory migration for $npcName")
                    future.complete(npcData)
                    return@thenAccept
                }

                // Split the AI response into separate memories
                val memoryTexts = aiResponse.split("\n\n").filter { it.isNotBlank() }

                val currentGameTime = plugin.timeService.getCurrentGameTime()
                val currentRealTime = Instant.now().toString()

                // Create memory objects
                val memories = memoryTexts.mapIndexed { index, content ->
                    // Generate significance based on position (earlier memories are more important)
                    val significance = 0.9 - (index * 0.05)

                    Memory(
                        id = "migrated_memory_${System.currentTimeMillis()}_${index}",
                        content = content.trim(),
                        gameCreatedAt = currentGameTime,
                        lastAccessed = currentGameTime,
                        power = 0.85,
                        _significance = significance.coerceAtLeast(0.6)
                    )
                }

                // Add the new memories to the NPC data
                npcData.memory.addAll(memories)

                // Update relationships based on memory content
                memories.forEach { memory ->
                    plugin.relationshipManager.updateRelationshipFromMemory(memory, npcName)
                }

                plugin.logger.info("Successfully migrated $npcName data: created ${memories.size} memories")
                future.complete(npcData)

            }.exceptionally { exception ->
                plugin.logger.warning("Error during NPC data migration for $npcName: ${exception.message}")
                future.complete(npcData)
                null
            }
        } catch (e: Exception) {
            plugin.logger.severe("Exception during NPC data migration for $npcName: ${e.message}")
            future.complete(npcData)
        }

        return future
    }
}