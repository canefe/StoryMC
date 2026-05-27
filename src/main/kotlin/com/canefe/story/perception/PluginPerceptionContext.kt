package com.canefe.story.perception

import com.canefe.story.Story
import com.canefe.story.util.characterId
import com.google.protobuf.Message
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.LivingEntity

/**
 * Production [PerceptionContext]. Pulls live snapshots from the running Story
 * plugin (NPCs, locations, affordances) and routes proto emits through
 * `plugin.wsTransport.sendProto(...)`.
 *
 * Construct per tick. The class holds no state; all reads happen lazily as the
 * broadcaster iterates its loops.
 */
class PluginPerceptionContext(private val plugin: Story) : PerceptionContext {

    override fun npcs(): List<NpcSnapshot> {
        if (!plugin.isNpcRegistryReady || !plugin.isCharacterRegistryReady) return emptyList()
        val onlineCharIds = Bukkit.getOnlinePlayers()
            .mapNotNull { try { it.characterId } catch (_: Exception) { null } }
            .toSet()

        return plugin.npcRegistry.all().mapNotNull { npc ->
            val entity = npc.entity as? LivingEntity ?: return@mapNotNull null
            val charId = plugin.characterRegistry.getCharacterIdForNPC(npc) ?: return@mapNotNull null
            // Mirror tickCharacters(): a stand-in for an online player doesn't
            // perceive (the player perceives for themselves).
            if (charId in onlineCharIds) return@mapNotNull null

            val stats = plugin.characterStatsCache.get(charId)
            val eyeLoc = entity.eyeLocation
            NpcSnapshot(
                charId = charId,
                position = entity.location,
                eye = eyeLoc.toVector(),
                facing = eyeLoc.direction,
                consciousness = stats.consciousness,
                sightRange = plugin.characterStatsCache.effectiveSightRange(charId),
                fovHalfDeg = plugin.characterStatsCache.effectiveFov(charId),
            )
        }
    }

    override fun locations(): List<LocationSnapshot> {
        // `locationManager` is a lateinit on Story; the parent broadcaster.tick()
        // already guards on registry readiness before invoking runOnce, and the
        // location manager is initialized in the same boot phase. If somehow
        // not initialized, an UninitializedPropertyAccessException would surface
        // — preferable to silently dropping locations.
        return plugin.locationManager.getAllLocations().mapNotNull { loc ->
            val bukkit = loc.bukkitLocation ?: return@mapNotNull null
            LocationSnapshot(
                instanceName = loc.name,
                templateId = loc.template,
                center = bukkit,
                radius = loc.radius,
                tags = loc.tags.toList(),
            )
        }
    }

    override fun affordances(): List<AffordanceSnapshot> {
        val mongo = plugin.storageFactory.mongoClient ?: return emptyList()
        return try {
            val storage = com.canefe.story.affordance.AffordanceStorage(mongo)
            storage.findAll().mapNotNull { r ->
                val world = plugin.server.getWorld(r.world) ?: return@mapNotNull null
                val typeDef = plugin.affordanceTypeRegistry.getById(r.affordanceTypeId)
                AffordanceSnapshot(
                    id = r.id,
                    position = Location(world, r.x, r.y, r.z),
                    tags = typeDef?.tags ?: emptyList(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun lightLevelAt(at: Location): Int =
        try {
            at.block.lightLevel.toInt()
        } catch (_: Exception) {
            15
        }

    override fun hasLineOfSight(perceiverCharId: String, to: Location): Boolean {
        // Live LOS uses the perceiver entity's hasLineOfSight against the target
        // entity. For locations there's no Entity at the target — fall back to a
        // raycast from the perceiver eye to the (x,y,z) world position.
        if (!plugin.isNpcRegistryReady || !plugin.isCharacterRegistryReady) return false
        val npc = plugin.npcRegistry.all().firstOrNull {
            plugin.characterRegistry.getCharacterIdForNPC(it) == perceiverCharId
        } ?: return false
        val entity = npc.entity as? LivingEntity ?: return false
        val eye = entity.eyeLocation
        val world = eye.world ?: return false
        if (to.world != world) return false
        val from = eye.toVector()
        val dirVec = to.toVector().subtract(from)
        val lenSq = dirVec.lengthSquared()
        if (lenSq < 1.0e-6) return true
        val len = kotlin.math.sqrt(lenSq)
        val dir = dirVec.multiply(1.0 / len)
        return try {
            val hit = world.rayTraceBlocks(eye, dir, len)
            hit == null
        } catch (_: Exception) {
            true
        }
    }

    override fun gameTimeMs(): Long = System.currentTimeMillis()

    override fun simPaused(): Boolean = false

    override fun sendProto(message: Message) {
        plugin.wsTransport?.sendProto(message)
    }
}
