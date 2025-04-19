package com.canefe.story.npc.data

import com.canefe.story.Story
import com.canefe.story.npc.memory.Memory
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.time.Instant

class NPCDataManager private constructor(private val plugin: Story) {
	private val npcDataCache: MutableMap<String, NPCData> = HashMap()
	private val npcDataMigrator = NPCDataMigrator(plugin)

	val npcDirectory: File =
		File(plugin.dataFolder, "npcs").apply {
			if (!exists()) {
				mkdirs() // Create the directory if it doesn't exist
			}
		}

	/**
	 * Gets a list of all NPC names by scanning the NPC directory for YAML files.
	 *
	 * @return A list of all NPC names without the .yml extension
	 */
	fun getAllNPCNames(): List<String> {
		val npcNames = ArrayList<String>()

		val files = npcDirectory.listFiles { _, name -> name.endsWith(".yml") }
		files?.forEach { file ->
			val fileName = file.name
			// Remove the .yml extension to get the NPC name
			val npcName = fileName.substring(0, fileName.length - 4)
			npcNames.add(npcName)
		}

		return npcNames
	}

	fun loadNPCData(npcName: String): FileConfiguration {
		val npcFile = File(npcDirectory, "$npcName.yml")
		if (!npcFile.exists()) {
			return YamlConfiguration() // Return an empty configuration if no file exists
		}

		return YamlConfiguration.loadConfiguration(npcFile)
	}

	fun getNPCData(npcName: String): NPCData? {
		// Check if NPC data is already cached
		if (npcDataCache.containsKey(npcName)) {
			return npcDataCache[npcName]
		}

		try {
			val npcFile = File(npcDirectory, "$npcName.yml")
			if (!npcFile.exists()) {
				return null // Return null if no file exists
			}

			val config = YamlConfiguration.loadConfiguration(npcFile)
			val name = config.getString("name") ?: npcName
			val role = config.getString("role") ?: ""
			val location = config.getString("location") ?: "Village"
			val context = config.getString("context") ?: ""
			val avatar = config.getString("avatar") ?: ""
			val knowledgeCategories = config.getStringList("knowledgeCategories").map { it.toString() }

			val storyLocation =
				plugin.locationManager.getLocation(location) ?: plugin.locationManager.createLocation(location, null)

			val npcData =
				NPCData(
					name = name,
					role = role,
					storyLocation = storyLocation,
					context = context,
				)

			// Use loadNPCMemory to get the memory objects
			npcData.memory = loadNPCMemory(npcName)
			npcData.avatar = avatar
			npcData.knowledgeCategories = knowledgeCategories

			// Check if the NPC data is in old format and needs migration
			if (npcDataMigrator.isOldFormat(npcData)) {
				plugin.logger.info("Old data format detected for NPC $npcName, starting migration...")

				// Migration is asynchronous, but we need to return data now
				// So we cache the initial data and start the migration
				npcDataCache[npcName] = npcData

				// Start migration asynchronously
				npcDataMigrator.migrateToNewFormat(npcName, npcData).thenAccept { migratedData ->
					// Update the cache with the migrated data
					npcDataCache[npcName] = migratedData

					// Save the migrated data back to file
					saveNPCData(npcName, migratedData)

					plugin.logger.info("Migration completed for NPC $npcName")
				}

				return npcData
			}

			// Normal case - no migration needed
			npcDataCache[npcName] = npcData
			return npcData
		} catch (e: Exception) {
			plugin.logger.severe("Failed to load NPC data for $npcName: ${e.message}")
			e.printStackTrace()
			return null
		}
	}

	fun createMemoryForNPC(
		npcName: String,
		content: String,
		significance: Double = 1.0,
	) {
		val npcData = getNPCData(npcName) ?: return

		val memory =
			Memory(
				content = content,
				gameCreatedAt = plugin.timeService.getCurrentGameTime(),
				lastAccessed = plugin.timeService.getCurrentGameTime(),
				power = 1.0,
				_significance = significance,
			)

		npcData.memory.add(memory)
		saveNPCData(npcName, npcData)
	}

