package com.canefe.story.bridge

import com.canefe.story.Story
import com.canefe.story.util.EssentialsUtils
import net.citizensnpcs.api.CitizensAPI
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.weather.WeatherChangeEvent

/**
 * Listens to Bukkit world events and feeds them to [PerceptionService].
 * Each event becomes a [PerceptionEvent] for nearby characters.
 */
class PerceptionListener(
    private val plugin: Story,
    private val perceptionService: PerceptionService,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val victim = event.entity
        if (victim !is LivingEntity) return

        val attackerName = getEntityName(event.damager)
        val victimName = getEntityName(victim)

        perceptionService.observe(
            details =
                PerceptionDetails.Combat(
                    attacker = attackerName,
                    victim = victimName,
                    damage = event.finalDamage,
                ),
            epicenter = victim.location,
            source = "combat",
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val killerName = entity.killer?.let { getEntityName(it) }

        perceptionService.observe(
            details =
                PerceptionDetails.Death(
                    deceased = getEntityName(entity),
                    killer = killerName,
                ),
            epicenter = entity.location,
            source = "death",
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWeatherChange(event: WeatherChangeEvent) {
        val world = event.world
        val weather = if (event.toWeatherState()) "rain" else "clear"

        // Observe at the first online player's location in that world
        val player = world.players.firstOrNull() ?: return
        perceptionService.observe(
            details = PerceptionDetails.Weather(state = weather),
            epicenter = player.location,
            source = "weather",
        )
    }

    private fun getEntityName(entity: org.bukkit.entity.Entity): String {
        // Check Citizens NPC first — they implement Player but aren't real players
        try {
            if (CitizensAPI.getNPCRegistry().isNPC(entity)) {
                return CitizensAPI.getNPCRegistry().getNPC(entity).name
            }
        } catch (_: Exception) {
        }

        if (entity is Player) {
            return try {
                EssentialsUtils.getNickname(entity.name)
            } catch (_: Exception) {
                entity.name
            }
        }

        return entity.name
    }
}
