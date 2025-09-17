package com.canefe.story.quest

import com.canefe.story.Story
import com.canefe.story.api.event.ConversationStartEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.inventory.ItemStack
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
    fun onPlayerMove(event: PlayerMoveEvent) {
        // Only check when crossing chunk boundaries to avoid excessive processing
        val fromChunk = event.from.chunk
        val toChunk = event.to.chunk

        if (fromChunk.x == toChunk.x && fromChunk.z == toChunk.z) {
            return
        }

        // Check if the player has entered a tracked location
        val locName = plugin.locationManager.getLocationByPosition(event.to)?.name
        if (locName != null) {
            updateQuestProgress(event.player, ObjectiveType.EXPLORE, locName)
        }
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
}
