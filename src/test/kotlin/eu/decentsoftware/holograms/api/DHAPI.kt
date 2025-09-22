package eu.decentsoftware.holograms.api

import org.bukkit.Location
import org.bukkit.entity.Player

object DHAPI {
    @JvmStatic
    fun createHologram(
        id: String,
        location: Location,
        lines: List<String>,
    ) {
        // no-op for tests
    }

    @JvmStatic
    fun removeHologram(id: String) {
        // no-op for tests
    }

    @JvmStatic
    fun showHologram(
        id: String,
        player: Player,
    ) {
        // no-op for tests
    }

    @JvmStatic
    fun hideHologram(
        id: String,
        player: Player,
    ) {
        // no-op for tests
    }
}
