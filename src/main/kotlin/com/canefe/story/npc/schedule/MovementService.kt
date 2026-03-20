package com.canefe.story.npc.schedule

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.location.data.StoryLocation
import org.bukkit.Bukkit
import org.bukkit.Location

class MovementService(
    private val plugin: Story,
) {
    fun moveNPCToLocation(
        npc: StoryNPC,
        location: Location,
        callback: Runnable? = null,
    ) {
        val range = plugin.config.rangeBeforeTeleport
        val nearbyPlayers = plugin.npcUtils.getNearbyPlayers(npc, range, ignoreY = true)
        var shouldTeleport = nearbyPlayers.isEmpty()
        val npcLocation = npc.location ?: return
        val debugMessages = plugin.config.debugMessages

        if (npcLocation.world != location.world) {
            plugin.logger.warning("NPC ${npc.name} is in a different world, cannot move.")
            return
        }

        if (shouldTeleport) {
            val nearbyPlayersInTargetLocation = plugin.npcUtils.getNearbyPlayers(location, range, ignoreY = true)
            if (nearbyPlayersInTargetLocation.isNotEmpty()) {
                shouldTeleport = false
            }
        }

        npc.stand()

        if (shouldTeleport) {
            if (debugMessages) {
                plugin.logger.info("Teleporting ${npc.name} to $location")
            }
            npc.teleport(location)
            callback?.run()
        } else {
            if (debugMessages) {
                plugin.logger.info("Walking ${npc.name} to $location")
            }
            val teleportOnFail =
                Runnable {
                    if (plugin.config.teleportOnFail) {
                        if (debugMessages) {
                            plugin.logger.info("Walking failed for ${npc.name}, teleporting instead")
                        }
                        npc.teleport(location)
                        callback?.run()
                    }
                }
            plugin.npcManager.walkToLocation(npc, location, 1.0, 1f, 120, callback, teleportOnFail)
        }
    }

    fun moveNPCToLocation(
        npc: StoryNPC,
        location: StoryLocation,
        callback: Runnable? = null,
    ) {
        npc.stand()
        val bukkitLocation = location.bukkitLocation
        if (bukkitLocation == null) {
            plugin.logger.warning("No Bukkit location found for ${location.name}")
            return
        }
        moveNPCToLocation(npc, bukkitLocation, callback)
    }

    fun findNearbyGround(
        location: Location,
        maxBlocksCheck: Int = 5,
    ): Location? {
        val world = location.world
        val x = location.x
        val z = location.z
        val startY = location.y.toInt()

        val exactBlock = world.getBlockAt(x.toInt(), startY - 1, z.toInt())
        val blockAtFeet = world.getBlockAt(x.toInt(), startY, z.toInt())
        val blockAtHead = world.getBlockAt(x.toInt(), startY + 1, z.toInt())

        if (exactBlock.type.isSolid && !blockAtFeet.type.isSolid && !blockAtHead.type.isSolid) {
            return location.clone()
        }

        for (yOffset in 1..maxBlocksCheck) {
            val y = startY - yOffset
            if (y <= 0) continue

            val block = world.getBlockAt(x.toInt(), y - 1, z.toInt())
            val blockAbove = world.getBlockAt(x.toInt(), y, z.toInt())
            val blockAboveTwo = world.getBlockAt(x.toInt(), y + 1, z.toInt())

            if (block.type.isSolid && !blockAbove.type.isSolid && !blockAboveTwo.type.isSolid) {
                return Location(world, x, y.toDouble(), z, location.yaw, location.pitch)
            }
        }

        for (yOffset in 1..maxBlocksCheck) {
            val y = startY + yOffset
            if (y >= world.maxHeight - 1) continue

            val block = world.getBlockAt(x.toInt(), y - 1, z.toInt())
            val blockAbove = world.getBlockAt(x.toInt(), y, z.toInt())
            val blockAboveTwo = world.getBlockAt(x.toInt(), y + 1, z.toInt())

            if (block.type.isSolid && !blockAbove.type.isSolid && !blockAboveTwo.type.isSolid) {
                return Location(world, x, y.toDouble(), z, location.yaw, location.pitch)
            }
        }

        return null
    }

    fun moveNearbyNPCsToLocation(
        targetLocation: Location,
        proximityRadius: Double = 50.0,
        action: String = "idle",
        excludeNPCs: List<String> = emptyList(),
        onlyIncludeNPCs: List<String> = emptyList(),
        actionExecutor: (StoryNPC, String) -> Unit,
    ) {
        val allNPCs = mutableListOf<StoryNPC>()

        for (player in plugin.server.onlinePlayers) {
            if (player.location.world == targetLocation.world &&
                player.location.distance(targetLocation) <= proximityRadius * 2
            ) {
                allNPCs.addAll(plugin.npcUtils.getNearbyNPCs(player, proximityRadius))
            }
        }

        val nearbyNPCs =
            allNPCs
                .distinct()
                .filter { npc ->
                    val npcLocation = npc.location
                    npcLocation != null &&
                        npcLocation.world == targetLocation.world &&
                        npcLocation.distance(targetLocation) <= proximityRadius
                }.filter { npc ->
                    val npcName = npc.name.lowercase()
                    val shouldExclude = excludeNPCs.any { it.lowercase() == npcName }
                    val shouldInclude = onlyIncludeNPCs.isEmpty() || onlyIncludeNPCs.any { it.lowercase() == npcName }
                    !shouldExclude && shouldInclude && !plugin.npcManager.isNPCDisabled(npc)
                }

        if (nearbyNPCs.isEmpty()) {
            plugin.logger.info("No NPCs found within $proximityRadius blocks of target location")
            return
        }

        plugin.logger.info(
            "Moving ${nearbyNPCs.size} NPCs to target location: ${targetLocation.x}, ${targetLocation.y}, ${targetLocation.z}",
        )

        nearbyNPCs.forEachIndexed { index, npc ->
            val random =
                java.util.concurrent.ThreadLocalRandom
                    .current()
            val offsetRadius = 3.0
            val randomOffsetX = random.nextDouble(-offsetRadius, offsetRadius)
            val randomOffsetZ = random.nextDouble(-offsetRadius, offsetRadius)

            val individualTargetLocation =
                Location(
                    targetLocation.world,
                    targetLocation.x + randomOffsetX,
                    targetLocation.y,
                    targetLocation.z + randomOffsetZ,
                    random.nextFloat() * 360f,
                    targetLocation.pitch,
                )

            val safeLocation = findNearbyGround(individualTargetLocation, maxBlocksCheck = 5)
            val finalLocation = safeLocation ?: targetLocation

            val moveCallback =
                Runnable {
                    actionExecutor(npc, action)
                    if (plugin.config.debugMessages) {
                        plugin.logger.info("${npc.name} reached target location and executed action: $action")
                    }
                }

            val delay = (index * 5L)
            Bukkit.getScheduler().runTaskLater(
                plugin,
                Runnable { moveNPCToLocation(npc, finalLocation, moveCallback) },
                delay,
            )
        }
    }
}
