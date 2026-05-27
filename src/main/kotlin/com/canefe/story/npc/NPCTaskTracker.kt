package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-NPC repeating-task registry for sticky behaviors that need a tick loop on
 * the Bukkit main thread (follow, look-at, future "channel" / "perform" tasks).
 *
 * Tasks are keyed by (npcId, kind) so a single NPC can run independent tasks
 * concurrently — e.g. follow X while looking at Y. Starting a new task with the
 * same (npcId, kind) cancels the prior one.
 *
 * The supplied [tick] callback returns false to self-cancel (target invalidated,
 * world changed, etc.); the tracker also cancels automatically when the NPC
 * becomes unspawned.
 */
class NPCTaskTracker(
    private val plugin: Story,
) {
    private data class Key(val npcId: UUID, val kind: String)

    private val tasks = ConcurrentHashMap<Key, Int>()

    /**
     * Schedule a repeating [tick] for [npc] under [kind]. Cancels any prior task
     * with the same (npc, kind). Period is in server ticks (20 = 1s). The tick
     * stops automatically when [tick] returns false or [npc] is no longer spawned.
     */
    fun start(
        npc: StoryNPC,
        kind: String,
        periodTicks: Long,
        tick: () -> Boolean,
    ) {
        val key = Key(npc.uniqueId, kind)
        cancelKey(key)
        val taskId =
            Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin,
                Runnable {
                    if (!npc.isSpawned) {
                        cancelKey(key)
                        return@Runnable
                    }
                    if (!tick()) {
                        cancelKey(key)
                    }
                },
                0L,
                periodTicks,
            )
        tasks[key] = taskId
    }

    /** Cancel a single (npc, kind) task if running. */
    fun cancel(npcId: UUID, kind: String) {
        cancelKey(Key(npcId, kind))
    }

    /** Cancel every task running for [npcId] across all kinds. */
    fun cancelAllFor(npcId: UUID) {
        val victims = tasks.keys.filter { it.npcId == npcId }
        victims.forEach { cancelKey(it) }
    }

    fun cancelAll() {
        tasks.values.forEach { Bukkit.getScheduler().cancelTask(it) }
        tasks.clear()
    }

    private fun cancelKey(key: Key) {
        tasks.remove(key)?.let { Bukkit.getScheduler().cancelTask(it) }
    }

    /**
     * Make [follower] follow [target]. Re-targets navigation each second when the
     * distance exceeds 3 blocks. Cancels any prior follow for [follower].
     */
    fun follow(follower: StoryNPC, target: Entity) {
        start(follower, KIND_FOLLOW, FOLLOW_PERIOD_TICKS) {
            if (!target.isValid) return@start false
            val followerLoc = follower.location ?: return@start true
            if (followerLoc.world != target.world) return@start false
            val distSq = followerLoc.distanceSquared(target.location)
            if (distSq > FOLLOW_ARRIVE_RANGE_SQ) {
                follower.navigateTo(target)
            }
            true
        }
    }

    /** Cancel an in-flight follow for [followerId]. */
    fun cancelFollow(followerId: UUID) = cancel(followerId, KIND_FOLLOW)

    /**
     * Sticky head-lock: re-applies [StoryNPC.lookAt] (teleport-rotate toward
     * [target]) every tick interval, so the NPC's facing tracks the target until
     * [cancelLookAt] / [cancel] is called, or the target becomes invalid /
     * leaves the world.
     */
    fun lookAt(npc: StoryNPC, target: Entity) {
        start(npc, KIND_LOOK_AT, LOOK_AT_PERIOD_TICKS) {
            if (!target.isValid) return@start false
            val npcLoc = npc.location ?: return@start true
            if (npcLoc.world != target.world) return@start false
            npc.lookAt(target)
            true
        }
    }

    /** Cancel an in-flight look-at for [npcId]. */
    fun cancelLookAt(npcId: UUID) = cancel(npcId, KIND_LOOK_AT)

    companion object {
        const val KIND_FOLLOW = "follow"
        const val KIND_LOOK_AT = "look_at"

        private const val FOLLOW_PERIOD_TICKS = 20L
        private const val FOLLOW_ARRIVE_RANGE_SQ = 9.0

        /**
         * Re-apply cadence for look_at. Teleport-rotate is instantaneous and
         * doesn't survive MythicMobs' AI rotating the head between ticks, so we
         * re-set the facing every 2 ticks (~10Hz) to keep the lock visually
         * smooth as the target moves.
         */
        private const val LOOK_AT_PERIOD_TICKS = 2L
    }
}
