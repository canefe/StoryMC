package com.canefe.story.perception

import com.canefe.story.Story
import com.canefe.story.bridge.PerceptionDetails
import com.canefe.story.util.characterId
import com.canefe.story.util.characterName
import net.citizensnpcs.api.CitizensAPI
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import kotlin.math.acos

/**
 * Per-observer dedup for "X is looking at Y" events.
 *
 * The gazer-side state in [GazeBroadcaster.activeGaze] already prevents
 * re-emitting on every tick for an unchanged target. This cache covers the
 * observer side: a single observer should not be told the same (gazer, target)
 * pair more than once per [ttlMillis].
 *
 * Keyed by (observerId, gazerId, targetId). Returns true if the event should
 * be emitted (and records the timestamp); false to suppress.
 */
class ObserverGazeCache(private val ttlMillis: Long) {
    private data class Key(val observer: String, val gazer: String, val target: String)
    private val seen = java.util.concurrent.ConcurrentHashMap<Key, Long>()

    fun shouldEmit(observerId: String, gazerId: String, targetId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = Key(observerId, gazerId, targetId)
        val last = seen[key]
        if (last != null && nowMs - last < ttlMillis) {
            return false
        }
        seen[key] = nowMs
        // Opportunistic cleanup: drop entries older than 2*ttl when the map
        // grows beyond a reasonable bound.
        if (seen.size > 1024) {
            seen.entries.removeIf { nowMs - it.value > ttlMillis * 2 }
        }
        return true
    }

    fun clear() {
        seen.clear()
    }
}

/**
 * Periodically raycasts from every character to detect direct eye-contact (tight ~15° cone).
 *
 * Emits two perception events per gaze pair each tick:
 *   - To the **target**: "X is looking directly at you" (Gaze with targetName=null)
 *   - To **nearby observers**: "X is looking at Y" (Gaze with targetName set)
 *
 * Tick: every 20 ticks (1 second) — fine enough for conversational use without spam.
 */
class GazeBroadcaster(private val plugin: Story) {
    private var taskId: Int = -1
    /** gazerId → targetId currently being gazed at. Emit only on enter/exit. */
    private val activeGaze = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val observerCache = ObserverGazeCache(ttlMillis = 30_000)

    companion object {
        private const val TICK_INTERVAL = 20L
        private const val GAZE_RANGE = 12.0       // max distance for gaze to count
        private const val GAZE_HALF_ANGLE = 15.0  // half-cone in degrees
    }

    fun start() {
        if (taskId != -1) return
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
        activeGaze.clear()
        observerCache.clear()
    }

    private fun tick() {
        if (!plugin.isNpcRegistryReady || !plugin.isPerceptionServiceReady) return
        if (!plugin.isCharacterRegistryReady) return

        data class Candidate(val charId: String, val name: String, val entity: LivingEntity)

        val candidates = mutableListOf<Candidate>()

        for (npc in plugin.npcRegistry.all()) {
            if (!npc.isSpawned) continue
            val entity = npc.entity as? LivingEntity ?: continue
            if (!entity.isValid) continue
            val charId = plugin.characterRegistry.getCharacterIdForNPC(npc) ?: continue
            candidates += Candidate(charId, npc.name, entity)
        }
        for (player in Bukkit.getOnlinePlayers()) {
            try { if (CitizensAPI.getNPCRegistry().isNPC(player)) continue } catch (_: Exception) {}
            val charId = try { player.characterId } catch (_: Exception) { null } ?: continue
            val name = try { player.characterName } catch (_: Exception) { player.name }
            candidates += Candidate(charId, name, player)
        }

        for (gazer in candidates) {
            val gazerEye = gazer.entity.eyeLocation
            val gazerDir = gazerEye.direction

            var gazeTarget: Candidate? = null
            var bestDot = -1.0

            for (target in candidates) {
                if (target.charId == gazer.charId) continue
                if (target.entity.world != gazer.entity.world) continue
                val dist = gazer.entity.location.distance(target.entity.location)
                if (dist > GAZE_RANGE) continue

                val toTarget = target.entity.eyeLocation.toVector()
                    .subtract(gazerEye.toVector()).normalize()
                val dot = gazerDir.dot(toTarget).coerceIn(-1.0, 1.0)
                val angleDeg = Math.toDegrees(acos(dot))
                if (angleDeg > GAZE_HALF_ANGLE) continue

                if (!gazer.entity.hasLineOfSight(target.entity)) continue

                if (dot > bestDot) {
                    bestDot = dot
                    gazeTarget = target
                }
            }

            // Track active gaze pairs — only emit on enter (target changed or new)
            val prevTarget = activeGaze[gazer.charId]
            if (gazeTarget == null) {
                activeGaze.remove(gazer.charId)
                continue
            }
            if (prevTarget == gazeTarget.charId) continue // still looking at same target, no re-emit
            activeGaze[gazer.charId] = gazeTarget.charId

            val gazerParticipants = mapOf(gazer.name to gazer.charId, gazeTarget.name to gazeTarget.charId)

            // Emit to the target: "X is looking directly at you"
            plugin.perceptionService.observeOne(
                characterName = gazeTarget.name,
                perceiverCharId = gazeTarget.charId,
                entity = gazeTarget.entity,
                details = PerceptionDetails.Gaze(
                    gazerId = gazer.charId,
                    gazerName = gazer.name,
                    targetName = null,
                    targetId = null,
                ),
                epicenter = gazer.entity.location,
                source = "gaze",
                participants = gazerParticipants,
            )

            // Emit to nearby observers (excluding gazer and target): "X is looking at Y"
            val now = System.currentTimeMillis()
            for (observer in candidates) {
                if (observer.charId == gazer.charId) continue
                if (observer.charId == gazeTarget.charId) continue
                if (observer.entity.world != gazer.entity.world) continue
                val dist = observer.entity.location.distance(gazer.entity.location)
                if (dist > plugin.perceptionService.getPerceptionRadius(observer.name)) continue
                if (!observerCache.shouldEmit(observer.charId, gazer.charId, gazeTarget.charId, now)) continue

                plugin.perceptionService.observeOne(
                    characterName = observer.name,
                    perceiverCharId = observer.charId,
                    entity = observer.entity,
                    details = PerceptionDetails.Gaze(
                        gazerId = gazer.charId,
                        gazerName = gazer.name,
                        targetName = gazeTarget.name,
                        targetId = gazeTarget.charId,
                    ),
                    epicenter = gazer.entity.location,
                    source = "gaze",
                    participants = gazerParticipants,
                )
            }
        }

        // Clear gaze state for any gazer no longer in the candidate list
        val activeCandidateIds = candidates.map { it.charId }.toSet()
        activeGaze.keys.retainAll(activeCandidateIds)
    }
}
