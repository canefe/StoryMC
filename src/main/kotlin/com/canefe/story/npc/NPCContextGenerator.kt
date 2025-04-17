package com.canefe.story.npc

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.canefe.story.conversation.ConversationMessageRole
import com.canefe.story.npc.data.NPCContext
import com.canefe.story.npc.data.NPCData
import com.canefe.story.npc.memory.Memory
import me.casperge.realisticseasons.api.SeasonsAPI
import org.bukkit.Bukkit
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.HashMap
import kotlin.random.Random

/**
 * Service responsible for generating and updating NPC context information.
 */
class NPCContextGenerator(private val plugin: Story) {

    /**
     * Generates a default context for a new NPC.
     */
    private fun generateDefaultContext(
        npcName: String,
        role: String,
        hours: Int,
        minutes: Int,
        season: String,
        date: String
    ): String {
        val random = Random(System.currentTimeMillis())

        // Randomly select personality traits
        val trait = plugin.config.traitList.random(random)
        val quirk = plugin.config.quirkList.random(random)
        val motivation = plugin.config.motivationList.random(random)
        val flaw = plugin.config.flawList.random(random)
        val tone = plugin.config.toneList.random(random)

        // Construct personality description
        val personality = " is $trait, has the quirk of $quirk, " +
                "is motivated by $motivation, and their flaw is $flaw. " +
                "They speak in a $tone tone."

        // Construct the context
        return "$npcName$personality The time is $hours:${String.format("%02d", minutes)} " +
                "and the season is $season. "
    }

    fun getOrCreateContextForNPC(npcName: String): NPCContext? {
        try {
            // Fetch NPC data dynamically
            val npcDataFile: FileConfiguration = plugin.npcDataManager.loadNPCData(npcName)
            val npcRole = npcDataFile.getString("role", "Default role")!!
            var existingContext = npcDataFile.getString("context", null)
            val location = npcDataFile.getString("location", "Village")!!
            val avatar = npcDataFile.getString("avatar", "")!!
            // Get list of relations for the NPC
            val relationsSection = npcDataFile.getConfigurationSection("relations")
            val relations: MutableMap<String, Int> = HashMap()
            if (relationsSection != null) {
                for (key in relationsSection.getKeys(false)) {
                    relations[key] = relationsSection.getInt(key)
                }
            }

            val storyLocation = plugin.locationManager.getLocation(location)
                ?: plugin.locationManager.createLocation(location, null) ?: return null

            // Add dynamic world context
            val seasonsAPI = SeasonsAPI.getInstance()
            val season = seasonsAPI.getSeason(Bukkit.getWorld("world")) // Replace "world" with actual world name
            val hours = seasonsAPI.getHours(Bukkit.getWorld("world"))
            val minutes = seasonsAPI.getMinutes(Bukkit.getWorld("world"))
            val date = seasonsAPI.getDate(Bukkit.getWorld("world"))

            // Update or generate context
            existingContext = if (existingContext != null) {
                updateContext(
                    existingContext,
                    npcName!!, hours, minutes, season.toString(), date.toString(true)
                )
            } else {
                generateDefaultContext(
                    npcName!!,
                    npcRole,
                    hours,
                    minutes,
                    season.toString(),
                    date.toString(true)
                )
            }

            // Add context to the conversation history
            val memories: MutableList<Memory> = plugin.npcDataManager.loadNPCMemory(npcName)

            val npcData = NPCData(
                npcName,
                npcRole,
                storyLocation,
                existingContext
            )
            npcData.memory = memories

            plugin.npcDataManager.saveNPCData(npcName, npcData)
            return NPCContext(
                npcName,
                npcRole,
                existingContext,
                relations,
                storyLocation,
                avatar,
                memories
            )
        } catch (e: Exception) {
            Bukkit.getLogger().warning("Error while updating NPC context: " + e.message)
            return null
        }
    }

    /**
     * Updates an existing NPC context with current time and season information.
     */
    private fun updateContext(
        context: String,
        npcName: String,
        hours: Int,
        minutes: Int,
        season: String,
        date: String
    ): String {
        Bukkit.getLogger().info("Updating context for NPC: $npcName")

        var updatedContext = context
            .replace(Regex("The time is \\d{1,2}:\\d{2}"), "The time is $hours:${String.format("%02d", minutes)}")
            .replace(Regex("and the season is \\w+"), "and the season is $season")
            .replace(Regex("The date is \\d{4}-\\d{2}-\\d{2}"), "The date is $date")

        return updatedContext
    }

    private val generalContexts: MutableList<String> = mutableListOf()
    private val generalContextFile = File(plugin.dataFolder, "general-contexts.yml")

    init {
        loadGeneralContexts()
    }

    fun loadConfig() {
        loadGeneralContexts()
    }

    private fun loadGeneralContexts() {
        generalContexts.clear()

        if (!generalContextFile.exists()) {
            return
        }

        val config = YamlConfiguration.loadConfiguration(generalContextFile)
        generalContexts.addAll(config.getStringList("contexts"))
    }

    private fun saveGeneralContexts() {
        val config = YamlConfiguration()
        config.set("contexts", generalContexts)
        config.save(generalContextFile)
    }

    fun getGeneralContexts(): List<String> = generalContexts.toList()

    fun addGeneralContext(context: String) {
        if (!generalContexts.contains(context)) {
            generalContexts.add(context)
            saveGeneralContexts()
        }
    }

    fun removeGeneralContext(context: String) {
        if (generalContexts.remove(context)) {
            saveGeneralContexts()
        }
    }
}