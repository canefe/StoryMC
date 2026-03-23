package com.canefe.story.npc.duty

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages NPC barks (short speech lines) with cooldowns
 */
class BarkService(
    private val plugin: Story,
    private val dutyLibrary: DutyLibrary,
) {
    // Track last bark time per NPC per pool
    private val lastBarkTimes = ConcurrentHashMap<String, Long>() // "npcId:poolName" -> timestamp

    /**
     * Try to make an NPC speak from a bark pool, respecting cooldowns
     */
    fun trySpeak(
        npc: StoryNPC,
        poolName: String,
        location: com.canefe.story.location.data.StoryLocation,
    ): Boolean {
        val cooldownKey = "${npc.uniqueId}:$poolName"
        val now = System.currentTimeMillis()

        // Get bark pool from location
        val barkPool = dutyLibrary.getBarkPool(location, poolName)

        if (barkPool == null || barkPool.messages.isEmpty()) {
            return false
        }

        // Check cooldown (default 30 seconds)
        val lastBarkTime = lastBarkTimes[cooldownKey] ?: 0
        val cooldownMs = 30000L // 30 seconds default

        if (now - lastBarkTime < cooldownMs) {
            return false // Still on cooldown
        }

        // Select random message from pool
        val message = barkPool.messages.random()

        // Broadcast the message
        plugin.npcMessageService.broadcastNPCMessage(message, npc, shouldBroadcast = false)

        // Update cooldown
        lastBarkTimes[cooldownKey] = now

        if (plugin.config.debugMessages) {
            plugin.logger.info("${npc.name} barked from pool '$poolName': $message")
        }

        return true
    }

    /**
     * Try to make an NPC speak with custom cooldown
     */
    fun trySpeak(
        npc: StoryNPC,
        poolName: String,
        location: com.canefe.story.location.data.StoryLocation,
        cooldownSeconds: Int,
    ): Boolean {
        val cooldownKey = "${npc.uniqueId}:$poolName"
        val now = System.currentTimeMillis()

        // Get bark pool from location
        val barkPool = dutyLibrary.getBarkPool(location, poolName)

        if (barkPool == null || barkPool.messages.isEmpty()) {
            return false
        }

        // Check custom cooldown
        val lastBarkTime = lastBarkTimes[cooldownKey] ?: 0
        val cooldownMs = cooldownSeconds * 1000L

        if (now - lastBarkTime < cooldownMs) {
            return false // Still on cooldown
        }

        // Select random message from pool
        val message = barkPool.messages.random()

        // Broadcast the message
        plugin.npcMessageService.broadcastNPCMessage(message, npc, shouldBroadcast = false)

        // Update cooldown
        lastBarkTimes[cooldownKey] = now

        if (plugin.config.debugMessages) {
            plugin.logger.info("${npc.name} barked from pool '$poolName': $message")
        }

        return true
    }

    /**
     * Clear bark cooldowns for an NPC
     */
    fun clearCooldowns(npc: StoryNPC) {
        val npcPrefix = "${npc.uniqueId}:"
        lastBarkTimes.keys.removeIf { it.startsWith(npcPrefix) }
    }

    /**
     * Clear all bark cooldowns
     */
    fun clearAllCooldowns() {
        lastBarkTimes.clear()
    }
}
