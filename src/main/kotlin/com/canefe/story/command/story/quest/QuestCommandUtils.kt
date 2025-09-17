package com.canefe.story.command.story.quest

import com.canefe.story.Story
import com.canefe.story.command.base.CommandComponentUtils
import com.canefe.story.location.LocationManager
import com.canefe.story.quest.QuestStatus
import com.canefe.story.util.EssentialsUtils
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import java.util.regex.Pattern
import kotlin.compareTo
import kotlin.text.append
import kotlin.text.compareTo

class QuestCommandUtils {
    val story: Story = Story.instance
    val mm: MiniMessage = story.miniMessage
    val locationManager: LocationManager = story.locationManager

    fun createButton(
        label: String,
        color: String,
        clickAction: String,
        command: String,
        hoverText: String,
    ): Component = CommandComponentUtils.createButton(mm, label, color, clickAction, command, hoverText)

    fun getStatusColor(status: QuestStatus): String =
        when (status) {
            QuestStatus.NOT_STARTED -> "<gray>"
            QuestStatus.IN_PROGRESS -> "<yellow>"
            QuestStatus.COMPLETED -> "<green>"
            QuestStatus.FAILED -> "<red>"
        }

    fun statusParser(status: String): String {
        val words = status.split("_")
        return words.joinToString(" ") { it.replaceFirstChar { it.uppercase() } }
    }

    fun openJournalBook(
        player: Player,
        target: OfflinePlayer? = null,
    ) {
        val targetUuid = target?.uniqueId ?: player.uniqueId
        val targetName = EssentialsUtils.getNickname(target?.name ?: player.name)
        val isAdmin = target != null && player.hasPermission("story.journal.admin")

        // Create the book item
        val book = ItemStack(Material.WRITTEN_BOOK)
        val meta = book.itemMeta as BookMeta

        // Set book properties
        meta.title(story.miniMessage.deserialize(if (isAdmin) "$targetName's Journal" else "Journal"))
        meta.author(story.miniMessage.deserialize("<gold>Story Plugin</gold>"))

        // Commands [My Quests], [Known Individuals], [Memories]
        val commands =
            CommandComponentUtils.combineComponentsWithSeparator(
                story.miniMessage,
                listOf(
                    createButton("My Quests", "#8e44ad", "run_command", "/story q qb", "The quests I'm working on"),
                    createButton(
                        "My Relationships",
                        "#8e44ad",
                        "run_command",
                        "/story q journal individuals ${target?.name ?: player.name}",
                        "People I know about",
                    ),
                    createButton(
                        "Memories",
                        "#8e44ad",
                        "run_command",
                        "/story q journal memories ${target?.name ?: player.name}",
                        "My strongest memories",
                    ),
                ),
                "\n<gray>------------------</gray>\n",
            )
        val mainPage =
            Component
                .text()
                .append(story.miniMessage.deserialize("<gold>I am $targetName.</gold>\n\n"))
                .append(commands)
                .build()
        meta.addPages(mainPage)

        // Set the metadata back to the book
        book.itemMeta = meta

        // Open the book for the player
        player.openBook(book)
    }

    fun openIndividualsBook(
        player: Player,
        target: OfflinePlayer? = null,
    ) {
        val targetUuid = target?.uniqueId ?: player.uniqueId
        val targetName = EssentialsUtils.getNickname(target?.name ?: player.name)
        val isAdmin = target != null && player.hasPermission("story.journal.admin")

        // Create the book item
        val book = ItemStack(Material.WRITTEN_BOOK)
        val meta = book.itemMeta as BookMeta

        // Set book properties
        meta.title(
            story.miniMessage.deserialize(if (isAdmin) "$targetName's Known Individuals" else "Known Individuals"),
        )
        meta.author(story.miniMessage.deserialize("<gold>Story Plugin</gold>"))

        val relationships =
            story.relationshipManager.getAllRelationships(
                EssentialsUtils.getNickname(target?.name ?: player.name),
            )

        if (relationships.isEmpty()) {
            val noIndividualsPage =
                story.miniMessage.deserialize("<gold>No Known Individuals</gold>\n\n<gray>I do not know anyone.")
            meta.addPages(noIndividualsPage)
        } else {
            var individualIndex = 1
            val sortedRelationships =
                relationships
                    .filter { it.value.score != 0.0 }
                    .toList()
                    .sortedByDescending { it.second.score }

            sortedRelationships.forEach { (_, individual) ->
                val formattedScore = String.format("%.2f", individual.score)
                val pageContent =
                    story.miniMessage.deserialize(
                        "<#8e44ad>${individual.targetName}</#8e44ad><gray> ($formattedScore)\n" +
                            "<#16a085>${individual.type}</#16a085>\n",
                    )
                for (trait in individual.traits) {
                    pageContent.append(Component.text("<gray>-</gray> <gold>$trait</gold>").append(Component.newline()))
                }
                meta.addPages(pageContent)
                individualIndex++
            }
        }

        // Set the metadata back to the book
        book.itemMeta = meta

        // Open the book for the player
        player.openBook(book)
    }

