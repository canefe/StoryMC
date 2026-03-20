package com.canefe.story.character.skill

/**
 * Skill provider for NPCs that reads from a pre-generated skill map.
 * Skills are generated on demand via LLM and persisted to storage.
 */
class NPCSkillProvider(
    private val skills: Map<String, Int>,
    private val availableSkills: List<String>,
) : SkillProvider {
    override fun getSkillLevel(skillName: String): Int = skills[skillName.lowercase()] ?: 0

    override fun getAllSkills(): List<String> = availableSkills

    override fun hasSkill(skillName: String): Boolean =
        skillName.lowercase() in skills || skillName.lowercase() in availableSkills.map { it.lowercase() }
}
