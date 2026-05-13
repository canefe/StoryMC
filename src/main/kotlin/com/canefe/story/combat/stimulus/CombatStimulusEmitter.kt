package com.canefe.story.combat.stimulus

import com.canefe.story.Story
import com.canefe.story.bridge.CombatHitOutcomeEvent
import com.canefe.story.combat.Combatant
import com.canefe.story.combat.SwingDir
import com.canefe.story.combat.adapter.NpcCombatant
import com.canefe.story.combat.adapter.PlayerCombatant
import com.canefe.story.combat.resolution.HitOutcome
import com.canefe.story.util.characterId
import org.bukkit.entity.Player

/**
 * Subscribes to [com.canefe.story.combat.DirectionalCombatService.onHitOutcome]
 * and forwards each event to the [com.canefe.story.bridge.StoryEventBus] as a
 * [CombatHitOutcomeEvent], which is then serialized to story-go (and from
 * there to story-sim).
 *
 * This is the plugin half of spec §6 outcome stimuli — sim-side consumers /
 * the new `MeleeHitLanded` / `MeleeParried` / etc. stimulus variants will be
 * registered against the `combat.hit_outcome` event type.
 */
class CombatStimulusEmitter(private val plugin: Story) {
    fun emit(
        attacker: Combatant,
        defender: Combatant,
        dir: SwingDir,
        outcome: HitOutcome,
        damage: Double,
    ) {
        val event =
            CombatHitOutcomeEvent(
                attackerCharId = combatantCharId(attacker),
                defenderCharId = combatantCharId(defender),
                direction = dir.name.lowercase(),
                outcome = outcomeWire(outcome),
                damage = damage,
            )
        plugin.eventBus.emit(event)
    }

    private fun combatantCharId(c: Combatant): String? =
        when (c) {
            is PlayerCombatant -> {
                val p = plugin.server.getPlayer(c.uniqueId)
                (p as? Player)?.characterId
            }
            is NpcCombatant -> {
                // The NpcCombatant doesn't expose StoryNPC directly; resolve by uniqueId.
                val npc = plugin.npcRegistry.get(c.uniqueId)
                npc?.let { plugin.characterRegistry.getCharacterIdForNPC(it) }
            }
            else -> null
        }

    private fun outcomeWire(outcome: HitOutcome): String =
        when (outcome) {
            HitOutcome.Unblocked -> "unblocked"
            HitOutcome.Parry -> "parry"
            HitOutcome.PerfectBlock -> "perfect_block"
            HitOutcome.PartialBlock -> "partial_block"
            HitOutcome.BadBlock -> "bad_block"
        }
}
