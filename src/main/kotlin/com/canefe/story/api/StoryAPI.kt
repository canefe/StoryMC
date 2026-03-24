package com.canefe.story.api

import com.canefe.story.Story
import com.canefe.story.api.character.AICharacter
import com.canefe.story.api.character.Character
import com.canefe.story.api.character.CharacterSkills
import com.canefe.story.api.character.PlayerCharacter
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.npc.CitizensStoryNPC
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Public API for the Story plugin */
interface StoryAPI {
    companion object {
        private lateinit var instance: Story

        /**
         * Initialize the API with the plugin instance
         * @param plugin The Story plugin instance
         */
        @JvmStatic
        fun initialize(plugin: Story) {
            instance = plugin
        }

        /**
         * Checks if a player is currently in a conversation
         *
         * @param player The player to check
         * @return true if the player is in an active conversation, false otherwise
         */
        @JvmStatic
        fun isInConversation(player: Player): Boolean = instance.conversationManager.isInConversation(player)

        /**
         * Gets the current conversation for a player
         *
         * @param player The player to check
         * @return The conversation API wrapper if player is in one, null otherwise
         */
        @JvmStatic
        fun getCurrentConversation(player: Player): APIConversation? =
            instance.conversationManager.getConversation(player)?.let { conversation ->
                APIConversation(
                    id = conversation.id,
                    // convert UUID to Player object
                    players =
                        conversation.players.mapNotNull { uuid ->
                            instance.server.getPlayer(uuid)
                        },
                    npcNames = conversation.npcNames,
                    active = conversation.active,
                )
            }

        /**
         * Gets all active conversations
         *
         * @return List of all active conversations as API wrappers
         */
        @JvmStatic
        fun getActiveConversations(): List<APIConversation> =
            instance.conversationManager.getAllActiveConversations().map { conversation ->
                APIConversation(
                    id = conversation.id,
                    players =
                        conversation.players.mapNotNull { uuid ->
                            instance.server.getPlayer(uuid)
                        },
                    npcNames = conversation.npcNames,
                    active = conversation.active,
                    history = conversation.history,
                )
            }

        /**
         * Get a Character's skills
         *
         * @param Player The player whose skills to retrieve
         * @return List of skill names or empty list if no skills found
         */
        @JvmStatic
        fun getCharacterSkills(player: Player): List<String> =
            instance.skillManager
                .createProviderForCharacter(player.uniqueId, true)
                .getAllSkills()

        /**
         * Get a Character's skill level
         *
         * @param player The player whose skill level to retrieve
         * @param skillName The name of the skill
         * @return The skill level or 0 if skill not found
         */
        @JvmStatic
        fun getCharacterSkillLevel(
            player: Player,
            skillName: String,
        ): Int = getCharacter(player).skills.getModifier(skillName)

        /**
         * Get a [PlayerCharacter] for a player.
         */
        @JvmStatic
        fun getCharacter(player: Player): PlayerCharacter {
            val skills =
                CharacterSkills(
                    provider = instance.skillManager.createProviderForCharacter(player.uniqueId, true),
                    player = player,
                )
            return PlayerCharacter(player = player, skills = skills)
        }

        /**
         * Get NPC data by name
         *
         * @param npcName The name of the NPC
         * @return The NPC data API wrapper if found, null otherwise
         */
        @JvmStatic
        fun getNPCByName(npcName: String): APINPCData? =
            instance.npcDataManager.getNPCData(npcName)?.let { npcData ->
                APINPCData(
                    name = npcData.name,
                    context = npcData.context,
                    appearance = npcData.appearance,
                )
            }

        /**
         * Ask LLM to generate a response to a context (asynchronous)
         *
         * @param context The context to generate a response to
         * @return CompletableFuture with the response as a string
         */
        @JvmStatic
        fun generateResponseAsync(context: String): CompletableFuture<String> {
            val messages = mutableListOf<ConversationMessage>()
            // Use ContextExtractor to extract context
            val contextExtractor = instance.contextExtractor
            val extractedContext = contextExtractor.extractContext(context)
            // Use PromptService to get the response generation prompt
            val responsePrompt = extractedContext.generatePromptContext()
            messages.add(ConversationMessage("system", responsePrompt))
            messages.add(ConversationMessage("user", context))

            // Use getAIResponse to generate the response
            return instance.getAIResponse(messages, lowCost = true).thenApply { response ->
                response?.trim() ?: ""
            }
        }
        // --- Audio API ---

        /**
         * Generate speech from text using ElevenLabs and send it to a specific player.
         *
         * @param player The player to send the audio to
         * @param text The text to convert to speech
         * @param voiceId The ElevenLabs voice ID to use
         * @param npcUuid Optional NPC UUID for client-side positioning
         * @return CompletableFuture that completes when audio is sent
         */
        @JvmStatic
        fun sendSpeech(
            player: Player,
            text: String,
            voiceId: String,
            npcUuid: UUID? = null,
        ): CompletableFuture<Void?> {
            val audioManager = instance.voiceManager.audioManager
            return audioManager.generateSpeechOnce(text, voiceId, "api").thenAccept { audioData ->
                if (audioData != null) {
                    audioManager.sendAudioToPlayer(player, audioData, npcUuid)
                }
            }
        }

        /**
         * Generate speech from text and send it to all online players.
         *
         * @param text The text to convert to speech
         * @param voiceId The ElevenLabs voice ID to use
         * @param npcUuid Optional NPC UUID for client-side positioning
         * @return CompletableFuture that completes when audio is sent to all players
         */
        @JvmStatic
        fun broadcastSpeech(
            text: String,
            voiceId: String,
            npcUuid: UUID? = null,
        ): CompletableFuture<Void?> {
            val audioManager = instance.voiceManager.audioManager
            return audioManager.generateSpeechOnce(text, voiceId, "api").thenAccept { audioData ->
                if (audioData != null) {
                    for (player in instance.server.onlinePlayers) {
                        audioManager.sendAudioToPlayer(player, audioData, npcUuid)
                    }
                }
            }
        }

        /**
         * Send pre-generated audio data directly to a specific player.
         * Bypasses ElevenLabs generation — use for custom audio sources.
         *
         * @param player The player to send the audio to
         * @param audioData WAV audio bytes (44.1kHz, 16-bit PCM)
         * @param npcUuid Optional NPC UUID for client-side positioning
         */
        @JvmStatic
        fun sendAudio(
            player: Player,
            audioData: ByteArray,
            npcUuid: UUID? = null,
        ) {
            instance.voiceManager.audioManager.sendAudioToPlayer(player, audioData, npcUuid)
        }

        /**
         * Send pre-generated audio data to all online players.
         *
         * @param audioData WAV audio bytes (44.1kHz, 16-bit PCM)
         * @param npcUuid Optional NPC UUID for client-side positioning
         */
        @JvmStatic
        fun broadcastAudio(
            audioData: ByteArray,
            npcUuid: UUID? = null,
        ) {
            for (player in instance.server.onlinePlayers) {
                instance.voiceManager.audioManager.sendAudioToPlayer(player, audioData, npcUuid)
            }
        }
        // --- Skill Check API ---

        /**
         * Trigger a skill check between two characters.
         * Rolls d20 + skill modifier, fires [SkillCheckEvent], and returns the result.
         * If the actor is in a conversation, the result is injected as context
         * and an in-character line is generated for the actor.
         *
         * @param actor The character attempting the action
         * @param target The character being targeted
         * @param skill The skill being checked (e.g. "intimidation", "persuasion")
         * @param action A description of the action (e.g. "haggle the price down")
         * @param dc The difficulty class (1-30)
         * @return The result, or null if the event was cancelled by a listener
         */
        @JvmStatic
        fun triggerSkillCheck(
            actor: com.canefe.story.api.character.Character,
            target: com.canefe.story.api.character.Character,
            skill: String,
            action: String,
            dc: Int,
        ): com.canefe.story.conversation.skillcheck.SkillCheckResult? =
            instance.skillCheckService.triggerSkillCheck(actor, target, skill, action, dc)

        // --- Character API ---

        /**
         * Get an [AICharacter] from a Citizens NPC.
         *
         * @param npc The Citizens NPC instance
         * @return An AICharacter wrapping the NPC, or null if no Story data exists for it
         */
        @JvmStatic
        fun getCharacterByNPC(npc: net.citizensnpcs.api.npc.NPC): AICharacter? {
            val storyNpc = CitizensStoryNPC(npc)
            val npcData = instance.npcDataManager.getNPCData(storyNpc) ?: return null
            val skills = CharacterSkills(provider = instance.skillManager.createProviderForNPC(npc.name))
            return AICharacter(
                npc = storyNpc,
                name = npcData.name,
                role = npcData.role,
                appearance = npcData.appearance,
                context = npcData.context,
                skills = skills,
            )
        }

        /**
         * Get an [AICharacter] from a Bukkit Entity (Citizens NPC or MythicMob).
         *
         * @param entity The Bukkit entity
         * @return An AICharacter wrapping the entity, or null if not a Story NPC
         */
        @JvmStatic
        fun getCharacterByEntity(entity: Entity): AICharacter? {
            // Try Citizens first
            try {
                val citizensNpc =
                    net.citizensnpcs.api.CitizensAPI
                        .getNPCRegistry()
                        .getNPC(entity)
                if (citizensNpc != null) return getCharacterByNPC(citizensNpc)
            } catch (_: Throwable) {
            }

            // Try MythicMobs
            try {
                val storyNpc = instance.mythicMobConversation.getOrCreateNPCAdapter(entity)
                if (storyNpc != null) {
                    val npcData = instance.npcDataManager.getNPCData(storyNpc)
                    val skills = CharacterSkills(provider = instance.skillManager.createProviderForNPC(storyNpc.name))
                    return AICharacter(
                        npc = storyNpc,
                        name = storyNpc.name,
                        role = npcData?.role ?: "NPC",
                        appearance = npcData?.appearance ?: "",
                        context = npcData?.context ?: "",
                        skills = skills,
                    )
                }
            } catch (_: Throwable) {
            }

            return null
        }

        // --- Speech API ---

        /**
         * Make a character speak a message.
         *
         * For players: runs the same flow as the player chat system — generates
         * an in-character line via LLM (if [llm] is true), broadcasts it, and
         * feeds it into the conversation.
         *
         * For NPCs: runs the same flow as /g — generates an NPC response via LLM
         * (if [llm] is true), broadcasts it, and adds it to the conversation.
         *
         * If [llm] is false, the raw message is broadcast and added to the
         * conversation without LLM rewriting.
         *
         * @param character The character who should speak
         * @param message The message (or draft if llm=true)
         * @param llm Whether to rewrite the message through the LLM (default: true)
         * @return CompletableFuture with the final spoken message
         */
        @JvmStatic
        fun speakCharacter(
            character: Character,
            message: String,
            llm: Boolean = true,
        ): CompletableFuture<String> =
            when (character) {
                is PlayerCharacter -> speakAsPlayer(character, message, llm)
                is AICharacter -> speakAsNPC(character, message, llm)
                else -> CompletableFuture.completedFuture(message)
            }

        private fun speakAsPlayer(
            character: PlayerCharacter,
            message: String,
            llm: Boolean,
        ): CompletableFuture<String> {
            val player = character.player
            val conversation = instance.conversationManager.getConversation(player)

            if (!llm) {
                // Raw broadcast, no LLM
                instance.npcMessageService.broadcastPlayerMessage(message, player)
                conversation?.addPlayerMessage(player, message)
                return CompletableFuture.completedFuture(message)
            }

            // LLM rewrite using the talk_as_npc prompt (same as player chat flow)
            val playerName =
                com.canefe.story.util.EssentialsUtils
                    .getNickname(player.name)
            val talkAsPrompt = instance.promptService.getTalkAsNpcPrompt(playerName, message)

            val responseContext = mutableListOf<String>()
            if (conversation != null) {
                val recentMessages = conversation.history.map { it.content }
                responseContext.add(
                    "====CURRENT CONVERSATION====\n${recentMessages.joinToString("\n")}\n=========================",
                )
            }
            responseContext.add(talkAsPrompt)

            return instance.npcResponseService
                .generateNPCResponse(
                    null,
                    responseContext,
                    false,
                    player,
                    rich = false,
                    isConversation = conversation != null,
                ).thenApply { response ->
                    val finalMessage = response?.trim() ?: message
                    instance.npcMessageService.broadcastPlayerMessage(finalMessage, player)
                    conversation?.addPlayerMessage(player, finalMessage)
                    finalMessage
                }
        }

        private fun speakAsNPC(
            character: AICharacter,
            message: String,
            llm: Boolean,
        ): CompletableFuture<String> {
            val npc = character.npc
            val conversation = instance.conversationManager.getConversation(npc)
            val npcContext = instance.npcContextGenerator.getOrCreateContextForNPC(npc.name)

            if (!llm) {
                // Raw broadcast, no LLM
                instance.npcMessageService.broadcastNPCMessage(message, npc, npcContext = npcContext)
                conversation?.addNPCMessage(npc, message)
                return CompletableFuture.completedFuture(message)
            }

            // LLM rewrite using the /g flow
            val responseContext = mutableListOf<String>()
            if (conversation != null) {
                val recentMessages = conversation.history.map { it.content }
                responseContext.add(
                    "====CURRENT CONVERSATION====\n${recentMessages.joinToString("\n")}\n=========================",
                )
            }
            val talkAsPrompt = instance.promptService.getTalkAsNpcPrompt(npc.name, message)
            responseContext.add(talkAsPrompt)

            return instance.npcResponseService
                .generateNPCResponse(
                    npc,
                    responseContext,
                    false,
                    rich = false,
                    isConversation = conversation != null,
                ).thenApply { response ->
                    val finalMessage = response?.trim() ?: message
                    instance.npcMessageService.broadcastNPCMessage(finalMessage, npc, npcContext = npcContext)
                    conversation?.addNPCMessage(npc, finalMessage)
                    finalMessage
                }
        }
    }
}

/** API representation of a conversation that's exposed to other plugins */
class APIConversation(
    val id: Int,
    val players: List<Player>,
    val npcNames: List<String>,
    val active: Boolean,
    val history: List<ConversationMessage> = emptyList(),
)

/** API representation of NPC data that's exposed to other plugins */
class APINPCData(
    val name: String,
    val context: String,
    val appearance: String,
)
