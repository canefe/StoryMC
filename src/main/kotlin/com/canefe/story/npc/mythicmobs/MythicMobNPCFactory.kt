package com.canefe.story.npc.mythicmobs

import com.canefe.story.Story
import io.lumine.mythic.api.adapters.AbstractEntity
import io.lumine.mythic.api.adapters.AbstractLocation
import io.lumine.mythic.api.skills.SkillCaster
import io.lumine.mythic.api.skills.SkillTrigger
import io.lumine.mythic.bukkit.BukkitAdapter
import io.lumine.mythic.bukkit.MythicBukkit
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent
import io.lumine.mythic.core.config.MythicLineConfigImpl
import io.lumine.mythic.core.skills.SkillExecutor
import io.lumine.mythic.core.skills.SkillMetadataImpl
import io.lumine.mythic.core.skills.mechanics.DisguiseMechanic
import me.libraryaddict.disguise.DisguiseAPI
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.io.File
import java.util.UUID

/**
 * Factory + lifecycle listener for MythicMob-backed StoryNPCs.
 *
 * Spawns a MythicMob template, applies a player-skin disguise, wraps as
 * [MythicMobStoryNPC], and registers it with [Story.npcRegistry].
 *
 * Listens for MythicMob deaths to unregister automatically.
 */
