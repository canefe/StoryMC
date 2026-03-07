package com.canefe.story.storage.mongo

import com.canefe.story.lore.LoreBookManager.LoreBook
import com.canefe.story.storage.LoreStorage
import com.canefe.story.storage.MongoClientManager
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document

class MongoLoreStorage(
    private val mongoClient: MongoClientManager,
) : LoreStorage {
    private val collection get() = mongoClient.getCollection("lore_books")

    override fun loadAllLoreBooks(): Map<String, LoreBook> {
        val loreBooks = mutableMapOf<String, LoreBook>()
        for (doc in collection.find()) {
            val name = doc.getString("name") ?: continue
            val context = doc.getString("context") ?: continue
            val keywords = doc.getList("keywords", String::class.java) ?: continue
            val categories = doc.getList("categories", String::class.java)?.toSet() ?: setOf("common")

            if (keywords.isNotEmpty()) {
                loreBooks[name.lowercase()] = LoreBook(name, context, keywords, categories)
            }
        }
        return loreBooks
    }

    override fun saveLoreBook(loreBook: LoreBook) {
        val doc =
            Document()
                .append("name", loreBook.name)
                .append("nameLower", loreBook.name.lowercase())
                .append("context", loreBook.context)
                .append("keywords", loreBook.keywords)
                .append("categories", ArrayList(loreBook.categories))

        collection.replaceOne(
            Filters.eq("nameLower", loreBook.name.lowercase()),
            doc,
            ReplaceOptions().upsert(true),
        )
    }

    override fun deleteLoreBook(name: String): Boolean {
        val result = collection.deleteOne(Filters.eq("nameLower", name.lowercase()))
        return result.deletedCount > 0
    }
}
