package com.canefe.story.audio

import com.canefe.story.Story
import org.bukkit.Location
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture
import kotlin.random.Random

/**
 * Manages audio playback for NPCs
 */
class AudioManager(
    private val plugin: Story,
) {
    private val maxVoiceFiles: Int
        get() = plugin.configService.maxVoiceFiles

    private val soundNamespace: String
        get() = plugin.configService.soundNameSpace

    /**
     * Plays a random NPC voice sound for a specific gender at the given location
     *
     * @param location The location to play the sound at
     * @param gender The gender of the NPC ("man" or "girl")
     * @param volume The volume of the sound (default 1.0)
     * @param pitch The pitch of the sound (default 1.0)
     */
    fun playRandomVoice(
        location: Location,
        gender: String = "man",
        volume: Float = 1.0f,
        pitch: Float = 1.0f,
        lastPlayed: String? = null,
    ): CompletableFuture<String> {
        val result = CompletableFuture<String>()

        var voiceNumber: String
        var soundId: String

        // Keep selecting a new random sound until we get one that's different from lastPlayed
        do {
            voiceNumber = Random.nextInt(1, maxVoiceFiles + 1).toString().padStart(2, '0')
            soundId = "$soundNamespace.${gender}_$voiceNumber"
        } while (soundId == lastPlayed && maxVoiceFiles > 1)

        // Run sound playback on the main server thread
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                location.world?.playSound(location, soundId, SoundCategory.VOICE, volume, pitch)
                result.complete(soundId)
            },
        )

        // Return the played sound ID so it can be stored as lastPlayed for next time
        return result
    }

    /**
     * Plays a random NPC voice sound for a specific gender to a player
     *
     * @param player The player to play the sound for
     * @param gender The gender of the NPC ("man" or "girl")
     * @param volume The volume of the sound (default 1.0)
     * @param pitch The pitch of the sound (default 1.0)
     */
    fun playRandomVoiceToPlayer(
        player: Player,
        gender: String = "man",
        volume: Float = 1.0f,
        pitch: Float = 1.0f,
    ) {
        val voiceNumber = Random.nextInt(1, maxVoiceFiles + 1).toString().padStart(2, '0')
        val soundId = "$soundNamespace.${gender}_$voiceNumber"

        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                player.playSound(player.location, soundId, SoundCategory.VOICE, volume, pitch)
            },
        )
    }

    /**
     * Plays a specific NPC voice sound at the given location
     *
     * @param location The location to play the sound at
     * @param soundName The specific sound name (e.g., "man_01")
     * @param volume The volume of the sound (default 1.0)
     * @param pitch The pitch of the sound (default 1.0)
     */
    fun playSpecificVoice(
        location: Location,
        soundName: String,
        volume: Float = 1.0f,
        pitch: Float = 1.0f,
    ) {
        val soundId = "$soundNamespace.$soundName"
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                location.world?.playSound(location, soundId, SoundCategory.VOICE, volume, pitch)
            },
        )
    }

    /**
     * Checks if the sound exists in the resource pack
     * Note: This is a best-effort check as there's no direct way to check if a sound exists
     *
     * @param gender The gender to check
     * @return True if at least one sound for this gender likely exists
     */
    fun hasVoiceType(gender: String): Boolean {
        // This is a simple check that assumes if gender is "man" or "girl", it exists
        // In a production environment, you might want a more robust check
        return gender == "man" || gender == "girl"
    }
}
