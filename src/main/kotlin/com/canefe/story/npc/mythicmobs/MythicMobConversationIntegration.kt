package com.canefe.story.npc.mythicmobs

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.conversation.Conversation
import com.canefe.story.util.Msg.sendError
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.*
import java.util.regex.Pattern

/**
 * Handles integration between MythicMobs and the conversation system
 */
class MythicMobConversationIntegration(
    private val plugin: Story,
) : Listener {
    private val mythicMobsHandler = MythicMobsHandler(plugin)
    private val adapterRegistry = mutableMapOf<UUID, MythicMobNPCAdapter>()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    /**
     * Checks if an entity is a MythicMob that can participate in conversations
     */
    fun isMythicMobNPC(entity: Entity): Boolean = mythicMobsHandler.isMythicMob(entity)

    fun endConversation(npc: StoryNPC) {
        if (npc is MythicMobNPCAdapter) {
            val entity = npc.entity
            if (entity != null) {
                mythicMobsHandler.setMythicMobInConversation(entity, false)
            }
        }
    }

    /**
     * Get or create an NPC adapter for a MythicMob entity
     * This converts a MythicMob into a form that works with your conversation system
     */
    fun getOrCreateNPCAdapter(entity: Entity): StoryNPC? {
        // Check cache first
        if (adapterRegistry.containsKey(entity.uniqueId)) {
            return adapterRegistry[entity.uniqueId]
        }

        // Not found, create new if it's a MythicMob
        if (!mythicMobsHandler.isMythicMob(entity)) return null

        val mobData = mythicMobsHandler.getMythicMobData(entity) ?: return null
        val cleanName = MythicMobFormatHelper.extractCleanName(mobData.displayName)

        val adapter = MythicMobNPCAdapter(entity, cleanName, mobData.internalName)

        // Cache for future use
        adapterRegistry[entity.uniqueId] = adapter
        return adapter
    }

    /**
     * Handle player interaction with MythicMobs
     */
    @EventHandler
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        if (!plugin.config.mythicMobsEnabled) {
            return // Skip if MythicMobs integration is disabled
        }

        if (event.hand != EquipmentSlot.HAND) {
            return // Ignore off-hand interactions
        }

        val player = event.player
        val entity = event.rightClicked

        // Skip if not a MythicMob
        if (!mythicMobsHandler.isMythicMob(entity)) return

        val npcAdapter = getOrCreateNPCAdapter(entity) ?: return

        handleConversation(player, npcAdapter, true)
    }

    /**
     * Gets MythicMobs near a player within a given radius
     */
    fun getNearbyMythicMobs(
        player: Player,
        radius: Double,
    ): List<Entity> {
        // Get all entities in a cube around the player
        val nearbyEntities = player.getNearbyEntities(radius, radius, radius)

        // Filter for MythicMobs that are actually within spherical radius
        return nearbyEntities.filter { entity ->
            mythicMobsHandler.isMythicMob(entity) &&
                player.location.distanceSquared(entity.location) <= radius * radius
        }
    }

    /**
     * Handles the conversation logic between a player and a MythicMob.
     */
    private fun handleConversation(
        player: Player,
        npc: StoryNPC,
        isDirectInteraction: Boolean,
    ): Boolean {
        // Check if player has disabled interactions
        if (plugin.playerManager.isPlayerDisabled(player)) {
            plugin.playerManager.playerCurrentNPC[player.uniqueId] = npc.uniqueId
            return true
        }

        val npcName = npc.name
        val conversationManager = plugin.conversationManager

        // Handle MythicMob-specific setup if this is a MythicMobNPCAdapter
        val entity = if (npc is MythicMobNPCAdapter) npc.entity else null

        try {
            // Check if player is already in a conversation
            val conversation = conversationManager.getConversation(player)
            if (conversation != null) {
                // Only end the conversation on direct interaction (right-click)
                if (isDirectInteraction && conversation.hasNPC(npc)) {
                    conversationManager.endConversation(conversation)
                    // If this was a MythicMob, end its conversation state
                    if (entity != null) {
                        mythicMobsHandler.setMythicMobInConversation(entity, false)
                    }
                    return true
                }

                // Check if NPC is disabled/busy
                if (plugin.npcManager.isNPCDisabled(npc)) {
                    player.sendError("<yellow>$npcName</yellow> is busy.")
                    return true
                }

                if (isDirectInteraction) {
                    // Add this NPC to the existing conversation
                    conversation.addNPC(npc)

                    // Set MythicMob in conversation mode
                    if (entity != null) {
                        mythicMobsHandler.setMythicMobInConversation(entity, true)
                        mythicMobsHandler.lookAtTarget(entity, player)
                    }
                }

                return true
            }
            // Player is not in a conversation
            else {
                // Check if NPC is disabled/busy
                if (plugin.npcManager.isNPCDisabled(npc)) {
                    player.sendError("<yellow>$npcName</yellow> is busy.")
                    return true
                }

                // check if there is any CitizensNPC nearby before starting a conversation
                val nearbyCitizensNPCs = plugin.npcUtils.getNearbyNPCs(player, plugin.config.chatRadius)

                val existingConversation =
                    conversationManager.getConversation(npc) ?: run {
                        if (nearbyCitizensNPCs.isNotEmpty()) {
                            return true
                        }

                        // No existing conversation, create a new one
                        val npcs = ArrayList<StoryNPC>()
                        npcs.add(npc)

                        // Set MythicMob in conversation mode
                        if (entity != null) {
                            mythicMobsHandler.setMythicMobInConversation(entity, true)
                            mythicMobsHandler.lookAtTarget(entity, player)
                        }

                        conversationManager.startConversation(player, npcs)
                        return true
                    }

                // Set MythicMob in conversation mode if joining existing conversation
                if (entity != null) {
                    mythicMobsHandler.setMythicMobInConversation(entity, true)
                    mythicMobsHandler.lookAtTarget(entity, player)
                }

                existingConversation.addPlayer(player)
                return true
            }
        } catch (e: Exception) {
            plugin.logger.warning("Error handling conversation with MythicMob: ${e.message}")
            player.sendError("An error occurred while processing the conversation.")

            // Clean up in case of error
            if (entity != null) {
                mythicMobsHandler.setMythicMobInConversation(entity, false)
            }

            return false
        }
    }

    /**
     * Start a conversation with a MythicMob
     */
    fun startConversation(
        player: Player,
        entity: Entity,
    ): Boolean {
        if (!mythicMobsHandler.isMythicMob(entity)) return false

        val adapter = getOrCreateNPCAdapter(entity) ?: return false

        // Mark as in conversation
        mythicMobsHandler.setMythicMobInConversation(entity, true)

        // Tell the entity to look at the player
        mythicMobsHandler.lookAtTarget(entity, player)

        // Start conversation using the conversation manager
        try {
            val conversationManager = plugin.conversationManager
            val npcs = listOf(adapter)
            conversationManager.startConversation(player, npcs)

            // Schedule task to check if conversation ended
            Bukkit.getScheduler().runTaskLater(
                plugin,
                Runnable {
                    if (!conversationManager.isInConversation(adapter)) {
                        mythicMobsHandler.setMythicMobInConversation(entity, false)
                    }
                },
                20L,
            ) // Check after 1 second

            return true
        } catch (e: Exception) {
            plugin.logger.severe("Error starting conversation with MythicMob: ${e.message}")
            e.printStackTrace()
            mythicMobsHandler.setMythicMobInConversation(entity, false)
            return false
        }
    }

    /**
     * Clean up resources when plugin disables
     */
    fun onDisable() {
        adapterRegistry.clear()
        mythicMobsHandler.onDisable()
    }

    /**
     * Adapter class that wraps a MythicMob entity to behave like a StoryNPC
     * Implements StoryNPC interface to work with the conversation system
     */
    inner class MythicMobNPCAdapter(
        private val backingEntity: Entity,
        private val displayName: String,
        private val internalName: String,
    ) : StoryNPC {
        private val _uniqueId = backingEntity.uniqueId
        private val _id = backingEntity.entityId

        // Track conversations this MythicMob is part of
        private var currentConversation: Conversation? = null

        override val name: String get() = displayName
        override val id: Int get() = _id
        override val uniqueId: UUID get() = _uniqueId
        override val entity: Entity? get() = backingEntity
        override val isSpawned: Boolean get() = !backingEntity.isDead
        override val location: Location? get() = backingEntity.location

        // -- Navigation --

        override fun navigateTo(location: Location) {
            // MythicMobs don't use Citizens navigation
        }

        override fun navigateTo(
            location: Location,
            speedModifier: Float,
            range: Float,
            distanceMargin: Double,
        ) {
            // MythicMobs don't use Citizens navigation
        }

        override fun navigateTo(entity: Entity) {
            // MythicMobs don't use Citizens navigation
        }

        override fun navigateTo(
            entity: Entity,
            speedModifier: Float,
            range: Float,
            distanceMargin: Double,
        ) {
            // MythicMobs don't use Citizens navigation
        }

        override fun cancelNavigation() {
            // MythicMobs don't use Citizens navigation
        }

        override val isNavigating: Boolean get() = false

        // -- Lifecycle --

        override fun spawn(location: Location): Boolean = true // Already spawned

        override fun despawn(): Boolean = true // We don't control MythicMob despawn

        override fun teleport(location: Location) {
            backingEntity.teleport(location)
        }

        override fun clone(): StoryNPC = this

        // -- Combat --

        override fun attack(target: Player) {
            // MythicMobs handle combat through their own skill system
        }

        override fun stopAttacking(target: Player) {
            // MythicMobs handle combat through their own skill system
        }

        // -- Following --

        override fun follow(target: Player) {
            // MythicMobs handle following through their own AI system
        }

        override fun stopFollowing() {
            // MythicMobs handle following through their own AI system
        }

        override val isFollowing: Boolean get() = false

        // -- Rotation --

        override fun lookAt(target: Entity) {
            mythicMobsHandler.lookAtTarget(backingEntity, target)
        }

        override fun rotateTo(
            yaw: Float,
            pitch: Float,
        ) {
            if (backingEntity is org.bukkit.entity.LivingEntity) {
                val loc = backingEntity.location.clone()
                loc.yaw = yaw
                loc.pitch = pitch
                backingEntity.teleport(loc)
            }
        }

        // -- Pose --

        override fun sit(location: Location?) {
            // MythicMobs don't support Citizens pose traits
        }

        override fun stand() {
            // MythicMobs don't support Citizens pose traits
        }

        override val isSitting: Boolean get() = false

        // -- Skin --

        override val skinTexture: String? get() = null
        override val skinSignature: String? get() = null

        override fun setSkin(
            name: String,
            signature: String,
            texture: String,
        ) {
            // MythicMobs don't support Citizens skin traits
        }

        // -- Source access --

        @Suppress("UNCHECKED_CAST")
        override fun <T> unwrap(type: Class<T>): T? = if (type.isInstance(backingEntity)) backingEntity as T else null

        // Set conversation for tracking
        fun setConversation(conversation: Conversation?) {
            currentConversation = conversation
        }

        override fun equals(other: Any?): Boolean = other is MythicMobNPCAdapter && _uniqueId == other._uniqueId

        override fun hashCode(): Int = _uniqueId.hashCode()

        override fun toString(): String = "MythicMobNPCAdapter(name=$displayName, id=$_id)"
    }

    /**
     * Helper class for MythicMob name formatting
     */
    object MythicMobFormatHelper {
        // Pattern to match color codes like §e, §7, etc.
        private val COLOR_PATTERN = Pattern.compile("§[0-9a-fk-orx]")

        // Pattern to match mythicmobs variables like <caster.level>
        private val VARIABLE_PATTERN = Pattern.compile("<[^>]+>")

        // Pattern to remove "LV" or "Level" from the name
        private val LEVEL_PATTERN = Pattern.compile("\\s*LV\\s*|\\s*Level\\s*", Pattern.CASE_INSENSITIVE)

        /**
         * Extracts the clean name from a MythicMob display name by removing:
         * - Legacy color codes (§e, §7, etc.)
         * - MythicMobs variables (<mob.level>, <caster.level>, etc.)
         */
        fun extractCleanName(displayName: String): String {
            // First remove color codes
            var result = COLOR_PATTERN.matcher(displayName).replaceAll("")

            // Then remove variables
            result = VARIABLE_PATTERN.matcher(result).replaceAll("")

            // Finally remove "LV" or "Level"
            result = LEVEL_PATTERN.matcher(result).replaceAll("")

            // Trim any extra whitespace
            return result.trim()
        }
    }
}
