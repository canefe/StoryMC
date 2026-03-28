package com.canefe.story.storage

import com.canefe.story.information.Rumor

interface RumorStorage {
    fun loadAllRumors(): List<Rumor>

    fun loadRumor(id: String): Rumor?

    fun loadRumorsByLocation(location: String): List<Rumor>

    fun saveRumor(rumor: Rumor)

    fun deleteRumor(id: String): Boolean
}
