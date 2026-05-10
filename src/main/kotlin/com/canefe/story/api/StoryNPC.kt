package com.canefe.story.api

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Story's own NPC abstraction, decoupled from Citizens.
 * Implementations wrap Citizens NPCs, MythicMobs, or any future NPC source.
 */
interface StoryNPC {
    /** Display name shown in conversations and UI. */
    val name: String

    /** Integer ID (Citizens NPC ID, entity ID for non-Citizens). */
    val id: Int

    /** Unique identifier for caching, deduplication, and mapping. */
    val uniqueId: UUID

    /** The underlying Bukkit entity, or null if not spawned. */
    val entity: Entity?

    /**
     * UUID the client should use to find this NPC's visible entity for bubbles,
     * actions, and positional audio.
     *
     * Defaults to [entity]'s uniqueId. Override when the NPC is rendered as a
     * different entity client-side (e.g. a MythicMob disguised as a player via
     * LibsDisguises spawns a fake player packet entity with its own UUID).
     */
    val clientFacingUuid: UUID? get() = entity?.uniqueId

    /** Whether this NPC is currently spawned in the world. */
    val isSpawned: Boolean

    /** Current location of the NPC, or null if not spawned. */
    val location: Location?

    // -- Navigation --

    /** Navigate toward a location with default parameters. */
    fun navigateTo(location: Location)

    /** Navigate toward a location with speed, range, and distance margin. */
    fun navigateTo(
        location: Location,
        speedModifier: Float = 1.0f,
        range: Float = 100f,
        distanceMargin: Double = 1.0,
    )

    /** Navigate toward an entity. */
    fun navigateTo(entity: Entity)

    /** Navigate toward an entity with speed, range, and distance margin. */
    fun navigateTo(
        entity: Entity,
        speedModifier: Float = 1.0f,
        range: Float = 100f,
        distanceMargin: Double = 1.0,
    )

    /** Cancel any active navigation. */
    fun cancelNavigation()

    /** Whether the NPC is currently navigating. */
    val isNavigating: Boolean

    // -- Lifecycle --

    /** Spawn the NPC at a location. Returns true if successful. */
    fun spawn(location: Location): Boolean

    /** Despawn the NPC. Returns true if successful. */
    fun despawn(): Boolean

    /** Teleport the NPC to a location. */
    fun teleport(location: Location)

    /** Create a copy of this NPC with a new ID. */
    fun clone(): StoryNPC

    // -- Combat --

    /** Make this NPC attack a player. */
    fun attack(target: Player)

    /** Stop attacking a specific player. */
    fun stopAttacking(target: Player)

    /**
     * Set this NPC's combat target without triggering an attack skill.
     *
     * Default no-op for backends without a native targeting concept (e.g. Citizens
     * routes hostility through Sentinel's target list, not a single live target).
     * MythicMobs sets the underlying Mob's threat target so the AI engages naturally.
     */
    fun setTarget(target: Player) {}

    // -- Following --

    /** Make this NPC follow a player. */
    fun follow(target: Player)

    /** Stop following. */
    fun stopFollowing()

    /** Whether this NPC is currently following someone. */
    val isFollowing: Boolean

    // -- Rotation --

    /** Make this NPC look at an entity. */
    fun lookAt(target: Entity)

    /** Rotate NPC to a specific yaw and pitch. */
    fun rotateTo(
        yaw: Float,
        pitch: Float,
    )

    // -- Pose --

    /** Make this NPC sit at its current or given location. */
    fun sit(location: Location? = null)

    /** Make this NPC stand up. */
    fun stand()

    /** Whether this NPC is currently sitting. */
    val isSitting: Boolean

    // -- Skin --

    /** The skin texture data, or null if none. */
    val skinTexture: String?

    /** The skin signature data, or null if none. */
    val skinSignature: String?

    /** Set the skin persistently. */
    fun setSkin(
        name: String,
        signature: String,
        texture: String,
    )

    // -- Signal --

    /**
     * Send a Mythic-style signal to this NPC (no-op for backends without one).
     *
     * The MythicMobs adapter dispatches to `ActiveMob.signalMob(source, name)`,
     * which lets the mob's template react via `~onSignal:<name>` skill triggers
     * with [source] available as `@trigger`.
     *
     * For Citizens: no-op (Citizens has no equivalent of skill triggers driven
     * by external signals — the AI is goal-based, not event-based).
     */
    fun signal(name: String, source: Entity? = null) {}

    // -- Source access --

    /**
     * Returns the underlying NPC object from the source system (e.g. Citizens NPC).
     * Use sparingly — prefer StoryNPC methods where possible.
     */
    fun <T> unwrap(type: Class<T>): T?
}
