package com.canefe.story.perception

import com.canefe.story.Story
import com.canefe.story.bridge.PerceptionStimulusEvent
import com.canefe.story.util.characterId
import com.canefe.storyproto.v1.AffordanceSightingStimulus
import com.canefe.storyproto.v1.LocationSightingStimulus
import com.google.protobuf.Message
import java.util.UUID
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.util.Vector
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.acos

/* -------------------------------------------------------------------------- */
/*  PerceptionContext — testable surface for the pure spatial-perception pass */
/* -------------------------------------------------------------------------- */

/**
 * Snapshot of a perceiver NPC at tick time. Uses Bukkit's own [Location]/[Vector]
 * types — same shapes the live `tickCharacters()` path uses, no parallel coordinate
 * fields to keep in sync.
 */
data class NpcSnapshot(
    val charId: String,
    /** Body position. `position.world?.name` is the comparison key for cross-world filtering. */
    val position: Location,
    /** Eye position used for FOV ray origin and LOS. */
    val eye: Vector,
    /** Unit forward vector from the head's yaw+pitch. */
    val facing: Vector,
    val consciousness: Double,
    val sightRange: Double,
    /** Half-angle in degrees. `>= 180` disables FOV gating. */
    val fovHalfDeg: Double,
)

/** Snapshot of a registered StoryLocation instance. */
data class LocationSnapshot(
    /** Instance name (`Old Well`). Goes into `target_location_id`. */
    val instanceName: String,
    /** Sim location-def id (`village_well`). Goes into `target_location_def`. */
    val templateId: String,
    /** Center of the location instance. World is read off this. */
    val center: Location,
    val radius: Double,
    val tags: List<String>,
)

/** Snapshot of a placed affordance instance. */
data class AffordanceSnapshot(
    val id: String,
    val position: Location,
    val tags: List<String>,
)

/**
 * Pure interface the broadcaster's per-tick logic runs against. Tests supply
 * a `FakePerceptionContext`; production wires `PluginPerceptionContext`.
 */
interface PerceptionContext {
    fun npcs(): List<NpcSnapshot>
    fun locations(): List<LocationSnapshot>
    fun affordances(): List<AffordanceSnapshot>

    /** Block-light level at the given world position. 0–15. */
    fun lightLevelAt(at: Location): Int

    /** Bukkit-style line-of-sight check from the perceiver's eyes. */
    fun hasLineOfSight(perceiverCharId: String, to: Location): Boolean

    /** Monotonic game time in milliseconds — stamped onto outgoing stimuli. */
    fun gameTimeMs(): Long

    /** When true, the broadcaster is a no-op (sim disabled / paused). */
    fun simPaused(): Boolean

    /** Wire-only proto emit. Bypasses [StoryEventBus]. */
    fun sendProto(message: Message)
}

/* -------------------------------------------------------------------------- */
/*  Broadcaster                                                               */
/* -------------------------------------------------------------------------- */

/**
 * Periodically scans the MC world and emits perception stimuli so story-sim
 * can update each NPC's StimulusBuffer without owning spatial logic itself.
 *
 * Per-perceiver checks applied (in order, short-circuit on fail):
 *   1. Range          — effectiveSightRange from stats × consciousness
 *   2. Consciousness  — skip if below threshold (unconscious/sleeping)
 *   3. FOV cone       — target within perceiver's horizontal field-of-view
 *   4. Line-of-sight  — Paper hasLineOfSight() raycast (walls, terrain)
 *   5. Light level    — dim targets reduce strength (not a hard cut-off)
 *
 * Affordances (static world objects) skip FOV and LOS — they're ambient,
 * always "perceivable" when within range and conscious.
 *
 * Tick: every 2 seconds (40 ticks).
 *
 * Emit paths:
 *   - **Characters**: legacy [PerceptionStimulusEvent] via the in-process bus
 *     (Bukkit-coupled because it also drives PERCEPTION popups). Handled by
 *     [tickCharacters], NOT [runOnce].
 *   - **Locations**: typed [LocationSightingStimulus] via `ctx.sendProto`.
 *   - **Affordances**: typed [AffordanceSightingStimulus] via `ctx.sendProto`.
 */
