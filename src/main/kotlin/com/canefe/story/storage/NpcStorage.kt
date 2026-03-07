package com.canefe.story.storage

import com.canefe.story.npc.data.NPCData
import com.canefe.story.npc.memory.Memory

interface NpcStorage {
    fun loadNpcData(npcName: String): NPCData?

    fun saveNpcData(
        npcName: String,
        npcData: NPCData,
    )

    fun deleteNpc(npcName: String)

    fun getAllNpcNames(): List<String>

    fun loadNpcMemories(npcName: String): MutableList<Memory>

    /** Returns the actual filename/key for a given display handle, or null if not found. */
    fun findNpcByDisplayHandle(displayHandle: String): String?

    /** Returns the actual filename/key for a given NPC name (checking display handle index, normalized, and original). */
    fun resolveNpcKey(npcName: String): String?
}
