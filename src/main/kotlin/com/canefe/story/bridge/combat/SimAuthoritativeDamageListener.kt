package com.canefe.story.bridge.combat

import com.canefe.story.Story
import com.canefe.story.bridge.CombatAttackResolvedEvent
import com.canefe.story.bridge.CombatPlayerAttackEvent
import com.canefe.story.util.characterId
import net.citizensnpcs.api.CitizensAPI
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Routes vanilla EntityDamageByEntityEvent (EDBE) through the sim-authoritative
 * combat round-trip described in
 * `story-sim/docs/2026-05-27-combat-player-attack-contract.md` and
 * `story-sim/docs/2026-05-27-combat-attack-resolved-contract.md`.
 *
 * Pipeline per swing:
 *
 *   1. EDBE fires (player swings, NPC swings, projectile lands).
 *   2. We confirm at least one side is sim-tracked. If neither is, we let other
 *      damage handlers (vanilla, perception-only listeners, the legacy
 *      VanillaMeleeListener combat effort) own the event.
 *   3. We cancel the EDBE so vanilla doesn't apply damage yet.
 *   4. We publish [CombatPlayerAttackEvent] (type `combat.player_attack`) to
 *      story-go. story-go forwards it via NATS to story-sim; sim picks a weapon,
 *      runs the resolver, applies damage to body parts and hediffs, then
 *      publishes [CombatAttackResolvedEvent] (type `combat.attack_resolved`)
 *      back through story-go to the plugin.
 *   5. The reply handler applies the resolved damage to the defender MC entity
 *      via [LivingEntity.damage], which triggers vanilla's hurt animation, red
 *      flash, knockback, and death animation (if killed).
 *
 * Coexists with the existing `entity_hurt` flow (NPCDamagedEvent emitted by
 * PerceptionListener at LOWEST priority): per the Stage-1 plan both paths fire
 * for a sim-tracked attack until the migration cuts over fully. Environment
 * damage (lava, fall, drowning) never reaches this listener — it arrives as
 * EntityDamageEvent, not EDBE.
 *
 * Independent of the `combat/` package's DirectionalCombatService /
 * VanillaMeleeListener effort. That work is intentionally untouched.
 *
 * ## Correlation
 *
 * The sim contract does NOT include a `request_id` field — it correlates the
 * reply back by (`attackerId`/`attackerName`, `defenderId`/`defenderName`).
 * Multiple in-flight attacks for the same pair are pushed onto a FIFO deque
 * and popped in arrival order when the reply lands.
 *
 * ## Timeout safety
 *
 * If no reply arrives within [REPLY_TIMEOUT_TICKS] ticks (~500ms at 20 TPS) we
 * fall back to applying the original vanilla damage so the plugin degrades
 * gracefully when the sim is down or NATS is partitioned. The fallback uses
 * the originally-computed `event.finalDamage` captured at attempt time.
 */
