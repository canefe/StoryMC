package com.canefe.story.command.story.location

import com.canefe.story.location.data.StoryLocation
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.ArgumentSuggestions
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.arguments.TextArgument
import dev.jorel.commandapi.executors.ConsoleCommandExecutor
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender

class CreateLocationCommand(
    private val commandUtils: LocationCommandUtils,
) {
    // Known sim location-def ids usable as templates. These are authored in the
    // story-sim pack (packs/BaseGame/lua/defs/locations); story-go asks the sim
    // for the template's DefaultTags + DefaultRadius and merges them into the
    // mongo doc at create-time. Suggestion-only — any string is accepted.
    private val templateSuggestions =
        arrayOf("market_square", "tavern_common_room", "temple_grounds", "village_well")

    fun getCommand(): CommandAPICommand {
        return CommandAPICommand("create")
            .withArguments(TextArgument("location_name"))
            .withOptionalArguments(
                TextArgument("template").replaceSuggestions(
                    ArgumentSuggestions.strings(*templateSuggestions),
                ),
            )
            .withOptionalArguments(GreedyStringArgument("context"))
            .withUsage(
                "/story location create <location_name> [template] [context]",
            ).executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val locationName = args["location_name"] as String
                    val template = (args["template"] as? String).orEmpty()
                    val providedContext = args["context"] as? String
                    val playerLocation: Location = player.location

                    if (commandUtils.locationManager.getLocation(locationName) != null) {
                        player.sendError(
                            "Location <gold>'$locationName'</gold> already exists.",
                        )
                        return@PlayerCommandExecutor
                    }

                    requestCreate(
                        sender = player,
                        name = locationName,
                        template = template,
                        bukkitLocation = playerLocation,
                        providedContext = providedContext,
                    )
                },
            ).executesConsole(
                ConsoleCommandExecutor { sender, args ->
                    val locationName = args["location_name"] as String
                    val template = (args["template"] as? String).orEmpty()
                    val providedContext = args["context"] as? String

                    if (commandUtils.locationManager.getLocation(locationName) != null) {
                        sender.sendError(
                            "Location <gold>'$locationName'</gold> already exists.",
                        )
                        return@ConsoleCommandExecutor
                    }

                    requestCreate(
                        sender = sender,
                        name = locationName,
                        template = template,
                        bukkitLocation = null,
                        providedContext = providedContext,
                    )
                },
            )
    }

    private fun requestCreate(
        sender: CommandSender,
        name: String,
        template: String,
        bukkitLocation: Location?,
        providedContext: String?,
    ) {
        val worldName = bukkitLocation?.world?.name.orEmpty()
        val x = bukkitLocation?.x ?: 0.0
        val y = bukkitLocation?.y ?: 0.0
        val z = bukkitLocation?.z ?: 0.0

        commandUtils.locationBridge
            .createLocation(
                name = name,
                template = template,
                world = worldName,
                x = x,
                y = y,
                z = z,
            ).thenAccept { result ->
                // Bukkit APIs (and our cache) must be touched on the main thread.
                Bukkit.getScheduler().runTask(
                    commandUtils.story,
                    Runnable {
                        if (!result.ok) {
                            sender.sendError(
                                "Failed to create location <gold>'$name'</gold>: ${result.error}",
                            )
                            return@Runnable
                        }

                        // Mirror story-go's result into the in-memory cache so
                        // subsequent commands resolve the new location without a
                        // mongo round-trip.
                        val location =
                            StoryLocation(name, "", bukkitLocation, null).apply {
                                this.template = template
                                this.radius = result.radius
                                this.tags.addAll(result.tags)
                            }
                        commandUtils.locationManager.addLocation(location)

                        val templateNote =
                            if (template.isNotBlank()) " from template <gold>'$template'</gold>" else ""
                        val coordsNote =
                            if (bukkitLocation != null) " at your current location" else " without a set location"
                        sender.sendSuccess(
                            "Location <gold>'$name'</gold> created$templateNote$coordsNote.",
                        )

                        if (!providedContext.isNullOrBlank()) {
                            generateContextAndAppend(sender, name, providedContext, location)
                        }
                    },
                )
            }.exceptionally { e ->
                sender.sendError("Error creating location: ${e.message}")
                null
            }
    }

    private fun generateContextAndAppend(
        sender: CommandSender,
        locationName: String,
        providedContext: String,
        location: StoryLocation,
    ) {
        commandUtils
            .generateLocationContext(locationName, providedContext, sender)
            .thenAccept { response ->
                if (response.isNullOrEmpty()) {
                    sender.sendError("AI did not generate any context for this location.")
                    return@thenAccept
                }

                val newContextEntries =
                    commandUtils.parseLocationContextResponse(response)
                val newText = newContextEntries.joinToString("\n")

                Bukkit.getScheduler().runTask(
                    commandUtils.story,
                    Runnable {
                        location.description =
                            if (location.description.isBlank()) {
                                newText
                            } else {
                                "${location.description}\n$newText"
                            }
                        // TODO: route description updates through story-go once we
                        // have a location.update.request; for now this still hits
                        // mongo directly via LocationManager.saveLocation.
                        commandUtils.locationManager.saveLocation(location)

                        sender.sendSuccess(
                            "AI-generated context added to location <gold>'$locationName'</gold>.",
                        )
                    },
                )
            }.exceptionally { e ->
                sender.sendError("Error generating context: ${e.message}")
                null
            }
    }
}
