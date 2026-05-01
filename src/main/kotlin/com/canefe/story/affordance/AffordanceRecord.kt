package com.canefe.story.affordance

data class MinecraftAffordanceConfig(
    val blockType: String,
)

data class AffordanceRecord(
    val id: String,                          // MongoDB _id
    val affordanceTypeId: String,            // e.g. "water_source"
    val name: String,                        // display name
    val x: Double,
    val y: Double,
    val z: Double,
    val world: String,
    val frontend: Map<String, Any> = emptyMap(),
)
