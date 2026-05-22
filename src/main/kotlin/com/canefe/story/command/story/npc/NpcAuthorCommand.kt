package com.canefe.story.command.story.npc

import com.canefe.story.Story
import com.canefe.story.bridge.NpcGiveItemIntent
import com.canefe.story.bridge.NpcGiveTraitIntent
import com.canefe.story.bridge.NpcKnowIntent
import com.canefe.story.bridge.NpcSetNeedIntent
import com.canefe.story.bridge.NpcSetOffersIntent
import com.canefe.story.bridge.NpcSetStatIntent
import com.canefe.story.storage.CharDataItemStack
import com.canefe.story.storage.OfferItemSpec
import com.canefe.story.storage.OfferSpec
import com.canefe.story.util.Msg.sendError
import com.canefe.story.util.Msg.sendSuccess
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.arguments.ArgumentSuggestions
import dev.jorel.commandapi.arguments.DoubleArgument
import dev.jorel.commandapi.arguments.GreedyStringArgument
import dev.jorel.commandapi.arguments.IntegerArgument
import dev.jorel.commandapi.arguments.MultiLiteralArgument
import dev.jorel.commandapi.arguments.StringArgument
import dev.jorel.commandapi.executors.PlayerCommandExecutor
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.bukkit.entity.Player

class NpcAuthorCommand(private val plugin: Story) {

    /** All authoring subcommands to graft onto the /story npc command. */
    fun subcommands(): List<CommandAPICommand> =
        listOf(needCmd(), giveCmd(), traitCmd(), statCmd(), knowCmd(), offerAddCmd(), offerRemoveCmd())

    private fun charIdArg() =
        StringArgument("char_id").replaceSuggestions(
            ArgumentSuggestions.strings { _ ->
                if (plugin.isCharacterRegistryReady) plugin.characterRegistry.allIds().toTypedArray()
                else emptyArray()
            },
        )

    /** Resolve id -> record name, or null + error sent to player. */
    private fun resolve(player: Player, id: String): String? {
        if (!plugin.isCharacterRegistryReady) {
            player.sendError("Character registry not ready.")
            return null
        }
        val rec = plugin.characterRegistry.getById(id)
        if (rec == null) {
            player.sendError("No character with id '$id'.")
            return null
        }
        if (plugin.characterDataStorage == null) {
            player.sendError("character_data storage unavailable (Mongo required).")
            return null
        }
        return rec.name
    }

    private fun needCmd(): CommandAPICommand =
        CommandAPICommand("need")
            .withPermission("story.dm")
            .withArguments(charIdArg(), StringArgument("need"), DoubleArgument("value"))
            .executesPlayer(PlayerCommandExecutor { player, args ->
                val id = args["char_id"] as String
                val name = resolve(player, id) ?: return@PlayerCommandExecutor
                val need = args["need"] as String
                val value = args["value"] as Double
                plugin.characterDataStorage!!.update(id) { it.copy(needValues = it.needValues + (need to value)) }
                plugin.eventBus.emit(NpcSetNeedIntent(characterId = id, name = name, need = need, value = value))
                player.sendSuccess("Set $name need <gold>$need=$value</gold>.")
            })

    private fun statCmd(): CommandAPICommand =
        CommandAPICommand("stat")
            .withPermission("story.dm")
            .withArguments(charIdArg(), StringArgument("stat"), DoubleArgument("value"))
            .executesPlayer(PlayerCommandExecutor { player, args ->
                val id = args["char_id"] as String
                val name = resolve(player, id) ?: return@PlayerCommandExecutor
                val stat = args["stat"] as String
                val value = args["value"] as Double
                plugin.characterDataStorage!!.update(id) { it.copy(statValues = it.statValues + (stat to value)) }
                plugin.eventBus.emit(NpcSetStatIntent(characterId = id, name = name, stat = stat, value = value))
                player.sendSuccess("Set $name stat <gold>$stat=$value</gold>.")
            })

    private fun giveCmd(): CommandAPICommand =
        CommandAPICommand("give")
            .withPermission("story.dm")
            .withArguments(charIdArg(), StringArgument("item"), IntegerArgument("qty", 1))
            .executesPlayer(PlayerCommandExecutor { player, args ->
                val id = args["char_id"] as String
                val name = resolve(player, id) ?: return@PlayerCommandExecutor
                val item = args["item"] as String
                val qty = args["qty"] as Int
                plugin.characterDataStorage!!.update(id) { doc ->
                    val existing = doc.startingInventory.find { it.item == item }
                    val merged = if (existing != null) {
                        doc.startingInventory.map { if (it.item == item) it.copy(qty = it.qty + qty) else it }
                    } else {
                        doc.startingInventory + CharDataItemStack(item, qty)
                    }
                    doc.copy(startingInventory = merged)
                }
                plugin.eventBus.emit(NpcGiveItemIntent(characterId = id, name = name, item = item, qty = qty))
                player.sendSuccess("Gave $name <gold>${qty}x $item</gold>.")
            })

