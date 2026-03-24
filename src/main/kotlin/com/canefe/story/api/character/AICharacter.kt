package com.canefe.story.api.character

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.location.data.StoryLocation
import com.canefe.story.npc.memory.Memory
import java.util.UUID

class AICharacter(
    val npc: StoryNPC,
    override val id: String? = null,
    override val name: String,
    override val role: String = "",
    override val appearance: String = "",
    override val context: String = "",
    override val location: StoryLocation? = null,
    override val memory: MutableList<Memory> = mutableListOf(),
    override val skills: CharacterSkills = CharacterSkills.EMPTY,
) : Character {
    override val entityId: UUID get() = npc.uniqueId

    companion object {
        /**
         * Build an AICharacter from a StoryNPC, resolving data from the plugin.
         */
        fun from(npc: StoryNPC): AICharacter {
            val plugin = Story.instance
            val record = plugin.characterRegistry.getByStoryNPC(npc)
            return AICharacter(
                npc = npc,
                id = record?.id,
                name = npc.name,
                role = "",
                appearance = record?.appearance ?: "",
                context = "",
                skills = CharacterSkills(plugin.skillManager.createProviderForNPC(npc)),
            )
        }
    }
}
