package com.canefe.story.storage

import kotlinx.serialization.Serializable

interface LocationStorage {
    fun loadAllLocations(): Map<String, LocationDocument>

    fun loadLocation(name: String): LocationDocument?

    fun saveLocation(location: LocationDocument)

    fun deleteLocation(name: String)
}

@Serializable
data class LocationDocument(
    val name: String,
    val description: String = "",
    val parentLocationName: String? = null,
    val world: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val allowedNPCs: List<String> = emptyList(),
    val hideTitle: Boolean = false,
    val randomPathingAction: String? = null,
)
