package com.canefe.story.config

import com.canefe.story.Story
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class PromptService(
    private val plugin: Story,
) {
    private lateinit var promptsConfig: YamlConfiguration

    init {
        loadPrompts()
    }

    private fun loadPrompts() {
        val promptsFile = File(plugin.dataFolder, "prompts.yml")

        // Save default prompts.yml if it doesn't exist
        if (!promptsFile.exists()) {
            plugin.saveResource("prompts.yml", false)
        }

        promptsConfig = YamlConfiguration.loadConfiguration(promptsFile)
    }

    fun reload() {
        loadPrompts()
    }

    /** Gets a prompt template with placeholders */
    fun getPrompt(promptKey: String): String = promptsConfig.getString("$promptKey.system_prompt") ?: ""

    /** Gets a prompt with variables replaced */
    fun getPrompt(
        promptKey: String,
        variables: Map<String, String>,
    ): String {
        var prompt = getPrompt(promptKey)

        variables.forEach { (key, value) -> prompt = prompt.replace("{$key}", value) }

        return prompt
    }

    /** Gets the theme analysis prompt */
    fun getThemeAnalysisPrompt(
        availableThemes: String,
        activeThemes: String,
    ): String {
        val variables =
            mapOf(
                "available_themes" to availableThemes,
                "active_themes" to activeThemes,
            )
        return getPrompt("theme_analysis", variables)
    }

    /** Gets the player analysis prompt */
    fun getPlayerAnalysisPrompt(
        playerName: String,
        location: String,
        questTitle: String,
        team: String,
        inConversation: String,
    ): String {
        val variables =
            mapOf(
                "player_name" to playerName,
                "location" to location,
                "quest_title" to questTitle,
                "team" to team,
                "in_conversation" to inConversation,
            )
        return getPrompt("player_analysis", variables)
    }

    /** Gets the behavioral directive prompt with context */
    fun getBehavioralDirectivePrompt(
        recentMessages: String,
        relationshipContext: String,
        npcName: String,
    ): String {
        val variables =
            mapOf(
                "recent_messages" to recentMessages,
                "relationship_context" to
                    if (relationshipContext.isNotEmpty()) {
                        "Relationships:\n$relationshipContext"
                    } else {
                        ""
                    },
                "npc_name" to npcName,
            )

        return getPrompt("behavioral_directive", variables)
    }

    /** Gets the speaker selection prompt */
    fun getSpeakerSelectionPrompt(availableCharacters: String): String =
        getPrompt("speaker_selection", mapOf("available_characters" to availableCharacters))

    /** Gets the memory significance evaluation prompt */
    fun getMemorySignificancePrompt(): String = getPrompt("memory_significance")

    /** Gets the NPC name generation prompt */
    fun getNpcNameGenerationPrompt(
        location: String,
        role: String,
        context: String,
    ): String {
        val variables = mapOf("location" to location, "role" to role, "context" to context)

        return getPrompt("npc_name_generation", variables)
    }

    /** Gets the NPC greeting prompt */
    fun getNpcGreetingPrompt(
        npcName: String,
        target: String,
    ): String {
        val variables = mapOf("npc_name" to npcName, "target" to target)

        return getPrompt("npc_greeting", variables)
    }

    /** Gets the NPC goodbye prompt */
    fun getNpcGoodbyePrompt(npcName: String): String {
        val variables = mapOf("npc_name" to npcName)

        return getPrompt("npc_goodbye", variables)
    }

    /** Gets the NPC memory generation prompt */
    fun getNpcMemoryGenerationPrompt(npcName: String): String {
        val variables = mapOf("npc_name" to npcName)

        return getPrompt("npc_memory_generation", variables)
    }

    /** Gets the conversation summary prompt */
    fun getConversationSummaryPrompt(npcName: String): String {
        val variables = mapOf("npc_name" to npcName)

        return getPrompt("conversation_summary", variables)
    }

    /** Gets the NPC character generation prompt */
    fun getNpcCharacterGenerationPrompt(
        npcName: String,
        location: String,
    ): String {
        val variables = mapOf("npc_name" to npcName, "location" to location)

        return getPrompt("npc_character_generation", variables)
    }

    /** Gets the NPC population planning prompt */
    fun getNpcPopulationPlanningPrompt(
        location: String,
        context: String,
        npcCount: Int,
    ): String {
        val variables =
            mapOf(
                "location" to location,
                "context" to context,
                "npc_count" to npcCount.toString(),
            )

        return getPrompt("npc_population_planning", variables)
    }

    /** Gets the single NPC generation prompt */
    fun getSingleNpcGenerationPrompt(
        npcName: String,
        role: String,
        background: String,
        personality: String,
        relationships: String,
        situation: String,
    ): String {
        val variables =
            mapOf(
                "npc_name" to npcName,
                "role" to role,
                "background" to background,
                "personality" to personality,
                "relationships" to relationships,
                "situation" to situation,
            )

        return getPrompt("single_npc_generation", variables)
    }

    /** Gets the talk as NPC prompt */
    fun getTalkAsNpcPrompt(
        npcName: String,
        message: String,
    ): String {
        val variables = mapOf("npc_name" to npcName, "message" to message)

        return getPrompt("talk_as_npc", variables)
    }

    /** Gets the NPC reactions prompt */
    fun getNpcReactionsPrompt(): String = getPrompt("npc_reactions")

    /** Gets the quest book generation prompt */
    fun getQuestBookPrompt(contextInformation: String): String {
        val variables = mapOf("context_information" to contextInformation)

        return getPrompt("quest_book_generation", variables)
    }

    /** Gets the NPC action intent recognition prompt */
    fun getNpcActionIntentPrompt(npcName: String): String {
        val variables = mapOf("npc_name" to npcName)

        return getPrompt("npc_action_intent_recognition", variables)
    }

    /** Gets the NPC quest giving intent prompt */
    fun getNpcQuestGivingIntentPrompt(
        npcName: String,
        playerName: String,
        validCollectibles: String,
        validKillTargets: String,
        validLocations: String,
        validTalkTargets: String,
    ): String {
        val variables =
            mapOf(
                "npc_name" to npcName,
                "player_name" to playerName,
                "valid_collectibles" to validCollectibles,
                "valid_kill_targets" to validKillTargets,
                "valid_locations" to validLocations,
                "valid_talk_targets" to validTalkTargets,
            )

        return getPrompt("npc_quest_giving_intent", variables)
    }

    /** Gets the game master response prompt */
    fun getGameMasterResponsePrompt(contextInformation: String): String {
        val variables = mapOf("context_information" to contextInformation)

        return getPrompt("game_master_response", variables)
    }

    /** Gets the quest creation prompt */
    fun getQuestCreationPrompt(
        playerName: String,
        contextInformation: String,
        validCollectibles: String,
        validKillTargets: String,
        validLocations: String,
    ): String {
        val variables =
            mapOf(
                "player_name" to playerName,
                "context_information" to contextInformation,
                "valid_collectibles" to validCollectibles,
                "valid_kill_targets" to validKillTargets,
                "valid_locations" to validLocations,
            )

        return getPrompt("quest_creation", variables)
    }

    /** Gets the story message generation prompt */
    fun getStoryMessageGenerationPrompt(contextInformation: String): String {
        val variables = mapOf("context_information" to contextInformation)

        return getPrompt("story_message_generation", variables)
    }

    /** Story Location context generator prompt */
    fun getLocationContextGenerationPrompt(contextInformation: String): String {
        val variables = mapOf("context_information" to contextInformation)

        return getPrompt("location_context_generation", variables)
    }

    /** Gets the message history summary prompt for mid-conversation summarization */
    fun getMessageHistorySummaryPrompt(): String = getPrompt("message_history_summary")

    /** Gets the session history summary prompt */
    fun getSessionHistorySummaryPrompt(): String = getPrompt("session_history_summary")

    /** Recent events generation prompt */
    fun getRecentEventsGenerationPrompt(contextInformation: String): String {
        val variables = mapOf("context_information" to contextInformation)

        return getPrompt("recent_events_generation", variables)
    }

    /** Gets the NPC skill generation prompt */
    fun getNpcSkillGenerationPrompt(
        npcName: String,
        role: String,
        context: String,
        availableSkills: String,
    ): String {
        val variables =
            mapOf(
                "npc_name" to npcName,
                "role" to role,
                "context" to context,
                "available_skills" to availableSkills,
            )
        return getPrompt("npc_skill_generation", variables)
    }

    /** Gets the skill check speech generation prompt */
    fun getSkillCheckSpeechPrompt(
        characterName: String,
        skill: String,
        action: String,
        result: String,
        roll: Int,
        dc: Int,
    ): String {
        val variables =
            mapOf(
                "character_name" to characterName,
                "skill" to skill,
                "action" to action,
                "result" to result,
                "roll" to roll.toString(),
                "dc" to dc.toString(),
            )
        return getPrompt("skill_check_speech", variables)
    }

    /** Gets the skill check evaluation prompt */
    fun getSkillCheckEvaluationPrompt(
        npcNames: String,
        playerNames: String,
        conversation: String,
    ): String {
        val variables =
            mapOf(
                "npc_names" to npcNames,
                "player_names" to playerNames,
                "conversation" to conversation,
            )
        return getPrompt("skill_check_evaluation", variables)
    }
}
