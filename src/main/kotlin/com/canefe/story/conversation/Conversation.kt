package com.canefe.story.conversation

import com.canefe.story.Story
import com.canefe.story.api.StoryNPC
import com.canefe.story.util.*
import org.bukkit.entity.Player
import java.util.*

class Conversation(
    var id: Int = -1,
    private val _players: MutableList<UUID>,
    initialNPCs: List<StoryNPC>,
) {
    private val _npcNames: MutableList<String> = ArrayList()
    private val _npcCharacterIds: MutableList<String> = ArrayList()
    private val _npcs: MutableSet<StoryNPC> = HashSet(initialNPCs)
    private val _history: MutableList<ConversationMessage> = ArrayList()

    // Track the number of non-system messages added since the last history summarization
    var messagesSinceLastSummary: Int = 0

    // Public properties
    var active: Boolean = true
    var chatEnabled: Boolean = true
    var autoMode: Boolean = false
    var radiant: Boolean = false
    val mutedNPCs: MutableList<StoryNPC> = ArrayList()

    // Read-only properties
    val npcNames: List<String> get() = _npcNames.toList()
    val npcCharacterIds: List<String> get() = _npcCharacterIds.toList()
    val npcs: List<StoryNPC> get() = _npcs.toList()
    val history: List<ConversationMessage> get() = _history.toList()
    val players: List<UUID> get() = _players.toList()

    // Last Speaking NPC
    var lastSpeakingNPC: StoryNPC? = null

    init {
        for (npc in initialNPCs) {
            _npcNames.add(npc.name)
            resolveCharacterId(npc)?.let { _npcCharacterIds.add(it) }
        }
    }

    fun getNPCByName(name: String): StoryNPC? = _npcs.find { it.name.equals(name, ignoreCase = true) }

    fun getNPCByCharacterId(characterId: String): StoryNPC? {
        val record =
            try {
                Story.instance.characterRegistry.getById(characterId)
            } catch (_: UninitializedPropertyAccessException) {
                null
            } ?: return null
        return _npcs.find { it.name.equals(record.name, ignoreCase = true) }
    }

    fun hasPlayer(playerUUID: UUID): Boolean = _players.contains(playerUUID)

    fun hasNPC(name: String): Boolean = _npcNames.contains(name)

    fun hasNPCByCharacterId(characterId: String): Boolean = _npcCharacterIds.contains(characterId)

    fun hasNPC(npc: StoryNPC): Boolean = _npcs.contains(npc)

    fun hasPlayer(player: Player): Boolean = _players.contains(player.uniqueId)

    fun addNPC(npc: StoryNPC): Boolean {
        val npcName = npc.name
        _npcs.add(npc)

        if (!_npcNames.contains(npcName)) {
            _npcNames.add(npcName)
            resolveCharacterId(npc)?.let { _npcCharacterIds.add(it) }
            return true
        }
        return false
    }

    fun removeHistoryMessageAt(index: Int): Boolean {
        if (index < 0 || index >= _history.size) {
            return false
        }
        _history.removeAt(index)
        return true
    }

    fun removeNPC(npc: StoryNPC): Boolean {
        val npcName = npc.name
        _npcs.remove(npc)

        if (_npcNames.contains(npcName)) {
            _npcNames.remove(npcName)
            resolveCharacterId(npc)?.let { _npcCharacterIds.remove(it) }
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

    fun muteNPC(npc: StoryNPC): Boolean {
        if (!_npcs.contains(npc)) {
            return false
        }
        if (!mutedNPCs.contains(npc)) {
            mutedNPCs.add(npc)
            return true
        }
        return false
    }

    fun unmuteNPC(npc: StoryNPC): Boolean {
        if (mutedNPCs.contains(npc)) {
            mutedNPCs.remove(npc)
            return true
        }
        return false
    }

    fun addPlayerMessage(
        player: Player,
        message: String,
    ) {
        val playerName = player.characterName
        addUserMessage("$playerName: $message")
    }

    fun addNPCMessage(
        npc: StoryNPC,
        message: String,
    ) {
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
        val systemMessage =
            ConversationMessage(
                "system",
                message,
            )
        _history.add(systemMessage)
    }

    private fun addUserMessage(message: String) {
        val userMessage =
            ConversationMessage(
                "user",
                message,
            )
        _history.add(userMessage)
        if (message != "...") {
            messagesSinceLastSummary++
        }
    }

    private fun addAssistantMessage(message: String) {
        val assistantMessage =
            ConversationMessage(
                "assistant",
                message,
            )
        _history.add(assistantMessage)
        messagesSinceLastSummary++
    }

    fun replaceHistoryWithSummary(
        summary: String,
        summarizedMessagesCount: Int,
        countedMessages: Int = summarizedMessagesCount,
    ) {
        if (summarizedMessagesCount <= 0 || _history.size < summarizedMessagesCount) {
            return
        }
        _history.subList(0, summarizedMessagesCount).clear()
        _history.add(0, ConversationMessage("system", "Summary of conversation so far: $summary"))

        messagesSinceLastSummary -= countedMessages
        if (messagesSinceLastSummary < 0) {
            messagesSinceLastSummary = 0
        }
    }

    fun clearHistory() {
        _history.clear()
    }

    private fun resolveCharacterId(npc: StoryNPC): String? =
        try {
            Story.instance.characterRegistry.getCharacterIdForNPC(npc)
        } catch (_: UninitializedPropertyAccessException) {
            null
        }
}
