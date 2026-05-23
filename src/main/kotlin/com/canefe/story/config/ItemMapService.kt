package com.canefe.story.config

import org.bukkit.Material
import org.yaml.snakeyaml.Yaml
import java.io.File

data class ItemRenderSpec(val material: Material, val customModelData: Int?)

/**
 * Maps sim item ids (e.g. "bread", "coin") to a Minecraft rendering spec.
 * Backed by items.yml. Unknown ids and bad material names fall back to `default`.
 */
class ItemMapService {
    @Volatile private var specs: Map<String, ItemRenderSpec> = emptyMap()
    @Volatile private var default: ItemRenderSpec = ItemRenderSpec(Material.PAPER, null)

    fun renderSpecFor(simId: String): ItemRenderSpec = specs[simId.lowercase()] ?: default

    /** Parse a snakeyaml-shaped map into specs. Exposed for tests and the file loader. */
    @Suppress("UNCHECKED_CAST")
    fun loadFromMap(root: Map<String, Any?>) {
        fun parseSpec(node: Any?, fallback: ItemRenderSpec): ItemRenderSpec {
            val m = node as? Map<String, Any?> ?: return fallback
            val matName = (m["material"] as? String)?.uppercase()
            val mat = matName?.let { runCatching { Material.valueOf(it) }.getOrNull() } ?: fallback.material
            val cmd = (m["customModelData"] as? Number)?.toInt()
            return ItemRenderSpec(mat, cmd)
        }
        default = parseSpec(root["default"], ItemRenderSpec(Material.PAPER, null))
        val items = root["items"] as? Map<String, Any?> ?: emptyMap()
        specs = items.entries.associate { (k, v) -> k.lowercase() to parseSpec(v, default) }
    }

    /** Load from items.yml in the plugin data folder. */
    fun loadFromFile(file: File) {
        if (!file.exists()) {
            specs = emptyMap()
            default = ItemRenderSpec(Material.PAPER, null)
            return
        }
        val yaml = Yaml()
        val root = file.inputStream().use { yaml.load<Map<String, Any?>>(it) } ?: emptyMap()
        loadFromMap(root)
    }
}
