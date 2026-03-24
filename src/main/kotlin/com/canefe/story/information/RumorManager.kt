package com.canefe.story.information

import com.canefe.story.Story
import com.canefe.story.storage.RumorStorage
import java.util.concurrent.ConcurrentHashMap

class RumorManager(
    private val plugin: Story,
    private var rumorStorage: RumorStorage,
) {
    private val rumors: MutableMap<String, Rumor> = ConcurrentHashMap()

    fun updateStorage(storage: RumorStorage) {
        rumorStorage = storage
    }

    fun loadAll() {
        rumors.clear()
        val loaded = rumorStorage.loadAllRumors()
        for (rumor in loaded) {
            rumors[rumor.id] = rumor
        }
        plugin.logger.info("Loaded ${rumors.size} rumors")
    }

    fun addRumor(rumor: Rumor) {
        rumorStorage.saveRumor(rumor)
        rumors[rumor.id] = rumor
    }

    fun getRumor(id: String): Rumor? = rumors[id]

    fun getRumorsForLocation(location: String): List<Rumor> =
        rumors.values.filter { it.location.equals(location, ignoreCase = true) }

    fun getAllRumors(): List<Rumor> = rumors.values.toList()

    fun deleteRumor(id: String): Boolean {
        val deleted = rumorStorage.deleteRumor(id)
        if (deleted) {
            rumors.remove(id)
        }
        return deleted
    }
}
