package com.canefe.story.bridge

import com.canefe.story.Story
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
        val npc = findNPC(intent.npcName)
        if (npc == null) {
            plugin.logger.warning("Speak intent: NPC '${intent.npcName}' not found")
            return
        }

        plugin.conversationManager.speakAsNPC(npc, intent.message)
    }

    fun executeMoveIntent(
        plugin: Story,
        intent: NPCMoveIntent,
    ) {
        val npc = findNPC(intent.npcName)
        if (npc == null) {
            plugin.logger.warning("Move intent: NPC '${intent.npcName}' not found")
            return
        }

        val world =
            intent.world?.let { Bukkit.getWorld(it) }
                ?: npc.entity?.world
                ?: return

        val target = Location(world, intent.x, intent.y, intent.z)
        npc.navigateTo(target)
    }

    fun executeEmoteIntent(
        plugin: Story,
        intent: NPCEmoteIntent,
    ) {
        val npc = findNPC(intent.npcName)
        if (npc == null) {
            plugin.logger.warning("Emote intent: NPC '${intent.npcName}' not found")
            return
        }

        // Broadcast as action text — the client renders it as scattered floating text
        val action = if (intent.action.startsWith("*")) intent.action else "*${intent.action}*"
        plugin.npcMessageService.broadcastNPCMessage(
            message = action,
            npc = npc,
            streaming = true,
        )
    }

    private fun findNPC(name: String): com.canefe.story.api.StoryNPC? {
        val citizenNpc =
            CitizensAPI
                .getNPCRegistry()
                .firstOrNull { it.name == name }
                ?: return null
        return CitizensStoryNPC(citizenNpc)
    }

    /**
     * Finds an NPC and returns the real entity (accounting for disguised players).
     */
    private fun findNPCEntity(
        plugin: Story,
        name: String,
    ): Pair<com.canefe.story.api.StoryNPC, org.bukkit.entity.Entity>? {
        val npc = findNPC(name) ?: return null
        val entity = plugin.conversationManager.getRealEntityForNPC(npc) ?: npc.entity ?: return null
        return npc to entity
    }
}
