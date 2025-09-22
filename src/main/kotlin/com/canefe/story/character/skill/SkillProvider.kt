package com.canefe.story.character.skill

/**
 * Interface for skill providers that can be plugged into the Story system
 */
interface SkillProvider {
    /**
     * Get the skill level for a specific skill
     * @param skillName The name of the skill to retrieve
     * @return The skill level or 0 if the skill doesn't exist
     */
    fun getSkillLevel(skillName: String): Int

    /**
     * Get a list of all available skills
     * @return List of skill names
     */
    fun getAllSkills(): List<String>

    /**
     * Check if a skill exists in this provider
     * @param skillName The name of the skill to check
     * @return true if the skill exists, false otherwise
     */
    fun hasSkill(skillName: String): Boolean
}