    fun openMemoriesBook(
        player: Player,
        target: OfflinePlayer? = null,
    ) {
        val targetUuid = target?.uniqueId ?: player.uniqueId
        val targetName = EssentialsUtils.getNickname(target?.name ?: player.name)
        val isAdmin = target != null && player.hasPermission("story.journal.admin")

        // Create the book item
        val book = ItemStack(Material.WRITTEN_BOOK)
        val meta = book.itemMeta as BookMeta

        // Set book properties
        meta.title(story.miniMessage.deserialize(if (isAdmin) "$targetName's Memories" else "Memories"))
        meta.author(story.miniMessage.deserialize("<gold>Story Plugin</gold>"))

        val target = target ?: player

        if (target.name == null) {
            player.sendMessage(story.miniMessage.deserialize("<red>Error: Target player has no name!"))
            return
        }
        // Get memories for the target player
        val contextResult =
            story.npcContextGenerator.getOrCreateContextForNPC(
                EssentialsUtils.getNickname(target.name!!),
            )
        val memories =
            contextResult?.memories?.let { memList ->
                val memoryList = ArrayList(memList)
                memoryList.sortWith { m1, m2 ->
                    m2.gameCreatedAt.compareTo(m1.gameCreatedAt) // Sort by creation time, newest first
                }
                memoryList
            }

        if (memories == null) {
            player.sendMessage(story.miniMessage.deserialize("<red>Error: No memories found for ${target.name}"))
            return
        }

        if (memories.isEmpty()) {
            val noMemoriesPage =
                story.miniMessage.deserialize("<gold>No Memories Found</gold>\n\n<gray>You have no recorded memories.")
            meta.addPages(noMemoriesPage)
        } else {
            var memoryIndex = 1
            memories.forEach { memory ->
                // get cur page index
                var pageIndex = meta.pages().size
                if (pageIndex == 0) {
                    meta.addPages(
                        story.miniMessage.deserialize("<#8e44ad>Memory</#8e44ad>\n"),
                    ) // Ensure we start with at least one page
                    pageIndex = 1
                }
                // Add memory title
                meta.page(
                    pageIndex,
                    story.miniMessage.deserialize(
                        "<#8e44ad>Memory</#8e44ad> <gray><i>(${memory.getElapsedTime(story.timeService)} ago)\n\n",
                    ),
                )
                memoryIndex = memoryIndex + 1

                splitIntoPages(memory.content).forEach { page ->
                    val content = meta.page(pageIndex).append(Component.text(page))
                    meta.page(pageIndex, content)
                    pageIndex = pageIndex + 1
                    meta.addPages(Component.text("\n\n")) // Add empty page to continue
                }
            }
        }

        // Set the metadata back to the book
        book.itemMeta = meta

        // Open the book for the player
        player.openBook(book)
    }

