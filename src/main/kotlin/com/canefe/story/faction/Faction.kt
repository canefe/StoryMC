package com.canefe.story.faction

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Chest
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import kotlin.random.Random

/**
 * Core Faction class representing settlements, towns, or other groups
 */
data class Faction(
	val id: String,
	var name: String,
	var description: String = "",
	var config: FactionConfig = FactionConfig(),
) {
	// In Faction class, add settlements
	val settlements = mutableListOf<Settlement>()

	// Add leader property (similar to settlement leaders)
	var leader: Leader? = null

	// Population stats
	var populationCount: Int = 0
		private set

	// Faction happiness (0.0-1.0)
	var happiness: Double = 0.5
		private set

	// Power represents faction's strength/influence
	var power: Double = 0.0
		private set

	// Faction level (determines capabilities)
	var level: Int = 1
		private set

	// Experience points toward next level
	var experience: Double = 0.0
		private set

	// Named Citizens (NPCs or players with special roles)
	val namedMembers = mutableMapOf<String, FactionMember>()

	// Treasury balance (separate from physical items in chests)
	var treasuryBalance: BigDecimal = BigDecimal.ZERO

	// Faction stats for display
	val stats = mutableMapOf<String, Double>()

	// History of significant events
	val history = mutableListOf<HistoryEntry>()

	/**
	 * Initialize stats for a new faction
	 */
	init {
		// Set initial stats
		stats["income"] = 0.0
		stats["expenses"] = 0.0
		stats["growth"] = 0.0
		stats["militaryStrength"] = 0.0
		stats["prosperity"] = 0.0

		// Record founding in history
		addHistoryEntry("Faction founded", "The settlement was established")
	}

	/**
	 * Calculate how much xp is needed for next level
	 */
	fun experienceForNextLevel(): Double = 1000.0 * level * 1.5

	/**
	 * Add experience to the faction
	 */
	fun addExperience(amount: Double): Boolean {
		experience += amount
		val requiredXp = experienceForNextLevel()

		// Check for level up
		if (experience >= requiredXp) {
			experience -= requiredXp
			level++
			addHistoryEntry("Level Up", "The faction has grown to level $level")
			return true
		}

		return false
	}

	// Add method to add settlement
	fun addSettlement(settlement: Settlement): Settlement {
		settlement.factionId = id
		settlements.add(settlement)
		return settlement
	}

	// Add method to create and add a new settlement
	fun createSettlement(name: String, isCapital: Boolean = false): Settlement {
		val settlementId = "${id}_${name.lowercase().replace(" ", "_")}"
		val settlement = Settlement(settlementId, name, isCapital)
		settlement.factionId = id

		// Create a leader for the settlement
		val leader = Leader.generateRandom("settlement")
		settlement.leader = leader

		// Add to faction's settlements
		settlements.add(settlement)

		// Add to history
		history.add(
			HistoryEntry(
				Date(),
				"Settlement Created",
				"The settlement of $name has been established.",
			),
		)

		return settlement
	}

	// Add method to remove settlement
	fun removeSettlement(settlementId: String): Boolean {
		val settlement = settlements.find { it.id == settlementId } ?: return false
		return settlements.remove(settlement)
	}

	// Add method to get settlement by ID
	fun getSettlement(id: String): Settlement? = settlements.find { it.id == id }

	// Add method to collect taxes from all settlements
	fun collectSettlementTaxes(): BigDecimal {
		var totalTaxes = BigDecimal.ZERO

		settlements.forEach { settlement ->
			val taxes = settlement.calculateDailyTaxIncome()
			totalTaxes = totalTaxes.add(taxes)

			// Record tax collection
			if (taxes > BigDecimal.ZERO) {
				history.add(
					HistoryEntry(
						Date(),
						"Tax Collection",
						"Collected ${taxes.setScale(2, RoundingMode.HALF_UP)} coins from ${settlement.name}",
					),
				)
			}
		}

		// Add taxes to treasury
		treasuryBalance = treasuryBalance.add(totalTaxes)

		return totalTaxes
	}

	// Handle settlement rebellion
	fun handleRebellion(settlement: Settlement) {
		settlement.addAction(LeaderActionType.REBELLION, "The people of ${settlement.name} have rebelled against $name!")
		addHistoryEntry("Rebellion", "The settlement of ${settlement.name} has risen in rebellion")

		// Depending on martial strength, the rebellion might succeed or fail
		if (power < 100 || Random.nextDouble() < 0.3) {
			// Rebellion succeeds - remove settlement
			addHistoryEntry("Lost Settlement", "${settlement.name} has broken away from $name")
			removeSettlement(settlement.id)
		} else {
			// Rebellion crushed
			settlement.adjustPopulation(-Random.nextInt(5, 20))
			settlement.adjustHappiness(-0.2)
			settlement.adjustLoyalty(0.1) // Fear increases loyalty temporarily
			addHistoryEntry("Rebellion Crushed", "The rebellion in ${settlement.name} was crushed")
		}
	}

	// Enum for categorizing event types
	enum class ActionType {
		DISASTER,
		FESTIVAL,
		CRIME,
		DIPLOMATIC,
		DISCOVERY,
		ECONOMIC,
		MISC,
		TAX_INCREASE,
		REBELLION,
	}

	/**
	 * Set population count and recalculate power
	 */
	fun setPopulation(count: Int) {
		val oldCount = populationCount
		populationCount = maxOf(0, count)

		// Update power based on population change
		updatePower()

		// Record significant population changes
		if (populationCount >= oldCount * 1.5 && populationCount >= oldCount + 5) {
			addHistoryEntry("Population Boom", "Population grew from $oldCount to $populationCount")
		} else if (populationCount <= oldCount * 0.7 && oldCount - populationCount >= 5) {
			addHistoryEntry("Population Decline", "Population fell from $oldCount to $populationCount")
		}
	}

	/**
	 * Set happiness and recalculate power
	 */
	fun setHappiness(value: Double) {
		happiness = value.coerceIn(0.0, 1.0)
		updatePower()
	}

	/**
	 * Add a named member to the faction
	 */
	fun addMember(id: String, name: String, role: String): FactionMember {
		val member = FactionMember(id, name, role)
		namedMembers[id] = member
		return member
	}

	/**
	 * Remove a named member from the faction
	 */
	fun removeMember(id: String): Boolean = namedMembers.remove(id) != null

	/**
	 * Calculate and update faction's power based on various factors
	 */
	fun updatePower() {
		TODO()
	}

	/**
	 * Record a history entry
	 */
	fun addHistoryEntry(title: String, description: String) {
		val entry =
			HistoryEntry(
				Date(),
				title,
				description,
			)
		history.add(entry)

		// Keep history at a reasonable size
		if (history.size > 100) {
			history.removeAt(0)
		}
	}

	/**
	 * Generate a formatted display of faction stats
	 */
	fun getStatsDisplay(): List<String> {
		val result = mutableListOf<String>()
		result.add("${ChatColor.GOLD}=== ${ChatColor.YELLOW}$name ${ChatColor.GOLD}===")
		result.add(
			"${ChatColor.GRAY}Level: ${ChatColor.WHITE}$level ${ChatColor.GRAY}(${ChatColor.AQUA}${experience.toInt()}/${experienceForNextLevel().toInt()} XP${ChatColor.GRAY})",
		)
		result.add("${ChatColor.GRAY}Population: ${ChatColor.WHITE}$populationCount")

		// Generate happiness bar
		val happinessBar = StringBuilder("${ChatColor.GRAY}Happiness: ")
		val bars = (happiness * 10).toInt()
		for (i in 1..10) {
			if (i <= bars) {
				happinessBar.append("${ChatColor.GREEN}|")
			} else {
				happinessBar.append("${ChatColor.RED}|")
			}
		}
		happinessBar.append(" ${ChatColor.WHITE}${(happiness * 100).toInt()}%")
		result.add(happinessBar.toString())

		result.add("${ChatColor.GRAY}Power: ${ChatColor.GOLD}${power.toInt()}")
		result.add("${ChatColor.GRAY}Treasury: ${ChatColor.GOLD}${treasuryBalance.setScale(2, RoundingMode.HALF_UP)} coins")

		return result
	}

	/**
	 * Generate a list of recent history events
	 */
	fun getHistoryDisplay(count: Int = 5): List<String> {
		val result = mutableListOf<String>()
		result.add("${ChatColor.GOLD}=== ${ChatColor.YELLOW}$name History ${ChatColor.GOLD}===")

		val recentHistory = history.takeLast(count).reversed()
		if (recentHistory.isEmpty()) {
			result.add("${ChatColor.GRAY}No historical events recorded.")
			return result
		}

		recentHistory.forEach { entry ->
			val dateStr = entry.formattedDate
			result.add("${ChatColor.GRAY}$dateStr: ${ChatColor.YELLOW}${entry.title}")
			result.add("${ChatColor.GRAY}  ${entry.description}")
		}

		return result
	}

	/**
	 * Convert to a format that can be saved in YAML
	 */
	fun toYamlSection(): Map<String, Any> {
		val data = mutableMapOf<String, Any>()
		data["id"] = id
		data["name"] = name
		data["description"] = description
		data["level"] = level
		data["experience"] = experience
		data["populationCount"] = populationCount
		data["happiness"] = happiness
		data["power"] = power
		data["treasuryBalance"] = treasuryBalance.toString()

		// Save settlements
		val settlementsData = settlements.map { settlement -> settlement.toYamlSection() }
		data["settlements"] = settlementsData

		// Save leader if exists
		leader?.let { data["leader"] = it.toYamlSection() }

		// Save stats
		data["stats"] = HashMap(stats)

		// Save members
		val membersData = mutableMapOf<String, Map<String, String>>()
		namedMembers.forEach { (id, member) ->
			membersData[id] =
				mapOf(
					"name" to member.name,
					"role" to member.role,
				)
		}
		data["members"] = membersData

		// Save history (limited to last 20 entries)
		val historyData =
			history.takeLast(20).map { entry ->
				mapOf(
					"timestamp" to entry.timestamp.time,
					"title" to entry.title,
					"description" to entry.description,
				)
			}
		data["history"] = historyData

		// Save config
		data["config"] = config.serialize()

		return data
	}

	companion object {
		/**
		 * Create a faction from YAML data
		 */
		fun fromYamlSection(data: Map<String, Any?>): Faction? {
			try {
				val id = data["id"] as? String ?: return null
				val name = data["name"] as? String ?: return null
				val description = data["description"] as? String ?: ""

				// Create config
				val configData = data["config"] as? Map<String, Any?> ?: emptyMap()
				val config = FactionConfig.deserialize(configData)

				// Create the faction
				val faction = Faction(id, name, description, config)

				// Set basic properties
				faction.level = (data["level"] as? Number)?.toInt() ?: 1
				faction.experience = (data["experience"] as? Number)?.toDouble() ?: 0.0
				faction.populationCount = (data["populationCount"] as? Number)?.toInt() ?: 0
				faction.happiness = (data["happiness"] as? Number)?.toDouble() ?: 0.5
				faction.power = (data["power"] as? Number)?.toDouble() ?: 0.0

				// Set treasury balance
				val balanceStr = data["treasuryBalance"] as? String
				if (balanceStr != null) {
					try {
						faction.treasuryBalance = BigDecimal(balanceStr)
					} catch (e: Exception) {
						faction.treasuryBalance = BigDecimal.ZERO
					}
				}

				// Load settlements
				val settlementsData = data["settlements"] as? List<Map<String, Any?>>
				settlementsData?.forEach { settlementData ->
					Settlement.fromYamlSection(settlementData)?.let {
						faction.settlements.add(it)
					}
				}

				// Load stats
				val statsData = data["stats"] as? Map<String, Double>
				if (statsData != null) {
					faction.stats.putAll(statsData)
				}

				// Load members
				val membersData = data["members"] as? Map<String, Map<String, String>>
				if (membersData != null) {
					membersData.forEach { (id, memberData) ->
						val memberName = memberData["name"] ?: "Unknown"
						val memberRole = memberData["role"] ?: "Member"
						faction.addMember(id, memberName, memberRole)
					}
				}

				// Load history
				val historyData = data["history"] as? List<Map<String, Any>>
				historyData?.forEach { entryData ->
					val timestamp = (entryData["timestamp"] as? Number)?.toLong() ?: 0
					val title = entryData["title"] as? String ?: "Unknown Event"
					val desc = entryData["description"] as? String ?: ""

					faction.history.add(HistoryEntry(Date(timestamp), title, desc))
				}

				return faction
			} catch (e: Exception) {
				Bukkit.getLogger().severe("Failed to load faction: ${e.message}")
				e.printStackTrace()
				return null
			}
		}
	}
}

