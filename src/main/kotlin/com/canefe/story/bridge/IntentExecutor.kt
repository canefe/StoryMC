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

/**
 * Executes inbound intents from the sim/LLM service by translating them
 * into Minecraft actions. All methods run on the main server thread.
 */
object IntentExecutor {
    /** Per-characterId timestamp of last reconciliation trigger from a missing-NPC intent. */
    private val lastMissingReconcile = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val MISSING_RECONCILE_COOLDOWN_MS = 5_000L

    /** Global floor between any two missing-NPC reconciliation requests. */
    @Volatile private var lastMissingReconcileGlobal: Long = 0L
    private const val MISSING_RECONCILE_GLOBAL_INTERVAL_MS = 1_500L

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

        plugin.conversationManager.speakAsNPC(npc, intent.message, addressedToId = intent.addressedToId, addressedToName = intent.addressedToName)
    }

    fun executeMoveIntent(
        plugin: Story,
        intent: NPCMoveIntent,
    ) {
        val npc = resolveNPC(plugin, intent.characterId)
        if (npc == null) {
            plugin.logger.warning("[MoveIntent] NPC not found for characterId=${intent.characterId}")
            requestReconcileForMissing(plugin, intent.characterId, source = "move_intent")
            return
        }
        plugin.logger.info("[MoveIntent] ${npc.name} → (${intent.x}, ${intent.y}, ${intent.z})")

        val world =
            intent.world?.let { Bukkit.getWorld(it) }
                ?: npc.entity?.world
                ?: return

        val target = Location(world, intent.x, intent.y, intent.z)
        npc.navigateTo(target)
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
                "navigate_to" -> {
                    val world = Bukkit.getWorlds().firstOrNull()
                    if (world == null) {
                        rejectIntent(plugin, intent, RejectionReason.EXECUTION_ERROR); return
                    }
                    npc.navigateTo(Location(world, intent.x, intent.y, intent.z))
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
