package com.canefe.story.character.skill

import com.canefe.story.Story
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
     * Create a skill provider for a character
     */
    fun createProviderForCharacter(
        characterId: UUID,
        isPlayer: Boolean,
    ): SkillProvider {
        val factory = skillProviders[activeProvider] ?: return NoopSkillProvider()
        return factory(characterId, isPlayer)
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
