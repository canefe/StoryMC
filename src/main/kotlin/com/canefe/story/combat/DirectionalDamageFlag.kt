package com.canefe.story.combat

/**
 * Per-thread re-entry guard for directional damage. When set,
 * [com.canefe.story.combat.listener.VanillaMeleeListener] does NOT cancel an
 * incoming [org.bukkit.event.entity.EntityDamageByEntityEvent] — letting our
 * own damage call land normally. Cleared in a `finally` after the call.
 */
internal object DirectionalDamageFlag {
    private val tl = ThreadLocal.withInitial { false }

    fun isApplying(): Boolean = tl.get()

    inline fun <T> applying(block: () -> T): T {
        val prior = tl.get()
        tl.set(true)
        try {
            return block()
        } finally {
            tl.set(prior)
        }
    }
}
