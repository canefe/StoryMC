package com.canefe.story.bridge

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.combat.SwingDir
import com.canefe.story.combat.adapter.NpcCombatant
import com.canefe.story.npc.CitizensStoryNPC
import com.canefe.story.util.characterId
import net.citizensnpcs.api.CitizensAPI
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes inbound intents from the sim/LLM service by translating them
 * into Minecraft actions. All methods run on the main server thread.
 */
object IntentExecutor {
    /** Per-characterId timestamp of last reconciliation trigger from a missing-NPC intent. */
    private val lastMissingReconcile = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val MISSING_RECONCILE_COOLDOWN_MS = 5_000L

    /** Per-characterId last action label sent to the client, for change-diffing. */
    private val lastActionLabel = ConcurrentHashMap<String, String>()

    /**
     * Decides what action label to push for [characterId] given the sim's current
     * [rawLabel]. Returns the label to send, or null to send nothing.
     *
     * The client ACTION popup is sticky, so we simply never forward empty labels:
     * the sim emits empty during the gaps between idle behaviors (cooldowns), and
     * forwarding those as clears would flicker the sticky label off. A real label
     * always overwrites the previous one; an empty one means "nothing new" and is
     * ignored. Non-empty labels are still de-duplicated so we only send on change.
     */
    fun actionLabelToSend(characterId: String, rawLabel: String): String? {
        val label = rawLabel.trim()
        if (label.isEmpty()) return null
        val prev = lastActionLabel.put(characterId, label)
        return if (prev != label) label else null
    }

    fun resetActionLabelCacheForTest() = lastActionLabel.clear()

    /** Per-characterId last behaviorId we played an animation for, for change-diffing. */
    private val lastAnimationBehavior = ConcurrentHashMap<String, String>()

    /**
     * Maps a sim behaviorId to the animation action key passed to
     * [StoryNPC.playActionAnimation] (skill `StoryAnim_<key>`). Behaviors with no
     * visible body animation (e.g. walking, which pathing already shows) are
     * absent and yield null — no animation fires. Unknown ids are also null
     * (forgiving, like the location-template default).
     */
    private val behaviorAnimationKeys: Map<String, String> = mapOf(
        "eat_bread" to "eat",
        "seek_water" to "drink",
        "seek_rest" to "rest",
        "socialize" to "socialize",
        "buy_item" to "trade",
    )

    /**
     * Decides which animation key to play for [characterId] given the sim's
     * current [behaviorId]. Returns the key (e.g. "eat") to fire once, or null to
     * play nothing.
     *
     * npc.state arrives ~every 2s while an action runs, so animations must fire
     * ONCE per action, not every tick. This change-diffs on behaviorId exactly
     * like [actionLabelToSend]: an unmapped/unknown/empty behaviorId is ignored
     * (returns null) and — like empty labels — does NOT reset the diff, so a brief
     * idle gap between two runs of the same behavior won't replay the animation.
     * A mapped behavior fires only when it differs from the last one we played.
     */
    fun animationToPlay(characterId: String, behaviorId: String): String? {
        val key = behaviorAnimationKeys[behaviorId.trim()] ?: return null
        val prev = lastAnimationBehavior.put(characterId, key)
        return if (prev != key) key else null
    }

    fun resetAnimationCacheForTest() = lastAnimationBehavior.clear()

    /** Global floor between any two missing-NPC reconciliation requests. */
    @Volatile private var lastMissingReconcileGlobal: Long = 0L
    private const val MISSING_RECONCILE_GLOBAL_INTERVAL_MS = 1_500L

    // --- navigate_to arrival watcher ---
    //
    // navigate_to is fire-and-forget at the pathing layer (Citizens' navigator
    // and MythicMobs' GoToMechanic both just kick off async Mojang AI pathing).
    // The sim treats intent.completed as "you arrived" — so acking inline at
    // dispatch time was a lie: the NPC hadn't moved yet, which let the sim
    // believe a travel step finished while the NPC stood still. This watcher
    // polls position after dispatch and only acks on real arrival (within
    // `arriveRange` of the target) — or rejects UNREACHABLE if the NPC stops
    // making progress (stuck) or the deadline passes.

    /**
     * How close (blocks) to the target counts as arrived. Kept >= the largest
     * location radius (temple_grounds is 3.0) so MythicMobs pathing, which stops
     * short of the exact center, still registers as "arrived" instead of stalling
     * just outside the ring.
     */
    private const val NAV_ARRIVE_RANGE = 3.5

    /** Poll cadence in server ticks (10 = twice per second). */
    private const val NAV_POLL_TICKS = 10L

    /** Hard deadline before giving up, in poll samples. 600 samples × 10t ≈ 5 min. */
    private const val NAV_MAX_SAMPLES = 600

