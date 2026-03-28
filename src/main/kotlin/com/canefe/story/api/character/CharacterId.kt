package com.canefe.story.api.character

import java.util.UUID

/**
 * Generates human-readable character IDs in the format: `thorne_mossveil_a3f2b1c4`
 * (full name slugified + 8 char UUID suffix).
 */
object CharacterId {
    fun generate(name: String): String {
        val slug =
            name
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
        val suffix =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(8)
        return "${slug}_$suffix"
    }
}
