package com.canefe.story.npc.squad

import org.bukkit.Location
import java.util.UUID

/**
 * What a squad is currently being told to do. The [SquadOrderTracker] reads
 * this every tick and re-issues the appropriate per-NPC navigation.
 */
sealed class SquadOrder {
    /** Idle. NPCs are not directed by the tracker — Mythic / Citizens AI takes over. */
    data object Idle : SquadOrder()

    /**
     * Walk to a fixed location in formation.
     * [facingYaw] is the commander's yaw at order time, used to orient the formation.
     */
    data class MoveTo(val location: Location, val facingYaw: Float) : SquadOrder()

    /** Stop moving and stay where each NPC currently is. */
    data object HoldPosition : SquadOrder()

    /**
     * Follow a player by UUID, in formation around them.
     * [facingYaw] not used here — follow uses the player's own yaw each tick.
     */
    data class FollowPlayer(val playerUuid: UUID) : SquadOrder()

    /** Engage a target NPC (by stable UUID) or a player. */
    data class Engage(val targetUuid: UUID, val targetIsPlayer: Boolean) : SquadOrder()
}
