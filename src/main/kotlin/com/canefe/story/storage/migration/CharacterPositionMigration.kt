package com.canefe.story.storage.migration

import com.canefe.story.api.character.CharacterRegistry
import com.canefe.story.api.character.FrontendConfig
import com.canefe.story.npc.CitizensStoryNPC
import com.canefe.story.storage.MongoClientManager
import com.mongodb.client.model.UpdateOptions
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.trait.SkinTrait
import org.bson.Document
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import java.util.logging.Logger

/**
 * One-shot migration: scans the Citizens NPC registry, resolves each NPC to a
 * character via [CharacterRegistry], and writes:
 *  - an entry in `character_positions` (matching story-go's position.Position
 *    schema: `_id` + `x/y/z/world/updatedAt`).
 *  - a `properties.skin = { textureRaw, signature }` field on the matching
 *    `frontend_config` doc (frontend = "minecraft").
 *
 * Existing position rows are NOT overwritten (sim is authoritative once it has
 * one). Skin field is overwritten on each run since Citizens is the source of
 * truth for current visual identity.
 */
class CharacterPositionMigration(
    private val mongo: MongoClientManager,
    private val characterRegistry: CharacterRegistry,
    private val logger: Logger,
) {
    data class Result(
        val scanned: Int,
        val positionsInserted: Int,
        val positionsSkippedExisting: Int,
        val positionsSkippedNotSpawned: Int,
        val skinsWritten: Int,
        val skinsSkippedNoData: Int,
        val skinsSkippedNoConfig: Int,
        val skippedNoCharacter: Int,
    )

    fun run(): Result {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            logger.warning("[CharacterPositionMigration] Citizens not enabled — nothing to migrate")
            return Result(0, 0, 0, 0, 0, 0, 0, 0)
        }

        val positions = mongo.getCollection("character_positions")
        val frontendConfigs = mongo.getCollection("frontend_config")

        var scanned = 0
        var positionsInserted = 0
        var positionsSkippedExisting = 0
        var positionsSkippedNotSpawned = 0
        var skinsWritten = 0
        var skinsSkippedNoData = 0
        var skinsSkippedNoConfig = 0
        var skippedNoCharacter = 0
        val now = System.currentTimeMillis()

        for (citizensNpc in CitizensAPI.getNPCRegistry()) {
            scanned++
            val storyNpc = CitizensStoryNPC(citizensNpc)
            val charId = characterRegistry.getCharacterIdForNPC(storyNpc)
            if (charId == null) {
                skippedNoCharacter++
                continue
            }

            // ── Position ────────────────────────────────────────────────
            val entity = citizensNpc.entity as? LivingEntity
            val loc = entity?.location ?: citizensNpc.storedLocation
            val world = loc?.world?.name
            if (loc == null || world == null) {
                positionsSkippedNotSpawned++
            } else {
                val existing = positions.find(Document("_id", charId)).first()
                if (existing != null) {
                    positionsSkippedExisting++
                } else {
                    val doc = Document()
                        .append("_id", charId)
                        .append("x", loc.x)
                        .append("y", loc.y)
                        .append("z", loc.z)
                        .append("world", world)
                        .append("updatedAt", now)
                    positions.updateOne(
                        Document("_id", charId),
                        Document("\$setOnInsert", doc),
                        UpdateOptions().upsert(true),
                    )
                    positionsInserted++
                }
            }

            // ── Skin ────────────────────────────────────────────────────
            val skinTrait = citizensNpc.getTraitNullable(SkinTrait::class.java)
            val textureRaw = skinTrait?.texture
            val signature = skinTrait?.signature
            if (textureRaw.isNullOrBlank() || signature.isNullOrBlank()) {
                skinsSkippedNoData++
            } else {
                val skinDoc = Document()
                    .append("textureRaw", textureRaw)
                    .append("signature", signature)
                val res = frontendConfigs.updateOne(
                    Document("characterId", charId).append("frontend", FrontendConfig.MINECRAFT),
                    Document("\$set", Document("properties.skin", skinDoc)),
                )
                if (res.matchedCount == 0L) {
                    skinsSkippedNoConfig++
                } else {
                    skinsWritten++
                }
            }
        }

        logger.info(
            "[CharacterPositionMigration] scanned=$scanned " +
                "positions: inserted=$positionsInserted skippedExisting=$positionsSkippedExisting " +
                "skippedNotSpawned=$positionsSkippedNotSpawned | " +
                "skins: written=$skinsWritten skippedNoData=$skinsSkippedNoData " +
                "skippedNoConfig=$skinsSkippedNoConfig | skippedNoCharacter=$skippedNoCharacter",
        )
        return Result(
            scanned = scanned,
            positionsInserted = positionsInserted,
            positionsSkippedExisting = positionsSkippedExisting,
            positionsSkippedNotSpawned = positionsSkippedNotSpawned,
            skinsWritten = skinsWritten,
            skinsSkippedNoData = skinsSkippedNoData,
            skinsSkippedNoConfig = skinsSkippedNoConfig,
            skippedNoCharacter = skippedNoCharacter,
        )
    }
}
