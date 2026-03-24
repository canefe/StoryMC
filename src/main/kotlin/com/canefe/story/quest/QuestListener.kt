package com.canefe.story.quest

import com.canefe.story.Story
import com.canefe.story.api.event.ConversationStartEvent
import com.canefe.story.api.event.PlayerLocationChangeEvent
import com.canefe.story.command.story.quest.QuestCommandUtils
import com.canefe.story.location.data.StoryLocation
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.regex.Pattern

class QuestListener(
    private val plugin: Story,
) : Listener {
    @EventHandler
    fun onEntityKill(event: EntityDeathEvent) {
        val killer = event.entity.killer
        if (killer !is Player) return

        val entityType = event.entityType.name
        val entityName =
            event.entity.customName()?.let { plugin.miniMessage.serialize(it) }
                ?: entityType // Fallback to entity type if custom name is not set

        updateQuestProgress(killer, ObjectiveType.KILL, entityName)
    }

    @EventHandler
    fun onItemPickup(event: PlayerAttemptPickupItemEvent) {
        val item = event.item.itemStack

        // For each quest with collection objectives, check if this item matches
        val playerQuests = plugin.questManager.getPlayerQuests(event.player.uniqueId)

        for ((questId, playerQuest) in playerQuests) {
            if (playerQuest.status == QuestStatus.IN_PROGRESS) {
                val quest = plugin.questManager.getQuest(questId) ?: continue

                for (i in quest.objectives.indices) {
                    val objective = quest.objectives[i]

                    // Check only COLLECT objectives
                    if (objective.type == ObjectiveType.COLLECT) {
                        // Use our new matcher for complex item targets
                        if (itemMatchesTarget(item, objective.target)) {
                            plugin.questManager.updateObjectiveProgress(
                                event.player,
                                questId,
                                ObjectiveType.COLLECT,
                                objective.target,
                                item.amount,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Parses a collection target string into item properties
     * Format: "MATERIAL{name:display_name,lore:lore_text}"
     * @param target The collection target string
     * @return Map of item properties
     */
    fun parseCollectionTarget(target: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        // Extract material and properties
        val matcher = Pattern.compile("(\\w+)(?:\\{(.+)\\})?").matcher(target)
        if (matcher.find()) {
            // Base material
            result["material"] = matcher.group(1)

            // Parse properties if they exist
            val properties = matcher.group(2)
            if (properties != null) {
                // Split by commas not inside quotes
                val propertyPairs = properties.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())

                for (pair in propertyPairs) {
                    val keyValue = pair.split(":", limit = 2)
                    if (keyValue.size == 2) {
                        val key = keyValue[0].trim()
                        var value = keyValue[1].trim()

                        // Remove surrounding quotes if present
                        if (value.startsWith("\"") && value.endsWith("\"")) {
                            value = value.substring(1, value.length - 1)
                        }

                        result[key] = value
                    }
                }
            }
        } else {
            // If no pattern match, use the whole string as material
            result["material"] = target
        }

        return result
    }

    /**
     * Checks if an ItemStack matches a collection target
     * @param item The ItemStack to check
     * @param target The collection target string
     * @return True if the item matches the target
     */
    fun itemMatchesTarget(
        item: ItemStack,
        target: String,
    ): Boolean {
        val properties = parseCollectionTarget(target)

        // Check material first
        val materialName = properties["material"]?.toString() ?: return false
        if (!item.type.name.equals(materialName, ignoreCase = true)) {
            return false
        }

        // Check name if specified
        if (properties.containsKey("name")) {
            val itemName = item.itemMeta?.displayName()?.let { plugin.miniMessage.serialize(it) } ?: ""
            val targetName = properties["name"]?.toString() ?: ""
            if (!itemName.contains(targetName)) {
                return false
            }
        }

        // Check lore if specified
        if (properties.containsKey("lore")) {
            val targetLore = properties["lore"]?.toString() ?: ""
            val itemLore =
                item.itemMeta?.lore()?.joinToString("\n") {
                    plugin.miniMessage.serialize(it)
                } ?: ""

            if (!itemLore.contains(targetLore)) {
                return false
            }
        }

        return true
    }

    @EventHandler
    fun onPlayerChangeLocation(event: PlayerLocationChangeEvent) {
        val to = event.to ?: return
        val from = event.from

        updateQuestProgress(event.player, to)

        // DEBUG
        plugin.logger.info("[LocationChange] ${from?.name} -> ${to.name}")
    }

    @EventHandler
    fun onPlayerCraftItem(event: CraftItemEvent) {
        val player = event.whoClicked
        if (player !is Player) return

        if (event.isLeftClick || event.isRightClick) {
            val itemName =
                event.currentItem?.itemMeta?.displayName()?.let {
                    plugin.miniMessage.serialize(it)
                }
                    ?: event.currentItem?.type?.name
                    ?: return // Fallback to item type if display name is not set
            updateQuestProgress(player, ObjectiveType.CRAFT, itemName)
        }
    }

    @EventHandler
    fun onPlayerUseItem(event: PlayerInteractEvent) {
        if (!event.hasItem()) return

        val item = event.item ?: return
        val itemName =
            item.itemMeta?.displayName()?.let {
                plugin.miniMessage.serialize(it)
            } ?: item.type.name

        // Only track item usage for right-click actions on items
        if (event.action.isRightClick && item.type.isItem) {
            updateQuestProgress(event.player, ObjectiveType.USE, itemName)
        }
    }

    @EventHandler
    fun onConversationStart(event: ConversationStartEvent) {
        val player = event.player

        // For each NPC in the conversation, update the TALK objective
        for (npc in event.npcs) {
            if (npc.name == null) continue
            val npcName = npc.name
            updateQuestProgress(player, ObjectiveType.TALK, npcName)
        }
    }

    /**
     * Helper method to update quest progress for all appropriate quests
     *
     * TODO: Move this to QuestManager
     */
    private fun updateQuestProgress(
        player: Player,
        type: ObjectiveType,
        target: String,
    ) {
        // Create a copy of the map entries to avoid ConcurrentModificationException
        val questEntries = HashMap(plugin.questManager.getPlayerQuests(player.uniqueId))

        for ((questId, playerQuest) in questEntries) {
            if (playerQuest.status == QuestStatus.IN_PROGRESS) {
                plugin.questManager.updateObjectiveProgress(
                    player,
                    questId,
                    type,
                    target,
                )
            }
        }
    }

    // Explore location variant
    private fun updateQuestProgress(
        player: Player,
        location: StoryLocation,
    ) {
        updateQuestProgress(player, ObjectiveType.EXPLORE, location.name)
    }

    @EventHandler
    fun onQuestBookInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR &&
            event.action != Action.RIGHT_CLICK_BLOCK
        ) {
            return
        }
        if (event.item?.type != Material.WRITTEN_BOOK) return

        val meta = event.item?.itemMeta as? BookMeta ?: return
        val targetKey = NamespacedKey(plugin, "quest_book_target")
        val targetUuidString =
            meta.persistentDataContainer.get(
                targetKey,
                PersistentDataType.STRING,
            )
                ?: return

        try {
            val targetUuid = UUID.fromString(targetUuidString)
            val targetPlayer = Bukkit.getOfflinePlayer(targetUuid)

            // Cancel the default book opening
            event.isCancelled = true

            // Open custom quest book interface
            val commandUtils = QuestCommandUtils()
            if (targetPlayer.isOnline) {
                commandUtils.openJournalBook(event.player, targetPlayer.player)
            } else {
                commandUtils.openJournalBook(event.player, targetPlayer)
            }
        } catch (e: IllegalArgumentException) {
            plugin.logger.warning("Invalid UUID in quest book: $targetUuidString")
        }
    }
}