	fun loadNPCMemory(npcName: String): MutableList<Memory> {
		val npcFile = File(npcDirectory, "$npcName.yml")
		if (!npcFile.exists()) {
			plugin.logger.info("No NPC file exists for $npcName, returning empty memory list")
			return mutableListOf()
		}

		val config = YamlConfiguration.loadConfiguration(npcFile)
		val memoriesSection = config.getConfigurationSection("memories")
		if (memoriesSection == null) {
			plugin.logger.info("No memories section found for NPC $npcName")
			return mutableListOf()
		}

		val memories = mutableListOf<Memory>()
		val currentGameTime = plugin.timeService.getCurrentGameTime()

		for (id in memoriesSection.getKeys(false)) {
			val memorySection = memoriesSection.getConfigurationSection(id) ?: continue

			val content = memorySection.getString("content") ?: continue
			val power = memorySection.getDouble("power", 1.0)

			// Handle real created time
			val realCreatedAt =
				try {
					val createdAtStr = memorySection.getString("realCreatedAt")
					if (createdAtStr != null) Instant.parse(createdAtStr) else Instant.now()
				} catch (e: Exception) {
					plugin.logger.warning("Invalid realCreatedAt for memory $id of NPC $npcName: ${e.message}")
					Instant.now()
				}

			// Handle game created time with better defaults
			val gameCreatedAt = memorySection.getLong("gameCreatedAt", currentGameTime)

			// Handle last accessed with better defaults
			val lastAccessed = memorySection.getLong("lastAccessed", currentGameTime)

			// And add this to the loadNPCMemory method when creating memories
			val significance = memorySection.getDouble("significance", 1.0)

			memories.add(
				Memory(
					id = id,
					content = content,
					realCreatedAt = realCreatedAt,
					gameCreatedAt = gameCreatedAt,
					power = power,
					lastAccessed = lastAccessed,
					_significance = significance,
				),
			)
		}

		plugin.logger.info("Loaded ${memories.size} memories for NPC $npcName")
		return memories
	}

	fun saveNPCData(
		npcName: String,
		npcData: NPCData,
	) {
		val npcFile = File(npcDirectory, "$npcName.yml")
		val config = YamlConfiguration()

		// Save basic NPC data
		config.set("name", npcData.name)
		config.set("role", npcData.role)
		config.set("location", npcData.storyLocation?.name)
		config.set("context", npcData.context)

		// Save memories in structured format
		if (npcData.memory.isNotEmpty()) {
			val memoriesSection = config.createSection("memories")
			for (memory in npcData.memory) {
				// Make sure memory has a valid ID
				if (memory.id.isBlank()) {
					plugin.logger.warning("Memory has blank ID for NPC $npcName: ${memory.content}")
					continue
				}

				try {
					val memorySection = memoriesSection.createSection(memory.id)
					memorySection.set("content", memory.content)
					memorySection.set("realCreatedAt", memory.realCreatedAt.toString())
					memorySection.set("gameCreatedAt", memory.gameCreatedAt)
					memorySection.set("power", memory.power)
					memorySection.set("lastAccessed", memory.lastAccessed)
					memorySection.set("significance", memory.significance)
					plugin.logger.info("Saved memory ${memory.id} for NPC $npcName")
				} catch (e: Exception) {
					plugin.logger.severe("Failed to save memory for NPC $npcName: ${e.message}")
					e.printStackTrace()
				}
			}
		} else {
			config.set("memories", emptyMap<String, Any>())
		}

		// Other properties
		config.set("avatar", npcData.avatar)
		config.set("knowledgeCategories", npcData.knowledgeCategories)

		try {
			config.save(npcFile)
			plugin.logger.info("Saved NPC data for $npcName with ${npcData.memory.size} memories")
		} catch (e: IOException) {
			plugin.logger.severe("Failed to save NPC file for $npcName: ${e.message}")
			e.printStackTrace()
		}
	}

	fun saveNPCFile(
		npcName: String,
		config: FileConfiguration,
	) {
		val npcFile = File(npcDirectory, "$npcName.yml")
		try {
			config.save(npcFile)
		} catch (e: IOException) {
			e.printStackTrace()
		}
	}

	fun getNPC(npcName: String): NPC? {
		val npcFile = File(npcDirectory, "$npcName.yml")
		if (!npcFile.exists()) {
			return null // Return null if no file exists
		}

		// Try to find npc by name in citizens registry
		return CitizensAPI.getNPCRegistry().find { it.name.equals(npcName, ignoreCase = true) }
	}

	fun deleteNPCFile(npcName: String) {
		val npcFile = File(npcDirectory, "$npcName.yml")
		if (npcFile.exists()) {
			npcFile.delete()
		}
	}

	// Reset the NPC data cache
	fun loadConfig() {
		npcDataCache.clear()
		plugin.logger.info("NPC data cache cleared")
	}

	companion object {
		private var instance: NPCDataManager? = null

		@JvmStatic
		fun getInstance(plugin: Story): NPCDataManager {
			return instance ?: NPCDataManager(plugin).also { instance = it }
		}
	}
}
