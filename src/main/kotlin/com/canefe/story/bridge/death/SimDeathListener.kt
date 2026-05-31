package com.canefe.story.bridge.death

import com.canefe.story.Story
import com.canefe.story.bridge.EntityDiedEvent
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity

/**
 * Force-kills the MC entity backing a sim character when the sim reports the
 * entity has died. Covers non-combat deaths (starvation, dehydration, hediff
 * cascade) — combat kills already route through
 * [com.canefe.story.bridge.combat.SimAuthoritativeDamageListener] via
 * `combat.attack_resolved.killed=true`.
 *
 * Resolution: characterId → CharacterRegistry → display name → StoryNPCRegistry
 * → Bukkit entity. Setting `health = 0.0` runs the vanilla death sequence,
 * which triggers MythicMobDeathEvent; the existing cleanup in
 * MythicMobNPCFactory then deregisters the StoryNPC.
 *
 * For online players backing a sim character we currently skip — killing a
 * player from a sim event is a policy decision (respawn screen, death message,
 * statistic) that combat already owns through normal damage flow. If a player
 * actually starves in the sim today the player is unaffected; if/when that
 * needs to mirror, lift the player branch from SimAuthoritativeDamageListener.
 */
class SimDeathListener(
    private val plugin: Story,
) {
    init {
        plugin.eventBus.on<EntityDiedEvent> { handleDied(it) }
    }

    private fun handleDied(ev: EntityDiedEvent) {
        val entity = resolveLivingEntity(ev.characterId, ev.name)
        if (entity == null) {
            // No tracked NPC for this character — nothing to kill MC-side. The
            // sim may have killed an unspawned/distant entity, or this is a
            // player not currently mirrored. Quiet log so it's debuggable
            // without spamming on the dehydration mass-death case.
            plugin.logger.fine(
                "[death] sim killed ${ev.name} (${ev.characterId}) via ${ev.cause} — no MC entity to kill",
            )
            return
        }
        if (entity.isDead) return

        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                if (!entity.isDead && entity.health > 0.0) {
                    try {
                        entity.health = 0.0
                    } catch (e: Exception) {
                        plugin.logger.warning(
                            "[death] failed to kill ${ev.name} (${ev.characterId}): ${e.message}",
                        )
                    }
                }
            },
        )
    }

    private fun resolveLivingEntity(
        characterId: String,
        name: String,
    ): LivingEntity? {
        if (!plugin.isNpcRegistryReady) return null
        val record =
            if (characterId.isNotBlank() && plugin.isCharacterRegistryReady) {
                plugin.characterRegistry.getById(characterId)
            } else {
                null
            }
        val lookupName = record?.name ?: name
        if (lookupName.isBlank()) return null
        val storyNpc = plugin.npcRegistry.getByName(lookupName) ?: return null
        return storyNpc.entity as? LivingEntity
    }
}
