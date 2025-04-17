package com.canefe.story.npc.service

import com.canefe.story.Story
import dev.lone.itemsadder.api.FontImages.FontImageWrapper
import net.citizensnpcs.api.npc.NPC
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.mcmonkey.sentinel.SentinelTrait
import java.util.*
import java.util.regex.Pattern

class NPCMessageService(private val plugin: Story) {

    fun broadcastNPCMessage(
        message: String,
        npc: NPC,
        color: String? = null
    ) {
        val mm = MiniMessage.miniMessage()
        val maxLineWidth = 40 // Adjust based on desired character limit per line
        val padding = "             " // Space padding to align text with the image

        // Split response into lines to handle multi-line input
        val lines = message.split("\\n+".toRegex())
        val parsedMessages = ArrayList<Component>()

        var npcName = npc.name

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue // Skip empty lines

            // Regex to capture NPC name and dialogue/emote
            val pattern = Pattern.compile("^([A-Za-z0-9_\\s]+):\\s*(.*)$")
            val matcher = pattern.matcher(trimmedLine)

            var cleanedMessage = trimmedLine // Default to the full line if no match

            if (matcher.find()) {
                npcName = matcher.group(1).trim() // Extract NPC name
                cleanedMessage = matcher.group(2).trim() // Extract message content

                // Remove all duplicate NPC name prefixes (e.g., "Ubyr: Ubyr: Ubyr:")
                while (cleanedMessage.startsWith("$npcName:")) {
                    cleanedMessage = cleanedMessage.substring(npcName.length + 1).trim()
                }
            }

            // Handle emotes inside the message (*content*)
            val formattedMessage = cleanedMessage.replace(Regex("\\*(.*?)\\*"), "<gray><italic>$1</italic></gray>")

            // Split the message into multiple lines to fit within the width limit
            val wrappedLines = wrapTextWithFormatting(formattedMessage, maxLineWidth)
            parsedMessages.add(mm.deserialize(padding)) // Empty line for spacing


            val avatar = plugin.npcContextGenerator.getOrCreateContextForNPC(npcName)?.avatar

            // Get avatar as a string with legacy formatting
            val rawAvatar = when {
                !avatar.isNullOrEmpty() && plugin.itemsAdderEnabled -> {
                    try {
                        val fontImageWrapper = FontImageWrapper("npcavatars:$avatar").string
                        fontImageWrapper
                    } catch (e: Exception) {
                        plugin.logger.warning("Error loading avatar for NPC $npcName: ${e.message}")
                        "            "
                    }
                }
                else -> "            "
            }

            // Create components directly
            val avatarComponent = LegacyComponentSerializer.legacySection().deserialize(rawAvatar)
            val defaultColor = plugin.npcUtils.randomColor(npcName)
            val nameComponent = mm.deserialize(" <${color ?: defaultColor}>$npcName</${color ?: defaultColor}>")

            // Combine them
            parsedMessages.add(Component.empty().append(avatarComponent).append(nameComponent))

            // Add padding spaces before each wrapped line for alignment
            for (wrappedLine in wrappedLines) {
                parsedMessages.add(mm.deserialize("$padding$wrappedLine"))
            }

            // Add empty lines for spacing
            repeat(5) {
                parsedMessages.add(mm.deserialize(padding))
            }
        }

        Bukkit.getScheduler().runTask(plugin, Runnable {
            // Only send message to players who are nearby OR have permission
            val npcLocation = npc.entity?.location ?: return@Runnable

            // Determine which players can see the message
            for (p in Bukkit.getOnlinePlayers()) {
                var shouldSee = p.hasPermission("storymaker.npc.hearglobal") ||
                        (p.world == npcLocation.world &&
                                p.location.distance(npcLocation) <= plugin.config.chatRadius)
                plugin.playerManager.getSpyingConversation(p)?.let { conversation ->
                    shouldSee = conversation.hasNPC(npc)
                }
                // Send messages to players who should see them
                if (shouldSee) {
                    parsedMessages.forEach { message ->
                        p.sendMessage(message)
                    }
                }
            }

            // Send for console
            parsedMessages.forEach { message ->
                Bukkit.getConsoleSender().sendMessage(message)
            }
        })
    }

    // Helper methods for text wrapping
    private fun wrapTextWithFormatting(text: String, maxWidth: Int): List<String> {
        val result = ArrayList<String>()
        var remainingText = text
        var safetyLimit = 100 // to avoid infinite loops

        while (remainingText.isNotEmpty() && safetyLimit-- > 0) {
            // if the remaining plain text fits, we're done
            if (getPlainTextLength(remainingText) <= maxWidth) {
                result.add(remainingText)
                break
            }

            // Find a break point based solely on plain text length
            val breakPoint = findBreakPoint(remainingText, maxWidth)
            val line = remainingText.substring(0, breakPoint).trim()
            remainingText = remainingText.substring(breakPoint).trim()

            // Get all active (open but not closed) tags at the end of 'line'
            val activeTags = getActiveTags(line)

            // Append closing tags in reverse order to properly close them
            val closingTags = StringBuilder()
            for (i in activeTags.size - 1 downTo 0) {
                closingTags.append(getClosingTag(activeTags[i]))
            }
            val finalizedLine = line + closingTags.toString()
            result.add(finalizedLine)

            // For the next line, prepend the active tags so that the formatting continues
            val reopening = StringBuilder()
            for (tag in activeTags) {
                reopening.append(tag)
            }
            remainingText = reopening.toString() + remainingText
        }

        return result
    }

    private fun getPlainTextLength(text: String): Int {
        // Remove all tags and return the length of what remains
        return text.replace("<[^>]+>".toRegex(), "").length
    }

    private fun findBreakPoint(text: String, maxWidth: Int): Int {
        var count = 0
        var lastSpaceIndex = -1
        var inTag = false

        for (i in text.indices) {
            val c = text[i]
            if (c == '<') inTag = true
            if (!inTag) {
                count++
                if (c == ' ') lastSpaceIndex = i
            }
            if (c == '>') inTag = false
            if (count >= maxWidth) {
                return if (lastSpaceIndex != -1) lastSpaceIndex else i + 1
            }
        }
        return text.length
    }

    private fun getActiveTags(text: String): List<String> {
        // This method parses the string and returns a list of all formatting tags that have been opened but not closed
        val active = ArrayList<String>()
        val pattern = Pattern.compile("</?(gray|italic)>")
        val matcher = pattern.matcher(text)

        while (matcher.find()) {
            val tag = matcher.group()
            if (tag.startsWith("</")) {
                // For a closing tag, remove the last matching open tag if it exists
                val openTag = "<" + tag.substring(2)
                val lastIndex = active.lastIndexOf(openTag)
                if (lastIndex != -1) {
                    active.removeAt(lastIndex)
                }
            } else {
                active.add(tag)
            }
        }
        return active
    }

    private fun getClosingTag(openTag: String): String {
        // Simply turn an opening tag into its corresponding closing tag
        return if (openTag.startsWith("<") && openTag.endsWith(">")) {
            "</" + openTag.substring(1)
        } else {
            ""
        }
    }

    companion object {
        private var instance: NPCMessageService? = null

        @JvmStatic
        fun getInstance(plugin: Story): NPCMessageService {
            return instance ?: NPCMessageService(plugin).also { instance = it }
        }
    }
}