    /**
     * Consecutive no-progress samples before declaring "stuck". A frontend that
     * stops short of the target republishes the same position; this catches it.
     * 60 samples × 10t ≈ 30s of zero progress. MythicMobs' GoToMechanic cuts the
     * path short and stops, and the sim re-issues navigate_to on a ~1s cadence to
     * re-path it — so there are brief no-progress gaps between nudges that must
     * NOT trip the stall detector. 30s is generous enough to ride out those gaps
     * while still catching a genuinely wedged NPC before the 5-min hard timeout.
     */
    private const val NAV_STALL_SAMPLES = 60

    /** Minimum closing distance (blocks) per sample to count as progress. */
    private const val NAV_PROGRESS_EPS = 0.15

    /**
     * Poll samples between navigate_to re-issues. StoryMC owns the keep-walking
     * loop (mirroring NPCFollowTracker): the sim issues navigate_to ONCE per walk
     * and this watcher re-calls npc.navigateTo on a steady cadence so MythicMobs'
     * GoToMechanic — which cuts the path short partway and stops — keeps re-pathing
     * toward the target. At NAV_POLL_TICKS (0.5s) per sample, 2 samples ≈ 1s, the
     * same cadence NPCFollowTracker/SquadOrderTracker use. Re-issuing every poll
     * would restart pathing before the NPC could step (the original freeze), so we
     * nudge once per interval, not every sample.
     */
    const val NAV_REISSUE_SAMPLES = 2

    /**
     * Pure cadence decision, kept tiny + testable (see NavReissueTest): re-issue
     * navigate_to when this many poll samples have elapsed since the last re-issue.
     */
    fun shouldReissueNav(samplesSinceReissue: Int, intervalSamples: Int): Boolean =
        samplesSinceReissue >= intervalSamples

    /** Active arrival watchers keyed by characterId, so a new nav supersedes the old. */
    private val navWatchers = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * characterId -> (intentId, primitive) of the currently-watched go_to, so a
     * superseding go_to can emit intent.rejected(SUPERSEDED) for the OLD intentId
     * before [startNavWatcher] cancels its watcher. Also gates completeGoTo /
     * rejectGoTo: they only emit when they're still the active intent for the
     * character, so a superseded watcher's stray terminal callback can't double-emit.
     */
    private val activeGoTo = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    /**
     * Pure supersede decision (see GoToSupersedeTest): given the previously-active
     * go_to record [prev] for an NPC and the [newIntentId] now arriving, returns
     * the old intentId that must be rejected as SUPERSEDED, or null if there is no
     * distinct in-flight intent to reject (no prior, or it's the same intentId).
     */
    fun supersededIntentId(prev: Pair<String, String>?, newIntentId: String): String? =
        if (prev != null && prev.first != newIntentId) prev.first else null

    fun resetActiveGoToForTest() = activeGoTo.clear()

    /**
     * Start (or replace) an arrival watcher for [characterId] heading to [target].
     * Calls [onArrive] once it gets within [NAV_ARRIVE_RANGE], or [onFail] on
     * stuck / timeout / despawn. Exactly one terminal callback fires.
     */
    private fun startNavWatcher(
        plugin: Story,
        characterId: String,
        target: Location,
        onArrive: () -> Unit,
        onFail: (RejectionReason) -> Unit,
        arriveRange: Double = NAV_ARRIVE_RANGE,
        stallSamples: Int = NAV_STALL_SAMPLES,
        maxSamples: Int = NAV_MAX_SAMPLES,
    ) {
        // Supersede any in-flight watcher for this NPC (a fresh navigate wins).
        navWatchers.remove(characterId)?.let { Bukkit.getScheduler().cancelTask(it) }

        val state = NavWatchState()
        val taskId =
            Bukkit.getScheduler().runTaskTimer(
                plugin,
                Runnable {
                    val npc = resolveNPC(plugin, characterId)
                    val loc = npc?.location
                    if (npc == null || loc == null || !npc.isSpawned) {
                        finishNavWatcher(characterId)
                        onFail(RejectionReason.NPC_NOT_FOUND)
                        return@Runnable
                    }
                    // Different world than the target → can't path there.
                    if (loc.world != target.world) {
                        finishNavWatcher(characterId)
                        onFail(RejectionReason.UNREACHABLE)
                        return@Runnable
                    }

                    val dist = loc.distance(target)
                    if (dist <= arriveRange) {
                        finishNavWatcher(characterId)
                        onArrive()
                        return@Runnable
                    }

                    // Progress / stall tracking.
                    if (dist < state.lastDist - NAV_PROGRESS_EPS) {
                        state.lastDist = dist
                        state.stalledSamples = 0
                    } else {
                        state.stalledSamples++
                    }

                    state.samples++
                    val stuck = state.stalledSamples >= stallSamples
                    val timedOut = state.samples >= maxSamples
                    if (stuck || timedOut) {
                        plugin.logger.info(
                            "[FrontendIntent] navigate_to UNREACHABLE char=$characterId " +
                                "dist=${"%.1f".format(dist)} ${if (stuck) "stuck" else "timeout"}",
                        )
                        finishNavWatcher(characterId)
                        onFail(RejectionReason.UNREACHABLE)
                        return@Runnable
                    }

                    // Keep-walking loop (StoryMC-owned, mirrors NPCFollowTracker):
                    // MythicMobs' GoToMechanic cuts the path short and stops, so we
                    // re-issue navigateTo on a steady ~1s cadence to re-path the NPC
                    // toward the target. The sim sends navigate_to only once per walk.
                    state.samplesSinceReissue++
                    if (shouldReissueNav(state.samplesSinceReissue, NAV_REISSUE_SAMPLES)) {
                        npc.navigateTo(target)
                        state.samplesSinceReissue = 0
                    }
                },
                NAV_POLL_TICKS,
                NAV_POLL_TICKS,
            ).taskId
        navWatchers[characterId] = taskId
    }