/**
 * Configuration for faction properties
 */
data class FactionConfig(
	var workdayHours: Int = 8,
	var minerCount: Int = 20,
	var minerSalary: Double = 0.01,
	var ironValue: Double = 0.10, // Value per iron ore
	var coalValue: Double = 0.01, // Value per coal ore
	val usePhysicalCurrency: Boolean = true,
	val blacklistedDenominations: Set<Double> = setOf(13.5, 1.25, 1.5, 4.5, 9.0),
) {
	/**
	 * Convert to serializable format
	 */
	fun serialize(): Map<String, Any> = mapOf(
		"workdayHours" to workdayHours,
		"minerCount" to minerCount,
		"minerSalary" to minerSalary,
		"ironValue" to ironValue,
		"coalValue" to coalValue,
		"usePhysicalCurrency" to usePhysicalCurrency,
		"blacklistedDenominations" to blacklistedDenominations.toList(),
	)

	companion object {
		/**
		 * Create from serialized data
		 */
		fun deserialize(data: Map<String, Any?>): FactionConfig = FactionConfig(
			workdayHours = (data["workdayHours"] as? Number)?.toInt() ?: 8,
			minerCount = (data["minerCount"] as? Number)?.toInt() ?: 20,
			minerSalary = (data["minerSalary"] as? Number)?.toDouble() ?: 0.01,
			ironValue = (data["ironValue"] as? Number)?.toDouble() ?: 0.10,
			coalValue = (data["coalValue"] as? Number)?.toDouble() ?: 0.01,
			usePhysicalCurrency = data["usePhysicalCurrency"] as? Boolean ?: true,
			blacklistedDenominations =
			(data["blacklistedDenominations"] as? List<Number>)?.map { it.toDouble() }?.toSet()
				?: setOf(13.5, 1.25, 1.5, 4.5, 9.0),
		)
	}
}

