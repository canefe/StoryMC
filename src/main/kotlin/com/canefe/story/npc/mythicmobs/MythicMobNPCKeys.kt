package com.canefe.story.npc.mythicmobs

import com.canefe.story.Story
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType

/**
 * Persistent-data-container keys for tagging spawned MythicMob StoryNPCs so
 * they can be re-registered after restart / chunk reload.
 *
 * Stored on the BACKING bukkit entity (not the LibsDisguises fake player).
 */
object MythicMobNPCKeys {
    val STABLE_UUID: NamespacedKey by lazy { NamespacedKey(Story.instance, "npc_uuid") }
    val DISPLAY_NAME: NamespacedKey by lazy { NamespacedKey(Story.instance, "npc_name") }
    val INTERNAL_NAME: NamespacedKey by lazy { NamespacedKey(Story.instance, "npc_template") }

    val STRING: PersistentDataType<String, String> = PersistentDataType.STRING
}
