package com.canefe.story.command.story.npc

import com.canefe.story.storage.OfferItemSpec

/**
 * Parse "tag:currency:3" or "item:wheat:1" into an [OfferItemSpec].
 * Throws [IllegalArgumentException] on bad input.
 */
fun parseOfferToken(token: String): OfferItemSpec {
    val parts = token.split(":")
    require(parts.size == 3) { "Expected kind:id:qty, got '$token'" }
    val (kind, id, qtyStr) = parts
    val qty = qtyStr.toIntOrNull() ?: throw IllegalArgumentException("Bad qty in '$token'")
    return when (kind) {
        "tag"  -> OfferItemSpec(tag = id, qty = qty)
        "item" -> OfferItemSpec(item = id, qty = qty)
        else   -> throw IllegalArgumentException("Unknown spec kind '$kind' in '$token'")
    }
}
