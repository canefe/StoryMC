package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side state for "puppet mode" — a DM-only feature where one player
 * has a stack/group of NPCs they're commanding via right-click in StoryClient.
 *
 * Group state per player is the source of truth. Entering puppet mode = adding
 * the first NPC; exiting = clearing the group.
 *
 * Pushes group state to the controlling player via [PuppetGroupBroadcaster].
 */
class PuppetManager(
    private val plugin: Story,
) {
    private val groups = ConcurrentHashMap<UUID, MutableList<UUID>>() // player UUID -> ordered list of NPC stable UUIDs

    fun groupOf(player: Player): List<UUID> = groups[player.uniqueId]?.toList() ?: emptyList()

    fun isInPuppetMode(player: Player): Boolean = !groupOf(player).isEmpty()

    fun add(player: Player, npc: StoryNPC): Boolean {
        val list = groups.getOrPut(player.uniqueId) { mutableListOf() }
        if (list.contains(npc.uniqueId)) return false
        list.add(npc.uniqueId)
        plugin.puppetGroupBroadcaster.push(player)
        return true
    }

    fun remove(player: Player, npc: StoryNPC): Boolean {
        val list = groups[player.uniqueId] ?: return false
        val removed = list.remove(npc.uniqueId)
        if (list.isEmpty()) groups.remove(player.uniqueId)
        plugin.puppetGroupBroadcaster.push(player)
        return removed
    }

    fun toggle(player: Player, npc: StoryNPC): Boolean {
        // Returns true if NPC is now in the group, false if removed.
        return if (groupOf(player).contains(npc.uniqueId)) {
            remove(player, npc)
            false
        } else {
            add(player, npc)
            true
        }
    }

    fun clear(player: Player) {
        groups.remove(player.uniqueId)
        plugin.puppetGroupBroadcaster.push(player)
    }

    /** Move every NPC in the player's group to the given location. */
    fun moveAll(player: Player, location: Location) {
        val ids = groupOf(player)
        for (id in ids) {
            val npc = plugin.npcRegistry.get(id) ?: continue
            // Cancel any followchar loop targeting this NPC so it doesn't override.
            plugin.npcTaskTracker.cancelFollow(id)
            npc.navigateTo(location)
        }
    }

    /** Resolve every NPC in the player's group to live StoryNPC entries. */
    fun resolveGroup(player: Player): List<StoryNPC> =
        groupOf(player).mapNotNull { plugin.npcRegistry.get(it) }
}
