package com.canefe.story.command.base

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.api.character.AICharacter
import com.canefe.story.api.character.Character
import com.canefe.story.api.character.CharacterDTO
import com.canefe.story.api.character.PlayerCharacter
import com.canefe.story.command.conversation.ConvCommand
import com.canefe.story.command.player.PlayerConfigCommand
import com.canefe.story.command.story.StoryCommand
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.location.data.StoryLocation
import com.canefe.story.npc.CitizensStoryNPC
import com.canefe.story.npc.StubStoryNPC
import com.canefe.story.npc.data.NPCData
import com.canefe.story.npc.util.NPCUtils
import com.canefe.story.util.*
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.*
import dev.jorel.commandapi.executors.CommandArguments
import dev.jorel.commandapi.executors.CommandExecutor
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import io.lumine.mythic.bukkit.MythicBukkit
import net.citizensnpcs.api.CitizensAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Centralized command manager that registers and manages all plugin commands.
 */
class CommandManager(
    private val plugin: Story,
) {
    private val commandExecutors = mutableMapOf<String, CommandExecutor>()

    // Stores pending NPC resolve callbacks per player UUID
    private val pendingNPCResolve = mutableMapOf<UUID, (StoryNPC) -> Unit>()

    /**
     * Called during plugin load to initialize CommandAPI
     */
    fun onLoad() {
    }

    /**
     * Called during plugin enable to register all commands
     */
    fun registerCommands() {
        // Register CommandAPI commands
        registerCommandAPICommands()
    }

    /**
     * Called during plugin disable to clean up commands
     */
    fun onDisable() {
    }

    private fun registerCommandAPICommands() {
        // Register structured commands
        ConvCommand(plugin).register()
        StoryCommand(plugin).register()
        PlayerConfigCommand(plugin).register()

        // Register simpler commands
        registerSimpleCommands()
    }

    private fun registerSimpleCommands() {
        // resetcitizensnavigation
        CommandAPICommand("resetcitizensnavigation")
            .withPermission("storymaker.npc.navigation")
            .executes(
                CommandExecutor { sender, _ ->
                    val npcRegistry = CitizensAPI.getNPCRegistry()
                    for (citizensNpc in npcRegistry) {
                        val npc: StoryNPC = CitizensStoryNPC(citizensNpc)
                        npc.cancelNavigation()
                        npc.stopFollowing()
                    }
                    sender.sendSuccess("All NPC navigation has been reset.")
                },
            ).register()

        // Register simple commands using CommandAPI
        CommandAPICommand("togglechat")
            .withPermission("storymaker.chat.toggle")
            .withOptionalArguments(PlayerArgument("target"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val target = args.getOptional("target").orElse(player) as? Player

                    plugin.playerManager.togglePlayerInteractions(player, target)
                },
            ).executes(
                dev.jorel.commandapi.executors.CommandExecutor { _, args ->
                    val target = args.get("target") as? Player

                    plugin.playerManager.togglePlayerInteractions(null, target)
                },
            ).register()

        CommandAPICommand("maketalk")
            .withPermission("storymaker.chat.toggle")
            .withArguments(
                dev.jorel.commandapi.arguments
                    .GreedyStringArgument("npc"),
            ).executes(
                dev.jorel.commandapi.executors.CommandExecutor { sender, args ->
                    val npc = args.get("npc") as String

                    // Fetch NPC conversation
                    val conversation = plugin.conversationManager.getConversation(npc)
                    if (conversation == null) {
                        val errorMessage =
                            plugin.miniMessage.deserialize(
                                "<red>No active conversation found for NPC '$npc'.",
                            )
                        sender.sendMessage(errorMessage)
                        throw CommandAPI.failWithString("No active conversation found for NPC '$npc'.")
                    }

                    val successMessage = plugin.miniMessage.deserialize("<green>NPC '$npc' is now talking.</green>")
                    sender.sendMessage(successMessage)
                    // Reset auto mode timer (debounce)
                    plugin.conversationManager.resetAutoTimer(conversation)
                    // Generate NPC responses
                    plugin.conversationManager.generateResponses(conversation, npc)
                },
            ).register()

        // togglegpt
        CommandAPICommand("togglegpt")
            .withPermission("storymaker.chat.toggle")
            .executes(
                dev.jorel.commandapi.executors.CommandExecutor { sender, _ ->
                    plugin.config.chatEnabled = !plugin.config.chatEnabled
                    if (plugin.config.chatEnabled) {
                        sender.sendSuccess("Chat with NPCs enabled.")
                    } else {
                        sender.sendError("Chat with NPCs disabled.")
                    }
                    plugin.config.save()
                },
            ).register()

        // toggleradiant
        CommandAPICommand("toggleradiant")
            .withPermission("storymaker.chat.toggle")
            .executes(
                dev.jorel.commandapi.executors.CommandExecutor { sender, _ ->
                    plugin.config.radiantEnabled = !plugin.config.radiantEnabled
                    if (plugin.config.radiantEnabled) {
                        sender.sendSuccess("Radiant chat enabled.")
                    } else {
                        sender.sendError("Radiant chat disabled.")
                    }
                    plugin.config.save()
                },
            ).register()

        // toggleschedule [<random_pathing>] toggle schedules or random pathing
        CommandAPICommand("toggleschedule")
            .withPermission("storymaker.npc.schedule")
            .withOptionalArguments(BooleanArgument("random_pathing"))
            .executes(
                CommandExecutor { sender, args ->
                    val randomPathing = args.getOptional("random_pathing").orElse(null) as? Boolean
                    if (randomPathing != null) {
                        plugin.config.randomPathingEnabled = randomPathing
                        if (randomPathing) {
                            sender.sendSuccess("Random pathing enabled.")
                        } else {
                            sender.sendError("Random pathing disabled.")
                        }
                    } else {
                        plugin.config.scheduleEnabled = !plugin.config.scheduleEnabled
                        if (plugin.config.scheduleEnabled) {
                            sender.sendSuccess("Schedules enabled.")
                        } else {
                            sender.sendError("Schedules disabled.")
                        }
                    }
                    plugin.config.save()
                },
            ).register()

        // npctalk
        CommandAPICommand("npctalk")
            .withPermission("storymaker.npc.talk")
            .withArguments(
                TextArgument("npc_name").replaceSuggestions(
                    ArgumentSuggestions.strings { _ ->
                        CitizensAPI
                            .getNPCRegistry()
                            .map { "\"${it.name}\"" }
                            .distinct()
                            .toTypedArray()
                    },
                ),
            ).withArguments(
                TextArgument("npc_name_target").replaceSuggestions(
                    ArgumentSuggestions.strings { _ ->
                        CitizensAPI
                            .getNPCRegistry()
                            .map { "\"${it.name}\"" }
                            .distinct()
                            .toTypedArray()
                    },
                ),
            ).withArguments(GreedyStringArgument("message"))
            .executesPlayer(
                PlayerCommandExecutor { player: Player, args: CommandArguments ->
                    val npcName = (args["npc_name"] as String).replace("\"", "")
                    val npcNameTarget = (args["npc_name_target"] as String).replace("\"", "")
                    val message = args["message"] as String
                    resolveNPCPair(player, npcName, npcNameTarget) { npc, target ->
                        generateAndWalkToNPC(npc, target, message)
                    }
                },
            ).register()

        // Command to generate memories for NPCs dynamically
        CommandAPICommand("npcmemory")
            .withPermission("storymaker.npc.memory")
            .withArguments(
                TextArgument("npc").replaceSuggestions(
                    ArgumentSuggestions.strings { _ ->
                        plugin.npcDataManager
                            .getAllNPCNames()
                            .map { "\"$it\"" }
                            .toTypedArray()
                    },
                ),
            ).withArguments(
                StringArgument("type").replaceSuggestions { info, builder ->
                    val suggestions = listOf("event", "conversation", "observation", "experience")
                    suggestions.forEach { builder.suggest(it) }
                    builder.buildFuture()
                },
            ).withArguments(GreedyStringArgument("context"))
            .executes(
                CommandExecutor { sender, args ->
                    val npcName = (args.get("npc") as String).replace("\"", "")
                    val type = args.get("type") as String
                    val context = args.get("context") as String

                    // Resolve NPC from name
                    val resolvedNpc = plugin.npcDataManager.getNPC(npcName)

                    // Check if NPC exists first
                    val npcData =
                        (
                            if (resolvedNpc !=
                                null
                            ) {
                                plugin.npcDataManager.getNPCData(resolvedNpc)
                            } else {
                                plugin.npcDataManager.getNPCData(npcName)
                            }
                        )
                            ?: run {
                                // Initialize NPC data if it doesn't exist
                                val npcContext =
                                    plugin.npcContextGenerator
                                        .getOrCreateContextForNPC(resolvedNpc ?: StubStoryNPC(npcName)) ?: run {
                                        sender.sendError("NPC context not found. Please create the NPC first.")
                                        return@CommandExecutor
                                    }
                                plugin.storage.saveCharacterData(
                                    CharacterDTO(
                                        name = npcName,
                                        role = npcContext.role,
                                        context = npcContext.context,
                                        appearance = "",
                                        locationName = "Wilderness",
                                    ),
                                )
                                (
                                    if (resolvedNpc !=
                                        null
                                    ) {
                                        plugin.npcDataManager.getNPCData(resolvedNpc)
                                    } else {
                                        plugin.npcDataManager.getNPCData(npcName)
                                    }
                                )
                                    ?: run {
                                        sender.sendError("Failed to create NPC data for $npcName")
                                        return@CommandExecutor
                                    }
                            }

                    sender.sendInfo("Creating memory for <yellow>$npcName</yellow> based on: <italic>$context</italic>")

                    val storyNpc = plugin.npcDataManager.getNPC(npcName)
                    val character: Character =
                        AICharacter.from(storyNpc ?: StubStoryNPC(npcName))

                    plugin.npcResponseService
                        .generateNPCMemory(character, type, context)
                        .thenAccept { memory ->
                            Bukkit.getScheduler().runTask(
                                plugin,
                                Runnable {
                                    if (memory != null) {
                                        sender.sendSuccess("Memory created for <yellow>$npcName</yellow>!")
                                        sender.sendInfo(
                                            "Memory preview: <yellow>${
                                                if (memory.content.length > 50) {
                                                    memory.content.substring(0, 50) + "..."
                                                } else {
                                                    memory.content
                                                }
                                            }</yellow>",
                                        )
                                    } else {
                                        sender.sendError("Failed to create memory for $npcName")
                                    }
                                },
                            )
                        }.exceptionally { e ->
                            Bukkit.getScheduler().runTask(
                                plugin,
                                Runnable {
                                    sender.sendError("Failed to create memory: ${e.message}")
                                },
                            )
                            null
                        }
                },
            ).register()

        // npcinit
        CommandAPICommand("npcinit")
            .withPermission("storymaker.npc.init")
            .withArguments(
                TextArgument("location").replaceSuggestions(
                    ArgumentSuggestions.strings { _ ->
                        plugin.locationManager
                            .getAllLocations()
                            .map { "\"${it.name}\"" }
                            .toTypedArray()
                    },
                ),
            ).withArguments(
                TextArgument("npc").replaceSuggestions(
                    ArgumentSuggestions.strings { _ ->
                        CitizensAPI
                            .getNPCRegistry()
                            .map { "\"${it.name}\"" }
                            .distinct()
                            .toTypedArray()
                    },
                ),
            ).withOptionalArguments(GreedyStringArgument("prompt"))
            .executes(
                CommandExecutor { sender, args: CommandArguments ->
                    val npcName = (args["npc"] as String).replace("\"", "")
                    val location = (args["location"] as String).replace("\"", "")
                    val prompt = args.getOrDefault("prompt", "") as String

                    val resolvedNpc = plugin.npcDataManager.getNPC(npcName)
                    val npcContext =
                        plugin.npcContextGenerator.getOrCreateContextForNPC(resolvedNpc ?: StubStoryNPC(npcName))
                            ?: run {
                                sender.sendError("NPC context not found. Please create the NPC first.")
                                return@CommandExecutor
                            }

                    val storyLocation =
                        plugin.locationManager.getLocation(location) ?: run {
                            sender.sendError("Location not found. Please create the location first.")
                            return@CommandExecutor
                        }

                    // Set the Location for the NPC
                    val npcData =
                        NPCData(
                            npcName,
                            npcContext.role,
                            storyLocation,
                            npcContext.context,
                        )

                    plugin.storage.saveCharacterData(
                        CharacterDTO
                            .from(npcData),
                    )

                    if (prompt.isNotEmpty()) {
                        // Inform player we're generating context
                        sender.sendInfo(
                            "Generating AI context for NPC <yellow>$npcName</yellow> based on: <italic>$prompt</italic>",
                        )

                        // Create a system message to instruct the AI
                        val messages: MutableList<ConversationMessage> = ArrayList()

                        // Add General Context and Location context
                        messages.add(
                            ConversationMessage(
                                "system",
                                plugin.npcContextGenerator
                                    .getGeneralContexts()
                                    .joinToString("\n"),
                            ),
                        )

                        messages.add(
                            ConversationMessage(
                                "system",
                                storyLocation.getContextForPrompt(plugin.locationManager),
                            ),
                        )

                        // Find relevant lore related to the context
                        val loreContexts = plugin.lorebookManager.findLoresByKeywords(prompt)
                        val loreInfo =
                            if (loreContexts.isNotEmpty()) {
                                "Relevant lore found: " + loreContexts.joinToString(", ") { it.loreName }
                            } else {
                                "No relevant lore found for the given context."
                            }

                        sender.sendSuccess(loreInfo)

                        // Include relevant lore in the prompt
                        messages.add(
                            ConversationMessage(
                                "system",
                                "Include these world lore elements in your writing:\n" +
                                    loreContexts.joinToString("\n\n") { "- ${it.loreName}: ${it.context}" },
                            ),
                        )

                        // Use PromptService to get the NPC character generation prompt
                        val characterPrompt = plugin.promptService.getNpcCharacterGenerationPrompt(npcName, location)

                        messages.add(ConversationMessage("system", characterPrompt))

                        // Add the user prompt
                        messages.add(ConversationMessage("user", prompt))

                        // Use CompletableFuture API instead of manual task scheduling
                        plugin
                            .getAIResponse(messages)
                            .thenAccept { response ->
                                // Return to the main thread to access Bukkit API
                                Bukkit.getScheduler().runTask(
                                    plugin,
                                    Runnable {
                                        if (response == null) {
                                            sender.sendInfo("Failed to generate NPC data for $npcName.")
                                            return@Runnable
                                        }

                                        try {
                                            // Extract the JSON object from the response
                                            val jsonContent = extractJsonFromString(response)

                                            // Use Gson instead of org.json.JSONObject
                                            val gson = com.google.gson.Gson()
                                            val npcInfo = gson.fromJson(jsonContent, NPCInfo::class.java)

                                            // Get context and appearance from the parsed object
                                            val context = npcInfo.context ?: ""
                                            val appearance = npcInfo.appearance ?: ""

                                            if (context.isEmpty() || appearance.isEmpty()) {
                                                sender.sendError("Failed to parse AI response. Using default values.")
                                                return@Runnable
                                            }

                                            // add npcContext.context before 'response'
                                            val contextWithNPCContext = "${npcContext.context} $context"

                                            val npcData =
                                                NPCData(
                                                    npcName,
                                                    npcContext.role,
                                                    storyLocation,
                                                    contextWithNPCContext,
                                                )

                                            npcData.appearance = appearance

                                            plugin.storage.saveCharacterData(
                                                CharacterDTO
                                                    .from(npcData),
                                            )
                                            sender.sendSuccess(
                                                "AI-generated profile for <yellow>$npcName</yellow> created!",
                                            )
                                            sender.sendInfo("Role: <yellow>${npcContext.role}</yellow>")
                                            sender.sendInfo(
                                                "Context summary: <yellow>${
                                                    if (context.length > 50) {
                                                        context.substring(0, 50) + "..."
                                                    } else {
                                                        context
                                                    }
                                                }</yellow>",
                                            )
                                            sender.sendInfo(
                                                "Appearance: <yellow>${
                                                    if (appearance.length > 50) {
                                                        appearance.substring(0, 50) + "..."
                                                    } else {
                                                        appearance
                                                    }
                                                }</yellow>",
                                            )
                                        } catch (e: Exception) {
                                            sender.sendError(
                                                "Failed to generate AI context: ${e.message}. Using default values.",
                                            )
                                            plugin.logger.warning("Error parsing NPC data from AI: ${e.message}")
                                            e.printStackTrace()
                                        }
                                    },
                                )
                            }.exceptionally { e ->
                                Bukkit.getScheduler().runTask(
                                    plugin,
                                    Runnable {
                                        sender.sendError(
                                            "Failed to generate AI context: ${e.message}. Using default values.",
                                        )
                                    },
                                )
                                null
                            }
                    }
                },
            ).register()

        // locinitnpcs command - Multi-agent NPC population system
        CommandAPICommand("locinitnpcs")
            .withPermission("storymaker.npc.init")
            .withArguments(
                TextArgument("location").replaceSuggestions(
                    ArgumentSuggestions.strings { _ ->
                        plugin.locationManager
                            .getAllLocations()
                            .map { "\"${it.name}\"" }
                            .toTypedArray()
                    },
                ),
            ).withArguments(TextArgument("context"))
            .withOptionalArguments(IntegerArgument("npc_count"))
            .withOptionalArguments(BooleanArgument("debug").setOptional(false))
            .executes(
                CommandExecutor { sender, args ->
                    val location = (args.get("location") as String).replace("\"", "")
                    val context = args.get("context") as String
                    val npcCount = args.getOptional("npc_count").orElse(5) as Int
                    val debug = args.getOptional("debug").orElse(false) as Boolean

                    // Validate location exists
                    val storyLocation = plugin.locationManager.getLocation(location)
                    if (storyLocation == null) {
                        sender.sendError("Location '$location' not found. Please create the location first.")
                        return@CommandExecutor
                    }

                    // Validate NPC count
                    if (npcCount < 1 || npcCount > 20) {
                        sender.sendError("NPC count must be between 1 and 20.")
                        return@CommandExecutor
                    }

                    sender.sendInfo("🏗️ Starting multi-agent NPC population for location: <yellow>$location</yellow>")
                    sender.sendInfo("📝 Context: <italic>$context</italic>")
                    sender.sendInfo("👥 Target NPC count: <yellow>$npcCount</yellow>")
                    if (debug) {
                        sender.sendInfo("🐛 Debug mode: <green>enabled</green>")
                    }

                    // Execute the multi-agent population system
                    executeLocationNPCPopulation(sender, location, context, npcCount, debug)
                },
            ).register()

        fun talkAsNPC(
            player: Player,
            npcUniqueId: UUID,
            message: String,
        ) {
            // Check if NPC exists
            var npc: StoryNPC? = CitizensAPI.getNPCRegistry().getByUniqueId(npcUniqueId)?.let { CitizensStoryNPC(it) }

            // Only check MythicMobs if the plugin is available
            val isMythicMob =
                try {
                    if (Bukkit.getPluginManager().getPlugin("MythicMobs") != null) {
                        MythicBukkit
                            .inst()
                            .mobManager.mobRegistry
                            .get(npcUniqueId) != null
                    } else {
                        false
                    }
                } catch (e: NoClassDefFoundError) {
                    // MythicMobs classes not available
                    false
                }

            if (npc == null && !isMythicMob) {
                player.sendError("NPC not found.")
                throw CommandAPI.failWithString("NPC not found.")
            }

            if (isMythicMob) {
                // If it's a MythicMob, we need to get the NPC from the MythicMobs API
                val entity =
                    MythicBukkit
                        .inst()
                        .mobManager.mobRegistry
                        .get(npcUniqueId)
                        ?.entity
                        ?.bukkitEntity
                npc = plugin.mythicMobConversation.getOrCreateNPCAdapter(entity as Entity)
            }

            // At this point npc is guaranteed non-null (either Citizens or MythicMob)
            val resolvedNpc = npc!!
            val npcName =
                if (isMythicMob) {
                    // If it's a MythicMob, use its internal name
                    MythicBukkit
                        .inst()
                        .mobManager.mobRegistry
                        .get(npcUniqueId)
                        ?.name ?: "Unknown"
                } else {
                    resolvedNpc.name
                }
            val chatRadius = plugin.config.chatRadius
            val isImpersonated =
                if (!isMythicMob) {
                    plugin.disguiseManager.isNPCBeingImpersonated(resolvedNpc)
                } else {
                    false
                }
            val impersonator =
                if (!isMythicMob) {
                    plugin.disguiseManager.getDisguisedPlayer(resolvedNpc)
                } else {
                    null
                }
            val conversation =
                plugin.conversationManager.getConversation(npcName) ?: run {
                    // create new conversation with nearby NPCs and players
                    var nearbyNPCs =
                        NPCUtils.getNearbyNPCs(resolvedNpc, chatRadius)
                    var players = NPCUtils.getNearbyPlayers(resolvedNpc, chatRadius)

                    if (isImpersonated && impersonator != null) {
                        nearbyNPCs = NPCUtils.getNearbyNPCs(impersonator, chatRadius)
                        players = NPCUtils.getNearbyPlayers(impersonator, chatRadius)
                    }

                    // remove players that have their chat disabled
                    players = players.filterNot { plugin.playerManager.isPlayerDisabled(it) }

                    // Add the NPC to the list of nearby NPCs
                    nearbyNPCs = nearbyNPCs + listOf(resolvedNpc)

                    // Check if any nearby NPCs are already in a conversation
                    val existingConversation =
                        nearbyNPCs.firstNotNullOfOrNull { plugin.conversationManager.getConversation(it.name) }

                    // Check if any nearby players are already in a conversation
                    val playerConversation =
                        players
                            .flatMap { player ->
                                plugin.conversationManager
                                    .getAllActiveConversations()
                                    .filter { conv -> conv.players?.contains(player.uniqueId) == true }
                            }.firstOrNull()

                    // Use existing conversation if available
                    if (existingConversation != null) {
                        // Add this NPC to the existing conversation if not already included
                        if (existingConversation.npcs?.contains(resolvedNpc) != true) {
                            existingConversation.addNPC(resolvedNpc)
                        }

                        // Add any players not already in the conversation
                        players.forEach { p ->
                            if (existingConversation.players?.contains(p.uniqueId) != true) {
                                existingConversation.addPlayer(p)
                            }
                        }

                        // Any disguised players should also be added to the conversation

                        plugin.conversationManager.handleHolograms(existingConversation, resolvedNpc.name)
                        return@run existingConversation
                    } else if (playerConversation != null) {
                        // Add this NPC to the player's existing conversation
                        if (!playerConversation.npcs.contains(resolvedNpc)) {
                            playerConversation.addNPC(resolvedNpc)
                        }

                        plugin.conversationManager.handleHolograms(playerConversation, resolvedNpc.name)
                        return@run playerConversation
                    }

                    if (!(players.isNotEmpty() || nearbyNPCs.size > 1)) {
                        player.sendError("No players or NPCs nearby to start a conversation.")
                        return
                    }

                    val newConversationFuture = plugin.conversationManager.startConversation(nearbyNPCs)

                    newConversationFuture.thenAccept { newConv ->
                        plugin.conversationManager.handleHolograms(newConv, resolvedNpc.name)

                        for (p in players) {
                            newConv.addPlayer(p)
                        }
                    }

                    newConversationFuture.join()
                }

            // Show holograms for the NPCs
            plugin.conversationManager.handleHolograms(conversation, resolvedNpc.name)
            val shouldStream = plugin.config.streamMessages
            val npcContext =
                plugin.npcContextGenerator.getOrCreateContextForNPC(resolvedNpc) ?: run {
                    player.sendError("NPC context not found. Please create the NPC first.")
                    return
                }

            // Get only the messages from the conversation for context
            val recentMessages =
                conversation.history
                    .map { it.content }

            // Prepare response context with limited messages
            var responseContext =
                listOf(
                    "====CURRENT CONVERSATION====\n" +
                        recentMessages.joinToString("\n") +
                        "\n=========================\n" +
                        "This is an active conversation and you are talking to multiple characters: ${
                            conversation.players?.joinToString(
                                ", ",
                            ) { Bukkit.getPlayer(it)?.characterName ?: "" }
                        }. " +
                        conversation.npcNames.joinToString("\n") +
                        "\n===APPEARANCES===\n" +
                        conversation.npcs.joinToString("\n") { npc ->
                            val npcContext = plugin.npcContextGenerator.getOrCreateContextForNPC(npc)
                            "${npc.name}: ${npcContext?.appearance ?: "No appearance information available."}"
                        } +
                        // We treat players as NPCs for this purpose
                        conversation.players?.joinToString("\n") { playerId ->
                            val player = Bukkit.getPlayer(playerId)
                            if (player == null) {
                                // skip this player
                                return@joinToString ""
                            }
                            val playerName = player.name
                            val nickname = Bukkit.getPlayer(playerName)?.characterName ?: playerName
                            val playerContext =
                                plugin.npcContextGenerator.getOrCreateContextForNPC(
                                    PlayerCharacter.from(player),
                                )
                            "$nickname: ${playerContext?.appearance ?: "No appearance information available."}"
                        } +
                        "\n=========================",
                )

            // Add relationship context with clear section header
            val relationships = plugin.relationshipManager.getAllRelationships(resolvedNpc.name)
            if (relationships.isNotEmpty()) {
                val relationshipContext =
                    plugin.relationshipManager.buildRelationshipContext(
                        resolvedNpc.name,
                        relationships,
                        conversation,
                    )
                if (relationshipContext.isNotEmpty()) {
                    responseContext = responseContext + "===RELATIONSHIPS===\n$relationshipContext"
                }
            }

            // Use PromptService to get the talk as NPC prompt, then generate via intelligence
            val talkAsNpcPrompt = plugin.promptService.getTalkAsNpcPrompt(npcName, message)
            conversation.addSystemMessage(talkAsNpcPrompt)

            // Reset auto mode timer to prevent double responses
            plugin.conversationManager.resetAutoTimer(conversation)

            plugin.intelligence.generateNPCResponse(resolvedNpc, conversation).thenApply { response ->
                conversation.addNPCMessage(resolvedNpc, response)
                plugin.conversationManager.speakAsNPC(resolvedNpc, response, addToHistory = false)
            }
        }

        // g command
        CommandAPICommand("g")
            .withPermission("storymaker.chat.toggle")
            .withArguments(
                GreedyStringArgument("message"),
            ).executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val message = args.get("message") as String
                    var currentNPC = plugin.playerManager.getCurrentNPC(player.uniqueId)

                    if (currentNPC == null) {
                        player.sendError("Please select an NPC first.")
                        return@PlayerCommandExecutor
                    }

                    talkAsNPC(player, currentNPC, message)
                },
            ).register()

        // h command
        CommandAPICommand("h")
            .withPermission("storymaker.chat.toggle")
            .withArguments(
                GreedyStringArgument("message"),
            ).executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val message = args.get("message") as String
                    val imitatedNPC = plugin.disguiseManager.getImitatedNPC(player)

                    if (imitatedNPC == null) {
                        player.sendError("You are not imitating any NPC.")
                        return@PlayerCommandExecutor
                    }

                    talkAsNPC(player, imitatedNPC.uniqueId, message)
                },
            ).register()

        // npcply
        CommandAPICommand("npcply")
            .withPermission("storymaker.npc.talk")
            .withArguments(
                TextArgument("npc_name").replaceSuggestions(
                    ArgumentSuggestions.strings { _ ->
                        CitizensAPI
                            .getNPCRegistry()
                            .map { "\"${it.name}\"" }
                            .distinct()
                            .toTypedArray()
                    },
                ),
            ).withArguments(PlayerArgument("player"))
            .withArguments(GreedyStringArgument("message"))
            .executesPlayer(
                PlayerCommandExecutor { player: Player, args: CommandArguments ->
                    val npcName = (args["npc_name"] as String).replace("\"", "")
                    val target = args["player"] as Player
                    val message = args["message"] as String
                    val npc =
                        resolveNPC(player, npcName) { resolvedNpc ->
                            talkAsNPC(player, resolvedNpc.uniqueId, message)
                        } ?: return@PlayerCommandExecutor
                    talkAsNPC(player, npc.uniqueId, message)
                },
            ).register()

        // setcurnpc
        CommandAPICommand("setcurnpc")
            .withPermission("storymaker.chat.toggle")
            .withOptionalArguments(
                TextArgument("npc"),
            ).withOptionalArguments(
                IntegerArgument("npc_id"),
            ).executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val npc = args.getOptional("npc").orElse(null) as? String
                    // integer
                    val npcId = args.getOptional("npc_id").orElse(null) as? Int

                    // Check if NPC is in front of us first.
                    val player = player as Player
                    val target = player.getTargetEntity(15) // Get entity player is looking at within 15 blocks
                    if (target != null && CitizensAPI.getNPCRegistry().isNPC(target)) {
                        val npc: StoryNPC = CitizensStoryNPC(CitizensAPI.getNPCRegistry().getNPC(target))
                        plugin.playerManager.setCurrentNPC(player.uniqueId, npc.uniqueId)
                        player.sendSuccess("Current NPC set to ${npc.name}")
                        return@PlayerCommandExecutor
                    }

                    // If no target, check if the player provided an NPC name
                    if (npc == null) {
                        player.sendError("Please provide an NPC name.")
                        return@PlayerCommandExecutor
                    }

                    // If npcId is provided, check if it exists
                    if (npcId != null) {
                        val npc: StoryNPC? = CitizensAPI.getNPCRegistry().getById(npcId)?.let { CitizensStoryNPC(it) }
                        if (npc == null) {
                            player.sendError("NPC with ID $npcId not found.")
                            return@PlayerCommandExecutor
                        }
                        plugin.playerManager.setCurrentNPC(player.uniqueId, npc.uniqueId)
                        player.sendSuccess("Current NPC set to ${npc.name}")
                        return@PlayerCommandExecutor
                    }

                    // There might be multiple NPCs with the same name, if so, check player radius. If not, ask player to select one.
                    for (citizensNpc in CitizensAPI.getNPCRegistry()) {
                        val npc: StoryNPC = CitizensStoryNPC(citizensNpc)
                        if (npc.name.equals(args["npc"])) {
                            if (!npc.isSpawned) {
                                continue
                            }
                            val npcLocation = npc.location!!
                            val playerLocation = player.location
                            if (playerLocation.distance(npcLocation) <= 15) {
                                plugin.playerManager.setCurrentNPC(player.uniqueId, npc.uniqueId)
                                player.sendSuccess("Current NPC set to $npc")
                                return@PlayerCommandExecutor
                            } else {
                                // print all possible npcs with the same name their ids next to it (make them clickable)
                                val npcList =
                                    CitizensAPI
                                        .getNPCRegistry()
                                        .filter {
                                            it.name.equals(
                                                args["npc"],
                                            )
                                        }.map { CitizensStoryNPC(it) }
                                for (npc in npcList) {
                                    val clickableNpc =
                                        CommandComponentUtils.createButton(
                                            plugin.miniMessage,
                                            "Select ${npc.name} (${npc.id})",
                                            "green",
                                            "run_command",
                                            "/setcurnpc ${npc.name} ${npc.id}",
                                            "Set current NPC to ${npc.name} (${npc.id})",
                                        )

                                    player.sendMessage(clickableNpc)
                                }
                            }
                        }
                    }
                },
            ).register()

        // npcselect - internal command for NPC disambiguation
        CommandAPICommand("npcselect")
            .withPermission("storymaker.npc.talk")
            .withArguments(IntegerArgument("npc_id"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val npcId = args["npc_id"] as Int
                    val citizenNpc = CitizensAPI.getNPCRegistry().getById(npcId)
                    if (citizenNpc == null) {
                        player.sendError("NPC with ID $npcId not found.")
                        return@PlayerCommandExecutor
                    }
                    val callback = pendingNPCResolve.remove(player.uniqueId)
                    if (callback == null) {
                        player.sendError("No pending NPC selection. Please re-run your command.")
                        return@PlayerCommandExecutor
                    }
                    callback(CitizensStoryNPC(citizenNpc))
                },
            ).register()

        registerSafeStopCommand()
    }

    fun registerSafeStopCommand() {
        // Create a command to safely stop the plugin
        CommandAPICommand("safestop")
            .withPermission("story.admin")
            .withFullDescription("Safely stops the Story plugin, ensuring all conversations are summarized")
            .executes(
                CommandExecutor { sender, _ ->
                    sender.sendMessage("§6Starting safe shutdown process... Please wait.")

                    // Run async to avoid blocking the main thread
                    Bukkit.getScheduler().runTaskAsynchronously(
                        plugin,
                        Runnable {
                            plugin.safeStop().thenRun {
                                sender.sendMessage("§2Story plugin has been safely shut down.")
                                // Schedule server shutdown on the main thread
                                Bukkit.getScheduler().runTask(
                                    plugin,
                                    Runnable {
                                        sender.sendMessage("§6Stopping the server...")
                                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop")
                                    },
                                )
                            }
                        },
                    )

                    1
                },
            ).register()
    }

    /**
     * Generates an AI response for an NPC using the message as a prompt, then
     * has the NPC walk to the target and deliver the generated response.
     */
    private fun generateAndWalkToNPC(
        npc: StoryNPC,
        target: StoryNPC,
        prompt: String,
    ) {
        // Get or create conversation for context
        val conversation = plugin.conversationManager.getConversation(npc)
        if (conversation != null) {
            val talkAsNpcPrompt = plugin.promptService.getTalkAsNpcPrompt(npc.name, prompt)
            conversation.addSystemMessage(talkAsNpcPrompt)
            plugin.intelligence.generateNPCResponse(npc, conversation).thenAccept { response ->
                Bukkit.getScheduler().runTask(
                    plugin,
                    Runnable {
                        plugin.npcManager.walkToNPC(npc, target, response)
                    },
                )
            }
        } else {
            // No conversation — fall back to direct generation
            val talkAsNpcPrompt = plugin.promptService.getTalkAsNpcPrompt(npc.name, prompt)
            plugin.npcResponseService.generateNPCResponse(npc, listOf(talkAsNpcPrompt), false).thenAccept { response ->
                Bukkit.getScheduler().runTask(
                    plugin,
                    Runnable {
                        plugin.npcManager.walkToNPC(npc, target, response)
                    },
                )
            }
        }
    }

    /**
     * Resolves a Citizens NPC by name. If multiple NPCs share the same name,
     * picks the closest spawned one within 15 blocks, or prompts the player to select one
     * via /npcselect.
     *
     * @param onResolved callback to execute when the player selects an NPC from the disambiguation list.
     *                   If null, falls back to /setcurnpc.
     * Returns null if no NPC is found or if selection is needed.
     */
    private fun resolveNPC(
        player: Player,
        npcName: String,
        onResolved: ((StoryNPC) -> Unit)? = null,
    ): StoryNPC? {
        val matches = CitizensAPI.getNPCRegistry().filter { it.name == npcName }
        if (matches.isEmpty()) {
            player.sendError("NPC '$npcName' not found.")
            return null
        }
        if (matches.size == 1) {
            return CitizensStoryNPC(matches.first())
        }
        // Multiple NPCs with the same name — find the closest spawned one
        val spawned = matches.filter { it.isSpawned }
        if (spawned.size == 1) {
            return CitizensStoryNPC(spawned.first())
        }
        val closest =
            spawned
                .filter { it.storedLocation != null }
                .minByOrNull { it.storedLocation.distance(player.location) }
        if (closest != null && closest.storedLocation.distance(player.location) <= 15) {
            return CitizensStoryNPC(closest)
        }
        // Store callback and prompt player to select via /npcselect
        if (onResolved != null) {
            pendingNPCResolve[player.uniqueId] = onResolved
        }
        player.sendError("Multiple NPCs named '$npcName' found. Please select one:")
        for (npc in matches) {
            val command = if (onResolved != null) "/npcselect ${npc.id}" else "/setcurnpc ${npc.name} ${npc.id}"
            val clickableNpc =
                CommandComponentUtils.createButton(
                    plugin.miniMessage,
                    "Select ${npc.name} (${npc.id})",
                    "green",
                    "run_command",
                    command,
                    "Select ${npc.name} (ID: ${npc.id})",
                )
            player.sendMessage(clickableNpc)
        }
        return null
    }

    private fun resolveNPCPair(
        player: Player,
        npcName: String,
        targetName: String,
        action: (StoryNPC, StoryNPC) -> Unit,
    ) {
        val npc =
            resolveNPC(player, npcName) { resolvedNpc ->
                val target =
                    resolveNPC(player, targetName) { resolvedTarget ->
                        action(resolvedNpc, resolvedTarget)
                    } ?: return@resolveNPC
                action(resolvedNpc, target)
            } ?: return
        val target =
            resolveNPC(player, targetName) { resolvedTarget ->
                action(npc, resolvedTarget)
            } ?: return
        action(npc, target)
    }

    /**
     * Extracts a JSON object from a string that might contain additional text.
     *
     * @param input String that contains JSON somewhere within it
     * @return String containing only the JSON object
     */
    private fun extractJsonFromString(input: String): String {
        // First try to find JSON array (for NPC plans)
        val arrayStartIndex = input.indexOf("[")
        val arrayEndIndex = input.lastIndexOf("]")

        if (arrayStartIndex >= 0 && arrayEndIndex > arrayStartIndex) {
            return input.substring(arrayStartIndex, arrayEndIndex + 1)
        }

        // Fallback to JSON object extraction
        val objectStartIndex = input.indexOf("{")
        val objectEndIndex = input.lastIndexOf("}")

        if (objectStartIndex >= 0 && objectEndIndex > objectStartIndex) {
            return input.substring(objectStartIndex, objectEndIndex + 1)
        }

        // If no JSON found, return the original string
        return input
    }

    // Helper data class for Gson parsing
    data class NPCInfo(
        val context: String? = null,
        val appearance: String? = null,
    )

    /**
     * Executes the multi-agent NPC population system for a location
     */
    private fun executeLocationNPCPopulation(
        sender: org.bukkit.command.CommandSender,
        location: String,
        context: String,
        npcCount: Int,
        debug: Boolean,
    ) {
        // Step 1: Location Analysis
        sender.sendInfo("🔍 Step 1: Analyzing location '$location'...")

        val storyLocation = plugin.locationManager.getLocation(location)!!

        plugin.taskManager.createTask(
            description =
                "📊 Location Analysis Complete\n" +
                    "Location: $location\n" +
                    "Description: ${storyLocation.description}\n" +
                    "Parent: ${storyLocation.parentLocationName ?: "None"}\n" +
                    "User Context: $context\n\n" +
                    "Proceed with NPC planning phase?",
            permission = "story.task.respond",
            onAccept =
                Runnable {
                    executeNPCPlanning(sender, location, context, npcCount, debug)
                },
            onRefuse =
                Runnable {
                    sender.sendError("❌ Location analysis cancelled by user.")
                },
        )
    }

    /**
     * Step 2: AI-driven NPC planning and role generation
     */
    private fun executeNPCPlanning(
        sender: org.bukkit.command.CommandSender,
        location: String,
        context: String,
        npcCount: Int,
        debug: Boolean,
    ) {
        sender.sendInfo("🧠 Step 2: Planning NPC population...")

        val storyLocation = plugin.locationManager.getLocation(location)!!

        // Build planning prompt
        val messages = mutableListOf<ConversationMessage>()

        // Add location context
        messages.add(
            ConversationMessage(
                "system",
                storyLocation.getContextForPrompt(plugin.locationManager),
            ),
        )

        // Add general world context
        messages.add(
            ConversationMessage(
                "system",
                plugin.npcContextGenerator.getGeneralContexts().joinToString("\n"),
            ),
        )

        // Add relevant lore
        val loreContexts = plugin.lorebookManager.findLoresByKeywords("$location $context")
        if (loreContexts.isNotEmpty()) {
            messages.add(
                ConversationMessage(
                    "system",
                    "Relevant lore:\n" +
                        loreContexts.joinToString("\n\n") { "- ${it.loreName}: ${it.context}" },
                ),
            )
        }

        // Use PromptService to get the NPC population planning prompt
        val planningPrompt = plugin.promptService.getNpcPopulationPlanningPrompt(location, context, npcCount)

        // Planning instruction
        messages.add(ConversationMessage("system", planningPrompt))

        if (debug) {
            sender.sendInfo("🐛 Sending planning request to AI...")
        }

        plugin
            .getAIResponse(messages, lowCost = false)
            .thenAccept { response ->
                Bukkit.getScheduler().runTask(
                    plugin,
                    Runnable {
                        if (response.isNullOrEmpty()) {
                            sender.sendError("❌ Failed to generate NPC plan")
                            return@Runnable
                        }

                        try {
                            val npcPlans = parseNPCPlans(response)
                            if (npcPlans.isEmpty()) {
                                sender.sendError("❌ No valid NPC plans generated")
                                return@Runnable
                            }

                            sender.sendSuccess("✅ Generated plans for ${npcPlans.size} NPCs")

                            // Show preview of planned NPCs
                            val preview =
                                npcPlans.take(3).joinToString("\n") { plan ->
                                    "• ${plan.name} (${plan.role}): ${plan.background.take(80)}..."
                                }

                            plugin.taskManager.createTask(
                                description =
                                    "📋 NPC Planning Complete\n" +
                                        "Generated ${npcPlans.size} NPC plans:\n\n$preview\n" +
                                        (if (npcPlans.size > 3) "\n...and ${npcPlans.size - 3} more\n" else "") +
                                        "\nProceed with NPC generation?",
                                permission = "story.task.respond",
                                onAccept =
                                    Runnable {
                                        executeNPCGeneration(sender, location, npcPlans, debug)
                                    },
                                onRefuse =
                                    Runnable {
                                        sender.sendError("❌ NPC planning cancelled by user.")
                                    },
                            )
                        } catch (e: Exception) {
                            sender.sendError("❌ Failed to parse NPC plans: ${e.message}")
                            if (debug) {
                                plugin.logger.warning("NPC plan parsing error: ${e.message}")
                                plugin.logger.warning("Raw response: $response")
                            }
                        }
                    },
                )
            }.exceptionally { e ->
                Bukkit.getScheduler().runTask(
                    plugin,
                    Runnable {
                        sender.sendError("❌ AI planning failed: ${e.message}")
                    },
                )
                null
            }
    }

    /**
     * Step 3: Generate individual NPCs with full context and appearance
     */
    private fun executeNPCGeneration(
        sender: org.bukkit.command.CommandSender,
        location: String,
        npcPlans: List<NPCPlan>,
        debug: Boolean,
    ) {
        sender.sendInfo("⚡ Step 3: Generating ${npcPlans.size} NPCs...")

        val storyLocation = plugin.locationManager.getLocation(location)!!
        val generatedNPCs = mutableListOf<String>()
        var completedCount = 0

        // Generate NPCs concurrently
        val futures =
            npcPlans.map { plan ->
                generateSingleNPC(plan, storyLocation, debug).thenApply { success ->
                    if (success) {
                        synchronized(generatedNPCs) {
                            generatedNPCs.add(plan.name)
                            completedCount++
                            sender.sendInfo("✅ Generated NPC $completedCount/${npcPlans.size}: ${plan.name}")
                        }
                    } else {
                        sender.sendError("❌ Failed to generate NPC: ${plan.name}")
                    }
                    success
                }
            }

        CompletableFuture.allOf(*futures.toTypedArray()).thenRun {
            Bukkit.getScheduler().runTask(
                plugin,
                Runnable {
                    val successCount = generatedNPCs.size
                    sender.sendSuccess("🎉 Generated $successCount/${npcPlans.size} NPCs successfully!")

                    if (successCount > 0) {
                        val NPCUtils = NPCUtils
                        // Let's create citizens npcs at the target location.
                        // for npc in generatedNPCs, create a citizen npc if it doesn't already exist
                        for (npcName in generatedNPCs) {
                            if (NPCUtils.getNPCByNameAsync(npcName) != null) {
                                // spawn npc at location
                                val npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, npcName)
                                val spawnLocation = storyLocation.bukkitLocation

                                npc.spawn(spawnLocation)
                            }
                        }

                        plugin.taskManager.createTask(
                            description =
                                "🧠 NPC Generation Complete\n" +
                                    "Successfully generated $successCount NPCs:\n" +
                                    generatedNPCs.joinToString(", ") + "\n\n" +
                                    "Proceed with memory and relationship generation?",
                            permission = "story.task.respond",
                            onAccept =
                                Runnable {
                                    executeMemoryGeneration(sender, generatedNPCs, npcPlans, debug)
                                },
                            onRefuse =
                                Runnable {
                                    sender.sendInfo("✅ NPC generation completed. Skipping memory generation.")
                                },
                        )
                    }
                },
            )
        }
    }

    /**
     * Step 4: Generate core memories and relationships
     */
    private fun executeMemoryGeneration(
        sender: org.bukkit.command.CommandSender,
        generatedNPCs: List<String>,
        npcPlans: List<NPCPlan>,
        debug: Boolean,
    ) {
        sender.sendInfo("🧠 Step 4: Generating memories and relationships...")

        val memoryFutures = mutableListOf<CompletableFuture<Void>>()
        var completedMemories = 0

        for (npcName in generatedNPCs) {
            val npcPlan = npcPlans.find { it.name == npcName } ?: continue
            val storyNpc = plugin.npcDataManager.getNPC(npcName)
            val npcData =
                (
                    if (storyNpc !=
                        null
                    ) {
                        plugin.npcDataManager.getNPCData(storyNpc)
                    } else {
                        plugin.npcDataManager.getNPCData(npcName)
                    }
                )
                    ?: continue
            val character: Character =
                AICharacter.from(storyNpc ?: StubStoryNPC(npcName))

            // Generate core background memories
            val coreMemoryFuture =
                plugin.npcResponseService
                    .generateNPCMemory(
                        character,
                        "experience",
                        "Core background: ${npcPlan.background}. Key relationships: ${npcPlan.relationships}. Current situation: ${npcPlan.situation}",
                    ).thenAccept { memory ->
                        if (memory != null) {
                            synchronized(this) {
                                completedMemories++
                                sender.sendInfo(
                                    "💭 Generated core memory $completedMemories/${generatedNPCs.size}: $npcName",
                                )
                            }
                        }
                    }

            memoryFutures.add(coreMemoryFuture.thenApply { null })

            // Generate recent event memories if there are relationships
            if (npcPlan.relationships.isNotEmpty()) {
                val recentMemoryFuture =
                    plugin.npcResponseService
                        .generateNPCMemory(
                            character,
                            "event",
                            "Recent interactions and developments: ${npcPlan.relationships}. Current goals: ${npcPlan.situation}",
                        ).thenAccept { memory ->
                            if (memory != null && debug) {
                                sender.sendInfo("🔄 Generated recent memory for: $npcName")
                            }
                        }

                memoryFutures.add(recentMemoryFuture.thenApply { null })
            }
        }

        CompletableFuture.allOf(*memoryFutures.toTypedArray()).thenRun {
            Bukkit.getScheduler().runTask(
                plugin,
                Runnable {
                    sender.sendSuccess("🎉 Location NPC population complete!")
                    sender.sendInfo("📊 Summary:")
                    sender.sendInfo("• ${generatedNPCs.size} NPCs generated")
                    sender.sendInfo("• $completedMemories core memories created")
                    sender.sendInfo("• Relationships established between NPCs")
                    sender.sendInfo("• NPCs are ready for interaction")
                },
            )
        }
    }

    /**
     * Generates a single NPC with full context and appearance
     */
    private fun generateSingleNPC(
        plan: NPCPlan,
        location: StoryLocation,
        debug: Boolean,
    ): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()

        // Create the context for this NPC
        val npcContext =
            plugin.npcDataManager.getNPC(plan.name)?.let { plugin.npcContextGenerator.getOrCreateContextForNPC(it) }
                ?: plugin.npcContextGenerator.getOrCreateContextForNPC(StubStoryNPC(plan.name))

        if (npcContext == null) {
            future.complete(false)
            return future
        }

        // Generate detailed context and appearance using AI
        val messages = mutableListOf<ConversationMessage>()

        // Add location context
        messages.add(
            ConversationMessage(
                "system",
                location.getContextForPrompt(plugin.locationManager),
            ),
        )

        // Add general context
        messages.add(
            ConversationMessage(
                "system",
                plugin.npcContextGenerator.getGeneralContexts().joinToString("\n"),
            ),
        )

        // Use PromptService to get the single NPC generation prompt
        val singleNpcPrompt =
            plugin.promptService.getSingleNpcGenerationPrompt(
                plan.name,
                plan.role,
                plan.background,
                plan.personality,
                plan.relationships,
                plan.situation,
            )

        // Generation instruction
        messages.add(ConversationMessage("system", singleNpcPrompt))

        plugin
            .getAIResponse(messages, lowCost = false)
            .thenAccept { response ->
                Bukkit.getScheduler().runTask(
                    plugin,
                    Runnable {
                        try {
                            if (response.isNullOrEmpty()) {
                                future.complete(false)
                                return@Runnable
                            }

                            val jsonContent = extractJsonFromString(response)
                            val gson = com.google.gson.Gson()
                            val npcInfo = gson.fromJson(jsonContent, NPCInfo::class.java)

                            val context = npcInfo.context ?: plan.background
                            val appearance =
                                npcInfo.appearance ?: "A ${plan.role.lowercase()} with an unremarkable appearance."

                            // Create and save NPC data
                            val expandedContext = "${npcContext.context} $context"
                            val npcData =
                                com.canefe.story.npc.data.NPCData(
                                    plan.name,
                                    plan.role,
                                    location,
                                    expandedContext,
                                )

                            npcData.appearance = appearance
                            plugin.storage.saveCharacterData(
                                CharacterDTO
                                    .from(npcData),
                            )

                            if (debug) {
                                plugin.logger.info("Generated NPC: ${plan.name} - ${plan.role}")
                            }

                            future.complete(true)
                        } catch (e: Exception) {
                            if (debug) {
                                plugin.logger.warning("Failed to generate NPC ${plan.name}: ${e.message}")
                            }
                            future.complete(false)
                        }
                    },
                )
            }.exceptionally { e ->
                if (debug) {
                    plugin.logger.warning("AI generation failed for ${plan.name}: ${e.message}")
                }
                future.complete(false)
                null
            }

        return future
    }

    /**
     * Parses AI response into NPC plans
     */
    private fun parseNPCPlans(response: String): List<NPCPlan> =
        try {
            val jsonContent = extractJsonFromString(response)
            if (plugin.config.debugMessages) {
                plugin.logger.info("Extracted JSON content: $jsonContent")
            }

            val gson = com.google.gson.Gson()
            val plans = gson.fromJson(jsonContent, Array<NPCPlan>::class.java)
            plans.toList()
        } catch (e: Exception) {
            plugin.logger.warning("Failed to parse NPC plans: ${e.message}")
            if (plugin.config.debugMessages) {
                plugin.logger.warning("Raw response: $response")
                e.printStackTrace()
            }
            emptyList()
        }

    /**
     * Data class for NPC planning
     */
    data class NPCPlan(
        val name: String,
        val role: String,
        val background: String,
        val personality: String,
        val relationships: String,
        val situation: String,
    )
}
