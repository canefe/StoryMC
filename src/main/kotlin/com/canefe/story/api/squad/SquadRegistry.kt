package com.canefe.story.api.squad

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.storage.mongo.MongoSquadStorage
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * In-memory cache of [SquadRecord]s with secondary indices, backed by Mongo.
 *
 * Loaded fully at boot (squads are low-cardinality). All mutations write
 * through to Mongo synchronously to keep the cache and DB in lockstep.
 */
class SquadRegistry(
    private val storage: MongoSquadStorage,
    private val logger: Logger,
) {
    private val byId = ConcurrentHashMap<String, SquadRecord>()

    /** Owner character id -> set of squad ids. */
    private val byOwner = ConcurrentHashMap<String, MutableSet<String>>()

    /** Character id (owner OR shared-with) -> set of commandable squad ids. */
    private val byCommander = ConcurrentHashMap<String, MutableSet<String>>()

    /** Character id -> set of squad ids the character belongs to. */
    private val byMember = ConcurrentHashMap<String, MutableSet<String>>()

    fun loadAll() {
        clear()
        for (squad in storage.findAll()) {
            indexInto(squad)
        }
        logger.info("[SquadRegistry] Loaded ${byId.size} squads")
        migrateLegacyMembers()
    }

    /**
     * One-shot migration: any squad still holding NPC stableUniqueIds in
     * memberCharacterIds (legacy schema) gets translated to characterIds.
     * Safe to run on every load — no-op once all members are characterIds.
     */
    private fun migrateLegacyMembers() {
        val plugin = Story.instance
        if (!plugin.isCharacterRegistryReady || !plugin.isNpcRegistryReady) return

        val toMigrate = byId.values.filter { sq -> sq.memberCharacterIds.any { looksLikeUuid(it) } }
        if (toMigrate.isEmpty()) return

        logger.info("[SquadRegistry] Migrating ${toMigrate.size} squads from NPC-uuid to characterId members")
        for (sq in toMigrate) {
            val translated =
                sq.memberCharacterIds.mapNotNull { id ->
                    if (!looksLikeUuid(id)) return@mapNotNull id
                    val npcUuid = try { UUID.fromString(id) } catch (_: Exception) { return@mapNotNull null }
                    val npc = plugin.npcRegistry.get(npcUuid) ?: return@mapNotNull null
                    plugin.characterRegistry.getCharacterIdForNPC(npc)
                }
            update(sq.id) { it.copy(memberCharacterIds = translated.distinct()) }
        }
    }

    private fun looksLikeUuid(s: String): Boolean =
        s.length == 36 && s.count { it == '-' } == 4

    fun reload() = loadAll()

    private fun clear() {
        byId.clear()
        byOwner.clear()
        byCommander.clear()
        byMember.clear()
    }

    private fun indexInto(squad: SquadRecord) {
        byId[squad.id] = squad
        byOwner.getOrPut(squad.ownerCharacterId) { mutableSetOf() }.add(squad.id)
        byCommander.getOrPut(squad.ownerCharacterId) { mutableSetOf() }.add(squad.id)
        for (cid in squad.sharedWithCharacterIds) {
            byCommander.getOrPut(cid) { mutableSetOf() }.add(squad.id)
        }
        for (cid in squad.memberCharacterIds) {
            byMember.getOrPut(cid) { mutableSetOf() }.add(squad.id)
        }
    }

    private fun unindex(squad: SquadRecord) {
        byId.remove(squad.id)
        byOwner[squad.ownerCharacterId]?.remove(squad.id)
        byCommander[squad.ownerCharacterId]?.remove(squad.id)
        for (cid in squad.sharedWithCharacterIds) byCommander[cid]?.remove(squad.id)
        for (cid in squad.memberCharacterIds) byMember[cid]?.remove(squad.id)
    }

    // ── Lookup ──────────────────────────────────────────────────────────

    fun getById(id: String): SquadRecord? = byId[id]

    fun getByOwnerAndName(ownerCharacterId: String, name: String): SquadRecord? {
        val ids = byOwner[ownerCharacterId] ?: return null
        return ids.asSequence()
            .mapNotNull { byId[it] }
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    fun ownedBy(ownerCharacterId: String): List<SquadRecord> =
        byOwner[ownerCharacterId]?.mapNotNull { byId[it] } ?: emptyList()

    fun commandableBy(characterId: String): List<SquadRecord> =
        byCommander[characterId]?.mapNotNull { byId[it] } ?: emptyList()

    fun squadsContaining(characterId: String): List<SquadRecord> =
        byMember[characterId]?.mapNotNull { byId[it] } ?: emptyList()

    /**
     * Resolve a squad's roster to currently-live StoryNPCs in the world.
     * Members whose character has no live NPC right now (despawned, not loaded)
     * are silently skipped.
     */
    fun resolveLiveMembers(squad: SquadRecord): List<StoryNPC> {
        val plugin = Story.instance
        if (!plugin.isCharacterRegistryReady || !plugin.isNpcRegistryReady) return emptyList()
        return squad.memberCharacterIds.mapNotNull { charId ->
            plugin.npcRegistry.all().firstOrNull { npc ->
                plugin.characterRegistry.getCharacterIdForNPC(npc) == charId
            }
        }
    }

    fun all(): Collection<SquadRecord> = byId.values

    // ── Mutation (write-through to Mongo) ───────────────────────────────

    /**
     * Create a new squad. Throws if [ownerCharacterId] already owns a squad
     * with the same name (case-insensitive).
     */
    fun create(name: String, ownerCharacterId: String): SquadRecord {
        require(getByOwnerAndName(ownerCharacterId, name) == null) {
            "Owner $ownerCharacterId already has a squad named '$name'"
        }
        val record =
            SquadRecord(
                id = UUID.randomUUID().toString(),
                name = name,
                ownerCharacterId = ownerCharacterId,
            )
        storage.save(record)
        indexInto(record)
        return record
    }

    fun delete(id: String): Boolean {
        val existing = byId[id] ?: return false
        unindex(existing)
        storage.delete(id)
        return true
    }

    /**
     * Mutate a squad via [transform], persist to Mongo, re-index.
     * Returns the new record, or null if [id] doesn't exist.
     */
    private fun update(id: String, transform: (SquadRecord) -> SquadRecord): SquadRecord? {
        val current = byId[id] ?: return null
        val updated = transform(current).copy(updatedAt = Instant.now())
        unindex(current)
        storage.save(updated)
        indexInto(updated)
        return updated
    }

    fun addMember(id: String, characterId: String): SquadRecord? =
        update(id) { sq ->
            if (sq.memberCharacterIds.contains(characterId)) sq
            else sq.copy(memberCharacterIds = sq.memberCharacterIds + characterId)
        }

    fun removeMember(id: String, characterId: String): SquadRecord? =
        update(id) { sq ->
            sq.copy(memberCharacterIds = sq.memberCharacterIds - characterId)
        }

    fun share(id: String, characterId: String): SquadRecord? =
        update(id) { sq ->
            if (sq.sharedWithCharacterIds.contains(characterId)) sq
            else sq.copy(sharedWithCharacterIds = sq.sharedWithCharacterIds + characterId)
        }

    fun unshare(id: String, characterId: String): SquadRecord? =
        update(id) { sq ->
            sq.copy(sharedWithCharacterIds = sq.sharedWithCharacterIds - characterId)
        }

    fun transferOwnership(id: String, newOwnerCharacterId: String): SquadRecord? =
        update(id) { sq ->
            sq.copy(
                ownerCharacterId = newOwnerCharacterId,
                sharedWithCharacterIds = sq.sharedWithCharacterIds - newOwnerCharacterId,
            )
        }

    fun setFormation(id: String, formation: SquadFormation): SquadRecord? =
        update(id) { sq -> sq.copy(formation = formation) }
}
