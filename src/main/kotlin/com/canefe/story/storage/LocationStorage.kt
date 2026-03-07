package com.canefe.story.storage

interface LocationStorage {
    fun loadAllLocations(): Map<String, LocationDocument>

    fun loadLocation(name: String): LocationDocument?

    fun saveLocation(location: LocationDocument)

    fun deleteLocation(name: String)
}

data class LocationDocument(
    val name: String,
    val context: List<String>,
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
