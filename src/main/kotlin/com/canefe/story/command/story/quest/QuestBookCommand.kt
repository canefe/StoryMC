package com.canefe.story.command.story.quest

import com.canefe.story.context.ContextExtractor
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser

class QuestBookCommand(
    private val commandUtils: QuestCommandUtils,
) {
    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("book")
            .withPermission("story.command.quest.book")
            .withArguments(GreedyStringArgument("context"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val context = args["context"] as String
                    val story = commandUtils.story

                    // Extract context using the centralized ContextExtractor
                    val contextExtractor = ContextExtractor(story)
                    val extractedContext = contextExtractor.extractContext(context)

                    player.sendSuccess("Generating quest book with context: '$context'")

                    // Send info messages to player
                    extractedContext.generateInfoMessages().forEach { message ->
                        player.sendSuccess(message)
                    }

                    // Create messages for AI prompt using extracted context
                    val contextInformation = extractedContext.generatePromptContext()
                    val questBookPrompt =
                        story.promptService.getQuestBookPrompt(contextInformation)

                    val messages =
                        mutableListOf(
                            ConversationMessage("system", questBookPrompt),
                            ConversationMessage("user", context),
                        )

                    // Get AI response
                    story
                        .getAIResponse(messages)
                        .thenAccept { aiResponse ->
                            if (aiResponse == null) {
                                player.sendError("Failed to generate book content")
                                return@thenAccept
                            }

                            try {
                                // Parse the JSON response
                                val (title, content) =
                                    extractBookContentFromJSON(aiResponse)

                                if (content.isEmpty()) {
                                    player.sendError(
                                        "Failed to parse book content from AI response",
                                    )
                                    return@thenAccept
                                }

                                // Split content into pages of appropriate size
                                val pages = commandUtils.splitIntoPages(content)

                                // Create the book item
                                val book = ItemStack(Material.WRITTEN_BOOK)
                                val meta = book.itemMeta as BookMeta

                                // Set book properties
                                meta.title(Component.text(title))
                                meta.author(Component.text("Unknown"))

                                // Add pages to the book
                                meta.addPages(
                                    *pages
                                        .map {
                                            story.miniMessage.deserialize(it)
                                        }.toTypedArray(),
                                )

                                book.itemMeta = meta

                                // Give the book to the player
                                if (player.inventory.firstEmpty() != -1) {
                                    player.inventory.addItem(book)
                                    player.sendSuccess(
                                        "Quest book generated (${pages.size} pages) and added to your inventory",
                                    )
                                } else {
                                    player.world.dropItem(player.location, book)
                                    player.sendSuccess(
                                        "Quest book generated and dropped at your feet (inventory full)",
                                    )
                                }
                            } catch (e: Exception) {
                                player.sendError("Failed to create book: ${e.message}")
                                story.logger.warning(
                                    "Error creating quest book: ${e.message}",
                                )
                                e.printStackTrace()
                            }
                        }.exceptionally { e ->
                            player.sendError("Error generating book: ${e.message}")
                            story.logger.warning(
                                "Error in AI response for quest book: ${e.message}",
                            )
                            e.printStackTrace()
                            null
                        }
                },
            )
    }

    /**
     * Extract book title and content from JSON response
     * @return Pair of (title, content)
     */
    private fun extractBookContentFromJSON(response: String): Pair<String, String> {
        val trimmedResponse = response.trim()
        var title = "Quest Book" // Default title
        var content = ""

        try {
            // Try to find JSON object in the response
            val jsonStartIndex = trimmedResponse.indexOf("{")
            val jsonEndIndex = trimmedResponse.lastIndexOf("}") + 1

            if (jsonStartIndex >= 0 && jsonEndIndex > jsonStartIndex) {
                val jsonStr = trimmedResponse.substring(jsonStartIndex, jsonEndIndex)
                val parser = JSONParser()
                val jsonObject = parser.parse(jsonStr) as JSONObject

                // Extract title and content
                title = (jsonObject["title"] as? String) ?: title
                content = (jsonObject["content"] as? String) ?: ""

                return Pair(title, content)
            }

            // Fallback: if no content key but looks like JSON, try to extract title and content
            if (trimmedResponse.startsWith("{") && trimmedResponse.endsWith("}")) {
                val parser = JSONParser()
                val jsonObject = parser.parse(trimmedResponse) as JSONObject

                // Try to find title and content keys
                if (jsonObject.containsKey("title")) {
                    title = (jsonObject["title"] as? String) ?: title
                }

                if (jsonObject.containsKey("content")) {
                    content = (jsonObject["content"] as? String) ?: ""
                }
            }
        } catch (e: Exception) {
            // If JSON parsing fails, check if response has content wrapped in quotes and braces
            val titleRegex = "\"title\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"".toRegex()
            val contentRegex = "\"content\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"".toRegex()

            val titleMatch = titleRegex.find(trimmedResponse)
            if (titleMatch != null) {
                title = titleMatch.groupValues[1].replace("\\\"", "\"")
            }

            val contentMatch = contentRegex.find(trimmedResponse)
            if (contentMatch != null) {
                content = contentMatch.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n")
            }
        }

        // Return the extracted title and content
        return Pair(title, content)
    }
}
