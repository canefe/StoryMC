package com.canefe.story.combat.listener

import com.canefe.story.Story
import com.canefe.story.combat.DirectionalCombatService
import com.canefe.story.combat.DirectionalDamageFlag
import com.canefe.story.combat.SwingDir
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause

/**
 * Cancels vanilla melee swings when directional combat is enabled, and routes
 * the swing through [DirectionalCombatService] instead. The
 * [DirectionalDamageFlag] thread-local lets our own damage application
 * re-enter [EntityDamageByEntityEvent] without being cancelled here.
 *
 * Coexists with [com.canefe.story.perception.CombatPerceptionListener]: that
 * listener runs at MONITOR priority with `ignoreCancelled = true`, so a
 * cancelled vanilla swing won't generate a perception popup. Phase 2 emits
 * the popup explicitly when our own damage lands (TODO: wire in Phase 3
 * stimulus pipeline).
 */
class VanillaMeleeListener(
    private val plugin: Story,
    private val service: DirectionalCombatService,
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMelee(event: EntityDamageByEntityEvent) {
        if (!plugin.configService.combatEnabled) return
        if (DirectionalDamageFlag.isApplying()) return
        if (event.cause != DamageCause.ENTITY_ATTACK) return

        val damager = event.damager as? LivingEntity ?: return
        val combatant = service.registry.byEntityId(damager.entityId)
            ?: service.registry.byUuid(damager.uniqueId)
            ?: run {
                // Lazily register Players so vanilla LMB works even on first hit.
                if (damager is Player) service.registerPlayer(damager) else null
            }
            ?: return

        event.isCancelled = true
        service.queueSwing(combatant, SwingDir.THRUST)
    }
}
