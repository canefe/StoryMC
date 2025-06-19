package com.canefe.story.npc.mythicmobs

import com.canefe.story.Story
import com.canefe.story.conversation.Conversation
import com.canefe.story.util.Msg.sendError
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.ai.Navigator
import net.citizensnpcs.api.event.DespawnReason
import net.citizensnpcs.api.event.SpawnReason
import net.citizensnpcs.api.npc.BlockBreaker
import net.citizensnpcs.api.npc.MetadataStore
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.trait.Trait
import net.citizensnpcs.api.util.DataKey
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.*
import java.util.function.Consumer
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

	fun endConversation(npc: NPC) {
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
	fun getOrCreateNPCAdapter(entity: Entity): NPC? {
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
		npc: NPC,
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
				val nearbyCitizensNPCs = plugin.getNearbyNPCs(player, plugin.config.chatRadius)

				val existingConversation =
					conversationManager.getConversation(npc) ?: run {
						if (nearbyCitizensNPCs.isNotEmpty()) {
							return true
						}

						// No existing conversation, create a new one
						val npcs = ArrayList<NPC>()
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
	 * Adapter class that wraps a MythicMob entity to behave like a Citizens NPC
	 * Implements Citizens NPC interface to work with your conversation system
	 */
	inner class MythicMobNPCAdapter(
		private val entity: Entity,
		private val displayName: String,
		private val internalName: String,
	) : NPC {
		private val uniqueId = UUID.randomUUID()
		private val id = entity.entityId
		private val traits = mutableMapOf<Class<out Trait>, Trait>()

		// Track conversations this MythicMob is part of
		private var currentConversation: Conversation? = null

		// Required implementation of NPC interface
		override fun getId(): Int = id

		override fun getName(): String = displayName

		override fun getEntity(): Entity? = entity

		override fun isSpawned(): Boolean = !entity.isDead

		override fun getUniqueId(): UUID = uniqueId

		override fun getFullName(): String = displayName

		override fun getRawName(): String = displayName

		override fun getStoredLocation(): Location = entity.location

		// Special rotation trait implementation for MythicMobs
		inner class RotationTrait : Trait("rotation") {
			val physicalSession =
				object {
					fun rotateToFace(target: Entity) {
						mythicMobsHandler.lookAtTarget(entity, target)
					}

					fun rotateToHave(
						yaw: Float,
						pitch: Float,
					) {
						if (entity is org.bukkit.entity.LivingEntity) {
							val loc = entity.location.clone()
							loc.yaw = yaw
							loc.pitch = pitch
							entity.teleport(loc)
						}
					}
				}

			override fun onSpawn() {}

			override fun onDespawn() {}

			override fun onAttach() {}

			override fun onRemove() {}

			override fun onCopy() {}

			override fun load(key: DataKey) {}

			override fun save(key: DataKey) {}
		}

		// Trait management for Citizens API compatibility
		@Suppress("UNCHECKED_CAST")
		override fun <T : Trait> getTrait(trait: Class<T>): T? {
			if (trait.name == "net.citizensnpcs.api.trait.trait.RotationTrait") {
				if (!traits.containsKey(trait)) {
					traits[trait as Class<out Trait>] = RotationTrait()
				}
				return traits[trait] as? T
			}
			return null
		}

		override fun <T : Trait> getTraitNullable(trait: Class<T>): T? = getTrait(trait)

		override fun getTraits(): MutableIterable<Trait> {
			TODO("Not yet implemented")
		}

		@Suppress("UNCHECKED_CAST")
		override fun <T : Trait> getOrAddTrait(trait: Class<T>): T =
			getTrait(trait) ?: when {
				trait.name == "net.citizensnpcs.api.trait.trait.RotationTrait" -> {
					val rotTrait = RotationTrait() as T
					traits[trait as Class<out Trait>] = rotTrait
					rotTrait
				}
				else -> throw UnsupportedOperationException("Cannot add trait $trait to MythicMob")
			}

		override fun hasTrait(trait: Class<out Trait>): Boolean =
			trait.name == "net.citizensnpcs.api.trait.trait.RotationTrait" || traits.containsKey(trait)

		// Set conversation for tracking
		fun setConversation(conversation: Conversation?) {
			currentConversation = conversation
		}

		override fun getOwningRegistry() = CitizensAPI.getNPCRegistry()

		override fun getNavigator(): Navigator =
			throw UnsupportedOperationException("Navigator not implemented for MythicMobs")

		override fun removeTrait(trait: Class<out Trait>) {
			traits.remove(trait)
		}

		override fun addTrait(trait: Trait) {
			traits[trait.javaClass] = trait
		}

		override fun addTrait(trait: Class<out Trait>) {
			try {
				val instance = trait.getDeclaredConstructor().newInstance()
				traits[trait] = instance
			} catch (e: Exception) {
				// Ignore if can't create trait
			}
		}

		override fun destroy() {
			// Nothing to destroy for our adapter
		}

		override fun destroy(p0: CommandSender?) {
			TODO("Not yet implemented")
		}

		override fun save(key: DataKey) {
			// Nothing to save for our adapter
		}

		override fun load(key: DataKey) {
			// Nothing to load for our adapter
		}

		// Other Citizens NPC interface methods
		override fun spawn(location: Location): Boolean = true // Already spawned

		override fun spawn(
			p0: Location?,
			p1: SpawnReason?,
		): Boolean {
			TODO("Not yet implemented")
		}

		override fun spawn(
			p0: Location?,
			p1: SpawnReason?,
			p2: Consumer<Entity?>?,
		): Boolean {
			TODO("Not yet implemented")
		}

		override fun despawn(): Boolean = true // We don't control MythicMob despawn

		override fun despawn(p0: DespawnReason?): Boolean {
			TODO("Not yet implemented")
		}

		override fun isProtected() = false

		override fun setProtected(protect: Boolean) {}

		override fun faceLocation(location: Location) {
			mythicMobsHandler.lookAtTarget(entity, location.world?.getEntities()?.firstOrNull() ?: return)
		}

		override fun getBlockBreaker(
			p0: Block?,
			p1: BlockBreaker.BlockBreakerConfiguration?,
		): BlockBreaker {
			TODO("Not yet implemented")
		}

		override fun getMinecraftUniqueId(): UUID = entity.uniqueId

		override fun shouldRemoveFromPlayerList(): Boolean = true

		override fun shouldRemoveFromTabList(): Boolean = true

		override fun getDefaultGoalController() = null

		override fun getDefaultSpeechController() = null

		override fun getItemProvider() = null

		override fun setItemProvider(provider: java.util.function.Supplier<org.bukkit.inventory.ItemStack>) {}

		override fun isFlyable() = false

		override fun setFlyable(flyable: Boolean) {}

		override fun isPushableByFluids() = false

		override fun isHiddenFrom(player: Player) = false

		override fun setName(name: String) {}

		override fun teleport(
			location: Location,
			cause: PlayerTeleportEvent.TeleportCause,
		) {}

		override fun useMinecraftAI() = false

		override fun setUseMinecraftAI(use: Boolean) {}

		override fun setBukkitEntityType(type: EntityType) {}

		override fun requiresNameHologram() = false

		override fun setAlwaysUseNameHologram(use: Boolean) {}

		override fun setSneaking(sneaking: Boolean) {}

		override fun setMoveDestination(location: Location) {}

		override fun scheduleUpdate(update: NPC.NPCUpdate) {}

		override fun isUpdating(update: NPC.NPCUpdate) = false

		override fun addRunnable(runnable: Runnable) {}

		override fun copy() = this

		override fun data(): MetadataStore {
			TODO("Not yet implemented")
		}

		override fun clone() = this
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
