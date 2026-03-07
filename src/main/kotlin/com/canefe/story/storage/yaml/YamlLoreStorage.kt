@file:Suppress("DEPRECATION")

package com.canefe.story.storage.yaml

import com.canefe.story.lore.LoreBookManager.LoreBook
import com.canefe.story.storage.LoreStorage
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.logging.Logger

@Deprecated("YAML storage is deprecated and has known bugs. Use MongoDB backend.")
class YamlLoreStorage(
    private val loreFolder: File,
    private val logger: Logger,
) : LoreStorage {
    init {
        if (!loreFolder.exists()) loreFolder.mkdirs()
    }

    override fun loadAllLoreBooks(): Map<String, LoreBook> {
        val loreBooks = mutableMapOf<String, LoreBook>()
        val files = loreFolder.listFiles { _, name -> name.endsWith(".yml") } ?: return loreBooks

        for (file in files) {
            try {
                val config = YamlConfiguration.loadConfiguration(file)
                val name = config.getString("name") ?: continue
                val context = config.getString("context") ?: continue
                val keywords = config.getStringList("keywords")
                val categoryList = config.getStringList("categories")
                val categories = if (categoryList.isEmpty()) setOf("common") else categoryList.toSet()

                if (keywords.isNotEmpty()) {
                    loreBooks[name.lowercase()] = LoreBook(name, context, keywords, categories)
                }
            } catch (e: Exception) {
                logger.warning("Error loading lorebook from file: ${file.name}")
            }
        }
        return loreBooks
    }

    override fun saveLoreBook(loreBook: LoreBook) {
        val file = File(loreFolder, "${loreBook.name.replace(" ", "_")}.yml")
        val config = YamlConfiguration()

        config.set("name", loreBook.name)
        config.set("context", loreBook.context)
        config.set("keywords", loreBook.keywords)
        config.set("categories", ArrayList(loreBook.categories))

        try {
            config.save(file)
        } catch (e: Exception) {
            logger.warning("Error saving lorebook: ${loreBook.name}")
        }
    }

    override fun deleteLoreBook(name: String): Boolean {
        val file = File(loreFolder, "${name.replace(" ", "_")}.yml")
        return if (file.exists()) file.delete() else false
    }
}