class MythicMobNPCFactory(
    private val plugin: Story,
) : Listener {
    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    /**
     * Spawn a MythicMob NPC and register it into the StoryNPC registry.
     *
     * @param mobTemplate Mythic mob internal name (e.g. "Character")
     * @param location Spawn location
     * @param displayName Player-facing name; also used as the disguise skin name
     * @param stableUniqueId Pre-existing stable UUID (use when re-creating from sim
     *                      state); defaults to a fresh random UUID
     * @return the registered [MythicMobStoryNPC], or null if spawn failed
     */
    fun spawn(
        mobTemplate: String,
        location: Location,
        displayName: String,
        stableUniqueId: UUID = UUID.randomUUID(),
    ): MythicMobStoryNPC? {
        val mm = MythicBukkit.inst()
        val mobType = mm.mobManager.getMythicMob(mobTemplate).orElse(null) ?: run {
            plugin.logger.warning("[MythicMobNPCFactory] Unknown mob template: $mobTemplate")
            return null
        }

        val active = mobType.spawn(BukkitAdapter.adapt(location), 1.0)
        active.displayName = displayName

        applyDisguise(active, displayName)

        val bukkitEntity = active.entity.bukkitEntity

        // Tag the backing entity so we can rehydrate after restart / chunk reload.
        bukkitEntity.persistentDataContainer.set(
            MythicMobNPCKeys.STABLE_UUID,
            MythicMobNPCKeys.STRING,
            stableUniqueId.toString(),
        )
        bukkitEntity.persistentDataContainer.set(
            MythicMobNPCKeys.DISPLAY_NAME,
            MythicMobNPCKeys.STRING,
            displayName,
        )
        bukkitEntity.persistentDataContainer.set(
            MythicMobNPCKeys.INTERNAL_NAME,
            MythicMobNPCKeys.STRING,
            mobTemplate,
        )

        val npc =
            MythicMobStoryNPC(
                backingEntity = bukkitEntity,
                displayName = displayName,
                internalName = mobTemplate,
                stableUniqueId = stableUniqueId,
            )

        // Capture the LibsDisguises fake-player UUID so client-side bubbles can
        // anchor to the visible disguise instead of the (invisible) backing mob.
        if (Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")) {
            val disguise = DisguiseAPI.getDisguise(bukkitEntity)
            if (disguise is PlayerDisguise) {
                npc.disguiseUuid = disguise.uuid
            }
        }

        plugin.npcRegistry.register(npc)
        return npc
    }

    private fun applyDisguise(
        active: io.lumine.mythic.core.mobs.ActiveMob,
        skinName: String,
    ) {
        val mm = MythicBukkit.inst()
        val executor = mm.skillManager as SkillExecutor
        val mlc = MythicLineConfigImpl("type=player <disguise_name>")
        val mechanic =
            object : DisguiseMechanic(executor, File(""), "storynpc_initdisguise", mlc) {
                init {
                    this.disguise =
                        mlc.getPlaceholderString(
                            arrayOf("type", "disguise", "d"),
                            // Strip anything after the first space to keep the username valid
                            "player ${skinName.substringBefore(' ')} setCustomName \"\" setCustomNameVisible false setSprinting",
                        )
                }
            }
        val abstLoc = BukkitAdapter.adapt(active.entity.bukkitEntity.location)
        val metadata =
            SkillMetadataImpl(
                SkillTrigger.create("API"),
                active as SkillCaster,
                active.entity,
                abstLoc,
                mutableListOf<AbstractEntity>(),
                mutableListOf<AbstractLocation>(abstLoc),
                1f,
            )
        metadata.setMetadata("disguise_name", skinName)
        mechanic.execute(metadata)
    }

    /**
     * Re-register an existing tagged Bukkit entity as a MythicMobStoryNPC.
     * Reads stable UUID + display name from the entity's PDC, re-applies the
     * player disguise (lost on restart), and registers in the NPC registry.
     *
     * Returns the registered NPC, or null if the entity has no Story tags or
     * isn't currently a tracked Mythic mob.
     */
    fun rehydrate(entity: org.bukkit.entity.Entity): MythicMobStoryNPC? {
        val pdc = entity.persistentDataContainer
        val stableUuidStr = pdc.get(MythicMobNPCKeys.STABLE_UUID, MythicMobNPCKeys.STRING) ?: return null
        val displayName = pdc.get(MythicMobNPCKeys.DISPLAY_NAME, MythicMobNPCKeys.STRING) ?: return null
        val internalName = pdc.get(MythicMobNPCKeys.INTERNAL_NAME, MythicMobNPCKeys.STRING) ?: "Character"

        val stableUuid =
            try {
                UUID.fromString(stableUuidStr)
            } catch (_: IllegalArgumentException) {
                return null
            }

        // Already registered (e.g. EntityAddToWorldEvent fired twice) — skip.
        plugin.npcRegistry.get(stableUuid)?.let { return it as? MythicMobStoryNPC }

        val active = MythicBukkit.inst().mobManager.getActiveMob(entity.uniqueId).orElse(null)
        if (active == null) {
            // Mythic may not have reattached the ActiveMob yet (its own chunk-load
            // handler can run after ours on first stream-in). Retry on a short
            // delay before giving up.
            Bukkit.getScheduler().runTaskLater(
                plugin,
                Runnable {
                    if (!entity.isValid) return@Runnable
                    if (plugin.npcRegistry.get(stableUuid) != null) return@Runnable
                    val retry = MythicBukkit.inst().mobManager.getActiveMob(entity.uniqueId).orElse(null)
                    if (retry == null) {
                        plugin.logger.warning(
                            "[MythicMobNPCFactory] Tagged entity ${entity.uniqueId} ($displayName) " +
                                "is not a live MythicMob after retry — skipping rehydration.",
                        )
                    } else {
                        rehydrate(entity)
                    }
                },
                20L,
            )
            return null
        }

        // Re-apply disguise (LibsDisguises does not persist by default).
        applyDisguise(active, displayName)

        val npc =
            MythicMobStoryNPC(
                backingEntity = entity,
                displayName = displayName,
                internalName = internalName,
                stableUniqueId = stableUuid,
            )
        if (Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")) {
            val disguise = DisguiseAPI.getDisguise(entity)
            if (disguise is PlayerDisguise) {
                npc.disguiseUuid = disguise.uuid
            }
        }
        plugin.npcRegistry.register(npc)
        plugin.logger.info("[MythicMobNPCFactory] Rehydrated $displayName (uuid=$stableUuid)")
        return npc
    }

    /**
     * Scan all loaded entities in all worlds and rehydrate any that carry
     * Story PDC tags. Called at boot after MythicMobs has loaded.
     */
    fun rehydrateAllLoaded() {
        var count = 0
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities) {
                if (entity.persistentDataContainer.has(MythicMobNPCKeys.STABLE_UUID, MythicMobNPCKeys.STRING)) {
                    if (rehydrate(entity) != null) count++
                }
            }
        }
        plugin.logger.info("[MythicMobNPCFactory] Rehydrated $count MythicMob StoryNPCs from loaded chunks")
    }

    @EventHandler
    fun onMythicMobDeath(event: MythicMobDeathEvent) {
        val entity = event.entity ?: return
        val storyNpc = plugin.npcRegistry.getByEntity(entity) ?: return
        plugin.npcRegistry.unregister(storyNpc.uniqueId)
    }

    @EventHandler
    fun onChunkLoad(event: org.bukkit.event.world.ChunkLoadEvent) {
        for (entity in event.chunk.entities) {
            if (entity.persistentDataContainer.has(MythicMobNPCKeys.STABLE_UUID, MythicMobNPCKeys.STRING)) {
                rehydrate(entity)
            }
        }
    }

    /**
     * Sweep tagged entities around a joining player after their chunks have
     * had time to load and re-apply disguises. Two cases handled:
     *   1. Not yet registered → full [rehydrate] (registry insert + disguise).
     *   2. Already registered → re-apply disguise only. LibsDisguises does
     *      not always push packets to a tracker that joined after the
     *      disguise was first applied, so a fresh apply is the simplest
     *      reliable way to make sure the new player sees the skin.
     */
    @EventHandler
    fun onPlayerJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                if (!player.isOnline) return@Runnable
                val box = org.bukkit.util.BoundingBox.of(player.location, 128.0, 128.0, 128.0)
                for (entity in player.world.getNearbyEntities(box)) {
                    if (!entity.persistentDataContainer.has(MythicMobNPCKeys.STABLE_UUID, MythicMobNPCKeys.STRING)) continue
                    if (plugin.npcRegistry.getByEntity(entity) == null) {
                        rehydrate(entity)
                    } else {
                        reapplyDisguise(entity)
                    }
                }
            },
            40L,
        )
    }

    /**
     * Re-run the disguise mechanic on an already-registered MythicMob entity.
     * Mirrors what [rehydrate] does for the non-registered case, just without
     * touching the registry. Also refreshes [MythicMobStoryNPC.disguiseUuid] so
     * [clientFacingUuid] returns the new disguise UUID — the old one is gone
     * after LibsDisguises re-creates the PlayerDisguise.
     */
    private fun reapplyDisguise(entity: org.bukkit.entity.Entity) {
        val displayName = entity.persistentDataContainer
            .get(MythicMobNPCKeys.DISPLAY_NAME, MythicMobNPCKeys.STRING) ?: return
        val active = MythicBukkit.inst().mobManager.getActiveMob(entity.uniqueId).orElse(null) ?: return
        applyDisguise(active, displayName)
        if (Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")) {
            val disguise = DisguiseAPI.getDisguise(entity)
            if (disguise is PlayerDisguise) {
                val npc = plugin.npcRegistry.getByEntity(entity) as? MythicMobStoryNPC ?: return
                npc.disguiseUuid = disguise.uuid
                // UUID changed — force every online player to receive a fresh bundle.
                for (p in Bukkit.getOnlinePlayers()) plugin.nearbyNpcBroadcaster.invalidatePlayer(p)
            }
        }
    }

    /**
     * Periodic sweep: every [REHYDRATE_INTERVAL_TICKS] ticks, scan a box around
     * each online player and rehydrate any tagged MythicMob StoryNPCs the
     * registry doesn't yet know about. Catches:
     *   - boot-time entities (player chunks unloaded at server start)
     *   - chunks streamed via player movement after Mythic's ActiveMob
     *     reattach loses the race with our ChunkLoadEvent handler
     *   - any other path that produces a tagged-but-unregistered entity
     */
    fun startPeriodicRehydrate() {
        Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable {
                for (player in Bukkit.getOnlinePlayers()) {
                    val box = org.bukkit.util.BoundingBox.of(player.location, 128.0, 128.0, 128.0)
                    for (entity in player.world.getNearbyEntities(box)) {
                        if (entity.persistentDataContainer.has(MythicMobNPCKeys.STABLE_UUID, MythicMobNPCKeys.STRING) &&
                            plugin.npcRegistry.getByEntity(entity) == null
                        ) {
                            rehydrate(entity)
                        }
                    }
                }
            },
            REHYDRATE_INTERVAL_TICKS,
            REHYDRATE_INTERVAL_TICKS,
        )
    }

    private companion object {
        const val REHYDRATE_INTERVAL_TICKS = 100L
    }
}