class PerceptionBroadcaster(private val plugin: Story) : Listener {
    private var taskId: Int = -1
    // perceiverCharId → set of targetCharIds currently in perception
    private val perceivedSets = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /** A sticky action label plus the entity id the client resolves it against. */
    private data class ActionLabel(val label: String, val entityId: Int)

    /**
     * npcUuid → current non-empty ACTION label. ACTION popups are sticky on the
     * client, so a label is sent once on change; this map lets us replay the
     * current label to a player who joins/relogs after it was set (otherwise the
     * global change-diff in IntentExecutor would never re-send it).
     */
    private val activeActionLabels = java.util.concurrent.ConcurrentHashMap<UUID, ActionLabel>()

    /** Returns the set of charIds that [perceiverCharId] currently has in perception. */
    fun perceivedBy(perceiverCharId: String): Set<String> = perceivedSets[perceiverCharId] ?: emptySet()

    companion object {
        private const val TICK_INTERVAL = 40L       // 2 seconds
        private const val MIN_CONSCIOUSNESS = 0.1   // below this = can't perceive
        private const val BASE_STRENGTH = 50.0f
        private const val MIN_LIGHT_LEVEL = 4       // below this penalises strength
        private const val DEFAULT_FOV_DEG = 90.0    // half-angle each side = 180° total cone

        /**
         * Pure per-tick pass over locations and affordances. No Bukkit access:
         * every spatial fact comes from [ctx]. Used by both the production
         * scheduler tick and the unit tests.
         */
        fun runOnce(ctx: PerceptionContext) {
            if (ctx.simPaused()) return
            val now = ctx.gameTimeMs()

            val locations = ctx.locations()
            val affordances = ctx.affordances()
            if (locations.isEmpty() && affordances.isEmpty()) return

            for (npc in ctx.npcs()) {
                if (npc.consciousness < MIN_CONSCIOUSNESS) continue
                if (npc.sightRange <= 0.0) continue
                val npcWorld = npc.position.world ?: continue

                // ----- locations: ranged + FOV + LOS + light -----
                for (loc in locations) {
                    if (loc.center.world != npcWorld) continue
                    val dist = npc.position.distance(loc.center)
                    if (dist > npc.sightRange) continue

                    if (!inFov(npc.facing, npc.eye, loc.center.toVector(), npc.fovHalfDeg)) continue

                    if (!ctx.hasLineOfSight(npc.charId, loc.center)) continue

                    val lightLevel = ctx.lightLevelAt(loc.center)
                    val lightFactor = if (lightLevel < MIN_LIGHT_LEVEL) {
                        (lightLevel.toFloat() / MIN_LIGHT_LEVEL).coerceAtLeast(0.1f)
                    } else 1.0f
                    val strength = (BASE_STRENGTH * (1.0 - dist / npc.sightRange) * lightFactor * npc.consciousness).toFloat()

                    val msg = LocationSightingStimulus.newBuilder()
                        .setPerceiverCharId(npc.charId)
                        .setTargetLocationId(loc.instanceName)
                        .setTargetLocationDef(loc.templateId)
                        .setStrength(strength)
                        .setX(loc.center.x.toFloat())
                        .setY(loc.center.y.toFloat())
                        .setZ(loc.center.z.toFloat())
                        .also { b -> loc.tags.forEach { b.addTags(it) } }
                        .setTimestampMs(now)
                        .build()
                    ctx.sendProto(msg)
                }

                // ----- affordances: ambient (no FOV / no LOS) -----
                for (aff in affordances) {
                    if (aff.position.world != npcWorld) continue
                    val dist = npc.position.distance(aff.position)
                    if (dist > npc.sightRange) continue

                    val strength = (BASE_STRENGTH * (1.0 - dist / npc.sightRange) * npc.consciousness).toFloat()

                    val msg = AffordanceSightingStimulus.newBuilder()
                        .setPerceiverCharId(npc.charId)
                        .setTargetAffordanceId(aff.id)
                        .setStrength(strength)
                        .setX(aff.position.x.toFloat())
                        .setY(aff.position.y.toFloat())
                        .setZ(aff.position.z.toFloat())
                        .also { b -> aff.tags.forEach { b.addTags(it) } }
                        .setTimestampMs(now)
                        .build()
                    ctx.sendProto(msg)
                }
            }
        }

        internal fun inFov(facing: Vector, from: Vector, to: Vector, halfAngleDeg: Double): Boolean {
            if (halfAngleDeg >= 180.0) return true
            val dir = to.clone().subtract(from)
            if (dir.lengthSquared() < 1.0e-9) return true  // co-located → trivially "in fov"
            dir.normalize()
            val dot = facing.dot(dir).coerceIn(-1.0, 1.0)
            val angleDeg = Math.toDegrees(acos(dot))
            return angleDeg <= halfAngleDeg
        }
    }

