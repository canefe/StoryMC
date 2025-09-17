package com.canefe.story.character.data

import com.canefe.story.character.skill.SkillProvider
import com.canefe.story.location.data.StoryLocation
import com.canefe.story.npc.data.NPCData
import com.canefe.story.npc.memory.Memory
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.regex.Pattern
import kotlin.text.compareTo
import kotlin.text.get
import kotlin.text.toDouble

/**
 * CharacterData represents a character in the story, which can be an NPC or a player.
 * This centralizes common data for all characters in the story system.
 */
data class CharacterData(
    val id: UUID,
    var name: String,
    var role: String,
    var storyLocation: StoryLocation?,
    var context: String,
) {
    var memory: MutableList<Memory> = mutableListOf()
    var avatar: String = ""
    var knowledgeCategories: List<String> = listOf()
    var appearance: String = ""

    // Flag to distinguish between player characters and NPCs
    var isPlayer: Boolean = false

    // Skill system integration
    private var skillProvider: SkillProvider? = null

    // Cache for equipment skill modifiers
    private var skillModifierCache: MutableMap<String, Int> = mutableMapOf()
    private var lastCacheUpdateTime: Long = 0L
    private val CACHE_VALIDITY_PERIOD = 5000L // 5 seconds

    /**
     * Set the skill provider for this character
     */
    fun setSkillProvider(provider: SkillProvider) {
        this.skillProvider = provider
    }

    /**
     * Get the base skill level for a specific skill
     * @param skillName The name of the skill to retrieve
     * @return The skill level or 0 if not found
     */
    fun getSkill(skillName: String): Int = skillProvider?.getSkillLevel(skillName.lowercase()) ?: 0

    /**
     * Get the modified skill level for a specific skill, including equipment bonuses
     * @param skillName The name of the skill to retrieve
     * @return The modified skill level
     */
    fun getModifiedSkill(skillName: String): Int {
        val baseSkill = getSkill(skillName.lowercase())
        val modifier = getSkillModifier(skillName.lowercase())
        return baseSkill + modifier
    }

    /**
     * Update the cache of skill modifiers from equipped items
     */
    fun updateSkillModifierCache() {
        skillModifierCache.clear()

        // Only players have equipment
        if (!isPlayer) return

        val player = Bukkit.getPlayer(id) ?: return

        // Check main hand, offhand, and armor items
        val equipmentToCheck =
            listOf(
                player.inventory.itemInMainHand,
                player.inventory.itemInOffHand,
            ) + player.inventory.armorContents.filterNotNull()

        // Pattern to match "+X Skill" or "-X Skill" in lore
        val skillModifierPattern = Pattern.compile("([+\\-]\\d+)\\s+([\\w\\s]+)")

        for (item in equipmentToCheck) {
            checkItemForSkillModifiers(item, skillModifierPattern)
        }

        lastCacheUpdateTime = System.currentTimeMillis()
    }

    /**
     * Check an item for skill modifiers in its lore
     */
    private fun checkItemForSkillModifiers(
        item: ItemStack,
        pattern: Pattern,
    ) {
        if (!item.hasItemMeta() || !item.itemMeta.hasLore()) return

        val lore = item.itemMeta.lore() ?: return

        for (line in lore) {
            val matcher = pattern.matcher(line.toString().replace("§[0-9a-fk-or]".toRegex(), "")) // Remove color codes

            while (matcher.find()) {
                val modifierStr = matcher.group(1)
                val skillName = matcher.group(2).trim().lowercase()

                // Verify this is an actual skill
                if (skillProvider?.hasSkill(skillName) == true) {
                    val modifier = modifierStr.toIntOrNull() ?: continue
                    skillModifierCache[skillName] = (skillModifierCache[skillName] ?: 0) + modifier
                }
            }
        }
    }

    /**
     * Get equipment bonuses for a specific skill from items
     * @param skillName The name of the skill to check modifiers for
     * @return The total equipment bonus value from all equipped items
     */
    fun getEquipmentModifier(skillName: String): Int {
        val lowerCaseSkill = skillName.lowercase()

        // If player is offline, return cached value or 0
        if (isPlayer && Bukkit.getPlayer(id) == null) {
            return skillModifierCache[lowerCaseSkill] ?: 0
        }

        // If cache is valid, return cached value
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCacheUpdateTime < CACHE_VALIDITY_PERIOD) {
            return skillModifierCache[lowerCaseSkill] ?: 0
        }

        // Update cache
        updateSkillModifierCache()
        return skillModifierCache[lowerCaseSkill] ?: 0
    }

    /**
     * Calculate the full skill modifier (base skill/6 + equipment bonuses)
     * @param skillName The name of the skill
     * @return The calculated full modifier for skill checks
     */
    fun getSkillModifier(skillName: String): Int {
        val lowerCaseSkill = skillName.lowercase()
        val baseSkill = getSkill(lowerCaseSkill)
        val baseModifier = Math.floor(baseSkill.toDouble() / 6.0).toInt()
        val equipmentModifier = getEquipmentModifier(lowerCaseSkill)
        return baseModifier + equipmentModifier
    }

    /**
     * Calculate the skill check modifier - floor(skillLevel / 6) + equipment bonuses
     * @param skillName The name of the skill to calculate the check modifier for
     * @return The calculated modifier for skill checks
     */
    fun getSkillCheckModifier(skillName: String): Int = getSkillModifier(skillName.lowercase())

    /**
     * Helper method to add a memory
     */
    fun addMemory(
        content: String,
        power: Double = 1.0,
    ): Memory {
        val memory = Memory(content = content, power = power)
        this.memory.add(memory)
        return memory
    }

    override fun toString(): String = "CharacterData{name=$name, role=$role, location=$storyLocation, context=$context}"

    companion object {
        /**
         * Create a CharacterData instance from NPCData for migration
         */
        fun fromNPCData(
            id: UUID,
            npcData: NPCData,
        ): CharacterData =
            CharacterData(
                id = id,
                name = npcData.name,
                role = npcData.role,
                storyLocation = npcData.storyLocation,
                context = npcData.context,
            ).apply {
                // Convert memories
                this.memory =
                    npcData.memory
                        .map { npcMemory ->
                            Memory(
                                id = npcMemory.id,
                                content = npcMemory.content,
                                realCreatedAt = npcMemory.realCreatedAt,
                                gameCreatedAt = npcMemory.gameCreatedAt,
                                power = npcMemory.power,
                                lastAccessed = npcMemory.lastAccessed,
                                _significance = npcMemory.significance,
                            )
                        }.toMutableList()

                this.avatar = npcData.avatar
                this.knowledgeCategories = npcData.knowledgeCategories
                this.appearance = npcData.appearance
                this.isPlayer = false
            }

        /**
         * Create a CharacterData instance for a Player
         */
        fun fromPlayer(player: Player): CharacterData =
            CharacterData(
                id = player.uniqueId,
                name = player.name,
                role = "Player",
                storyLocation = null, // Will need to be set separately
                context = "A player in the world.",
            ).apply {
                this.isPlayer = true
            }
    }
}
