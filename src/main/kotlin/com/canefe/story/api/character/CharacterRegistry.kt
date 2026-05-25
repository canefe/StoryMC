package com.canefe.story.api.character

import com.canefe.story.api.StoryNPC
import com.canefe.story.npc.util.NPCUtils
import com.canefe.story.storage.mongo.MongoCharacterStorage
import com.canefe.story.storage.mongo.MongoFrontendConfigStorage
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Central lookup service for character identity. Eager-loads all characters and
 * frontend configs from MongoDB on startup. Maintains index caches for fast lookups
 * by character ID, name, Minecraft UUID, Citizens UUID/ID, etc.
 */
class CharacterRegistry(
    private val characterStorage: MongoCharacterStorage,
    private val frontendConfigStorage: MongoFrontendConfigStorage,
    private val logger: Logger,
    private val mongoManager: com.canefe.story.storage.MongoClientManager? = null,
) {
    // Primary cache
    private val byId = ConcurrentHashMap<String, CharacterRecord>()
    private val byNameLower = ConcurrentHashMap<String, String>()

    // Minecraft frontend indexes (built from frontend_config)
    private val byMinecraftUuid = ConcurrentHashMap<UUID, String>()
    private val byCitizensUuid = ConcurrentHashMap<UUID, String>()
    private val byCitizensNpcId = ConcurrentHashMap<Int, String>()

    // Frontend config cache
    private val frontendConfigs = ConcurrentHashMap<String, FrontendConfig>() // key: "${characterId}:${frontend}"

    fun loadAll() {
        byId.clear()
        byNameLower.clear()
        byMinecraftUuid.clear()
        byCitizensUuid.clear()
        byCitizensNpcId.clear()
        frontendConfigs.clear()

        // Load characters
        for (record in characterStorage.findAll()) {
            byId[record.id] = record
            byNameLower[record.name.lowercase()] = record.id
        }

        // Load Minecraft frontend configs and build indexes
        for (config in frontendConfigStorage.findAllByFrontend(FrontendConfig.MINECRAFT)) {
            val key = "${config.characterId}:${config.frontend}"
            frontendConfigs[key] = config
            config.minecraftUuid?.let { byMinecraftUuid[it] = config.characterId }
            config.citizensUuid?.let { byCitizensUuid[it] = config.characterId }
            config.citizensNpcId?.let { byCitizensNpcId[it] = config.characterId }
        }

        logger.info("CharacterRegistry loaded ${byId.size} characters, ${frontendConfigs.size} frontend configs")
    }

    fun reload() = loadAll()

    // ── Core lookups ────────────────────────────────────────────────────

    fun getById(id: String): CharacterRecord? = byId[id]

    fun allIds(): Set<String> = byId.keys

    fun getByName(name: String): CharacterRecord? = byNameLower[name.lowercase()]?.let { byId[it] }

    fun getByPlayer(player: Player): CharacterRecord? = byMinecraftUuid[player.uniqueId]?.let { byId[it] }

    fun getByStoryNPC(npc: StoryNPC): CharacterRecord? =
        npc.characterId?.let { byId[it] }
            ?: byCitizensUuid[npc.uniqueId]?.let { byId[it] }
            ?: byCitizensNpcId[npc.id]?.let { byId[it] }
            ?: byId[npc.uniqueId.toString()]
            ?: byNameLower[npc.name.lowercase()]?.let { byId[it] }?.also {
                logger.warning(
                    "[CharacterRegistry] resolved NPC '${npc.name}' by NAME fallback " +
                        "— identity index miss, investigate (characterId=${npc.characterId})",
                )
            }

    // ── Convenience ─────────────────────────────────────────────────────

    fun getCharacterIdForPlayer(player: Player): String? = byMinecraftUuid[player.uniqueId]

    fun getCharacterIdForNPC(npc: StoryNPC): String? = getByStoryNPC(npc)?.id

    fun getDisplayName(characterId: String): String = byId[characterId]?.name ?: characterId

    fun isRegistered(player: Player): Boolean = byMinecraftUuid.containsKey(player.uniqueId)

    /**
     * Get the active character ID for a player on a specific frontend,
     * by looking up the players collection in MongoDB.
     * Falls back to FrontendConfig mapping if no player doc exists.
     */
    fun getActiveCharacterForPlayer(
        player: Player,
        frontend: String = "minecraft",
    ): String? {
        // Try players collection first (new unified identity)
        if (mongoManager != null) {
            try {
                val playerDoc =
                    mongoManager
                        .getCollection("players")
                        .find(org.bson.Document("frontends.$frontend.identifier", player.uniqueId.toString()))
                        .first()
                if (playerDoc != null) {
                    val activeChars = playerDoc.get("activeCharacters") as? org.bson.Document
                    val activeId = activeChars?.getString(frontend)
                    if (activeId != null) return activeId
                }
            } catch (_: Exception) {
                // Fall through to legacy lookup
            }
        }

        // Fallback: existing FrontendConfig mapping
        return getCharacterIdForPlayer(player)
    }

    // ── Frontend config ─────────────────────────────────────────────────

    fun getFrontendConfig(
        characterId: String,
        frontend: String,
    ): FrontendConfig? = frontendConfigs["$characterId:$frontend"]

    fun getMinecraftConfig(characterId: String): FrontendConfig? =
        getFrontendConfig(characterId, FrontendConfig.MINECRAFT)

    fun saveFrontendConfig(config: FrontendConfig) {
        frontendConfigStorage.save(config)
        val key = "${config.characterId}:${config.frontend}"
        frontendConfigs[key] = config

        // Update Minecraft indexes if applicable
        if (config.frontend == FrontendConfig.MINECRAFT) {
            config.minecraftUuid?.let { byMinecraftUuid[it] = config.characterId }
            config.citizensUuid?.let { byCitizensUuid[it] = config.characterId }
            config.citizensNpcId?.let { byCitizensNpcId[it] = config.characterId }
        }
    }

    // ── Mutation ────────────────────────────────────────────────────────

    fun register(record: CharacterRecord) {
        characterStorage.save(record)
        byId[record.id] = record
        byNameLower[record.name.lowercase()] = record.id
        logger.info("Registered character: ${record.id} (${record.name})")
    }

    fun unregister(characterId: String) {
        val record = byId.remove(characterId) ?: return
        byNameLower.remove(record.name.lowercase())

        // Clean up frontend indexes
        frontendConfigs.keys.filter { it.startsWith("$characterId:") }.forEach { key ->
            val config = frontendConfigs.remove(key)
            if (config?.frontend == FrontendConfig.MINECRAFT) {
                config.minecraftUuid?.let { byMinecraftUuid.remove(it) }
                config.citizensUuid?.let { byCitizensUuid.remove(it) }
                config.citizensNpcId?.let { byCitizensNpcId.remove(it) }
            }
        }

        frontendConfigStorage.deleteAllForCharacter(characterId)
        characterStorage.delete(characterId)
        logger.info("Unregistered character: $characterId")
    }

    // ── Bulk queries ────────────────────────────────────────────────────

    fun allNPCs(): List<CharacterRecord> = byId.values.filter { it.type == CharacterRecord.CharacterType.NPC }

    fun allPlayers(): List<CharacterRecord> = byId.values.filter { it.type == CharacterRecord.CharacterType.PLAYER }

    fun all(): List<CharacterRecord> = byId.values.toList()

    /**
     * Resolve the characterIds of every NPC and player within [radius] of [npc], plus the NPC itself.
     *
     * @param playerFilter optional predicate to drop players (e.g. chat-disabled ones)
     * @return Pair of (nearby NPC characterIds including self, nearby player characterIds)
     */
    fun getNearbyCharacterIds(
        npc: StoryNPC,
        radius: Double,
        playerFilter: (Player) -> Boolean = { true },
    ): Pair<List<String>, List<String>> {
        val npcIds =
            (NPCUtils.getNearbyNPCs(npc, radius) + npc)
                .mapNotNull { getCharacterIdForNPC(it) ?: it.name }

        val playerIds =
            NPCUtils.getNearbyPlayers(npc, radius)
                .filter(playerFilter)
                .mapNotNull { getCharacterIdForPlayer(it) }

        return npcIds to playerIds
    }

    /**
     * Player-centred overload of [getNearbyCharacterIds].
     * Self is included in the player list.
     */
    fun getNearbyCharacterIds(
        player: Player,
        radius: Double,
        playerFilter: (Player) -> Boolean = { true },
    ): Pair<List<String>, List<String>> {
        val npcIds =
            NPCUtils.getNearbyNPCs(player, radius)
                .mapNotNull { getCharacterIdForNPC(it) ?: it.name }

        val nearbyPlayers = NPCUtils.getNearbyPlayers(player, radius)
        val playerIds =
            (nearbyPlayers + player)
                .distinctBy { it.uniqueId }
                .filter(playerFilter)
                .mapNotNull { getCharacterIdForPlayer(it) }

        return npcIds to playerIds
    }
}
