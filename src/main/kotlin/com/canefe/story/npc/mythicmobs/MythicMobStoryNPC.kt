package com.canefe.story.npc.mythicmobs

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import io.lumine.mythic.api.adapters.AbstractEntity
import io.lumine.mythic.api.adapters.AbstractLocation
import io.lumine.mythic.api.skills.SkillCaster
import io.lumine.mythic.api.skills.SkillTrigger
import io.lumine.mythic.bukkit.BukkitAdapter
import io.lumine.mythic.bukkit.MythicBukkit
import io.lumine.mythic.core.config.MythicLineConfigImpl
import io.lumine.mythic.core.mobs.ActiveMob
import io.lumine.mythic.core.skills.SkillExecutor
import io.lumine.mythic.core.skills.SkillMetadataImpl
import io.lumine.mythic.core.skills.mechanics.DisguiseMechanic
import io.lumine.mythic.core.skills.mechanics.GoToMechanic
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID

/**
 * StoryNPC adapter for MythicMobs entities.
 *
 * Wraps an in-world MythicMob entity. Navigation uses GoToMechanic, attack casts
 * a Mythic skill, follow uses runaigoalselector, skin via DisguiseMechanic.
 *
 * The [stableUniqueId] is minted at construction time so the registry can keep
 * a consistent key across respawns (Bukkit entity uniqueId changes on respawn).
 */
