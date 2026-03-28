package com.canefe.story.storage

import com.canefe.story.information.WorldEvent

interface WorldEventStorage {
    fun loadAllWorldEvents(): List<WorldEvent>

    fun loadWorldEvent(id: String): WorldEvent?

    fun loadWorldEventsByLocation(location: String): List<WorldEvent>

    fun saveWorldEvent(event: WorldEvent)

    fun deleteWorldEvent(id: String): Boolean
}