    fun start() {
        if (taskId != -1) return
        // Register for join events so we can replay sticky ACTION labels to
        // players who connect after the label was set.
        Bukkit.getPluginManager().registerEvents(this, plugin)
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
            plugin,
            Runnable { tick() },
            TICK_INTERVAL,
            TICK_INTERVAL,
        )
    }

    fun stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId)
            taskId = -1
        }
        perceivedSets.clear()
        activeActionLabels.clear()
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        if (activeActionLabels.isEmpty()) return
        // Delay one tick so the client has registered its packet receivers.
        val snapshot = activeActionLabels.toMap()
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            for ((npcUuid, action) in snapshot) {
                sendPerceptionPopupTo(event.player, npcUuid, action.label, PopupType.ACTION, action.entityId)
            }
        }, 1L)
    }

    private fun tick() {
        if (!plugin.isNpcRegistryReady) return
        if (!plugin.isCharacterRegistryReady) return
        tickCharacters()
        runOnce(PluginPerceptionContext(plugin))
    }

    /**
     * Character-target perception. Stays Bukkit-coupled because it both
     * drives [PerceptionStimulusEvent] AND the PERCEPTION popup packet,
     * and tracks per-perceiver perceivedSets for popup edge-detection.
     */
    private fun tickCharacters() {
        val onlinePlayers = Bukkit.getOnlinePlayers()
        val onlineCharIds = onlinePlayers
            .mapNotNull { try { it.characterId } catch (_: Exception) { null } }
            .toSet()

        // Build candidate target list: all spawned NPCs + online players
        data class Target(
            val charId: String,
            val entity: LivingEntity,
            val isPlayer: Boolean,
        )

        val targets = mutableListOf<Target>()
        for (npc in plugin.npcRegistry.all()) {
            val entity = npc.entity as? LivingEntity ?: continue
            val charId = plugin.characterRegistry.getCharacterIdForNPC(npc) ?: continue
            targets += Target(charId, entity, charId in onlineCharIds)
        }
        for (player in onlinePlayers) {
            val charId = try { player.characterId } catch (_: Exception) { null } ?: continue
            targets += Target(charId, player, true)
        }

        for (npc in plugin.npcRegistry.all()) {
            val perceiverEntity = npc.entity as? LivingEntity ?: continue
            val perceiverCharId = plugin.characterRegistry.getCharacterIdForNPC(npc) ?: continue
            // Don't perceive if this is a stand-in for an online player
            if (perceiverCharId in onlineCharIds) continue

            val stats = plugin.characterStatsCache.get(perceiverCharId)
            if (stats.consciousness < MIN_CONSCIOUSNESS) continue

            val sightRange = plugin.characterStatsCache.effectiveSightRange(perceiverCharId)
            val fovHalfDeg = plugin.characterStatsCache.effectiveFov(perceiverCharId)
            val perceiverLoc = perceiverEntity.location
            val perceiverEyeLoc = perceiverEntity.eyeLocation
            val perceiverWorld = perceiverLoc.world ?: continue

            val perceivedNow = mutableSetOf<String>()
            val knownSet = perceivedSets.getOrPut(perceiverCharId) { java.util.concurrent.CopyOnWriteArraySet() }

            for (target in targets) {
                if (target.charId == perceiverCharId) continue
                val targetLoc = target.entity.location
                if (targetLoc.world != perceiverWorld) continue

                val dist = perceiverLoc.distance(targetLoc)
                if (dist > sightRange) continue

                val targetEyeLoc = target.entity.eyeLocation
                if (!inFov(perceiverEyeLoc.direction, perceiverEyeLoc.toVector(), targetEyeLoc.toVector(), fovHalfDeg)) continue

                if (!perceiverEntity.hasLineOfSight(target.entity)) continue

                val lightLevel = targetLoc.block.lightLevel.toInt()
                val lightFactor = if (lightLevel < MIN_LIGHT_LEVEL) {
                    (lightLevel.toFloat() / MIN_LIGHT_LEVEL).coerceAtLeast(0.1f)
                } else 1.0f

                val strength = (BASE_STRENGTH * (1.0 - dist / sightRange) * lightFactor * stats.consciousness).toFloat()

                perceivedNow += target.charId
                if (knownSet.add(target.charId)) {
                    val targetName = if (target.isPlayer)
                        target.entity.name
                    else
                        plugin.npcRegistry.all().firstOrNull {
                            plugin.characterRegistry.getCharacterIdForNPC(it) == target.charId
                        }?.name ?: target.charId
                    val clientUuid = npc.clientFacingUuid ?: perceiverEntity.uniqueId
                    broadcastPerceptionPopup(clientUuid, targetName, PopupType.PERCEPTION)
                }

                plugin.eventBus.emit(PerceptionStimulusEvent(
                    perceiverCharId = perceiverCharId,
                    targetCharId = target.charId,
                    targetAffordanceId = null,
                    stimulusType = "sight",
                    strength = strength,
                    x = targetLoc.x,
                    y = targetLoc.y,
                    z = targetLoc.z,
                    tags = if (target.isPlayer) listOf("humanoid", "player") else listOf("humanoid"),
                ))
            }

            // Remove targets that left perception this tick
            val lost = knownSet - perceivedNow
            if (lost.isNotEmpty()) {
                knownSet -= lost
            }
        }
    }

    enum class PopupType(val id: Byte) {
        PERCEPTION(0), COMBAT_ATTACK(1), COMBAT_ATTACKED(2), MOOD(3), AGGRESSION(4), ACTION(5)
    }

    /**
     * Sends the NPC's current sim action as sticky head-text via the perception
     * popup path. An empty label is an explicit clear (the client drops the
     * current ACTION popup for that NPC). The label is recorded so it can be
     * replayed to players who join after it was set (see [onPlayerJoin]).
     *
     * [entityId] is the Bukkit entity id of the NPC's backing entity. The client
     * resolves the in-world entity by id first (the same path the dialogue
     * bubbles use), because for LibsDisguises-disguised NPCs the disguise UUID
     * we key the popup by never matches any client-side entity uuid — only the
     * entity id is stable across the disguise.
     */
    fun sendActionPopup(npcUuid: UUID, label: String, entityId: Int) {
        if (label.isBlank()) {
            activeActionLabels.remove(npcUuid)
        } else {
            activeActionLabels[npcUuid] = ActionLabel(label, entityId)
        }
        broadcastPerceptionPopup(npcUuid, label, PopupType.ACTION, entityId)
    }

    private fun encodePopup(npcUuid: UUID, perceivedLabel: String, type: PopupType, entityId: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeLong(npcUuid.mostSignificantBits)
            out.writeLong(npcUuid.leastSignificantBits)
            out.writeByte(type.id.toInt())
            out.writeUTF(perceivedLabel)
            out.writeInt(entityId)
        }
        return baos.toByteArray()
    }

    /** Send a popup to a single player (used for join-replay of sticky labels). */
    private fun sendPerceptionPopupTo(
        player: Player,
        npcUuid: UUID,
        perceivedLabel: String,
        type: PopupType,
        entityId: Int,
    ) {
        val packet = WrapperPlayServerPluginMessage("story:npc_perception", encodePopup(npcUuid, perceivedLabel, type, entityId))
        try {
            PacketEvents.getAPI().playerManager.getUser(player).sendPacket(packet)
        } catch (_: Exception) {}
    }

    fun broadcastPerceptionPopup(
        npcUuid: UUID,
        perceivedLabel: String,
        type: PopupType = PopupType.PERCEPTION,
        entityId: Int = -1,
    ) {
        val packet = WrapperPlayServerPluginMessage("story:npc_perception", encodePopup(npcUuid, perceivedLabel, type, entityId))
        for (player in Bukkit.getOnlinePlayers()) {
            try {
                PacketEvents.getAPI().playerManager.getUser(player).sendPacket(packet)
            } catch (_: Exception) {}
        }
    }

    private fun inFov(facing: Vector, from: Vector, to: Vector, halfAngleDeg: Double): Boolean =
        Companion.inFov(facing, from, to, halfAngleDeg)
}
