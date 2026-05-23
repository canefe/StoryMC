package com.canefe.story.perception

import com.canefe.story.Story
import com.canefe.story.bridge.PerceptionStimulusEvent
import com.canefe.story.util.characterId
import java.util.UUID
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.util.Vector
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Periodically scans the MC world and emits [PerceptionStimulusEvent]s so story-sim
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

        // Load registered affordances once per tick
        val affordances = loadAffordances()

        // For each NPC perceiver, evaluate candidates
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

            // --- Character targets ---
            val perceivedNow = mutableSetOf<String>()
            val knownSet = perceivedSets.getOrPut(perceiverCharId) { java.util.concurrent.CopyOnWriteArraySet() }

            for (target in targets) {
                if (target.charId == perceiverCharId) continue
                val targetLoc = target.entity.location
                if (targetLoc.world != perceiverWorld) continue

                val dist = perceiverLoc.distance(targetLoc)
                if (dist > sightRange) continue

                // FOV check — ray from eye position using head's actual yaw+pitch
                val targetEyeLoc = target.entity.eyeLocation
                if (!inFov(perceiverEyeLoc.direction, perceiverEyeLoc.toVector(), targetEyeLoc.toVector(), fovHalfDeg)) continue

                // Line-of-sight check (Bukkit internally uses eye positions)
                if (!perceiverEntity.hasLineOfSight(target.entity)) continue

                // Light level — attenuate strength in darkness
                val lightLevel = targetLoc.block.lightLevel.toInt()
                val lightFactor = if (lightLevel < MIN_LIGHT_LEVEL) {
                    (lightLevel.toFloat() / MIN_LIGHT_LEVEL).coerceAtLeast(0.1f)
                } else 1.0f

                val strength = (BASE_STRENGTH * (1.0 - dist / sightRange) * lightFactor * stats.consciousness).toFloat()

                perceivedNow += target.charId
                if (knownSet.add(target.charId)) {
                    val perceiverName = npc.name
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

            // --- Affordance targets (no FOV/LOS — ambient perception) ---
            for (aff in affordances) {
                if (aff.world != perceiverWorld.name) continue
                val dx = aff.x - perceiverLoc.x
                val dy = aff.y - perceiverLoc.y
                val dz = aff.z - perceiverLoc.z
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                if (dist > sightRange) continue

                val strength = (BASE_STRENGTH * (1.0 - dist / sightRange) * stats.consciousness).toFloat()

                plugin.eventBus.emit(PerceptionStimulusEvent(
                    perceiverCharId = perceiverCharId,
                    targetCharId = null,
                    targetAffordanceId = aff.id,
                    stimulusType = "proximity",
                    strength = strength,
                    x = aff.x,
                    y = aff.y,
                    z = aff.z,
                    tags = aff.tags,
                ))
            }
        }
    }

    private data class AffordanceTarget(
        val id: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val world: String,
        val tags: List<String>,
    )

    private fun loadAffordances(): List<AffordanceTarget> {
        val mongo = plugin.storageFactory.mongoClient ?: return emptyList()
        return try {
            val storage = com.canefe.story.affordance.AffordanceStorage(mongo)
            storage.findAll().map { r ->
                val typeDef = plugin.affordanceTypeRegistry.getById(r.affordanceTypeId)
                AffordanceTarget(
                    id = r.id,
                    x = r.x,
                    y = r.y,
                    z = r.z,
                    world = r.world,
                    tags = typeDef?.tags ?: emptyList(),
                )
            }
        } catch (_: Exception) {
            emptyList()
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

    private fun inFov(facing: Vector, from: Vector, to: Vector, halfAngleDeg: Double): Boolean {
        if (halfAngleDeg >= 180.0) return true
        val dir = to.subtract(from).normalize()
        val dot = facing.dot(dir).coerceIn(-1.0, 1.0)
        val angleDeg = Math.toDegrees(acos(dot))
        return angleDeg <= halfAngleDeg
    }
}
