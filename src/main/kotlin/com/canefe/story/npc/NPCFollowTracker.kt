package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight per-NPC follow loop that re-targets navigation each second.
 *
 * Used for "Follow Char" (NPC follows another NPC) since the StoryNPC.follow()
 * interface is Player-only. For Player follow, prefer [StoryNPC.follow] which
 * already handles its own loop in CitizensStoryNPC.
 */
class NPCFollowTracker(
    private val plugin: Story,
) {
    private val tasks = ConcurrentHashMap<UUID, Int>()

    /**
     * Make [follower] follow [target]. Cancels any prior follow for [follower].
     * The loop ends automatically if either side becomes unspawned/invalid.
     */
    fun follow(follower: StoryNPC, target: Entity) {
        cancel(follower.uniqueId)
        val taskId =
            Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin,
                Runnable {
                    if (!follower.isSpawned || !target.isValid) {
                        cancel(follower.uniqueId)
                        return@Runnable
                    }
                    val followerLoc = follower.location ?: return@Runnable
                    val distSq = followerLoc.distanceSquared(target.location)
                    if (distSq > 9.0) {
                        follower.navigateTo(target)
                    }
                },
                0L,
                20L,
            )
        tasks[follower.uniqueId] = taskId
    }

    fun cancel(followerId: UUID) {
        tasks.remove(followerId)?.let { Bukkit.getScheduler().cancelTask(it) }
    }

    fun cancelAll() {
        tasks.values.forEach { Bukkit.getScheduler().cancelTask(it) }
        tasks.clear()
    }
}
