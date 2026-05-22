package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.bridge.NPCPerceptEvent
import com.canefe.story.intelligence.BridgeIntelligence
import com.canefe.story.util.characterId
import com.canefe.story.util.characterName
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Periodically pushes a "nearby characters" info bundle to each online player so
 * StoryClient can render the look-at action wheel and Helix-style nametags
 * without an extra roundtrip.
 *
 * Channel: `story:nearby_npcs` (wire version 3)
 *
 * Wire format (big-endian):
 *   short  count
 *   for each entry:
 *     byte  entityType        (0 = npc, 1 = player)
 *     long  clientFacingUuid.mostSig
 *     long  clientFacingUuid.leastSig
 *     UTF   displayLabel      (real name if perceiver knows them, else short label)
 *     UTF   characterId       (empty string if unmapped)
 *     UTF   descriptor        (full appearance prose / fallback label; "" if none)
 *     UTF   shortLabel        (compact "Black-haired Man"-style label for chat/wheel)
 *     UTF   realName          (DM-only: server-known NPC/player name. Empty
 *                              string for non-DM perceivers — never sent.)
 *     short hp                 (DM-only: rounded current HP; -1 for non-DMs)
 *     short maxHp              (DM-only: rounded max HP;     -1 for non-DMs)
 *     bool  canSpeakAs        (DM-only flag for this player; players: always false)
 *     bool  isFollowing       (NPC follow state; players: false)
 *
 * Nametag rule (client): top line = realName when known, else descriptor.
 * If known, second line = descriptor. If not known, only top line is shown.
 */