/**
 * Represents a named member of the faction (player or NPC)
 */
data class FactionMember(
	val id: String,
	var name: String,
	var role: String,
	var salary: Double = 0.0,
	var happiness: Double = 0.5,
	var influence: Double = 1.0,
)

/**
 * Enum for resource types managed by factions
 */
enum class ResourceType(val displayName: String) {
	FOOD("Food"),
	WOOD("Wood"),
	STONE("Stone"),
	IRON("Iron"),
	COAL("Coal"),
}

/**
 * Historical event in faction's timeline
 */
data class HistoryEntry(val timestamp: Date, val title: String, val description: String) {
	val formattedDate: String
		get() {
			val calendar = Calendar.getInstance()
			calendar.time = timestamp

			val year = calendar.get(Calendar.YEAR)
			val month = calendar.get(Calendar.MONTH) + 1
			val day = calendar.get(Calendar.DAY_OF_MONTH)

			return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
		}

	// toyamlsection
	fun toYamlSection(): Map<String, Any> = mapOf(
		"timestamp" to timestamp.time,
		"title" to title,
		"description" to description,
	)
}

/**
 * Data class to store chest locations persistently
 */
data class ChestLocation(val world: String, val x: Int, val y: Int, val z: Int) {
	/**
	 * Convert location back to a chest block
	 */
	fun toChest(): Chest? {
		val world = Bukkit.getWorld(world) ?: return null
		val block = world.getBlockAt(x, y, z)

		if (block.type != Material.CHEST) {
			return null
		}

		return block.state as? Chest
	}

