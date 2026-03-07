@file:Suppress("DEPRECATION")

package com.canefe.story.storage.yaml

import com.canefe.story.npc.data.NPCData
import com.canefe.story.npc.memory.Memory
import com.canefe.story.storage.NpcStorage
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.logging.Logger

/**
 * YAML-based NPC storage implementation.
 * @deprecated YAML storage is deprecated and has known bugs with concurrent access. Use MongoDB instead.
 */
@Deprecated("YAML storage is deprecated and has known bugs. Use MongoDB backend.")
class YamlNpcStorage(
    private val npcDirectory: File,
    private val logger: Logger,
) : NpcStorage {
    // Index cache for displayHandle -> filename mappings
    private val displayHandleToFileIndex: MutableMap<String, String> = HashMap()
    private var indexBuilt = false

    init {
        if (!npcDirectory.exists()) {
            npcDirectory.mkdirs()
        }
    }

    fun buildDisplayHandleIndex() {
        if (indexBuilt) return

        displayHandleToFileIndex.clear()
        val allFiles = npcDirectory.listFiles { _, name -> name.endsWith(".yml") }
        allFiles?.forEach { file ->
            try {
                val tempConfig = YamlConfiguration.loadConfiguration(file)
                val fileDisplayHandle = tempConfig.getString("displayHandle")
                if (fileDisplayHandle != null) {
                    displayHandleToFileIndex[fileDisplayHandle] = file.nameWithoutExtension
                }
            } catch (_: Exception) {
                // Skip malformed files
            }
        }
        indexBuilt = true
        logger.info("Built display handle index with ${displayHandleToFileIndex.size} mappings")
    }

    fun resetIndex() {
        displayHandleToFileIndex.clear()
        indexBuilt = false
        buildDisplayHandleIndex()
    }

    override fun findNpcByDisplayHandle(displayHandle: String): String? {
        if (!indexBuilt) buildDisplayHandleIndex()
        return displayHandleToFileIndex[displayHandle]
    }

    override fun resolveNpcKey(npcName: String): String? {
        if (!indexBuilt) buildDisplayHandleIndex()

        // Check if this is a display handle in our index
        displayHandleToFileIndex[npcName]?.let { return it }

        // If not in index, try normalized filename
        val normalizedFileName = npcName.replace(" ", "_").lowercase()
        val normalizedFile = File(npcDirectory, "$normalizedFileName.yml")
        if (normalizedFile.exists()) {
            return normalizedFileName
        }

        // Try original filename
        val originalFile = File(npcDirectory, "$npcName.yml")
        if (originalFile.exists()) {
            return npcName
        }

        return null
    }

    override fun getAllNpcNames(): List<String> {
        val npcNames = ArrayList<String>()
        val files = npcDirectory.listFiles { _, name -> name.endsWith(".yml") }
        files?.forEach { file ->
            npcNames.add(file.nameWithoutExtension)
        }
        return npcNames
    }

    override fun loadNpcData(npcName: String): NPCData? {
        val actualFileName = resolveNpcKey(npcName) ?: return null

        val npcFile = File(npcDirectory, "$actualFileName.yml")
        if (!npcFile.exists()) return null

        return try {
            val config = YamlConfiguration.loadConfiguration(npcFile)
            val name = config.getString("name") ?: npcName
            val role = config.getString("role") ?: ""
            val context = config.getString("context") ?: ""
            val appearance = config.getString("appearance") ?: ""
            val avatar = config.getString("avatar") ?: ""
            val customVoice = config.getString("customVoice")
            val locationName = config.getString("location")

            val knowledgeCategories = mutableListOf<String>()
            val categoriesSection = config.getConfigurationSection("knowledgeCategories")
            if (categoriesSection != null) {
                categoriesSection.getKeys(false).forEach { key ->
                    val category = categoriesSection.getString(key)
                    if (category != null) knowledgeCategories.add(category)
                }
            } else {
                knowledgeCategories.addAll(config.getStringList("knowledgeCategories").map { it.toString() })
            }

            val randomPathing = config.getBoolean("randomPathing", true)
            val generic = config.getBoolean("generic", false)
            val nameBank = config.getString("nameBank")
            val npcId = config.getString("npcId")
            val anchorKey = config.getString("anchorKey")
            val canonicalName = config.getString("canonicalName")
            val displayHandle = config.getString("displayHandle")
            val callsign = config.getString("callsign")

            // Create NPCData with null storyLocation — the manager will resolve it
            val npcData = NPCData(name = name, role = role, storyLocation = null, context = context)
            npcData.avatar = avatar
            npcData.knowledgeCategories = knowledgeCategories
            npcData.appearance = appearance
            npcData.randomPathing = randomPathing
            npcData.customVoice = customVoice
            npcData.generic = generic
            npcData.nameBank = nameBank
            npcData.npcId = npcId
            npcData.anchorKey = anchorKey
            npcData.canonicalName = canonicalName
            npcData.displayHandle = displayHandle
            npcData.callsign = callsign
            // Store the location name in a temporary way — we use the context field is not ideal.
            // Instead, store as a tag in the object for the manager to resolve.
            // We'll use a companion property for this.
            npcData.locationName = locationName

            npcData
        } catch (e: Exception) {
            logger.severe("Failed to load NPC data for $npcName: ${e.message}")
            null
        }
    }

    override fun saveNpcData(
        npcName: String,
        npcData: NPCData,
    ) {
        val actualFileName = resolveNpcKey(npcName) ?: npcName.replace(" ", "_").lowercase()

        val npcFile = File(npcDirectory, "$actualFileName.yml")
        val config = YamlConfiguration()

        config.set("name", npcData.name)
        config.set("role", npcData.role)
        config.set("location", npcData.storyLocation?.name)
        config.set("context", npcData.context)
        config.set("appearance", npcData.appearance)
        config.set("customVoice", npcData.customVoice)

        // Save memories
        if (npcData.memory.isNotEmpty()) {
            val memoriesSection = config.createSection("memories")
            for (memory in npcData.memory) {
                if (memory.id.isBlank()) continue
                try {
                    val memorySection = memoriesSection.createSection(memory.id)
                    memorySection.set("content", memory.content)
                    memorySection.set("realCreatedAt", memory.realCreatedAt.toString())
                    memorySection.set("gameCreatedAt", memory.gameCreatedAt)
                    memorySection.set("power", memory.power)
                    memorySection.set("lastAccessed", memory.lastAccessed)
                    memorySection.set("significance", memory.significance)
                } catch (e: Exception) {
                    logger.severe("Failed to save memory for NPC $npcName: ${e.message}")
                }
            }
        } else {
            config.set("memories", emptyMap<String, Any>())
        }

        config.set("avatar", npcData.avatar)

        if (npcData.knowledgeCategories.isNotEmpty()) {
            config.set("knowledgeCategories", ArrayList(npcData.knowledgeCategories))
        }

        config.set("randomPathing", npcData.randomPathing)
        config.set("generic", npcData.generic)
        config.set("nameBank", npcData.nameBank)
        config.set("npcId", npcData.npcId)
        config.set("anchorKey", npcData.anchorKey)
        config.set("canonicalName", npcData.canonicalName)
        config.set("displayHandle", npcData.displayHandle)
        config.set("callsign", npcData.callsign)

        try {
            config.save(npcFile)
        } catch (e: IOException) {
            logger.severe("Failed to save NPC file for $npcName: ${e.message}")
        }

        // Update the display handle index
        if (npcData.displayHandle != null) {
            displayHandleToFileIndex[npcData.displayHandle!!] = actualFileName
        }
    }

    override fun deleteNpc(npcName: String) {
        val npcFile = File(npcDirectory, "$npcName.yml")
        if (npcFile.exists()) {
            npcFile.delete()
        }
    }

    override fun loadNpcMemories(npcName: String): MutableList<Memory> {
        val actualFileName = resolveNpcKey(npcName) ?: return mutableListOf()

        val npcFile = File(npcDirectory, "$actualFileName.yml")
        if (!npcFile.exists()) return mutableListOf()

        val config = YamlConfiguration.loadConfiguration(npcFile)
        val memoriesSection = config.getConfigurationSection("memories") ?: return mutableListOf()

        val memories = mutableListOf<Memory>()

        for (id in memoriesSection.getKeys(false)) {
            val memorySection = memoriesSection.getConfigurationSection(id) ?: continue
            val content = memorySection.getString("content") ?: continue
            val power = memorySection.getDouble("power", 1.0)

            val realCreatedAt =
                try {
                    val createdAtStr = memorySection.getString("realCreatedAt")
                    if (createdAtStr != null) Instant.parse(createdAtStr) else Instant.now()
                } catch (_: Exception) {
                    Instant.now()
                }

            val gameCreatedAt = memorySection.getLong("gameCreatedAt", 0)
            val lastAccessed = memorySection.getLong("lastAccessed", 0)
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

        return memories
    }
}
