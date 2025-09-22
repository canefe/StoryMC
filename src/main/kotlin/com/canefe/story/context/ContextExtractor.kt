package com.canefe.story.context

import com.canefe.story.Story
import com.canefe.story.lore.LoreBookManager.LoreContext
import org.bukkit.entity.Player

/** Data class representing the result of context extraction */
data class ContextResult(
    val loreContexts: List<LoreContext>,
    val locationContexts: List<LocationContextInfo>,
    val npcContexts: List<NPCContextInfo>,
    val currentLocationContext: String? = null,
) {
    /** Generate a formatted prompt context string for AI */
    fun generatePromptContext(
        includeLore: Boolean = true,
        includeLocations: Boolean = true,
        includeNPCs: Boolean = true,
        includeCurrentLocation: Boolean = false,
    ): String {
        val contextBuilder = StringBuilder()

        if (includeLore && loreContexts.isNotEmpty()) {
            contextBuilder.append("\n\nInclude these world lore elements in your writing:\n")
            contextBuilder.append(
                loreContexts.joinToString("\n\n") { "- ${it.loreName}: ${it.context}" },
            )
        }

        if (includeLocations && locationContexts.isNotEmpty()) {
            contextBuilder.append("\n\nInclude these locations and their context:\n")
            contextBuilder.append(
                locationContexts.joinToString("\n\n") { "- ${it.name}: ${it.context}" },
            )
        }

        if (includeNPCs && npcContexts.isNotEmpty()) {
            contextBuilder.append("\n\nRelevant NPCs and their context:\n")
            contextBuilder.append(
                npcContexts.joinToString("\n\n") { info ->
                    buildString {
                        append("- ${info.name}: ${info.context}")
                        if (info.recentMemories.isNotEmpty()) {
                            append("\n  Recent memories: ${info.recentMemories}")
                        }
                    }
                },
            )
        }

        if (includeCurrentLocation && currentLocationContext != null) {
            contextBuilder.append("\n\nCURRENT LOCATION:\n")
            contextBuilder.append(currentLocationContext)
        }

        return contextBuilder.toString()
    }

    /** Generate info messages for the user about what was found */
    fun generateInfoMessages(): List<String> {
        val messages = mutableListOf<String>()

        val loreInfo =
            if (loreContexts.isNotEmpty()) {
                "Relevant lore found: " + loreContexts.joinToString(", ") { it.loreName }
            } else {
                "No relevant lore found for the given context."
            }
        messages.add(loreInfo)

        val locationInfo =
            if (locationContexts.isNotEmpty()) {
                "Relevant locations found: ${locationContexts.joinToString(", ") { it.name }}"
            } else {
                "No relevant locations found for the given context."
            }
        messages.add(locationInfo)

        val npcInfo =
            if (npcContexts.isNotEmpty()) {
                "Relevant NPCs found: ${npcContexts.joinToString(", ") { it.name }}"
            } else {
                "No relevant NPCs found for the given context."
            }
        messages.add(npcInfo)

        return messages
    }
}

/** Data class for location context information */
data class LocationContextInfo(
    val name: String,
    val context: String,
)

/** Data class for NPC context information */
data class NPCContextInfo(
    val name: String,
    val context: String,
    val recentMemories: String = "",
)

/** Configuration for context extraction */
data class ContextExtractionConfig(
    val extractLore: Boolean = true,
    val extractLocations: Boolean = true,
    val extractNPCs: Boolean = true,
    val extractCurrentLocation: Boolean = false,
    val minKeywordLength: Int = 3,
    val maxRecentMemories: Int = 3,
    val maxLoreContexts: Int = 10,
    val maxLocationContexts: Int = 10,
    val maxNPCContexts: Int = 10,
)

/**
 * Centralized context extraction service for finding relevant lore, NPCs, and locations from text
 * input to include in AI prompts.
 */