    private fun finishNavWatcher(characterId: String) {
        navWatchers.remove(characterId)?.let { Bukkit.getScheduler().cancelTask(it) }
    }

    private class NavWatchState {
        var lastDist = Double.MAX_VALUE
        var stalledSamples = 0
        var samples = 0

        /** Poll samples since the last navigate_to re-issue (keep-walking loop). */
        var samplesSinceReissue = 0
    }

    /**
     * If story-go sends an intent for a characterId we don't have spawned, ask the sim
     * to reconcile around the nearest player. Two debounces:
     *   - per-characterId cooldown: same missing NPC won't retrigger within COOLDOWN_MS.
     *   - global interval: at most one missing-driven reconcile every GLOBAL_INTERVAL_MS,
     *     so a flood of distinct unknown IDs doesn't spam the sim.
     */
    private fun requestReconcileForMissing(plugin: Story, characterId: String, source: String) {
        val now = System.currentTimeMillis()
        if (now - lastMissingReconcileGlobal < MISSING_RECONCILE_GLOBAL_INTERVAL_MS) return
        val previous = lastMissingReconcile[characterId]
        if (previous != null && now - previous < MISSING_RECONCILE_COOLDOWN_MS) return
        lastMissingReconcile[characterId] = now
        lastMissingReconcileGlobal = now

        val anchor = Bukkit.getOnlinePlayers().firstOrNull() ?: return
        val service = try {
            plugin.reconciliationService
        } catch (_: UninitializedPropertyAccessException) {
            return
        }
        service.requestNearby(
            world = anchor.world.name,
            x = anchor.location.x,
            y = anchor.location.y,
            z = anchor.location.z,
            radius = plugin.configService.reconcileRadius,
            source = "$source:missing=$characterId",
        )
    }

    fun executeSpeakIntent(
        plugin: Story,
        intent: NPCSpeakIntent,
    ) {
        val npc = resolveNPC(plugin, intent.characterId)
        if (npc == null) {
            // Fall back: check if it's a player-character
            val player = plugin.server.onlinePlayers.firstOrNull {
                it.characterId == intent.characterId
            }
            if (player != null) {
                player.chat(intent.message)
            } else {
                plugin.logger.warning("Speak intent: character '${intent.characterId}' not found")
            }
            return
        }

        // Speaking ends any prior action label automatically — the speech bubble
        // overrides the action indicator on the client.
        plugin.conversationManager.clearActionIndicator(npc)
        plugin.conversationManager.speakAsNPC(npc, intent.message, addressedToId = intent.addressedToId, addressedToName = intent.addressedToName)
    }

    fun executeActionIntent(
        plugin: Story,
        intent: NPCActionIntent,
    ) {
        val npc = resolveNPC(plugin, intent.characterId)
        if (npc == null) {
            plugin.logger.warning("[ActionIntent] NPC not found for characterId=${intent.characterId}")
            return
        }
        val action = intent.action?.trim().orEmpty()
        if (action.isEmpty()) {
            plugin.conversationManager.clearActionIndicator(npc)
        } else {
            plugin.conversationManager.sendActionIndicator(npc, action)
        }
    }

    /**
     * World to path into for a go_to: the intent's [intentWorld] wins when
     * non-blank, otherwise the NPC's current world [npcWorld]. Pure + testable.
     */
    fun resolveGoToWorld(intentWorld: String, npcWorld: String): String =
        intentWorld.ifBlank { npcWorld }

