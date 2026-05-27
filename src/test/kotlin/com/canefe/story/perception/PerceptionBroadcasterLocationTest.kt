package com.canefe.story.perception

import com.canefe.story.testsupport.FakePerceptionContext
import com.canefe.storyproto.v1.AffordanceSightingStimulus
import com.canefe.storyproto.v1.LocationSightingStimulus
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.Vector
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Locks the proto-emit contract of [PerceptionBroadcaster.runOnce] for typed
 * sightings:
 *
 *   - Locations: ranged + FOV + LOS + light → emit `LocationSightingStimulus`
 *     with `targetLocationId` = instance name and `targetLocationDef` = template id.
 *   - Affordances: ambient (no FOV/LOS) → emit `AffordanceSightingStimulus`,
 *     NOT the legacy `PerceptionStimulusEvent`.
 *
 * The broadcaster receives a [PerceptionContext] so spatial logic can run
 * without a live Bukkit world. Snapshots use Bukkit `Location`/`Vector` for the
 * same shapes the production tickCharacters path uses — `World` is a Mockito
 * stub since `Location.distance` requires identity-comparable worlds.
 */
class PerceptionBroadcasterLocationTest {

    private val world: World = Mockito.mock(World::class.java)
    private val otherWorld: World = Mockito.mock(World::class.java)

    /** Standard perceiver-NPC fixture: at origin, full consciousness, facing +X. */
    private fun perceiver(
        charId: String = "npc_alice",
        sightRange: Double = 32.0,
        fovHalfDeg: Double = 180.0, // unrestricted FOV by default
    ) = NpcSnapshot(
        charId = charId,
        position = Location(world, 0.0, 64.0, 0.0),
        eye = Vector(0.0, 65.6, 0.0),
        facing = Vector(1.0, 0.0, 0.0),
        consciousness = 1.0,
        sightRange = sightRange,
        fovHalfDeg = fovHalfDeg,
    )

    @Test
    fun `NPC in range with LOS emits LocationSightingStimulus`() {
        val ctx = FakePerceptionContext(
            npcs = listOf(perceiver()),
            locations = listOf(
                LocationSnapshot(
                    instanceName = "Old Well",
                    templateId = "village_well",
                    center = Location(world, 10.0, 64.0, 0.0),
                    radius = 8.0,
                    tags = listOf("water_source", "public"),
                ),
            ),
            gameTimeMs = 1_700_000L,
        )

        PerceptionBroadcaster.runOnce(ctx)

        val locSightings = ctx.sentProtos.filterIsInstance<LocationSightingStimulus>()
        assertEquals(1, locSightings.size, "expected exactly one LocationSightingStimulus")
        val s = locSightings[0]
        assertEquals("npc_alice", s.perceiverCharId)
        assertEquals("Old Well", s.targetLocationId)
        assertEquals("village_well", s.targetLocationDef)
        assertEquals(listOf("water_source", "public"), s.tagsList)
        assertEquals(10.0f, s.x)
        assertEquals(64.0f, s.y)
        assertEquals(0.0f, s.z)
        assertEquals(1_700_000L, s.timestampMs)
        assertTrue(s.strength > 0f, "strength should be positive for in-range LOS")
    }

    @Test
    fun `location with blocked LOS emits no LocationSightingStimulus`() {
        val ctx = FakePerceptionContext(
            npcs = listOf(perceiver()),
            locations = listOf(
                LocationSnapshot(
                    instanceName = "Hidden Cave",
                    templateId = "cave",
                    center = Location(world, 5.0, 64.0, 0.0),
                    radius = 8.0,
                    tags = emptyList(),
                ),
            ),
            losFn = { _, _ -> false },
        )

        PerceptionBroadcaster.runOnce(ctx)

        assertTrue(
            ctx.sentProtos.none { it is LocationSightingStimulus },
            "LOS-blocked location must not emit LocationSightingStimulus",
        )
    }

    @Test
    fun `affordance in range emits AffordanceSightingStimulus not legacy event`() {
        val ctx = FakePerceptionContext(
            npcs = listOf(perceiver()),
            affordances = listOf(
                AffordanceSnapshot(
                    id = "aff_well_1",
                    position = Location(world, 5.0, 64.0, 0.0),
                    tags = listOf("water_source"),
                ),
            ),
            gameTimeMs = 1_700_000L,
        )

        PerceptionBroadcaster.runOnce(ctx)

        val affSightings = ctx.sentProtos.filterIsInstance<AffordanceSightingStimulus>()
        assertEquals(1, affSightings.size, "affordance branch should emit AffordanceSightingStimulus")
        val a = affSightings[0]
        assertEquals("npc_alice", a.perceiverCharId)
        assertEquals("aff_well_1", a.targetAffordanceId)
        assertEquals(listOf("water_source"), a.tagsList)
        assertEquals(5.0f, a.x)
        assertEquals(1_700_000L, a.timestampMs)
        assertTrue(a.strength > 0f)
    }

    @Test
    fun `affordance LOS-blocked still emits — affordances are ambient`() {
        // Locks observation 7113: affordances skip FOV/LOS and are always
        // ambient-perceived within range every 2s.
        val ctx = FakePerceptionContext(
            npcs = listOf(perceiver()),
            affordances = listOf(
                AffordanceSnapshot(
                    id = "aff_x",
                    position = Location(world, 5.0, 64.0, 0.0),
                    tags = listOf("seat"),
                ),
            ),
            losFn = { _, _ -> false }, // would block char/location, MUST NOT block affordances
        )

        PerceptionBroadcaster.runOnce(ctx)

        assertNotNull(
            ctx.sentProtos.filterIsInstance<AffordanceSightingStimulus>().firstOrNull(),
            "affordance should ambient-emit despite blocked LOS",
        )
    }

    @Test
    fun `out-of-range location emits nothing`() {
        val ctx = FakePerceptionContext(
            npcs = listOf(perceiver(sightRange = 8.0)),
            locations = listOf(
                LocationSnapshot(
                    instanceName = "Far Tower",
                    templateId = "tower",
                    center = Location(world, 100.0, 64.0, 0.0),
                    radius = 8.0,
                    tags = emptyList(),
                ),
            ),
        )

        PerceptionBroadcaster.runOnce(ctx)

        assertTrue(ctx.sentProtos.filterIsInstance<LocationSightingStimulus>().isEmpty())
    }

    @Test
    fun `cross-world location emits nothing`() {
        val ctx = FakePerceptionContext(
            npcs = listOf(perceiver()),
            locations = listOf(
                LocationSnapshot(
                    instanceName = "Nether Hub",
                    templateId = "hub",
                    center = Location(otherWorld, 0.0, 64.0, 0.0),
                    radius = 8.0,
                    tags = emptyList(),
                ),
            ),
        )

        PerceptionBroadcaster.runOnce(ctx)

        assertTrue(ctx.sentProtos.filterIsInstance<LocationSightingStimulus>().isEmpty())
    }
}
