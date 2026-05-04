package com.canefe.story.perception

import com.canefe.story.Story
import com.canefe.story.util.characterId
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent

/**
 * Listens for combat events and emits perception popups so players can see
 * when NPCs attack, are attacked, or witness a fight.
 */
class CombatPerceptionListener(private val plugin: Story) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        if (!plugin.isNpcRegistryReady) return
        val attacker = event.damager as? LivingEntity ?: return
        val victim = event.entity as? LivingEntity ?: return

        val attackerCharId = resolveCharId(attacker)
        val victimCharId = resolveCharId(victim)
        val attackerLabel = resolveLabel(attacker)
        val victimLabel = resolveLabel(victim)

        // NPC attacks someone
        resolveNpcUuid(attacker)?.let { npcUuid ->
            plugin.perceptionBroadcaster.broadcastPerceptionPopup(
                npcUuid, "→ $victimLabel", PerceptionBroadcaster.PopupType.COMBAT_ATTACK,
            )
        }

        // Someone attacks an NPC
        resolveNpcUuid(victim)?.let { npcUuid ->
            plugin.perceptionBroadcaster.broadcastPerceptionPopup(
                npcUuid, "← $attackerLabel", PerceptionBroadcaster.PopupType.COMBAT_ATTACKED,
            )
        }

        // Witnesses: NPCs that currently perceive either combatant
        val combatantIds = setOfNotNull(attackerCharId, victimCharId)
        val witnessLabel = "$attackerLabel ⚔ $victimLabel"
        for (npc in plugin.npcRegistry.all()) {
            val perceiverCharId = plugin.characterRegistry.getCharacterIdForNPC(npc) ?: continue
            // Skip the direct combatants — they already got their own popup
            if (perceiverCharId in combatantIds) continue
            val perceived = plugin.perceptionBroadcaster.perceivedBy(perceiverCharId)
            if (combatantIds.any { it in perceived }) {
                val npcUuid = npc.clientFacingUuid ?: (npc.entity?.uniqueId ?: continue)
                plugin.perceptionBroadcaster.broadcastPerceptionPopup(
                    npcUuid, witnessLabel, PerceptionBroadcaster.PopupType.COMBAT_ATTACK,
                )
            }
        }
    }

    private fun resolveNpcUuid(entity: LivingEntity): java.util.UUID? {
        val npc = plugin.npcRegistry.all().firstOrNull { it.entity?.uniqueId == entity.uniqueId } ?: return null
        return npc.clientFacingUuid ?: entity.uniqueId
    }

    private fun resolveCharId(entity: LivingEntity): String? =
        if (entity is Player) {
            try { entity.characterId } catch (_: Exception) { null }
        } else {
            plugin.npcRegistry.all().firstOrNull { it.entity?.uniqueId == entity.uniqueId }
                ?.let { plugin.characterRegistry.getCharacterIdForNPC(it) }
        }

    private fun resolveLabel(entity: LivingEntity): String =
        if (entity is Player) entity.name
        else plugin.npcRegistry.all().firstOrNull { it.entity?.uniqueId == entity.uniqueId }?.name ?: entity.name
}
