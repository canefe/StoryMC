package com.canefe.story.api

import com.canefe.story.Story
import com.canefe.story.character.data.CharacterData
import com.canefe.story.conversation.ConversationMessage
import org.bukkit.entity.Player
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
        ): Int {
            val char = getCharacterData(player)
            char.let {
                return it?.getSkillModifier(skillName) ?: 0
            }
        }

        /**
         * Get a Character Data by Player
         *
         * @param player The player to check
         * @return The CharacterData API wrapper if found, null otherwise
         */
        @JvmStatic
        fun getCharacterData(player: Player): CharacterData? {
            val char =
                CharacterData(
                    id = player.uniqueId,
                    name = player.name,
                    role = "default",
                    storyLocation = null,
                    context = "null",
                )
            char.isPlayer = true
            char.setSkillProvider(
                instance.skillManager.createProviderForCharacter(player.uniqueId, true),
            )
            return char
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
