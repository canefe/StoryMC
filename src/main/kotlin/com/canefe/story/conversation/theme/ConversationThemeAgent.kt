package com.canefe.story.conversation.theme

import com.canefe.story.Story
import com.canefe.story.conversation.Conversation
import com.canefe.story.conversation.ConversationMessage
import java.util.Collections

class ConversationThemeAgent(
    private val plugin: Story,
    private val themeManager: ConversationThemeManager,
    private val registry: ConversationThemeRegistry,
) {
    private val analyzingConversations = Collections.synchronizedSet(mutableSetOf<Int>())

    fun analyzeAndUpdateThemes(conversation: Conversation) {
        val debug = plugin.config.debugMessages

        // Skip if chat/AI is disabled
        if (!conversation.chatEnabled) {
            if (debug) plugin.logger.info("[ThemeAgent] Skipping conversation ${conversation.id}: chat disabled")
            return
        }

        // Prevent concurrent analysis for the same conversation
        if (!analyzingConversations.add(conversation.id)) {
            if (debug) {
                plugin.logger.info(
                    "[ThemeAgent] Skipping conversation ${conversation.id}: analysis already in progress",
                )
            }
            return
        }

        val recentMessages =
            conversation.history
                .filter { it.role != "system" && it.content != "..." }
                .takeLast(RECENT_MESSAGES_TO_ANALYZE)

        if (recentMessages.isEmpty()) {
            if (debug) {
                plugin.logger.info(
                    "[ThemeAgent] Skipping conversation ${conversation.id}: no messages to analyze",
                )
            }
            analyzingConversations.remove(conversation.id)
            return
        }

        val availableThemes = buildThemeDescriptions()
        val activeThemeNames = themeManager.getActiveThemes(conversation).map { it.name }

        if (debug) {
            plugin.logger.info(
                "[ThemeAgent] Analyzing conversation ${conversation.id} (${recentMessages.size} messages, active themes: ${activeThemeNames.ifEmpty {
                    listOf(
                        "none",
                    )
                }})",
            )
        }

        val prompt =
            plugin.promptService.getThemeAnalysisPrompt(
                availableThemes = availableThemes,
                activeThemes = activeThemeNames.joinToString(", ").ifEmpty { "none" },
            )

        val transcript =
            recentMessages.joinToString("\n") {
                it.content.replace("\n", " ")
            }

        val prompts =
            listOf(
                ConversationMessage("system", prompt),
                ConversationMessage("user", transcript),
            )

        try {
            plugin
                .getAIResponse(prompts, lowCost = false)
                .thenAccept { response ->
                    if (debug) {
                        plugin.logger.info(
                            "[ThemeAgent] AI response for conversation ${conversation.id}: '$response'",
                        )
                    }
                    if (!response.isNullOrBlank() && conversation.active) {
                        applyThemeChanges(conversation, response.trim(), activeThemeNames)
                    } else if (debug) {
                        plugin.logger.info(
                            "[ThemeAgent] No action for conversation ${conversation.id}: response blank=${response.isNullOrBlank()}, active=${conversation.active}",
                        )
                    }
                    analyzingConversations.remove(conversation.id)
                }.exceptionally { ex ->
                    plugin.logger.warning(
                        "[ThemeAgent] Analysis failed for conversation ${conversation.id}: ${ex.message}",
                    )
                    analyzingConversations.remove(conversation.id)
                    null
                }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            if (debug) plugin.logger.info("[ThemeAgent] Executor rejected for conversation ${conversation.id}")
            analyzingConversations.remove(conversation.id)
        }
    }

    private fun applyThemeChanges(
        conversation: Conversation,
        response: String,
        previousThemes: List<String>,
    ) {
        val debug = plugin.config.debugMessages
        val desiredThemes =
            response
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && it != "none" && registry.isRegistered(it) }
                .toSet()

        if (debug) {
            plugin.logger.info(
                "[ThemeAgent] Desired themes for conversation ${conversation.id}: $desiredThemes (previous: $previousThemes)",
            )
        }

        // Deactivate themes that are no longer needed
        for (themeName in previousThemes) {
            if (themeName !in desiredThemes) {
                themeManager.deactivateTheme(conversation, themeName)
                if (debug) {
                    plugin.logger.info(
                        "[ThemeAgent] Deactivated '$themeName' for conversation ${conversation.id}",
                    )
                }
            }
        }

        // Activate new themes
        for (themeName in desiredThemes) {
            if (themeName !in previousThemes) {
                val activated = themeManager.activateTheme(conversation, themeName)
                if (debug) {
                    if (activated) {
                        plugin.logger.info("[ThemeAgent] Activated '$themeName' for conversation ${conversation.id}")
                    } else {
                        plugin.logger.info(
                            "[ThemeAgent] Failed to activate '$themeName' for conversation ${conversation.id} (incompatible?)",
                        )
                    }
                }
            }
        }
    }

    private fun buildThemeDescriptions(): String {
        val themes = registry.getRegisteredThemes()
        return themes.joinToString("\n") { name ->
            val theme = registry.create(name)
            "- ${theme.name}: ${theme.description}"
        }
    }

    companion object {
        private const val RECENT_MESSAGES_TO_ANALYZE = 6
    }
}