    // In QuestCommandUtils class
    fun openQuestBook(
        player: Player,
        target: OfflinePlayer? = null,
    ) {
        val targetUuid = target?.uniqueId ?: player.uniqueId
        val targetName = EssentialsUtils.getNickname(target?.name ?: player.name)
        val isAdmin = target != null && player.hasPermission("story.quest.admin")

        // Create the book item
        val book = ItemStack(Material.WRITTEN_BOOK)
        val meta = book.itemMeta as BookMeta

        // Set book properties
        meta.title(story.miniMessage.deserialize(if (isAdmin) "$targetName's Quest Book" else "Quest Book"))
        meta.author(story.miniMessage.deserialize("<gold>Story Plugin</gold>"))

        // Get quests for the target player
        val quests = story.questManager.getPlayerQuests(targetUuid)

        // Filter out completed quests
        val activeQuests =
            quests.filter { (questId, _) ->
                val status = story.questManager.getPlayerQuestStatus(target ?: player, questId)
                status == QuestStatus.IN_PROGRESS
            }

        if (activeQuests.isEmpty()) {
            val noQuestsPage =
                Component
                    .text()
                    .append(story.miniMessage.deserialize("<gold>No Active Quests</gold>\n\n"))
                    .append(
                        story.miniMessage.deserialize(
                            "<gray>I do not have any quests at the moment.",
                        ),
                    ).build()
            meta.addPages(noQuestsPage)
        } else {
            // Convert map entries to a list and reverse it
            val reversedEntries = activeQuests.entries.toList().reversed()

            // Create pages for each quest
            reversedEntries.forEach { (questId, playerQuest) ->
                val quest = story.questManager.getQuest(questId) ?: return@forEach
                val status = story.questManager.getPlayerQuestStatus(target ?: player, questId)
                val statusColor = getStatusColor(status)
                val statusText = statusParser(status.name)

                // Create quest page
                val pageContent =
                    Component
                        .text()
                        .append(story.miniMessage.deserialize("<#8e44ad>${quest.title}</#8e44ad>\n"))
                        .append(
                            story.miniMessage.deserialize(
                                "<black>${truncateDescription(quest.description)}</black>\n\n",
                            ),
                        ).append(story.miniMessage.deserialize("<#16a085>Objectives:</#16a085>\n"))

                // Add objectives
                val currentObjectiveMap = story.questManager.getCurrentObjective(target ?: player, questId)
                val currentObjectiveIndex = currentObjectiveMap?.keys?.firstOrNull()

                quest.objectives.forEachIndexed { index, obj ->
                    val marker =
                        when {
                            currentObjectiveIndex != null && index < currentObjectiveIndex -> "<st><#10ac84>✔"
                            currentObjectiveIndex != null && index == currentObjectiveIndex -> "<reset><#d35400>⟳"
                            else -> "<reset><red>✘"
                        }
                    pageContent.append(story.miniMessage.deserialize("$marker ${obj.description}\n"))
                }

                meta.addPages(pageContent.build())
            }
        }

        // Set the metadata back to the book
        book.itemMeta = meta

        // Open the book for the player
        player.openBook(book)
    }

    /**
     * Truncates a quest description to a reasonable length for book display
     */
    private fun truncateDescription(description: String): String {
        val maxLength = 150 // Maximum length for description
        return if (description.length > maxLength) {
            description.substring(0, maxLength - 3) + "..."
        } else {
            description
        }
    }

    /**
     * Splits content into pages with fixed values:
     * - 165 characters per page maximum
     * - Preferably 3 paragraphs per page for readability
     * - Maximum 6 lines per page
     */
    fun splitIntoPages(content: String): List<String> {
        val pages = mutableListOf<String>()

        // Split by paragraphs first
        val paragraphs = content.split("\n\n").filter { it.isNotBlank() }

        var currentPage = StringBuilder()
        var paragraphsInCurrentPage = 0
        var index = 0
        val maxCharacters = story.config.maxBookCharactersPerPage

        while (index < paragraphs.size) {
            val paragraph = paragraphs[index]

            // Track if this paragraph has unclosed tags
            val openTags = mutableListOf<String>()
            val tagPattern = Pattern.compile("<([^/>]+)>|</([^>]+)>")
            val matcher = tagPattern.matcher(paragraph)

            while (matcher.find()) {
                val openTag = matcher.group(1)
                val closeTag = matcher.group(2)

                if (openTag != null) {
                    openTags.add(openTag)
                } else if (closeTag != null && openTags.isNotEmpty()) {
                    if (openTags.last() == closeTag) {
                        openTags.removeAt(openTags.size - 1)
                    }
                }
            }

            val hasUnclosedTags = openTags.isNotEmpty()

            // Check if adding this paragraph would exceed character limit
            if (currentPage.length + paragraph.length + (if (currentPage.isEmpty()) 0 else 2) <= maxCharacters) {
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
                // Paragraph is too large for the current page
                if (currentPage.isNotEmpty()) {
                    // Finish current page first
                    pages.add(currentPage.toString().trim())
                    currentPage = StringBuilder()
                    paragraphsInCurrentPage = 0
                }

                // If the paragraph has unclosed tags, keep it intact on its own page
                if (hasUnclosedTags) {
                    pages.add(paragraph.trim())
                    index++
                    continue
                }

                // Now handle the large paragraph
                var remainingText = paragraph
                while (remainingText.isNotEmpty()) {
                    // Find a good breaking point within character limit
                    val chunkSize = minOf(remainingText.length, maxCharacters)
                    val breakPoint =
                        if (chunkSize < remainingText.length) {
                            val possibleBreakPoint =
                                remainingText
                                    .substring(0, chunkSize)
                                    .lastIndexOfAny(charArrayOf(' ', '.', '!', '?', ','))
                            if (possibleBreakPoint > 0) possibleBreakPoint + 1 else chunkSize
                        } else {
                            chunkSize
                        }

                    val chunk = remainingText.substring(0, breakPoint).trim()
                    currentPage.append(chunk)
                    paragraphsInCurrentPage++

                    remainingText =
                        if (breakPoint < remainingText.length) {
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
