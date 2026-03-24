package com.canefe.story.api.character

import com.canefe.story.Story
import com.canefe.story.location.data.StoryLocation
import com.canefe.story.npc.memory.Memory
import com.canefe.story.util.*
import org.bukkit.entity.Player
import java.util.UUID

class PlayerCharacter(
    val player: Player,
    override val id: String? = player.characterId,
    override val role: String = "Player",
    override val appearance: String = "",
    override val context: String = "A player in the world.",
    override val location: StoryLocation? = null,
    override val memory: MutableList<Memory> = mutableListOf(),
    override val skills: CharacterSkills = CharacterSkills.EMPTY,
) : Character {
    override val entityId: UUID get() = player.uniqueId
    override val name: String get() = player.characterName

    companion object {
        fun from(player: Player): PlayerCharacter {
            val plugin = Story.instance
            return PlayerCharacter(
                player = player,
                skills = CharacterSkills(plugin.skillManager.createProviderForCharacter(player.uniqueId, true), player),
            )
        }
    }
}
