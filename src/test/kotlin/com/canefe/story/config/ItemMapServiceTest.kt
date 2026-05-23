package com.canefe.story.config

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ItemMapServiceTest {
    private fun service(): ItemMapService {
        val svc = ItemMapService()
        svc.loadFromMap(
            mapOf(
                "items" to mapOf(
                    "bread" to mapOf("material" to "BREAD"),
                    "coin" to mapOf("material" to "GOLD_NUGGET"),
                    "potion" to mapOf("material" to "POTION", "customModelData" to 1001),
                    "bogus" to mapOf("material" to "NOT_A_REAL_MATERIAL"),
                ),
                "default" to mapOf("material" to "PAPER"),
            ),
        )
        return svc
    }

    @Test
    fun `known item resolves to its material`() {
        assertEquals(Material.BREAD, service().renderSpecFor("bread").material)
        assertEquals(Material.GOLD_NUGGET, service().renderSpecFor("coin").material)
    }

    @Test
    fun `customModelData is read when present and null otherwise`() {
        assertEquals(1001, service().renderSpecFor("potion").customModelData)
        assertNull(service().renderSpecFor("bread").customModelData)
    }

    @Test
    fun `unknown item falls back to default`() {
        assertEquals(Material.PAPER, service().renderSpecFor("unobtainium").material)
    }

    @Test
    fun `invalid material name falls back to default material`() {
        assertEquals(Material.PAPER, service().renderSpecFor("bogus").material)
    }
}
