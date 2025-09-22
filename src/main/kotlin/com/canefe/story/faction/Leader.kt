package com.canefe.story.faction

import java.util.*
import kotlin.random.Random

/**
 * Represents a leader of a faction or settlement
 */
data class Leader(
    val id: String,
    var name: String,
    var title: String,
    var traits: MutableList<LeaderTrait> = mutableListOf(),
) {
    // Leadership attributes (0-10 scale)
    var diplomacy: Int = Random.nextInt(1, 11)
    var martial: Int = Random.nextInt(1, 11)
    var stewardship: Int = Random.nextInt(1, 11)
    var intrigue: Int = Random.nextInt(1, 11)
    var charisma: Int = Random.nextInt(1, 11)

    // Relationship values with other leaders/factions
    val relationships = mutableMapOf<String, Int>() // id to value (-100 to 100)

    // Initialize with random traits
    init {
        val traitCount = Random.nextInt(1, 4)
        val availableTraits = LeaderTrait.values().toMutableList()
        repeat(traitCount) {
            if (availableTraits.isNotEmpty()) {
                val randomTrait = availableTraits.random()
                traits.add(randomTrait)
                availableTraits.remove(randomTrait)

                // Apply trait effects to attributes
                applyTraitEffects(randomTrait)
            }
        }
    }

    // Apply trait effects to leader attributes
    private fun applyTraitEffects(trait: LeaderTrait) {
        when (trait) {
            LeaderTrait.JUST -> {
                diplomacy += 2
                intrigue -= 1
            }
            LeaderTrait.CRUEL -> {
                intrigue += 2
                diplomacy -= 2
                martial += 1
            }
            LeaderTrait.WISE -> {
                stewardship += 2
                diplomacy += 1
            }
            LeaderTrait.ZEALOUS -> {
                martial += 2
                diplomacy -= 1
            }
            LeaderTrait.PATIENT -> {
                stewardship += 1
                intrigue += 1
                martial -= 1
            }
            LeaderTrait.WRATHFUL -> {
                martial += 2
                diplomacy -= 2
            }
            LeaderTrait.GREEDY -> {
                stewardship += 2
                diplomacy -= 1
            }
            LeaderTrait.GENEROUS -> {
                diplomacy += 3
                stewardship -= 1
            }
            LeaderTrait.AMBITIOUS -> {
                martial += 1
                intrigue += 1
                stewardship += 1
            }
            LeaderTrait.CONTENT -> {
                diplomacy += 1
                martial -= 1
            }
            LeaderTrait.BRAVE -> {
                martial += 3
                intrigue -= 1
            }
            LeaderTrait.CRAVEN -> {
                intrigue += 2
                martial -= 2
            }
            LeaderTrait.CHARISMATIC -> {
                diplomacy += 3
                charisma += 3
            }
        }

        // Ensure all attributes stay within bounds
        diplomacy = diplomacy.coerceIn(1, 10)
        martial = martial.coerceIn(1, 10)
        stewardship = stewardship.coerceIn(1, 10)
        intrigue = intrigue.coerceIn(1, 10)
        charisma = charisma.coerceIn(1, 10)
    }

    // Update relationship with another leader
    fun updateRelationship(
        targetId: String,
        change: Int,
    ) {
        val current = relationships.getOrDefault(targetId, 0)
        relationships[targetId] = (current + change).coerceIn(-100, 100)
    }

    // Get relationship with another leader
    fun getRelationship(targetId: String): Int = relationships.getOrDefault(targetId, 0)

    // Get relationship description
    fun getRelationshipDescription(targetId: String): String =
        when (getRelationship(targetId)) {
            in -100..-75 -> "Hated Enemy"
            in -74..-50 -> "Rival"
            in -49..-25 -> "Disliked"
            in -24..-1 -> "Unfriendly"
            0 -> "Neutral"
            in 1..25 -> "Cordial"
            in 26..50 -> "Friendly"
            in 51..75 -> "Trusted Ally"
            else -> "Blood Brother"
        }

    // Convert to data that can be saved in YAML
    fun toYamlSection(): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        data["id"] = id
        data["name"] = name
        data["title"] = title
        data["traits"] = traits.map { it.name }
        data["diplomacy"] = diplomacy
        data["martial"] = martial
        data["stewardship"] = stewardship
        data["intrigue"] = intrigue
        data["charisma"] = charisma

        // Save relationships
        val relationshipData = mutableMapOf<String, Int>()
        relationships.forEach { (id, value) -> relationshipData[id] = value }
        data["relationships"] = relationshipData

        return data
    }

    companion object {
        // Generate a random leader
        fun generateRandom(prefix: String): Leader {
            val id = UUID.randomUUID().toString()

            val firstName =
                listOf(
                    "Aldric",
                    "Berwyn",
                    "Caspian",
                    "Darius",
                    "Eadric",
                    "Fendrel",
                    "Gareth",
                    "Hadrian",
                    "Iver",
                    "Jareth",
                    "Keiran",
                    "Lucan",
                    "Mordred",
                    "Nyle",
                    "Orion",
                    "Percival",
                    "Quentin",
                    "Roderick",
                    "Silas",
                    "Tristan",
                    "Aelina",
                    "Brenna",
                    "Cordelia",
                    "Drusilla",
                    "Elara",
                    "Freya",
                    "Gwendolyn",
                    "Helena",
                    "Isolde",
                    "Juliana",
                ).random()

            val lastName =
                listOf(
                    "Blackwood",
                    "Crestfall",
                    "Dawnheart",
                    "Embercrest",
                    "Frostbane",
                    "Grimshaw",
                    "Highwind",
                    "Ironwood",
                    "Nightshade",
                    "Ravenscroft",
                    "Silverstone",
                    "Thornfield",
                    "Valemont",
                    "Wolfsbane",
                    "Zephyrheart",
                ).random()

            val titles =
                mapOf(
                    "settlement" to
                        listOf("Lord", "Lady", "Baron", "Baroness", "Count", "Countess", "Mayor", "Elder", "Chief"),
                    "faction" to
                        listOf(
                            "King",
                            "Queen",
                            "Emperor",
                            "Empress",
                            "High Lord",
                            "Grand Duke",
                            "Archduke",
                            "Chancellor",
                        ),
                )

            val title = titles[prefix]?.random() ?: "Leader"

            return Leader(id, "$firstName $lastName", title)
        }

        // Create leader from YAML data
        fun fromYamlSection(data: Map<String, Any?>): Leader? {
            try {
                val id = data["id"] as? String ?: UUID.randomUUID().toString()
                val name = data["name"] as? String ?: return null
                val title = data["title"] as? String ?: "Leader"

                val leader = Leader(id, name, title)

                // Load stats
                leader.diplomacy = (data["diplomacy"] as? Number)?.toInt() ?: Random.nextInt(1, 11)
                leader.martial = (data["martial"] as? Number)?.toInt() ?: Random.nextInt(1, 11)
                leader.stewardship = (data["stewardship"] as? Number)?.toInt() ?: Random.nextInt(1, 11)
                leader.intrigue = (data["intrigue"] as? Number)?.toInt() ?: Random.nextInt(1, 11)
                leader.charisma = (data["charisma"] as? Number)?.toInt() ?: Random.nextInt(1, 11)

                // Load traits
                val traitsData = data["traits"] as? List<String> ?: emptyList()
                leader.traits.clear()
                traitsData.forEach { traitStr ->
                    try {
                        val trait = LeaderTrait.valueOf(traitStr)
                        leader.traits.add(trait)
                    } catch (e: IllegalArgumentException) {
                        // Ignore invalid traits
                    }
                }

                // Load relationships
                val relationshipsData = data["relationships"] as? Map<String, Int>
                relationshipsData?.forEach { (id, value) ->
                    leader.relationships[id] = value
                }

                return leader
            } catch (e: Exception) {
                return null
            }
        }
    }
}

