package com.canefe.story.command.story.location

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.location.LocationManager
import com.canefe.story.util.Msg.sendSuccess
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.CommandSender
import java.util.concurrent.CompletableFuture

class LocationCommandUtils {
    val story: Story = Story.instance
    val mm: MiniMessage = story.miniMessage
    val locationManager: LocationManager = story.locationManager
    private val gson: Gson = Gson()

    data class LocationContextResponse(
        val description: String,
        val recent_events: String,
    )

    data class RecentEventsResponse(
        val recent_events: String,
    )

    fun generateLocationContext(
        locationName: String,
        context: String,
        player: CommandSender? = null,
    ): CompletableFuture<String?> {
        // Use ContextExtractor to gather helpful world context for the AI
        val contextExtractor = story.contextExtractor
        val extractedContext = contextExtractor.extractContext(context)

        // Inform the player what was found
        if (player != null) {
            extractedContext.generateInfoMessages().forEach { msg -> player.sendSuccess(msg) }
        }

        // Build prompt for AI
        val contextInformation = extractedContext.generatePromptContext()
        val systemPrompt =
            // Reuse the existing story message prompt as a system-level context provider
            story.promptService.getLocationContextGenerationPrompt(contextInformation)

        val prompts = mutableListOf<ConversationMessage>()
        prompts.add(ConversationMessage("system", systemPrompt))
        prompts.add(
            ConversationMessage(
                "user",
                "Generate contextual description for the location named '$locationName' based on this input: $context",
            ),
        )

        // Ask the AI
        return story.getAIResponse(prompts)
    }

    /**
     * Generates recent events for a location using AI. Expects a JSON response with a single
     * "recent_events" field.
     */
    fun generateRecentEvents(
        locationName: String,
        context: String,
        sender: CommandSender? = null,
    ): CompletableFuture<String?> {
        // Use ContextExtractor to gather helpful world context for the AI
        val contextExtractor = story.contextExtractor
        val extractedContext = contextExtractor.extractContext(context)

        // Inform the player what was found
        if (sender != null) {
            extractedContext.generateInfoMessages().forEach { msg -> sender.sendSuccess(msg) }
        }

        // Build prompt for AI
        val contextInformation = extractedContext.generatePromptContext()
        val systemPrompt =
            // Use a specific prompt for recent events generation
            story.promptService.getRecentEventsGenerationPrompt(contextInformation)

        val prompts = mutableListOf<ConversationMessage>()
        prompts.add(ConversationMessage("system", systemPrompt))
        prompts.add(
            ConversationMessage(
                "user",
                "Generate recent events for the location named '$locationName' based on this input: $context. Respond with a JSON object containing only a 'recent_events' field.",
            ),
        )

        // Ask the AI
        return story.getAIResponse(prompts)
    }

    /**
     * Parses AI response and extracts context entries. Handles both JSON format and plain text
     * format.
     */
    fun parseLocationContextResponse(response: String): List<String> {
        if (response.isBlank()) {
            return emptyList()
        }

        return try {
            // Try to parse as JSON first
            val contextResponse = gson.fromJson(response, LocationContextResponse::class.java)
            listOf(contextResponse.description, contextResponse.recent_events).filter {
                it.isNotBlank()
            }
        } catch (e: JsonSyntaxException) {
            // If JSON parsing fails, fallback to plain text parsing
            parsePlainTextResponse(response)
        }
    }

    /** Parses plain text response by splitting into paragraphs. */
    private fun parsePlainTextResponse(response: String): List<String> =
        response.split(Regex("\n\n+|\r\n\r\n+")).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Parses AI response expecting a JSON with a single "recent_events" field. Returns the
     * recent_events content or null if parsing fails.
     */
    fun parseRecentEventsResponse(response: String): String? {
        if (response.isBlank()) {
            return null
        }

        return try {
            val recentEventsResponse = gson.fromJson(response, RecentEventsResponse::class.java)
            recentEventsResponse.recent_events.takeIf { it.isNotBlank() }
        } catch (e: JsonSyntaxException) {
            null
        }
    }
}
