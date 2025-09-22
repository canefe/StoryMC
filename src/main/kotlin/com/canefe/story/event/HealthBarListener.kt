package com.canefe.story.event

import kr.toxicity.healthbar.api.event.HealthBarCreateEvent
import kr.toxicity.healthbar.api.placeholder.PlaceholderContainer
import me.libraryaddict.disguise.DisguiseAPI
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

class HealthBarListener : Listener {
    @EventHandler
    fun onHealthbarCreated(event: HealthBarCreateEvent) {
        // if event entity is a player and vanished cancel
        if (event.entity.entity() is Player && isVanished(event.entity.entity() as Player)) {
            event.isCancelled = true
        }
    }

    fun onEnable() {
        PlaceholderContainer.STRING.addPlaceholder("disguise_name") { e ->
            val bukkitEntity = e.entity.entity()

            if (bukkitEntity !is Player) {
                return@addPlaceholder bukkitEntity.name
            }

            if (DisguiseAPI.isDisguised(bukkitEntity)) {
                var disguise = DisguiseAPI.getDisguise(bukkitEntity)

                if (disguise.isPlayerDisguise) {
                    disguise = disguise as PlayerDisguise
                } else {
                    val name = disguise.disguiseName
                    return@addPlaceholder name
                }

                disguise.name ?: bukkitEntity.name
            } else {
                bukkitEntity.name
            }
        }
    }

    private fun isVanished(player: Player): Boolean {
        for (meta in player.getMetadata("vanished")) {
            if (meta.asBoolean()) return true
        }
        return false
    }
}
