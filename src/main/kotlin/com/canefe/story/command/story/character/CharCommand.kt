package com.canefe.story.command.story.character

import com.canefe.story.Story
import com.canefe.story.api.character.CharacterRecord
import com.canefe.story.api.character.Gender
import com.canefe.story.bridge.NpcSpawnIntent
import com.canefe.story.intelligence.BridgeIntelligence
import com.canefe.story.intelligence.GeneratedCharacterDTO
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.IntegerArgument
import dev.jorel.commandapi.arguments.StringArgument
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * `/story char ...` — character management. For now: `spawn <Template> [count]`,
 * which asks the bridge (story-go → story-chargen) to generate procedural
 * characters and then spawns them as MythicMob NPCs at the player's location.
 */
class CharCommand(
    private val plugin: Story,
) {
    fun getCommand(): CommandAPICommand =
        CommandAPICommand("char")
            .withAliases("character")
            .withPermission("story.npc")
            .withUsage("/story char spawn <Template> [count]")
            .withSubcommand(spawnCommand())

    private fun spawnCommand(): CommandAPICommand =
        CommandAPICommand("spawn")
            .withArguments(StringArgument("template"))
            .withOptionalArguments(IntegerArgument("count", 1, 50))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    val template = args.get("template") as String
                    val count = (args["count"] as? Int) ?: 1

                    val bridge = plugin.intelligence as? BridgeIntelligence
                    if (bridge == null) {
                        player.sendError("Chargen requires bridge mode (set bridge.enabled in config).")
                        return@PlayerCommandExecutor
                    }
                    if (!Bukkit.getPluginManager().isPluginEnabled("MythicMobs") ||
                        plugin.mythicMobNpcFactoryOrNull == null
                    ) {
                        player.sendError("MythicMobs / chargen NPC factory not available.")
                        return@PlayerCommandExecutor
                    }

                    player.sendSuccess("Requesting $count character(s) from chargen template '$template'…")

                    bridge
                        .requestCharacters(template = template, count = count)
                        .thenAccept { characters ->
                            // Hop back to main thread for spawning.
                            Bukkit.getScheduler().runTask(
                                plugin,
                                Runnable { spawnAll(player, characters, bridge) },
                            )
                        }.exceptionally { e ->
                            Bukkit.getScheduler().runTask(
                                plugin,
                                Runnable {
                                    player.sendError("Chargen request failed: ${e.message}")
                                },
                            )
                            null
                        }
                },
            )

    private fun spawnAll(
        player: Player,
        characters: List<GeneratedCharacterDTO>,
        bridge: BridgeIntelligence,
    ) {
        if (characters.isEmpty()) {
            player.sendError("Chargen returned no characters.")
            return
        }

        var spawned = 0
        for (gen in characters) {
            // Avoid name collisions with already-registered characters (Mongo).
            if (plugin.isCharacterRegistryReady && plugin.characterRegistry.getByName(gen.name) != null) {
                plugin.logger.warning("[CharCommand] Skipping '${gen.name}' — already registered.")
                continue
            }

            val stableUuid = UUID.randomUUID()
            val npc =
                plugin.mythicMobNpcFactory.spawn(
                    mobTemplate = "Character",
                    location = player.location,
                    displayName = gen.name,
                    stableUniqueId = stableUuid,
                )
            if (npc == null) {
                player.sendError("Failed to spawn '${gen.name}' (template 'Character').")
                continue
            }

            if (plugin.isCharacterRegistryReady) {
                plugin.characterRegistry.register(
                    CharacterRecord(
                        id = stableUuid.toString(),
                        name = gen.name,
                        race = gen.race,
                        gender = Gender.fromWire(gen.gender),
                        appearance = gen.appearance,
                        type = CharacterRecord.CharacterType.NPC,
                    ),
                )
            }

            // Forward the chargen-built descriptor to story-recognition so
            // unknown perceivers see "Pale-skinned Nord Guard with red hair"
            // instead of "Stranger". Chargen owns the phrasing — plugin is a
            // thin pipe.
            if (bridge.isRecognitionSupported() && gen.descriptor.isNotBlank()) {
                bridge.setDescriptor(stableUuid.toString(), gen.descriptor)
                    .exceptionally { e ->
                        plugin.logger.warning(
                            "[CharCommand] Failed to set descriptor for ${gen.name}: ${e.message}",
                        )
                        null
                    }
            }

            // Push the structured appearance map so recognition can render
            // per-perceiver prose via /describe instead of the local toProse().
            if (bridge.isDescribeSupported() && gen.appearance.isNotEmpty()) {
                bridge.setAppearance(
                    stableUuid.toString(),
                    Gender.fromWire(gen.gender).name.lowercase(),
                    gen.appearance,
                ).exceptionally { e ->
                    plugin.logger.warning(
                        "[CharCommand] Failed to set appearance for ${gen.name}: ${e.message}",
                    )
                    null
                }
            }
            // Notify story-go so the sim can create an entity at these coordinates.
            plugin.eventBus.emit(NpcSpawnIntent(
                characterId = stableUuid.toString(),
                name = gen.name,
                race = gen.race ?: "human",
                x = player.location.x,
                y = player.location.y,
                z = player.location.z,
            ))

            spawned++
        }

        player.sendSuccess("Spawned $spawned/${characters.size} character(s) from '${characters.first().template}'.")
    }

}
