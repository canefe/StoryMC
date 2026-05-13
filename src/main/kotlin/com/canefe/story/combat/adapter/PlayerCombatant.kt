package com.canefe.story.combat.adapter

import com.canefe.story.combat.CombatState
import com.canefe.story.combat.Combatant
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Adapter wrapping a Bukkit [Player] as a [Combatant]. Stamina and state are
 * tracked per-player here; damage routes through Bukkit's damage path with the
 * "applying directional damage" thread-local flag set by DirectionalCombatService.
 */
class PlayerCombatant(
    private val player: Player,
    initialMaxStamina: Float = DEFAULT_MAX_STAMINA,
) : Combatant {
    @Volatile private var state: CombatState = CombatState.Idle

    @Volatile private var stamina: Float = initialMaxStamina

    @Volatile private var maxStamina: Float = initialMaxStamina

    override val entityId: Int get() = player.entityId
    override val uniqueId: UUID get() = player.uniqueId

    override fun facingYaw(): Float = player.location.yaw

    override fun eyeLocation(): Location = player.eyeLocation

    override fun stamina(): Float = stamina

    override fun maxStamina(): Float = maxStamina

    fun setMaxStamina(value: Float) {
        maxStamina = value
        if (stamina > value) stamina = value
    }

    override fun applyStaminaDelta(delta: Float) {
        stamina = (stamina + delta).coerceIn(0f, maxStamina)
    }

    // combatSkill() / strength() inherit the 0.5 default. Future: route through
    // SkillManager / MMOCore once a normalized [0,1] adapter exists.

    override fun takeDamage(
        amount: Double,
        attacker: Combatant?,
    ) {
        // Caller wraps this in DirectionalDamageFlag.applying { ... } so
        // VanillaMeleeListener won't re-cancel the resulting damage event.
        if (player.isDead) return
        player.damage(amount)
    }

    override fun currentState(): CombatState = state

    override fun transitionTo(state: CombatState) {
        this.state = state
        onPoseChange(state)
    }

    companion object {
        const val DEFAULT_MAX_STAMINA = 100f
    }
}
