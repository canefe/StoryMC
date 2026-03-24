package com.canefe.story.npc

import com.canefe.story.api.StoryNPC
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Lightweight StoryNPC stub for cases where the NPC is not spawned in Citizens
 * but we still need a StoryNPC reference (e.g. for building Character objects).
 */
class StubStoryNPC(
    override val name: String,
    override val uniqueId: UUID = UUID.randomUUID(),
    override val id: Int = -1,
) : StoryNPC {
    override val entity: Entity? = null
    override val isSpawned = false
    override val location: Location? = null

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

    override fun clone(): StoryNPC = StubStoryNPC(name, uniqueId, id)

    override fun attack(target: Player) {}

    override fun stopAttacking(target: Player) {}

    override fun follow(target: Player) {}

    override fun stopFollowing() {}

    override val isFollowing = false

    override fun lookAt(target: Entity) {}

    override fun rotateTo(
        yaw: Float,
        pitch: Float,
    ) {}

    override fun sit(location: Location?) {}

    override fun stand() {}

    override val isSitting = false
    override val skinTexture: String? = null
    override val skinSignature: String? = null

    override fun setSkin(
        name: String,
        signature: String,
        texture: String,
    ) {}

    override fun <T : Any?> unwrap(type: Class<T>): T? = null
}