    /**
     * The single movement primitive. Resolves the NPC, kicks off pathing, and
     * watches arrival — emitting exactly one outcome (intent.completed on arrival,
     * intent.rejected on missing-NPC / stuck / timeout) echoing intent.intentId.
     *
     * Optional wire thresholds override the watcher defaults when non-zero:
     *   - arrivalRange (blocks),
     *   - stallTimeout / maxDuration (seconds → poll samples at NAV_POLL_TICKS).
     */
    fun executeGoTo(
        plugin: Story,
        intent: GoToExecIntent,
    ) {
        // Supersede any live go_to for this NPC: emit intent.rejected(SUPERSEDED)
        // for the OLD intentId, then register THIS intent as the active one. Done
        // up front (before resolveNPC / world checks) so the prior intent always
        // gets an outcome even when the new go_to itself bails early — and so the
        // early-return reject paths below pass rejectGoTo's active-intent guard
        // (and clean up) instead of leaving a stale activeGoTo entry. For the live
        // success path, startNavWatcher cancels the old Bukkit task (which never
        // invokes the old onFail), so the SUPERSEDED emit is the old intent's only
        // outcome — we do not double-cancel here.
        beginActiveGoTo(plugin, intent.characterId, intent.intentId)

        val npc = resolveNPC(plugin, intent.characterId)
        if (npc == null) {
            plugin.logger.warning("[GoTo] NPC not found for characterId=${intent.characterId}")
            requestReconcileForMissing(plugin, intent.characterId, source = "go_to")
            rejectGoTo(plugin, intent, RejectionReason.NPC_NOT_FOUND)
            return
        }

        val npcWorldName = npc.entity?.world?.name ?: ""
        val worldName = resolveGoToWorld(intent.world, npcWorldName)
        val world = Bukkit.getWorld(worldName) ?: npc.entity?.world
        if (world == null) {
            plugin.logger.warning("[GoTo] world '$worldName' not found for characterId=${intent.characterId}")
            rejectGoTo(plugin, intent, RejectionReason.EXECUTION_ERROR)
            return
        }

        val target = Location(world, intent.x, intent.y, intent.z)
        plugin.logger.info("[GoTo] ${npc.name} -> (${intent.x}, ${intent.y}, ${intent.z}) @${world.name}")

        // Apply per-intent thresholds when supplied (>0); otherwise the watcher
        // keeps its built-in constants. stall/max are seconds on the wire.
        val secondsPerSample = NAV_POLL_TICKS / 20.0
        val arriveRange = if (intent.arrivalRange > 0.0) intent.arrivalRange else NAV_ARRIVE_RANGE
        val stallSamples =
            if (intent.stallTimeout > 0.0) (intent.stallTimeout / secondsPerSample).toInt().coerceAtLeast(1)
            else NAV_STALL_SAMPLES
        val maxSamples =
            if (intent.maxDuration > 0.0) (intent.maxDuration / secondsPerSample).toInt().coerceAtLeast(1)
            else NAV_MAX_SAMPLES

        npc.navigateTo(target)
        // Do NOT ack inline — navigateTo only kicks off async pathing. Watch
        // position and ack on real arrival (or reject if unreachable).
        startNavWatcher(
            plugin,
            intent.characterId,
            target,
            onArrive = { completeGoTo(plugin, intent) },
            onFail = { reason -> rejectGoTo(plugin, intent, reason) },
            arriveRange = arriveRange,
            stallSamples = stallSamples,
            maxSamples = maxSamples,
        )
    }

    /**
     * Clears [activeGoTo] for [characterId] iff it still maps to [intentId], and
     * reports whether this intent is the active one. Returns false (without
     * clearing) when a newer go_to has already taken over — so a superseded
     * watcher's stray terminal callback is dropped instead of double-emitting an
     * outcome or evicting the newer intent's record.
     */
    private fun claimAndClearActiveGoTo(characterId: String, intentId: String): Boolean {
        val current = activeGoTo[characterId]
        if (current == null || current.first != intentId) return false
        activeGoTo.remove(characterId, current)
        return true
    }

    /**
     * Makes [intentId] the active go_to for [characterId], superseding any prior
     * one: emits intent.rejected(SUPERSEDED) for the OLD intentId (so the sim
     * never leaks its PendingIntents entry) before overwriting the record.
     */
    private fun beginActiveGoTo(plugin: Story, characterId: String, intentId: String) {
        supersededIntentId(activeGoTo[characterId], intentId)?.let { oldIntentId ->
            plugin.eventBus.emit(
                IntentRejectedEvent(
                    intentId = oldIntentId,
                    characterId = characterId,
                    primitive = "go_to",
                    reason = RejectionReason.SUPERSEDED,
                ),
            )
        }
        activeGoTo[characterId] = intentId to "go_to"
    }

    private fun completeGoTo(plugin: Story, intent: GoToExecIntent) {
        if (!claimAndClearActiveGoTo(intent.characterId, intent.intentId)) return
        plugin.eventBus.emit(
            IntentCompletedEvent(
                intentId = intent.intentId,
                characterId = intent.characterId,
                primitive = "go_to",
            ),
        )
    }

    private fun rejectGoTo(plugin: Story, intent: GoToExecIntent, reason: RejectionReason) {
        if (!claimAndClearActiveGoTo(intent.characterId, intent.intentId)) return
        plugin.eventBus.emit(
            IntentRejectedEvent(
                intentId = intent.intentId,
                characterId = intent.characterId,
                primitive = "go_to",
                reason = reason,
            ),
        )
    }

    fun executeQuestAssignIntent(
        plugin: Story,
        intent: QuestAssignIntent,
    ) {
        val player = plugin.server.getPlayer(intent.playerName)
        if (player == null) {
            plugin.logger.warning("Quest assign intent: player '${intent.playerName}' not online")
            return
        }
        plugin.questManager.assignQuestToPlayer(player, intent.questId)
    }

