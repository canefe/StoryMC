package com.canefe.story.character.skill

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages skill providers and handles character skill interactions
 */
class SkillManager(
    private val plugin: Story,
) {
    private val skillProviders = ConcurrentHashMap<String, (UUID, Boolean) -> SkillProvider>()
    private val activeProvider = "mmocore" // Default provider

    init {
        // Register default providers
        registerSkillProvider("mmocore") { uuid, isPlayer ->
            MMOCoreSkillProvider(uuid, isPlayer)
        }

        plugin.logger.info("SkillManager initialized with ${skillProviders.size} providers")
    }

    fun load() {
        // Load active provider from config
        val providerId = plugin.config.skillProvider
        if (skillProviders.containsKey(providerId.lowercase())) {
            plugin.logger.info("Active skill provider set to: $providerId")
        } else {
            plugin.logger.warning("Configured skill provider '$providerId' not found, using default '$activeProvider'")
        }
    }

    /**
     * Register a new skill provider
     */
    fun registerSkillProvider(
        id: String,
        factory: (UUID, Boolean) -> SkillProvider,
    ) {
        skillProviders[id.lowercase()] = factory
        plugin.logger.info("Registered skill provider: $id")
    }

    /**
     * Create a skill provider for a character.
     * For players, delegates to the active provider (e.g. MMOCore).
     * For NPCs, use [createProviderForNPC] instead.
     */
    fun createProviderForCharacter(
        characterId: UUID,
        isPlayer: Boolean,
    ): SkillProvider {
        val factory = skillProviders[activeProvider] ?: return NoopSkillProvider()
        return factory(characterId, isPlayer)
    }

    /**
     * Create a skill provider for an NPC using their stored skills.
     * Returns a provider with empty skills if not yet generated.
     */
    fun createProviderForNPC(npcName: String): SkillProvider {
        val npcData = plugin.npcDataManager.getNPCData(npcName)
        val skills = npcData?.skills ?: emptyMap()
        val availableSkills = getAvailableSkills()
        return NPCSkillProvider(skills, availableSkills)
    }

    fun createProviderForNPC(npc: StoryNPC): SkillProvider {
        val npcData = plugin.npcDataManager.getNPCData(npc)
        val skills = npcData?.skills ?: emptyMap()
        val availableSkills = getAvailableSkills()
        return NPCSkillProvider(skills, availableSkills)
    }

    /**
     * Get the list of all available skills from any online player's provider.
     */
    fun getAvailableSkills(): List<String> {
        val player = plugin.server.onlinePlayers.firstOrNull() ?: return emptyList()
        val factory = skillProviders[activeProvider] ?: return emptyList()
        return factory(player.uniqueId, true).getAllSkills()
    }

    /**
     * A no-operation skill provider for when no provider is available
     */
    private class NoopSkillProvider : SkillProvider {
        override fun getSkillLevel(skillName: String): Int = 0

        override fun getAllSkills(): List<String> = emptyList()

        override fun hasSkill(skillName: String): Boolean = false
    }
}
