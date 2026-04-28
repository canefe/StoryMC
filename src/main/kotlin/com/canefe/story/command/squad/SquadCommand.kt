package com.canefe.story.command.squad

import com.canefe.story.Story
import com.canefe.story.command.base.BaseCommand
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendInfo
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.TextArgument
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import org.bukkit.entity.Player

/**
 * `/squad` — manage squads of NPCs (in-fiction command structures).
 *
 * Permission: `story.squad`. Ownership and sharing are tracked by the player's
 * active CHARACTER (via CharacterRegistry.getActiveCharacterForPlayer), not
 * their player UUID, so logging in as a different character switches which
 * squads they command.
 */
class SquadCommand(
    private val plugin: Story,
) : BaseCommand {
    override fun register() {
        CommandAPICommand("squad")
            .withPermission("story.squad")
            .withSubcommand(create())
            .withSubcommand(delete())
            .withSubcommand(list())
            .withSubcommand(info())
            .withSubcommand(addMember())
            .withSubcommand(removeMember())
            .withSubcommand(share())
            .withSubcommand(unshare())
            .register()
    }

    /** Resolve the player's active character; sends an error and returns null if missing. */
    private fun activeCharacterId(player: Player): String? {
        if (!plugin.isCharacterRegistryReady) {
            player.sendError("Character system unavailable (no MongoDB).")
            return null
        }
        val id = plugin.characterRegistry.getActiveCharacterForPlayer(player)
        if (id == null) {
            player.sendError("You have no active character on this frontend. Set one via story-mcp switch_character or your character link flow.")
        }
        return id
    }

    private fun ensureSquadRegistry(player: Player): Boolean {
        if (plugin.isSquadRegistryReady) return true
        player.sendError("Squad system unavailable (no MongoDB).")
        return false
    }

    private fun create(): CommandAPICommand =
        CommandAPICommand("create")
            .withArguments(TextArgument("name"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    if (!ensureSquadRegistry(player)) return@PlayerCommandExecutor
                    val charId = activeCharacterId(player) ?: return@PlayerCommandExecutor
                    val name = args.get("name") as String
                    if (plugin.squadRegistry.getByOwnerAndName(charId, name) != null) {
                        player.sendError("You already command a squad named '$name'.")
                        return@PlayerCommandExecutor
                    }
                    val squad = plugin.squadRegistry.create(name, charId)
                    plugin.squadListBroadcaster.push(player)
                    player.sendSuccess("Created squad '${squad.name}'.")
                },
            )

    private fun delete(): CommandAPICommand =
        CommandAPICommand("delete")
            .withArguments(TextArgument("name"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    if (!ensureSquadRegistry(player)) return@PlayerCommandExecutor
                    val charId = activeCharacterId(player) ?: return@PlayerCommandExecutor
                    val name = args.get("name") as String
                    val squad = plugin.squadRegistry.getByOwnerAndName(charId, name)
                    if (squad == null) {
                        player.sendError("You don't own a squad named '$name'.")
                        return@PlayerCommandExecutor
                    }
                    plugin.squadRegistry.delete(squad.id)
                    plugin.squadOrderTracker.cancel(squad.id)
                    plugin.squadListBroadcaster.push(player)
                    player.sendSuccess("Deleted squad '${squad.name}'.")
                },
            )

    private fun list(): CommandAPICommand =
        CommandAPICommand("list")
            .executesPlayer(
                PlayerCommandExecutor { player, _ ->
                    if (!ensureSquadRegistry(player)) return@PlayerCommandExecutor
                    val charId = activeCharacterId(player) ?: return@PlayerCommandExecutor
                    val owned = plugin.squadRegistry.ownedBy(charId)
                    val shared =
                        plugin.squadRegistry
                            .commandableBy(charId)
                            .filter { it.ownerCharacterId != charId }
                    if (owned.isEmpty() && shared.isEmpty()) {
                        player.sendInfo("You command no squads.")
                        return@PlayerCommandExecutor
                    }
                    if (owned.isNotEmpty()) {
                        player.sendInfo("Squads you own (${owned.size}):")
                        for (sq in owned) player.sendInfo(" • ${sq.name}  <gray>[${sq.memberCharacterIds.size} members]</gray>")
                    }
                    if (shared.isNotEmpty()) {
                        player.sendInfo("Squads shared with you (${shared.size}):")
                        for (sq in shared) {
                            val ownerName =
                                plugin.characterRegistry.getDisplayName(sq.ownerCharacterId)
                            player.sendInfo(" • ${sq.name}  <gray>[owner: $ownerName, ${sq.memberCharacterIds.size} members]</gray>")
                        }
                    }
                },
            )

    private fun info(): CommandAPICommand =
        CommandAPICommand("info")
            .withArguments(TextArgument("name"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    if (!ensureSquadRegistry(player)) return@PlayerCommandExecutor
                    val charId = activeCharacterId(player) ?: return@PlayerCommandExecutor
                    val name = args.get("name") as String
                    val squad = resolveCommandable(player, charId, name) ?: return@PlayerCommandExecutor

                    val ownerName = plugin.characterRegistry.getDisplayName(squad.ownerCharacterId)
                    player.sendInfo("Squad: <yellow>${squad.name}</yellow>")
                    player.sendInfo(" Owner: $ownerName")
                    player.sendInfo(" Members (${squad.memberCharacterIds.size}):")
                    for (cid in squad.memberCharacterIds) {
                        val record = plugin.characterRegistry.getById(cid)
                        val displayName = record?.name ?: "<missing-char:$cid>"
                        val live =
                            plugin.npcRegistry.all().any { plugin.characterRegistry.getCharacterIdForNPC(it) == cid }
                        val suffix = if (live) "" else " <gray>(no live NPC)</gray>"
                        player.sendInfo("   - $displayName$suffix")
                    }
                    if (squad.sharedWithCharacterIds.isNotEmpty()) {
                        player.sendInfo(" Shared with:")
                        for (cid in squad.sharedWithCharacterIds) {
                            player.sendInfo("   - ${plugin.characterRegistry.getDisplayName(cid)}")
                        }
                    }
                },
            )

    private fun addMember(): CommandAPICommand =
        CommandAPICommand("add")
            .withArguments(TextArgument("name"))
            .withArguments(TextArgument("npc"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    if (!ensureSquadRegistry(player)) return@PlayerCommandExecutor
                    val charId = activeCharacterId(player) ?: return@PlayerCommandExecutor
                    val name = args.get("name") as String
                    val npcName = args.get("npc") as String

                    val squad = resolveOwned(player, charId, name) ?: return@PlayerCommandExecutor
                    val npc = plugin.npcRegistry.getByName(npcName)
                    if (npc == null) {
                        player.sendError("No registered NPC named '$npcName'.")
                        return@PlayerCommandExecutor
                    }
                    val memberCharId = plugin.characterRegistry.getCharacterIdForNPC(npc)
                    if (memberCharId == null) {
                        player.sendError("${npc.name} has no backing character record.")
                        return@PlayerCommandExecutor
                    }
                    if (squad.memberCharacterIds.contains(memberCharId)) {
                        player.sendError("${npc.name} is already in '${squad.name}'.")
                        return@PlayerCommandExecutor
                    }
                    plugin.squadRegistry.addMember(squad.id, memberCharId)
                    player.sendSuccess("Added ${npc.name} to '${squad.name}'.")
                },
            )

    private fun removeMember(): CommandAPICommand =
        CommandAPICommand("remove")
            .withArguments(TextArgument("name"))
            .withArguments(TextArgument("npc"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    if (!ensureSquadRegistry(player)) return@PlayerCommandExecutor
                    val charId = activeCharacterId(player) ?: return@PlayerCommandExecutor
                    val name = args.get("name") as String
                    val npcName = args.get("npc") as String

                    val squad = resolveOwned(player, charId, name) ?: return@PlayerCommandExecutor
                    // Try by live NPC name first; if missing, allow direct character name lookup.
                    val memberCharId =
                        plugin.npcRegistry.getByName(npcName)
                            ?.let { plugin.characterRegistry.getCharacterIdForNPC(it) }
                            ?: plugin.characterRegistry.getByName(npcName)?.id
                    if (memberCharId == null) {
                        player.sendError("No character named '$npcName'.")
                        return@PlayerCommandExecutor
                    }
                    if (!squad.memberCharacterIds.contains(memberCharId)) {
                        player.sendError("'$npcName' is not in '${squad.name}'.")
                        return@PlayerCommandExecutor
                    }
                    plugin.squadRegistry.removeMember(squad.id, memberCharId)
                    player.sendSuccess("Removed '$npcName' from '${squad.name}'.")
                },
            )

    private fun share(): CommandAPICommand =
        CommandAPICommand("share")
            .withArguments(TextArgument("name"))
            .withArguments(TextArgument("character"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    if (!ensureSquadRegistry(player)) return@PlayerCommandExecutor
                    val charId = activeCharacterId(player) ?: return@PlayerCommandExecutor
                    val name = args.get("name") as String
                    val targetCharName = args.get("character") as String

                    val squad = resolveOwned(player, charId, name) ?: return@PlayerCommandExecutor
                    val target = plugin.characterRegistry.getByName(targetCharName)
                    if (target == null) {
                        player.sendError("No character named '$targetCharName'.")
                        return@PlayerCommandExecutor
                    }
                    if (target.id == charId) {
                        player.sendError("Cannot share with yourself.")
                        return@PlayerCommandExecutor
                    }
                    plugin.squadRegistry.share(squad.id, target.id)
                    player.sendSuccess("Shared '${squad.name}' with ${target.name}.")
                },
            )

    private fun unshare(): CommandAPICommand =
        CommandAPICommand("unshare")
            .withArguments(TextArgument("name"))
            .withArguments(TextArgument("character"))
            .executesPlayer(
                PlayerCommandExecutor { player, args ->
                    if (!ensureSquadRegistry(player)) return@PlayerCommandExecutor
                    val charId = activeCharacterId(player) ?: return@PlayerCommandExecutor
                    val name = args.get("name") as String
                    val targetCharName = args.get("character") as String

                    val squad = resolveOwned(player, charId, name) ?: return@PlayerCommandExecutor
                    val target = plugin.characterRegistry.getByName(targetCharName)
                    if (target == null) {
                        player.sendError("No character named '$targetCharName'.")
                        return@PlayerCommandExecutor
                    }
                    plugin.squadRegistry.unshare(squad.id, target.id)
                    player.sendSuccess("Unshared '${squad.name}' from ${target.name}.")
                },
            )

    /** Resolve a squad the player owns. */
    private fun resolveOwned(player: Player, charId: String, name: String) =
        plugin.squadRegistry.getByOwnerAndName(charId, name).also { sq ->
            if (sq == null) player.sendError("You don't own a squad named '$name'.")
        }

    /** Resolve a squad the player can command (owns or is shared with). */
    private fun resolveCommandable(player: Player, charId: String, name: String) =
        plugin.squadRegistry
            .commandableBy(charId)
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            .also { sq ->
                if (sq == null) player.sendError("No squad named '$name' you can command.")
            }
}
