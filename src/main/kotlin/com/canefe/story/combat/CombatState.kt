package com.canefe.story.combat

sealed class CombatState {
    object Idle : CombatState()

    data class Windup(
        val dir: SwingDir,
        val ticksLeft: Int,
        val canFeint: Boolean,
    ) : CombatState()

    data class Active(
        val dir: SwingDir,
        val ticksLeft: Int,
    ) : CombatState()

    data class Recovery(
        val ticksLeft: Int,
    ) : CombatState()

    data class Blocking(
        val dir: SwingDir,
        val parryWindowTicksLeft: Int,
    ) : CombatState()

    data class Staggered(
        val ticksLeft: Int,
    ) : CombatState()
}