    fun executeQuestUpdateIntent(
        plugin: Story,
        intent: QuestUpdateIntent,
    ) {
        val player = plugin.server.getPlayer(intent.playerName)
        if (player == null) {
            plugin.logger.warning("Quest update intent: player '${intent.playerName}' not online")
            return
        }
        val type =
            intent.objectiveType?.let {
                try {
                    com.canefe.story.quest.ObjectiveType
                        .valueOf(it)
                } catch (_: Exception) {
                    null
                }
            }
        plugin.questManager.updateObjectiveProgress(player, intent.questId, type, intent.target, intent.progress)
    }

    fun executeQuestCompleteIntent(
        plugin: Story,
        intent: QuestCompleteIntent,
    ) {
        val player = plugin.server.getPlayer(intent.playerName)
        if (player == null) {
            plugin.logger.warning("Quest complete intent: player '${intent.playerName}' not online")
            return
        }
        plugin.questManager.completeQuest(player, intent.questId)
    }

    fun executeCharacterUpdateIntent(
        plugin: Story,
        intent: CharacterUpdateIntent,
    ) {
        plugin.characterRegistry.reload()
        plugin.logger.info("Character registry reloaded for update to ${intent.characterId}")
    }

    fun executeSignalIntent(
        plugin: Story,
        intent: NPCSignalIntent,
    ) {
        val npc = resolveNPC(plugin, intent.characterId)
        if (npc == null) {
            plugin.logger.warning("Signal intent: character '${intent.characterId}' not found")
            return
        }

        val sourceEntity: Entity? = intent.sourceCharacterId?.let { srcId ->
            val srcNpc = resolveNPC(plugin, srcId)
            srcNpc?.entity ?: plugin.server.onlinePlayers.firstOrNull { it.characterId == srcId }
        }

        npc.signal(intent.signal, sourceEntity)
        plugin.logger.info("Signal '${intent.signal}' sent to '${npc.name}'")
    }

    /**
     * Spawns a MythicMob-backed NPC if one with this characterId isn't already in-world.
     * Only fires when the Bevy sim is active and sends a spawn request.
     */
    fun executeNpcSpawnIntent(plugin: Story, intent: NpcSpawnIntent) {
        if (!plugin.isNpcRegistryReady) return

        // Never spawn a stand-in for a player who is currently online
        val onlinePlayer = Bukkit.getOnlinePlayers().firstOrNull { it.characterId == intent.characterId }
        if (onlinePlayer != null) {
            plugin.logger.info("[NpcSpawn] Skipping spawn of '${intent.name}' — player ${onlinePlayer.name} is online")
            return
        }

        // Already spawned — only skip if the entity is actually alive in the world
        val existing = resolveNPC(plugin, intent.characterId)
        if (existing?.isSpawned == true) return

        val factory = plugin.mythicMobNpcFactoryOrNull ?: run {
            plugin.logger.warning("[NpcSpawn] MythicMob factory not available, cannot spawn '${intent.name}'")
            return
        }

        val targetWorld = if (intent.world.isNotBlank()) Bukkit.getWorld(intent.world) else null
        val world = targetWorld ?: Bukkit.getWorlds().firstOrNull() ?: return
        val loc = Location(world, intent.x, intent.y, intent.z)

        // Only spawn if a player is nearby — prevents mass spawning with no players online
        val nearPlayer = Bukkit.getOnlinePlayers().any { p ->
            p.world == world &&
                p.location.distanceSquared(loc) <= SPAWN_RADIUS_SQ
        }
        if (!nearPlayer) {
            plugin.logger.info("[NpcSpawn] Skipping spawn of '${intent.name}' — no player within range")
            return
        }

        plugin.logger.info("[NpcSpawn] Spawning '${intent.name}' (${intent.characterId}) at $loc")
        // "character" is the generic MythicMobs template for story NPCs.
        // The characterId becomes the stable UUID so the registry can find it later.
        factory.spawn(
            mobTemplate = intent.mobTemplate,
            location = loc,
            displayName = intent.name,
            stableUniqueId = try { java.util.UUID.fromString(intent.characterId) } catch (_: Exception) { java.util.UUID.randomUUID() },
            characterId = intent.characterId,
        )
    }

