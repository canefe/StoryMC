package com.canefe.story.information

import com.canefe.story.Story
import com.canefe.story.storage.WorldEventStorage
import java.util.concurrent.ConcurrentHashMap

class WorldEventManager(
    private val plugin: Story,
    private var worldEventStorage: WorldEventStorage,
) {
    private val worldEvents: MutableMap<String, WorldEvent> = ConcurrentHashMap()

    fun updateStorage(storage: WorldEventStorage) {
        worldEventStorage = storage
    }

    fun loadAll() {
        worldEvents.clear()
        val loaded = worldEventStorage.loadAllWorldEvents()
        for (event in loaded) {
            worldEvents[event.id] = event
        }
        plugin.logger.info("Loaded ${worldEvents.size} world events")
    }

    fun addWorldEvent(event: WorldEvent) {
        worldEventStorage.saveWorldEvent(event)
        worldEvents[event.id] = event
    }

    fun getWorldEvent(id: String): WorldEvent? = worldEvents[id]

    fun getWorldEventsForLocation(location: String): List<WorldEvent> =
        worldEvents.values.filter { it.location.equals(location, ignoreCase = true) }

    fun getAllWorldEvents(): List<WorldEvent> = worldEvents.values.toList()

    fun deleteWorldEvent(id: String): Boolean {
        val deleted = worldEventStorage.deleteWorldEvent(id)
        if (deleted) {
            worldEvents.remove(id)
        }
        return deleted
    }
}
