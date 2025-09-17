package com.canefe.story.util

import com.canefe.story.Story
import me.libraryaddict.disguise.DisguiseAPI
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DisguiseManager(
    private val plugin: Story,
) {
    // Map to track which player is disguised as which NPC
    private val disguisedPlayers = ConcurrentHashMap<UUID, UUID>() // Player UUID -> NPC UUID

    /**
     * Check if an entity is a player disguised as an NPC
     */
    fun isDisguisedAsNPC(entity: Entity): Boolean {
        if (entity !is Player) return false
        return DisguiseAPI.isDisguised(entity) &&
            DisguiseAPI.getDisguise(entity) is PlayerDisguise
    }

    /**
     * Register a player as disguised as a specific NPC
     */
    fun registerDisguise(
        player: Player,
        npc: NPC,
    ) {
        disguisedPlayers[player.uniqueId] = npc.uniqueId
    }

    /**
     * Get the NPC that the player is disguised as
     */
    fun getImitatedNPC(player: Player): NPC? {
        val npcId = disguisedPlayers[player.uniqueId] ?: return null
        return CitizensAPI.getNPCRegistry().getByUniqueId(npcId)
    }

    /**
     * Get the player disguised as the specified NPC, if any
     */
    fun getDisguisedPlayer(npc: NPC): Player? {
        for ((playerUUID, npcUUID) in disguisedPlayers) {
            if (npcUUID == npc.uniqueId) {
                return plugin.server.getPlayer(playerUUID)
            }
        }
        return null
    }

    /**
     * Check if an NPC is being impersonated by a player
     */
    fun isNPCBeingImpersonated(npc: NPC): Boolean = disguisedPlayers.containsValue(npc.uniqueId)

    /**
     * Unregister a player's disguise
     */
    fun removeDisguise(player: Player) {
        disguisedPlayers.remove(player.uniqueId)
    }
}