    /**
     * Applies a position/health update from the Bevy sim to a spawned NPC.
     */
    fun executeNpcStateIntent(plugin: Story, intent: NpcStateIntent) {
        if (!plugin.isNpcRegistryReady) return
        val npc = resolveNPC(plugin, intent.characterId) ?: return
        val entity = npc.entity ?: return
        val world = entity.world
        val newLoc = Location(world, intent.x, intent.y, intent.z, entity.location.yaw, entity.location.pitch)

        if (entity.location.distanceSquared(newLoc) > 0.25) {
            npc.navigateTo(newLoc)
        }

        if (intent.health > 0 && entity is LivingEntity) {
            val maxHp = entity.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0
            entity.health = intent.health.coerceIn(0.0, maxHp)
        }

        // Action label: push on change; empty labels are ignored so the sim's
        // inter-action gaps don't flicker the sticky client label off. Use the
        // client-facing UUID (the display entity the client can resolve in-world),
        // exactly as the perception-popup path does — the backing entity.uniqueId
        // is invisible to the client for MythicMob-backed NPCs.
        val clientUuid = npc.clientFacingUuid ?: entity.uniqueId
        val toSend = actionLabelToSend(intent.characterId, intent.actionLabel ?: "")
        if (toSend != null) {
            // Pass the backing entity id: the disguise UUID never matches a
            // client-side entity, so the client resolves the NPC by entity id.
            // [DBG actionLabel] TEMP — remove after live verification.
            plugin.logger.info("[DBG npcState] LABEL cid=${intent.characterId} send='$toSend' clientUuid=$clientUuid entityId=${entity.entityId}")
            plugin.perceptionBroadcaster.sendActionPopup(clientUuid, toSend, entity.entityId)
        }

        // Body animation: behaviorId is the distinct behavior (eat_bread/seek_water
        // /…) — actionId is "lua_hook" for every hook primitive, so it can't drive
        // animation. Change-diffed so the animation fires once per action, not
        // every ~2s state tick. Unknown/unmapped/empty behaviors play nothing.
        val animKey = animationToPlay(intent.characterId, intent.behaviorId ?: "")
        if (animKey != null) {
            plugin.logger.info("[npcState] ANIM cid=${intent.characterId} behavior='${intent.behaviorId}' key='$animKey'")
            npc.playActionAnimation(animKey)
        }
    }

    /**
     * Resolves both NPCs of a sim item transfer and relays a story:item_transfer
     * packet to nearby players, who render the floating-item arc. Renders nothing
     * server-side. Skips when either NPC is unresolved or no player is in range.
     */
    fun executeItemTransferIntent(plugin: Story, intent: NpcItemTransferIntent) {
        if (!plugin.isNpcRegistryReady) return
        val from = resolveNPC(plugin, intent.fromCharacterId)?.entity ?: return
        val to = resolveNPC(plugin, intent.toCharacterId)?.entity ?: return
        val spec = plugin.itemMapService.renderSpecFor(intent.item)
        val materialId = "minecraft:" + spec.material.name.lowercase()
        val cmd = spec.customModelData ?: -1

        val renderDist = 64.0
        val renderDistSq = renderDist * renderDist
        val audience = Bukkit.getOnlinePlayers().filter { p ->
            (p.world == from.world && p.location.distanceSquared(from.location) <= renderDistSq) ||
                (p.world == to.world && p.location.distanceSquared(to.location) <= renderDistSq)
        }
        if (audience.isEmpty()) return
        plugin.itemTransferBridge.send(
            audience,
            from.entityId,
            to.entityId,
            materialId,
            cmd,
            intent.qty,
            intent.reason,
        )
    }

    fun executeEmoteIntent(
        plugin: Story,
        intent: NPCEmoteIntent,
    ) {
        val npc = resolveNPC(plugin, intent.characterId)
        if (npc == null) {
            plugin.logger.warning("Emote intent: character '${intent.characterId}' not found")
            return
        }

        val action = if (intent.action.startsWith("*")) intent.action else "*${intent.action}*"
        plugin.npcMessageService.broadcastNPCMessage(
            message = action,
            npc = npc,
            streaming = true,
        )
    }

    private fun wrapAngle(angle: Float): Float {
        var a = angle % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }

    /**
     * Resolves a character ID to a StoryNPC. Tries the character registry first,
     * then falls back to name-based Citizens lookup for backwards compatibility.
     */
    private fun resolveNPC(
        plugin: Story,
        characterId: String,
    ): StoryNPC? {
        // Try character registry first
        val record =
            try {
                plugin.characterRegistry.getById(characterId)
            } catch (_: UninitializedPropertyAccessException) {
                null
            }

        if (record != null) {
            // Prefer the live StoryNPCRegistry first (MythicMob-backed NPCs live here)
            if (plugin.isNpcRegistryReady) {
                plugin.npcRegistry.getByName(record.name)?.let { return it }
            }

            val config =
                try {
                    plugin.characterRegistry.getMinecraftConfig(characterId)
                } catch (_: UninitializedPropertyAccessException) {
                    null
                }

            // Fall back to Citizens via UUID or numeric ID from frontend config
            config?.citizensUuid?.let { uuid ->
                val citizenNpc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid)
                if (citizenNpc != null) return CitizensStoryNPC(citizenNpc)
            }
            config?.citizensNpcId?.let { id ->
                val citizenNpc = CitizensAPI.getNPCRegistry().getById(id)
                if (citizenNpc != null) return CitizensStoryNPC(citizenNpc)
            }

            // Name match in Citizens
            val citizenNpc = CitizensAPI.getNPCRegistry().firstOrNull { it.name == record.name }
            if (citizenNpc != null) return CitizensStoryNPC(citizenNpc)
        }

