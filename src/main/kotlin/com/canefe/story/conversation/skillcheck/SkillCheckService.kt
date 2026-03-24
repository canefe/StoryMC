package com.canefe.story.conversation.skillcheck

import com.canefe.story.Story
import com.canefe.story.api.character.AICharacter
import com.canefe.story.api.character.Character
import com.canefe.story.api.character.PlayerCharacter
import com.canefe.story.api.event.SkillCheckEvent
import com.canefe.story.conversation.Conversation
import com.canefe.story.conversation.ConversationMessage
import org.bukkit.Bukkit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadLocalRandom

/**
 * Monitors conversations and triggers skill checks when appropriate.
 *
 * An LLM agent analyzes the last few messages to detect actions that warrant
 * a skill check (intimidation, persuasion, deception, athletics, etc.).
 * When detected, it fires a [SkillCheckEvent] and injects the result into
 * the conversation context so the NPC can respond accordingly.
 */
class SkillCheckService(
    private val plugin: Story,
) {
    /**
     * Analyze recent conversation messages to determine if a skill check should occur.
     * Called after a player message is added to the conversation, before NPC response generation.
     *
     * @return A [SkillCheckResult] if a check was triggered, or null if no check needed.
     */
    fun evaluateForSkillCheck(conversation: Conversation): CompletableFuture<SkillCheckResult?> {
        val history = conversation.history
        if (history.size < 2) return CompletableFuture.completedFuture(null)

        // Take the last few messages for context
        val recentMessages = history.takeLast(6)

        val prompt = buildAnalysisPrompt(recentMessages, conversation)

        return plugin
            .getAIResponse(
                listOf(ConversationMessage("system", prompt)),
                lowCost = true,
            ).thenApply { response ->
                parseSkillCheckResponse(response, conversation)
            }
    }

    private fun buildAnalysisPrompt(
        recentMessages: List<ConversationMessage>,
        conversation: Conversation,
    ): String {
        val messagesText =
            recentMessages
                .filter { it.role != "system" }
                .joinToString("\n") { it.content }

        val npcNames = conversation.npcNames.joinToString(", ")
        val playerNames =
            conversation.players
                .mapNotNull { uuid ->
                    plugin.server.getPlayer(uuid)?.name
                }.joinToString(", ")

        return plugin.promptService.getSkillCheckEvaluationPrompt(npcNames, playerNames, messagesText)
    }

    private fun parseSkillCheckResponse(
        response: String?,
        conversation: Conversation,
    ): SkillCheckResult? {
        if (response.isNullOrBlank()) return null

        try {
            val cleaned =
                response
                    .trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

            val json =
                com.google.gson.JsonParser
                    .parseString(cleaned)
                    .asJsonObject

            if (!json.get("check").asBoolean) return null

            val actorName = json.get("actor").asString
            val targetName = json.get("target").asString
            val skill = json.get("skill").asString
            val action = json.get("action").asString
            val dc = json.get("dc").asInt.coerceIn(5, 25)

            // Resolve characters
            val actor = resolveCharacter(actorName, conversation) ?: return null
            val target = resolveCharacter(targetName, conversation) ?: return null

            // Roll: modifier + d20
            val modifier = actor.skills.getModifier(skill)
            val d20 = ThreadLocalRandom.current().nextInt(1, 21)
            val roll = modifier + d20
            val passed = roll >= dc

            // Fire the event
            val event =
                SkillCheckEvent(
                    character = actor,
                    target = target,
                    skill = skill,
                    action = action,
                    dc = dc,
                    roll = roll,
                    passed = passed,
                    conversationId = conversation.id,
                )
            Bukkit.getPluginManager().callEvent(event)

            if (event.isCancelled) return null

            return SkillCheckResult(
                actor = event.character,
                target = event.target,
                skill = event.skill,
                action = event.action,
                dc = event.dc,
                roll = event.roll,
                passed = event.passed,
                d20 = d20,
                modifier = modifier,
            )
        } catch (e: Exception) {
            if (plugin.config.debugMessages) {
                plugin.logger.warning("Failed to parse skill check response: ${e.message}")
            }
            return null
        }
    }

    private fun resolveCharacter(
        name: String,
        conversation: Conversation,
    ): Character? {
        // Check NPCs
        val npc = conversation.npcs.find { it.name.equals(name, ignoreCase = true) }
        if (npc != null) {
            return AICharacter.from(npc)
        }

        // Check players
        val player =
            conversation.players
                .mapNotNull { uuid ->
                    plugin.server.getPlayer(uuid)
                }.find {
                    it.name.equals(name, ignoreCase = true) ||
                        com.canefe.story.util.EssentialsUtils
                            .getNickname(it.name)
                            .equals(name, ignoreCase = true)
                }

        if (player != null) {
            return PlayerCharacter.from(player)
        }

        return null
    }

    /**
     * Manually trigger a skill check between two characters.
     * Rolls d20 + modifier, fires [SkillCheckEvent], and returns the result.
     * If the actor is in a conversation, the result is injected as context and actor speech is generated.
     *
     * @return The result, or null if the event was cancelled.
     */
    fun triggerSkillCheck(
        actor: Character,
        target: Character,
        skill: String,
        action: String,
        dc: Int,
    ): SkillCheckResult? {
        val modifier = actor.skills.getModifier(skill)
        val d20 = ThreadLocalRandom.current().nextInt(1, 21)
        val roll = modifier + d20
        val passed = roll >= dc

        // Find conversation if actor is in one
        val conversationId =
            when (actor) {
                is PlayerCharacter -> plugin.conversationManager.getConversation(actor.player)?.id ?: -1
                is AICharacter -> plugin.conversationManager.getConversation(actor.npc)?.id ?: -1
                else -> -1
            }

        val event =
            SkillCheckEvent(
                character = actor,
                target = target,
                skill = skill,
                action = action,
                dc = dc.coerceIn(1, 30),
                roll = roll,
                passed = passed,
                conversationId = conversationId,
            )
        Bukkit.getPluginManager().callEvent(event)

        if (event.isCancelled) return null

        val result =
            SkillCheckResult(
                actor = event.character,
                target = event.target,
                skill = event.skill,
                action = event.action,
                dc = event.dc,
                roll = event.roll,
                passed = event.passed,
                d20 = d20,
                modifier = modifier,
            )

        // If in a conversation, inject context and generate speech
        if (conversationId != -1) {
            val conversation = plugin.conversationManager.getConversationById(conversationId)
            if (conversation != null) {
                generateActorSpeech(result, conversation).thenAccept {
                    val context = formatAsConversationContext(result)
                    conversation.addSystemMessage(context)
                }
            }
        }

        return result
    }

    /**
     * Format the skill check result as context to inject into the conversation
     * so the NPC knows the outcome and can respond accordingly.
     */
    fun formatAsConversationContext(result: SkillCheckResult): String =
        buildString {
            append("[SKILL CHECK: ${result.actor.name} attempted to ${result.action} against ${result.target.name}. ")
            append("Skill: ${result.skill}. ")
            append("Roll: ${result.d20} + ${result.modifier} (modifier) = ${result.roll} vs DC ${result.dc}. ")
            if (result.passed) {
                append("RESULT: SUCCESS. The action succeeds — respond accordingly.]")
            } else {
                append("RESULT: FAILURE. The action fails — respond accordingly, the target is not affected.]")
            }
        }

    /**
     * Generate an in-character line for the actor performing the skill check,
     * then broadcast it as a player message and add it to the conversation.
     */
    fun generateActorSpeech(
        result: SkillCheckResult,
        conversation: Conversation,
    ): CompletableFuture<String?> {
        val resultText = if (result.passed) "SUCCEEDED" else "FAILED"

        val prompt =
            plugin.promptService.getSkillCheckSpeechPrompt(
                characterName = result.actor.name,
                skill = result.skill,
                action = result.action,
                result = resultText,
                roll = result.roll,
                dc = result.dc,
            )

        val messages = mutableListOf<ConversationMessage>()

        // Add character context
        val characterContext = plugin.npcContextGenerator.getOrCreateContextForNPC(result.actor)
        if (characterContext != null) {
            messages.add(
                ConversationMessage(
                    "system",
                    "You are roleplaying as ${result.actor.name}.\n" +
                        "===CHARACTER===\n${characterContext.context}\n" +
                        "===APPEARANCE===\n${characterContext.appearance}",
                ),
            )
        }

        // Add conversation history for context
        val recentHistory =
            conversation.history
                .filter { it.role != "system" }
                .takeLast(8)
        if (recentHistory.isNotEmpty()) {
            messages.add(
                ConversationMessage(
                    "system",
                    "===RECENT CONVERSATION===\n${recentHistory.joinToString("\n") { it.content }}",
                ),
            )
        }

        messages.add(ConversationMessage("system", prompt))

        return plugin
            .getAIResponse(
                messages,
                lowCost = true,
            ).thenApplyAsync { response ->
                if (response.isNullOrBlank()) return@thenApplyAsync null

                val speech = response.trim()

                // If actor is a player, broadcast and add to conversation
                val playerChar = result.actor as? PlayerCharacter
                if (playerChar != null) {
                    val player = playerChar.player
                    Bukkit.getScheduler().runTask(
                        plugin,
                        Runnable {
                            plugin.npcMessageService.broadcastPlayerMessage(speech, player)
                        },
                    )
                    conversation.addPlayerMessage(player, speech)
                }

                speech
            }
    }
}

data class SkillCheckResult(
    val actor: Character,
    val target: Character,
    val skill: String,
    val action: String,
    val dc: Int,
    val roll: Int,
    val passed: Boolean,
    val d20: Int,
    val modifier: Int,
)
