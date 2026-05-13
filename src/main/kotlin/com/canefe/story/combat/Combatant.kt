package com.canefe.story.combat

import org.bukkit.Location
import java.util.UUID

/**
 * Single seam the directional combat system speaks to. Players and NPCs both
 * implement this so HitDetector / DamageResolver / state machine never branch
 * on actor type.
 */
interface Combatant {
    val entityId: Int
    val uniqueId: UUID

    fun facingYaw(): Float

    fun eyeLocation(): Location

    fun stamina(): Float

    fun maxStamina(): Float

    fun applyStaminaDelta(delta: Float)

    /** Combat skill in [0,1]. Defaults to mid (0.5). */
    fun combatSkill(): Double = 0.5

    /** Strength in [0,1]. Defaults to mid (0.5). */
    fun strength(): Double = 0.5

    fun takeDamage(
        amount: Double,
        attacker: Combatant?,
    )

    fun currentState(): CombatState

    fun transitionTo(state: CombatState)

    fun onPoseChange(state: CombatState) {}
}
