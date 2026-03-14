package com.canefe.story.npc.util

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.npc.CitizensStoryNPC
import net.citizensnpcs.api.CitizensAPI
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import java.awt.Color
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

class NPCUtils // Private constructor to prevent instantiation
    private constructor(
        private val plugin: Story,
    ) {
        // Cache for NPCs
        private val npcCache: MutableMap<String, StoryNPC> = ConcurrentHashMap()

        // Virtual thread executor for I/O-bound operations
        private val virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()

        private object InstanceHolder {
            // Single instance of the class
            lateinit var instance: NPCUtils
                private set

            fun initialize(plugin: Story) {
                if (!::instance.isInitialized) {
                    instance = NPCUtils(plugin)
                }
            }
        }

        // Asynchronous method to get an NPC by name, with caching
        fun getNPCByNameAsync(npcName: String): CompletableFuture<StoryNPC?> {
            return CompletableFuture.supplyAsync(
                {
                    // Check cache first
                    if (npcCache.containsKey(npcName.lowercase(Locale.getDefault()))) {
                        return@supplyAsync npcCache[npcName.lowercase(Locale.getDefault())]
                    }

                    // Search NPC registry if not in cache
                    for (npc in CitizensAPI.getNPCRegistry()) {
                        if (npc.name.equals(npcName, ignoreCase = true)) {
                            val storyNpc = CitizensStoryNPC(npc)
                            npcCache[npcName.lowercase(Locale.getDefault())] = storyNpc
                            return@supplyAsync storyNpc
                        }
                    }

                    null // NPC not found
                },
                virtualThreadExecutor,
            )
        }

        fun getNPCUUID(npcName: String?): UUID? {
            for (npc in CitizensAPI.getNPCRegistry()) {
                if (npc.name.equals(npcName, ignoreCase = true)) {
                    return npc.uniqueId
                }
            }
            return null
        }

        fun randomColor(npcName: String): String {
            val hash = abs(npcName.hashCode().toDouble()).toInt() // Ensure non-negative value

            // Convert hash into an HSL-based color for better distribution
            val hue = (hash % 360) / 360.0f // Keep within 0-1 range
            val saturation = 0.7f // 70% saturation (not too gray)
            val brightness = 0.8f // 80% brightness (not too dark)

            val color: Color = Color.getHSBColor(hue, saturation, brightness)

            // Convert to hex format
            return java.lang.String.format("#%02X%02X%02X", color.red, color.green, color.blue)
        }

        fun getNearbyPlayers(
            player: Player,
            radius: Double,
            ignoreY: Boolean = false,
        ): List<Player> {
            val radiusSquared = radius * radius
            val playerLoc = player.location

            return nearbyPlayersInLocation(playerLoc, ignoreY, radiusSquared)
        }

        fun getNearbyNPCs(
            npc: StoryNPC,
            radius: Double,
        ): List<StoryNPC> {
            if (!npc.isSpawned) return Collections.emptyList()

            val npcLocation = npc.location ?: return Collections.emptyList()

            return CitizensAPI
                .getNPCRegistry()
                .filter { otherNpc ->
                    otherNpc.isSpawned &&
                        otherNpc.uniqueId != npc.uniqueId &&
                        otherNpc.entity.location.world == npcLocation.world &&
                        otherNpc.entity.location.distanceSquared(npcLocation) <= radius * radius
                }.map { CitizensStoryNPC(it) }
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
        ): List<Player> {
            return Bukkit.getOnlinePlayers().filter { player ->
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
        ): List<StoryNPC> =
            CitizensAPI
                .getNPCRegistry()
                .filter { npc ->
                    npc.isSpawned &&
                        npc.entity.location.world == player.location.world &&
                        npc.entity.location.distanceSquared(player.location) <= radius * radius
                }.map { CitizensStoryNPC(it) }

        // Optional: Clear the cache (e.g., on reload)
        fun clearCache() {
            npcCache.clear()
        }

        companion object {
            // Static method to get the single instance
            fun getInstance(plugin: Story): NPCUtils {
                InstanceHolder.initialize(plugin)
                return InstanceHolder.instance
            }
        }
    }
