package com.canefe.story.bridge

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
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

        plugin.conversationManager.speakAsNPC(npc, intent.message)
    }

    fun executeMoveIntent(
        plugin: Story,
        intent: NPCMoveIntent,
    ) {
        val npc = resolveNPC(plugin, intent.characterId)
        if (npc == null) {
            plugin.logger.warning("[MoveIntent] NPC not found for characterId=${intent.characterId}")
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

        val world = Bukkit.getWorlds().firstOrNull() ?: return
        val loc = Location(world, intent.x, intent.y, intent.z)

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
            val config =
                try {
                    plugin.characterRegistry.getMinecraftConfig(characterId)
                } catch (_: UninitializedPropertyAccessException) {
                    null
                }

            // Try Citizens UUID from frontend config
            config?.citizensUuid?.let { uuid ->
                val citizenNpc = CitizensAPI.getNPCRegistry().getByUniqueId(uuid)
                if (citizenNpc != null) return CitizensStoryNPC(citizenNpc)
            }

            // Try Citizens NPC ID from frontend config
            config?.citizensNpcId?.let { id ->
                val citizenNpc = CitizensAPI.getNPCRegistry().getById(id)
                if (citizenNpc != null) return CitizensStoryNPC(citizenNpc)
            }

            // Try unified StoryNPC registry (covers MythicMob-backed NPCs) by name
            if (plugin.isNpcRegistryReady) {
                plugin.npcRegistry.getByName(record.name)?.let { return it }
            }

            // Fall back to name match in Citizens
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
        val npc = resolveNPC(plugin, intent.characterId) ?: run {
            plugin.logger.warning("[FrontendIntent] NPC not found for characterId=${intent.characterId}")
            return
        }

        when (intent.primitive) {
            "set_target" -> {
                val targetId = intent.targetCharId ?: return
                val target = resolveTarget(plugin, targetId) as? org.bukkit.entity.Player
                if (target != null) {
                    npc.attack(target)
                } else {
                    plugin.logger.warning("[FrontendIntent] set_target: player target not found for $targetId")
                }
            }
            "navigate_to" -> {
                val world = Bukkit.getWorlds().firstOrNull() ?: return
                npc.navigateTo(Location(world, intent.x, intent.y, intent.z))
            }
            "look_at" -> {
                val entity = npc.entity as? LivingEntity ?: return
                val from = if (intent.useEyeLocation) entity.eyeLocation else entity.location
                val to: Location = if (intent.targetCharId != null) {
                    val target = resolveTarget(plugin, intent.targetCharId) as? LivingEntity ?: return
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
            }
            "attempt_hit" -> {
                val targetId = intent.targetCharId ?: return
                val target = resolveTarget(plugin, targetId) as? LivingEntity ?: return
                val attacker = npc.entity as? LivingEntity ?: return
                attacker.world.getNearbyEntities(attacker.location, 5.0, 5.0, 5.0)
                    .firstOrNull { it.uniqueId == target.uniqueId }
                    ?.let { attacker.attack(it as LivingEntity) }
            }
            "clear_target" -> {
                npc.cancelNavigation()
            }
        }
    }

    private fun resolveTarget(plugin: Story, targetId: String): Entity? {
        Bukkit.getPlayerExact(targetId)?.let { return it }
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.characterId == targetId) return player
        }
        return resolveNPC(plugin, targetId)?.entity
    }
}
