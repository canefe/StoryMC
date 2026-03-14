package com.canefe.story.npc

import com.canefe.story.api.StoryNPC
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.CurrentLocation
import net.citizensnpcs.trait.EntityPoseTrait
import net.citizensnpcs.trait.FollowTrait
import net.citizensnpcs.trait.RotationTrait
import net.citizensnpcs.trait.SitTrait
import net.citizensnpcs.trait.SkinTrait
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.mcmonkey.sentinel.SentinelTrait
import java.util.UUID

class CitizensStoryNPC(
    private val npc: NPC,
) : StoryNPC {
    override val name: String get() = npc.name
    override val id: Int get() = npc.id
    override val uniqueId: UUID get() = npc.uniqueId
    override val entity: Entity? get() = npc.entity
    override val isSpawned: Boolean get() = npc.isSpawned

    override val location: Location?
        get() =
            npc.entity?.location
                ?: npc.getOrAddTrait(CurrentLocation::class.java).location

    // -- Navigation --

    override fun navigateTo(location: Location) {
        npc.navigator.setTarget(location)
    }

    override fun navigateTo(
        location: Location,
        speedModifier: Float,
        range: Float,
        distanceMargin: Double,
    ) {
        npc.navigator.defaultParameters
            .speedModifier(speedModifier)
            .range(range)
            .distanceMargin(distanceMargin)
        npc.navigator.setTarget(location)
    }

    override fun navigateTo(entity: Entity) {
        npc.navigator.setTarget(entity, false)
    }

    override fun navigateTo(
        entity: Entity,
        speedModifier: Float,
        range: Float,
        distanceMargin: Double,
    ) {
        npc.navigator.defaultParameters
            .speedModifier(speedModifier)
            .range(range)
            .distanceMargin(distanceMargin)
        npc.navigator.setTarget(entity, false)
    }

    override fun cancelNavigation() {
        npc.navigator.cancelNavigation()
    }

    override val isNavigating: Boolean get() = npc.navigator.isNavigating

    // -- Lifecycle --

    override fun spawn(location: Location): Boolean = npc.spawn(location)

    override fun despawn(): Boolean = npc.despawn()

    override fun teleport(location: Location) {
        npc.teleport(location, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN)
    }

    override fun clone(): StoryNPC = CitizensStoryNPC(npc.clone())

    // -- Combat --

    override fun attack(target: Player) {
        npc.getOrAddTrait(SentinelTrait::class.java).addTarget("player:${target.name}")
    }

    override fun stopAttacking(target: Player) {
        val sentinel = npc.getOrAddTrait(SentinelTrait::class.java)
        sentinel.removeTarget("player:${target.name}")
        sentinel.tryUpdateChaseTarget(null)
    }

    // -- Following --

    override fun follow(target: Player) {
        npc.getOrAddTrait(SentinelTrait::class.java).guarding = target.uniqueId
        npc.getOrAddTrait(SentinelTrait::class.java).guardDistanceMinimum = 3.0
    }

    override fun stopFollowing() {
        npc.getOrAddTrait(SentinelTrait::class.java).guarding = null
        npc.getOrAddTrait(FollowTrait::class.java).follow(null)
    }

    override val isFollowing: Boolean
        get() =
            npc.getOrAddTrait(SentinelTrait::class.java).guarding != null ||
                npc.getOrAddTrait(FollowTrait::class.java).isActive

    // -- Rotation --

    override fun lookAt(target: Entity) {
        npc.getOrAddTrait(RotationTrait::class.java).physicalSession.rotateToFace(target)
    }

    override fun rotateTo(
        yaw: Float,
        pitch: Float,
    ) {
        npc.getOrAddTrait(RotationTrait::class.java).physicalSession.rotateToHave(yaw, pitch)
    }

    // -- Pose --

    override fun sit(location: Location?) {
        npc.getOrAddTrait(EntityPoseTrait::class.java).pose = EntityPoseTrait.EntityPose.SITTING
        npc.getOrAddTrait(SitTrait::class.java).setSitting(location ?: npc.entity?.location)
    }

    override fun stand() {
        npc.getOrAddTrait(EntityPoseTrait::class.java).pose = EntityPoseTrait.EntityPose.STANDING
        npc.getOrAddTrait(SitTrait::class.java).setSitting(null)
    }

    override val isSitting: Boolean
        get() = npc.getOrAddTrait(SitTrait::class.java).isSitting

    // -- Skin --

    override val skinTexture: String?
        get() = npc.getOrAddTrait(SkinTrait::class.java).texture

    override val skinSignature: String?
        get() = npc.getOrAddTrait(SkinTrait::class.java).signature

    override fun setSkin(
        name: String,
        signature: String,
        texture: String,
    ) {
        npc.getOrAddTrait(SkinTrait::class.java).setSkinPersistent(name, signature, texture)
    }

    // -- Source access --

    @Suppress("UNCHECKED_CAST")
    override fun <T> unwrap(type: Class<T>): T? = if (type.isInstance(npc)) npc as T else null

    override fun equals(other: Any?): Boolean = other is CitizensStoryNPC && npc.uniqueId == other.npc.uniqueId

    override fun hashCode(): Int = npc.uniqueId.hashCode()

    override fun toString(): String = "CitizensStoryNPC(name=$name, id=$id)"
}
