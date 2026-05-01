package com.canefe.story.storage.migration

import com.canefe.story.storage.MongoClientManager
import org.bson.Document
import java.util.logging.Logger

/**
 * One-shot migration: rewrites legacy `characters.appearance` values that are
 * not objects (typically empty strings from the pre-structured-appearance era)
 * into an empty map `{}`, matching the current `CharacterRecord.appearance:
 * Map<String, String>` schema.
 *
 * Without this, `MongoCharacterStorage.deserialize` silently drops every
 * affected document and the rest of the system thinks those characters don't
 * exist.
 */
class AppearanceFieldMigration(
    private val mongo: MongoClientManager,
    private val logger: Logger,
) {
    data class Result(val scanned: Int, val rewritten: Int)

    fun run(): Result {
        val col = mongo.getCollection("characters")
        var scanned = 0
        var rewritten = 0

        for (doc in col.find()) {
            scanned++
            val appearance = doc["appearance"]
            // Object → already correct. Anything else (String "", null, missing,
            // wrong primitive) needs to become an empty map.
            if (appearance is Document) continue

            val id = doc.getString("_id") ?: continue
            col.updateOne(
                Document("_id", id),
                Document("\$set", Document("appearance", Document())),
            )
            rewritten++
        }

        logger.info("[AppearanceFieldMigration] scanned=$scanned rewritten=$rewritten")
        return Result(scanned, rewritten)
    }
}
