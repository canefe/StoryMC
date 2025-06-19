package com.canefe.story.command.story.npc

import com.canefe.story.Story
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import de.myzelyam.api.vanish.VanishAPI
import me.libraryaddict.disguise.DisguiseAPI
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.SkinTrait
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent

class DisguiseUtil(
	private val plugin: Story,
) {
	fun disguisePlayer(
		player: Player,
		npc: NPC,
	) {
		if (npc != null) {
			val skin = npc.getOrAddTrait(SkinTrait::class.java)
			val texture = skin.texture
			val signature = skin.signature

			// Create JSON with the actual texture and signature from the NPC
			val userProfile =
				@Suppress("ktlint:standard:max-line-length")
				"{\"uuid\":\"9bd053db-62fe-4bd9-a563-b36d9f0de7c9\",\"name\":\"Unknown\",\"textureProperties\":[{\"name\":\"textures\",\"value\":\"$texture\",\"signature\":\"$signature\"}]}"

			val playerDisguise = PlayerDisguise(npc.name)
			player.sendInfo("You are now disguised as ${npc.name}.")
			playerDisguise.skin = userProfile
			playerDisguise.setViewSelfDisguise(false)
			playerDisguise.setNameVisible(false)
			playerDisguise.entity = player
			playerDisguise.startDisguise()

			// despawn npc, put the player in the same location as the npc
			if (!npc.isSpawned) {
				npc.spawn(player.location)
			}
			player.teleport(npc.entity.location, PlayerTeleportEvent.TeleportCause.PLUGIN)
			npc.despawn()
			// unvanish the player
			VanishAPI
				.showPlayer(player)
			// Register this disguise in the manager
			plugin.disguiseManager
				.registerDisguise(player, npc)
		} else {
			player.sendError("NPC not found.")
		}
	}

	fun undisguisePlayer(player: Player) {
		val disguisedNPC = plugin.disguiseManager.getImitatedNPC(player)
		if (disguisedNPC != null) {
			DisguiseAPI.undisguiseToAll(player)
			plugin.disguiseManager.removeDisguise(player)
			// remove holograms
			eu.decentsoftware.holograms.api.DHAPI
				.removeHologram(player.uniqueId.toString())
			VanishAPI.hidePlayer(player)
			disguisedNPC.teleport(player.location, PlayerTeleportEvent.TeleportCause.PLUGIN)
			disguisedNPC.spawn(player.location)
			player.sendInfo("You are no longer disguised as ${disguisedNPC.name}.")
		} else {
			player.sendError("You are not disguised as any NPC.")
		}
	}
}
