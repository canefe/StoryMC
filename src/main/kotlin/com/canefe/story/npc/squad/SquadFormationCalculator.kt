package com.canefe.story.npc.squad

import com.canefe.story.api.squad.SquadFormation
import org.bukkit.Location
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

/**
 * Computes per-NPC slot positions around an anchor point given a formation,
 * member count, and facing direction.
 *
 * Slot index is stable: roster position N → formation slot N. Caller is
 * responsible for iterating members in roster order and pairing each with
 * the calculated location at the same index.
 *
 * Coordinate convention: "forward" is the +Z direction relative to the
 * commander's [facingYaw]; "right" is +X. Slots are computed in this local
 * frame, then rotated by the yaw and translated to the anchor.
 */
object SquadFormationCalculator {
    /** Spacing between adjacent slots (blocks). */
    private const val SPACING = 2.0

    fun slots(
        anchor: Location,
        facingYaw: Float,
        memberCount: Int,
        formation: SquadFormation,
    ): List<Location> {
        if (memberCount <= 0) return emptyList()
        val localOffsets = localOffsets(formation, memberCount)
        // Yaw 0 in Minecraft is south (+Z); +90 is west (-X). We want "forward"
        // in the local frame to map to the commander's look direction, so
        // rotate by -yaw (yaw is degrees, clockwise from south).
        val yawRad = Math.toRadians(facingYaw.toDouble())
        val cos = cos(-yawRad)
        val sin = sin(-yawRad)
        return localOffsets.map { (lx, lz) ->
            val rx = lx * cos - lz * sin
            val rz = lx * sin + lz * cos
            anchor.clone().add(Vector(rx, 0.0, rz))
        }
    }

    /** Local offsets in (right, forward) where +forward = direction commander faces. */
    private fun localOffsets(formation: SquadFormation, count: Int): List<Pair<Double, Double>> =
        when (formation) {
            SquadFormation.LINE -> line(count)
            SquadFormation.WEDGE -> wedge(count)
            SquadFormation.COLUMN -> column(count)
            SquadFormation.LOOSE -> loose(count)
            SquadFormation.SQUARE -> square(count)
            SquadFormation.CIRCLE -> circle(count)
            SquadFormation.ECHELON -> echelon(count)
        }

    /** N units shoulder-to-shoulder, perpendicular to facing. Center on anchor. */
    private fun line(count: Int): List<Pair<Double, Double>> {
        val centerOffset = (count - 1) / 2.0
        return (0 until count).map { i ->
            val right = (i - centerOffset) * SPACING
            right to 0.0
        }
    }

    /** Wedge ▲: leader at front (anchor), pairs flank back-and-out. */
    private fun wedge(count: Int): List<Pair<Double, Double>> {
        val out = mutableListOf<Pair<Double, Double>>()
        // First slot is the tip
        out.add(0.0 to 0.0)
        // Subsequent slots alternate left, right, left, right, getting wider+deeper
        var depth = 1
        var i = 1
        while (i < count) {
            // left
            out.add(-depth * SPACING to -depth * SPACING)
            i++
            if (i >= count) break
            // right
            out.add(depth * SPACING to -depth * SPACING)
            i++
            depth++
        }
        return out
    }

    /** Column: single file behind anchor. Slot 0 at anchor, others trailing. */
    private fun column(count: Int): List<Pair<Double, Double>> =
        (0 until count).map { i -> 0.0 to -i * SPACING }

    /** Loose: scatter in a small radius around anchor. Deterministic per slot. */
    private fun loose(count: Int): List<Pair<Double, Double>> =
        (0 until count).map { i ->
            // Spiral outward; each slot is at angle i*137.5° (golden angle) and radius sqrt(i).
            val angle = Math.toRadians(i * 137.5)
            val radius = SPACING * Math.sqrt(i.toDouble())
            radius * cos(angle) to radius * sin(angle)
        }

    /**
     * Square (hollow): members form the perimeter of a square centered on the
     * anchor, side length sized to fit count members evenly.
     * For 1 member: at center. For 2-3: line fallback. For ≥4: hollow square.
     */
    private fun square(count: Int): List<Pair<Double, Double>> {
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(0.0 to 0.0)
        if (count < 4) return line(count)

        // Members per side (rounded up so all fit). 4 members → 1 per side; 8 → 2; 12 → 3.
        val perSide = (count + 3) / 4
        val sideHalf = (perSide - 1) / 2.0 * SPACING
        val out = mutableListOf<Pair<Double, Double>>()
        // Place around perimeter: front, right, back, left, in order.
        // Front side (forward = +z), right-to-left along it.
        for (i in 0 until perSide) {
            val rightOffset = (i - (perSide - 1) / 2.0) * SPACING
            out.add(rightOffset to sideHalf)
            if (out.size >= count) return out
        }
        for (i in 0 until perSide) {
            val forwardOffset = sideHalf - (i + 1) * SPACING
            out.add(sideHalf to forwardOffset)
            if (out.size >= count) return out
        }
        for (i in 0 until perSide) {
            val rightOffset = sideHalf - (i + 1) * SPACING
            out.add(rightOffset to -sideHalf)
            if (out.size >= count) return out
        }
        for (i in 0 until perSide) {
            val forwardOffset = -sideHalf + (i + 1) * SPACING
            out.add(-sideHalf to forwardOffset)
            if (out.size >= count) return out
        }
        return out
    }

    /**
     * Circle: members evenly spaced on a circle centered on the anchor.
     * Radius scales with count so spacing stays roughly constant.
     */
    private fun circle(count: Int): List<Pair<Double, Double>> {
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(0.0 to 0.0)
        // Circumference = count * SPACING → radius = count * SPACING / (2π)
        val radius = (count * SPACING) / (2 * Math.PI)
        return (0 until count).map { i ->
            val angle = 2 * Math.PI * i / count
            (radius * sin(angle)) to (radius * cos(angle)) // first slot at front (+z)
        }
    }

    /**
     * Echelon (right): diagonal staircase. Each successive member is one step
     * right and one step back from the previous. Useful for advancing past a
     * flank without exposing a straight line of fire.
     */
    private fun echelon(count: Int): List<Pair<Double, Double>> =
        (0 until count).map { i -> (i * SPACING) to (-i * SPACING) }
}
