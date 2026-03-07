package com.canefe.story.lore

import com.canefe.story.Story
import com.canefe.story.conversation.Conversation
import com.canefe.story.storage.LoreStorage
import org.bukkit.Bukkit
import java.util.concurrent.ConcurrentHashMap

class LoreBookManager(
    private val plugin: Story,
    private var loreStorage: LoreStorage,
) {
    fun updateStorage(storage: LoreStorage) {
        loreStorage = storage
    }

    private val loreBooks: MutableMap<String, LoreBook> = HashMap()

    // Track recently added lore contexts by conversation to avoid duplicates
    private val recentlyAddedLoreContexts: MutableMap<Conversation, MutableSet<String>> = ConcurrentHashMap()
    private val npcKnowledgeCategories: MutableMap<String, Set<String>> = HashMap()
    private val contextCooldown = 60000 // 1 minute cooldown

    init {
        loadConfig()

        // Schedule cleanup of old context entries
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::cleanupOldContextEntries, 1200L, 1200L)
    }

    fun loadConfig() {
        loadAllLoreBooks()
        loadNPCKnowledgeCategories()
    }

    private fun cleanupOldContextEntries() {
        val iterator = recentlyAddedLoreContexts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.key.active || entry.value.isEmpty()) {
                iterator.remove()
            }
        }
    }

    // Method to load NPC knowledge categories - now just clears the cache
    fun loadNPCKnowledgeCategories() {
        // Clear the cache but don't preload anything
        npcKnowledgeCategories.clear()
    }

    // Helper method to get categories for an NPC when needed
    private fun getNPCKnowledgeCategories(npcName: String): Set<String> =
        npcKnowledgeCategories.getOrPut(npcName.lowercase()) {
            val categories = mutableSetOf("common")
            val npcData = plugin.npcDataManager.getNPCData(npcName)
            if (npcData != null) {
                categories.addAll(npcData.knowledgeCategories)
            }
            categories
        }

    // Add this method to LoreBookManager class
    fun findLoresByKeywords(text: String): List<LoreContext> {
        val relevantContexts = mutableListOf<LoreContext>()
        val textLower = text.lowercase()
        val addedLoreNames = HashSet<String>() // Track lorebooks added in this call

        for (loreBook in loreBooks.values) {
            for (keyword in loreBook.keywords) {
                if (textLower.contains(keyword.lowercase())) {
                    if (addedLoreNames.add(loreBook.name)) { // Only add if not already added
                        relevantContexts.add(LoreContext(loreBook.name, loreBook.context))
                    }
                    break // Only add each lorebook once even if multiple keywords match
                }
            }
        }

        return relevantContexts
    }

    fun loadAllLoreBooks() {
        loreBooks.clear()
        val loaded = loreStorage.loadAllLoreBooks()
        loreBooks.putAll(loaded)
        plugin.logger.info("Loaded ${loreBooks.size} lorebooks")
    }

    fun findRelevantLoreContexts(
        message: String,
        conversation: Conversation,
    ): List<LoreContext> {
        val relevantContexts = mutableListOf<LoreContext>()
        val messageLower = message.lowercase()
        val addedLoreNames = HashSet<String>() // Track lorebooks added in this call

        // Initialize the set for this conversation if needed
        val recentContexts = recentlyAddedLoreContexts.getOrPut(conversation) { HashSet() }

        // Get all knowledge categories accessible in this conversation
        val conversationKnowledgeCategories = mutableSetOf("common") // Everyone knows common knowledge

        for (npcName in conversation.npcNames) {
            conversationKnowledgeCategories.addAll(getNPCKnowledgeCategories(npcName))
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
            Bukkit.getScheduler().runTaskLaterAsynchronously(
                plugin,
                Runnable {
                    recentlyAddedLoreContexts[conversation]?.remove(loreName)
                },
                contextCooldown / 50L,
            ) // Convert ms to ticks
        }

        return relevantContexts
    }

    fun saveLoreBook(loreBook: LoreBook) {
        try {
            loreStorage.saveLoreBook(loreBook)
            loreBooks[loreBook.name.lowercase()] = loreBook
        } catch (e: Exception) {
            plugin.logger.warning("Error saving lorebook: ${loreBook.name}")
            e.printStackTrace()
        }
    }

    fun deleteLoreBook(name: String): Boolean {
        val loreName = name.lowercase()
        if (!loreBooks.containsKey(loreName)) return false

        val deleted = loreStorage.deleteLoreBook(name)
        if (deleted) {
            loreBooks.remove(loreName)
        }
        return deleted
    }

    // Function to get a lore book
    fun getLoreBook(name: String): LoreBook? = loreBooks[name.lowercase()]

    // Function to get all lore books
    fun getAllLoreBooks(): List<LoreBook> = loreBooks.values.toList()

    // Class to hold context information
    data class LoreContext(
        val loreName: String,
        val context: String,
    )

    // Class definition for LoreBook
    data class LoreBook(
        val name: String,
        val context: String,
        val keywords: List<String>,
        val categories: Set<String> = setOf("common"),
    )
}