        // Unified registry: scan for any StoryNPC whose characterId matches
        if (plugin.isNpcRegistryReady) {
            for (storyNpc in plugin.npcRegistry.all()) {
                val id =
                    try {
                        plugin.characterRegistry.getCharacterIdForNPC(storyNpc)
                    } catch (_: UninitializedPropertyAccessException) {
                        null
                    }
                if (id == characterId) return storyNpc
            }
            // Last resort: treat characterId as a name in the unified registry
            plugin.npcRegistry.getByName(characterId)?.let { return it }
        }

        // Legacy fallback: treat characterId as a name in Citizens
        val citizenNpc = CitizensAPI.getNPCRegistry().firstOrNull { it.name == characterId }
        if (citizenNpc != null) return CitizensStoryNPC(citizenNpc)

        return null
    }

    fun executeFrontendIntent(plugin: Story, intent: FrontendIntentEvent) {
        plugin.logger.info("[FrontendIntent] received primitive=${intent.primitive} char=${intent.characterId}")
        val npc = resolveNPC(plugin, intent.characterId) ?: run {
            plugin.logger.warning("[FrontendIntent] NPC not found for characterId=${intent.characterId}")
            requestReconcileForMissing(plugin, intent.characterId, source = "frontend_intent")
            rejectIntent(plugin, intent, RejectionReason.NPC_NOT_FOUND)
            return
        }

        try {
            when (intent.primitive) {
                "set_target" -> {
                    val targetId = intent.targetCharId
                    if (targetId == null) {
                        rejectIntent(plugin, intent, RejectionReason.TARGET_NOT_FOUND); return
                    }
                    val target = resolveTarget(plugin, targetId) as? org.bukkit.entity.Player
                    if (target == null) {
                        plugin.logger.warning("[FrontendIntent] set_target: player target not found for $targetId")
                        rejectIntent(plugin, intent, RejectionReason.TARGET_NOT_FOUND); return
                    }
                    npc.setTarget(target)
                    completeIntent(plugin, intent)
                }
                "flee_from" -> {
                    plugin.logger.info("[FrontendIntent] flee_from from=(${intent.fromX},${intent.fromZ}) min=${intent.minDist} max=${intent.maxDist}")
                    val entity = npc.entity as? LivingEntity
                    if (entity == null) {
                        plugin.logger.warning("[FrontendIntent] flee_from: npc.entity is not LivingEntity for ${intent.characterId}")
                        rejectIntent(plugin, intent, RejectionReason.UNSUPPORTED_BACKEND); return
                    }
                    val world = entity.world
                    val origin = entity.location
                    // Vector from threat anchor to NPC, in XZ — the direction we want to flee.
                    var dx = origin.x - intent.fromX
                    var dz = origin.z - intent.fromZ
                    val len = Math.sqrt(dx * dx + dz * dz)
                    if (len < 1e-3) {
                        // Threat is on top of us — pick a random direction to break the tie.
                        val angle = Math.random() * 2.0 * Math.PI
                        dx = Math.cos(angle); dz = Math.sin(angle)
                    } else {
                        dx /= len; dz /= len
                    }
                    val minD = if (intent.minDist > 0.0) intent.minDist else 12.0
                    val maxD = if (intent.maxDist > minD) intent.maxDist else (minD + 15.0)

                    // Sample candidates in a 90° cone around the away-vector at random distances.
                    // First standable candidate wins; fall back to a straight-line shot if none pass.
                    val rng = java.util.concurrent.ThreadLocalRandom.current()
                    var chosen: Location? = null
                    repeat(8) {
                        val coneOffset = (rng.nextDouble() - 0.5) * (Math.PI / 2.0) // ±45°
                        val baseAngle = Math.atan2(dz, dx)
                        val a = baseAngle + coneOffset
                        val d = minD + rng.nextDouble() * (maxD - minD)
                        val tx = origin.x + Math.cos(a) * d
                        val tz = origin.z + Math.sin(a) * d
                        val ty = world.getHighestBlockYAt(tx.toInt(), tz.toInt()) + 1.0
                        val candidate = Location(world, tx, ty, tz)
                        val standOn = candidate.clone().add(0.0, -1.0, 0.0).block
                        val feet = candidate.block
                        if (standOn.type.isSolid && !feet.type.isSolid && !feet.isLiquid) {
                            chosen = candidate
                            return@repeat
                        }
                    }
                    val dest = chosen ?: run {
                        val d = (minD + maxD) / 2.0
                        val tx = origin.x + dx * d
                        val tz = origin.z + dz * d
                        val ty = world.getHighestBlockYAt(tx.toInt(), tz.toInt()) + 1.0
                        Location(world, tx, ty, tz)
                    }
                    npc.navigateTo(dest)
                    completeIntent(plugin, intent)
                }
                "look_at" -> {
                    val entity = npc.entity as? LivingEntity
                    if (entity == null) {
                        rejectIntent(plugin, intent, RejectionReason.UNSUPPORTED_BACKEND); return
                    }
                    val from = if (intent.useEyeLocation) entity.eyeLocation else entity.location
                    val to: Location = if (intent.targetCharId != null) {
                        val target = resolveTarget(plugin, intent.targetCharId) as? LivingEntity
                        if (target == null) {
                            rejectIntent(plugin, intent, RejectionReason.TARGET_NOT_FOUND); return
                        }
                        target.eyeLocation
                    } else {
                        val world = entity.world
                        Location(world, intent.x, intent.y, intent.z)
                    }

                    val dx = to.x - from.x
                    val dy = to.y - from.y
                    val dz = to.z - from.z
                    val horizDist = Math.sqrt(dx * dx + dz * dz)
                    var targetYaw = (Math.toDegrees(Math.atan2(-dx, dz)).toFloat())
                    var targetPitch = (Math.toDegrees(-Math.atan2(dy, horizDist)).toFloat())

                    val currentYaw = entity.location.yaw
                    val currentPitch = entity.location.pitch

                    val finalYaw = if (intent.maxYaw > 0f) {
                        val diff = wrapAngle(targetYaw - currentYaw)
                        currentYaw + diff.coerceIn(-intent.maxYaw, intent.maxYaw)
                    } else targetYaw

                    val finalPitch = if (intent.maxPitch > 0f) {
                        val diff = wrapAngle(targetPitch - currentPitch)
                        currentPitch + diff.coerceIn(-intent.maxPitch, intent.maxPitch)
                    } else targetPitch

                    val newLoc = entity.location.clone()
                    newLoc.yaw = finalYaw
                    newLoc.pitch = finalPitch
                    entity.teleport(newLoc)
                    completeIntent(plugin, intent)
                }
                "attempt_hit" -> {
                    val targetId = intent.targetCharId
                    if (targetId == null) {
                        rejectIntent(plugin, intent, RejectionReason.TARGET_NOT_FOUND); return
                    }
                    val target = resolveTarget(plugin, targetId) as? LivingEntity
                    if (target == null) {
                        rejectIntent(plugin, intent, RejectionReason.TARGET_NOT_FOUND); return
                    }
                    val attacker = npc.entity as? LivingEntity
                    if (attacker == null) {
                        rejectIntent(plugin, intent, RejectionReason.UNSUPPORTED_BACKEND); return
                    }
                    if (attacker.isDead) {
                        rejectIntent(plugin, intent, RejectionReason.NPC_DEAD); return
                    }

                    if (plugin.configService.combatEnabled) {
                        // Route through directional combat. Direction comes from sim;
                        // null falls back to a random pick so legacy callers still work.
                        val service = plugin.directionalCombatService
                        val combatant =
                            service.registry.byUuid(attacker.uniqueId)
                                ?: service.registry.byEntityId(attacker.entityId)
                                ?: NpcCombatant(npc).also { service.registry.register(it) }
                        val dir = SwingDir.fromWire(intent.swingDirection) ?: SwingDir.entries.random()
                        service.queueSwing(combatant, dir)
                        completeIntent(plugin, intent)
                    } else {
                        if (attacker.location.distanceSquared(target.location) <= 9.0) {
                            attacker.attack(target)
                            completeIntent(plugin, intent)
                        } else {
                            rejectIntent(plugin, intent, RejectionReason.OUT_OF_RANGE)
                        }
                    }
                }
                "clear_target" -> {
                    // Stop any in-flight arrival watcher: the nav it was tracking
                    // is being cancelled, so its target is moot.
                    finishNavWatcher(intent.characterId)
                    npc.cancelNavigation()
                    completeIntent(plugin, intent)
                }
                else -> {
                    plugin.logger.warning("[FrontendIntent] unknown primitive=${intent.primitive}")
                    rejectIntent(plugin, intent, RejectionReason.INVALID_PRIMITIVE)
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("[FrontendIntent] exception applying primitive=${intent.primitive} char=${intent.characterId}: ${e.message}")
            e.printStackTrace()
            rejectIntent(plugin, intent, RejectionReason.EXECUTION_ERROR)
        }
    }

    private fun completeIntent(plugin: Story, intent: FrontendIntentEvent) {
        plugin.eventBus.emit(
            IntentCompletedEvent(
                intentId = intent.intentId,
                characterId = intent.characterId,
                primitive = intent.primitive,
            ),
        )
    }

    private fun rejectIntent(plugin: Story, intent: FrontendIntentEvent, reason: RejectionReason) {
        plugin.eventBus.emit(
            IntentRejectedEvent(
                intentId = intent.intentId,
                characterId = intent.characterId,
                primitive = intent.primitive,
                reason = reason,
            ),
        )
    }

    private const val SPAWN_RADIUS = 96.0
    private const val SPAWN_RADIUS_SQ = SPAWN_RADIUS * SPAWN_RADIUS

    private fun resolveTarget(plugin: Story, targetId: String): Entity? {
        Bukkit.getPlayerExact(targetId)?.let { return it }
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.characterId == targetId) return player
        }
        return resolveNPC(plugin, targetId)?.entity
    }
}
