package com.canefe.story.conversation

import com.canefe.story.util.EssentialsUtils
import net.citizensnpcs.api.npc.NPC
import org.bukkit.entity.Player
import java.util.*

class Conversation(

    var id: Int = -1,

    private val _players: MutableList<UUID>,
    initialNPCs: List<NPC>
) {
    private val _npcNames: MutableList<String> = ArrayList()
    private val _npcs: MutableSet<NPC> = HashSet(initialNPCs)
    private val _history: MutableList<ConversationMessage> = ArrayList()

    // Public properties
    var isActive: Boolean = true
    var chatEnabled: Boolean = true

    // Read-only property exposing internal list as immutable
    val npcNames: List<String> get() = _npcNames.toList()
    val npcs: List<NPC> get() = _npcs.toList()
    val history: List<ConversationMessage> get() = _history.toList()
    val players: List<UUID> get() = _players.toList()

    init {
        for (npc in initialNPCs) {
            _npcNames.add(npc.name)
        }
    }

    fun getNPCByName(name: String): NPC? {
        return _npcs.find { it.name.equals(name, ignoreCase = true) }
    }

    fun hasPlayer(playerUUID: UUID): Boolean {
        return _players.contains(playerUUID)
    }

    fun hasNPC(name: String): Boolean {
        return _npcNames.contains(name)
    }

    fun hasNPC(npc: NPC): Boolean {
        return _npcs.contains(npc)
    }

    fun hasPlayer(player: Player): Boolean {
        return _players.contains(player.uniqueId)
    }

    fun addNPC(npc: NPC): Boolean {
        val npcName = npc.name
        _npcs.add(npc)

        if (!_npcNames.contains(npcName)) {
            _npcNames.add(npcName)
            return true
        }
        return false
    }

    fun removeNPC(npc: NPC): Boolean {
        val npcName = npc.name
        _npcs.remove(npc)

        if (_npcNames.contains(npcName)) {
            _npcNames.remove(npcName)
            return true
        }
        return false
    }

    fun addPlayer(player: Player): Boolean {
        if (!_players.contains(player.uniqueId)) {
            _players.add(player.uniqueId)
            return true
        }
        return false
    }

    fun removePlayer(player: Player): Boolean {
        if (_players.contains(player.uniqueId)) {
            _players.remove(player.uniqueId)
            return true
        }
        return false
    }

    fun addPlayerMessage(player: Player, message: String) {
        // Get nickname
        val playerName = EssentialsUtils.getNickname(player.name)
        addUserMessage("$playerName: $message")
    }

    fun addNPCMessage(npc: NPC, message: String) {
        // Get nickname
        val npcName = npc.name
        addAssistantMessage("$npcName: $message")
        addUserMessage("...")
    }

    fun removePlayer(playerUUID: UUID): Boolean {
        if (_players.contains(playerUUID)) {
            _players.remove(playerUUID)
            return true
        }
        return false
    }

    fun addMessage(message: ConversationMessage) {
        _history.add(message)
    }

    fun addSystemMessage(message: String) {
        val systemMessage = ConversationMessage(
            "system",
            message,
        )
        _history.add(systemMessage)
    }

    private fun addUserMessage(message: String) {
        val userMessage = ConversationMessage(
            "user",
            message,
        )
        _history.add(userMessage)
    }

    private fun addAssistantMessage(message: String) {
        val assistantMessage = ConversationMessage(
            "assistant",
            message,
        )
        _history.add(assistantMessage)
    }



    fun clearHistory() {
        _history.clear()
    }
}