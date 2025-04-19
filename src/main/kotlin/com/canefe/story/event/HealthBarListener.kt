package com.canefe.story.event

import kr.toxicity.healthbar.api.event.HealthBarCreateEvent
import me.libraryaddict.disguise.DisguiseAPI
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

		// if event entity is a player and lib disguised then return disguise name
		if (DisguiseAPI.isDisguised(event.entity.entity())) {
			// there is no setCustomName method in HealthBarCreateEvent what do I do?
			if (event.entity.entity() is Player) {
				event.isCancelled = true
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
