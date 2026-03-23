package com.canefe.story.api.character

import com.canefe.story.location.data.StoryLocation
import com.canefe.story.npc.memory.Memory
import java.util.UUID

/**
 * Represents any entity that can speak or act in the Story world.
 * Implemented by [PlayerCharacter] and [AICharacter].
 */
interface Character {
    val id: UUID
    val name: String
    val role: String
    val appearance: String
    val context: String
    val location: StoryLocation?
    val memory: MutableList<Memory>
    val skills: CharacterSkills
}
