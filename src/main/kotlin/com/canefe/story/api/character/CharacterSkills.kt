package com.canefe.story.api.character

import com.canefe.story.character.skill.SkillProvider
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.regex.Pattern

/**
 * Encapsulates skill access and equipment-based modifier calculation for a character.
 */
class CharacterSkills(
    private val provider: SkillProvider,
    private val player: Player? = null,
) {
    private var modifierCache: MutableMap<String, Int> = mutableMapOf()
    private var lastCacheUpdate: Long = 0L
    private val CACHE_TTL = 5000L

    fun getLevel(skill: String): Int = provider.getSkillLevel(skill.lowercase())

    fun getAll(): List<String> = provider.getAllSkills()

    fun has(skill: String): Boolean = provider.hasSkill(skill.lowercase())

    fun getModifier(skill: String): Int {
        val base = getLevel(skill.lowercase())
        val baseModifier = Math.floor(base / 6.0).toInt()
        return baseModifier + getEquipmentBonus(skill.lowercase())
    }

    fun getEquipmentBonus(skill: String): Int {
        val lowerSkill = skill.lowercase()
        val player = player ?: return 0

        if (player.isOnline.not()) return modifierCache[lowerSkill] ?: 0

        val now = System.currentTimeMillis()
        if (now - lastCacheUpdate < CACHE_TTL) return modifierCache[lowerSkill] ?: 0

        refreshCache(player)
        return modifierCache[lowerSkill] ?: 0
    }

    private fun refreshCache(player: Player) {
        modifierCache.clear()
        val items =
            listOf(player.inventory.itemInMainHand, player.inventory.itemInOffHand) +
                player.inventory.armorContents.filterNotNull()
        val pattern = Pattern.compile("([+\\-]\\d+)\\s+([\\w\\s]+)")
        items.forEach { item -> scanItem(item, pattern) }
        lastCacheUpdate = System.currentTimeMillis()
    }

    private fun scanItem(
        item: ItemStack,
        pattern: Pattern,
    ) {
        if (!item.hasItemMeta() || !item.itemMeta.hasLore()) return
        item.itemMeta.lore()?.forEach { line ->
            val matcher = pattern.matcher(line.toString().replace("§[0-9a-fk-or]".toRegex(), ""))
            while (matcher.find()) {
                val skillName = matcher.group(2).trim().lowercase()
                if (provider.hasSkill(skillName)) {
                    val modifier = matcher.group(1).toIntOrNull() ?: return@forEach
                    modifierCache[skillName] = (modifierCache[skillName] ?: 0) + modifier
                }
            }
        }
    }
}
