package com.canefe.story.npc.squad

import com.canefe.story.Story
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-squad order state + re-targeting tick loop. Mirrors the simpler
 * NPCFollowTracker pattern but operates on whole squads.
 *
 * One Bukkit task per active squad. Issuing a new order replaces the old.
 * [SquadOrder.Idle] cancels the loop.
 *
 * No formation logic v1 — every member targets the same point. Formations
 * land in Task 5.
 */
class SquadOrderTracker(
    private val plugin: Story,
) {
    private val orders = ConcurrentHashMap<String, SquadOrder>() // squadId -> current order
    private val taskIds = ConcurrentHashMap<String, Int>()

    fun current(squadId: String): SquadOrder = orders[squadId] ?: SquadOrder.Idle

    fun issue(squadId: String, order: SquadOrder, commander: org.bukkit.entity.Player? = null) {
        cancelTask(squadId)
        orders[squadId] = order
        // Fire once-on-issue signals — fire-and-forget, no payload beyond source.
        // Templates use @trigger to know who issued the order.
        emitIssueSignal(squadId, order, commander)
        if (order is SquadOrder.Idle || order is SquadOrder.HoldPosition) {
            applyOnce(squadId, order)
            return
        }
        val taskId =
            Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin,
                Runnable { applyTick(squadId) },
                0L,
                20L,
            )
        taskIds[squadId] = taskId
    }

    private fun emitIssueSignal(
        squadId: String,
        order: SquadOrder,
        commander: org.bukkit.entity.Player?,
    ) {
        val signalName =
            when (order) {
                is SquadOrder.MoveTo -> "SquadMove"
                is SquadOrder.FollowPlayer -> "SquadFollow"
                else -> return // Engage/Hold/Idle have their own per-tick / applyOnce paths
            }
        val source: org.bukkit.entity.Entity? =
            when (order) {
                is SquadOrder.FollowPlayer -> Bukkit.getPlayer(order.playerUuid)
                else -> commander
            }
        val squad = plugin.squadRegistry.getById(squadId) ?: return
        for (npc in plugin.squadRegistry.resolveLiveMembers(squad)) {
            if (!npc.isSpawned) continue
            npc.signal(signalName, source)
        }
    }

    fun cancel(squadId: String) {
        cancelTask(squadId)
        orders[squadId] = SquadOrder.Idle
        applyOnce(squadId, SquadOrder.Idle)
    }

    fun cancelAll() {
        taskIds.keys.toList().forEach { cancelTask(it) }
        orders.clear()
    }

    private fun cancelTask(squadId: String) {
        taskIds.remove(squadId)?.let { Bukkit.getScheduler().cancelTask(it) }
    }

    /** Find any loaded entity by uuid (mobs, items, anything Bukkit knows). */
    private fun findBukkitEntity(uuid: UUID): org.bukkit.entity.Entity? =
        Bukkit.getWorlds().asSequence().mapNotNull { it.getEntity(uuid) }.firstOrNull()

    private fun applyOnce(squadId: String, order: SquadOrder) {
        val squad = plugin.squadRegistry.getById(squadId) ?: return
        for (npc in plugin.squadRegistry.resolveLiveMembers(squad)) {
            if (!npc.isSpawned) continue
            plugin.npcTaskTracker.cancelFollow(npc.uniqueId)
            when (order) {
                is SquadOrder.HoldPosition -> {
                    npc.cancelNavigation()
                    npc.signal("SquadHold")
                }
                is SquadOrder.Idle -> {
                    npc.cancelNavigation()
                    npc.signal("SquadIdle")
                }
                else -> Unit
            }
        }
    }

    private fun applyTick(squadId: String) {
        val squad = plugin.squadRegistry.getById(squadId)
        if (squad == null) {
            cancelTask(squadId)
            orders.remove(squadId)
            return
        }
        // Resolve members live each tick — this is the auto-rebind: if a Mythic
        // NPC respawned with a fresh stableUniqueId, npcRegistry.all() finds it
        // again via its characterId.
        val liveMembers = plugin.squadRegistry.resolveLiveMembers(squad)

        when (val order = orders[squadId] ?: return) {
            is SquadOrder.MoveTo -> {
                val slots =
                    SquadFormationCalculator.slots(
                        anchor = order.location,
                        facingYaw = order.facingYaw,
                        memberCount = liveMembers.size,
                        formation = squad.formation,
                    )
                liveMembers.forEachIndexed { i, npc ->
                    if (!npc.isSpawned) return@forEachIndexed
                    val slotLoc = slots.getOrNull(i) ?: order.location
                    val loc = npc.location ?: return@forEachIndexed
                    if (loc.distanceSquared(slotLoc) > 4.0) {
                        plugin.npcTaskTracker.cancelFollow(npc.uniqueId)
                        npc.navigateTo(slotLoc)
                    }
                }
            }
            is SquadOrder.FollowPlayer -> {
                val player = Bukkit.getPlayer(order.playerUuid) ?: return
                val behind = player.location.direction.clone().multiply(-3.0)
                val anchor = player.location.clone().add(behind.x, 0.0, behind.z)
                val slots =
                    SquadFormationCalculator.slots(
                        anchor = anchor,
                        facingYaw = player.location.yaw,
                        memberCount = liveMembers.size,
                        formation = squad.formation,
                    )
                liveMembers.forEachIndexed { i, npc ->
                    if (!npc.isSpawned) return@forEachIndexed
                    val slotLoc = slots.getOrNull(i) ?: anchor
                    val loc = npc.location ?: return@forEachIndexed
                    if (loc.distanceSquared(slotLoc) > 4.0) {
                        plugin.npcTaskTracker.cancelFollow(npc.uniqueId)
                        npc.navigateTo(slotLoc)
                    }
                }
            }
            is SquadOrder.Engage -> {
                // Resolve target — try the hint type first, then fall back to the
                // other type. Citizens NPCs render as players client-side and
                // Mythic disguises do too, so isPlayer can be wrong.
                val targetEntity: org.bukkit.entity.Entity? =
                    when {
                        order.targetIsPlayer ->
                            Bukkit.getPlayer(order.targetUuid)
                                ?: (plugin.npcRegistry.getByClientFacingUuid(order.targetUuid)
                                    ?: plugin.npcRegistry.get(order.targetUuid))
                                    ?.entity
                                ?: findBukkitEntity(order.targetUuid)
                        else ->
                            (plugin.npcRegistry.getByClientFacingUuid(order.targetUuid)
                                ?: plugin.npcRegistry.get(order.targetUuid))
                                ?.entity
                                ?: Bukkit.getPlayer(order.targetUuid)
                                ?: findBukkitEntity(order.targetUuid)
                    }
                if (targetEntity == null) {
                    plugin.logger.warning("[SquadEngage] target ${order.targetUuid} (isPlayer=${order.targetIsPlayer}) not resolved")
                    return
                }
                for (npc in liveMembers) {
                    if (!npc.isSpawned) continue
                    plugin.npcTaskTracker.cancelFollow(npc.uniqueId)
                    // Mythic templates handle their own combat behavior via signals
                    // (melee charges, ranged stays still and shoots, shielders raise
                    // shield, etc). Citizens NPCs no-op on signal and fall through to
                    // the legacy attack/navigate path below.
                    npc.signal("SquadEngage", targetEntity)
                    // Legacy fallback for backends without signal support (Citizens):
                    if (npc !is com.canefe.story.npc.mythicmobs.MythicMobStoryNPC) {
                        if (targetEntity is org.bukkit.entity.Player) {
                            npc.attack(targetEntity)
                        } else {
                            npc.navigateTo(targetEntity)
                        }
                    }
                }
            }
            SquadOrder.HoldPosition, SquadOrder.Idle -> Unit
        }
    }
}
