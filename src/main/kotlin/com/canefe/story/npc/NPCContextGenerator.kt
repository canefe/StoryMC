package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.api.character.AICharacter
import com.canefe.story.api.character.Character
import com.canefe.story.api.character.CharacterDTO
import com.canefe.story.api.character.PlayerCharacter
import com.canefe.story.npc.data.NPCContext
import com.canefe.story.npc.data.NPCData
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import kotlin.random.Random

/**
 * Service responsible for generating and updating NPC context information.
 */
class NPCContextGenerator(
    private val plugin: Story,
) {
    private fun generateDefaultContext(name: String): String {
        val random = Random(System.currentTimeMillis())
        val trait = plugin.config.traitList.random(random)
        val quirk = plugin.config.quirkList.random(random)
        val motivation = plugin.config.motivationList.random(random)
        val flaw = plugin.config.flawList.random(random)
        val tone = plugin.config.toneList.random(random)
        return "$name is $trait, has the quirk of $quirk, " +
            "is motivated by $motivation, and their flaw is $flaw. " +
            "They speak in a $tone tone."
    }

    private fun generateTemporaryPersonality(
        name: String,
        customContext: String,
    ): String {
        val random = Random(System.currentTimeMillis())
        val trait = plugin.config.traitList.random(random)
        val quirk = plugin.config.quirkList.random(random)
        val motivation = plugin.config.motivationList.random(random)
        val flaw = plugin.config.flawList.random(random)
        val tone = plugin.config.toneList.random(random)
        val temporaryPersonality =
            "$name is $trait, has the quirk of $quirk, " +
                "is motivated by $motivation, and their flaw is $flaw. " +
                "They speak in a $tone tone. " +
                "The time is ${plugin.timeService.getHours()}:${plugin.timeService.getMinutes()} and the season is ${plugin.timeService.getSeason()}."
        return if (customContext.isNotBlank()) "$temporaryPersonality $customContext" else temporaryPersonality
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Primary entry point: get or create context for a Character.
     */
    fun getOrCreateContextForNPC(character: Character): NPCContext? {
        val npc = if (character is AICharacter) character.npc else null
        val player = if (character is PlayerCharacter) character.player else null
        return getOrCreateContextInternal(
            displayName = character.name,
            entityId = character.entityId.toString(),
            npc = npc,
            player = player,
        )
    }

    /**
     * Get or create context for a StoryNPC.
     */
    fun getOrCreateContextForNPC(npc: StoryNPC): NPCContext? =
        getOrCreateContextInternal(
            displayName = npc.name,
            entityId = npc.uniqueId.toString(),
            npc = npc,
        )

    // ── Internal ────────────────────────────────────────────────────────

    private fun getOrCreateContextInternal(
        displayName: String,
        entityId: String?,
        npc: StoryNPC? = null,
        player: Player? = null,
    ): NPCContext? {
        try {
            val npcData =
                when {
                    npc != null -> plugin.npcDataManager.getNPCData(npc)
                    else -> plugin.npcDataManager.getNPCData(displayName)
                }

            // If this is a generic NPC with a name bank, resolve its canonical name
            val actualName =
                if (npcData?.generic == true && npcData.nameBank != null) {
                    val canonicalName =
                        plugin.npcNameResolver.resolveCanonicalName(
                            displayName = displayName,
                            entityId = entityId ?: npcData.npcId ?: displayName,
                            location = npcData.storyLocation?.name,
                        )

                    if (canonicalName != displayName && npcData.canonicalName != canonicalName) {
                        val activeConversations = plugin.conversationManager.getAllActiveConversations()
                        for (conversation in activeConversations) {
                            val originalNPC = conversation.getNPCByName(displayName)
                            if (originalNPC != null) {
                                val alias =
                                    plugin.npcNameManager.getOrCreateAlias(
                                        npcId = entityId ?: npcData.npcId ?: displayName,
                                        anchorKey = npcData.anchorKey,
                                        nameBankName = npcData.nameBank!!,
                                        location = npcData.storyLocation?.name,
                                    )
                                plugin.npcNameResolver.createAndReplaceNPCInConversations(originalNPC, alias)
                                break
                            }
                        }
                    }

                    canonicalName
                } else {
                    displayName
                }

            // Resolve location
            var location = plugin.locationManager.getOrCreateDefaultLocation()
            if (npc != null && npc.isSpawned && npc.entity != null) {
                val npcLocation = plugin.locationManager.getLocationByPosition2D(npc.entity!!.location)
                if (npcLocation != null) location = npcLocation
            }

            // Load NPC data
            val finalNpcData =
                when {
                    npc != null -> plugin.npcDataManager.getNPCData(npc)
                    else -> plugin.npcDataManager.getNPCData(displayName)
                } ?: NPCData(
                    actualName,
                    "Default role",
                    location,
                    context = generateDefaultContext(actualName),
                )

            // Save updated NPC data
            plugin.storage.saveCharacterData(CharacterDTO.from(finalNpcData))

            // For generic NPCs, generate temporary personality
            val finalContext =
                if (finalNpcData.generic) {
                    generateTemporaryPersonality(actualName, finalNpcData.context)
                } else {
                    finalNpcData.context
                }

            // For generic NPCs, use empty memories
            val memories = if (finalNpcData.generic) emptyList() else finalNpcData.memory

            // Get relationships
            val relationships = plugin.relationshipManager.getAllRelationships(displayName)

            return NPCContext(
                name = finalNpcData.name,
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

    // ── General contexts ────────────────────────────────────────────────

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
        if (!generalContextFile.exists()) return
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
