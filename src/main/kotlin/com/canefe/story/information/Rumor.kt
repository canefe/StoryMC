package com.canefe.story.information

import com.canefe.story.storage.mongo.InstantEpochMilliSerializer
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class Rumor(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    @Serializable(with = InstantEpochMilliSerializer::class)
    val realCreatedAt: Instant = Instant.now(),
    val gameCreatedAt: Long = 0,
    val location: String? = null,
    val significance: Double = 1.0,
)
