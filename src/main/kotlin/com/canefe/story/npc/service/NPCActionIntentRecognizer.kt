package com.canefe.story.npc.service

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.quest.*
import com.canefe.story.util.EssentialsUtils
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
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
	fun recognizeActionIntents(
		npc: NPC,
		lastTwoResponses: List<String>,
		targetPlayer: Player?,
	): CompletableFuture<ActionIntent> {
		val messages = mutableListOf<ConversationMessage>()

		val systemPrompt = """
        Analyze ${npc.name}'s message for action intents. You MUST respond with ONLY a single JSON object and nothing else.
        Format:
        {
          "follow": 0.0,
          "attack": 0.0,
          "stopFollowing": 0.0,
		  "stopAttacking": 0.0,
		  "target": "target_name_if_specified_in_the_message"
        }

        Examples of follow intent: "I'll follow you", "Let's go", "Lead the way", "I'm coming with you", "After you", "I'll accompany you", "Right behind you", "*follows you*", "*walks alongside*", "Show me the way", "You lead", "I'm with you"
        Examples of attack intent: "I'll kill them", "Let's attack", "We must fight", "They must die", "Prepare to die!", "*draws weapon*", "*charges forward*", "Time to end this", "You'll pay for that", "I'll make you regret that", "Attack!", "Die!", "*readies for combat*"
        Examples of stop following intent: "I'll wait here", "This is far enough", "I should stay", "Go on without me", "I'll remain here", "*stops walking*", "I can't go any further", "I'll stay behind", "This is where I stop", "You continue alone", "I must part ways here", "*stands still*", "*stops*"
		Examples of stop attacking intent: "I won't fight anymore", "Let's not do this", "I can't attack anymore", "Stop fighting", "This is enough", "*puts away weapon*", "*ceases combat*", "No more violence", "I give up", "Let's end this peacefully", "I'm done fighting", "*surrenders*"

		If the player expresses discomfort, resistance, or directly questions being followed (e.g., "Why are you following me?", "Stop following me", "Leave me alone"), and the response does not explicitly override or ignore this objection with authority or a strong reason (like being a guard, arresting the player, or needing to protect them), then the NPC’s intent MUST be interpreted as stopFollowing, even if their language is soft, ambiguous, or indirectly tries to justify it.

		If a character responds to being questioned for following with anything other than clear resistance or justification, treat it as them backing off.

		Prioritize player discomfort as a hard stop unless overridden by narrative context.

		CRITICAL: Ensure that all JSON objects are properly closed with matching braces. Do not cut off any part of the JSON structure.

		Return ONLY valid, complete JSON with no additional text before or after.
        """

		// Stricter system instruction
		messages.add(
			ConversationMessage(
				"system",
				systemPrompt,
			),
		)

		// Add each response to the messages list
		for (response in lastTwoResponses) {
			messages.add(ConversationMessage("user", response))
		}

		// Create a CompletableFuture to return
		val resultFuture = CompletableFuture<ActionIntent>()

		// Use the CompletableFuture API properly
		// In NPCActionIntentRecognizer.kt - update the JSON extraction and parsing code
		plugin
			.getAIResponse(messages, lowCost = true)
			.thenAccept { aiResponse ->
				try {
					if (aiResponse == null) {
						resultFuture.complete(ActionIntent())
						return@thenAccept
					}

					// Better JSON extraction - first try to extract the complete JSON structure
					val cleanedJson =
						aiResponse
							.trim()
							.replace(Regex("```json|```"), "") // Remove any code block markers
							.replace(Regex(",\\s*([}\\]])"), "$1") // Remove trailing commas
							.trim()

					try {
						// Use direct parsing first
						val jsonResponse = plugin.gson.fromJson(cleanedJson, ActionIntent::class.java)

						// If we have high confidence and a target player, execute the action
						if (targetPlayer != null && jsonResponse.getHighestConfidenceAction() > 0.7) {
							Bukkit.getScheduler().runTask(
								plugin,
								Runnable {
									executeAction(npc, targetPlayer, jsonResponse)
								},
							)
						}

						resultFuture.complete(jsonResponse)
					} catch (e: Exception) {
						// If direct parsing fails, try using regex as fallback
						plugin.logger.warning("Direct JSON parsing failed: ${e.message}, trying regex extraction")

						// Extract JSON using a better regex pattern that ensures the entire JSON structure
						val jsonPattern = "\\{[\\s\\S]*\\}".toRegex() // This matches from first { to last }
						val jsonMatch = jsonPattern.find(cleanedJson)?.value

						if (jsonMatch != null) {
							try {
								val lenientGson =
									plugin.gson
										.newBuilder()
										.setLenient()
										.create()

								val jsonResponse = lenientGson.fromJson(jsonMatch, ActionIntent::class.java)

								if (targetPlayer != null && jsonResponse.getHighestConfidenceAction() > 0.7) {
									Bukkit.getScheduler().runTask(
										plugin,
										Runnable {
											executeAction(npc, targetPlayer, jsonResponse)
										},
									)
								}
								resultFuture.complete(jsonResponse)
							} catch (e2: Exception) {
								plugin.logger.warning("Error parsing JSON with regex extraction: ${e2.message}")
								plugin.logger.warning("Failed JSON: $jsonMatch")
								resultFuture.complete(ActionIntent())
							}
						} else {
							plugin.logger.warning("Could not extract JSON using regex. Raw response: $cleanedJson")
							resultFuture.complete(ActionIntent())
						}
					}
				} catch (e: Exception) {
					plugin.logger.warning("Error recognizing action intent: ${e.message}")
					resultFuture.complete(ActionIntent())
				}
			}

		return resultFuture
	}

	fun recognizeQuestGivingIntent(
		npc: NPC,
		lastTwoResponses: List<String>,
		targetPlayer: Player,
	): CompletableFuture<QuestGenerationResult> {
		// First check if player already has a quest from this NPC
		val playerQuests = plugin.questManager.getPlayerQuests(targetPlayer.uniqueId)
		val npcId = npc.id

		// Check for existing IN_PROGRESS quests from this NPC
		val hasExistingQuestFromNPC =
			playerQuests.any { (questId, playerQuest) ->
				playerQuest.status == QuestStatus.IN_PROGRESS &&
					questId.startsWith("npc_${npcId}_quest_")
			}

		val playerName = EssentialsUtils.getNickname(targetPlayer.name)

		// If player already has a quest from this NPC, don't generate a new one
		if (hasExistingQuestFromNPC) {
			plugin.logger.info("Player $playerName already has an active quest from NPC ${npc.name} (ID: ${npc.id})")
			return CompletableFuture.completedFuture(QuestGenerationResult(false, null))
		}

		val messages = mutableListOf<ConversationMessage>()

		val validCollectibles = plugin.questManager.getValidCollectibles().joinToString(", ")
		val validKillTargets = plugin.questManager.getValidKillTargets().joinToString(", ")
		val validLocations = plugin.questManager.getValidLocations().joinToString(", ")
		val validTalkTargets = plugin.questManager.getValidTalkTargets(npc).joinToString(", ")

		val systemPrompt = """
        Analyze ${npc.name}'s message to determine if they are giving a quest or task to $playerName.

        CRITICAL RULES:
        1. The quest MUST be something the ${npc.name} wants the $playerName to do FOR the ${npc.name}.
        2. The objectives MUST be actions for the $playerName to complete, not actions the ${npc.name} plans to do.
        3. DO NOT include objectives about "finding out what they need" or "talking to self" or similar self-references.
        4. Quest objectives should ALWAYS be directed at the $playerName's actions, never the ${npc.name}'s internal thoughts.
        5. If the message does not contain a clear quest assignment FROM the ${npc.name} TO the player, return isQuestGiving: false. AND leave questDetails empty.
		Quest title must be two words max.
        You MUST respond with ONLY a single JSON object and nothing else:
		DO NOT use '_' for target name spaces. Allowed: Location Name - Not: Location_Name
        {
            "isQuestGiving": true/false,
            "questDetails": {
                "title": "Brief Title (TWO WORDS MAX)",
                "description": "First person perspective from $playerName of brief quest description that explains the backstory and motivation (2 sentences MAX)",
                "questType": "SIDE",
                "objectives": [
                    {"description": "Action for $playerName to complete (3-4 WORDS MAX)", "type": "EXPLORE", "target": "Location", "required": 1},
                    {"description": "Another $playerName action (3-4 WORDS MAX)", "type": "KILL", "target": "Enemy", "required": 1}
                ],
				"rewards": [
					{"type": "EXPERIENCE", "amount": 100-1000}
				]
            }
        }

        Valid objective types: KILL, COLLECT, TALK, EXPLORE, CRAFT, USE

        Valid collection targets: $validCollectibles
        Valid kill targets: $validKillTargets
        Valid location targets: $validLocations
        Valid talk targets: $validTalkTargets

        CRITICAL: DO NOT GENERATE UNKNOWN TARGETS. USE ONLY THE VALID TARGETS PROVIDED ABOVE.
        CRITICAL: If no valid quest is being given, set isQuestGiving to false and leave questDetails empty.
        CRITICAL: Only generate a quest if the ${npc.name} is EXPLICITLY asking the ${npc.name} to do something FOR them.
    """

		messages.add(
			ConversationMessage(
				"system",
				systemPrompt,
			),
		)

		// Add each response to the messages list
		for (response in lastTwoResponses) {
			messages.add(ConversationMessage("user", response))
		}

		val resultFuture = CompletableFuture<QuestGenerationResult>()

		plugin.getAIResponse(messages, lowCost = true).thenAccept { aiResponse ->
			try {
				if (aiResponse == null) {
					resultFuture.complete(QuestGenerationResult(false, null))
					return@thenAccept
				}

				val cleanedJson = aiResponse.trim().replace(Regex("```json|```"), "").trim()

				try {
					val jsonResponse = plugin.gson.fromJson(cleanedJson, QuestGenerationResult::class.java)

					// Validate the quest details if it's marked as a quest
					if (jsonResponse.isQuestGiving && jsonResponse.questDetails != null) {
						// Verify that objectives are player-focused and not NPC self-tasks
						val validQuest = validateQuestIsPlayerFocused(jsonResponse.questDetails, targetPlayer.name, npc.name)

						if (validQuest) {
							resultFuture.complete(jsonResponse)

							// Execute quest assignment on main thread if it's valid
							Bukkit.getScheduler().runTask(
								plugin,
								Runnable {
									// first ask for permission to give quest
									plugin.askForPermission(
										"Following quest will be assigned to <gold>${EssentialsUtils.getNickname(
											targetPlayer.name,
										)}</gold>: <yellow>${jsonResponse.questDetails.title}</yellow> by <red>${npc.name}</red>. Do you want to accept it?",
										onAccept = {
											createAndAssignQuest(npc, targetPlayer, jsonResponse.questDetails)
										},
										onRefuse = {
											plugin.logger.info("Quest from NPC ${npc.name} was refused by player ${targetPlayer.name}.")
										},
									)
								},
							)
						} else {
							// If quest validation fails, return no quest
							resultFuture.complete(QuestGenerationResult(false, null))
						}
					} else {
						resultFuture.complete(jsonResponse)
					}
				} catch (e: Exception) {
					plugin.logger.warning("Error parsing quest JSON: ${e.message}")
					resultFuture.complete(QuestGenerationResult(false, null))
				}
			} catch (e: Exception) {
				plugin.logger.warning("Error recognizing quest intent: ${e.message}")
				resultFuture.complete(QuestGenerationResult(false, null))
			}
		}

		return resultFuture
	}

	/**
	 * Validates that a quest is properly player-focused and not a self-task for the NPC
	 */
	private fun validateQuestIsPlayerFocused(questDetails: QuestDetails, playerName: String, npcName: String): Boolean {
		// Check if any objective contains references to NPC's own tasks
		val selfReferenceKeywords =
			listOf(
				"find out what",
				"learn what",
				"discover what",
				"hear what",
				"talk to me",
				"come back to me",
				"interrupt",
				"return to $npcName",
			).map { it.lowercase() }

		val npcNameLower = npcName.lowercase()
		val playerNameLower = playerName.lowercase()

		// Check for objectives that seem like NPC self-tasks
		for (objective in questDetails.objectives) {
			val descLower = objective.description.lowercase()

			// Check for self-reference keywords
			if (selfReferenceKeywords.any { descLower.contains(it) }) {
				return false
			}

			// Check if objective is about talking to the NPC giving the quest
			if (objective.type == "TALK" && objective.target.equals(npcName, ignoreCase = true)) {
				// This is fine only if it's not the first objective
				if (questDetails.objectives.indexOf(objective) == 0) {
					return false
				}
			}

			// Check if description refers to the NPC in third person
			if (descLower.contains("talk to $npcNameLower") ||
				descLower.contains("find $npcNameLower") ||
				descLower.contains("help $npcNameLower")
			) {
				// These are actually good quest objectives for the player
				continue
			}

			// Check if the quest seems to be from the player's perspective
			if (descLower.startsWith("my ") ||
				descLower.startsWith("i need ") ||
				descLower.startsWith("i want ") ||
				descLower.contains("my quest")
			) {
				return false
			}
		}

		return true
	}

	/**
	 * Result class for quest generation
	 */
	data class QuestGenerationResult(val isQuestGiving: Boolean = false, val questDetails: QuestDetails? = null)

	/**
	 * Updates the NPCActionIntentRecognizer to support complex item targets for quest generation
	 */
	private fun executeAction(npc: NPC, player: Player, intent: ActionIntent) {
		when {
			intent.follow > 0.7 -> {
				plugin.askForPermission(
					"Do you want <gold>${npc.name}</gold> to follow <red>${player.name}</red>?",
					onAccept = {
						npc.getOrAddTrait(SentinelTrait::class.java).guarding = player.uniqueId
						npc.getOrAddTrait(SentinelTrait::class.java).guardDistanceMinimum = 3.0
						player.sendInfo("${npc.name} is now following you.")
					},
					onRefuse = {},
				)
			}
			intent.attack > 0.7 -> {
				val target = intent.target?.let { Bukkit.getPlayer(it) }
				if (target != null) {
					plugin.askForPermission(
						"Do you want <gold>${npc.name}</gold> to attack <red>${target.name}</red>?",
						onAccept = {
							npc.getOrAddTrait(SentinelTrait::class.java).addTarget("player:${target.name}")
							player.sendError("${npc.name} is now attacking you.")
						},
						onRefuse = {
						},
					)
				}
			}
			intent.stopFollowing > 0.7 -> {
				plugin.askForPermission(
					"Do you want <gold>${npc.name}</gold> to stop following ${player.name}?",
					onAccept = {
						npc.getOrAddTrait(SentinelTrait::class.java).guarding = null
						player.sendMessage("§7${npc.name} is no longer following you.")
					},
					onRefuse = {},
				)
			}
			intent.stopAttacking > 0.7 -> {
				plugin.askForPermission(
					"Do you want <gold>${npc.name}</gold> to stop attacking ${player.name}?",
					onAccept = {
						npc.getOrAddTrait(SentinelTrait::class.java).removeTarget("player:${player.name}")
						npc.getOrAddTrait(SentinelTrait::class.java).tryUpdateChaseTarget(null)
						player.sendMessage("§7${npc.name} has stopped attacking you.")
					},
					onRefuse = {},
				)
			}
			intent.giveQuest > 0.7 -> {
				// Give quest with enhanced collect objectives
				if (intent.questDetails != null) {
					createAndAssignQuest(npc, player, intent.questDetails)
				}
			}
		}
	}

	/**
	 * Creates a quest from intent details and assigns it to the player
	 */
	private fun createAndAssignQuest(npc: NPC, player: Player, questDetails: QuestDetails) {
		// Create a unique ID for this quest
		val questId = "npc_${npc.id}_quest_${System.currentTimeMillis()}"

		// Convert AI-generated objectives to quest system objectives
		val objectives =
			questDetails.objectives.map { objDetail ->
				QuestObjective(
					description = objDetail.description,
					type =
					try {
						ObjectiveType.valueOf(objDetail.type)
					} catch (e: Exception) {
						ObjectiveType.TALK // Default to TALK if invalid type
					},
					target = objDetail.target,
					required = objDetail.required.coerceAtLeast(1), // Ensure at least 1 required
				)
			}

		// Create the quest object
		val quest =
			Quest(
				id = questId,
				title = questDetails.title,
				description = questDetails.description,
				type =
				try {
					QuestType.valueOf(questDetails.questType)
				} catch (e: Exception) {
					QuestType.SIDE // Default to SIDE if invalid type
				},
				objectives = objectives,
				rewards =
				questDetails.rewards.map { reward ->
					QuestReward(
						type =
						try {
							reward.type
						} catch (e: Exception) {
							RewardType.EXPERIENCE // Default to EXPERIENCE if invalid type
						},
						amount = reward.amount.coerceAtLeast(0), // Ensure non-negative amount
					)
				},
			)

		// Register and assign the quest
		plugin.questManager.registerQuest(quest, npc)
		plugin.questManager.assignQuestToPlayer(player, questId)
	}

	/**
	 * Data class for action intents
	 */
	data class ActionIntent(
		val follow: Double = 0.0,
		val attack: Double = 0.0,
		val stopFollowing: Double = 0.0,
		val stopAttacking: Double = 0.0,
		val giveQuest: Double = 0.0,
		val target: String? = null,
		val questDetails: QuestDetails? = null,
	) {
		fun getHighestConfidenceAction(): Double = maxOf(follow, attack, stopFollowing, giveQuest)
	}

	data class QuestDetails(
		val title: String = "",
		val description: String = "",
		val objectives: List<ObjectiveDetail> = emptyList(),
		val questType: String = "SIDE",
		val rewards: List<QuestReward> = emptyList(),
	)

	data class ObjectiveDetail(
		val description: String = "",
		val type: String = "TALK",
		val target: String = "",
		val required: Int = 1,
	)
}
