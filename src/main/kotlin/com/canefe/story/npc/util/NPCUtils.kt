package com.canefe.story.npc.util

import com.canefe.story.Story
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import java.awt.Color
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class NPCUtils // Private constructor to prevent instantiation
    private constructor(
        private val plugin: Story,
    ) {
        // Cache for NPCs
        private val npcCache: MutableMap<String, NPC> = ConcurrentHashMap()

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
        fun getNPCByNameAsync(npcName: String): CompletableFuture<NPC?> {
            return CompletableFuture.supplyAsync {
                // Check cache first
                if (npcCache.containsKey(npcName.lowercase(Locale.getDefault()))) {
                    return@supplyAsync npcCache[npcName.lowercase(Locale.getDefault())]
                }

                // Search NPC registry if not in cache
                for (npc in CitizensAPI.getNPCRegistry()) {
                    if (npc.name.equals(npcName, ignoreCase = true)) {
                        npcCache[npcName.lowercase(Locale.getDefault())] = npc
                        return@supplyAsync npc
                    }
                }

                null // NPC not found
            }
        }

        // GetOrCreateContextForNPC

        fun getNPCUUID(npcName: String?): UUID? {
            var foundNPC: NPC? = null
            for (npc in CitizensAPI.getNPCRegistry()) {
                if (npc.name.equals(npcName, ignoreCase = true)) {
                    foundNPC = npc
                    break
                }
            }
            return foundNPC?.uniqueId
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
