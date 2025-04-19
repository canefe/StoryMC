package com.canefe.story.npc.service

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.mcmonkey.sentinel.SentinelTrait
import java.util.concurrent.CompletableFuture

class NPCActionIntentRecognizer(private val plugin: Story) {

    /**
     * Analyzes NPC response for action intents
     * @return ActionIntent object with detected intents and confidence scores
     */
    fun recognizeActionIntents(npc: NPC, response: String, targetPlayer: Player?): CompletableFuture<ActionIntent> {
        val messages = mutableListOf<ConversationMessage>()

        // Stricter system instruction
        messages.add(ConversationMessage(
            "system",
            """
        Analyze the NPC's message for action intents. You MUST respond with ONLY a single JSON object and nothing else.
        Format:
        {
          "follow": 0.0,
          "attack": 0.0,
          "stopFollowing": 0.0,
          "target": "target_name_if_specified_in_the_message"
        }

        Examples of follow intent: "I'll follow you", "Let's go", "Lead the way", "I'm coming with you", "After you", "I'll accompany you", "Right behind you", "*follows you*", "*walks alongside*", "Show me the way", "You lead", "I'm with you"
        Examples of attack intent: "I'll kill them", "Let's attack", "We must fight", "They must die", "Prepare to die!", "*draws weapon*", "*charges forward*", "Time to end this", "You'll pay for that", "I'll make you regret that", "Attack!", "Die!", "*readies for combat*"  
        Examples of stop following intent: "I'll wait here", "This is far enough", "I should stay", "Go on without me", "I'll remain here", "*stops walking*", "I can't go any further", "I'll stay behind", "This is where I stop", "You continue alone", "I must part ways here", "*stands still*", "*stops*"

        Return ONLY valid JSON with no additional text before or after.
        """
        ))

        messages.add(ConversationMessage("user", response))

        // Create a CompletableFuture to return
        val resultFuture = CompletableFuture<ActionIntent>()

        // Use the CompletableFuture API properly
        plugin.getAIResponse(messages).thenAccept { aiResponse ->
            try {
                if (aiResponse == null) {
                    resultFuture.complete(ActionIntent())
                    return@thenAccept
                }

                // Extract JSON using regex pattern
                val jsonPattern = "\\{[\\s\\S]*?\\}".toRegex()
                val jsonMatch = jsonPattern.find(aiResponse)?.value

                if (jsonMatch != null) {
                    try {
                        val jsonResponse = plugin.gson.fromJson(jsonMatch, ActionIntent::class.java)

                        // If we have high confidence and a target player, execute the action
                        if (targetPlayer != null && jsonResponse.getHighestConfidenceAction() > 0.7) {
                            Bukkit.getScheduler().runTask(plugin, Runnable {
                                executeAction(npc, jsonResponse, targetPlayer)
                            })
                        }

                        resultFuture.complete(jsonResponse)
                    } catch (e: Exception) {
                        plugin.logger.warning("Error parsing JSON: ${e.message}")
                        resultFuture.complete(ActionIntent())
                    }
                } else {
                    resultFuture.complete(ActionIntent())
                }
            } catch (e: Exception) {
                plugin.logger.warning("Error recognizing action intent: ${e.message}")
                resultFuture.complete(ActionIntent())
            }
        }.exceptionally { exception ->
            plugin.logger.warning("Exception in AI response handling: ${exception.message}")
            resultFuture.complete(ActionIntent())
            null
        }

        return resultFuture
    }

    /**
     * Execute the detected action if confidence is high enough
     */
    private fun executeAction(npc: NPC, intent: ActionIntent, targetPlayer: Player) {
        when {
            intent.follow > 0.7 -> {
                // Execute follow behavior
                // Add SentinelTrait or Get
                val sentinelTrait = npc.getOrAddTrait(SentinelTrait::class.java)
                sentinelTrait.guarding = targetPlayer.uniqueId
                plugin.logger.info("NPC ${npc.name} is now following ${targetPlayer.name}")
            }
            intent.attack > 0.7 -> {
                // Execute attack behavior (if you have combat mechanics)
                plugin.logger.info("NPC ${npc.name} is attacking ${intent.target ?: targetPlayer.name}")
                // Implement attack logic
            }
            intent.stopFollowing > 0.7 -> {
                // Stop following
                val sentinelTrait = npc.getOrAddTrait(SentinelTrait::class.java)
                sentinelTrait.guarding = null
                plugin.logger.info("NPC ${npc.name} stopped following")
            }
        }
    }

    /**
     * Data class for action intents
     */
    data class ActionIntent(
        val follow: Double = 0.0,
        val attack: Double = 0.0,
        val stopFollowing: Double = 0.0,
        val target: String? = null
    ) {
        fun getHighestConfidenceAction(): Double {
            return maxOf(follow, attack, stopFollowing)
        }
    }
}