class ContextExtractor(
    private val plugin: Story,
) {
    /** Extract all relevant context from the given text */
    fun extractContext(
        text: String,
        config: ContextExtractionConfig = ContextExtractionConfig(),
        currentPlayer: Player? = null,
    ): ContextResult {
        val loreContexts =
            if (config.extractLore) extractLoreContexts(text, config) else emptyList()
        val locationContexts =
            if (config.extractLocations) extractLocationContexts(text, config) else emptyList()
        val npcContexts = if (config.extractNPCs) extractNPCContexts(text, config) else emptyList()
        val currentLocationContext =
            if (config.extractCurrentLocation && currentPlayer != null) {
                extractCurrentLocationContext(currentPlayer)
            } else {
                null
            }

        return ContextResult(
            loreContexts = loreContexts,
            locationContexts = locationContexts,
            npcContexts = npcContexts,
            currentLocationContext = currentLocationContext,
        )
    }

    /** Generate a formatted prompt context string - convenience method */
    fun generatePrompt(
        text: String,
        config: ContextExtractionConfig = ContextExtractionConfig(),
        currentPlayer: Player? = null,
    ): String {
        val context = extractContext(text, config, currentPlayer)
        return context.generatePromptContext()
    }

    /** Extract relevant lore contexts from text */
    private fun extractLoreContexts(
        text: String,
        config: ContextExtractionConfig,
    ): List<LoreContext> = plugin.lorebookManager.findLoresByKeywords(text).take(config.maxLoreContexts)

    /** Extract relevant location contexts from text */
    private fun extractLocationContexts(
        text: String,
        config: ContextExtractionConfig,
    ): List<LocationContextInfo> {
        val locationKeywords = extractKeywords(text, config.minKeywordLength)
        val locationContexts = mutableListOf<LocationContextInfo>()

        locationKeywords.forEach { keyword ->
            plugin.locationManager.getAllLocations().forEach { location ->
                if (location.name.equals(keyword, ignoreCase = true) &&
                    location.context.isNotEmpty()
                ) {
                    val contextString = location.context.joinToString(". ")
                    locationContexts.add(LocationContextInfo(location.name, contextString))
                }
            }
        }

        return locationContexts.distinctBy { it.name }.take(config.maxLocationContexts)
    }

    /** Extract relevant NPC contexts from text */
    private fun extractNPCContexts(
        text: String,
        config: ContextExtractionConfig,
    ): List<NPCContextInfo> {
        val npcKeywords = extractKeywords(text, config.minKeywordLength)
        val npcContexts = mutableListOf<NPCContextInfo>()

        npcKeywords.forEach { keyword ->
            plugin.npcDataManager.getAllNPCNames().forEach { npcName ->
                if (npcName.equals(keyword, ignoreCase = true)) {
                    val npcContext = plugin.npcContextGenerator.getOrCreateContextForNPC(npcName)
                    if (npcContext != null) {
                        val lastFewMemories =
                            npcContext.getMemoriesForPrompt(
                                plugin.timeService,
                                config.maxRecentMemories,
                            )
                        val recentMemoriesString =
                            if (lastFewMemories != null && lastFewMemories.isNotEmpty()) {
                                lastFewMemories
                            } else {
                                ""
                            }

                        npcContexts.add(
                            NPCContextInfo(
                                name = npcName,
                                context = npcContext.context,
                                recentMemories = recentMemoriesString,
                            ),
                        )
                    }
                }
            }
        }

        return npcContexts.distinctBy { it.name }.take(config.maxNPCContexts)
    }

    /** Extract current location context for a player */
    private fun extractCurrentLocationContext(player: Player): String? {
        val actualLocation = plugin.locationManager.getLocationByPosition(player.location, 150.0)
        return if (actualLocation != null) {
            "${player.name} is currently at ${actualLocation.name}.\n" +
                "Location context: ${actualLocation.getContextForPrompt(plugin.locationManager)}\n"
        } else {
            null
        }
    }

    /** Extract keywords from text for matching against names */
    private fun extractKeywords(
        text: String,
        minLength: Int = 3,
    ): List<String> =
        text
            .split(" ")
            .filter { it.length > minLength }
            .map { it.lowercase().trim(',', '.', '?', '!', '\'', '"') }
            .distinct()
}
