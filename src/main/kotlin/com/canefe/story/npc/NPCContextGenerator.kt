package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.npc.data.NPCContext
import com.canefe.story.npc.data.NPCData
import com.canefe.story.npc.memory.Memory
import net.citizensnpcs.api.npc.NPC
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.random.Random

/**
 * Service responsible for generating and updating NPC context information.
 */
class NPCContextGenerator(
    private val plugin: Story,
) {
    /**
     * Generates a default context for a new NPC.
     */
    private fun generateDefaultContext(npcName: String): String {
        val random = Random(System.currentTimeMillis())

        // Randomly select personality traits
        val trait = plugin.config.traitList.random(random)
        val quirk = plugin.config.quirkList.random(random)
        val motivation = plugin.config.motivationList.random(random)
        val flaw = plugin.config.flawList.random(random)
        val tone = plugin.config.toneList.random(random)

        // Construct personality description
        val personality =
            " is $trait, has the quirk of $quirk, " +
                "is motivated by $motivation, and their flaw is $flaw. " +
                "They speak in a $tone tone."

        // Construct the context
        return "$npcName$personality"
    }

    /**
     * Generates a temporary personality context for generic NPCs.
     * This creates new personality traits for each conversation while preserving custom context.
     */
    private fun generateTemporaryPersonality(
        npcName: String,
        customContext: String,
    ): String {
        val random = Random(System.currentTimeMillis())

        // Randomly select personality traits
        val trait = plugin.config.traitList.random(random)
        val quirk = plugin.config.quirkList.random(random)
        val motivation = plugin.config.motivationList.random(random)
        val flaw = plugin.config.flawList.random(random)
        val tone = plugin.config.toneList.random(random)

        // Construct temporary personality description
        val temporaryPersonality =
            "$npcName is $trait, has the quirk of $quirk, " +
                "is motivated by $motivation, and their flaw is $flaw. " +
                "They speak in a $tone tone. " +
                "The time is ${plugin.timeService.getHours()}:${plugin.timeService.getMinutes()} and the season is ${plugin.timeService.getSeason()}."

        // Combine with custom context if it exists
        return if (customContext.isNotBlank()) {
            "$temporaryPersonality $customContext"
        } else {
            temporaryPersonality
        }
    }

    /**
     * Get or create context for an NPC using the NPC entity (preferred method)
     */
    fun getOrCreateContextForNPC(npc: NPC): NPCContext? =
        getOrCreateContextForNPCInternal(npc.name, npc.uniqueId.toString())

    /**
     * Get or create context for an NPC using just the name (fallback method)
     */
    fun getOrCreateContextForNPC(npcName: String): NPCContext? = getOrCreateContextForNPCInternal(npcName, null)

    private fun getOrCreateContextForNPCInternal(
        npcName: String,
        actualEntityId: String?,
    ): NPCContext? {
        try {
            // FIRST: Check if this is a generic NPC that needs name aliasing
            // Load existing NPC data to check if it's generic
            val npcData = plugin.npcDataManager.getNPCData(npcName)

            // If this is a generic NPC with a name bank, resolve its canonical name
            val actualName =
                if (npcData?.generic == true && npcData.nameBank != null) {
                    // Use name resolver to get or generate canonical name
                    val canonicalName =
                        plugin.npcNameResolver.resolveCanonicalName(
                            displayName = npcName,
                            // Use actual entity ID first, then stored npcId, then fallback to name
                            entityId = actualEntityId ?: npcData.npcId ?: npcName,
                            location = npcData.storyLocation?.name,
                        )

                    // If a new canonical name was generated and this NPC is in an active conversation,
                    // we need to create a new Citizens NPC and replace it in the conversation
                    if (canonicalName != npcName && npcData.canonicalName != canonicalName) {
                        // Find the NPC in any active conversation
                        val activeConversations = plugin.conversationManager.getAllActiveConversations()
                        for (conversation in activeConversations) {
                            val originalNPC = conversation.getNPCByName(npcName)
                            if (originalNPC != null) {
                                // Get the alias that was generated
                                val alias =
                                    plugin.npcNameManager.getOrCreateAlias(
                                        npcId =
                                            actualEntityId ?: npcData.npcId
                                                ?: npcName,
                                        // Use actual entity ID first
                                        anchorKey = npcData.anchorKey,
                                        nameBankName = npcData.nameBank!!,
                                        location = npcData.storyLocation?.name,
                                    )

                                // Create and replace the NPC in conversations
                                plugin.npcNameResolver.createAndReplaceNPCInConversations(originalNPC, alias)

                                break // Only need to do this once
                            }
                        }
                    }

                    canonicalName
                } else {
                    npcName
                }

            // Load existing NPC data including all data and memories using the resolved name
            val finalNpcData =
                plugin.npcDataManager.getNPCData(npcName) ?: NPCData(
                    actualName, // Use the canonical name for the display name in context
                    "Default role",
                    plugin.locationManager.getLocation("Village")
                        ?: plugin.locationManager.createLocation("Village", null)
                        ?: return null,
                    context = generateDefaultContext(actualName), // Use canonical name in context
                )

            // Save updated NPC data with existing memories preserved
            plugin.npcDataManager.saveNPCData(npcName, finalNpcData)

            // For generic NPCs, generate temporary personality while preserving custom context
            val finalContext =
                if (finalNpcData.generic) {
                    generateTemporaryPersonality(actualName, finalNpcData.context) // Use canonical name in personality
                } else {
                    finalNpcData.context
                }

            // For generic NPCs, use empty memories list instead of persistent memories
            val memories =
                if (finalNpcData.generic) {
                    emptyList<Memory>()
                } else {
                    finalNpcData.memory
                }

            // Get relationships for this NPC
            val relationships = plugin.relationshipManager.getAllRelationships(npcName)

            return NPCContext(
                finalNpcData.name, // Use canonical name in the context
                role = finalNpcData.role,
                context = finalContext,
                appearance = finalNpcData.appearance,
                location = finalNpcData.storyLocation!!,
                avatar = finalNpcData.avatar,
                memories = memories,
                relationships = relationships,
                customVoice = finalNpcData.customVoice,
                generic = finalNpcData.generic,
            )
        } catch (e: Exception) {
            plugin.logger.warning("Error while updating NPC context: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private val generalContexts: MutableList<String> = mutableListOf()
    private val generalContextFile = File(plugin.dataFolder, "general-contexts.yml")

    init {
        loadGeneralContexts()
    }

    fun loadConfig() {
        loadGeneralContexts()
    }

    private fun loadGeneralContexts() {
        generalContexts.clear()

        if (!generalContextFile.exists()) {
            return
        }

        val config = YamlConfiguration.loadConfiguration(generalContextFile)
        generalContexts.addAll(config.getStringList("contexts"))
    }

    private fun saveGeneralContexts() {
        val config = YamlConfiguration()
        config.set("contexts", generalContexts)
        config.save(generalContextFile)
    }

    fun getGeneralContexts(): List<String> = generalContexts.toList()

    fun addGeneralContext(context: String) {
        if (!generalContexts.contains(context)) {
            generalContexts.add(context)
            saveGeneralContexts()
        }
    }

    fun removeGeneralContext(context: String) {
        if (generalContexts.remove(context)) {
            saveGeneralContexts()
        }
    }
}
