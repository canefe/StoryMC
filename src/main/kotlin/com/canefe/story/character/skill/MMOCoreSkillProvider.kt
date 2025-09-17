package com.canefe.story.character.skill

import net.Indyuce.mmocore.MMOCore
import net.Indyuce.mmocore.api.player.PlayerData
import java.util.UUID
import kotlin.text.contains

/**
 * Implementation of SkillProvider for MMOCore plugin
 */
class MMOCoreSkillProvider(
    private val characterId: UUID,
    private val isPlayer: Boolean,
) : SkillProvider {
    override fun getSkillLevel(skillName: String): Int {
        if (!isPlayer) return 0 // MMOCore only supports players

        // Get player from UUID
        val player = org.bukkit.Bukkit.getPlayer(characterId) ?: return 0

        // Check if MMOCore is available
        if (!org.bukkit.Bukkit
                .getPluginManager()
                .isPluginEnabled("MMOCore")
        ) {
            return 0
        }

        try {
            val skills = PlayerData.get(player).collectionSkills
            if (skills == null) {
                // Log error or handle case where skills collection is null
                return 0
            }
            return skills.getLevel(skillName.lowercase())
        } catch (e: Exception) {
            // Log error
            return 0
        }
    }

    override fun getAllSkills(): List<String> {
        if (!isPlayer ||
            !org.bukkit.Bukkit
                .getPluginManager()
                .isPluginEnabled("MMOCore")
        ) {
            return emptyList()
        }

        val player = org.bukkit.Bukkit.getPlayer(characterId) ?: return emptyList()
        val skills = PlayerData.get(player).collectionSkills ?: return emptyList()
        return MMOCore.plugin.professionManager
            .getAll()
            .map { it.name }
    }

    override fun hasSkill(skillName: String): Boolean {
        if (!isPlayer ||
            !org.bukkit.Bukkit
                .getPluginManager()
                .isPluginEnabled("MMOCore")
        ) {
            return false
        }

        // Convert both the skill name and all skills in the list to lowercase for case-insensitive comparison
        val lowerCaseSkillName = skillName.lowercase()
        val lowerCaseSkills = getAllSkills().map { it.lowercase() }
        return lowerCaseSkills.contains(lowerCaseSkillName)
    }
}
