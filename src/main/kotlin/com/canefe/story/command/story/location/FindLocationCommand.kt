package com.canefe.story.command.story.location

import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.DoubleArgument
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

class FindLocationCommand(private val commandUtils: LocationCommandUtils) {
	fun getCommand(): CommandAPICommand {
		return CommandAPICommand("find")
			.withOptionalArguments(DoubleArgument("radius"))
			.withUsage("/story location find [radius]")
			.executesPlayer(
				PlayerCommandExecutor { player, args ->
					val radius = (args["radius"] as? Double) ?: 32.0
					val playerLocation = player.location

					// Get all locations within radius
					val nearbyLocations = commandUtils.locationManager.getAllLocations()
						.filter { location ->
							val bukkitLocation = location.bukkitLocation
							bukkitLocation != null &&
								bukkitLocation.world == playerLocation.world &&
								bukkitLocation.distance(playerLocation) <= radius
						}
						.sortedBy { it.bukkitLocation?.distance(playerLocation) ?: Double.MAX_VALUE }

					if (nearbyLocations.isEmpty()) {
						player.sendError("No story locations found within $radius blocks.")
						return@PlayerCommandExecutor
					}

					// Send header
					player.sendMessage(
						commandUtils.mm.deserialize(
							"<gold><bold>Story Locations within $radius blocks:</bold></gold>",
						),
					)

					// Send each location with distance and details
					nearbyLocations.forEach { location ->
						val distance = location.bukkitLocation?.distance(playerLocation)?.toInt() ?: 0
						val locationType = if (location.isSubLocation) "Sub-location" else "Location"
						val parentInfo = if (location.hasParent()) " <gray>(parent: ${location.parentLocationName})</gray>" else ""

						val message = Component.text()
							.append(Component.text("• ", NamedTextColor.YELLOW))
							.append(Component.text(location.name, NamedTextColor.GOLD))
							.append(Component.text(" - ", NamedTextColor.GRAY))
							.append(Component.text("${distance}m", NamedTextColor.GREEN))
							.append(Component.text(" [", NamedTextColor.GRAY))
							.append(Component.text(locationType, NamedTextColor.AQUA))
							.append(Component.text("]", NamedTextColor.GRAY))

						if (parentInfo.isNotEmpty()) {
							message.append(commandUtils.mm.deserialize(parentInfo))
						}

						player.sendMessage(message.build())

						// Show allowed NPCs if any
						if (location.allowedNPCs.isNotEmpty()) {
							player.sendMessage(
								commandUtils.mm.deserialize(
									"  <dark_gray>Allowed NPCs: <gray>${location.allowedNPCs.joinToString(", ")}</gray></dark_gray>",
								),
							)
						}
					}

					player.sendSuccess("Found ${nearbyLocations.size} story location(s) within $radius blocks.")
				},
			)
	}
}
