package com.canefe.story.audio

import com.canefe.story.Story
import com.canefe.story.util.EssentialsUtils
import net.citizensnpcs.api.npc.NPC
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Manages voice synthesis and delivery for NPC conversations. This class coordinates between the
 * conversation system, ElevenLabs API, and client mods to deliver spoken dialogue.
 */
class VoiceManager(
    private val plugin: Story,
) {
    private val audioManager: ElevenLabsAudioManager = ElevenLabsAudioManager(plugin)

    // Track which NPC is currently speaking to which players
    private val activeSpeakers = mutableMapOf<UUID, String>() // NPC UUID -> Current message

    /** Determines if voice features are enabled and properly configured */
    fun isEnabled(): Boolean = plugin.config.voiceGenerationEnabled && audioManager.isConfigured()

    fun load() = audioManager.loadConfig()

    /**
     * Generate speech for an NPC's message and send to relevant players
     *
     * @param npc The NPC who is speaking
     * @param message The text message to convert to speech
     * @param players The players who should hear this
     * @return CompletableFuture indicating success/failure
     */
    fun generateSpeechForNPC(
        npc: NPC,
        message: String,
        players: HashSet<Player>,
    ): CompletableFuture<Boolean> {
        // Skip if voice features are disabled
        if (!isEnabled()) {
            return CompletableFuture.completedFuture(false)
        }

        // Remove emotes and formatting from the message
        val cleanMessage = sanitizeMessage(message)

        // Skip empty messages
        if (cleanMessage.isEmpty()) {
            return CompletableFuture.completedFuture(false)
        }

        // Determine voice ID for this NPC
        val voiceId = determineNPCVoiceId(npc)

        // Skip if no custom voice is set
        if (voiceId == null) {
            return CompletableFuture.completedFuture(false)
        }

        // Store as active speaker
        activeSpeakers[npc.uniqueId] = cleanMessage

        if (players.isEmpty()) {
            return CompletableFuture.completedFuture(false)
        }

        val scheduleAudioGeneration = plugin.config.scheduleVoiceGenerationEnabled
        // check if we are in a conversation
        if (!scheduleAudioGeneration && !plugin.conversationManager.isInConversation(npc)) {
            // If not in a conversation, do not generate audio for scheduled messages
            return CompletableFuture.completedFuture(false)
        }

        // Generate speech once and send to all players
        return audioManager
            .generateSpeechOnce(cleanMessage, voiceId, npc.name)
            .thenApply { audioData ->
                if (audioData != null) {
                    // Send the same audio data to all players
                    var successCount = 0
                    for (player in players) {
                        try {
                            audioManager.sendAudioToPlayer(player, audioData)
                            successCount++
                        } catch (e: Exception) {
                            plugin.logger.warning(
                                "Failed to send audio to player ${player.name}: ${e.message}",
                            )
                        }
                    }
                    plugin.logger.info(
                        "Successfully sent audio to $successCount/${players.size} players for NPC ${npc.name}",
                    )
                    successCount > 0
                } else {
                    plugin.logger.warning("Failed to generate audio for NPC ${npc.name}")
                    false
                }
            }.exceptionally { e ->
                plugin.logger.warning("Failed to generate speech for ${npc.name}: ${e.message}")
                false
            }
    }

    /**
     * Generate speech for a player's message and send to relevant players
     *
     * @param player The player who is speaking
     * @param message The text message to convert to speech
     * @param players The players who should hear this
     * @return CompletableFuture indicating success/failure
     */
    fun generateSpeechForPlayer(
        player: Player,
        message: String,
        players: HashSet<Player>,
    ): CompletableFuture<Boolean> {
        // Skip if voice features are disabled or player voices are disabled
        if (!isEnabled() || !plugin.config.playerVoiceGenerationEnabled) {
            return CompletableFuture.completedFuture(false)
        }

        // Remove emotes and formatting from the message
        val cleanMessage = sanitizeMessage(message)

        // Skip empty messages
        if (cleanMessage.isEmpty()) {
            return CompletableFuture.completedFuture(false)
        }

        // Determine voice ID for this player
        val voiceId = determinePlayerVoiceId(player)

        // Skip if no custom voice is set
        if (voiceId == null) {
            return CompletableFuture.completedFuture(false)
        }

        // Store as active speaker
        activeSpeakers[player.uniqueId] = cleanMessage

        if (players.isEmpty()) {
            return CompletableFuture.completedFuture(false)
        }

        // Generate speech once and send to all players
        return audioManager
            .generateSpeechOnce(cleanMessage, voiceId, player.name)
            .thenApply { audioData ->
                if (audioData != null) {
                    // Send the same audio data to all players
                    var successCount = 0
                    for (p in players) {
                        try {
                            audioManager.sendAudioToPlayer(p, audioData)
                            successCount++
                        } catch (e: Exception) {
                            plugin.logger.warning(
                                "Failed to send audio to player ${p.name}: ${e.message}",
                            )
                        }
                    }
                    plugin.logger.info(
                        "Successfully sent audio to $successCount/${players.size} players for player ${player.name}",
                    )
                    successCount > 0
                } else {
                    plugin.logger.warning("Failed to generate audio for player ${player.name}")
                    false
                }
            }.exceptionally { e ->
                plugin.logger.warning(
                    "Failed to generate speech for ${player.name}: ${e.message}",
                )
                false
            }
    }

    /** Generate speech for a single player (useful for private conversations) */
    fun generateSpeechForSinglePlayer(
        npc: NPC,
        message: String,
        player: Player,
    ): CompletableFuture<Boolean> {
        // Skip if voice features are disabled
        if (!isEnabled()) {
            return CompletableFuture.completedFuture(false)
        }

        // Remove emotes and formatting from the message
        val cleanMessage = sanitizeMessage(message)

        // Skip empty messages
        if (cleanMessage.isEmpty()) {
            return CompletableFuture.completedFuture(false)
        }

        // Determine voice ID for this NPC
        val voiceId = determineNPCVoiceId(npc)

        // Skip if no custom voice is set
        if (voiceId == null) {
            return CompletableFuture.completedFuture(false)
        }

        // Store as active speaker
        activeSpeakers[npc.uniqueId] = cleanMessage

        // Generate and send speech to the player
        return audioManager.generateAndSendSpeech(
            player = player,
            text = cleanMessage,
            voiceId = voiceId,
            npcName = npc.name,
        )
    }

    /** Determine the appropriate voice ID for an NPC based on their traits */
    private fun determineNPCVoiceId(npc: NPC): String? {
        // Try to get NPC-specific voice mapping first
        val npcVoice = audioManager.getVoiceId(npc.name)
        if (npcVoice != audioManager.getVoiceId("default")) {
            return npcVoice
        }

        // Check if the NPC has a custom voice set in their data
        try {
            val npcData = plugin.npcContextGenerator.getOrCreateContextForNPC(npc.name)
            if (npcData?.customVoice != null) {
                plugin.logger.info(
                    "Using custom voice '${npcData.customVoice}' for NPC ${npc.name}",
                )
                return npcData.customVoice
            }
        } catch (e: Exception) {
            plugin.logger.warning("Error accessing NPC data for voice selection: ${e.message}")
        }

        // Return null if no custom voice is set - this will skip voice generation
        return null
    }

    /** Determine the appropriate voice ID for a player based on their traits */
    private fun determinePlayerVoiceId(player: Player): String? {
        // Try to get player-specific voice mapping first
        val playerName = EssentialsUtils.getNickname(player.name)
        val playerVoice = audioManager.getVoiceId(playerName)
        if (playerVoice != audioManager.getVoiceId("default")) {
            return playerVoice
        }

        // Check if the player has a custom voice set in their NPC data
        try {
            val playerData = plugin.npcContextGenerator.getOrCreateContextForNPC(player.name)
            if (playerData?.customVoice != null) {
                plugin.logger.info(
                    "Using custom voice '${playerData.customVoice}' for player ${player.name}",
                )
                return playerData.customVoice
            }
        } catch (e: Exception) {
            plugin.logger.warning("Error accessing player data for voice selection: ${e.message}")
        }

        // Return null if no custom voice is set - this will skip voice generation
        plugin.logger.info(
            "No custom voice set for player ${player.name}, skipping voice generation",
        )
        return null
    }

    /** Set a specific voice for an NPC */
    fun setNPCVoice(
        npcName: String,
        voiceId: String,
    ) {
        audioManager.setVoiceMapping(npcName, voiceId)
    }

    /** Get the current voice ID for an NPC */
    fun getNPCVoice(npcName: String): String = audioManager.getVoiceId(npcName)

    /** Check if an NPC is currently speaking */
    fun isNPCSpeaking(npcUuid: UUID): Boolean = activeSpeakers.containsKey(npcUuid)

    /** Stop speech for an NPC (remove from active speakers) */
    fun stopNPCSpeech(npcUuid: UUID) {
        activeSpeakers.remove(npcUuid)
    }

    /** Get available voices from ElevenLabs */
    fun getAvailableVoices(): CompletableFuture<List<ElevenLabsAudioManager.Voice>> = audioManager.getAvailableVoices()

    /** Clear audio cache */
    fun clearCache() {
        audioManager.clearCache()
    }

    /** Cleans the message of formatting codes, emotes, etc. */
    private fun sanitizeMessage(message: String): String {
        // Remove Minecraft formatting codes
        var cleaned = message.replace("§[0-9a-fk-or]".toRegex(), "")

        // Remove emotes (text between asterisks or in parentheses)
        cleaned = cleaned.replace("\\*[^*]*\\*".toRegex(), "")
        cleaned = cleaned.replace("\\([^)]*\\)".toRegex(), "")

        // Remove action text in brackets
        cleaned = cleaned.replace("\\[[^\\]]*\\]".toRegex(), "")

        // Remove excessive whitespace
        cleaned = cleaned.replace("\\s+".toRegex(), " ")

        return cleaned.trim()
    }

    /** Shutdown cleanup - no longer needed since we don't have a server */
    fun shutdown() {
        activeSpeakers.clear()
        // Shutdown the ElevenLabsAudioManager's virtual thread executor
        audioManager.shutdown()
        plugin.logger.info("VoiceManager shutdown complete")
    }
}
