package com.canefe.story.bridge

import com.canefe.story.Story
import com.canefe.story.util.*
import net.citizensnpcs.api.CitizensAPI
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
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

        val attacker = (event.damager as? Projectile)?.shooter as? org.bukkit.entity.Entity ?: event.damager
        val attackerName = getEntityName(attacker)
        val victimName = getEntityName(victim)
        val attackerCharId = getCharacterId(attacker)
        val victimCharId = getCharacterId(victim)

        val combatDetails = PerceptionDetails.Combat(
            attacker = attackerName,
            victim = victimName,
            damage = event.finalDamage,
        )
        val participants = buildMap {
            attackerCharId?.let { put(attackerName, it) }
            victimCharId?.let { put(victimName, it) }
        }

        // Emit directly to the attacker — they always know who they hit.
        perceptionService.observeOne(
            characterName = attackerName,
            perceiverCharId = attackerCharId ?: attacker.uniqueId.toString(),
            entity = attacker,
            details = combatDetails,
            epicenter = victim.location,
            source = "combat",
            participants = participants,
        )

        // Everyone else nearby (victim included) perceives the combat.
        perceptionService.observe(
            details = combatDetails,
            epicenter = victim.location,
            source = "combat",
            exclude = attackerName,
            participants = participants,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val killer = entity.killer
        val deceasedName = getEntityName(entity)
        val killerName = killer?.let { getEntityName(it) }
        val participants = buildMap {
            getCharacterId(entity)?.let { put(deceasedName, it) }
            if (killer != null) getCharacterId(killer)?.let { put(killerName!!, it) }
        }

        perceptionService.observe(
            details = PerceptionDetails.Death(deceased = deceasedName, killer = killerName),
            epicenter = entity.location,
            source = "death",
            participants = participants,
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

    private fun getCharacterId(entity: org.bukkit.entity.Entity): String? {
        try {
            if (CitizensAPI.getNPCRegistry().isNPC(entity)) {
                val npc = CitizensAPI.getNPCRegistry().getNPC(entity)
                val storyNpc = plugin.npcRegistry.getByEntity(entity)
                    ?: return null
                return plugin.characterRegistry.getCharacterIdForNPC(storyNpc)
            }
        } catch (_: Exception) {
        }
        if (plugin.isNpcRegistryReady) {
            val storyNpc = plugin.npcRegistry.getByEntity(entity)
            if (storyNpc != null) return plugin.characterRegistry.getCharacterIdForNPC(storyNpc)
        }
        if (entity is Player) return entity.characterId
        return null
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
                entity.characterName
            } catch (_: Exception) {
                entity.name
            }
        }

        // MythicMob-backed StoryNPCs — use the clean display name from the registry
        if (plugin.isNpcRegistryReady) {
            val storyNpc = plugin.npcRegistry.getByEntity(entity)
            if (storyNpc != null) return storyNpc.name
        }

        return entity.name
    }
}
