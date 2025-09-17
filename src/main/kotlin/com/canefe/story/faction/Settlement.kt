package com.canefe.story.faction

import com.canefe.story.Story
import net.tnemc.core.TNECore
import net.tnemc.core.currency.item.ItemDenomination
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.inventory.ItemStack
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import kotlin.random.Random

/**
 * Represents a settlement within a faction
 */
data class Settlement(
    val id: String,
    var name: String,
    var isCapital: Boolean = false,
    var description: String = "",
) {
    // Reference to parent faction (to be set by faction)
    var factionId: String? = null

    // Leader of the settlement
    var leader: Leader? = null
    var leaderNpcId: Int? = null

    // StoryLocation
    var location: String = ""

    // Population stats
    var population: Int = 100

    // Settlement happiness (0.0-1.0)
    var happiness: Double = 0.7

    // Prosperity represents economic strength
    var prosperity: Double = 0.5

    // Storage system
    val mineChests = mutableListOf<ChestLocation>()
    val treasuryChests = mutableListOf<ChestLocation>()

    val settlementRelations = mutableMapOf<String, Int>() // settlementId -> relation value

    // Config specific to this settlement
    var config = SettlementConfig()

    // Stats tracking
    val stats = mutableMapOf<String, Double>()

    var treasuryBalance: BigDecimal = BigDecimal.ZERO

    // Loyalty to faction (0.0-1.0)
    var loyalty: Double = 0.8

    // Faction
    var faction: Faction? = null
        set(value) {
            field = value
            factionId = value?.id
        }

    // Resource levels specific to this settlement
    val resources =
        mutableMapOf<ResourceType, Double>().apply {
            ResourceType.values().forEach { type ->
                put(type, Random.nextDouble(0.3, 0.7))
            }
        }

    // History of events affecting this settlement
    val history = mutableListOf<HistoryEntry>()

    // Recent actions that happened to/in settlement
    val recentActions = LinkedList<SettlementAction>()

    // Level of the settlement (for experience)
    var level: Int = 1

    // Total experience points earned
    var experience: Double = 0.0

    // Tax rate applied to this settlement (0.0-1.0)
    var taxRate: Double = 0.2
        set(value) {
            val oldValue = field
            field = value.coerceIn(0.0, 0.8)

            // Record significant tax changes
            if (field > oldValue * 1.2) {
                // Tax increase could reduce happiness and loyalty
                adjustHappiness(-0.05)
                adjustLoyalty(-0.04)
                addAction(
                    LeaderActionType.TAX_INCREASE,
                    "Tax rate increased from ${(oldValue * 100).toInt()}% to ${(field * 100).toInt()}%",
                )
            } else if (field < oldValue * 0.8) {
                // Tax decrease could increase happiness and loyalty
                adjustHappiness(0.04)
                adjustLoyalty(0.03)
                addAction(
                    LeaderActionType.TAX_DECREASE,
                    "Tax rate decreased from ${(oldValue * 100).toInt()}% to ${(field * 100).toInt()}%",
                )
            }
        }

    /**
     * Calculate how much xp is needed for next level
     */
    fun experienceForNextLevel(): Double = 1000.0 * level * 1.5

    fun adjustSettlementRelation(
        targetId: String,
        change: Int,
    ) {
        val current = settlementRelations.getOrDefault(targetId, 0)
        settlementRelations[targetId] = (current + change).coerceIn(-100, 100)
    }

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

    fun isMineChest(block: Block): Boolean = mineChests.any { it.getLocation() == block }

    // remove minechest by index
    fun removeMineChest(index: Int): Boolean {
        if (index < 0 || index >= mineChests.size) {
            return false
        }

        mineChests.removeAt(index)
        return true
    }

    /**
     * Add a chest to the mine chest system
     */
    fun addMineChest(block: Block): Boolean {
        if (block.type != Material.CHEST) {
            return false
        }

        val location = ChestLocation.fromBlock(block)

        // Check if already registered
        if (mineChests.any { it == location }) {
            return false
        }

        mineChests.add(location)
        return true
    }

    // Move to Settlement class
    fun processDailyDynamics(plugin: Story) {
        val dailyEventsChance = plugin.config.dailyEventsChance
        val dailyEventsEnabled = plugin.config.dailyEventsEnabled

        // Random resource fluctuations
        ResourceType.values().forEach { resourceType ->
            val change = Random.nextDouble(-0.05, 0.07)
            adjustResource(resourceType, change)
        }

        // Population changes based on happiness and prosperity
        val populationChange = ((happiness - 0.5) * 5 + (prosperity - 0.5) * 5).toInt()
        if (populationChange != 0) {
            adjustPopulation(populationChange)
        }

        // Prosperity adjustments based on resource levels
        var resourceAverage = resources.values.average()
        val prosperityChange = (resourceAverage - 0.5) * 0.02
        adjustProsperity(prosperityChange)

        // Check for possible rebellions
        if (loyalty < 0.2 && !isCapital && Random.nextDouble() < 0.05) {
            faction?.handleRebellion(this)
        }

        // Generate random events occasionally
        if ((Random.nextDouble() < dailyEventsChance) && dailyEventsEnabled) {
            plugin.factionManager.settlementNPCService
                .generateRandomEvent(this)
                .thenAccept { event ->
                    if (event != null) {
                        // Apply the effects to the settlement
                        adjustHappiness(event.effects.happiness)
                        adjustLoyalty(event.effects.loyalty)
                        adjustProsperity(event.effects.prosperity)
                        adjustPopulation(event.effects.population)

                        // Apply resource effects if any
                        event.resourceEffects?.let { resourceEffect ->
                            try {
                                val resourceType = ResourceType.valueOf(resourceEffect.resourceType)
                                adjustResource(resourceType, resourceEffect.change)
                            } catch (e: IllegalArgumentException) {
                                // Invalid resource type
                            }
                        }

                        // Add the event to settlement actions
                        addAction(
                            LeaderActionType.valueOf(event.eventType.ifEmpty { "MISC" }),
                            event.description,
                        )

                        val shouldBroadcast =
                            plugin.config.followedSettlements
                                .any { it.equals(this.name, ignoreCase = true) }

                        if (shouldBroadcast) {
                            Bukkit.getServer().sendMessage(
                                plugin.miniMessage.deserialize("<gold>[${this.name}]</gold> ${event.description}"),
                            )
                        }
                    }
                }
        }

        // Leader-based decisions
        leader?.let { leader ->
            // Leader stats affect settlement
            val stewardshipBonus = (leader.stewardship - 5) * 0.01
            val charismaBonus = (leader.charisma - 5) * 0.01

            // Apply leader bonuses
            adjustProsperity(stewardshipBonus)
            adjustHappiness(charismaBonus)
        }

        faction?.addExperience(Random.nextDouble(1.0, 5.0))
    }

    // Data class to parse AI-generated event
    data class SettlementEvent(
        val eventType: String = "MISC",
        val title: String = "",
        val description: String = "",
        val effects: SettlementEffects = SettlementEffects(),
        val resourceEffects: ResourceEffect? = null,
    )

    data class SettlementEffects(
        val happiness: Double = 0.0,
        val loyalty: Double = 0.0,
        val prosperity: Double = 0.0,
        val population: Int = 0,
    )

    data class ResourceEffect(
        val resourceType: String = "",
        val change: Double = 0.0,
    )

    fun setStoryLocation(locationId: String) {
        location = locationId
        // Update any location-dependent systems
    }

    private fun generateLeaderAction(
        settlement: Settlement,
        leader: Leader,
    ) {
        // Different actions based on leader's strongest attribute
        val strongestAttribute =
            listOf(
                "diplomacy" to leader.diplomacy,
                "martial" to leader.martial,
                "stewardship" to leader.stewardship,
                "intrigue" to leader.intrigue,
                "charisma" to leader.charisma,
            ).maxByOrNull { it.second }?.first ?: return

        // Actions influenced by traits
        val hasPositiveTrait =
            leader.traits.any {
                it == LeaderTrait.JUST ||
                    it == LeaderTrait.WISE ||
                    it == LeaderTrait.GENEROUS ||
                    it == LeaderTrait.CHARISMATIC
            }

        val hasNegativeTrait =
            leader.traits.any {
                it == LeaderTrait.CRUEL ||
                    it == LeaderTrait.WRATHFUL ||
                    it == LeaderTrait.GREEDY
            }

        // Generate action based on strongest attribute and traits
        when (strongestAttribute) {
            "diplomacy" -> {
                // Diplomatic actions
                if (hasPositiveTrait) {
                    val action = "diplomacy_positive"
                    when (Random.nextInt(3)) {
                        0 -> {
                            settlement.adjustLoyalty(0.05)
                            settlement.addAction(
                                LeaderActionType.DIPLOMATIC,
                                "${leader.title} ${leader.name} has negotiated favorable trade terms with neighbors.",
                            )
                            settlement.adjustProsperity(0.04)
                        }
                        1 -> {
                            settlement.adjustHappiness(0.06)
                            settlement.addAction(
                                LeaderActionType.DIPLOMATIC,
                                "${leader.title} ${leader.name} has resolved a local dispute peacefully.",
                            )
                        }
                        2 -> {
                            // Add faction-level effect
                            addHistoryEntry(
                                "Diplomatic Success",
                                "${leader.title} ${leader.name} of ${settlement.name} has improved relations with neighboring factions.",
                            )
                        }
                    }
                } else if (hasNegativeTrait) {
                    settlement.adjustLoyalty(-0.03)
                    settlement.addAction(
                        LeaderActionType.DIPLOMATIC,
                        "${leader.title} ${leader.name} has offended an important visitor with harsh words.",
                    )
                } else {
                    // Neutral trait
                    settlement.addAction(
                        LeaderActionType.DIPLOMATIC,
                        "${leader.title} ${leader.name} has hosted dignitaries from another settlement.",
                    )
                }
            }

            "martial" -> {
                // Military/security actions
                if (hasPositiveTrait) {
                    settlement.addAction(
                        LeaderActionType.MISC,
                        "${leader.title} ${leader.name} has organized militia training to protect the settlement.",
                    )
                    stats["militaryStrength"] = (stats["militaryStrength"] ?: 0.0) + 0.05
                } else if (hasNegativeTrait) {
                    when (Random.nextInt(2)) {
                        0 -> {
                            settlement.adjustHappiness(-0.06)
                            settlement.addAction(
                                LeaderActionType.MISC,
                                "${leader.title} ${leader.name} has forcefully conscripted locals into military service.",
                            )
                            stats["militaryStrength"] = (stats["militaryStrength"] ?: 0.0) + 0.08
                        }
                        1 -> {
                            settlement.addAction(
                                LeaderActionType.CRIME,
                                "${leader.title} ${leader.name} has executed suspected criminals without trial.",
                            )
                            settlement.adjustLoyalty(-0.04)
                        }
                    }
                } else {
                    // Neutral trait
                    settlement.addAction(
                        LeaderActionType.MISC,
                        "${leader.title} ${leader.name} has inspected the settlement defenses.",
                    )
                }
            }

            "stewardship" -> {
                // Economic/administrative actions
                if (hasPositiveTrait) {
                    val resourceType = ResourceType.values().random()
                    settlement.adjustResource(resourceType, 0.06)
                    settlement.adjustProsperity(0.04)
                    settlement.addAction(
                        LeaderActionType.PROSPERITY_INCREASE,
                        "${leader.title} ${leader.name} has improved ${resourceType.displayName.lowercase()} production methods.",
                    )
                } else if (hasNegativeTrait && leader.traits.contains(LeaderTrait.GREEDY)) {
                    settlement.adjustHappiness(-0.05)
                    settlement.addAction(
                        LeaderActionType.TAX_INCREASE,
                        "${leader.title} ${leader.name} has imposed additional fees on local businesses.",
                    )
                    treasuryBalance = treasuryBalance.add(BigDecimal("10.00"))
                } else {
                    // Neutral trait
                    settlement.addAction(
                        LeaderActionType.MISC,
                        "${leader.title} ${leader.name} has audited the settlement's resources and supplies.",
                    )
                }
            }

            "intrigue" -> {
                // Plotting/scheming actions
                if (hasNegativeTrait) {
                    settlement.addAction(
                        LeaderActionType.CRIME,
                        "Rumors spread that ${leader.title} ${leader.name} is using spies to monitor citizens.",
                    )
                    settlement.adjustHappiness(-0.04)
                } else {
                    settlement.addAction(
                        LeaderActionType.MISC,
                        "${leader.title} ${leader.name} has uncovered valuable information about neighboring settlements.",
                    )
                    addExperience(15.0)
                }
            }

            "charisma" -> {
                // People-oriented actions
                if (hasPositiveTrait || leader.traits.contains(LeaderTrait.CHARISMATIC)) {
                    settlement.adjustHappiness(0.08)
                    settlement.adjustLoyalty(0.04)
                    settlement.addAction(
                        LeaderActionType.FESTIVAL,
                        "${leader.title} ${leader.name} has given an inspiring speech to the people.",
                    )
                } else {
                    settlement.addAction(
                        LeaderActionType.MISC,
                        "${leader.title} ${leader.name} has made public appearances around the settlement.",
                    )
                    settlement.adjustHappiness(0.02)
                }
            }
        }

        // Additional trait-specific actions
        leader.traits.forEach { trait ->
            when (trait) {
                LeaderTrait.AMBITIOUS -> {
                    if (Random.nextDouble() < 0.2) {
                        addExperience(20.0)
                        settlement.addAction(
                            LeaderActionType.MISC,
                            "${leader.title} ${leader.name}'s ambition drives settlement expansion efforts.",
                        )
                    }
                }
                LeaderTrait.ZEALOUS -> {
                    if (Random.nextDouble() < 0.25) {
                        settlement.addAction(
                            LeaderActionType.MISC,
                            "${leader.title} ${leader.name} has ordered construction of a shrine or temple.",
                        )
                        if (settlement.loyalty < 0.5) {
                            settlement.adjustLoyalty(0.03)
                        }
                    }
                }
                else -> {} // Other traits handled in attribute-specific sections
            }
        }
    }

    /**
     * Check mine chest status
     */
    fun getMineChestStatus(): List<String> {
        val result = mutableListOf<String>()

        if (mineChests.isEmpty()) {
            result.add("${ChatColor.RED}No mine chests registered!")
            return result
        }

        mineChests.forEachIndexed { index, location ->
            val chest = location.toChest()

            if (chest == null) {
                result.add("${ChatColor.GRAY}- ${ChatColor.RED}Chest ${index + 1}: NULL")
                return@forEachIndexed
            }

            val loc = chest.location

            if (chest.type == Material.CHEST) {
                val usedSlots = chest.inventory.contents.count { it != null }
                result.add(
                    "${ChatColor.GRAY}- ${ChatColor.GREEN}Chest ${index + 1}: ${ChatColor.WHITE}${loc.x}, ${loc.y}, ${loc.z} ${ChatColor.GRAY}($usedSlots/54 slots used)",
                )
            } else {
                result.add(
                    "${ChatColor.GRAY}- ${ChatColor.RED}Chest ${index + 1}: ${ChatColor.WHITE}${loc.x}, ${loc.y}, ${loc.z} ${ChatColor.GRAY}(Invalid type: ${chest.type})",
                )
            }
        }

        return result
    }

    fun collectTaxes(): BigDecimal {
        val taxes = calculateDailyTaxIncome()

        // Add to settlement treasury first
        treasuryBalance = treasuryBalance.add(taxes)

        // Record collection
        if (taxes > BigDecimal.ZERO) {
            addHistoryEntry("Tax Collection", "Collected ${taxes.setScale(2, RoundingMode.HALF_UP)} coins in taxes")
        }

        return taxes
    }

    /**
     * Add a chest to the treasury system
     */
    fun addTreasuryChest(block: Block): Boolean {
        if (block.type != Material.CHEST) {
            return false
        }

        val location = ChestLocation.fromBlock(block)

        // Check if already registered
        if (treasuryChests.any { it == location }) {
            return false
        }

        treasuryChests.add(location)
        updateTreasuryDisplay() // Update the display immediately
        return true
    }

    fun updateStats() {
        stats["populationGrowth"] = (population - (stats["previousPopulation"] ?: population.toDouble())) /
            maxOf(1.0, stats["previousPopulation"] ?: population.toDouble())
        stats["previousPopulation"] = population.toDouble()

        stats["happinessChange"] = happiness - (stats["previousHappiness"] ?: happiness)
        stats["previousHappiness"] = happiness

        // More stats updates...
    }

    /**
     * Remove a treasury chest by index
     */
    fun removeTreasuryChest(index: Int): Boolean {
        if (index < 0 || index >= treasuryChests.size) {
            return false
        }

        treasuryChests.removeAt(index)
        updateTreasuryDisplay()
        return true
    }

    /**
     * Update display of physical currency in treasury chests
     */
    fun updateTreasuryDisplay() {
        if (!config.usePhysicalCurrency || treasuryChests.isEmpty()) {
            return
        }

        // Clear existing chests first
        treasuryChests
            .mapNotNull { it.toChest() }
            .forEach { chest -> chest.inventory.clear() }

        // Convert balance to physical currency
        val items = convertMoneyToItems(treasuryBalance)

        // Distribute items across chests
        distributeItemsToChests(items, treasuryChests.mapNotNull { it.toChest() })
    }

    /**
     * Calculate daily income from mines
     */
    fun collectDailyMineIncome(): BigDecimal {
        // Calculate raw resource production
        val ironOrePerMiner = Random.nextInt(4, 11)
        val coalOrePerMiner = Random.nextInt(16, 33)

        val minerCount = config.minerCount
        val totalIronOre = ironOrePerMiner * config.workdayHours * minerCount * happiness
        val totalCoalOre = coalOrePerMiner * config.workdayHours * minerCount * happiness

        // Calculate values
        val ironMoney = totalIronOre * config.ironValue
        val coalMoney = totalCoalOre * config.coalValue

        val grossIncome = BigDecimal(ironMoney + coalMoney)

        // Calculate expenses
        val salaries = BigDecimal(minerCount * config.minerSalary * config.workdayHours)

        // Calculate net income
        val netIncome = grossIncome.subtract(salaries)

        // Update stats
        stats["income"] = grossIncome.toDouble()
        stats["expenses"] = salaries.toDouble()
        stats["growth"] = netIncome.toDouble() / maxOf(1.0, stats["income"] ?: 1.0)

        // Add to treasury
        treasuryBalance = treasuryBalance.add(netIncome)

        // Record significant events
        if (netIncome.compareTo(BigDecimal.ZERO) > 0) {
            addHistoryEntry("Mining Income", "Mining operations generated $netIncome coins")
        } else {
            addHistoryEntry("Mining Loss", "Mining operations lost ${netIncome.abs()} coins")
        }

        // Broadcast results
        broadcastMineResults(
            totalIronOre,
            totalCoalOre,
            grossIncome,
            salaries,
            netIncome,
        )

        // Distribute physical currency to chests if desired
        if (config.usePhysicalCurrency && mineChests.isNotEmpty()) {
            distributeMoneyToChests(netIncome)
        }

        return netIncome
    }

    /**
     * Distribute money to physical mine chests
     */
    private fun distributeMoneyToChests(amount: BigDecimal) {
        val items = convertMoneyToItems(amount)
        val chests = mineChests.mapNotNull { it.toChest() }

        distributeItemsToChests(items, chests)
    }

    /**
     * Convert money amount to item stacks representing currency
     */
    private fun convertMoneyToItems(amount: BigDecimal): List<ItemStack> {
        // Safely get TNE instance
        val tneCore =
            try {
                TNECore.instance() ?: return emptyList()
            } catch (e: Exception) {
                Story.instance.logger.warning("Failed to access TNE Core: ${e.message}")
                return emptyList()
            }

        val tneApi =
            try {
                TNECore.api() ?: return emptyList()
            } catch (e: Exception) {
                Story.instance.logger.warning("Failed to access TNE API: ${e.message}")
                return emptyList()
            }

        val currency = tneApi.getDefaultCurrency("world") ?: return emptyList()

        // List to hold all currency items
        val result = mutableListOf<ItemStack>()

        // Get denominations and filter out blacklisted ones (do this once)
        val denominations =
            currency.denominations
                .descendingMap()
                .entries
                .filter { !config.blacklistedDenominations.contains(it.key.toDouble()) }
                .toList()

        // Shuffle once instead of repeatedly
        val shuffledDenominations = denominations.shuffled()

        // Amount to convert
        var remaining = amount

        // Add safety counter to prevent infinite loops
        var attempts = 0
        val maxAttempts = 100

        while (remaining.compareTo(BigDecimal.ZERO) > 0 && result.size < 54 && attempts < maxAttempts) {
            attempts++
            var madeProgress = false

            for (entry in shuffledDenominations) {
                val denominationWeight = entry.key
                val denomination = entry.value

                // If this denomination fits into remaining amount
                if (remaining.compareTo(denominationWeight) >= 0) {
                    // Calculate how many of this denomination we can use
                    val maxPossible = remaining.divide(denominationWeight, 10, RoundingMode.FLOOR)

                    // Limit stack to 64 items max
                    val stackSize = minOf(maxPossible.toInt(), 64)

                    // Add some randomness to denominations used
                    val actualAmount = Random.nextInt(1, stackSize + 1)

                    // Calculate stack value
                    val stackValue = denominationWeight.multiply(BigDecimal(actualAmount))

                    // Create the item stack
                    val tneItemStack = tneCore.denominationToStack(denomination as ItemDenomination?, actualAmount)
                    if (tneItemStack != null) {
                        result.add(tneItemStack.locale() as ItemStack)
                        remaining = remaining.subtract(stackValue)
                        madeProgress = true

                        // Break early if we've cleared the amount
                        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                            break
                        }
                    }
                }
            }

            // If we didn't make any progress on this pass, break to avoid infinite loop
            if (!madeProgress) break
        }

        return splitItemStacks(result)
    }

    /**
     * Split item stacks to ensure none exceed 64 items
     */
    private fun splitItemStacks(itemStacks: List<ItemStack>): List<ItemStack> {
        val result = mutableListOf<ItemStack>()

        for (itemStack in itemStacks) {
            var amount = itemStack.amount

            while (amount > 64) {
                val newStack = itemStack.clone()
                newStack.amount = 64
                result.add(newStack)
                amount -= 64
            }

            if (amount > 0) {
                val newStack = itemStack.clone()
                newStack.amount = amount
                result.add(newStack)
            }
        }

        return result
    }

    /**
     * Distribute items to chests
     */
    private fun distributeItemsToChests(
        items: List<ItemStack>,
        chests: List<Chest>,
    ) {
        if (chests.isEmpty() || items.isEmpty()) return

        var itemsPlaced = 0
        val totalItems = items.size

        // Try to add each item to chests
        for (chest in chests) {
            val inventory = chest.inventory

            // Loop through the items and try to add them
            for (i in itemsPlaced until items.size) {
                val item = items[i]

                // Find first empty slot
                val emptySlot = inventory.firstEmpty()
                if (emptySlot == -1) {
                    // No empty slots in this chest, try next one
                    break
                }

                // Place the item
                inventory.setItem(emptySlot, item)
                itemsPlaced++

                // Exit if all items placed
                if (itemsPlaced >= totalItems) {
                    return
                }
            }
        }

        // If not all items were placed, log a warning
        if (itemsPlaced < totalItems) {
            Bukkit.getLogger().warning(
                "[Faction $name] Not all treasury funds could be displayed! Need more chests (placed $itemsPlaced/$totalItems)",
            )
        }
    }

    /**
     * Convert physical currency items in a chest to money value
     * @param chest The chest containing currency items
     * @return The total monetary value as BigDecimal
     */
    fun convertChestToMoney(chest: Block): BigDecimal {
        // Get the chest's inventory
        val chestState = chest.state as? Chest ?: return BigDecimal.ZERO
        val inventory = chestState.inventory ?: return BigDecimal.ZERO

        // Get TNE API
        val tneCore =
            try {
                TNECore.instance() ?: return BigDecimal.ZERO
            } catch (e: Exception) {
                Story.instance.logger.warning("Failed to access TNE Core: ${e.message}")
                return BigDecimal.ZERO
            }

        val tneApi =
            try {
                TNECore.api() ?: return BigDecimal.ZERO
            } catch (e: Exception) {
                Story.instance.logger.warning("Failed to access TNE API: ${e.message}")
                return BigDecimal.ZERO
            }

        // Get default currency
        val currency = tneApi.getDefaultCurrency("world") ?: return BigDecimal.ZERO

        // Initialize the total balance
        var totalBalance = BigDecimal.ZERO

        // Get all valid denominations (BigDecimal -> ItemDenomination mapping)
        val denominations = currency.denominations.descendingMap().entries

        // Loop through chest inventory
        inventory.contents.forEach { item ->
            if (item == null) return@forEach // Skip empty slots

            // Loop through each valid TNE denomination to find a match
            for (entry in denominations) {
                val denominationWeight = entry.key
                val denomination = entry.value

                // Convert item to matchable material string
                val itemMaterial = item.type.toString()

                // Get the denomination's material type
                val denominationMaterial =
                    (denomination as? ItemDenomination)?.material?.replace("minecraft:", "") ?: continue

                // If it's a currency item
                if (itemMaterial.equals(denominationMaterial, ignoreCase = true)) {
                    // Multiply amount by denomination value
                    val itemAmount = item.amount
                    val itemValue = denominationWeight.multiply(BigDecimal(itemAmount))

                    // Add to total balance
                    totalBalance = totalBalance.add(itemValue)

                    // Found a match, no need to check other denominations for this item
                    break
                }
            }
        }

        return totalBalance
    }

    /**
     * Broadcast mine results to server
     */
    private fun broadcastMineResults(
        ironOre: Double,
        coalOre: Double,
        grossIncome: BigDecimal,
        expenses: BigDecimal,
        netIncome: BigDecimal,
    ) {
        val prefix = "${ChatColor.GOLD}[$name] "
        val message1 =
            "${ChatColor.GRAY}⛏ +${ChatColor.YELLOW}${ironOre.toInt()} ${ChatColor.GRAY}iron ore, " +
                "+${ChatColor.YELLOW}${coalOre.toInt()} ${ChatColor.GRAY}coal ore → " +
                "${ChatColor.GOLD}₿${grossIncome.setScale(2, RoundingMode.HALF_UP)}"

        val message2 =
            "${ChatColor.GRAY}⛏ ${ChatColor.GRAY}wages -${ChatColor.RED}₿${expenses.setScale(
                2,
                RoundingMode.HALF_UP,
            )} " +
                "${ChatColor.DARK_GRAY}| ${ChatColor.GRAY}💰 "

        // Add color based on profit/loss
        val netIncomeMsg =
            if (netIncome.compareTo(BigDecimal.ZERO) >= 0) {
                "${ChatColor.GREEN}₿${netIncome.setScale(2, RoundingMode.HALF_UP)}"
            } else {
                "${ChatColor.RED}₿${netIncome.abs().setScale(2, RoundingMode.HALF_UP)}"
            }

        // Broadcast to all players or just log to console
        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendMessage("$prefix$message1")
            player.sendMessage("$prefix$message2$netIncomeMsg")
        }

        // Also log to console
        Bukkit.getLogger().info("$name mine results: $netIncome net income")
    }

    // Generate daily tax income
    fun calculateDailyTaxIncome(): BigDecimal {
        val baseIncome = BigDecimal(population * 0.05)
        val taxMultiplier = BigDecimal(taxRate)
        val prosperityBonus = BigDecimal(1.0 + prosperity)

        return baseIncome.multiply(taxMultiplier).multiply(prosperityBonus)
    }

    // Adjust population
    fun adjustPopulation(change: Int) {
        val oldPopulation = population
        population = (population + change).coerceAtLeast(50)

        if (change > 0) {
            addAction(LeaderActionType.POPULATION_GROWTH, "Population increased by $change to $population")
        } else if (change < 0) {
            addAction(LeaderActionType.POPULATION_DECLINE, "Population decreased by ${-change} to $population")
        }
    }

    // Adjust happiness with bounds checking
    fun adjustHappiness(change: Double): Boolean {
        val newHappiness = (happiness + change).coerceIn(0.0, 1.0)
        val significant = Math.abs(newHappiness - happiness) > 0.05
        happiness = newHappiness

        if (significant) {
            if (change > 0) {
                addAction(LeaderActionType.HAPPINESS_INCREASE, "People are becoming more content")
            } else {
                addAction(LeaderActionType.HAPPINESS_DECREASE, "People are growing restless")
            }

            // Happiness affects loyalty over time
            if (happiness < 0.3) {
                adjustLoyalty(-0.02) // Very unhappy people become less loyal
            } else if (happiness > 0.7) {
                adjustLoyalty(0.01) // Happy people become more loyal
            }
        }

        return significant
    }

    // Adjust loyalty with bounds checking
    fun adjustLoyalty(change: Double): Boolean {
        val newLoyalty = (loyalty + change).coerceIn(0.0, 1.0)
        val significant = Math.abs(newLoyalty - loyalty) > 0.05
        loyalty = newLoyalty

        if (significant) {
            if (change > 0) {
                addAction(LeaderActionType.LOYALTY_INCREASE, "Loyalty to ${factionId ?: "faction"} is strengthening")
            } else {
                addAction(LeaderActionType.LOYALTY_DECREASE, "Loyalty to ${factionId ?: "faction"} is weakening")

                // Very low loyalty might lead to rebellion
                if (loyalty < 0.2 && Random.nextDouble() < 0.1) {
                    addAction(LeaderActionType.REBELLION_RISK, "Whispers of rebellion are spreading")
                }
            }
        }

        return significant
    }

    // Adjust prosperity
    fun adjustProsperity(change: Double) {
        prosperity = (prosperity + change).coerceIn(0.0, 1.0)

        if (change > 0.05) {
            addAction(LeaderActionType.PROSPERITY_INCREASE, "Trade and commerce are flourishing")
        } else if (change < -0.05) {
            addAction(LeaderActionType.PROSPERITY_DECREASE, "Economic hardship is spreading")
        }
    }

    // Add a resource change to the settlement
    fun adjustResource(
        type: ResourceType,
        change: Double,
    ) {
        val currentLevel = resources[type] ?: 0.0
        resources[type] = (currentLevel + change).coerceIn(0.0, 1.0)

        if (Math.abs(change) > 0.1) {
            if (change > 0) {
                addAction(LeaderActionType.RESOURCE_INCREASE, "${type.displayName} supplies have increased")
            } else {
                addAction(LeaderActionType.RESOURCE_DECREASE, "${type.displayName} supplies are dwindling")

                // Resource shortages affect happiness
                if ((resources[type] ?: 0.0) < 0.2) {
                    adjustHappiness(-0.05)
                    addAction(LeaderActionType.RESOURCE_CRISIS, "Shortage of ${type.displayName} is causing unrest")
                }
            }
        }
    }

    // Add historic event entry
    fun addHistoryEntry(
        title: String,
        description: String,
    ) {
        history.add(HistoryEntry(Date(), title, description))

        // Keep history at a reasonable size
        if (history.size > 50) {
            history.removeAt(0)
        }
    }

    // Add a settlement action
    fun addAction(
        type: LeaderActionType,
        description: String,
    ) {
        val action = SettlementAction(type, description, Date())
        recentActions.add(action)

        // Keep actions list at a manageable size
        if (recentActions.size > 10) {
            recentActions.removeFirst()
        }

        // Significant actions also go in history
        if (type.isSignificant) {
            addHistoryEntry(type.title, description)
        }
    }

    // Generate status display
    fun getStatusDisplay(): List<String> {
        val result = mutableListOf<String>()
        result.add(
            "${ChatColor.GOLD}=== ${ChatColor.YELLOW}$name ${if (isCapital) "(Capital)" else ""} ${ChatColor.GOLD}===",
        )
        result.add("${ChatColor.GRAY}Population: ${ChatColor.WHITE}$population")

        // Leader info
        leader?.let {
            result.add("${ChatColor.GRAY}Leader: ${ChatColor.YELLOW}${it.title} ${it.name}")
        }

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

        // Generate loyalty bar
        val loyaltyBar = StringBuilder("${ChatColor.GRAY}Loyalty: ")
        val loyaltyBars = (loyalty * 10).toInt()
        for (i in 1..10) {
            if (i <= loyaltyBars) {
                loyaltyBar.append("${ChatColor.AQUA}|")
            } else {
                loyaltyBar.append("${ChatColor.DARK_GRAY}|")
            }
        }
        loyaltyBar.append(" ${ChatColor.WHITE}${(loyalty * 100).toInt()}%")
        result.add(loyaltyBar.toString())

        result.add("${ChatColor.GRAY}Prosperity: ${ChatColor.GOLD}${(prosperity * 100).toInt()}%")
        result.add("${ChatColor.GRAY}Tax Rate: ${ChatColor.GOLD}${(taxRate * 100).toInt()}%")

        // Show resources
        result.add("${ChatColor.GRAY}Resources:")
        resources.forEach { (type, level) ->
            val resourceBar = StringBuilder("  ${ChatColor.GRAY}${type.displayName}: ")
            val resourceBars = (level * 10).toInt()
            for (i in 1..10) {
                if (i <= resourceBars) {
                    resourceBar.append("${ChatColor.GREEN}|")
                } else {
                    resourceBar.append("${ChatColor.RED}|")
                }
            }
            result.add(resourceBar.toString())
        }

        // Show recent actions
        if (recentActions.isNotEmpty()) {
            result.add("${ChatColor.GRAY}Recent Events:")
            recentActions.takeLast(5).forEach {
                result.add("  ${ChatColor.DARK_GRAY}- ${ChatColor.WHITE}${it.description}")
            }
        }

        return result
    }

    // Convert to data that can be saved in YAML
    fun toYamlSection(): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        data["id"] = id
        data["name"] = name
        data["isCapital"] = isCapital
        data["description"] = description
        data["factionId"] = factionId ?: ""
        data["population"] = population
        data["happiness"] = happiness
        data["prosperity"] = prosperity
        data["loyalty"] = loyalty
        data["taxRate"] = taxRate
        data["treasuryBalance"] = treasuryBalance.toString() // Save as string to preserve precision
        data["level"] = level
        data["experience"] = experience

        // Save leader if exists
        leader?.let {
            data["leader"] = it.toYamlSection()
        }

        // Save mine chests
        data["mineChests"] = mineChests.map { it.toYamlSection() }

        // Save treasury chests
        data["treasuryChests"] = treasuryChests.map { it.toYamlSection() }

        // Save settlement relations
        data["settlementRelations"] = settlementRelations

        // Save stats
        data["stats"] = stats

        // Save resources
        val resourceData = mutableMapOf<String, Double>()
        resources.forEach { (type, level) -> resourceData[type.name] = level }
        data["resources"] = resourceData

        // Save configuration
        data["config"] = config.toYamlSection()

        // Save actions (limited to last 10)
        val actionsData = recentActions.takeLast(10).map { it.toYamlSection() }
        data["actions"] = actionsData

        // Save history (limited to last 20 entries)
        val historyData = history.takeLast(20).map { it.toYamlSection() }
        data["history"] = historyData

        return data
    }

    companion object {
        // Create settlement from YAML data
        fun fromYamlSection(data: Map<String, Any?>): Settlement? {
            try {
                val id = data["id"] as? String ?: return null
                val name = data["name"] as? String ?: return null
                val isCapital = data["isCapital"] as? Boolean ?: false
                val description = data["description"] as? String ?: ""

                val settlement = Settlement(id, name, isCapital, description)

                // Set basic properties
                settlement.factionId = data["factionId"] as? String
                settlement.population = (data["population"] as? Number)?.toInt() ?: 100
                settlement.happiness = (data["happiness"] as? Number)?.toDouble() ?: 0.7
                settlement.prosperity = (data["prosperity"] as? Number)?.toDouble() ?: 0.5
                settlement.loyalty = (data["loyalty"] as? Number)?.toDouble() ?: 0.8
                settlement.taxRate = (data["taxRate"] as? Number)?.toDouble() ?: 0.2
                settlement.treasuryBalance = BigDecimal((data["treasuryBalance"] as? String) ?: "0.0")
                settlement.level = (data["level"] as? Number)?.toInt() ?: 1
                settlement.experience = (data["experience"] as? Number)?.toDouble() ?: 0.0

                // Load leader
                val leaderData = data["leader"] as? Map<String, Any?>
                if (leaderData != null) {
                    settlement.leader = Leader.fromYamlSection(leaderData)
                }

                // Load mine chests
                val mineChestsData = data["mineChests"] as? List<Map<String, Any>>
                mineChestsData?.forEach { chestData ->
                    ChestLocation.deserialize(chestData)?.let {
                        settlement.mineChests.add(it)
                    }
                }

                // Load treasury chests
                val treasuryChestsData = data["treasuryChests"] as? List<Map<String, Any>>
                treasuryChestsData?.forEach { chestData ->
                    ChestLocation.deserialize(chestData)?.let {
                        settlement.treasuryChests.add(it)
                    }
                }

                // Load settlement relations
                val relationsData = data["settlementRelations"] as? Map<String, Int>
                if (relationsData != null) {
                    settlement.settlementRelations.putAll(relationsData)
                }

                // Load resources
                val resourcesData = data["resources"] as? Map<String, Double>
                if (resourcesData != null) {
                    ResourceType.values().forEach { type ->
                        resourcesData[type.name]?.let { value ->
                            settlement.resources[type] = value
                        }
                    }
                }

                // Load stats
                val statsData = data["stats"] as? Map<String, Double>
                if (statsData != null) {
                    settlement.stats.putAll(statsData)
                }

                // Load configuration
                val configData = data["config"] as? Map<String, Any?>
                if (configData != null) {
                    settlement.config = SettlementConfig.fromYamlSection(configData)
                }

                // Load actions
                val actionsData = data["actions"] as? List<Map<String, Any>>
                actionsData?.forEach { actionData ->
                    val typeStr = actionData["type"] as? String ?: return@forEach
                    val type =
                        try {
                            LeaderActionType.valueOf(typeStr)
                        } catch (e: IllegalArgumentException) {
                            LeaderActionType.MISC
                        }
                    val description = actionData["description"] as? String ?: "Unknown"
                    val timestamp = (actionData["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()

                    settlement.recentActions.add(SettlementAction(type, description, Date(timestamp)))
                }

                // Load history
                val historyData = data["history"] as? List<Map<String, Any>>
                historyData?.forEach { entryData ->
                    val timestamp = (entryData["timestamp"] as? Number)?.toLong() ?: 0
                    val title = entryData["title"] as? String ?: "Unknown Event"
                    val desc = entryData["description"] as? String ?: ""

                    settlement.history.add(HistoryEntry(Date(timestamp), title, desc))
                }

                return settlement
            } catch (e: Exception) {
                Bukkit.getLogger().severe("Failed to load settlement: ${e.message}")
                e.printStackTrace()
                return null
            }
        }
    }
}

/**
 * Configuration for settlement properties
 */
data class SettlementConfig(
    var workdayHours: Int = 8,
    var minerCount: Int = 20,
    var minerSalary: Double = 0.01,
    var ironValue: Double = 0.10, // Value per iron ore
    var coalValue: Double = 0.01, // Value per coal ore
    var usePhysicalCurrency: Boolean = true,
    var blacklistedDenominations: Set<Double> = setOf(13.5, 1.25, 1.5, 4.5, 9.0),
) {
    // Serialization methods (copy from FactionConfig)
    fun serialize(): Map<String, Any> =
        mapOf(
            "workdayHours" to workdayHours,
            "minerCount" to minerCount,
            "minerSalary" to minerSalary,
            "ironValue" to ironValue,
            "coalValue" to coalValue,
            "usePhysicalCurrency" to usePhysicalCurrency,
            "blacklistedDenominations" to blacklistedDenominations.toList(),
        )

    fun toYamlSection(): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        data["usePhysicalCurrency"] = usePhysicalCurrency
        data["minerCount"] = minerCount
        data["minerSalary"] = minerSalary
        data["workdayHours"] = workdayHours
        data["ironValue"] = ironValue
        data["coalValue"] = coalValue
        data["blacklistedDenominations"] = blacklistedDenominations
        return data
    }

    companion object {
        fun deserialize(data: Map<String, Any?>): SettlementConfig =
            SettlementConfig(
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

        fun fromYamlSection(data: Map<String, Any?>): SettlementConfig =
            SettlementConfig(
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