class SimAuthoritativeDamageListener(
    private val plugin: Story,
) : Listener {
    /**
     * One outstanding swing awaiting `combat.attack_resolved`. Keyed by
     * (attackerKey, defenderKey) — see [keyFor].
     */
    internal data class PendingAttack(
        val attackerEntityId: UUID,
        val defenderEntityId: UUID,
        val originalDamage: Double,
        val publishedAtMs: Long,
    )

    /**
     * FIFO queue per (attacker, defender) pair. The sim resolves swings in
     * arrival order, so the head of the deque matches the next reply.
     */
    private val pending = ConcurrentHashMap<Pair<String, String>, ConcurrentLinkedDeque<PendingAttack>>()

    /**
     * Re-entrancy guard. `defender.damage(amount, attacker)` synthesises a
     * fresh [EntityDamageByEntityEvent] through Bukkit, which lands right back
     * on this listener at LOWEST priority. Without this flag the fallback path
     * cancels its own replay, re-queues, times out again, and we loop forever
     * (one swing → infinite "sim did not resolve" warnings). Bukkit damage is
     * main-thread, so a single bool is enough.
     */
    @Volatile
    private var applyingSyntheticDamage: Boolean = false

    init {
        plugin.eventBus.on<CombatAttackResolvedEvent> { handleResolved(it) }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        // We re-enter via [applyDamage] (`defender.damage(...)` fires a new
        // EDBE). Skip those replays — they ARE the resolved damage, not a new
        // swing to route through the sim. Without this we'd loop forever.
        if (applyingSyntheticDamage) return

        val victim = event.entity as? LivingEntity ?: return
        val rawAttacker = (event.damager as? Projectile)?.shooter as? org.bukkit.entity.Entity ?: event.damager
        val attacker = rawAttacker as? LivingEntity ?: return

        val attackerCharId = resolveCharacterId(attacker)
        val attackerName = resolveDisplayName(attacker)
        val defenderCharId = resolveCharacterId(victim)
        val defenderName = resolveDisplayName(victim)

        // Only act when at least one side is sim-tracked. Otherwise let vanilla
        // / other combat effort own the event.
        if (attackerCharId == null && defenderCharId == null) return

        // Defensive: need at least name on both sides for the sim to resolve.
        if (attackerName.isBlank() || defenderName.isBlank()) return

        val originalDamage = event.finalDamage
        event.isCancelled = true

        val weaponItem = resolveWeaponItem(attacker)
        val key = keyFor(attackerCharId, attackerName, defenderCharId, defenderName)

        pending
            .computeIfAbsent(key) { ConcurrentLinkedDeque() }
            .addLast(
                PendingAttack(
                    attackerEntityId = attacker.uniqueId,
                    defenderEntityId = victim.uniqueId,
                    originalDamage = originalDamage,
                    publishedAtMs = System.currentTimeMillis(),
                ),
            )

        plugin.eventBus.emit(
            CombatPlayerAttackEvent(
                attackerId = attackerCharId,
                attackerName = attackerName,
                defenderId = defenderCharId,
                defenderName = defenderName,
                weaponItem = weaponItem,
                tick = plugin.server.currentTick.toLong(),
            ),
        )

        // Schedule timeout: if no reply within REPLY_TIMEOUT_TICKS, apply the
        // original damage so we never leave a swing un-resolved.
        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable { timeoutFallback(key, attacker.uniqueId, victim.uniqueId) },
            REPLY_TIMEOUT_TICKS,
        )
    }

    /**
     * Handle a `combat.attack_resolved` reply from the sim. Looks up the
     * matching pending attack and applies the resolved damage to the defender
     * MC entity, which triggers vanilla's hurt/flash/knockback/death visuals.
     */
    internal fun handleResolved(reply: CombatAttackResolvedEvent) {
        val key = keyFor(reply.attackerId, reply.attackerName, reply.defenderId, reply.defenderName)
        val queue = pending[key]
        val entry = queue?.pollFirst()
        if (queue != null && queue.isEmpty()) pending.remove(key, queue)

        val isKill = reply.killed

        // Miss → swing animation only; no damage to apply (unless the sim
        // somehow flagged it as a kill, which would be a bug — but be defensive).
        if (!isKill && (reply.outcome.equals("miss", ignoreCase = true) || reply.damageDealt <= 0f)) return

        // Resolve defender/attacker from the pending entry when available
        // (the swing was routed through our EDBE listener), otherwise fall
        // back to identity lookup so sim-internal swings (NPC-vs-NPC, where
        // the attack never went through MC EDBE) still apply visible
        // consequences — especially death.
        val defender = entry?.let { Bukkit.getEntity(it.defenderEntityId) as? LivingEntity }
            ?: resolveDefenderEntity(reply)
            ?: return
        val attacker = entry?.let { Bukkit.getEntity(it.attackerEntityId) }
            ?: resolveAttackerEntity(reply)

        if (isKill) {
            killDefender(defender, attacker, reply.damageDealt.toDouble())
        } else if (entry != null) {
            // Non-fatal damage: only apply when our listener actually cancelled
            // the original EDBE. Otherwise the sim's number would double-up on
            // damage that already played out through its own MC pathway.
            applyDamage(defender, attacker, reply.damageDealt.toDouble())
        }
    }

    /**
     * Look up the defender's Bukkit entity from the sim reply's identity
     * fields. Used when no pending EDBE entry exists (NPC-vs-NPC swing).
     */
    private fun resolveDefenderEntity(reply: CombatAttackResolvedEvent): LivingEntity? =
        resolveLivingEntity(reply.defenderId, reply.defenderName)

    private fun resolveAttackerEntity(reply: CombatAttackResolvedEvent): org.bukkit.entity.Entity? =
        resolveLivingEntity(reply.attackerId, reply.attackerName)

    private fun resolveLivingEntity(characterId: String?, name: String): LivingEntity? {
        // Player path: a sim character may be backed by a logged-in player.
        if (!characterId.isNullOrBlank()) {
            for (player in Bukkit.getOnlinePlayers()) {
                val pid = try { player.characterId } catch (_: Exception) { null }
                if (pid == characterId) return player
            }
        }
        // NPC path: characterId → CharacterRegistry → display name → StoryNPC.
        if (plugin.isNpcRegistryReady) {
            val record = characterId?.let { plugin.characterRegistry.getById(it) }
            val lookupName = record?.name ?: name
            if (lookupName.isNotBlank()) {
                val storyNpc = plugin.npcRegistry.getByName(lookupName)
                val entity = storyNpc?.entity
                if (entity is LivingEntity) return entity
            }
        }
        return null
    }

    /**
     * Sim says this swing was lethal — body died in the simulation. Vanilla MC
     * HP doesn't track body-part destruction, so the resolved damage number
     * may not be enough to drop the entity's health to zero. Apply the damage
     * first (so the kill is attributed to the attacker, last-damage-cause is
     * set correctly, advancements/statistics fire, knockback plays) then zero
     * health to guarantee the death animation runs.
     */
    private fun killDefender(
        defender: LivingEntity,
        attacker: org.bukkit.entity.Entity?,
        amount: Double,
    ) {
        applyingSyntheticDamage = true
        try {
            // Apply nominal damage so vanilla records the attacker as the killer
            // (last-damage-cause), plays the hurt animation, and triggers
            // knockback. Skip if amount is zero/negative.
            if (amount > 0.0) {
                if (attacker is LivingEntity) {
                    defender.damage(amount, attacker)
                } else {
                    defender.damage(amount)
                }
            }
            // Force death if still alive after the damage tick.
            if (!defender.isDead && defender.health > 0.0) {
                defender.health = 0.0
            }
        } catch (e: Exception) {
            plugin.logger.warning("[combat] killDefender failed: ${e.message}")
        } finally {
            applyingSyntheticDamage = false
        }
    }

    /**
     * Drops the head of the pending queue if it hasn't been resolved yet and
     * applies the original vanilla damage as a fallback. Idempotent — if the
     * reply already cleared the entry, this is a no-op.
     */
    private fun timeoutFallback(
        key: Pair<String, String>,
        attackerEntityId: UUID,
        defenderEntityId: UUID,
    ) {
        val queue = pending[key] ?: return
        // Walk the deque to find the matching entry — entries may have been
        // resolved out of head order if multiple pairs collide on same key.
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.attackerEntityId == attackerEntityId && entry.defenderEntityId == defenderEntityId) {
                iterator.remove()
                if (queue.isEmpty()) pending.remove(key, queue)
                val defender = Bukkit.getEntity(defenderEntityId) as? LivingEntity ?: return
                val attacker = Bukkit.getEntity(attackerEntityId)
                plugin.logger.warning(
                    "[combat] sim did not resolve attack within ${REPLY_TIMEOUT_TICKS}t — " +
                        "falling back to vanilla damage=${entry.originalDamage}",
                )
                applyDamage(defender, attacker, entry.originalDamage)
                return
            }
        }
    }

    private fun applyDamage(
        defender: LivingEntity,
        attacker: org.bukkit.entity.Entity?,
        amount: Double,
    ) {
        applyingSyntheticDamage = true
        try {
            if (attacker is LivingEntity) {
                defender.damage(amount, attacker)
            } else {
                defender.damage(amount)
            }
        } catch (e: Exception) {
            plugin.logger.warning("[combat] applyDamage failed: ${e.message}")
        } finally {
            applyingSyntheticDamage = false
        }
    }

    /**
     * Resolves the sim character_id for an entity. Returns null when the entity
     * is not sim-tracked (random vanilla mobs, non-Story-managed Citizens NPCs,
     * unlinked players).
     */
    private fun resolveCharacterId(entity: org.bukkit.entity.Entity): String? {
        try {
            if (CitizensAPI.getNPCRegistry().isNPC(entity)) {
                val storyNpc = plugin.npcRegistry.getByEntity(entity) ?: return null
                return plugin.characterRegistry.getCharacterIdForNPC(storyNpc)
            }
        } catch (_: Exception) {
        }
        if (plugin.isNpcRegistryReady) {
            val storyNpc = plugin.npcRegistry.getByEntity(entity)
            if (storyNpc != null) return plugin.characterRegistry.getCharacterIdForNPC(storyNpc)
        }
        if (entity is Player) {
            return try {
                entity.characterId
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun resolveDisplayName(entity: org.bukkit.entity.Entity): String {
        try {
            if (CitizensAPI.getNPCRegistry().isNPC(entity)) {
                return CitizensAPI.getNPCRegistry().getNPC(entity).name
            }
        } catch (_: Exception) {
        }
        if (plugin.isNpcRegistryReady) {
            val storyNpc = plugin.npcRegistry.getByEntity(entity)
            if (storyNpc != null) return storyNpc.name
        }
        if (entity is Player) return entity.name
        return entity.name
    }

    /**
     * Maps the attacker's main-hand item to a sim item id if we have a
     * vanilla→sim mapping. Today we have no mapping table, so we return null
     * (sim falls back to natural weapons / unarmed). Future work: build a
     * Material→ItemDef.id table and surface it here.
     */
    private fun resolveWeaponItem(attacker: LivingEntity): String? {
        if (attacker !is Player) return null
        val mainHand = attacker.inventory.itemInMainHand
        if (mainHand.type == org.bukkit.Material.AIR) return null
        // No vanilla→sim mapping yet. Returning null lets the sim use the
        // attacker's Equipment-equipped weapon or natural weapons.
        return null
    }

    companion object {
        /** ~500ms at 20 TPS. */
        internal const val REPLY_TIMEOUT_TICKS: Long = 10L

        /**
         * Correlation key built from whichever identifier each side has
         * available. Mirrors the sim's resolution preference (id over name,
         * case-insensitive name match).
         *
         * Exposed for tests.
         */
        internal fun keyFor(
            attackerId: String?,
            attackerName: String,
            defenderId: String?,
            defenderName: String,
        ): Pair<String, String> {
            val a = (attackerId ?: attackerName.lowercase()).ifBlank { attackerName.lowercase() }
            val d = (defenderId ?: defenderName.lowercase()).ifBlank { defenderName.lowercase() }
            return a to d
        }
    }
}
