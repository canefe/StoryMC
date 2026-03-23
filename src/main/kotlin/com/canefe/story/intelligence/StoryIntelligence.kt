package com.canefe.story.intelligence

import com.canefe.story.api.StoryNPC
import com.canefe.story.conversation.Conversation
import java.util.concurrent.CompletableFuture

/**
 * Abstraction for all "thinking" operations in the story system.
 * Implementations can be local (direct LLM calls) or remote (delegated to Go orchestrator).
 *
 * Each method can be independently overridden — a BridgeIntelligence can delegate
 * some methods to the remote backend and fall back to local for others.
 */
interface StoryIntelligence {
    /**
     * Generate what an NPC should say next in a conversation.
     * @return The NPC's dialogue response
     */
    fun generateNPCResponse(
        npc: StoryNPC,
        conversation: Conversation,
    ): CompletableFuture<String>

    /**
     * Determine which NPC should speak next in a multi-NPC conversation.
     * @return The name of the NPC who should speak, or null if none
     */
    fun selectNextSpeaker(conversation: Conversation): CompletableFuture<String?>

    /**
     * Summarize a conversation and store memories for participating NPCs.
     */
    fun summarizeConversation(conversation: Conversation): CompletableFuture<Void>

    /**
     * Generate physical reactions for non-speaking NPCs in a conversation.
     * @return Map of NPC name → reaction text (e.g. "*nods thoughtfully*")
     */
    fun generateNPCReactions(
        conversation: Conversation,
        speakerName: String,
        message: String,
    ): CompletableFuture<Map<String, String>>

    /**
     * Summarize conversation message history to keep context manageable.
     * @return Summary text to replace old messages
     */
    fun summarizeMessageHistory(conversation: Conversation): CompletableFuture<String?>
}
