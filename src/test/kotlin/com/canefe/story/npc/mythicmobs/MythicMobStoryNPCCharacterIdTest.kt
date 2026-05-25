package com.canefe.story.npc.mythicmobs

import com.canefe.story.api.StoryNPC
import org.junit.jupiter.api.Test
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import kotlin.test.assertNull

class MythicMobStoryNPCCharacterIdTest {
    // A minimal StoryNPC that does NOT override characterId, to prove the
    // interface default is null (Citizens/stub NPCs have no Mongo-id PDC).
    private class NoIdNpc : StoryNPC {
        override val name = "x"
        override val id = -1
        override val uniqueId = java.util.UUID.randomUUID()
        override val entity = null
        override val isSpawned = false
        override val location = null
        override fun navigateTo(location: Location) {}
        override fun navigateTo(
            location: Location,
            speedModifier: Float,
            range: Float,
            distanceMargin: Double,
        ) {}
        override fun navigateTo(entity: Entity) {}
        override fun navigateTo(
            entity: Entity,
            speedModifier: Float,
            range: Float,
            distanceMargin: Double,
        ) {}
        override fun cancelNavigation() {}
        override val isNavigating = false
        override fun spawn(location: Location) = false
        override fun despawn() = false
        override fun teleport(location: Location) {}
        override fun clone(): StoryNPC = this
        override fun attack(target: Player) {}
        override fun stopAttacking(target: Player) {}
        override fun follow(target: Player) {}
        override fun stopFollowing() {}
        override val isFollowing = false
        override fun lookAt(target: Entity) {}
        override fun rotateTo(yaw: Float, pitch: Float) {}
        override fun sit(location: Location?) {}
        override fun stand() {}
        override val isSitting = false
        override val skinTexture: String? = null
        override val skinSignature: String? = null
        override fun setSkin(name: String, signature: String, texture: String) {}
        override fun <T : Any?> unwrap(type: Class<T>): T? = null
    }

    @Test
    fun `interface default characterId is null`() {
        val npc: StoryNPC = NoIdNpc()
        assertNull(npc.characterId)
    }
}
