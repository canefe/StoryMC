package com.canefe.story.npc.duty

/**
 * Represents a duty script that defines what an NPC does while working
 */
data class DutyScript(
    val name: String,
    val cycleSeconds: Int,
    val steps: List<DutyStep>,
) {
    fun getStep(index: Int): DutyStep = steps[index % steps.size]
}

/**
 * Individual step in a duty script
 */
data class DutyStep(
    val action: String,
    val args: Map<String, String> = emptyMap(),
    val duration: Int = 0, // seconds
    val ifNear: Double? = null, // only execute if player within this distance
    val cooldown: Int = 0, // seconds between executions of this type
) {
    val durationMs: Long get() = duration * 1000L
    val cooldownMs: Long get() = cooldown * 1000L
}

/**
 * Workstation at a location
 */
data class Workstation(
    val name: String,
    val x: Double,
    val y: Double,
    val z: Double,
)

/**
 * Bark pool for vendor speech
 */
data class BarkPool(
    val name: String,
    val messages: List<String>,
)

/**
 * Current state of an NPC's duty execution
 */
data class DutyState(
    val duty: DutyScript,
    var stepIndex: Int = 0,
    var stepStartedAt: Long = System.currentTimeMillis(),
    var lastBarkTime: Long = 0,
) {
    fun getCurrentStep(): DutyStep = duty.getStep(stepIndex)

    fun advanceToNextStep() {
        stepIndex = (stepIndex + 1) % duty.steps.size
        stepStartedAt = System.currentTimeMillis()
    }
}
