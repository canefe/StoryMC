package com.canefe.story.faction

import com.canefe.story.Story
import me.casperge.realisticseasons.calendar.DayChangeEvent
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.io.File
import java.util.*

class FactionManager(
	private val plugin: Story,
) : Listener {
	private val factions = mutableMapOf<String, Faction>()
	private val dataFolder = File(plugin.dataFolder, "factions")
	val settlementNPCService: SettlementNPCService

	init {
		// Create data folder if it doesn't exist
		if (!dataFolder.exists()) {
			dataFolder.mkdirs()
		}

		settlementNPCService = SettlementNPCService(plugin)

		// Register events
		plugin.server.pluginManager.registerEvents(this, plugin)

		// Load all factions
		loadAllFactions()

		// Schedule daily updates as backup in case event doesn't fire
		plugin.server.scheduler.scheduleSyncRepeatingTask(
			plugin,
			{ dailyUpdate() },
			1200L, // 1 minute delay (in ticks)
			24000L, // 20 minutes (in ticks) - for testing, would be longer in production
		)

		plugin.logger.info("Faction Manager initialized with ${factions.size} factions")
	}

	/**
	 * Listen for day change events from RealisticSeasons
	 */
	@EventHandler
	fun onDayChange(event: DayChangeEvent) {
		plugin.logger.info("Day changed: Processing daily dynamics for all settlements")
		processDailySettlementDynamics()
	}

	/**
	 * Process daily dynamics for all settlements in all factions
	 */
	fun processDailySettlementDynamics() {
		plugin.logger.info("Processing daily dynamics for ${factions.size} factions and their settlements")

		var settlementCount = 0
		factions.values.forEach { faction ->
			faction.settlements.forEach { settlement ->
				try {
					settlement.processDailyDynamics(plugin)
					settlementCount++
				} catch (e: Exception) {
					plugin.logger.severe("Error processing daily dynamics for settlement ${settlement.name}: ${e.message}")
					e.printStackTrace()
				}
			}
		}

		// Save all factions after processing
		saveAllFactions()
		plugin.logger.info("Finished processing daily dynamics for $settlementCount settlements")
	}

	/**
	 * Get a faction by ID
	 */
	fun getFaction(id: String): Faction? = factions[id.lowercase(Locale.getDefault())]

	/**
	 * Create a new faction
	 */
	fun createFaction(
		id: String,
		name: String,
		description: String = "",
	): Faction? {
		// Check if faction already exists
		if (factions.containsKey(id.lowercase(Locale.getDefault()))) {
			return null
		}

		val faction = Faction(id.lowercase(Locale.getDefault()), name, description)
		factions[id.lowercase(Locale.getDefault())] = faction

		// Save the faction immediately
		saveFaction(faction)

		return faction
	}

	/**
	 * Delete a faction
	 */
	fun deleteFaction(id: String): Boolean {
		val faction = factions.remove(id.lowercase(Locale.getDefault())) ?: return false

		// Delete the faction file
		val factionFile = File(dataFolder, "${id.lowercase(Locale.getDefault())}.yml")
		if (factionFile.exists()) {
			factionFile.delete()
		}

		return true
	}

	/**
	 * Get all factions
	 */
	fun getAllFactions(): Collection<Faction> = factions.values

	/**
	 * Perform daily updates for all factions
	 */
	private fun dailyUpdate() {
		plugin.logger.info("Running daily update for ${factions.size} factions")

		factions.values.forEach { faction ->
			try {
				saveFaction(faction)
			} catch (e: Exception) {
				plugin.logger.severe("Error updating faction ${faction.name}: ${e.message}")
				e.printStackTrace()
			}
		}
	}

	/**
	 * Handle inventory close for treasury chests

	 @EventHandler
	 fun onInventoryClose(event: InventoryCloseEvent) {
	 val player = event.player as? Player ?: return
	 val block = event.inventory.location?.block ?: return

	 // Check if the block is a chest
	 if (block.type != Material.CHEST) {
	 return
	 }

	 // Check if this chest is a treasury chest for any faction
	 var faction: Faction? = null
	 val location = ChestLocation.fromBlock(block)

	 for (fac in factions.values) {
	 if (fac.treasuryChests.any { it == location || it.isAdjacentChest(location) }) {
	 faction = fac
	 break
	 }
	 }

	 // If not a treasury chest, stop
	 if (faction == null) {
	 return
	 }

	 // Calculate total money in all treasury chests
	 var totalMoney = BigDecimal.ZERO
	 for (chestLoc in faction.treasuryChests) {
	 val chest = chestLoc.getLocation() ?: continue
	 if (chest.type == Material.CHEST) {
	 val chestMoney = faction.convertChestToMoney(chest)
	 totalMoney = totalMoney.add(chestMoney)
	 }
	 }

	 // Get current balance
	 val beforeMoney = faction.treasuryBalance

	 // Calculate difference
	 val diffMoney = totalMoney.subtract(beforeMoney)

	 // Update town account with the new total
	 faction.treasuryBalance = totalMoney

	 // Send message to player about the change
	 when {
	 diffMoney > BigDecimal.ZERO -> {
	 val diffMoneyValue = diffMoney.toString()
	 player.sendMessage("§aAdded §6₿$diffMoneyValue §ato town account!")
	 }
	 diffMoney < BigDecimal.ZERO -> {
	 val diffMoneyValue = diffMoney.abs().toString()
	 player.sendMessage("§cRemoved §6₿$diffMoneyValue §cfrom town account!")
	 }
	 }

	 // Save faction
	 saveFaction(faction)
	 }
	 */

	/**
	 * Load all factions from storage
	 */
	private fun loadAllFactions() {
		dataFolder.listFiles()?.filter { it.isFile && it.extension == "yml" }?.forEach { file ->
			try {
				val config = YamlConfiguration.loadConfiguration(file)
				val data = config.getValues(false)

				Faction.fromYamlSection(data)?.let { faction ->
					factions[faction.id.lowercase(Locale.getDefault())] = faction
					plugin.logger.info("Loaded faction: ${faction.name}")
				}
			} catch (e: Exception) {
				plugin.logger.severe("Failed to load faction from file ${file.name}: ${e.message}")
				e.printStackTrace()
			}
		}
	}

	/**
	 * Save a faction to storage
	 */
	fun saveFaction(faction: Faction) {
		try {
			val factionFile = File(dataFolder, "${faction.id.lowercase(Locale.getDefault())}.yml")
			val config = YamlConfiguration()

			// Get serialized faction data
			val data = faction.toYamlSection()

			// Add all values to config
			for ((key, value) in data) {
				config.set(key, value)
			}

			// Save to file
			config.save(factionFile)
		} catch (e: Exception) {
			plugin.logger.severe("Failed to save faction ${faction.name}: ${e.message}")
			e.printStackTrace()
		}
	}

	/**
	 * Save all factions
	 */
	fun saveAllFactions() {
		factions.values.forEach { saveFaction(it) }
	}

	fun factionExists(id: String): Boolean = factions.containsKey(id.lowercase(Locale.getDefault()))

	fun load() {
		// Load all factions from storage
		loadAllFactions()
	}

	/**
	 * Clean up when plugin disables
	 */
	fun shutdown() {
		saveAllFactions()
		factions.clear()
	}

	fun getFactionSettlementIds(): List<String> {
		// Get all faction settlements
		return factions.values
			.flatMap { it.settlements }
			.map { it.id }
			.distinct()
	}

	fun findSettlement(settlementId: String): Pair<Faction, Settlement>? {
		// Find the faction that owns the settlement
		for (faction in factions.values) {
			val settlement = faction.settlements.find { it.id == settlementId }
			if (settlement != null) {
				return Pair(faction, settlement)
			}
		}
		return null
	}
}
