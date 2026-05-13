package com.canefe.story.combat

import com.canefe.story.Story
import com.canefe.story.combat.adapter.CombatantRegistry
import com.canefe.story.combat.adapter.PlayerCombatant
import com.canefe.story.combat.ai.CombatBrain
import com.canefe.story.combat.ai.Personality
import com.canefe.story.combat.adapter.NpcCombatant
import com.canefe.story.combat.resolution.DamageResolver
import com.canefe.story.combat.resolution.HitDetector
import com.canefe.story.combat.resolution.HitOutcome
import com.canefe.story.combat.resolution.StaminaService
import com.canefe.story.combat.resolution.WeaponClass
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask

/**
 * Server-authoritative state machine for the directional combat system.
 * Phase 2: drives Idle → Windup → Active → Recovery, runs hit detection at
 * each Active tick, drains/regens stamina, applies directional damage via
 * [DirectionalDamageFlag] so [com.canefe.story.combat.listener.VanillaMeleeListener]
 * doesn't re-cancel.
 *
 * Stats sourcing is Phase 3; for now stats default to mid (0.5).
 */
class DirectionalCombatService(
    private val plugin: Story,
    val registry: CombatantRegistry,
) {
    private var task: BukkitTask? = null
    private var globalTick: Int = 0
    private val brains = java.util.concurrent.ConcurrentHashMap<Int, CombatBrain>()
    private val brainTargets = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /**
     * Optional listener invoked after every state transition. The packet
     * bridge subscribes here to push [CombatState] updates to clients.
     */
    var onStateChange: ((Combatant, CombatState) -> Unit)? = null

    /**
     * Optional listener invoked when a swing resolves into a hit outcome
     * (Unblocked / PerfectBlock / etc). Bridge / stimulus emitter subscribe.
     */
    var onHitOutcome: ((attacker: Combatant, defender: Combatant, dir: SwingDir, outcome: HitOutcome, damage: Double) -> Unit)? = null

    fun start() {
        if (task != null) return
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    /** Lazily wraps a Bukkit [Player] as a [PlayerCombatant] and registers it. */
    fun registerPlayer(player: Player): PlayerCombatant {
        val existing = registry.byUuid(player.uniqueId) as? PlayerCombatant
        if (existing != null) return existing
        val pc = PlayerCombatant(player)
        registry.register(pc)
        return pc
    }

    fun queueSwing(
        attacker: Combatant,
        dir: SwingDir,
    ) { tryQueueSwing(attacker, dir) }

    /** Returns true if the swing was accepted, false if rejected (state/stamina). */
    fun tryQueueSwing(
        attacker: Combatant,
        dir: SwingDir,
    ): Boolean {
        if (attacker.currentState() !is CombatState.Idle) return false
        if (!StaminaService.canSwing(attacker)) return false
        attacker.applyStaminaDelta(-StaminaService.SWING_COMMIT_COST)
        transition(
            attacker,
            CombatState.Windup(
                dir = dir,
                ticksLeft = plugin.configService.combatWindupBaseTicks,
                canFeint = true,
            ),
        )
        return true
    }

    fun queueBlock(
        defender: Combatant,
        dir: SwingDir,
        pressed: Boolean,
    ) {
        if (!pressed) {
            if (defender.currentState() is CombatState.Blocking) transition(defender, CombatState.Idle)
            return
        }
        if (defender.currentState() !is CombatState.Idle) return
        transition(
            defender,
            CombatState.Blocking(
                dir = dir,
                parryWindowTicksLeft = plugin.configService.combatParryWindowBaseTicks,
            ),
        )
    }

    fun queueDirectionSwitch(
        attacker: Combatant,
        newDir: SwingDir,
    ) { tryQueueDirectionSwitch(attacker, newDir) }

    fun tryQueueDirectionSwitch(
        attacker: Combatant,
        newDir: SwingDir,
    ): Boolean {
        val w = attacker.currentState() as? CombatState.Windup ?: return false
        val halfWindup = plugin.configService.combatWindupBaseTicks / 2
        if (w.ticksLeft < halfWindup) return false // past the switch window
        if (attacker.stamina() < StaminaService.DIRECTION_SWITCH_COST) return false
        attacker.applyStaminaDelta(-StaminaService.DIRECTION_SWITCH_COST)
        transition(attacker, w.copy(dir = newDir))
        return true
    }

    fun queueFeint(attacker: Combatant) { tryQueueFeint(attacker) }

    fun tryQueueFeint(attacker: Combatant): Boolean {
        val w = attacker.currentState() as? CombatState.Windup ?: return false
        if (!w.canFeint) return false
        attacker.applyStaminaDelta(StaminaService.SWING_COMMIT_COST) // refund
        transition(attacker, CombatState.Recovery(ticksLeft = RECOVERY_BASE_TICKS))
        return true
    }

    /**
     * Attach (or replace) a [CombatBrain] for an NPC combatant. `targetEntityId`
     * is the brain's current opponent (looked up via the registry each tick).
     * Pass `null` for [targetEntityId] (or call [detachBrain]) to deactivate.
     */
    fun attachBrain(
        combatantEntityId: Int,
        brain: CombatBrain,
        targetEntityId: Int?,
    ) {
        brains[combatantEntityId] = brain
        if (targetEntityId != null) brainTargets[combatantEntityId] = targetEntityId else brainTargets.remove(combatantEntityId)
    }

    fun detachBrain(combatantEntityId: Int) {
        brains.remove(combatantEntityId)
        brainTargets.remove(combatantEntityId)
    }

    private fun tick() {
        globalTick++
        val all = registry.all()
        for (c in all) advanceState(c)
        StaminaService.tick(all)
        if (brains.isNotEmpty()) tickBrains()
    }

    private fun tickBrains() {
        for ((entityId, brain) in brains) {
            if (!brain.shouldTickNow(globalTick)) continue
            val targetId = brainTargets[entityId] ?: continue
            val target = registry.byEntityId(targetId) ?: continue
            brain.tick(target)
        }
    }

    private fun advanceState(c: Combatant) {
        when (val s = c.currentState()) {
            CombatState.Idle -> Unit
            is CombatState.Windup -> {
                val next = s.ticksLeft - 1
                if (next <= 0) {
                    transition(c, CombatState.Active(s.dir, ticksLeft = ACTIVE_BASE_TICKS))
                } else {
                    val halfWindup = plugin.configService.combatWindupBaseTicks / 2
                    transition(c, s.copy(ticksLeft = next, canFeint = next >= halfWindup))
                }
            }
            is CombatState.Active -> {
                resolveActiveTick(c, s.dir)
                val next = s.ticksLeft - 1
                if (next <= 0) {
                    transition(c, CombatState.Recovery(ticksLeft = RECOVERY_BASE_TICKS))
                } else {
                    transition(c, s.copy(ticksLeft = next))
                }
            }
            is CombatState.Recovery -> {
                val next = s.ticksLeft - 1
                if (next <= 0) transition(c, CombatState.Idle) else transition(c, s.copy(ticksLeft = next))
            }
            is CombatState.Blocking -> {
                if (c.stamina() <= 0f) {
                    transition(c, CombatState.Idle)
                } else if (s.parryWindowTicksLeft > 0) {
                    transition(c, s.copy(parryWindowTicksLeft = s.parryWindowTicksLeft - 1))
                }
            }
            is CombatState.Staggered -> {
                val next = s.ticksLeft - 1
                if (next <= 0) transition(c, CombatState.Idle) else transition(c, s.copy(ticksLeft = next))
            }
        }
    }

    private fun transition(
        combatant: Combatant,
        state: CombatState,
    ) {
        val prior = combatant.currentState()
        combatant.transitionTo(state)
        // Only fire the listener on actual changes — the per-tick `copy` calls
        // produce a lot of equal-but-different instances we don't want to spam.
        if (prior != state) onStateChange?.invoke(combatant, state)
    }

    private fun resolveActiveTick(
        attacker: Combatant,
        dir: SwingDir,
    ) {
        val weapon = WeaponClass.SWORD // Phase 5/§11: drive from item config
        ensureNearbyNpcsRegistered(attacker, weapon)
        val hits = HitDetector.detect(attacker, dir, weapon, registry.all())
        for (defender in hits) {
            val outcome = DamageResolver.resolve(defender.currentState(), dir)
            val damage =
                DamageResolver.damage(
                    outcome,
                    weaponDmg = weapon.baseDamage,
                    attackerStrength = attacker.strength(),
                    attackerCombatSkill = attacker.combatSkill(),
                    defenderCombatSkill = defender.combatSkill(),
                )
            defender.applyStaminaDelta(-DamageResolver.defenderStaminaDrain(outcome))
            if (damage > 0.0) {
                DirectionalDamageFlag.applying { defender.takeDamage(damage, attacker) }
            }
            if (outcome == HitOutcome.Parry) {
                transition(attacker, CombatState.Staggered(ticksLeft = STAGGER_BASE_TICKS))
            }
            onHitOutcome?.invoke(attacker, defender, dir, outcome, damage)
        }
    }

    /**
     * Lazily wrap any nearby StoryNPC entities as [com.canefe.story.combat.adapter.NpcCombatant]
     * and register them, so the player can hit NPCs that haven't yet swung at us
     * (which is the only other path that populates the registry today).
     */
    private fun ensureNearbyNpcsRegistered(attacker: Combatant, weapon: WeaponClass) {
        val origin = try { attacker.eyeLocation() } catch (_: Throwable) { return }
        val world = origin.world ?: return
        val r = weapon.reach + 1.0
        for (entity in world.getNearbyEntities(origin, r, r, r)) {
            if (entity !is org.bukkit.entity.LivingEntity) continue
            if (entity.entityId == attacker.entityId) continue
            if (registry.byEntityId(entity.entityId) != null) continue
            val npc = plugin.npcRegistry.getByEntity(entity) ?: continue
            val combatant = NpcCombatant(npc)
            registry.register(combatant)
            if (brains[combatant.entityId] == null) {
                val brain = CombatBrain(
                    self = combatant,
                    service = this,
                    combatSkill = 0.5,
                    personality = Personality.DEFAULT,
                )
                attachBrain(combatant.entityId, brain, attacker.entityId)
                plugin.logger.info("[combat] attached brain to NPC ${npc.name} entityId=${entity.entityId} target=${attacker.entityId}")
            }
            plugin.logger.info("[combat] auto-registered NPC ${npc.name} entityId=${entity.entityId}")
        }
    }

    companion object {
        const val ACTIVE_BASE_TICKS = 3
        const val RECOVERY_BASE_TICKS = 8
        const val STAGGER_BASE_TICKS = 14
        const val DEFAULT_STAT = 0.5
    }
}
