package com.canefe.story.combat.resolution

import com.canefe.story.combat.Combatant
import com.canefe.story.combat.SwingDir
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure hit-geometry. No Bukkit dependencies beyond what [Combatant.eyeLocation]
 * already exposes. Easily unit-tested.
 *
 * Per spec §6:
 *   - OVERHEAD/LEFT/RIGHT → ±45° yaw cone, LEFT biased -20°, RIGHT +20°.
 *   - THRUST → 0.6-block-wide capsule, reach × 1.3, single target.
 */
object HitDetector {
    private const val CONE_HALF_DEG = 45.0
    private const val LEFT_BIAS_DEG = -20.0
    private const val RIGHT_BIAS_DEG = 20.0
    private const val THRUST_HALF_WIDTH = 0.6

    fun detect(
        attacker: Combatant,
        dir: SwingDir,
        weapon: WeaponClass,
        candidates: Collection<Combatant>,
    ): List<Combatant> {
        val origin = attacker.eyeLocation()
        val yaw = attacker.facingYaw().toDouble()
        return when (dir) {
            SwingDir.THRUST -> detectThrust(attacker, origin, yaw, weapon, candidates)
            SwingDir.OVERHEAD -> detectCone(attacker, origin, yaw, weapon, 0.0, candidates)
            SwingDir.LEFT -> detectCone(attacker, origin, yaw, weapon, LEFT_BIAS_DEG, candidates)
            SwingDir.RIGHT -> detectCone(attacker, origin, yaw, weapon, RIGHT_BIAS_DEG, candidates)
        }
    }

    private fun detectCone(
        attacker: Combatant,
        origin: org.bukkit.Location,
        yawDeg: Double,
        weapon: WeaponClass,
        biasDeg: Double,
        candidates: Collection<Combatant>,
    ): List<Combatant> {
        val coneCenter = yawDeg + biasDeg
        val reachSq = weapon.reach * weapon.reach
        return candidates.filter { c ->
            if (c === attacker) return@filter false
            val target = c.eyeLocation()
            if (target.world != origin.world) return@filter false
            val dx = target.x - origin.x
            val dz = target.z - origin.z
            val distSq = dx * dx + dz * dz
            if (distSq > reachSq) return@filter false
            val angleToTarget = bukkitYawTo(dx, dz)
            val delta = normalizeYawDelta(angleToTarget - coneCenter)
            abs(delta) <= CONE_HALF_DEG
        }
    }

    private fun detectThrust(
        attacker: Combatant,
        origin: org.bukkit.Location,
        yawDeg: Double,
        weapon: WeaponClass,
        candidates: Collection<Combatant>,
    ): List<Combatant> {
        val reach = weapon.thrustReach()
        val yawRad = Math.toRadians(yawDeg)
        // Bukkit yaw 0 = south (+Z), 90 = west (-X). Forward unit vector:
        val fx = -sin(yawRad)
        val fz = cos(yawRad)
        var nearest: Combatant? = null
        var nearestForward = Double.MAX_VALUE
        for (c in candidates) {
            if (c === attacker) continue
            val target = c.eyeLocation()
            if (target.world != origin.world) continue
            val dx = target.x - origin.x
            val dz = target.z - origin.z
            val forward = dx * fx + dz * fz
            if (forward <= 0.0 || forward > reach) continue
            val perpSq = (dx * dx + dz * dz) - forward * forward
            if (perpSq > THRUST_HALF_WIDTH * THRUST_HALF_WIDTH) continue
            if (forward < nearestForward) {
                nearestForward = forward
                nearest = c
            }
        }
        return listOfNotNull(nearest)
    }

    /** Bukkit yaw of the (dx,dz) vector. 0 = +Z, 90 = -X. */
    private fun bukkitYawTo(
        dx: Double,
        dz: Double,
    ): Double = Math.toDegrees(atan2(-dx, dz))

    /** Wrap to [-180, 180]. */
    private fun normalizeYawDelta(deg: Double): Double {
        var d = deg % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }
}