    private fun traitCmd(): CommandAPICommand =
        CommandAPICommand("trait")
            .withPermission("story.dm")
            .withArguments(charIdArg(), StringArgument("trait"))
            .executesPlayer(PlayerCommandExecutor { player, args ->
                val id = args["char_id"] as String
                val name = resolve(player, id) ?: return@PlayerCommandExecutor
                val trait = args["trait"] as String
                plugin.characterDataStorage!!.update(id) {
                    if (it.traits.contains(trait)) it else it.copy(traits = it.traits + trait)
                }
                plugin.eventBus.emit(NpcGiveTraitIntent(characterId = id, name = name, trait = trait))
                player.sendSuccess("Gave $name trait <gold>$trait</gold>.")
            })

    private fun knowCmd(): CommandAPICommand =
        CommandAPICommand("know")
            .withPermission("story.dm")
            .withArguments(charIdArg(), StringArgument("location_name"))
            .executesPlayer(PlayerCommandExecutor { player, args ->
                val id = args["char_id"] as String
                val name = resolve(player, id) ?: return@PlayerCommandExecutor
                val loc = args["location_name"] as String
                plugin.characterDataStorage!!.update(id) {
                    if (it.knownLocations.contains(loc)) it else it.copy(knownLocations = it.knownLocations + loc)
                }
                plugin.eventBus.emit(NpcKnowIntent(characterId = id, locationId = loc))
                player.sendSuccess("$name now knows <gold>$loc</gold>.")
            })

    // ── Offer commands ────────────────────────────────────────────────────

    /**
     * /story npc offer <char_id> add <offer_id> <spec>
     *
     * <spec> format: wants <token>... gives <token>... [while <situation>]
     * token format:  tag:<tag>:<qty>  |  item:<item_id>:<qty>
     *
     * Example: wants tag:currency:3 gives item:wheat:10 while merchant_open
     */
    private fun offerAddCmd(): CommandAPICommand =
        CommandAPICommand("offer")
            .withPermission("story.dm")
            .withArguments(
                charIdArg(),
                MultiLiteralArgument("op", "add"),
                StringArgument("offer_id"),
                GreedyStringArgument("spec"),
            )
            .executesPlayer(PlayerCommandExecutor { player, args ->
                val id = args["char_id"] as String
                val name = resolve(player, id) ?: return@PlayerCommandExecutor
                val offerId = args["offer_id"] as String
                val spec = args["spec"] as String
                val offer = try {
                    parseOfferSpec(offerId, spec)
                } catch (e: IllegalArgumentException) {
                    player.sendError("Bad offer: ${e.message}")
                    return@PlayerCommandExecutor
                }
                var fullList: List<OfferSpec> = emptyList()
                plugin.characterDataStorage!!.update(id) { doc ->
                    val without = doc.offers.filter { it.id != offerId }
                    val updated = doc.copy(offers = without + offer)
                    fullList = updated.offers
                    updated
                }
                emitOffers(id, name, fullList)
                player.sendSuccess("Offer <gold>$offerId</gold> set for $name (${fullList.size} total).")
            })

    /** /story npc offer <char_id> remove <offer_id> */
    private fun offerRemoveCmd(): CommandAPICommand =
        CommandAPICommand("offer")
            .withPermission("story.dm")
            .withArguments(
                charIdArg(),
                MultiLiteralArgument("op", "remove"),
                StringArgument("offer_id"),
            )
            .executesPlayer(PlayerCommandExecutor { player, args ->
                val id = args["char_id"] as String
                val name = resolve(player, id) ?: return@PlayerCommandExecutor
                val offerId = args["offer_id"] as String
                var fullList: List<OfferSpec> = emptyList()
                plugin.characterDataStorage!!.update(id) { doc ->
                    val updated = doc.copy(offers = doc.offers.filter { it.id != offerId })
                    fullList = updated.offers
                    updated
                }
                emitOffers(id, name, fullList)
                player.sendSuccess("Offer <gold>$offerId</gold> removed from $name.")
            })

    /**
     * Parse the greedy spec string into an [OfferSpec].
     * Format: `wants <token>... gives <token>... [while <situation>]`
     */
    private fun parseOfferSpec(offerId: String, spec: String): OfferSpec {
        val toks = spec.trim().split(Regex("\\s+"))
        val wants = mutableListOf<OfferItemSpec>()
        val gives = mutableListOf<OfferItemSpec>()
        var whileSituation: String? = null
        var section = ""
        var i = 0
        while (i < toks.size) {
            when (toks[i]) {
                "wants" -> section = "wants"
                "gives" -> section = "gives"
                "while" -> {
                    whileSituation = toks.getOrNull(i + 1)
                    i++
                }
                else -> when (section) {
                    "wants" -> wants.add(parseOfferToken(toks[i]))
                    "gives" -> gives.add(parseOfferToken(toks[i]))
                    else    -> throw IllegalArgumentException("token '${toks[i]}' before wants/gives")
                }
            }
            i++
        }
        require(gives.isNotEmpty()) { "offer must have at least one gives token" }
        return OfferSpec(id = offerId, wants = wants, gives = gives, whileSituation = whileSituation)
    }

    /** Serialize the full offer list and emit [NpcSetOffersIntent] to the sim. */
    private fun emitOffers(id: String, name: String, offers: List<OfferSpec>) {
        val json = Json.encodeToString(ListSerializer(OfferSpec.serializer()), offers)
        plugin.eventBus.emit(NpcSetOffersIntent(characterId = id, name = name, offers = json))
    }
}
