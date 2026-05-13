package com.canefe.story.combat.ai

import com.canefe.story.combat.CombatState
import com.canefe.story.combat.Combatant
import com.canefe.story.combat.DirectionalCombatService
import com.canefe.story.combat.SwingDir
import kotlin.random.Random

/**
 * Per-NPC combat decision loop per spec §7. Ticks every 2-4 ticks (phase
 * offset = `entityId mod 4` to keep two NPCs from deciding same-tick and
 * mutually parrying).
 *
 * Reads target's [CombatState] from the registry to choose ATTACK vs DEFEND.
 * All math is delegated to [DecisionWeights] — this class only resolves
 * "should I act now?" and shapes the call into the [DirectionalCombatService]
 * queue.
 *
 * Range, disengage, and target acquisition stay with sim — this brain only
 * runs while sim's strategic mode is `fight` (caller checks before ticking).
 */
class CombatBrain(
    private val self: Combatant,
    private val service: DirectionalCombatService,
    private val combatSkill: Double,
    private val personality: Personality = Personality.DEFAULT,
    private val random: Random = Random.Default,
) {
    /** Phase offset within the 4-tick decision cycle. */
    val phaseOffset: Int = self.entityId.mod(DECISION_PERIOD)

    /** Tick at which the brain may next initiate an action from Idle. */
    private var nextActionTick: Int = 0
    private var lastObservedTick: Int = 0

    fun shouldTickNow(globalTick: Int): Boolean = (globalTick.mod(DECISION_PERIOD)) == phaseOffset

    fun tick(target: Combatant?) {
        if (target == null) return
        val targetState = target.currentState()
        lastObservedTick += DECISION_PERIOD

        if (!withinEngagementRange(target)) return

        when (val s = self.currentState()) {
            CombatState.Idle -> {
                if (lastObservedTick < nextActionTick) return
                idleDecision(target, targetState)
            }
            is CombatState.Windup -> windupDecision(s, target, targetState)
            else -> Unit // committed to swing/recover/block/stagger — let the state machine carry it
        }
    }

    private fun withinEngagementRange(target: Combatant): Boolean {
        val a = try { self.eyeLocation() } catch (_: Throwable) { return false }
        val b = try { target.eyeLocation() } catch (_: Throwable) { return false }
        if (a.world == null || a.world != b.world) return false
        return a.distanceSquared(b) <= ENGAGE_RANGE_SQ
    }

    private fun scheduleCooldown(extraTicks: Int) {
        nextActionTick = lastObservedTick + extraTicks
    }

    private fun idleDecision(
        target: Combatant,
        targetState: CombatState,
    ) {
        when (targetState) {
            is CombatState.Windup -> defendDecision(targetState)
            is CombatState.Active, is CombatState.Recovery, is CombatState.Staggered -> attackDecision(target, null)
            CombatState.Idle, is CombatState.Blocking -> attackDecision(target, targetState as? CombatState.Blocking)
        }
    }

    private fun attackDecision(
        target: Combatant,
        targetBlocking: CombatState.Blocking?,
    ) {
        val pSmart = DecisionWeights.pAttackUnguarded(combatSkill)
        val dir =
            if (targetBlocking != null && random.nextDouble() < pSmart) {
                pickUnguarded(targetBlocking.dir)
            } else {
                personality.preferredDirection ?: SwingDir.entries.random(random)
            }
        service.queueSwing(self, dir)
        scheduleCooldown(POST_SWING_COOLDOWN_TICKS)
    }

    private fun defendDecision(attackerWindup: CombatState.Windup) {
        val pParry = DecisionWeights.pParry(combatSkill)
        val readAccurate = random.nextDouble() < DecisionWeights.directionReadAccuracy(combatSkill)
        val guessDir =
            if (readAccurate) attackerWindup.dir else SwingDir.entries.random(random)
        service.queueBlock(self, guessDir, pressed = true)
        scheduleCooldown(POST_BLOCK_COOLDOWN_TICKS)
        // The state machine drains the parry window each tick; whether this
        // block lands inside or after the window is purely a function of how
        // soon the attacker reaches Active. P(parry) above is mostly a knob
        // for *whether* the brain commits early enough — for v1 we always
        // engage block; high combat-skill brains naturally land in the window
        // because they react earlier (DECISION_PERIOD phase offset).
        if (pParry < 0.001) Unit // suppress unused-variable warning while keeping the formula visible
    }

    private fun windupDecision(
        windup: CombatState.Windup,
        target: Combatant,
        targetState: CombatState,
    ) {
        if (!windup.canFeint) return
        // Only feint when the target is *also* committed (their windup) — otherwise
        // the NPC just cancels its own swings constantly and never lands a hit.
        if (targetState !is CombatState.Windup && targetState !is CombatState.Blocking) return
        val pressure = if (target.stamina() < target.maxStamina() * 0.25) 1.5 else 1.0
        val pFeint = DecisionWeights.pFeint(combatSkill, pressure) * personality.feintPropensity
        if (random.nextDouble() < pFeint) service.queueFeint(self)
    }

    private fun pickUnguarded(guarded: SwingDir): SwingDir {
        val candidates = SwingDir.entries.filter { it != guarded }
        return candidates.random(random)
    }

    companion object {
        const val DECISION_PERIOD = 4

        /** Idle delay after a swing finishes before the brain may act again (~1.0s @ 20 tps). */
        const val POST_SWING_COOLDOWN_TICKS = 20

        /** Idle delay after dropping a block before the brain may act again (~0.6s). */
        const val POST_BLOCK_COOLDOWN_TICKS = 12

        /** Engage radius (blocks) — beyond this the brain holds fire and lets sim/AI close the gap. */
        const val ENGAGE_RANGE: Double = 3.5

        /** Squared engage radius for cheap distance compare. */
        const val ENGAGE_RANGE_SQ: Double = ENGAGE_RANGE * ENGAGE_RANGE
    }
}
