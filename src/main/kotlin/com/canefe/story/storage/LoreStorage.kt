package com.canefe.story.storage

import com.canefe.story.lore.LoreBookManager.LoreBook

interface LoreStorage {
    fun loadAllLoreBooks(): Map<String, LoreBook>

    fun saveLoreBook(loreBook: LoreBook)

    fun deleteLoreBook(name: String): Boolean
}
