@file:Suppress("DEPRECATION")

package com.canefe.story.storage.yaml

import com.canefe.story.information.WorldEvent
import com.canefe.story.storage.WorldEventStorage

@Deprecated("YAML storage is deprecated. Use MongoDB backend.")
class YamlWorldEventStorage : WorldEventStorage {
    override fun loadAllWorldEvents(): List<WorldEvent> = emptyList()

    override fun loadWorldEvent(id: String): WorldEvent? = null

    override fun loadWorldEventsByLocation(location: String): List<WorldEvent> = emptyList()

    override fun saveWorldEvent(event: WorldEvent) {}

    override fun deleteWorldEvent(id: String): Boolean = false
}
