package com.canefe.story.npc.registry

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.npc.CitizensStoryNPC
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.NPCRemoveEvent
import net.citizensnpcs.api.event.NPCRenameEvent
import net.citizensnpcs.api.event.NPCSpawnEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for all StoryNPCs in the world (Citizens, MythicMobs, future).
 *
 * Citizens NPCs are only registered if they have a backing CharacterRecord —
 * decorative/shopkeeper NPCs without a Story character are ignored.
 */
class StoryNPCRegistry(
    private val plugin: Story,
) : Listener {
    private val byUuid = ConcurrentHashMap<UUID, StoryNPC>()
    private val byNameLower = ConcurrentHashMap<String, UUID>()
    private val byEntityUuid = ConcurrentHashMap<UUID, UUID>() // entity.uniqueId -> StoryNPC.uniqueId

    // -- Lookup --

    fun get(uniqueId: UUID): StoryNPC? = byUuid[uniqueId]

    fun getByName(name: String): StoryNPC? = byNameLower[name.lowercase()]?.let { byUuid[it] }

    fun getByEntity(entity: Entity): StoryNPC? = byEntityUuid[entity.uniqueId]?.let { byUuid[it] }

    /**
     * Lookup by the UUID the client sees (e.g. a LibsDisguises fake-player
     * uuid for MythicMobs). Used when the client sends an entity uuid in a
     * c2s packet — the server otherwise can't map it back to a StoryNPC.
     */
    fun getByClientFacingUuid(uuid: UUID): StoryNPC? =
        byUuid.values.firstOrNull { it.clientFacingUuid == uuid }

    fun all(): Collection<StoryNPC> = byUuid.values

    fun nearby(
        location: Location,
        radius: Double,
    ): List<StoryNPC> {
        val radiusSquared = radius * radius
        return byUuid.values.filter { npc ->
            val loc = npc.location ?: return@filter false
            loc.world == location.world && loc.distanceSquared(location) <= radiusSquared
        }
    }

    // -- Mutation --

    fun register(npc: StoryNPC) {
        byUuid[npc.uniqueId] = npc
        byNameLower[npc.name.lowercase()] = npc.uniqueId
        npc.entity?.uniqueId?.let { byEntityUuid[it] = npc.uniqueId }
    }

    fun unregister(uniqueId: UUID): StoryNPC? {
        val removed = byUuid.remove(uniqueId) ?: return null
        byNameLower.remove(removed.name.lowercase())
        byEntityUuid.entries.removeIf { it.value == uniqueId }
        return removed
    }

    fun clear() {
        byUuid.clear()
        byNameLower.clear()
        byEntityUuid.clear()
    }

    // -- Boot-time Citizens load --

    /**
     * Scan Citizens registry, register any NPC that has a backing CharacterRecord.
     * Safe to call multiple times — re-registers existing entries.
     */
    fun loadExistingCitizens() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            plugin.logger.info("[StoryNPCRegistry] Citizens not enabled, skipping load")
            return
        }
        if (!plugin.isCharacterRegistryReady) {
            plugin.logger.warning("[StoryNPCRegistry] CharacterRegistry not initialized, skipping load")
            return
        }

        var loaded = 0
        var skipped = 0
        for (citizensNpc in CitizensAPI.getNPCRegistry()) {
            val storyNpc = CitizensStoryNPC(citizensNpc)
            if (plugin.characterRegistry.getByStoryNPC(storyNpc) == null) {
                skipped++
                continue
            }
            register(storyNpc)
            loaded++
        }
        plugin.logger.info("[StoryNPCRegistry] Loaded $loaded Citizens NPCs (skipped $skipped without character)")
    }

    // -- Citizens event sync --

    @EventHandler
    fun onNPCSpawn(event: NPCSpawnEvent) {
        val storyNpc = CitizensStoryNPC(event.npc)
        if (!plugin.isCharacterRegistryReady) return
        if (plugin.characterRegistry.getByStoryNPC(storyNpc) == null) return
        register(storyNpc)
    }

    @EventHandler
    fun onNPCRemove(event: NPCRemoveEvent) {
        unregister(event.npc.uniqueId)
    }

    @EventHandler
    fun onNPCRename(event: NPCRenameEvent) {
        val uuid = event.npc.uniqueId
        val current = byUuid[uuid] ?: return
        // Update name index: drop old name, add new name (the StoryNPC reads name from
        // the underlying Citizens NPC, which has already been renamed by the time the
        // event fires after-the-fact for some Citizens versions; rebuild from current).
        byNameLower.entries.removeIf { it.value == uuid }
        byNameLower[current.name.lowercase()] = uuid
    }
}
