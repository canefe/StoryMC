package com.canefe.story.storage.sqlite

import com.canefe.story.lore.LoreBookManager.LoreBook
import com.canefe.story.storage.LoreStorage
import com.canefe.story.storage.SQLiteManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SQLiteLoreStorage(
    private val sqlite: SQLiteManager,
) : LoreStorage {
    private val gson = Gson()

    override fun loadAllLoreBooks(): Map<String, LoreBook> {
        val result = mutableMapOf<String, LoreBook>()
        val conn = sqlite.getConnection()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT * FROM lore_books")
            while (rs.next()) {
                val name = rs.getString("name")
                val context = rs.getString("context") ?: ""

                val keywordsJson = rs.getString("keywords") ?: "[]"
                val keywords: List<String> = gson.fromJson(keywordsJson, object : TypeToken<List<String>>() {}.type)

                val categoriesJson = rs.getString("categories") ?: "[]"
                val categories: Set<String> = gson.fromJson(categoriesJson, object : TypeToken<Set<String>>() {}.type)

                val loreBook =
                    LoreBook(
                        name = name,
                        context = context,
                        keywords = keywords,
                        categories = categories,
                    )
                result[name.lowercase()] = loreBook
            }
        }
        return result
    }

    override fun saveLoreBook(loreBook: LoreBook) {
        val conn = sqlite.getConnection()
        conn
            .prepareStatement(
                "REPLACE INTO lore_books (name_lower, name, context, keywords, categories) VALUES (?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, loreBook.name.lowercase())
                stmt.setString(2, loreBook.name)
                stmt.setString(3, loreBook.context)
                stmt.setString(4, gson.toJson(loreBook.keywords))
                stmt.setString(5, gson.toJson(loreBook.categories))
                stmt.executeUpdate()
            }
    }

    override fun deleteLoreBook(name: String): Boolean {
        val conn = sqlite.getConnection()
        conn.prepareStatement("DELETE FROM lore_books WHERE name_lower = ?").use { stmt ->
            stmt.setString(1, name.lowercase())
            return stmt.executeUpdate() > 0
        }
    }
}
