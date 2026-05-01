package com.canefe.story.command.story

import com.canefe.story.Story
import dev.jorel.commandapi.CommandAPICommand
import dev.jorel.commandapi.executors.CommandExecutor
import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import kotlin.math.*

class DebugCommand(private val plugin: Story) {

    // Active debug tasks per player: playerName -> taskId
    private val activeTasks = mutableMapOf<String, Int>()

    fun getCommand(): CommandAPICommand =
        CommandAPICommand("debug")
            .withPermission("story.admin")
            .withSubcommand(getFovCommand())

    private fun getFovCommand(): CommandAPICommand =
        CommandAPICommand("fov")
            .withPermission("story.admin")
            .executes(CommandExecutor { sender, _ ->
                val player = sender as? Player ?: run {
                    sender.sendMessage("Only players can use this command.")
                    return@CommandExecutor
                }

                // Toggle off if already running
                activeTasks.remove(player.name)?.let { existing ->
                    plugin.server.scheduler.cancelTask(existing)
                    player.sendMessage("§7[Debug] FOV visualisation stopped.")
                    return@CommandExecutor
                }

                player.sendMessage("§7[Debug] FOV visualisation started. Run again to stop.")

                val task = object : BukkitRunnable() {
                    override fun run() {
                        if (!player.isOnline) {
                            cancel()
                            activeTasks.remove(player.name)
                            return
                        }
                        if (!plugin.isNpcRegistryReady) return

                        for (npc in plugin.npcRegistry.all()) {
                            val entity = npc.entity as? LivingEntity ?: continue
                            if (entity.world != player.world) continue
                            val charId = try {
                                plugin.characterRegistry.getCharacterIdForNPC(npc)
                            } catch (_: Exception) { null } ?: continue

                            val sightRange = plugin.characterStatsCache.effectiveSightRange(charId)
                            val fovHalfDeg = plugin.characterStatsCache.effectiveFov(charId)
                            val eyeLoc = entity.eyeLocation

                            drawFovCone(player, eyeLoc.toVector(), eyeLoc.direction, sightRange, fovHalfDeg, eyeLoc.world!!)
                            drawRangeArc(player, eyeLoc, sightRange)
                        }
                    }
                }

                val taskId = task.runTaskTimer(plugin, 0L, 10L).taskId
                activeTasks[player.name] = taskId
            })

    /**
     * Draws the two FOV boundary rays + a forward ray with green dots,
     * and dots at regular intervals across the cone arc at max range.
     */
    private fun drawFovCone(
        player: Player,
        eyeVec: Vector,
        facing: Vector,
        sightRange: Double,
        fovHalfDeg: Double,
        world: org.bukkit.World,
    ) {
        val fovRad = Math.toRadians(fovHalfDeg)

        // Build an orthonormal basis: right and up relative to facing
        val globalUp = Vector(0.0, 1.0, 0.0)
        val right = facing.clone().crossProduct(globalUp).normalize()
        val up = right.clone().crossProduct(facing).normalize()

        // Draw forward ray (green)
        drawRay(player, world, eyeVec, facing, sightRange, Color.LIME, 0.6f)

        // Draw cone boundary rays: sweep azimuth 0..360 at fovHalfDeg polar angle
        val steps = 36
        for (i in 0 until steps) {
            val azimuth = (2 * Math.PI * i / steps)
            // Rotate the cone boundary vector around the facing axis
            val coneDir = facing.clone()
                .multiply(cos(fovRad))
                .add(right.clone().multiply(sin(fovRad) * cos(azimuth)))
                .add(up.clone().multiply(sin(fovRad) * sin(azimuth)))
                .normalize()

            // Dot at range boundary
            val tip = eyeVec.clone().add(coneDir.multiply(sightRange))
            spawnDust(player, world, tip, Color.YELLOW, 0.7f)
        }

        // Two vertical-plane boundary rays at ±fovHalf in the yaw plane (red)
        val yawRad = Math.toRadians(facing.clone().let {
            Math.toDegrees(atan2(-it.x, it.z))
        })
        for (side in listOf(-1.0, 1.0)) {
            val boundaryYaw = yawRad + side * fovRad
            val bDir = Vector(
                -sin(boundaryYaw) * cos(Math.toRadians(facing.let {
                    Math.toDegrees(asin(-it.y.coerceIn(-1.0, 1.0)))
                })),
                facing.y,
                cos(boundaryYaw) * cos(Math.toRadians(facing.let {
                    Math.toDegrees(asin(-it.y.coerceIn(-1.0, 1.0)))
                }))
            ).normalize()
            drawRay(player, world, eyeVec, bDir, sightRange, Color.RED, 0.5f)
        }
    }

    private fun drawRay(
        player: Player,
        world: org.bukkit.World,
        origin: Vector,
        dir: Vector,
        length: Double,
        color: Color,
        size: Float,
        dotSpacing: Double = 0.8,
    ) {
        val steps = (length / dotSpacing).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val t = i * dotSpacing
            if (t > length) break
            val point = origin.clone().add(dir.clone().multiply(t))
            spawnDust(player, world, point, color, size.toFloat())
        }
    }

    /** Draws a horizontal circle at eye height to mark the sight range boundary. */
    private fun drawRangeArc(player: Player, eyeLoc: org.bukkit.Location, sightRange: Double) {
        val steps = 64
        for (i in 0 until steps) {
            val angle = 2 * Math.PI * i / steps
            val x = eyeLoc.x + sightRange * cos(angle)
            val z = eyeLoc.z + sightRange * sin(angle)
            val point = Vector(x, eyeLoc.y, z)
            spawnDust(player, eyeLoc.world!!, point, Color.AQUA, 0.5f)
        }
    }

    private fun spawnDust(player: Player, world: org.bukkit.World, pos: Vector, color: Color, size: Float) {
        val loc = pos.toLocation(world)
        if (loc.distanceSquared(player.location) > 1024) return // don't render > 32 blocks away
        world.spawnParticle(
            Particle.DUST,
            loc,
            1,
            0.0, 0.0, 0.0,
            0.0,
            Particle.DustOptions(color, size),
        )
    }
}
