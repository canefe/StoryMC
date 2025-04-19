package com.canefe.story.command.story.quest

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
    private val commandUtils: QuestCommandUtils
) {
    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("book")
            .withArguments(GreedyStringArgument("context"))
            .executesPlayer(PlayerCommandExecutor { player, args ->
                val context = args["context"] as String
                val story = commandUtils.story

                // Find relevant lore related to the context
                val loreContexts = story.lorebookManager.findLoresByKeywords(context)
                val loreInfo = if (loreContexts.isNotEmpty()) {
                    "Relevant lore found: " + loreContexts.joinToString(", ") { it.loreName }
                } else {
                    "No relevant lore found for the given context."
                }

                player.sendSuccess("Generating quest book with context: '$context'")
                player.sendSuccess(loreInfo)

                // Include relevant lore in the prompt
                val loreContext = if (loreContexts.isNotEmpty()) {
                    "\n\nInclude these world lore elements in your writing:\n" +
                            loreContexts.joinToString("\n\n") { "- ${it.loreName}: ${it.context}" }
                } else ""

                // Create messages for AI prompt
                val messages = mutableListOf(
                    ConversationMessage(
                        "system",
                        """
                        Generate a short narrative passage that could be found in a Minecraft book.
                        
                        The passage should be:
                        - Written in a style appropriate for a fantasy Minecraft world
                        - Maximum 800 characters total
                        - Written in-character as if it's a book, personal notes, or letter
                        - Include relevant details from the provided context and lore

                        IMPORTANT: Format your response as a valid JSON object with this structure:
                        {"content": "The actual book content goes here..."}
                        
                        Wrap words in <bold></bold> tags to make them bold.
                        Use <italic></italic> tags for italicized text.
                        Use these where appropriate in the text.
                        
                        Don't add any explanations or other text outside the JSON.
                        $loreContext
                        """
                    ),
                    ConversationMessage("user", context)
                )

                // Get AI response
                story.getAIResponse(messages).thenAccept { aiResponse ->
                    if (aiResponse == null) {
                        player.sendError("Failed to generate book content")
                        return@thenAccept
                    }

                    try {
                        // Parse the JSON response
                        val bookContent = extractBookContentFromJSON(aiResponse)

                        if (bookContent.isBlank()) {
                            player.sendError("Failed to parse book content from AI response")
                            return@thenAccept
                        }

                        // Split content into pages of appropriate size
                        val pages = splitIntoPages(bookContent)

                        // Create the book item
                        val book = ItemStack(Material.WRITTEN_BOOK)
                        val meta = book.itemMeta as BookMeta

                        // Set book properties
                        meta.title(Component.text("Quest Book"))
                        meta.author(Component.text("Story Plugin"))

                        // Add pages to the book
                        meta.addPages(*pages.map { story.miniMessage.deserialize(it) }.toTypedArray())

                        book.itemMeta = meta

                        // Give the book to the player
                        if (player.inventory.firstEmpty() != -1) {
                            player.inventory.addItem(book)
                            player.sendSuccess("Quest book generated (${pages.size} pages) and added to your inventory")
                        } else {
                            player.world.dropItem(player.location, book)
                            player.sendSuccess("Quest book generated and dropped at your feet (inventory full)")
                        }
                    } catch (e: Exception) {
                        player.sendError("Failed to create book: ${e.message}")
                        story.logger.warning("Error creating quest book: ${e.message}")
                        e.printStackTrace()
                    }
                }.exceptionally { e ->
                    player.sendError("Error generating book: ${e.message}")
                    story.logger.warning("Error in AI response for quest book: ${e.message}")
                    e.printStackTrace()
                    null
                }
            })
    }

    /**
     * Extract book content from JSON response
     */
    private fun extractBookContentFromJSON(response: String): String {
        val trimmedResponse = response.trim()

        try {
            // Try to find JSON object in the response
            val jsonStartIndex = trimmedResponse.indexOf("{")
            val jsonEndIndex = trimmedResponse.lastIndexOf("}") + 1

            if (jsonStartIndex >= 0 && jsonEndIndex > jsonStartIndex) {
                val jsonStr = trimmedResponse.substring(jsonStartIndex, jsonEndIndex)
                val parser = JSONParser()
                val jsonObject = parser.parse(jsonStr) as JSONObject

                return (jsonObject["content"] as? String) ?: ""
            }

            // Fallback: if no content key but looks like JSON, try to extract any string value
            if (trimmedResponse.startsWith("{") && trimmedResponse.endsWith("}")) {
                val parser = JSONParser()
                val jsonObject = parser.parse(trimmedResponse) as JSONObject

                // Return first string value found
                for (key in jsonObject.keys) {
                    val value = jsonObject[key]
                    if (value is String) {
                        return value
                    }
                }
            }
        } catch (e: Exception) {
            // If JSON parsing fails, check if response has content wrapped in quotes and braces
            val contentRegex = "\"content\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"".toRegex()
            val matchResult = contentRegex.find(trimmedResponse)

            if (matchResult != null) {
                return matchResult.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n")
            }
        }

        // Last resort: return the whole response if all else fails
        return trimmedResponse
    }

    /**
     * Splits content into pages with fixed values:
     * - 165 characters per page maximum
     * - Preferably 3 paragraphs per page for readability
     * - Maximum 6 lines per page
     */
    private fun splitIntoPages(content: String): List<String> {
        val pages = mutableListOf<String>()

        // Split by paragraphs first
        val paragraphs = content.split("\n\n").filter { it.isNotBlank() }

        var currentPage = StringBuilder()
        var paragraphsInCurrentPage = 0
        var index = 0

        while (index < paragraphs.size) {
            val paragraph = paragraphs[index]

            // Check if adding this paragraph would exceed character limit
            if (currentPage.length + paragraph.length + (if (currentPage.isEmpty()) 0 else 2) <= 165) {
                // We can fit this paragraph on the current page
                if (currentPage.isNotEmpty()) {
                    currentPage.append("\n\n")
                }
                currentPage.append(paragraph)
                paragraphsInCurrentPage++
                index++

                // If we have 3 paragraphs or reached the end, create a new page
                if (paragraphsInCurrentPage == 3 || index == paragraphs.size) {
                    pages.add(currentPage.toString().trim())
                    currentPage = StringBuilder()
                    paragraphsInCurrentPage = 0
                }
            } else {
                // Paragraph is too large for the current page, we need to split it
                if (currentPage.isNotEmpty()) {
                    // Finish current page first
                    pages.add(currentPage.toString().trim())
                    currentPage = StringBuilder()
                    paragraphsInCurrentPage = 0
                }

                // Now handle the large paragraph
                var remainingText = paragraph
                while (remainingText.isNotEmpty()) {
                    // Find a good breaking point within character limit
                    val chunkSize = minOf(remainingText.length, 165)
                    val breakPoint = if (chunkSize < remainingText.length) {
                        val possibleBreakPoint = remainingText.substring(0, chunkSize).lastIndexOfAny(charArrayOf(' ', '.', '!', '?', ','))
                        if (possibleBreakPoint > 0) possibleBreakPoint + 1 else chunkSize
                    } else {
                        chunkSize
                    }

                    val chunk = remainingText.substring(0, breakPoint).trim()
                    currentPage.append(chunk)
                    paragraphsInCurrentPage++

                    remainingText = if (breakPoint < remainingText.length) {
                        remainingText.substring(breakPoint).trim()
                    } else {
                        ""
                    }

                    // If we have text remaining but have reached character limit, start new page
                    if (remainingText.isNotEmpty() || paragraphsInCurrentPage == 3) {
                        pages.add(currentPage.toString().trim())
                        currentPage = StringBuilder()
                        paragraphsInCurrentPage = 0
                    }
                }

                index++
            }
        }

        // Add any remaining content as the last page
        if (currentPage.isNotEmpty()) {
            pages.add(currentPage.toString().trim())
        }

        return pages
    }
}