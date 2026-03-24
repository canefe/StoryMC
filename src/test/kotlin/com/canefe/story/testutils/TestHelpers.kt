// kotlin
package com.canefe.story.testutils

import com.canefe.story.api.StoryNPC
import io.mockk.every
import io.mockk.mockk
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.mockbukkit.mockbukkit.ServerMock

fun makeStoryNpc(name: String = "Guard"): StoryNPC =
    com.canefe.story.npc
        .StubStoryNPC(name)

fun makeNpc(name: String = "Guard"): NPC {
    val npc = mockk<NPC>()
    val entity = mockk<Entity>()
    val navigator = mockk<net.citizensnpcs.api.ai.Navigator>()
    every { npc.navigator } returns navigator
    every { navigator.cancelNavigation() } returns Unit

    val world = mockk<World>()
    val loc = Location(world, 0.0, 64.0, 0.0)
    every { world.name } returns "world"

    every { npc.name } returns name
    every { npc.entity } returns entity
    // generate random unique ID (must be stable for hashCode)
    val uuid = java.util.UUID.randomUUID()
    val npcId = (1..1000).random()
    every { npc.uniqueId } returns uuid
    every { npc.id } returns npcId
    every { npc.isSpawned } returns true
    every { entity.location } returns loc
    every { entity.uniqueId } returns uuid
    every { entity.name } returns npc.name
    return npc
}

fun waitUntil(
    server: ServerMock,
    timeoutTicks: Int = 1800,
    condition: () -> Boolean,
) {
    repeat(timeoutTicks) {
        if (condition()) return
        server.scheduler.performOneTick()
    }
    error("Condition not met in $timeoutTicks ticks")
}
