package com.canefe.story.bridge

import kotlinx.serialization.Serializable

// ── Outbound domain events (Plugin → Go) ─────────────────────────────
// These replace direct storage mutations. The plugin observes/perceives
// and emits these events; Go owns all persistence and mutation logic.

@Serializable
data class CharacterSaveRequestedEvent(
    val characterId: String,
    val name: String,
    val role: String,
    val context: String,
    val appearance: String,
    val avatar: String,
    val locationName: String?,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.character.save"
}

@Serializable
data class CharacterDeleteRequestedEvent(
    val characterId: String,
    val name: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.character.delete"
}

@Serializable
data class MemoryObservedEvent(
    val characterId: String,
    val content: String,
    val significance: Double = 1.0,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.memory.observed"
}

@Serializable
data class RumorObservedEvent(
    val content: String,
    val location: String,
    val significance: Double,
    val gameCreatedAt: Long,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.rumor.observed"
}

@Serializable
data class QuestAssignRequestedEvent(
    val playerName: String,
    val questId: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.quest.assign"
}

@Serializable
data class QuestAssignFromIntentEvent(
    val playerName: String,
    val questId: String,
    val questTitle: String,
    val questDescription: String,
    val npcName: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.quest.assign_from_intent"
}

@Serializable
data class QuestProgressObservedEvent(
    val playerName: String,
    val questId: String,
    val objectiveType: String? = null,
    val target: String? = null,
    val progress: Int = 1,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.quest.progress"
}

@Serializable
data class QuestCompleteRequestedEvent(
    val playerName: String,
    val questId: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.quest.complete"
}

@Serializable
data class SessionFeedEvent(
    val text: String,
    val force: Boolean = false,
) : SerializableStoryEvent {
    override val eventType: String get() = "domain.session.feed"
}

// ── Inbound intents (Go → Plugin) ────────────────────────────────────
// Go processes domain events and sends intents back to update local state.

@Serializable
data class QuestAssignIntent(
    val playerName: String,
    val questId: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "intent.quest.assign"
}

@Serializable
data class QuestUpdateIntent(
    val playerName: String,
    val questId: String,
    val objectiveType: String? = null,
    val target: String? = null,
    val progress: Int = 1,
) : SerializableStoryEvent {
    override val eventType: String get() = "intent.quest.update"
}

@Serializable
data class QuestCompleteIntent(
    val playerName: String,
    val questId: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "intent.quest.complete"
}

@Serializable
data class CharacterUpdateIntent(
    val characterId: String,
) : SerializableStoryEvent {
    override val eventType: String get() = "intent.character.update"
}
