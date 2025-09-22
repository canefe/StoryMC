package com.canefe.story.command.story.location

import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.arguments.TextArgument
import dev.jorel.commandapi.executors.CommandExecutor

class UpdateLocationCommand(
    private val commandUtils: LocationCommandUtils,
) {
    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("update")
            // usage: /story location create <location_name> [context]
            .withArguments(TextArgument("location_name"))
            .withArguments(GreedyStringArgument("context"))
            .withUsage(
                "/story location update <location_name> <context>",
            ).executes(
                CommandExecutor { sender, args ->
                    val locationName = args["location_name"] as String
                    val providedContext = args["context"] as String

                    val location =
                        commandUtils.locationManager.getLocation(locationName)
                            ?: run {
                                sender.sendError(
                                    "Location '$locationName' does not exist.",
                                )
                                return@CommandExecutor
                            }

                    if (providedContext.isNotBlank()) {
                        // Ask the AI
                        commandUtils
                            .generateRecentEvents(
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
                                val newUpdate =
                                    commandUtils.parseRecentEventsResponse(
                                        response,
                                    )

                                // Append generated context to the location and save
                                if (newUpdate != null) {
                                    location.context.add(newUpdate)
                                    commandUtils.locationManager.saveLocation(location)
                                    sender.sendSuccess(
                                        "AI-generated context added to location <gold>'$locationName'</gold>.",
                                    )
                                } else {
                                    sender.sendError(
                                        "Failed to parse AI response for location context.",
                                    )
                                }
                            }.exceptionally { e ->
                                sender.sendError(
                                    "Error generating context: ${e.message}",
                                )
                                null
                            }
                    } else {
                        sender.sendError("No context provided to update the location.")
                    }
                },
            )
    }
}
