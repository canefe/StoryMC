package com.canefe.story.npc.util

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import java.awt.Color
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

object NPCUtils {
    private val registry get() = Story.instance.npcRegistry

    fun getNPCByNameAsync(npcName: String): CompletableFuture<StoryNPC?> =
        CompletableFuture.completedFuture(registry.getByName(npcName))

    fun getNPCUUID(npcName: String?): UUID? = npcName?.let { registry.getByName(it)?.uniqueId }

    fun randomColor(npcName: String): String {
        val hash = abs(npcName.hashCode().toDouble()).toInt()
        val hue = (hash % 360) / 360.0f
        val saturation = 0.7f
        val brightness = 0.8f
        val color: Color = Color.getHSBColor(hue, saturation, brightness)
        return java.lang.String.format("#%02X%02X%02X", color.red, color.green, color.blue)
    }

    fun getNearbyPlayers(
        player: Player,
        radius: Double,
        ignoreY: Boolean = false,
    ): List<Player> {
        val radiusSquared = radius * radius
        return nearbyPlayersInLocation(player.location, ignoreY, radiusSquared)
    }

    fun getNearbyNPCs(
        npc: StoryNPC,
        radius: Double,
    ): List<StoryNPC> {
        val npcLocation = npc.location ?: return Collections.emptyList()
        return registry.nearby(npcLocation, radius).filter { it.uniqueId != npc.uniqueId }
    }

    fun getNearbyPlayers(
        npc: StoryNPC,
        radius: Double,
        ignoreY: Boolean = false,
    ): List<Player> {
        if (!npc.isSpawned) return Collections.emptyList()
        val radiusSquared = radius * radius
        val npcLoc = npc.location ?: return Collections.emptyList()
        return nearbyPlayersInLocation(npcLoc, ignoreY, radiusSquared)
    }

    private fun nearbyPlayersInLocation(
        npcLoc: Location,
        ignoreY: Boolean,
        radiusSquared: Double,
    ): List<Player> =
        Bukkit.getOnlinePlayers().filter { player ->
            val loc = player.location
            if (loc.world != npcLoc.world) return@filter false

            if (ignoreY) {
                val dx = loc.x - npcLoc.x
                val dz = loc.z - npcLoc.z
                (dx * dx + dz * dz) <= radiusSquared
            } else {
                loc.distanceSquared(npcLoc) <= radiusSquared
            }
        }

    fun getNearbyPlayers(
        location: Location,
        radius: Double,
        ignoreY: Boolean = false,
    ): List<Player> {
        val radiusSquared = radius * radius
        return nearbyPlayersInLocation(location, ignoreY, radiusSquared)
    }

    fun getNearbyNPCs(
        player: Player,
        radius: Double,
    ): List<StoryNPC> = registry.nearby(player.location, radius)

    fun clearCache() {
        // Registry is the source of truth; no separate cache to clear.
    }
}
