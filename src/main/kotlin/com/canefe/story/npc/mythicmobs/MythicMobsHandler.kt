package com.canefe.story.npc.mythicmobs

import com.canefe.story.util.PluginUtils
import io.lumine.mythic.api.adapters.AbstractEntity
import io.lumine.mythic.api.mobs.MythicMob
import io.lumine.mythic.api.skills.SkillCaster
import io.lumine.mythic.api.skills.SkillMetadata
import io.lumine.mythic.api.skills.SkillTrigger
import io.lumine.mythic.api.skills.placeholders.PlaceholderString
import io.lumine.mythic.bukkit.BukkitAdapter
import io.lumine.mythic.bukkit.MythicBukkit
import io.lumine.mythic.bukkit.adapters.BukkitEntity
import io.lumine.mythic.core.config.MythicLineConfigImpl
import io.lumine.mythic.core.skills.SkillExecutor
import io.lumine.mythic.core.skills.SkillMechanic
import io.lumine.mythic.core.skills.SkillMetadataImpl
import io.lumine.mythic.core.skills.mechanics.RunAIGoalSelectorMechanic
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.*
import kotlin.random.Random

/**
 * Handler for MythicMobs NPCs to integrate them with the conversation system
 */
class MythicMobsHandler(private val plugin: JavaPlugin) {

    // Track MythicMobs that are in conversations
    private val mythicMobsInConversation = mutableSetOf<UUID>()

    // Track hologram display times for idle behaviors
    private val mobIdleHologramTimes = mutableMapOf<UUID, Long>()

    private val savedAIGoalSelectors = mutableMapOf<UUID, List<String>>()

    /**
     * Checks if an entity is a MythicMob
     */
    fun isMythicMob(entity: Entity): Boolean {
        if (!PluginUtils.isPluginEnabled("MythicMobs")) return false

        return try {
            MythicBukkit.inst().mobManager.isMythicMob(entity)
        } catch (e: Exception) {
            plugin.logger.warning("Error checking if entity is MythicMob: ${e.message}")
            false
        }
    }

    /**
     * Gets MythicMob data for an entity
     */
    fun getMythicMobData(entity: Entity): MythicMobData? {
        if (!isMythicMob(entity)) return null

        try {
            val mythicMob = MythicBukkit.inst().mobManager.getMythicMobInstance(entity)
            if (mythicMob != null) {
                val mobType = mythicMob.type
                return MythicMobData(
                    uuid = entity.uniqueId,
                    internalName = mobType.internalName,
                    displayName = mobType.displayName?.get() ?: entity.name,
                    faction = mobType.config.getString("Faction") ?: "Neutral"
                )
            }
        } catch (e: Exception) {
            plugin.logger.warning("Error getting MythicMob data: ${e.message}")
        }

        return null
    }

