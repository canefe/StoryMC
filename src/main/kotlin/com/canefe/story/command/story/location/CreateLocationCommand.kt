package com.canefe.story.command.story.location

import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.arguments.TextArgument
import dev.jorel.commandapi.executors.ConsoleCommandExecutor
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import org.bukkit.Location

class CreateLocationCommand(
    private val commandUtils: LocationCommandUtils,
) {
    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("create")
            // usage: /story location create <location_name> [context]
            .withArguments(TextArgument("location_name"))
            .withOptionalArguments(GreedyStringArgument("context"))
            .withUsage(
                "/story location create <location_name>",
            ).executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val locationName = args["location_name"] as String
                    val playerLocation: Location = player.location

                    val location =
                        commandUtils.locationManager.createLocation(
                            locationName,
                            playerLocation,
                        )
                            ?: run {
                                player.sendError(
                                    "Failed to create location. The location name may already exist.",
                                )
                                return@PlayerCommandExecutor
                            }

                    // Save initial location (createLocation already saves, but ensure
                    // persisted)
                    commandUtils.locationManager.saveLocation(location)

                    player.sendSuccess(
                        "Location <gold>'$locationName'</gold> created successfully at your current location.",
                    )

                    // If an optional context was provided, ask the AI to generate fuller
                    // context
                    val providedContext = args["context"] as? String
                    if (!providedContext.isNullOrBlank()) {
                        // Ask the AI
                        commandUtils
                            .generateLocationContext(
                                locationName,
                                providedContext,
                                player,
                            ).thenAccept { response ->
                                if (response.isNullOrEmpty()) {
                                    player.sendError(
                                        "AI did not generate any context for this location.",
                                    )
                                    return@thenAccept
                                }

                                // Parse the AI response and extract context entries
                                val newContextEntries =
                                    commandUtils.parseLocationContextResponse(
                                        response,
                                    )

                                // Append generated context to the location and save
                                location.context.addAll(newContextEntries)
                                commandUtils.locationManager.saveLocation(location)

                                player.sendSuccess(
                                    "AI-generated context added to location <gold>'$locationName'</gold>.",
                                )
                            }.exceptionally { e ->
                                player.sendError(
                                    "Error generating context: ${e.message}",
                                )
                                null
                            }
                    }
                },
            ).executesConsole(
                ConsoleCommandExecutor { sender, args ->
                    // Only create location without location
                    val locationName = args["location_name"] as String
                    val location =
                        commandUtils.locationManager.createLocation(locationName, null)
                            ?: run {
                                sender.sendError(
                                    "Failed to create location. The location name may already exist.",
                                )
                                return@ConsoleCommandExecutor
                            }

                    // Save initial location (createLocation already saves, but ensure
                    // persisted)
                    commandUtils.locationManager.saveLocation(location)

                    sender.sendSuccess(
                        "Location <gold>'$locationName'</gold> created successfully without a set location.",
                    )

                    val providedContext = args["context"] as? String
                    if (!providedContext.isNullOrBlank()) {
                        // Ask the AI
                        commandUtils
                            .generateLocationContext(
                                locationName,
                                providedContext,
                                sender,
                            ).thenAccept { response ->
                                if (response.isNullOrEmpty()) {
                                    sender.sendError(
                                        "AI did not generate any context for this location.",
                                    )
                                    return@thenAccept
                                }

                                // Parse the AI response and extract context entries
                                val newContextEntries =
                                    commandUtils.parseLocationContextResponse(
                                        response,
                                    )

                                // Append generated context to the location and save
                                location.context.addAll(newContextEntries)
                                commandUtils.locationManager.saveLocation(location)

                                sender.sendSuccess(
                                    "AI-generated context added to location <gold>'$locationName'</gold>.",
                                )
                            }.exceptionally { e ->
                                sender.sendError(
                                    "Error generating context: ${e.message}",
                                )
                                null
                            }
                    }
                },
            )
    }
}
