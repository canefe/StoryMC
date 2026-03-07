package com.canefe.story.npc.data

import com.canefe.story.Story
import com.canefe.story.npc.memory.Memory
import com.canefe.story.storage.NpcStorage
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException

class NPCDataManager private constructor(
    private val plugin: Story,
    private var npcStorage: NpcStorage,
) {
    fun updateStorage(storage: NpcStorage) {
        npcStorage = storage
    }

    private val npcDataCache: MutableMap<String, NPCData> = HashMap()
    private val npcDataMigrator = NPCDataMigrator(plugin)

    val npcDirectory: File =
        File(plugin.dataFolder, "npcs").apply {
            if (!exists()) {
                mkdirs()
            }
        }

    fun getAllNPCNames(): List<String> = npcStorage.getAllNpcNames()

    fun loadNPCData(npcName: String): FileConfiguration {
        val npcFile = File(npcDirectory, "$npcName.yml")
        if (!npcFile.exists()) {
            return YamlConfiguration()
        }
        return YamlConfiguration.loadConfiguration(npcFile)
    }

    fun getAllNPCData(): List<NPCData> {
        val allNPCData = mutableListOf<NPCData>()
        val npcNames = getAllNPCNames()

        for (npcName in npcNames) {
            val npcData = getNPCData(npcName)
            if (npcData != null) {
                allNPCData.add(npcData)
            } else {
                plugin.logger.warning("Failed to load NPC data for $npcName")
            }
        }

        return allNPCData
    }

    fun getNPCData(npcName: String): NPCData? {
        // Check if NPC data is already cached
        if (npcDataCache.containsKey(npcName)) {
            return npcDataCache[npcName]
        }

        try {
            val npcData = npcStorage.loadNpcData(npcName) ?: return null

            // Resolve location reference
            val locationName = npcData.locationName ?: "Village"
            val storyLocation =
                plugin.locationManager.getLocation(locationName)
                    ?: plugin.locationManager.createLocation(locationName, null)
            npcData.storyLocation = storyLocation

            // Load memories
            npcData.memory = npcStorage.loadNpcMemories(npcName)

            // Check if the NPC data is in old format and needs migration
            if (npcDataMigrator.isOldFormat(npcData)) {
                plugin.logger.info("Old data format detected for NPC $npcName, starting migration...")
                npcDataCache[npcName] = npcData

                npcDataMigrator.migrateToNewFormat(npcName, npcData).thenAccept { migratedData ->
                    npcDataCache[npcName] = migratedData
                    saveNPCData(npcName, migratedData)
                    plugin.logger.info("Migration completed for NPC $npcName")
                }

                return npcData
            }

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

    fun loadNPCMemory(npcName: String): MutableList<Memory> = npcStorage.loadNpcMemories(npcName)

    fun saveNPCData(
        npcName: String,
        npcData: NPCData,
    ) {
        npcStorage.saveNpcData(npcName, npcData)
        npcDataCache[npcName] = npcData
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
        val actualFileName = npcStorage.resolveNpcKey(npcName) ?: return null

        return CitizensAPI.getNPCRegistry().find {
            it.name.equals(actualFileName, ignoreCase = true) ||
                it.name.equals(npcName, ignoreCase = true)
        }
    }

    fun deleteNPCFile(npcName: String) {
        npcStorage.deleteNpc(npcName)
        npcDataCache.remove(npcName)
    }

    fun loadConfig() {
        npcDataCache.clear()
        plugin.logger.info("NPC data cache cleared")
    }

    companion object {
        private var instance: NPCDataManager? = null

        @JvmStatic
        fun getInstance(
            plugin: Story,
            npcStorage: NpcStorage,
        ): NPCDataManager = instance ?: NPCDataManager(plugin, npcStorage).also { instance = it }

        @JvmStatic
        fun getInstance(plugin: Story): NPCDataManager =
            instance
                ?: throw IllegalStateException(
                    "NPCDataManager not initialized. Call getInstance(plugin, npcStorage) first.",
                )
    }
}