    /**
     * Show idle behavior hologram above MythicMob
     */
    fun showIdleHologram(entity: Entity) {
        if (!isMythicMob(entity)) return

        val idleActions = listOf(
            "&7&o*adjusts posture*",
            "&7&o*scratches head*",
            "&7&o*hums quietly*",
            "&7&o*yawns*",
            "&7&o*stretches*",
            "&7&o*looks around*",
            "&7&o*coughs*"
        )

        val randomAction = idleActions[Random.nextInt(idleActions.size)]
        val mobUUID = entity.uniqueId
        val hologramName = "idle_mythic_${mobUUID}"

        if (PluginUtils.isPluginEnabled("DecentHolograms")) {
            try {
                val mobPos = entity.location.clone().add(0.0, 2.10, 0.0)

                // Check if the hologram already exists and remove it first
                val existingHologram = eu.decentsoftware.holograms.api.DHAPI.getHologram(hologramName)
                if (existingHologram != null) {
                    eu.decentsoftware.holograms.api.DHAPI.removeHologram(hologramName)
                }

                // Create new hologram
                val hologram = eu.decentsoftware.holograms.api.DHAPI.createHologram(hologramName, mobPos)
                eu.decentsoftware.holograms.api.DHAPI.addHologramLine(hologram, 0, randomAction)

                // Remove after a short delay
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    try {
                        eu.decentsoftware.holograms.api.DHAPI.removeHologram(hologramName)
                    } catch (e: Exception) {
                        // Hologram might already be removed, just ignore
                    }
                }, 40L) // 2 seconds

                // Track when we last showed an idle hologram
                mobIdleHologramTimes[mobUUID] = System.currentTimeMillis()
            } catch (e: Exception) {
                plugin.logger.warning("Error showing idle hologram: ${e.message}")
            }
        }
    }

    /**
     * Set a MythicMob as being in a conversation
     */
    fun setMythicMobInConversation(entity: Entity, inConversation: Boolean) {
        val mm = MythicBukkit.inst()
        val activeMobOptional = mm.mobManager.getActiveMob(entity.uniqueId)

        if (!activeMobOptional.isPresent) return

        val activeMob = activeMobOptional.get()
        val caster = activeMob as SkillCaster
        val trigger = BukkitAdapter.adapt(entity) // Use BukkitAdapter to get AbstractEntity
        val skillExecutor = mm.skillManager as SkillExecutor

        if (inConversation) {
            // Save current AI goal selectors
            savedAIGoalSelectors[entity.uniqueId] = activeMob.type.aiGoalSelectors
            mythicMobsInConversation.add(entity.uniqueId)

            // --- Clear Goals ---
            val clearMechanic = object : RunAIGoalSelectorMechanic(
                skillExecutor,
                File(""),
                "runaigoalselector_clear",
                MythicLineConfigImpl("")
            ) {
                init {
                    this.goal = PlaceholderString.of("clear")
                }
            }

            // Fix 1: Use create() factory method instead of constructor
            val metadataClear = SkillMetadataImpl(
                SkillTrigger.create("API"), // This handles generic type internally
                caster,
                trigger
            )
            clearMechanic.execute(metadataClear)

            // --- Add LookAtPlayers ---
            val lookMechanic = object : RunAIGoalSelectorMechanic(
                skillExecutor,
                File(""),
                "runaigoalselector_look",
                MythicLineConfigImpl("")
            ) {
                init {
                    this.goal = PlaceholderString.of("lookatplayers")
                }
            }

            val metadataLook = SkillMetadataImpl(
                SkillTrigger.create("API"),
                caster,
                trigger
            )
            lookMechanic.execute(metadataLook)

        } else {
            // Remove from conversation
            mythicMobsInConversation.remove(entity.uniqueId)

            // First clear existing AI goals
            val clearMechanic = object : RunAIGoalSelectorMechanic(
                skillExecutor,
                File(""),
                "runaigoalselector_clear",
                MythicLineConfigImpl("")
            ) {
                init {
                    this.goal = PlaceholderString.of("clear")
                }
            }

            val metadataClear = SkillMetadataImpl(
                SkillTrigger.create("API"),
                caster,
                trigger
            )
            clearMechanic.execute(metadataClear)

            // Restore saved AI goal selectors
            savedAIGoalSelectors[entity.uniqueId]?.forEach { goalSelector ->
                val restoreMechanic = object : RunAIGoalSelectorMechanic(
                    skillExecutor,
                    File(""),
                    "runaigoalselector_restore",
                    MythicLineConfigImpl("")
                ) {
                    init {
                        this.goal = PlaceholderString.of(goalSelector)
                    }
                }

                val metadataRestore = SkillMetadataImpl(
                    SkillTrigger.create("API"),
                    caster,
                    trigger
                )
                restoreMechanic.execute(metadataRestore)
            }

            // Remove saved selectors
            savedAIGoalSelectors.remove(entity.uniqueId)
        }
    }



    /**
     * Check if a MythicMob is currently in a conversation
     */
    fun isInConversation(entity: Entity): Boolean {
        return mythicMobsInConversation.contains(entity.uniqueId)
    }

    /**
     * Look at a target (player)
     */
    fun lookAtTarget(entity: Entity, target: Entity) {
        if (entity !is LivingEntity) return

        // Calculate direction vector from entity to target
        val direction = target.location.toVector().subtract(entity.location.toVector())

        // Set entity's direction to look at the target
        entity.teleport(entity.location.setDirection(direction))
    }

    /**
     * Cleanup MythicMob tracking
     */
    fun cleanup(entity: Entity) {
        mythicMobsInConversation.remove(entity.uniqueId)
        mobIdleHologramTimes.remove(entity.uniqueId)
    }

    /**
     * Clean up all resources when plugin disables
     */
    fun onDisable() {
        // Remove any active holograms
        if (PluginUtils.isPluginEnabled("DecentHolograms")) {
            mythicMobsInConversation.forEach { uuid ->
                try {
                    val hologramName = "idle_mythic_$uuid"
                    eu.decentsoftware.holograms.api.DHAPI.removeHologram(hologramName)
                } catch (e: Exception) {
                    // Ignore errors during shutdown
                }
            }
        }

        // Clear tracking collections
        mythicMobsInConversation.clear()
        mobIdleHologramTimes.clear()
        savedAIGoalSelectors.clear()
    }
}

/**
 * Data class to hold important information about a MythicMob
 */
data class MythicMobData(
    val uuid: UUID,
    val internalName: String,
    val displayName: String,
    val faction: String
)