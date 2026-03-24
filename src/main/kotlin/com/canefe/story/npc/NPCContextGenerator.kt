package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.api.character.AICharacter
import com.canefe.story.api.character.Character
import com.canefe.story.api.character.CharacterRecord
import com.canefe.story.api.character.PlayerCharacter
import com.canefe.story.npc.data.NPCContext
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
            val record: CharacterRecord? =
                when {
                    npc != null -> plugin.characterRegistry.getByStoryNPC(npc)
                    else -> plugin.characterRegistry.getByName(displayName)
                }

            // Resolve location
            var location = plugin.locationManager.getOrCreateDefaultLocation()
            if (npc != null && npc.isSpawned && npc.entity != null) {
                val npcLocation = plugin.locationManager.getLocationByPosition2D(npc.entity!!.location)
                if (npcLocation != null) location = npcLocation
            }

            val avatar = record?.let { plugin.characterRegistry.getMinecraftConfig(it.id)?.avatar ?: "" } ?: ""

            // Get relationships
            val relationships = plugin.relationshipManager.getAllRelationships(displayName)

            return NPCContext(
                name = record?.name ?: displayName,
                role = "",
                context = generateDefaultContext(record?.name ?: displayName),
                appearance = record?.appearance ?: "",
                location = location,
                avatar = avatar,
                memories = emptyList(),
                relationships = relationships,
                customVoice = record?.customVoice,
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
