package com.canefe.story.bridge

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.npc.CitizensStoryNPC
import net.citizensnpcs.api.CitizensAPI
import org.bukkit.Bukkit
import org.bukkit.Location

/**
 * Executes inbound intents from the sim/LLM service by translating them
 * into Minecraft actions. All methods run on the main server thread.
 */
object IntentExecutor {
    fun executeSpeakIntent(
        plugin: Story,
        intent: NPCSpeakIntent,
    ) {
        val npc = resolveNPC(plugin, intent.characterId)
        if (npc == null) {
            plugin.logger.warning("Speak intent: character '${intent.characterId}' not found")
            return
        }

        plugin.conversationManager.speakAsNPC(npc, intent.message)
    }

    fun executeMoveIntent(
        plugin: Story,
        intent: NPCMoveIntent,
    ) {
        val npc = resolveNPC(plugin, intent.characterId)
        if (npc == null) {
            plugin.logger.warning("Move intent: character '${intent.characterId}' not found")
            return
        }

        val world =
            intent.world?.let { Bukkit.getWorld(it) }
                ?: npc.entity?.world
                ?: return

        val target = Location(world, intent.x, intent.y, intent.z)
        npc.navigateTo(target)
    }

    fun executeQuestAssignIntent(
        plugin: Story,
        intent: QuestAssignIntent,
    ) {
        val player = plugin.server.getPlayer(intent.playerName)
        if (player == null) {
            plugin.logger.warning("Quest assign intent: player '${intent.playerName}' not online")
            return
        }
        plugin.questManager.assignQuestToPlayer(player, intent.questId)
    }

    fun executeQuestUpdateIntent(
        plugin: Story,
        intent: QuestUpdateIntent,
    ) {
        val player = plugin.server.getPlayer(intent.playerName)
        if (player == null) {
            plugin.logger.warning("Quest update intent: player '${intent.playerName}' not online")
            return
        }
        val type =
            intent.objectiveType?.let {
                try {
                    com.canefe.story.quest.ObjectiveType
                        .valueOf(it)
                } catch (_: Exception) {
                    null
                }
            }
        plugin.questManager.updateObjectiveProgress(player, intent.questId, type, intent.target, intent.progress)
    }

    fun executeQuestCompleteIntent(
        plugin: Story,
        intent: QuestCompleteIntent,
    ) {
        val player = plugin.server.getPlayer(intent.playerName)
        if (player == null) {
            plugin.logger.warning("Quest complete intent: player '${intent.playerName}' not online")
            return
        }
        plugin.questManager.completeQuest(player, intent.questId)
    }

    fun executeCharacterUpdateIntent(
        plugin: Story,
        intent: CharacterUpdateIntent,
    ) {
        plugin.characterRegistry.reload()
        plugin.logger.info("Character registry reloaded for update to ${intent.characterId}")
    }

    fun executeEmoteIntent(
        plugin: Story,
        intent: NPCEmoteIntent,
    ) {
        val npc = resolveNPC(plugin, intent.characterId)
        if (npc == null) {
            plugin.logger.warning("Emote intent: character '${intent.characterId}' not found")
            return
        }

        val action = if (intent.action.startsWith("*")) intent.action else "*${intent.action}*"
        plugin.npcMessageService.broadcastNPCMessage(
            message = action,
            npc = npc,
            streaming = true,
        )
    }

    /**
     * Resolves a character ID to a StoryNPC. Tries the character registry first,
     * then falls back to name-based Citizens lookup for backwards compatibility.
     */
    private fun resolveNPC(
        plugin: Story,
        characterId: String,
    ): StoryNPC? {
        // Try character registry first
        val record =
            try {
                plugin.characterRegistry.getById(characterId)
            } catch (_: UninitializedPropertyAccessException) {
                null
            }

        if (record != null) {
            val config =
                try {
                    plugin.characterRegistry.getMinecraftConfig(characterId)
                } catch (_: UninitializedPropertyAccessException) {
                    null
                }

            // Try Citizens UUID from frontend config
            config?.citizensUuid?.let { uuid ->
                val citizenNpc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid)
                if (citizenNpc != null) return CitizensStoryNPC(citizenNpc)
            }

            // Try Citizens NPC ID from frontend config
            config?.citizensNpcId?.let { id ->
                val citizenNpc = CitizensAPI.getNPCRegistry().getById(id)
                if (citizenNpc != null) return CitizensStoryNPC(citizenNpc)
            }

            // Fall back to name match
            val citizenNpc = CitizensAPI.getNPCRegistry().firstOrNull { it.name == record.name }
            if (citizenNpc != null) return CitizensStoryNPC(citizenNpc)
        }

        // Legacy fallback: treat characterId as a name
        val citizenNpc = CitizensAPI.getNPCRegistry().firstOrNull { it.name == characterId }
        if (citizenNpc != null) return CitizensStoryNPC(citizenNpc)

        return null
    }
}
