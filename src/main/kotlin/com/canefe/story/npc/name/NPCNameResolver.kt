package com.canefe.story.npc.name

import com.canefe.story.Story
import com.canefe.story.conversation.Conversation
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.npc.data.NPCData
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.SkinTrait
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import java.util.*

/**
 * Service that handles NPC name resolution and integration with the existing system.
 * This acts as the bridge between display names (Citizens/MythicMobs) and canonical names.
 */
class NPCNameResolver(
    private val plugin: Story,
) {
    private val nameManager: NPCNameManager by lazy { plugin.npcNameManager }

    /**
     * Resolves an NPC's canonical name from their display name or entity.
     * This is the primary integration point for the name aliasing system.
     */
    fun resolveCanonicalName(npc: NPC): String =
        resolveCanonicalNameInternal(
            npc.name,
            npc.uniqueId.toString(),
            plugin.locationManager.getLocationByPosition(npc.entity.location, 150.0)?.name,
        )

    /**
     * Resolves an NPC's canonical name from a MythicMob entity
     */
    fun resolveCanonicalName(
        entity: Entity,
        displayName: String,
    ): String = resolveCanonicalNameInternal(displayName, entity.uniqueId.toString(), entity.location.toString())

    /**
     * Resolves canonical name with explicit parameters (used by NPCContextGenerator)
     */
    fun resolveCanonicalName(
        displayName: String,
        entityId: String,
        location: String?,
    ): String = resolveCanonicalNameInternal(displayName, entityId, location)

    /**
     * Core name resolution logic
     */
    private fun resolveCanonicalNameInternal(
        displayName: String,
        entityId: String,
        location: String?,
    ): String {
        // First, try to load existing NPC data to see if we already have a canonical name
        val existingData = plugin.npcDataManager.getNPCData(displayName)

        if (existingData?.canonicalName != null) {
            return existingData.canonicalName!!
        }

        // Check if this is a generic NPC that should use name aliasing
        if (existingData?.generic == true && existingData.nameBank != null) {
            // Generate or retrieve alias for this generic NPC
            val alias =
                nameManager.getOrCreateAlias(
                    npcId = entityId,
                    anchorKey = existingData.anchorKey,
                    nameBankName = existingData.nameBank,
                    location = location,
                )

            // Create new file with canonical name - use canonicalName for filename to match data lookup
            val canonicalFileName = alias.canonicalName.replace(" ", "_").lowercase()

            // Copy the generic NPC data to a new file with the canonical name
            val newNpcData =
                NPCData(
                    name = alias.canonicalName, // Use canonical name as the display name
                    role = existingData.role,
                    storyLocation = existingData.storyLocation,
                    context = existingData.context,
                ).apply {
                    // Copy all properties from the original
                    memory = existingData.memory.toMutableList()
                    avatar = existingData.avatar
                    knowledgeCategories = existingData.knowledgeCategories
                    appearance = existingData.appearance
                    randomPathing = existingData.randomPathing
                    customVoice = existingData.customVoice
                    generic = false // This is now a specific NPC, not generic

                    // Set the alias fields
                    nameBank = existingData.nameBank
                    npcId = alias.displayHandle // Use displayHandle as npcId since that's the Citizens NPC name
                    anchorKey = existingData.anchorKey
                    canonicalName = alias.canonicalName
                    displayHandle = alias.displayHandle
                    callsign = alias.callsign
                }

            // Save the new specific NPC file
            plugin.npcDataManager.saveNPCData(canonicalFileName, newNpcData)

            // Generate contextual bio for the new specific NPC
            generateContextualBioForNewNPC(newNpcData, existingData)

            plugin.logger.info(
                "Created new NPC file '$canonicalFileName.yml' for aliased NPC '${alias.canonicalName}' (was generic '$displayName')",
            )

            return alias.canonicalName
        }

        // For non-generic NPCs or NPCs without name banks, use the display name as canonical name
        return displayName
    }

    /**
     * Creates a new Citizens NPC with the canonical name and replaces the generic NPC in conversations.
     * This is called when a generic NPC gets aliased and needs to be replaced with a properly named Citizens NPC.
     */
    fun createAndReplaceNPCInConversations(
        originalNPC: NPC,
        alias: NPCNameManager.NPCAlias,
    ): NPC? {
        try {
            // Create a new Citizens NPC with the canonical name
            val newNPC = createCitizensNPCWithAlias(originalNPC, alias)
            if (newNPC == null) {
                plugin.logger.warning("Failed to create new Citizens NPC for alias '${alias.canonicalName}'")
                return null
            }

            // Find any active conversations with the original NPC and replace it
            val activeConversations = plugin.conversationManager.getAllActiveConversations()
            for (conversation in activeConversations) {
                if (conversation.hasNPC(originalNPC)) {
                    replaceNPCInConversation(conversation, originalNPC, newNPC)
                    plugin.logger.info(
                        "Replaced NPC '${originalNPC.name}' with '${newNPC.name}' in conversation ${conversation.id}",
                    )
                }
            }

            return newNPC
        } catch (e: Exception) {
            plugin.logger.severe("Error creating and replacing NPC: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    /**
     * Creates a new Citizens NPC with the canonical name at the same location as the original
     */
    private fun createCitizensNPCWithAlias(
        originalNPC: NPC,
        alias: NPCNameManager.NPCAlias,
    ): NPC? {
        try {
            if (!originalNPC.isSpawned) {
                plugin.logger.warning("Cannot create alias NPC - original NPC '${originalNPC.name}' is not spawned")
                return null
            }

            val originalEntity = originalNPC.entity ?: return null
            val location = originalEntity.location
            val entityType = originalEntity.type

            // Create new Citizens NPC with the canonical name
            val npcRegistry =
                net.citizensnpcs.api.CitizensAPI
                    .getNPCRegistry()
            val newNPC = npcRegistry.createNPC(entityType, alias.displayHandle)

            // Despawn the original NPC
            originalNPC.despawn()
            val clone = originalNPC.clone() // do this so id is different
            clone.despawn()

            val skin = originalNPC.getOrAddTrait(SkinTrait::class.java)
            val texture = skin.texture
            val signature = skin.signature
            val skinTrait = newNPC.getOrAddTrait(SkinTrait::class.java)
            Bukkit.getScheduler().runTask(
                CitizensAPI.getPlugin(),
                Runnable {
                    try {
                        skinTrait.setSkinPersistent(skin.skinName, signature, texture)
                    } catch (e: IllegalArgumentException) {
                    }
                },
            )

            // execute conosle command
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "npc remove ${originalNPC.id}")

            // Spawn the new NPC at the same location
            newNPC.spawn(location)

            // Copy traits and properties from the original NPC
            copyNPCProperties(originalNPC, newNPC)

            plugin.logger.info(
                "Created new Citizens NPC '${newNPC.name}' (ID: ${newNPC.id}) for alias '${alias.canonicalName}'",
            )

            return newNPC
        } catch (e: Exception) {
            plugin.logger.severe("Error creating Citizens NPC with alias: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    /**
     * Copies basic properties and traits from the original NPC to the new aliased NPC
     */
    private fun copyNPCProperties(
        originalNPC: NPC,
        newNPC: NPC,
    ) {
        try {
            val originalEntity = originalNPC.entity
            val newEntity = newNPC.entity

            if (originalEntity != null && newEntity != null) {
                // Copy location and rotation
                newEntity.teleport(originalEntity.location)

                // Copy basic entity properties if they're living entities
                if (originalEntity is org.bukkit.entity.LivingEntity && newEntity is org.bukkit.entity.LivingEntity) {
                    // Copy equipment if applicable
                    if (originalEntity.equipment != null && newEntity.equipment != null) {
                        val originalEquipment = originalEntity.equipment!!
                        val newEquipment = newEntity.equipment!!

                        newEquipment.helmet = originalEquipment.helmet
                        newEquipment.chestplate = originalEquipment.chestplate
                        newEquipment.leggings = originalEquipment.leggings
                        newEquipment.boots = originalEquipment.boots
                        newEquipment.setItemInMainHand(originalEquipment.itemInMainHand)
                        newEquipment.setItemInOffHand(originalEquipment.itemInOffHand)
                    }
                }
            }

            // Copy Citizens traits (like LookClose, etc.)
            // Note: This is a basic implementation - you might want to add more specific trait copying
        } catch (e: Exception) {
            plugin.logger.warning("Error copying NPC properties: ${e.message}")
        }
    }

    /**
     * Replaces an NPC in a conversation with a new NPC
     */
    private fun replaceNPCInConversation(
        conversation: Conversation,
        oldNPC: NPC,
        newNPC: NPC,
    ) {
        // Remove the old NPC from the conversation
        conversation.removeNPC(oldNPC)

        // Add the new NPC to the conversation
        conversation.addNPC(newNPC)

        // Update conversation history to use the new name
        updateConversationHistoryNames(conversation, oldNPC.name, newNPC.name)

        // Notify players about the name change
        conversation.players.forEach { playerId ->
            val player = org.bukkit.Bukkit.getPlayer(playerId)
            player?.sendMessage("§e${oldNPC.name} is now known as ${newNPC.name}")
        }

        // Add a system message to the conversation history
        conversation.addSystemMessage("${oldNPC.name} is now known as ${newNPC.name}")
    }

    /**
     * Updates conversation history to replace old NPC name with new name
     */
    private fun updateConversationHistoryNames(
        conversation: Conversation,
        oldName: String,
        newName: String,
    ) {
        // Access the mutable history through reflection or a public method
        // Since history is read-only, we need to update the internal messages
        val historyField = conversation::class.java.getDeclaredField("_history")
        historyField.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val mutableHistory = historyField.get(conversation) as MutableList<ConversationMessage>

        // Update all messages that start with the old name
        for (message in mutableHistory) {
            if (message.content.startsWith("$oldName: ")) {
                val newContent = message.content.replaceFirst("$oldName: ", "$newName: ")
                // Create a new message with updated content
                val updatedMessage = ConversationMessage(message.role, newContent)
                val index = mutableHistory.indexOf(message)
                mutableHistory[index] = updatedMessage
            }
        }
    }

    /**
     * Generates a contextual bio for a newly created specific NPC using AI
     */
    private fun generateContextualBioForNewNPC(
        newNpcData: NPCData,
        originalGenericData: NPCData,
    ) {
        try {
            val messages: MutableList<ConversationMessage> = ArrayList()

            // Add general context
            messages.add(
                ConversationMessage(
                    "system",
                    plugin.npcContextGenerator.getGeneralContexts().joinToString("\n"),
                ),
            )

            // Add location context
            newNpcData.storyLocation?.let { location ->
                messages.add(
                    ConversationMessage(
                        "system",
                        location.getContextForPrompt(plugin.locationManager),
                    ),
                )
            }

            // Find relevant lore related to the name bank
            val loreContexts =
                originalGenericData.nameBank?.let { nameBank ->
                    plugin.lorebookManager.findLoresByKeywords(nameBank)
                } ?: emptyList()

            if (loreContexts.isNotEmpty()) {
                messages.add(
                    ConversationMessage(
                        "system",
                        "Include these world lore elements in your writing:\n" +
                            loreContexts.joinToString("\n\n") { "- ${it.loreName}: ${it.context}" },
                    ),
                )
            }

            // Create the main generation prompt
            val prompt =
                """
                Generate detailed RPG character information for an NPC named ${newNpcData.name} who is a ${originalGenericData.role} in the location ${newNpcData.storyLocation?.name}.

                This character was originally a generic "${originalGenericData.role}" but has now been given the specific identity "${newNpcData.name}".

                Return ONLY a valid JSON object with these fields:
                1. "context": Background story, personality, motivations, and role in society. Include their duties as a ${originalGenericData.role}, their personal history, and what makes them unique among other guards/officials.
                2. "appearance": Detailed physical description including clothing, notable features, equipment, and how they carry themselves.

                Make the character interesting with clear motivations, quirks, and depth while staying true to their role as a ${originalGenericData.role}.
                The JSON must be properly formatted with quotes escaped.
                """.trimIndent()

            messages.add(ConversationMessage("system", prompt))
            messages.add(ConversationMessage("user", "Generate the character information for ${newNpcData.name}."))

            // Generate the bio asynchronously
            plugin.getAIResponse(messages).thenAccept { response ->
                Bukkit.getScheduler().runTask(
                    plugin,
                    Runnable {
                        if (response != null) {
                            try {
                                // Extract JSON and update NPC data
                                val jsonContent = extractJsonFromString(response)
                                val gson = com.google.gson.Gson()
                                val npcInfo = gson.fromJson(jsonContent, NPCInfo::class.java)

                                // Update the NPC data with the generated bio
                                newNpcData.context = npcInfo.context ?: newNpcData.context
                                newNpcData.appearance = npcInfo.appearance ?: newNpcData.appearance

                                // Save the updated data using the same filename convention as the initial creation
                                val canonicalFileName = newNpcData.name.replace(" ", "_").lowercase()
                                plugin.npcDataManager.saveNPCData(canonicalFileName, newNpcData)

                                plugin.logger.info("Generated contextual bio for NPC '${newNpcData.name}'")
                            } catch (e: Exception) {
                                plugin.logger.warning(
                                    "Failed to parse AI response for NPC bio generation: ${e.message}",
                                )
                            }
                        } else {
                            plugin.logger.warning("Failed to generate bio for NPC '${newNpcData.name}'")
                        }
                    },
                )
            }
        } catch (e: Exception) {
            plugin.logger.warning("Error generating contextual bio for NPC '${newNpcData.name}': ${e.message}")
        }
    }

    /**
     * Extracts JSON content from a string that may contain additional text
     */
    private fun extractJsonFromString(input: String): String {
        val startIndex = input.indexOf('{')
        val endIndex = input.lastIndexOf('}')

        return if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
            input.substring(startIndex, endIndex + 1)
        } else {
            input
        }
    }

    /**
     * Data class for parsing NPC information from AI response
     */
    private data class NPCInfo(
        val context: String?,
        val appearance: String?,
    )
}