class NearbyNPCBroadcaster(
    private val plugin: Story,
) {
    private val channelId = "story:nearby_npcs"

    /**
     * Broad-enough to cover render distance for nametags, not just interaction
     * distance for the wheel. Vanilla nametag draw distance is about 64m for
     * non-sneaking players; 64m is a clean upper bound.
     */
    private val radius = 64.0

    /** Cache of last-sent bundle hash per player to avoid resending unchanged data. */
    private val lastSentHash = mutableMapOf<UUID, Int>()

    /** Per-NPC: set of player characterIds currently in proximity. Used to fire percepts only on enter/leave. */
    private val npcProximityState = mutableMapOf<UUID, Set<String>>()

    private var taskId: Int = -1

    fun start() {
        if (taskId != -1) return
        // Tick every 2 seconds (40 ticks).
        taskId =
            Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin,
                Runnable { broadcastAll() },
                40L,
                40L,
            )
    }

    fun stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId)
            taskId = -1
        }
        lastSentHash.clear()
        npcProximityState.clear()
    }

    /** Drop the cached hash for [player] so the next tick forces a resend. */
    fun invalidatePlayer(player: Player) {
        lastSentHash.remove(player.uniqueId)
    }

    private fun broadcastAll() {
        if (!plugin.isNpcRegistryReady) return
        if (!plugin.isCharacterRegistryReady) return
        for (player in Bukkit.getOnlinePlayers()) {
            broadcastTo(player)
        }
        // When the sim is active it owns NPC perception — skip duplicate percepts here
        if (!plugin.simActive) emitProximityPercepts()
    }

    /**
     * Emits [NPCPerceptEvent] only when a character (player with bound character, or
     * Story NPC with a character ID) enters or leaves an NPC's proximity.
     * No spam — one event on change, not every tick.
     */
    private fun emitProximityPercepts() {
        if (!plugin.isCharacterRegistryReady) return
        for (npc in plugin.npcRegistry.all()) {
            val loc = npc.location ?: continue
            val charId = plugin.characterRegistry.getCharacterIdForNPC(npc) ?: continue

            // Collect all nearby character IDs — players with bound characters + other Story NPCs
            val nearbyCharacters = mutableMapOf<String, String>() // characterId → displayName

            Bukkit.getOnlinePlayers()
                .filter { it.world == loc.world && it.location.distanceSquared(loc) <= radius * radius }
                .forEach { p ->
                    val pid = try { p.characterId } catch (_: Exception) { null } ?: return@forEach
                    val name = try { p.characterName } catch (_: Exception) { p.name }
                    nearbyCharacters[pid] = name
                }

            plugin.npcRegistry.nearby(loc, radius)
                .filter { it.uniqueId != npc.uniqueId }
                .forEach { other ->
                    val oid = plugin.characterRegistry.getCharacterIdForNPC(other) ?: return@forEach
                    nearbyCharacters[oid] = other.name
                }

            val currentIds = nearbyCharacters.keys.toSet()
            val previous = npcProximityState[npc.uniqueId] ?: emptySet()
            val entered = currentIds - previous
            val left = previous - currentIds

            entered.forEach { cid ->
                plugin.eventBus.emit(NPCPerceptEvent(
                    characterId = charId,
                    characterName = npc.name,
                    perceptType = "character_entered_range",
                    triggerName = nearbyCharacters[cid] ?: cid,
                ))
            }
            left.forEach { cid ->
                plugin.eventBus.emit(NPCPerceptEvent(
                    characterId = charId,
                    characterName = npc.name,
                    perceptType = "character_left_range",
                    triggerName = cid,
                ))
            }

            npcProximityState[npc.uniqueId] = currentIds
        }
    }

    private fun broadcastTo(player: Player) {
        val canSpeakAs = player.hasPermission("story.dm")
        // DMs always receive real names. Whether to display them is a
        // client-side preference (StoryClientConfig.dmRevealRealNames); the
        // server treats real-name visibility as a permission, not a toggle.
        val revealRealNames = canSpeakAs
        val nearbyNpcs = plugin.npcRegistry.nearby(player.location, radius)
        val nearbyPlayers =
            Bukkit.getOnlinePlayers()
                .filter {
                    it.uniqueId != player.uniqueId &&
                        it.world == player.world &&
                        it.location.distanceSquared(player.location) <= radius * radius
                }

        // Raw entries (real names) — used as the fallback when recognition is
        // unavailable, and as the source for the resolve batch when it is.
        val rawEntries = buildList {
            nearbyNpcs.forEach { npc ->
                val uuid = npc.clientFacingUuid ?: return@forEach
                val charId = plugin.characterRegistry.getCharacterIdForNPC(npc) ?: ""
                // Round to nearest 5 to keep the broadcaster's hash-dedupe useful;
                // 1-HP-tick churn would otherwise re-send the whole bundle constantly.
                val hp =
                    if (canSpeakAs) {
                        ((npc.entity as? LivingEntity)?.health?.toInt() ?: -1).let { if (it < 0) -1 else (it / 5) * 5 }
                    } else {
                        -1
                    }
                val maxHp =
                    if (canSpeakAs) {
                        (npc.entity as? LivingEntity)
                            ?.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH)
                            ?.value
                            ?.toInt() ?: -1
                    } else {
                        -1
                    }
                add(
                    Entry(
                        entityType = TYPE_NPC,
                        uuid = uuid,
                        displayLabel = npc.name,
                        characterId = charId,
                        descriptor = "",
                        shortLabel = "",
                        realName = if (revealRealNames) npc.name else "",
                        hp = hp,
                        maxHp = maxHp,
                        canSpeakAs = canSpeakAs,
                        isFollowing = npc.isFollowing,
                    ),
                )
            }
            nearbyPlayers.forEach { p ->
                // Players without a bound character are still useful for nametag
                // suppression on the client; we send them with empty characterId.
                val charId =
                    if (plugin.isCharacterRegistryReady) (p.characterId ?: "") else ""
                val hp = if (canSpeakAs) (p.health.toInt() / 5) * 5 else -1
                val maxHp =
                    if (canSpeakAs) {
                        p.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH)?.value?.toInt() ?: -1
                    } else {
                        -1
                    }
                val playerCharName = try { p.characterName } catch (_: Exception) { p.name }
                add(
                    Entry(
                        entityType = TYPE_PLAYER,
                        uuid = p.uniqueId,
                        displayLabel = playerCharName,
                        characterId = charId,
                        descriptor = "",
                        shortLabel = "",
                        realName = if (revealRealNames) playerCharName else "",
                        hp = hp,
                        maxHp = maxHp,
                        canSpeakAs = false,
                        isFollowing = false,
                    ),
                )
            }
        }

        val perceiverId = player.characterId
        val bridge = if (plugin.isIntelligenceReady) plugin.intelligence as? BridgeIntelligence else null

        if (perceiverId == null || bridge == null || !bridge.isRecognitionSupported()) {
            sendEntries(player, rawEntries)
            return
        }

        val targetIds = rawEntries.map { it.characterId }.filter { it.isNotEmpty() }.distinct()
        if (targetIds.isEmpty()) {
            sendEntries(player, rawEntries)
            return
        }

        bridge.resolveNames(perceiverId, targetIds)
            .thenAccept { resolved ->
                val byId = resolved.associateBy { it.targetId }
                val rewritten = rawEntries.map { e ->
                    val r = byId[e.characterId] ?: return@map e
                    val short = r.shortLabel.ifBlank { r.descriptor }
                    val label = if (r.known && !r.realName.isNullOrBlank()) r.realName else short
                    e.copy(displayLabel = label, descriptor = r.descriptor, shortLabel = short)
                }
                Bukkit.getScheduler().runTask(
                    plugin,
                    Runnable { sendEntries(player, rewritten) },
                )
            }
            .exceptionally { e ->
                plugin.logger.warning("[NearbyNPCBroadcaster] resolveNames failed for ${player.name}: ${e.message}")
                Bukkit.getScheduler().runTask(
                    plugin,
                    Runnable { sendEntries(player, rawEntries) },
                )
                null
            }
    }

    private fun sendEntries(player: Player, entries: List<Entry>) {
        val hash = entries.hashCode()
        if (lastSentHash[player.uniqueId] == hash) return
        lastSentHash[player.uniqueId] = hash

        val bytes = encode(entries)
        val packet = WrapperPlayServerPluginMessage(channelId, bytes)
        try {
            PacketEvents.getAPI().playerManager.getUser(player).sendPacket(packet)
        } catch (e: Exception) {
            plugin.logger.warning("[NearbyNPCBroadcaster] send failed for ${player.name}: ${e.message}")
        }
    }

    private fun encode(entries: List<Entry>): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeShort(entries.size)
            for (e in entries) {
                out.writeByte(e.entityType.toInt())
                out.writeLong(e.uuid.mostSignificantBits)
                out.writeLong(e.uuid.leastSignificantBits)
                out.writeUTF(e.displayLabel)
                out.writeUTF(e.characterId)
                out.writeUTF(e.descriptor)
                out.writeUTF(e.shortLabel)
                out.writeUTF(e.realName)
                out.writeShort(e.hp)
                out.writeShort(e.maxHp)
                out.writeBoolean(e.canSpeakAs)
                out.writeBoolean(e.isFollowing)
            }
        }
        return baos.toByteArray()
    }

    private data class Entry(
        val entityType: Byte,
        val uuid: UUID,
        val displayLabel: String,
        val characterId: String,
        val descriptor: String,
        val shortLabel: String,
        val realName: String,
        /** DM-only: rounded current HP. -1 if perceiver lacks DM permission. */
        val hp: Int,
        /** DM-only: rounded max HP. -1 if perceiver lacks DM permission. */
        val maxHp: Int,
        val canSpeakAs: Boolean,
        val isFollowing: Boolean,
    )

    private companion object {
        const val TYPE_NPC: Byte = 0
        const val TYPE_PLAYER: Byte = 1
    }
}
