package com.canefe.story.npc.service

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.npc.data.NPCContext
import com.canefe.story.util.EssentialsUtils
import dev.lone.itemsadder.api.FontImages.FontImageWrapper
import net.citizensnpcs.api.npc.NPC
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.regex.Pattern
import kotlin.text.append

class NPCMessageService(
    private val plugin: Story,
) {
    // Cache already gender-checked NPCs
    private val genderCache: MutableMap<String, String> = mutableMapOf()

    // Last played sound (to change the sound if the same NPC speaks again)
    private val lastPlayedSound: MutableMap<String, String> = mutableMapOf()

    fun load() {
        // Reset the cache
        genderCache.clear()
    }

    /**
     * Creates formatted components for a message from an entity (NPC or player)
     * @param message The message content
     * @param name The sender's name
     * @param color Optional color for the name
     * @param avatar Optional avatar identifier
     * @param characterId Optional character id for streaming messages
     * @return List of Components ready to be sent
     */
    fun formatMessage(
        message: String,
        name: String,
        color: String? = null,
        avatar: String? = null,
        characterId: UUID? = null,
        isClientPlayer: Boolean = false,
        formatColor: String? = "<white>",
        formatColorSuffix: String? = "</white>",
    ): List<Component> {
        val mm = MiniMessage.miniMessage()
        val maxLineWidth = plugin.config.maxLineWidth // Use configurable value from config
        val padding = "             " // Space padding to align text with the image
        val nameColor = color ?: plugin.npcUtils.randomColor(name)
        // Split response into lines to handle multi-line input
        val lines = message.split("\\n+".toRegex())
        val parsedMessages = ArrayList<Component>()

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue // Skip empty lines

            // Regex to capture name and dialogue/emote
            val pattern = Pattern.compile("^([A-Za-z0-9_\\s]+):\\s*(.*)$")
            val matcher = pattern.matcher(trimmedLine)

            var sender = name
            var cleanedMessage = trimmedLine // Default to the full line if no match

            if (matcher.find()) {
                sender = matcher.group(1).trim() // Extract name
                cleanedMessage = matcher.group(2).trim() // Extract message content

                // Remove all duplicate sender name prefixes (e.g., "Name: Name: Name:")
                while (cleanedMessage.startsWith("$sender:")) {
                    cleanedMessage = cleanedMessage.substring(sender.length + 1).trim()
                }
            }

            // Handle emotes inside the message (*content*)
            val formattedMessage =
                characterId?.run { cleanedMessage }
                    ?: cleanedMessage.replace(Regex("\\*(.*?)\\*\\s*"), "<gray><italic>($1)</italic></gray>\n$padding")

            // Split the message into multiple lines to fit within the width limit
            val wrappedLines = wrapTextWithFormatting(formattedMessage, maxLineWidth)
            parsedMessages.add(mm.deserialize(padding)) // Empty line for spacing

            // Get avatar as a string with legacy formatting
            val rawAvatar =
                when {
                    !avatar.isNullOrEmpty() && plugin.itemsAdderEnabled -> {
                        try {
                            val fontImageWrapper = FontImageWrapper("npcavatars:$avatar").string
                            fontImageWrapper
                        } catch (e: Exception) {
                            plugin.logger.warning("Error loading avatar for entity $name: ${e.message}")
                            "            "
                        }
                    }
                    else -> "            "
                }

            // Create components directly
            val avatarComponent = LegacyComponentSerializer.legacySection().deserialize(rawAvatar)
            val nameComponent =
                mm.deserialize(
                    " <${color ?: nameColor}>$sender</${color ?: nameColor}> ${if (isClientPlayer) "<red>(YOU)</red>" else ""}",
                )

            // Combine them
            parsedMessages.add(Component.empty().append(avatarComponent).append(nameComponent))

            // Add padding spaces before each wrapped line for alignment
            for (wrappedLine in wrappedLines) {
                // Check if the line already starts with padding (from action processing)
                val lineWithPadding =
                    if (wrappedLine.startsWith(padding)) {
                        // Line already has padding, don't add more
                        wrappedLine
                    } else {
                        // Line needs padding
                        "$padding$wrappedLine"
                    }
                parsedMessages.add(mm.deserialize("$formatColor$lineWithPadding$formatColorSuffix"))
            }

            // Add empty lines for spacing
            repeat(2) {
                parsedMessages.add(mm.deserialize(padding))
            }
        }

        // If this is a typing message, wrap everything in typing tags
        // Convert the formatted message to a string for the typing system
        val stringBuilder = StringBuilder()
        for (component in parsedMessages) {
            stringBuilder.append(MiniMessage.miniMessage().serialize(component)).append("\n")
        }

        if (characterId != null) {
            return listOf(
                MiniMessage
                    .miniMessage()
                    .deserialize("<npc_typing>color:$nameColor id:$characterId:$stringBuilder"),
            )
        }

        return parsedMessages
    }

    fun broadcastNPCMessage(
        message: String,
        npc: NPC,
        color: String? = null,
        npcContext: NPCContext? = null,
        streaming: Boolean = false,
        shouldBroadcast: Boolean = true,
    ) {
        // First check if we already have the gender cached
        val cachedGender = genderCache[npc.name]

        // Use the cached gender if available, or start determining gender
        val genderFuture =
            if (cachedGender != null) {
                CompletableFuture.completedFuture(cachedGender)
            } else {
                determineNPCGenderAsync(npc.name)
                    .thenApply { gender ->
                        // Cache the result for future use
                        genderCache[npc.name] = gender
                        gender
                    }.exceptionally { e ->
                        plugin.logger.warning("Error determining gender for NPC ${npc.name}: ${e.message}")
                        "man" // Default fallback on error
                    }
            }

        // Get the context if not provided
        val context = npcContext ?: plugin.npcContextGenerator.getOrCreateContextForNPC(npc)

        // Format the message
        val parsedMessages =
            formatMessage(
                message = message,
                name = npcContext?.name ?: npc.name,
                color = color,
                avatar = context?.avatar,
                characterId = if (streaming && npc.entity != null) npc.entity.uniqueId else null,
            )

        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                val entity =
                    plugin.disguiseManager.isNPCBeingImpersonated(npc).let {
                        // if it is true then return the player
                        plugin.disguiseManager.getDisguisedPlayer(npc)
                    }
                        ?: npc.entity

                // Only send message to players who are nearby OR have permission
                val npcLocation = entity?.location ?: return@Runnable
                val disabledHearing = plugin.playerManager.disabledHearing

                val players = HashSet<Player>()
                // Determine which players can see the message
                for (p in Bukkit.getOnlinePlayers()) {
                    // Check if the player is in the same world and within the radius
                    val inRange =
                        p.world == npcLocation.world &&
                            p.location.distance(npcLocation) <= plugin.config.radiantRadius

                    // Check if the player has permission to see the message or in range
                    var shouldSee =
                        (p.hasPermission("story.conversation.hearglobal") && !disabledHearing.contains(p.uniqueId)) ||
                            inRange

                    // Check if the admin is spying only one conversation
                    plugin.playerManager.getSpyingConversation(p)?.let { conversation ->
                        shouldSee = conversation.hasNPC(npc)
                    }

                    // Schedule messages shouldn't be seen by admins
                    if (plugin.scheduleManager.getSchedule(npc.name) != null &&
                        !inRange &&
                        p.hasPermission("story.conversation.hearglobal")
                    ) {
                        shouldSee = false
                    }

                    // Send messages to players who should see them
                    if (shouldSee) {
                        players.add(p)
                        parsedMessages.forEach { component ->
                            p.sendMessage(component)
                        }
                    }
                }

                var conversation = plugin.conversationManager.getConversation(npc)

                // If in a conversation, make other npcs look at the speaking NPC
                conversation?.let { conversation ->
                    if (conversation.hasNPC(npc)) {
                        // Make all other NPCs in the conversation look at this one
                        for (otherNpc in conversation.npcs) {
                            if (otherNpc != npc) {
                                otherNpc.entity?.let { otherEntity ->
                                    // Only turn head if the other NPC entity is valid
                                    if (otherEntity.isValid && (npc.entity != null && npc.entity.isValid)) {
                                        // Turn head towards the speaking NPC
                                        plugin.npcBehaviorManager.turnHead(otherNpc, npc.entity)
                                    }
                                }
                            }
                        }
                    }
                }
                if (!streaming) {
                    plugin.voiceManager
                        .generateSpeechForNPC(npc, message, players)
                        .thenAccept { success ->
                            if (!success) {
                                // Wait for gender determination and then play sound
                                genderFuture.thenAccept { gender ->
                                    // Use cached lastPlayedSound to avoid repetition
                                    val npcKey = npc.name
                                    val lastSound = lastPlayedSound[npcKey]

                                    plugin.audioManager
                                        .playRandomVoice(
                                            location = npc.entity.location,
                                            gender = gender,
                                            lastPlayed = lastSound,
                                        ).thenAccept { soundId ->
                                            // Update the last played sound for this NPC
                                            lastPlayedSound[npcKey] = soundId
                                        }
                                }
                            }
                        }
                }

                // Send for console
                if (!streaming && shouldBroadcast) {
                    parsedMessages.forEach { message ->
                        Bukkit.getConsoleSender().sendMessage(message)
                    }
                }
            },
        )
    }

    /**
     * NPC Stream Message Broadcast add prefix <npc_typing> to indicate we are in a stream (AI LLM)
     */
    fun broadcastNPCStreamMessage(
        message: String,
        npc: NPC,
        color: String? = null,
        npcContext: NPCContext? = null,
    ) {
        broadcastNPCMessage(
            message = message,
            npc = npc,
            color = color,
            npcContext = npcContext,
            streaming = true,
        )
    }

    /**
     * Broadcasts a player message with the same formatting as NPC messages
     */
    fun broadcastPlayerMessage(
        message: String,
        player: Player,
        color: String? = null,
    ) {
        val playerName = EssentialsUtils.getNickname(player.name)

        // Get player context for avatar support (using NPC data system for players)
        val playerContext = plugin.npcContextGenerator.getOrCreateContextForNPC(player.name)

        // Normal chat format
        val parsedMessages =
            formatMessage(
                message = message,
                name = playerName,
                color = color,
                avatar = playerContext?.avatar, // Add avatar support for players
            )

        // Speech bubble format
        val speechBubbleMessages =
            formatMessage(
                message = message,
                name = playerName,
                color = color,
                avatar = playerContext?.avatar, // Add avatar support for players
                characterId = player.uniqueId,
            )

        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                val location = player.location
                val disabledHearing = plugin.playerManager.disabledHearing

                // Collect players who should see the message
                val players = HashSet<Player>()

                // Determine which players can see the message
                for (p in Bukkit.getOnlinePlayers()) {
                    // Check if the player is in the same world and within the radius
                    val inRange =
                        p.world == location.world &&
                            p.location.distance(location) <= plugin.config.radiantRadius

                    // Check if the player has permission to see the message or in range
                    val shouldSee =
                        (p.hasPermission("story.conversation.hearglobal") && !disabledHearing.contains(p.uniqueId)) ||
                            inRange

                    // Send messages to players who should see them
                    if (shouldSee) {
                        players.add(p)

                        // If this is the speaking player, use a version with (YOU) added
                        val messagesToSend =
                            if (p == player) {
                                formatMessage(
                                    message = message,
                                    name = playerName,
                                    color = color,
                                    avatar = playerContext?.avatar,
                                    isClientPlayer = true,
                                )
                            } else {
                                parsedMessages
                            }

                        messagesToSend.forEach { component ->
                            p.sendMessage(component)
                        }

                        // Also send them as speech bubbles
                        speechBubbleMessages.forEach { component ->
                            if (p != player) {
                                p.sendMessage(component)
                            }
                        }
                    }
                }

                // Generate voice for the player message
                plugin.voiceManager
                    .generateSpeechForPlayer(player, message, players)
                    .thenAccept { success ->
                        if (!success) {
                            plugin.logger.info("Voice generation skipped or failed for player ${player.name}")
                        }
                    }

                // Send for console
                parsedMessages.forEach { component ->
                    Bukkit.getConsoleSender().sendMessage(component)
                }
            },
        )
    }

    /**
     * Asynchronously determines an NPC's gender
     * Returns a CompletableFuture that will be completed with the gender
     */
    private fun determineNPCGenderAsync(npcName: String): CompletableFuture<String> {
        // Create the result future
        val resultFuture = CompletableFuture<String>()

        // First check the cache
        genderCache[npcName]?.let {
            resultFuture.complete(it)
            return resultFuture
        }

        // First try the fast approach with tags and pronouns
        val npcContext = plugin.npcContextGenerator.getOrCreateContextForNPC(npcName)
        val contextStr = npcContext?.context ?: ""

        // Check for explicit gender tag
        val genderTagRegex = Regex("GENDER:\\s*(\\w+)", RegexOption.IGNORE_CASE)
        val genderTagMatch = genderTagRegex.find(contextStr)

        // If we have an explicit tag, use it immediately without AI
        if (genderTagMatch != null) {
            val explicitGender = genderTagMatch.groupValues[1].lowercase()
            val result =
                when (explicitGender) {
                    "female" -> "girl"
                    "male" -> "man"
                    else -> explicitGender
                }
            // Cache the result
            genderCache[npcName] = result
            resultFuture.complete(result)
            return resultFuture
        }

        // If we get here, we need to use AI
        // Run AI determination in another thread
        CompletableFuture.runAsync {
            try {
                val npcData = plugin.npcDataManager.getNPCData(npcName)
                val memoriesContext =
                    npcData
                        ?.memory
                        ?.sortedByDescending { it.lastAccessed }
                        ?.take(3)
                        ?.joinToString("\n") { it.content }
                        ?: ""

                val prompt = mutableListOf<ConversationMessage>()
                prompt.add(
                    ConversationMessage(
                        "system",
                        """
                    Based on the character name, context, and memories provided, determine the character's gender.
                    Consider name conventions, pronouns used, and any context clues in memories or descriptions.
                    Respond ONLY with one of these words: "girl", "man", or "unknown".
                    """,
                    ),
                )

                prompt.add(
                    ConversationMessage(
                        "user",
                        """
                    Character Name: $npcName

                    Character Context:
                    $contextStr

                    Character Memories:
                    $memoriesContext
                    """,
                    ),
                )

                // Get AI response - wait as long as needed
                val response =
                    plugin
                        .getAIResponse(prompt, lowCost = true)
                        .get()
                        ?.trim()
                        ?.lowercase()

                // Determine gender from response
                val aiGender =
                    when {
                        response?.contains("girl") == true ||
                            response?.contains("female") == true ||
                            response?.contains("woman") == true -> "girl"
                        response?.contains("man") == true ||
                            response?.contains("male") == true -> "man"
                        else -> "man" // Default fallback
                    }

                // Cache the result
                genderCache[npcName] = aiGender

                // Complete the future
                resultFuture.complete(aiGender)
            } catch (e: Exception) {
                plugin.logger.warning("AI gender determination failed for NPC $npcName: ${e.message}")
                // Default fallback on error
                genderCache[npcName] = "man"
                resultFuture.complete("man")
            }
        }

        return resultFuture
    }

    // Helper methods for text wrapping
    private fun wrapTextWithFormatting(
        text: String,
        maxWidth: Int,
    ): List<String> {
        val result = ArrayList<String>()
        val padding = "             " // Same padding used in formatMessage

        // Split by newlines first to handle action-forced line breaks
        val lineSegments = text.split("\n")

        for (segment in lineSegments) {
            var remainingText = segment
            var safetyLimit = 100 // to avoid infinite loops

            while (remainingText.isNotEmpty() && safetyLimit-- > 0) {
                // Remove padding from the beginning if it exists for length calculation
                val textForCalculation =
                    if (remainingText.startsWith(padding)) {
                        remainingText.substring(padding.length)
                    } else {
                        remainingText
                    }

                // if the remaining plain text fits, we're done
                if (getPlainTextLength(textForCalculation) <= maxWidth) {
                    result.add(remainingText)
                    break
                }

                // Find a break point based solely on plain text length
                val breakPoint = findBreakPoint(remainingText, maxWidth, padding)
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

                // For the next line, prepend the active tags and padding so that the formatting continues
                val reopening = StringBuilder()
                // Add padding for continuation lines only if needed
                if (!remainingText.startsWith(padding)) {
                    reopening.append(padding)
                }
                for (tag in activeTags) {
                    reopening.append(tag)
                }
                // Ensure no extra spaces are added when combining
                remainingText = reopening.toString() + remainingText.trimStart()
            }
        }

        return result
    }

    private fun getPlainTextLength(text: String): Int {
        // Remove all tags and return the length of what remains
        return text.replace("<[^>]+>".toRegex(), "").length
    }

    private fun findBreakPoint(
        text: String,
        maxWidth: Int,
        padding: String = "",
    ): Int {
        var count = 0
        var lastSpaceIndex = -1
        var inTag = false
        var skipPadding = text.startsWith(padding)
        var paddingSkipped = 0

        for (i in text.indices) {
            val c = text[i]

            // Skip padding at the beginning for character counting
            if (skipPadding && paddingSkipped < padding.length) {
                paddingSkipped++
                continue
            }

            if (c == '<') inTag = true
            if (!inTag) {
                count++
                if (c == ' ') lastSpaceIndex = i
            }
            if (c == '>') inTag = false
            if (count >= maxWidth) {
                // If we found a space within the limit, break there
                if (lastSpaceIndex != -1) {
                    return lastSpaceIndex
                }
                // If no space found, look ahead for the next space to avoid breaking words
                for (j in i until text.length) {
                    if (text[j] == ' ' || text[j] == '<') {
                        return j
                    }
                }
                // If no space found at all, return the current position as last resort
                return i
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
        fun getInstance(plugin: Story): NPCMessageService = instance ?: NPCMessageService(plugin).also { instance = it }
    }
}