	fun getLocation() = Bukkit.getWorld(world)?.getBlockAt(x, y, z)

	fun isAdjacentChest(other: ChestLocation): Boolean {
		// Must be in the same world and at the same y-level
		if (world != other.world || y != other.y) {
			return false
		}

		// Check if they're adjacent on x or z axis (but not both)
		val xDiff = Math.abs(x - other.x)
		val zDiff = Math.abs(z - other.z)

		// Adjacent means exactly 1 block away on either x OR z axis (not both)
		return (xDiff == 1 && zDiff == 0) || (xDiff == 0 && zDiff == 1)
	}

	/**
	 * Serialize for storage
	 */
	fun serialize(): Map<String, Any> = mapOf(
		"world" to world,
		"x" to x,
		"y" to y,
		"z" to z,
	)

	fun toYamlSection(): Map<String, Any> = mapOf(
		"world" to world,
		"x" to x,
		"y" to y,
		"z" to z,
	)

	companion object {
		/**
		 * Create from a block
		 */
		fun fromBlock(block: Block): ChestLocation = ChestLocation(
			block.world.name,
			block.x,
			block.y,
			block.z,
		)

		/**
		 * Deserialize from stored data
		 */
		fun deserialize(data: Map<String, Any>): ChestLocation? {
			val world = data["world"] as? String ?: return null
			val x = (data["x"] as? Number)?.toInt() ?: return null
			val y = (data["y"] as? Number)?.toInt() ?: return null
			val z = (data["z"] as? Number)?.toInt() ?: return null

			return ChestLocation(world, x, y, z)
		}
	}
}
