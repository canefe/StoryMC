package com.canefe.story.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OfferItemSpec(
    val tag: String? = null,
    val item: String? = null,
    val qty: Int = 1,
)

@Serializable
data class OfferSpec(
    val id: String,
    val wants: List<OfferItemSpec> = emptyList(),
    val gives: List<OfferItemSpec> = emptyList(),
    @SerialName("while_situation") val whileSituation: String? = null,
)

@Serializable
data class CharDataItemStack(val item: String, val qty: Int = 1)

@Serializable
data class CharacterDataDocument(
    val id: String,
    val offers: List<OfferSpec> = emptyList(),
    val startingInventory: List<CharDataItemStack> = emptyList(),
    val traits: List<String> = emptyList(),
    val needValues: Map<String, Double> = emptyMap(),
    val statValues: Map<String, Double> = emptyMap(),
    val knownLocations: List<String> = emptyList(),
)
