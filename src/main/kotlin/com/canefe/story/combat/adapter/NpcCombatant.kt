package com.canefe.story.combat.adapter

import com.canefe.story.api.StoryNPC
import com.canefe.story.combat.CombatState
import com.canefe.story.combat.Combatant
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import java.util.UUID

/**
 * Adapter wrapping a [StoryNPC] as a [Combatant]. Bypasses
 * [StoryNPC.attack] entirely (which would cast a MythicMob skill); damage and
 * targeting go through the directional combat path.
 */
class NpcCombatant(
    private val npc: StoryNPC,
    initialMaxStamina: Float = PlayerCombatant.DEFAULT_MAX_STAMINA,
) : Combatant {
    @Volatile private var state: CombatState = CombatState.Idle
    @Volatile private var lastSignalledClass: String? = null

    @Volatile private var stamina: Float = initialMaxStamina

    @Volatile private var maxStamina: Float = initialMaxStamina

    override val entityId: Int
        get() = npc.entity?.entityId ?: -1

    override val uniqueId: UUID
        get() = npc.uniqueId

    override fun facingYaw(): Float = npc.location?.yaw ?: 0f

    override fun eyeLocation(): Location {
        val living = npc.entity as? LivingEntity
        return living?.eyeLocation ?: (npc.location ?: error("npc not spawned"))
    }

    override fun stamina(): Float = stamina

    override fun maxStamina(): Float = maxStamina

    fun setMaxStamina(value: Float) {
        maxStamina = value
        if (stamina > value) stamina = value
    }

    override fun applyStaminaDelta(delta: Float) {
        stamina = (stamina + delta).coerceIn(0f, maxStamina)
    }

    override fun takeDamage(
        amount: Double,
        attacker: Combatant?,
    ) {
        val living = npc.entity as? LivingEntity ?: return
        if (living.isDead) return
        living.damage(amount)
    }

    override fun currentState(): CombatState = state

    override fun transitionTo(state: CombatState) {
        this.state = state
        onPoseChange(state)
        emitMythicSignal(state)
    }

    /**
     * Emit a MythicMobs signal per state transition so mob templates can
     * author the presentation (animations, sounds, particles, taunts) via
     * `~onSignal:Combat<...>` skill triggers. The brain decides the mechanics;
     * the mob YAML decides how it looks.
     *
     * Signals fire only on state-class changes (not per-tick `copy` updates),
     * so a Windup→Windup ticksLeft decrement does not re-trigger.
     */
    private fun emitMythicSignal(state: CombatState) {
        val signal =
            when (state) {
                CombatState.Idle -> "CombatIdle"
                is CombatState.Windup -> "CombatWindup${state.dir.name.lowercase().replaceFirstChar { it.uppercase() }}"
                is CombatState.Active -> "CombatActive${state.dir.name.lowercase().replaceFirstChar { it.uppercase() }}"
                is CombatState.Recovery -> "CombatRecovery"
                is CombatState.Blocking -> "CombatBlock${state.dir.name.lowercase().replaceFirstChar { it.uppercase() }}"
                is CombatState.Staggered -> "CombatStagger"
            }
        if (signal == lastSignalledClass) return
        lastSignalledClass = signal
        try {
            npc.signal(signal, null)
        } catch (_: Throwable) {
            // Signal dispatch must never break the combat tick loop.
        }
    }
}
