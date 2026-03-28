@file:Suppress("DEPRECATION")

package com.canefe.story.storage.yaml

import com.canefe.story.information.Rumor
import com.canefe.story.storage.RumorStorage

@Deprecated("YAML storage is deprecated. Use MongoDB backend.")
class YamlRumorStorage : RumorStorage {
    override fun loadAllRumors(): List<Rumor> = emptyList()

    override fun loadRumor(id: String): Rumor? = null

    override fun loadRumorsByLocation(location: String): List<Rumor> = emptyList()

    override fun saveRumor(rumor: Rumor) {}

    override fun deleteRumor(id: String): Boolean = false
}
