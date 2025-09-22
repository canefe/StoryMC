package com.canefe.story.faction

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.faction.Settlement.SettlementEvent
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.entity.EntityType
import java.util.concurrent.CompletableFuture

class SettlementNPCService(
    private val plugin: Story,
) {
    /**
     * Creates an NPC for a settlement leader
     */
    fun createNPCForLeader(
        settlement: Settlement,
        leader: Leader,
    ): NPC? {
        if (leader.name.isBlank()) return null

        try {
            val npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, leader.name)

            // Setup the NPC with leader data
            updateNPCWithLeaderData(settlement, npc, leader)

            // Store the NPC ID in the settlement
            settlement.leaderNpcId = npc.id

            return npc
        } catch (e: Exception) {
            plugin.logger.severe("Failed to create NPC for leader ${leader.name}: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    /**
     * Generate a random event for a settlement using AI
     */
    fun generateRandomEvent(settlement: Settlement): CompletableFuture<SettlementEvent?> {
        val future = CompletableFuture<SettlementEvent?>()

        // Create the messages for the AI prompt
        val messages = mutableListOf<ConversationMessage>()

// In the generateRandomEvent method, replace the current prompt with this
        val eventTypes =
            listOf(
                "DISASTER: Fire outbreaks, floods, disease epidemics, sinkholes, droughts, crop blights",
                "CRIME: Robberies, bandit attacks, corruption scandals, smuggling operations, prison breaks",
                "WEATHER: Unusual storms, early frosts, heat waves, fog that affects travel and commerce",
                "TRADE: Merchant caravans, trade disputes, supply shortages, market fluctuations, new goods",
                "DISCOVERY: Mineral deposits, ancient ruins, new trade routes, technological innovations",
                "DIPLOMATIC: Visiting emissaries, trade agreements, defense pacts, territorial disputes",
                "POLITICAL: Leadership challenges, faction rivalries, changes in laws, noble marriages",
                "FESTIVAL: Seasonal celebrations, tournaments, religious ceremonies, cultural performances",
                "WAR: Military recruitment, returning soldiers, nearby battles, refugees arriving",
                "PROSPERITY: Market booms, crafting innovations, investment opportunities",
                "HEALTH: New healing techniques, visiting physicians, herbal discoveries",
                "MIGRATION: New settlers arriving, families leaving, cultural shifts",
            )

// Select a random event type suggestion
        val suggestedEventType = eventTypes.random()

// Create the prompt with just one suggested event type
        messages.add(
            ConversationMessage(
                "system",
                "You are a medieval fantasy settlement simulator. Generate a random event " +
                    "that could happen in the settlement ${settlement.name}. " +
                    "Your response should be a JSON object with the following structure:\n" +
                    "{\n" +
                    "  \"eventType\": \"WEATHER | TRADE | CRIME | FESTIVAL | DISASTER | POLITICAL | DISCOVERY " +
                    "| DIPLOMATIC | WAR | PROSPERITY | HEALTH | MIGRATION\",\n" +
                    "  \"title\": \"Short event title\",\n" +
                    "  \"description\": \"Detailed description of what happened\",\n" +
                    "  \"effects\": {\n" +
                    "    \"happiness\": -0.1 to 0.1,\n" +
                    "    \"loyalty\": -0.1 to 0.1,\n" +
                    "    \"prosperity\": -0.1 to 0.1,\n" +
                    "    \"population\": -20 to 20\n" +
                    "  },\n" +
                    "  \"resourceEffects\": {\n" +
                    "    \"resourceType\": \"FOOD | TIMBER | STONE | METAL | TEXTILES\",\n" +
                    "    \"change\": -0.1 to 0.1\n" +
                    "  }\n" +
                    "}\n" +
                    "For your event, consider this example type: $suggestedEventType\n\n" +
                    "Make sure your effects are proportionate to the event description and choose a unique, creative " +
                    "event that hasn't happened before." +
                    "IMPORTANT: Use ONLY the settlement and leader names provided in the context." +
                    " Do not invent new locations, characters, or factions." +
                    " Reference ONLY places and people mentioned in the settlement context, location context," +
                    " or leader context provided to you.\n",
            ),
        )

        // Add settlement context
        messages.add(
            ConversationMessage(
                "system",
                "Settlement Context:\n" +
                    "Name: ${settlement.name}\n" +
                    "Population: ${settlement.population}\n" +
                    "Happiness: ${(settlement.happiness * 100).toInt()}%\n" +
                    "Prosperity: ${(settlement.prosperity * 100).toInt()}%\n" +
                    "Loyalty: ${(settlement.loyalty * 100).toInt()}%\n" +
                    "Is Capital: ${settlement.isCapital}\n" +
                    "Tax Rate: ${(settlement.taxRate * 100).toInt()}%\n" +
                    "Recent Events: ${settlement.recentActions.take(3).joinToString("; ") { it.description }}",
            ),
        )

        // Add location context if available
        if (settlement.location.isNotEmpty()) {
            val locationContext = plugin.locationManager.getAllContextForLocation(settlement.location)
            messages.add(ConversationMessage("system", "Location context: $locationContext"))
        }

        // Add leader context if available
        settlement.leader?.let { leaderData ->
            val leaderContext = buildLeaderContext(settlement, leaderData)
            messages.add(ConversationMessage("system", leaderContext))
            // get npcContext
            val npcContext =
                plugin.npcContextGenerator.getOrCreateContextForNPC(leaderData.name)
                    ?: return@let
            // Add memories if available

            npcContext.getMemoriesForPrompt(plugin.timeService).let { memories ->
                messages.add(
                    ConversationMessage(
                        "system",
                        "===MEMORY===\n" + memories,
                    ),
                )
            }
        }

        // Add resource context
        val resourceContext = StringBuilder("Resources:\n")
        settlement.resources.forEach { (key, value) ->
            val level =
                when {
                    value < 0.3 -> "Low"
                    value < 0.7 -> "Adequate"
                    else -> "Abundant"
                }
            resourceContext.append("- ${key.displayName}: $level\n")
        }
        messages.add(ConversationMessage("system", resourceContext.toString()))

        // Get AI response
        plugin
            .getAIResponse(messages, lowCost = true)
            .thenAccept { response ->
                if (response.isNullOrEmpty()) {
                    future.complete(null)
                    return@thenAccept
                }

                try {
                    // Extract the JSON object from the response
                    val jsonPattern = "\\{[\\s\\S]*\\}".toRegex()
                    val jsonMatch = jsonPattern.find(response)?.value

                    if (jsonMatch != null) {
                        val event = plugin.gson.fromJson(jsonMatch, SettlementEvent::class.java)
                        future.complete(event)
                    } else {
                        future.complete(null)
                    }
                } catch (e: Exception) {
                    plugin.logger.severe("Failed to parse AI response: ${e.message}")
                    future.complete(null)
                }
            }.exceptionally { throwable ->
                plugin.logger.severe("Error getting AI response: ${throwable.message}")
                future.complete(null)
                null
            }

        return future
    }

    /**
     * Update an existing NPC with leader data
     */
    fun updateNPCWithLeaderData(
        settlement: Settlement,
        npc: NPC,
        leader: Leader,
    ) {
        // Implementation to update NPC skin, traits, etc.
        // ...
    }

    /**
     * Build context string for leader to send to AI
     */
    private fun buildLeaderContext(
        settlement: Settlement,
        leader: Leader,
    ): String =
        "Leader context:\n" +
            "Name: ${leader.title} ${leader.name}\n" +
            "Stats: Diplomacy ${leader.diplomacy}, Martial ${leader.martial}, " +
            "Stewardship ${leader.stewardship}, Intrigue ${leader.intrigue}, " +
            "Charisma ${leader.charisma}\n" +
            "Traits: ${leader.traits.joinToString(", ") { it.name }}"
}