open class MythicMobStoryNPC(
    private var backingEntity: Entity,
    private val displayName: String,
    val internalName: String,
    private val stableUniqueId: UUID = UUID.randomUUID(),
    /** Skill name cast on [attack]. Override per-NPC if needed. */
    private val attackSkillName: String = "StoryAttack",
) : StoryNPC {
    private var followingTarget: Player? = null

    /**
     * Backing field for the [clientFacingUuid] override. The factory sets this
     * to the LibsDisguises fake-player UUID after applying the disguise.
     */
    var disguiseUuid: UUID? = null

    override val name: String get() = displayName
    override val id: Int get() = backingEntity.entityId
    override val uniqueId: UUID get() = stableUniqueId
    override val entity: Entity? get() = backingEntity
    override val isSpawned: Boolean get() = !backingEntity.isDead && backingEntity.isValid
    override val location: Location? get() = backingEntity.location

    override val clientFacingUuid: UUID? get() = disguiseUuid ?: backingEntity.uniqueId

    override val characterId: String?
        get() = backingEntity.persistentDataContainer
            .get(MythicMobNPCKeys.CHARACTER_ID, MythicMobNPCKeys.STRING)

    private val mm get() = MythicBukkit.inst()

    private fun activeMob(): ActiveMob? =
        mm.mobManager.getActiveMob(backingEntity.uniqueId).orElse(null)

    private fun runMechanicAt(
        location: Location,
        block: (caster: SkillCaster, trigger: AbstractEntity, abstLoc: AbstractLocation, executor: SkillExecutor) -> Unit,
    ) {
        val active = activeMob() ?: return
        val executor = mm.skillManager as SkillExecutor
        val abstLoc = BukkitAdapter.adapt(location)
        block(active as SkillCaster, active.entity, abstLoc, executor)
    }

    // -- Navigation --

    override fun navigateTo(location: Location) = navigateTo(location, 1.0f, 100f, 1.0)

    override fun navigateTo(
        location: Location,
        speedModifier: Float,
        range: Float,
        distanceMargin: Double,
    ) {
        runMechanicAt(location) { caster, trigger, abstLoc, executor ->
            val mechanic = object : GoToMechanic(executor, File(""), "storynpc_goto", MythicLineConfigImpl("")) {}
            val metadata =
                SkillMetadataImpl(
                    SkillTrigger.create("API"),
                    caster,
                    trigger,
                    abstLoc,
                    mutableListOf<AbstractEntity>(),
                    mutableListOf<AbstractLocation>(abstLoc),
                    1f,
                )
            mechanic.execute(metadata)
        }
    }

    override fun navigateTo(entity: Entity) = navigateTo(entity.location, 1.0f, 100f, 1.0)

    override fun navigateTo(
        entity: Entity,
        speedModifier: Float,
        range: Float,
        distanceMargin: Double,
    ) = navigateTo(entity.location, speedModifier, range, distanceMargin)

    override fun cancelNavigation() {
        // MythicMobs path cancellation: clear current AI nav goal.
        // Best-effort — sets a no-op nav by re-targeting current location.
        val current = backingEntity.location
        navigateTo(current, 0f, 0f, 0.5)
    }

    override val isNavigating: Boolean
        get() {
            val living = backingEntity as? LivingEntity ?: return false
            // Mojang AI doesn't expose a clean "isPathfinding" flag through Bukkit;
            // approximate via velocity magnitude in the horizontal plane.
            val v = living.velocity
            return (v.x * v.x + v.z * v.z) > 0.001
        }

    // -- Lifecycle --

    override fun spawn(location: Location): Boolean {
        // MythicMobStoryNPC wraps an already-spawned entity; spawning is the factory's job.
        return isSpawned
    }

    override fun despawn(): Boolean {
        val active = activeMob()
        if (active != null) {
            active.despawn()
            return true
        }
        backingEntity.remove()
        return true
    }

    override fun teleport(location: Location) {
        backingEntity.teleport(location)
    }

    override fun clone(): StoryNPC =
        MythicMobStoryNPC(backingEntity, displayName, internalName, UUID.randomUUID(), attackSkillName)

    // -- Combat --

    override fun attack(target: Player) {
        val skill = mm.skillManager.getSkill(attackSkillName).orElse(null) ?: return
        val active = activeMob() ?: return
        val abstLoc = BukkitAdapter.adapt(target.location)
        val targetAbst: AbstractEntity = BukkitAdapter.adapt(target)
        val targets = mutableListOf<AbstractEntity>(targetAbst)
        val metadata =
            SkillMetadataImpl(
                SkillTrigger.create("API"),
                active as SkillCaster,
                targetAbst,
                abstLoc,
                targets,
                mutableListOf<AbstractLocation>(),
                1f,
            )
        skill.execute(metadata)
    }

    override fun setTarget(target: Player) {
        val active = activeMob() ?: return
        active.setTarget(BukkitAdapter.adapt(target))
    }

    override fun stopAttacking(target: Player) {
        // No persistent target list on the Mythic side for one-shot skill casts.
        // If StoryAttack ever becomes a sustained aura, cancel it here.
    }

    // -- Following --

    override fun follow(target: Player) {
        followingTarget = target
        // MythicMobs has no built-in "follow this entity" goal selector exposed here;
        // route through the shared tracker which polls and re-targets each second.
        Story.instance.npcFollowTracker.follow(this, target)
    }

    override fun stopFollowing() {
        followingTarget = null
        Story.instance.npcFollowTracker.cancel(stableUniqueId)
        cancelNavigation()
    }

    override val isFollowing: Boolean get() = followingTarget != null

    // -- Rotation --

    override fun lookAt(target: Entity) {
        val living = backingEntity as? LivingEntity ?: return
        val direction = target.location.toVector().subtract(living.location.toVector())
        living.teleport(living.location.setDirection(direction))
    }

    /**
     * Persistent head-lock onto [target] via the MythicMobs `look` mechanic.
     * Casts the `StorySocializeLookAt` skill with [target] as the @Target.
     * The skill's `duration` field controls how long the lock holds (currently
     * ~7s in StorySocialize.yml); afterwards MythicMobs releases the head and
     * normal AI rotation resumes. Falls back to the one-shot [lookAt] if the
     * skill isn't configured.
     */
    fun lookAtViaSkill(target: Entity) {
        val skill = mm.skillManager.getSkill("StorySocializeLookAt").orElse(null) ?: run {
            lookAt(target)
            return
        }
        val active = activeMob() ?: return
        val abstLoc = BukkitAdapter.adapt(target.location)
        val targetAbst: AbstractEntity = BukkitAdapter.adapt(target)
        val targets = mutableListOf<AbstractEntity>(targetAbst)
        val metadata =
            SkillMetadataImpl(
                SkillTrigger.create("API"),
                active as SkillCaster,
                targetAbst,
                abstLoc,
                targets,
                mutableListOf<AbstractLocation>(),
                1f,
            )
        skill.execute(metadata)
    }

    override fun rotateTo(
        yaw: Float,
        pitch: Float,
    ) {
        if (backingEntity !is LivingEntity) return
        val loc = backingEntity.location.clone()
        loc.yaw = yaw
        loc.pitch = pitch
        backingEntity.teleport(loc)
    }

    // -- Pose --

    override fun sit(location: Location?) {
        // MythicMobs doesn't expose Citizens-style sit traits.
        // Future: spawn an invisible armor stand vehicle, or use a model engine pose.
    }

    override fun stand() {
        // No-op (see sit).
    }

    override val isSitting: Boolean get() = false

    // -- Skin --

    override val skinTexture: String? get() = null
    override val skinSignature: String? get() = null

    override fun setSkin(
        name: String,
        signature: String,
        texture: String,
    ) {
        // Apply via DisguiseMechanic — caller passes a player name to mimic.
        val active = activeMob() ?: return
        val executor = mm.skillManager as SkillExecutor
        val mlc = MythicLineConfigImpl("type=player <disguise_name>")
        val mechanic =
            object : DisguiseMechanic(executor, File(""), "storynpc_setskin", mlc) {
                init {
                    this.disguise =
                        mlc.getPlaceholderString(
                            arrayOf("type", "disguise", "d"),
                            "player $name setCustomName \"\" setCustomNameVisible false",
                        )
                }
            }
        val abstLoc = BukkitAdapter.adapt(backingEntity.location)
        val metadata =
            SkillMetadataImpl(
                SkillTrigger.create("API"),
                active as SkillCaster,
                active.entity,
                abstLoc,
                mutableListOf<AbstractEntity>(),
                mutableListOf<AbstractLocation>(abstLoc),
                1f,
            )
        metadata.setMetadata("disguise_name", name)
        mechanic.execute(metadata)
    }

    // -- Signal --

    override fun signal(name: String, source: Entity?) {
        val active = activeMob() ?: return
        val src = source?.let { BukkitAdapter.adapt(it) } ?: active.entity
        active.signalMob(src, name)
    }

    // -- Animation --

    override fun playActionAnimation(actionKey: String) {
        // Route through the same signal bus the squad/combat/AI presentation uses
        // (~onSignal:AI_Action_<Key> in the mob YAML drives holo/particles/anim).
        // No source — the action originates from the NPC itself. Key is PascalCase
        // on the wire to match the existing AI_/Squad signal convention.
        val name = actionKey.replaceFirstChar { it.uppercase() }
        signal("AI_Action_$name")
    }

    // -- Source access --

    @Suppress("UNCHECKED_CAST")
    override fun <T> unwrap(type: Class<T>): T? =
        when {
            type.isInstance(backingEntity) -> backingEntity as T
            type == ActiveMob::class.java -> activeMob() as T?
            else -> null
        }

    /** Re-bind the backing entity after a respawn. The stable uniqueId is preserved. */
    fun rebind(entity: Entity) {
        backingEntity = entity
    }

    override fun equals(other: Any?): Boolean = other is MythicMobStoryNPC && stableUniqueId == other.stableUniqueId

    override fun hashCode(): Int = stableUniqueId.hashCode()

    override fun toString(): String = "MythicMobStoryNPC(name=$displayName, internal=$internalName)"
}
