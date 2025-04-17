package com.canefe.story.lore

import com.canefe.story.Story
import com.canefe.story.conversation.Conversation
import org.bukkit.Bukkit
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class LoreBookManager private constructor(private val plugin: Story) {
    private val loreBooks: MutableMap<String, LoreBook> = HashMap()
    private val loreFolder = File(plugin.dataFolder, "lore").apply {
        if (!exists()) mkdirs()
    }

    // Track recently added lore contexts by conversation to avoid duplicates
    private val recentlyAddedLoreContexts: MutableMap<Conversation, MutableSet<String>> = ConcurrentHashMap()
    private val npcKnowledgeCategories: MutableMap<String, Set<String>> = HashMap()
    private val CONTEXT_COOLDOWN_MS = 60000 // 1 minute cooldown

    init {
        loadConfig()

        // Schedule cleanup of old context entries
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::cleanupOldContextEntries, 1200L, 1200L) // Run every minute
    }

    fun loadConfig() {
        loadAllLoreBooks()
        loadNPCKnowledgeCategories()
    }

    private fun cleanupOldContextEntries() {
        val iterator = recentlyAddedLoreContexts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.key.isActive || entry.value.isEmpty()) {
                iterator.remove()
            }
        }
    }

    // Method to load NPC knowledge categories
    fun loadNPCKnowledgeCategories() {
        npcKnowledgeCategories.clear()

        // Default everyone knows "common" knowledge
        for (npcName in plugin.npcDataManager.getAllNPCNames()) {
            val categories = mutableSetOf("common")

            val npcData = plugin.npcDataManager.getNPCData(npcName) ?: continue
            val configCategories = npcData.knowledgeCategories
            categories.addAll(configCategories)

            npcKnowledgeCategories[npcName.lowercase()] = categories
        }
    }

    fun loadAllLoreBooks() {
        loreBooks.clear()
        val files = loreFolder.listFiles { _, name -> name.endsWith(".yml") } ?: return

        for (file in files) {
            try {
                val config = YamlConfiguration.loadConfiguration(file)
                val name = config.getString("name") ?: continue
                val context = config.getString("context") ?: continue
                val keywords = config.getStringList("keywords")
                val categoryList = config.getStringList("categories")
                val categories = if (categoryList.isEmpty()) setOf("common") else categoryList.toSet()

                if (keywords.isNotEmpty()) {
                    val loreBook = LoreBook(name, context, keywords, categories)
                    loreBooks[name.lowercase()] = loreBook
                    plugin.logger.info("Loaded lorebook: $name")
                } else {
                    plugin.logger.warning("Invalid lorebook format in file: ${file.name}")
                }
            } catch (e: Exception) {
                plugin.logger.warning("Error loading lorebook from file: ${file.name}")
                e.printStackTrace()
            }
        }
        plugin.logger.info("Loaded ${loreBooks.size} lorebooks")
    }

    fun findRelevantLoreContexts(message: String, conversation: Conversation): List<LoreContext> {
        val relevantContexts = mutableListOf<LoreContext>()
        val messageLower = message.lowercase()
        val addedLoreNames = HashSet<String>() // Track lorebooks added in this call

        // Initialize the set for this conversation if needed
        val recentContexts = recentlyAddedLoreContexts.getOrPut(conversation) { HashSet() }

        // Get all knowledge categories accessible in this conversation
        val conversationKnowledgeCategories = mutableSetOf("common") // Everyone knows common knowledge

        for (npcName in conversation.npcNames) {
            npcKnowledgeCategories[npcName.lowercase()]?.let { npcCategories ->
                conversationKnowledgeCategories.addAll(npcCategories)
            }
        }

        for (loreBook in loreBooks.values) {
            // Skip if this lorebook was recently added to this conversation
            if (recentContexts.contains(loreBook.name)) {
                continue
            }

            // Check if any NPC in the conversation has access to this knowledge
            val knowledgeAccessible = loreBook.categories.any { it in conversationKnowledgeCategories }
            if (!knowledgeAccessible) {
                continue // Skip if no NPC knows this category
            }

            for (keyword in loreBook.keywords) {
                if (messageLower.contains(keyword.lowercase())) {
                    if (addedLoreNames.add(loreBook.name)) { // Only add if not already added in this call
                        relevantContexts.add(LoreContext(loreBook.name, loreBook.context))
                        recentContexts.add(loreBook.name) // Mark as recently added
                    }
                    break // Only add each lorebook once even if multiple keywords match
                }
            }
        }

        // Schedule cleanup of this lorebook from recent contexts
        for (loreName in addedLoreNames) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, Runnable {
                recentlyAddedLoreContexts[conversation]?.remove(loreName)
            }, CONTEXT_COOLDOWN_MS / 50L) // Convert ms to ticks
        }

        return relevantContexts
    }

    // Function to save a new lore book
    fun saveLoreBook(loreBook: LoreBook) {
        val file = File(loreFolder, "${loreBook.name.replace(" ", "_")}.yml")
        val config = YamlConfiguration()

        config.set("name", loreBook.name)
        config.set("context", loreBook.context)
        config.set("keywords", loreBook.keywords)
        config.set("categories", ArrayList(loreBook.categories))

        try {
            config.save(file)
            loreBooks[loreBook.name.lowercase()] = loreBook
        } catch (e: Exception) {
            plugin.logger.warning("Error saving lorebook: ${loreBook.name}")
            e.printStackTrace()
        }
    }

    // Function to delete a lore book
    fun deleteLoreBook(name: String): Boolean {
        val loreName = name.lowercase()
        val file = File(loreFolder, "${name.replace(" ", "_")}.yml")

        return if (loreBooks.containsKey(loreName) && file.exists()) {
            loreBooks.remove(loreName)
            file.delete()
        } else {
            false
        }
    }

    // Function to get a lore book
    fun getLoreBook(name: String): LoreBook? {
        return loreBooks[name.lowercase()]
    }

    // Function to get all lore books
    fun getAllLoreBooks(): List<LoreBook> {
        return loreBooks.values.toList()
    }

    // Class to hold context information
    data class LoreContext(
        val loreName: String,
        val context: String
    )

    // Class definition for LoreBook
    data class LoreBook(
        val name: String,
        val context: String,
        val keywords: List<String>,
        val categories: Set<String> = setOf("common")
    )

    companion object {
        private var instance: LoreBookManager? = null

        @JvmStatic
        fun getInstance(plugin: Story): LoreBookManager {
            return instance ?: synchronized(this) {
                instance ?: LoreBookManager(plugin).also { instance = it }
            }
        }
    }
}