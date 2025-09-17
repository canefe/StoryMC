package com.canefe.story.npc.name

import com.canefe.story.Story
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.*
import kotlin.random.Random

/**
 * Manages NPC name aliasing system including name banks, canonical names,
 * display names, and deterministic name generation.
 */
class NPCNameManager(
    private val plugin: Story,
) {
    private val nameBanks = mutableMapOf<String, NameBank>()
    private val locationRecencyCache = mutableMapOf<String, MutableList<String>>()
    private val npcIdToCanonicalName = mutableMapOf<String, String>()
    private val canonicalNameToNpcId = mutableMapOf<String, String>()

    val nameBanksDirectory: File =
        File(plugin.dataFolder, "namebanks").apply {
            if (!exists()) {
                mkdirs()
            }
        }

    init {
        loadNameBanks()
    }

    /**
     * Data class representing a name bank for a culture/faction
     */
    data class NameBank(
        val givenNames: List<String>,
        val familyNames: List<String>,
        val ranks: List<String> = emptyList(),
        val culturalMarkers: List<String> = emptyList(),
    )

    /**
     * Data class representing a complete NPC alias
     */
    data class NPCAlias(
        val canonicalName: String, // Full name for dialogue/storage (e.g., "Arik Mossveil")
        val displayHandle: String, // Short name for MC display (e.g., "A. Mossveil")
        val callsign: String? = null, // Optional differentiator (e.g., "Spearhand", "East Gate")
    )

    /**
     * Load all name banks from the namebanks directory
     */
    private fun loadNameBanks() {
        nameBanks.clear()

        val files = nameBanksDirectory.listFiles { _, name -> name.endsWith(".yml") }
        files?.forEach { file ->
            try {
                val config = YamlConfiguration.loadConfiguration(file)
                val bankName = file.nameWithoutExtension

                val givenNames = config.getStringList("given_names")
                val familyNames = config.getStringList("family_names")
                val ranks = config.getStringList("ranks")
                val culturalMarkers = config.getStringList("cultural_markers")

                nameBanks[bankName] = NameBank(givenNames, familyNames, ranks, culturalMarkers)
                plugin.logger.info(
                    "Loaded name bank '$bankName' with ${givenNames.size} given names, ${familyNames.size} family names",
                )
            } catch (e: Exception) {
                plugin.logger.warning("Failed to load name bank from ${file.name}: ${e.message}")
            }
        }

        // Create default name banks if none exist
        if (nameBanks.isEmpty()) {
            createDefaultNameBanks()
        }
    }

    /**
     * Create default name banks for testing
     */
    private fun createDefaultNameBanks() {
        // Example Alboran guard name bank
        val alboranGuard =
            NameBank(
                givenNames = listOf("Arik", "Daven", "Kael", "Marcus", "Theron", "Vale", "Gareth", "Roderick"),
                familyNames = listOf("Mossveil", "Ironwood", "Stormwind", "Brightblade", "Goldleaf", "Swiftarrow"),
                ranks = listOf("Guard", "Sentinel", "Watchman", "Defender"),
                culturalMarkers = listOf("the Steadfast", "the Vigilant", "Spearhand", "Shieldbearer"),
            )

        // Example ZinTari civilian name bank
        val zintariCivilian =
            NameBank(
                givenNames = listOf("Zara", "Khalil", "Amara", "Hakim", "Naia", "Rashid", "Soraya", "Tariq"),
                familyNames = listOf("al-Zahir", "ibn Malik", "al-Rashid", "ibn Hakim", "al-Noor"),
                ranks = listOf("Merchant", "Artisan", "Scholar", "Trader"),
                culturalMarkers = listOf("the Wise", "the Skilled", "the Traveled", "of the Sands"),
            )

        nameBanks["alboran.guard"] = alboranGuard
        nameBanks["zintari.civilian"] = zintariCivilian

        // Save to files
        saveNameBank("alboran.guard", alboranGuard)
        saveNameBank("zintari.civilian", zintariCivilian)
    }

    /**
     * Save a name bank to file
     */
    private fun saveNameBank(
        bankName: String,
        nameBank: NameBank,
    ) {
        val file = File(nameBanksDirectory, "$bankName.yml")
        val config = YamlConfiguration()

        config.set("given_names", nameBank.givenNames)
        config.set("family_names", nameBank.familyNames)
        config.set("ranks", nameBank.ranks)
        config.set("cultural_markers", nameBank.culturalMarkers)

        try {
            config.save(file)
        } catch (e: Exception) {
            plugin.logger.warning("Failed to save name bank '$bankName': ${e.message}")
        }
    }

    /**
     * Generate or retrieve an alias for an NPC based on their anchor/ID
     */
    fun getOrCreateAlias(
        npcId: String,
        anchorKey: String?,
        nameBankName: String?,
        location: String?,
    ): NPCAlias {
        // Check if we already have a canonical name for this NPC ID
        val existingCanonicalName = npcIdToCanonicalName[npcId]
        if (existingCanonicalName != null) {
            return createAliasFromCanonicalName(existingCanonicalName, npcId)
        }

        // Determine the seed for deterministic generation
        val seed =
            when {
                anchorKey != null -> anchorKey.hashCode().toLong()
                else -> npcId.hashCode().toLong()
            }

        // Get the name bank to use
        val nameBank = getNameBank(nameBankName) ?: getDefaultNameBank()

        // Generate the alias
        val alias = generateAlias(nameBank, seed, location)

        // Store the mapping
        npcIdToCanonicalName[npcId] = alias.canonicalName
        canonicalNameToNpcId[alias.canonicalName] = npcId

        // Update recency cache for location-based reuse
        if (location != null) {
            updateRecencyCache(location, alias.canonicalName)
        }

        return alias
    }

    /**
     * Generate a new alias from a name bank using deterministic seeding
     */
    private fun generateAlias(
        nameBank: NameBank,
        seed: Long,
        location: String?,
    ): NPCAlias {
        val random = Random(seed)

        // Check recency cache for location to reuse recent names
        val recentNames = location?.let { locationRecencyCache[it] } ?: emptyList()

        // 30% chance to reuse a recent name if available
        if (recentNames.isNotEmpty() && random.nextFloat() < 0.3f) {
            val recentName = recentNames.random(random)
            return createAliasFromCanonicalName(recentName, seed.toString())
        }

        // Generate new name with collision detection
        var attempts = 0
        val maxAttempts = 50 // Prevent infinite loops

        while (attempts < maxAttempts) {
            val givenName = nameBank.givenNames.random(random)
            val familyName = nameBank.familyNames.random(random)
            val canonicalName = "$givenName $familyName"

            // Check if this canonical name is already taken by an active Citizens NPC
            if (!isCanonicalNameTaken(canonicalName)) {
                // Generate display handle (shortened for MC 16-char limit)
                val displayHandle = createDisplayHandle(givenName, familyName)

                // Generate optional callsign
                val callsign =
                    if (nameBank.culturalMarkers.isNotEmpty() && random.nextFloat() < 0.4f) {
                        nameBank.culturalMarkers.random(random)
                    } else {
                        null
                    }

                return NPCAlias(canonicalName, displayHandle, callsign)
            }

            attempts++
            // Use a different seed for the next attempt to get different results
            random.nextInt() // Advance the random state
        }

        // Fallback: if we can't find a unique name after many attempts, append a suffix
        val givenName = nameBank.givenNames.random(random)
        val familyName = nameBank.familyNames.random(random)
        val baseName = "$givenName $familyName"

        // Find a unique suffix
        var suffix = 1
        var canonicalName = "$baseName $suffix"
        while (isCanonicalNameTaken(canonicalName) && suffix < 100) {
            suffix++
            canonicalName = "$baseName $suffix"
        }

        val displayHandle = createDisplayHandle(givenName, "$familyName $suffix")
        val callsign =
            if (nameBank.culturalMarkers.isNotEmpty()) {
                nameBank.culturalMarkers.random(random)
            } else {
                null
            }

        plugin.logger.warning("Had to use fallback naming for NPC, generated: $canonicalName")
        return NPCAlias(canonicalName, displayHandle, callsign)
    }

    /**
     * Create an alias from an existing canonical name
     */
    private fun createAliasFromCanonicalName(
        canonicalName: String,
        npcId: String,
    ): NPCAlias {
        val parts = canonicalName.split(" ")
        val givenName = parts.getOrNull(0) ?: "Unknown"
        val familyName = parts.getOrNull(1) ?: ""

        val displayHandle = createDisplayHandle(givenName, familyName)

        // Generate callsign based on NPC ID if needed
        val callsign =
            if (npcId.length > 8) {
                npcId.takeLast(4).uppercase()
            } else {
                null
            }

        return NPCAlias(canonicalName, displayHandle, callsign)
    }

    /**
     * Create a display handle that fits within Minecraft's 16-character limit
     */
    private fun createDisplayHandle(
        givenName: String,
        familyName: String,
    ): String =
        when {
            familyName.isEmpty() -> givenName.take(16)
            (givenName.length + familyName.length + 1) <= 16 -> "$givenName $familyName"
            else -> "${givenName.take(1)}. $familyName".take(16)
        }

    /**
     * Update the recency cache for a location
     */
    private fun updateRecencyCache(
        location: String,
        canonicalName: String,
    ) {
        val cache = locationRecencyCache.getOrPut(location) { mutableListOf() }

        // Remove if already exists
        cache.remove(canonicalName)

        // Add to front
        cache.add(0, canonicalName)

        // Keep only last 10 names
        if (cache.size > 10) {
            cache.removeAt(cache.size - 1)
        }
    }

    /**
     * Get a name bank by name
     */
    private fun getNameBank(nameBankName: String?): NameBank? = nameBankName?.let { nameBanks[it] }

    /**
     * Get the default name bank
     */
    private fun getDefaultNameBank(): NameBank =
        nameBanks.values.firstOrNull() ?: NameBank(
            givenNames = listOf("Unknown"),
            familyNames = listOf("Stranger"),
        )

    /**
     * Get canonical name from NPC ID
     */
    fun getCanonicalName(npcId: String): String? = npcIdToCanonicalName[npcId]

    /**
     * Get NPC ID from canonical name
     */
    fun getNpcId(canonicalName: String): String? = canonicalNameToNpcId[canonicalName]

    /**
     * Check if an NPC has an assigned alias
     */
    fun hasAlias(npcId: String): Boolean = npcIdToCanonicalName.containsKey(npcId)

    /**
     * Reload name banks from disk
     */
    fun reloadNameBanks() {
        loadNameBanks()
    }

    /**
     * Get all available name bank names
     */
    fun getAvailableNameBanks(): Set<String> = nameBanks.keys

    /**
     * Check if a canonical name is already taken by an active Citizens NPC
     */
    private fun isCanonicalNameTaken(canonicalName: String): Boolean {
        // Check if it's already in our mapping
        if (canonicalNameToNpcId.containsKey(canonicalName)) {
            val existingNpcId = canonicalNameToNpcId[canonicalName]
            // Check if the NPC with this ID still exists in Citizens
            if (existingNpcId != null && isNpcIdActive(existingNpcId)) {
                return true
            } else {
                // Clean up stale mapping
                canonicalNameToNpcId.remove(canonicalName)
                if (existingNpcId != null) {
                    npcIdToCanonicalName.remove(existingNpcId)
                }
            }
        }

        // Check if any active Citizens NPC has this name
        val citizensNPCs =
            net.citizensnpcs.api.CitizensAPI
                .getNPCRegistry()
        return citizensNPCs.any { npc -> npc.name == canonicalName }
    }

    /**
     * Check if an NPC ID is still active in Citizens
     */
    private fun isNpcIdActive(npcId: String): Boolean =
        try {
            val citizensNPCs =
                net.citizensnpcs.api.CitizensAPI
                    .getNPCRegistry()
            citizensNPCs.any { npc -> npc.uniqueId.toString() == npcId }
        } catch (e: Exception) {
            plugin.logger.warning("Error checking if NPC ID $npcId is active: ${e.message}")
            false
        }
}
