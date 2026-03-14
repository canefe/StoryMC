package com.canefe.story.storage

import com.canefe.story.player.PlayerConfig
import java.util.UUID

interface PlayerStorage {
    fun loadTeams(): Map<String, MutableSet<UUID>>

    fun saveTeams(teams: Map<String, Set<UUID>>)

    fun loadDisabledPlayers(): MutableList<String>

    fun saveDisabledPlayers(players: List<String>)

    fun loadPlayerQuestDisplay(): Map<UUID, Pair<String, String>>

    fun savePlayerQuestDisplay(
        playerId: UUID,
        title: String,
        objective: String,
    )

    fun clearPlayerQuestDisplay(playerId: UUID)

    fun loadPlayerConfig(playerId: UUID): PlayerConfig

    fun savePlayerConfig(
        playerId: UUID,
        config: PlayerConfig,
    )
}
