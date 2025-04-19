package com.canefe.story.npc.relationship

import java.time.Instant
import java.util.UUID

/**
 * Represents a complete relationship between two entities
 */
data class Relationship(
    val id: String = UUID.randomUUID().toString(),
    val targetName: String,
    var type: String = "acquaintance",
    var score: Double = 0.0,
    val memoryIds: MutableList<String> = mutableListOf(), // References to memories
    val traits: MutableSet<String> = mutableSetOf()
) {
    /**
     * Updates relationship score directly
     */
    fun updateScore(change: Double) {
        score = (score + change).coerceIn(-100.0, 100.0)

        // Update relationship type based on score if needed
        updateRelationshipType()
    }

    /**
     * Updates the relationship traits based on an event
     */
    fun addTrait(trait: String) {
        traits.add(trait)
    }

    /**
     * Removes a relationship trait
     */
    fun removeTrait(trait: String) {
        traits.remove(trait)
    }

    /**
     * Updates the relationship type based on the current score and traits
     */
    private fun updateRelationshipType() {
        // Only automatically update type for significant score changes

    }

    /**
     * Provides a descriptive summary of the relationship
     */
    fun describe(): String {
        val intensity = when {
            score > 90 -> "extremely close"
            score > 70 -> "very close"
            score > 50 -> "good"
            score > 30 -> "friendly"
            score > 10 -> "positive"
            score < -90 -> "hateful"
            score < -70 -> "antagonistic"
            score < -50 -> "poor"
            score < -30 -> "unfriendly"
            score < -10 -> "negative"
            else -> "neutral"
        }

        val typeStr = type.lowercase()
        val traitStr = if (traits.isNotEmpty()) traits.joinToString(", ") else ""

        return "$intensity $typeStr relationship" + if (traitStr.isNotEmpty()) " ($traitStr)" else ""
    }
}