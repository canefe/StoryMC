package com.canefe.story.npc.data

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.npc.CitizensStoryNPC
import com.canefe.story.npc.memory.Memory
import com.canefe.story.storage.NpcStorage
import net.citizensnpcs.api.CitizensAPI
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException

class NPCDataManager(
    private val plugin: Story,
    private var npcStorage: NpcStorage,
) {
    fun updateStorage(storage: NpcStorage) {
        npcStorage = storage
    }

    // Cache keyed by storage key (name-based for now, character ID in future)
    private val npcDataCache: MutableMap<String, NPCData> = HashMap()

    // Secondary index: character ID → storage key
    private val characterIdIndex: MutableMap<String, String> = HashMap()
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
        for (npcName in getAllNPCNames()) {
            val npcData = getNPCData(npcName)
            if (npcData != null) {
                allNPCData.add(npcData)
            } else {
                plugin.logger.warning("Failed to load NPC data for $npcName")
            }
        }
        return allNPCData
    }

    /**
     * Get NPC data by key. Accepts either:
     * - A character ID (e.g. "thorne_mossveil_a3f2b1c4") — resolved via CharacterRegistry
     * - An NPC name (e.g. "Guard") — resolved via storage directly
     */
    fun getNPCData(key: String): NPCData? {
        // Check cache by key directly
        npcDataCache[key]?.let { return it }

        // Check if key is a character ID mapped to a different storage key
        characterIdIndex[key]?.let { storageKey ->
            npcDataCache[storageKey]?.let { return it }
        }

        // Try resolving via character registry
        // Key might be a character ID or a display name — try both
        val registryRecord =
            try {
                plugin.characterRegistry.getById(key)
                    ?: plugin.characterRegistry.getByName(key)
            } catch (_: Exception) {
                null
            }

        // Determine the storage key: registry name if found, otherwise the key itself
        val storageKey = registryRecord?.name ?: key

        try {
            val npcData = npcStorage.loadNpcData(storageKey) ?: return null

            // Resolve location reference
            val locationName = npcData.locationName
            val storyLocation =
                if (locationName != null) {
                    plugin.locationManager.getLocation(locationName)
                        ?: plugin.locationManager.createLocation(locationName, null)
                } else {
                    plugin.locationManager.getOrCreateDefaultLocation()
                }
            npcData.storyLocation = storyLocation

            // Load memories
            npcData.memory = npcStorage.loadNpcMemories(storageKey)

            // Check if the NPC data is in old format and needs migration
            if (npcDataMigrator.isOldFormat(npcData)) {
                plugin.logger.info("Old data format detected for NPC $storageKey, starting migration...")
                cacheNPCData(storageKey, npcData)

                npcDataMigrator.migrateToNewFormat(storageKey, npcData).thenAccept { migratedData ->
                    cacheNPCData(storageKey, migratedData)
                    saveNPCData(storageKey, migratedData)
                    plugin.logger.info("Migration completed for NPC $storageKey")
                }

                return npcData
            }

            cacheNPCData(storageKey, npcData)
            return npcData
        } catch (e: Exception) {
            plugin.logger.severe("Failed to load NPC data for $key: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    /**
     * Get NPC data by StoryNPC. Resolves through the character registry first
     * to handle multiple NPCs with the same display name.
     */
    fun getNPCData(npc: StoryNPC): NPCData? {
        val characterId =
            try {
                plugin.characterRegistry.getCharacterIdForNPC(npc)
            } catch (_: Exception) {
                null
            }
        return if (characterId != null) getNPCData(characterId) else getNPCData(npc.name)
    }

    /**
     * Creates a memory for an NPC. Requires an active session — if no session
     * is active, the memory is silently dropped to prevent non-canon mutations.
     */
    fun createMemoryForNPC(
        key: String,
        content: String,
        significance: Double = 1.0,
    ) {
        if (!plugin.sessionManager.hasActiveSession()) {
            plugin.logger.info("Memory skipped for $key — no active session (content: ${content.take(60)}...)")
            return
        }

        val npcData = getNPCData(key) ?: return

        val memory =
            Memory(
                content = content,
                gameCreatedAt = plugin.timeService.getCurrentGameTime(),
                lastAccessed = plugin.timeService.getCurrentGameTime(),
                power = 1.0,
                _significance = significance,
                sessionId = plugin.sessionManager.getCurrentSessionId(),
            )

        npcData.memory.add(memory)
        saveNPCData(resolveStorageKey(key), npcData)
    }

    /**
     * Removes all memories tagged with the given session ID from an NPC.
     */
    fun removeMemoriesBySession(
        key: String,
        sessionId: String,
    ): Int {
        val npcData = getNPCData(key) ?: return 0
        val before = npcData.memory.size
        npcData.memory.removeAll { it.sessionId == sessionId }
        val removed = before - npcData.memory.size
        if (removed > 0) {
            saveNPCData(resolveStorageKey(key), npcData)
        }
        return removed
    }

    /**
     * Removes all memories tagged with the given session ID from ALL NPCs.
     */
    fun removeAllMemoriesBySession(sessionId: String): Int {
        var total = 0
        for (npcName in getAllNPCNames()) {
            total += removeMemoriesBySession(npcName, sessionId)
        }
        return total
    }

    fun loadNPCMemory(key: String): MutableList<Memory> = npcStorage.loadNpcMemories(resolveStorageKey(key))

    fun saveNPCData(
        key: String,
        npcData: NPCData,
    ) {
        val storageKey = resolveStorageKey(key)
        npcStorage.saveNpcData(storageKey, npcData)
        cacheNPCData(storageKey, npcData)
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

    fun getNPC(npcName: String): StoryNPC? {
        val actualFileName = npcStorage.resolveNpcKey(npcName) ?: return null

        val npc =
            CitizensAPI.getNPCRegistry().find {
                it.name.equals(actualFileName, ignoreCase = true) ||
                    it.name.equals(npcName, ignoreCase = true)
            } ?: return null

        return CitizensStoryNPC(npc)
    }

    fun deleteNPCFile(key: String) {
        val storageKey = resolveStorageKey(key)
        npcStorage.deleteNpc(storageKey)
        npcDataCache.remove(storageKey)
        // Clean up character ID index
        characterIdIndex.entries.removeIf { it.value == storageKey }
    }

    fun loadConfig() {
        npcDataCache.clear()
        characterIdIndex.clear()
        plugin.logger.info("NPC data cache cleared")
    }

    /**
     * Caches NPC data and indexes by character ID if available.
     */
    private fun cacheNPCData(
        storageKey: String,
        npcData: NPCData,
    ) {
        npcDataCache[storageKey] = npcData

        // Build character ID index
        try {
            val record = plugin.characterRegistry.getByName(npcData.name)
            if (record != null) {
                characterIdIndex[record.id] = storageKey
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Resolves a key (character ID or name) to the storage key.
     */
    private fun resolveStorageKey(key: String): String {
        // If the key is already a known storage key, use it
        if (npcDataCache.containsKey(key)) return key

        // Check character ID index
        characterIdIndex[key]?.let { return it }

        // Try registry — key might be a character ID or a name
        try {
            val record =
                plugin.characterRegistry.getById(key)
                    ?: plugin.characterRegistry.getByName(key)
            if (record != null) return record.name
        } catch (_: Exception) {
        }

        return key
    }
}
