package com.canefe.story.command.story.affordance

import com.canefe.story.Story
import com.canefe.story.affordance.AffordanceRecord
import com.canefe.story.bridge.SpawnAffordanceEvent
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import com.canefe.story.util.Msg.sendRaw
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.StringArgument
import dev.jorel.commandapi.arguments.TextArgument
import dev.jorel.commandapi.executors.PlayerCommandExecutor

class AffordanceCommand(private val plugin: Story) {

    fun getCommand(): CommandAPICommand =
        CommandAPICommand("affordance")
            .withAliases("aff", "affordances")
            .withPermission("story.affordance")
            .withSubcommand(getRegisterCommand())
            .withSubcommand(getListCommand())
            .withSubcommand(getUnregisterCommand())
            .withSubcommand(getTypesCommand())

    private fun getRegisterCommand(): CommandAPICommand =
        CommandAPICommand("register")
            .withPermission("story.affordance.register")
            .withArguments(
                StringArgument("typeId").replaceSuggestions(
                    dev.jorel.commandapi.arguments.ArgumentSuggestions.strings { _ ->
                        plugin.affordanceTypeRegistry.all().map { it.id }.toTypedArray()
                    }
                )
            )
            .withOptionalArguments(TextArgument("name"))
            .withUsage("/story affordance register <typeId> [name]")
            .executesPlayer(PlayerCommandExecutor { player, args ->
                val storage = plugin.storageFactory.mongoClient?.let {
                    com.canefe.story.affordance.AffordanceStorage(it)
                } ?: run {
                    player.sendError("MongoDB not available.")
                    return@PlayerCommandExecutor
                }

                val typeId = args["typeId"] as String
                val typeDef = plugin.affordanceTypeRegistry.getById(typeId) ?: run {
                    player.sendError("Unknown affordance type '<gold>$typeId</gold>'. Use /story affordance types to list known types.")
                    return@PlayerCommandExecutor
                }

                val name = (args["name"] as? String) ?: typeDef.name
                val targetBlock = player.getTargetBlockExact(8)
                    ?: player.world.getBlockAt(player.location) // fallback to feet
                val blockLoc = targetBlock.location
                val blockType = targetBlock.type.name
                val world = blockLoc.world?.name ?: "world"

                val record = AffordanceRecord(
                    id = "",
                    affordanceTypeId = typeId,
                    name = name,
                    x = blockLoc.blockX.toDouble() + 0.5,
                    y = blockLoc.blockY.toDouble(),
                    z = blockLoc.blockZ.toDouble() + 0.5,
                    world = world,
                    frontend = mapOf(
                        "minecraft" to mapOf(
                            "block_type" to blockType,
                            "x" to blockLoc.blockX,
                            "y" to blockLoc.blockY,
                            "z" to blockLoc.blockZ,
                        )
                    ),
                )

                val saved = storage.save(record)

                plugin.eventBus.emit(SpawnAffordanceEvent(
                    affordanceId = saved.id,
                    affordanceTypeId = typeId,
                    name = name,
                    x = saved.x,
                    y = saved.y,
                    z = saved.z,
                    world = world,
                    capacity = typeDef.capacity,
                ))

                player.sendSuccess(
                    "Registered <gold>'$name'</gold> as <gold>$typeId</gold> (block: $blockType at ${blockLoc.blockX},${blockLoc.blockY},${blockLoc.blockZ}) — id: ${saved.id}"
                )
                plugin.logger.info("[Affordance] registered ${saved.id}: $name ($typeId) at (${blockLoc.blockX},${blockLoc.blockY},${blockLoc.blockZ}) in $world")
            })

    private fun getListCommand(): CommandAPICommand =
        CommandAPICommand("list")
            .withPermission("story.affordance.list")
            .executesPlayer(PlayerCommandExecutor { player, _ ->
                val storage = plugin.storageFactory.mongoClient?.let {
                    com.canefe.story.affordance.AffordanceStorage(it)
                } ?: run {
                    player.sendError("MongoDB not available.")
                    return@PlayerCommandExecutor
                }

                val records = storage.findAll()
                if (records.isEmpty()) {
                    player.sendRaw("<gray>No affordances registered.</gray>")
                    return@PlayerCommandExecutor
                }

                player.sendRaw("<yellow>=== Registered Affordances (${records.size}) ===</yellow>")
                records.forEach { r ->
                    player.sendRaw(
                        "<gold>${r.name}</gold> <gray>[${r.affordanceTypeId}]</gray> " +
                        "<white>(${r.x.toInt()},${r.y.toInt()},${r.z.toInt()} ${r.world})</white> " +
                        "<dark_gray>id:${r.id}</dark_gray>"
                    )
                }
            })

    private fun getUnregisterCommand(): CommandAPICommand =
        CommandAPICommand("unregister")
            .withAliases("remove", "delete")
            .withPermission("story.affordance.register")
            .withArguments(StringArgument("id"))
            .withUsage("/story affordance unregister <id>")
            .executesPlayer(PlayerCommandExecutor { player, args ->
                val storage = plugin.storageFactory.mongoClient?.let {
                    com.canefe.story.affordance.AffordanceStorage(it)
                } ?: run {
                    player.sendError("MongoDB not available.")
                    return@PlayerCommandExecutor
                }

                val id = args["id"] as String
                if (storage.delete(id)) {
                    player.sendSuccess("Affordance <gold>$id</gold> removed.")
                } else {
                    player.sendError("No affordance found with id <gold>$id</gold>.")
                }
            })

    private fun getTypesCommand(): CommandAPICommand =
        CommandAPICommand("types")
            .withPermission("story.affordance.list")
            .executesPlayer(PlayerCommandExecutor { player, _ ->
                val types = plugin.affordanceTypeRegistry.all()
                if (types.isEmpty()) {
                    player.sendRaw("<gray>No affordance types received from sim yet. Is story-sim running?</gray>")
                    return@PlayerCommandExecutor
                }
                player.sendRaw("<yellow>=== Affordance Types (${types.size}) ===</yellow>")
                types.forEach { t ->
                    player.sendRaw(
                        "<gold>${t.id}</gold> <gray>${t.name}</gray> " +
                        "<white>tags:${t.tags.joinToString(",")}</white> " +
                        "<dark_gray>range:${t.use_range} cap:${if (t.capacity == 0) "∞" else t.capacity.toString()}</dark_gray>"
                    )
                }
            })
}
