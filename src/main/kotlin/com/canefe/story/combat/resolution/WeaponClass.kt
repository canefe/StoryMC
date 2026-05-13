package com.canefe.story.combat.resolution

/**
 * Weapon-class baselines per spec §6. v1 ships three classes; later versions
 * can drive these from `combat-weapons.yml` (open question §11).
 */
enum class WeaponClass(
    val reach: Double,
    val baseDamage: Double,
) {
    SWORD(reach = 3.2, baseDamage = 4.0),
    POLEARM(reach = 4.5, baseDamage = 6.0),
    DAGGER(reach = 2.2, baseDamage = 2.5),
    ;

    /** Thrust raycast reach is 1.3× the base reach per spec §6. */
    fun thrustReach(): Double = reach * 1.3
}
