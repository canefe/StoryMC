package com.canefe.story.npc.data

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.npc.memory.Memory
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import java.time.Instant

class NPCDataManager private constructor(private val plugin: Story) {
    private val npcDataMap: MutableMap<String, NPCData> = HashMap()
    val npcDirectory: File = File(plugin.dataFolder, "npcs").apply {
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
        val npcFile = File(npcDirectory, "$npcName.yml")
        if (!npcFile.exists()) {
            return null // Return null if no file exists
        }

        val config = YamlConfiguration.loadConfiguration(npcFile)
        val name = config.getString("name") ?: return null
        val role = config.getString("role") ?: return null
        val location = config.getString("location") ?: return null
        val context = config.getString("context") ?: return null
        val avatar = config.getString("avatar") ?: ""
        val relations = config.getConfigurationSection("relations")?.getValues(false) ?: emptyMap<String, Int>()
        val knowledgeCategories = config.getStringList("knowledgeCategories").map { it.toString() }

        val storyLocation = plugin.locationManager.getLocation(location) ?: plugin.locationManager.createLocation(location, null)

        val npcData = NPCData(
            name = name,
            role = role,
            storyLocation = storyLocation,
            context = context
        )

        // Use loadNPCMemory to get the memory objects
        npcData.memory = loadNPCMemory(npcName)
        npcData.avatar = avatar
        npcData.relations = relations.mapValues { it.value as Int }
        npcData.knowledgeCategories = knowledgeCategories

        return npcData
    }

    fun loadNPCMemory(npcName: String): MutableList<Memory> {
        val npcFile = File(npcDirectory, "$npcName.yml")
        if (!npcFile.exists()) {
            return mutableListOf() // Return an empty list if no file exists
        }

        val config = YamlConfiguration.loadConfiguration(npcFile)
        val memoriesSection = config.getConfigurationSection("memories") ?: return mutableListOf()

        val memories = mutableListOf<Memory>()
        for (id in memoriesSection.getKeys(false)) {
            val memorySection = memoriesSection.getConfigurationSection(id) ?: continue

            val content = memorySection.getString("content") ?: continue
            val createdAtStr = memorySection.getString("createdAt") ?: continue
            val power = memorySection.getDouble("power", 1.0)
            val lastAccessedStr = memorySection.getString("lastAccessed") ?: continue

            // Parse times
            val createdAt = Instant.parse(createdAtStr)
            val lastAccessed = Instant.parse(lastAccessedStr)

            memories.add(Memory(id, content, createdAt, power, lastAccessed))
        }

        return memories
    }

    fun saveNPCData(npcName: String, npcData: NPCData) {
        val npcFile = File(npcDirectory, "$npcName.yml")
        val config = YamlConfiguration()

        // Save NPC data to the configuration
        config.set("name", npcData.name)
        config.set("role", npcData.role)
        config.set("location", npcData.storyLocation?.name)
        config.set("context", npcData.context)

        // Save memories in structured format
        val memoriesSection = config.createSection("memories")
        for (memory in npcData.memory) {
            val memorySection = memoriesSection.createSection(memory.id)
            memorySection.set("content", memory.content)
            memorySection.set("createdAt", memory.createdAt.toString())
            memorySection.set("power", memory.power)
            memorySection.set("lastAccessed", memory.lastAccessed.toString())
        }

        // Other properties
        config.set("avatar", npcData.avatar)
        config.set("relations", npcData.relations)
        config.set("knowledgeCategories", npcData.knowledgeCategories)

        try {
            config.save(npcFile)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun saveNPCFile(npcName: String, config: FileConfiguration) {
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

    companion object {
        private var instance: NPCDataManager? = null

        @JvmStatic
        fun getInstance(plugin: Story): NPCDataManager {
            return instance ?: NPCDataManager(plugin).also { instance = it }
        }
    }
}