enum class LeaderTrait {
    JUST, // Fair and lawful
    CRUEL, // Harsh and merciless
    WISE, // Knowledgeable and thoughtful
    ZEALOUS, // Strongly religious
    PATIENT, // Calm and methodical
    WRATHFUL, // Quick to anger
    GREEDY, // Loves wealth
    GENEROUS, // Giving and kind
    AMBITIOUS, // Seeks power
    CONTENT, // Satisfied with status
    BRAVE, // Courageous
    CRAVEN, // Cowardly
    CHARISMATIC, // Naturally likeable
}

enum class LeaderActionType(
    val title: String,
    val isSignificant: Boolean = false,
) {
    TAX_INCREASE("Taxes Increased", true),
    TAX_DECREASE("Taxes Decreased", true),
    POPULATION_GROWTH("Population Growth"),
    POPULATION_DECLINE("Population Decline", true),
    HAPPINESS_INCREASE("Happiness Improved"),
    HAPPINESS_DECREASE("Happiness Declined"),
    LOYALTY_INCREASE("Loyalty Strengthened"),
    LOYALTY_DECREASE("Loyalty Weakened", true),
    PROSPERITY_INCREASE("Prosperity Improved", true),
    PROSPERITY_DECREASE("Economic Decline", true),
    RESOURCE_INCREASE("Resource Increase"),
    RESOURCE_DECREASE("Resource Shortage"),
    RESOURCE_CRISIS("Resource Crisis", true),
    REBELLION_RISK("Rebellion Risk", true),
    REBELLION("Rebellion!", true),
    DECREE("Royal Decree", true),
    WAR("War", true),
    PEACE("Peace Treaty", true),
    DISASTER("Disaster", true),
    FESTIVAL("Festival", true),
    DIPLOMATIC("Diplomatic Event", true),
    CRIME("Crime Wave"),
    MISC("Event"),
    DISCOVERY("Discovery"),
}

data class SettlementAction(
    val type: LeaderActionType,
    val description: String,
    val timestamp: Date,
) {
    fun toYamlSection(): Map<String, Any> =
        mapOf(
            "type" to type.name,
            "description" to description,
            "timestamp" to timestamp.time,
        )
}
