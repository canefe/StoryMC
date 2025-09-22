package com.canefe.story.util

import com.canefe.story.Story
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.FileNotFoundException
import java.util.*

/**
 *
 * EssentialsUtils v1.0
 *
 * @author seemethere
 *
 * This class returns all data associated with Essentials userdata & warps.
 * This class was created because it is a lightweight and easy to understand alternative to
 * including the Essentials API in your plugins
 * NOTE: This class also assumes you already have Essentials and does not check for that
 *
 * Last Update: 6/25/2013
 */
object EssentialsUtils {
    @Throws(FileNotFoundException::class)
    private fun getUserDataFile(player: String): File =
        File(
            Story.instance.dataFolder,
            "../Essentials/userdata/" +
                Bukkit
                    .getPlayer(player)!!
                    .uniqueId + ".yml",
        )

    private fun getUserYaml(player: String): YamlConfiguration? {
        var player = player
        player = player.lowercase(Locale.getDefault())
        val userDataFile: File
        try {
            userDataFile = getUserDataFile(player)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        return YamlConfiguration.loadConfiguration(userDataFile)
    }

    fun getLastLoginTime(player: String): Long? = getUserYaml(player)?.getLong("timestamps.login")

    fun getLastLogoutTime(player: String): Long? = getUserYaml(player)?.getLong("timestamps.logout")

    fun getLastTeleportTime(player: String): Long? = getUserYaml(player)?.getLong("timestamps.lastteleport")

    @JvmStatic
    fun getNickname(player: String): String {
        val userYaml = getUserYaml(player) ?: return player
        val nickname = userYaml.getString("nickname")
        return nickname ?: player
    }

    fun getMoney(player: String): Double? = getUserYaml(player)?.getDouble("money")

    // NOTE: This does not check if they are actually banned
    fun getBanReason(player: String): String? = getUserYaml(player)?.getString("ban.reason")!!

    fun hasSocialSpy(player: String): Boolean? = getUserYaml(player)?.getBoolean("socialspy")

    fun isAFK(player: String): Boolean? = getUserYaml(player)?.getBoolean("afk")

    fun getHomeNames(player: String): Set<String> =
        getUserYaml(player)?.getConfigurationSection("homes")!!.getKeys(false)

    fun getIP(player: String): String = getUserYaml(player)?.getString("ipAddress")!!

    fun getHomeLocation(
        player: String,
        home: String,
    ): Location? {
        val userYaml = getUserYaml(player)
        if (userYaml?.getConfigurationSection("homes") == null) {
            return null
        }
        return getLocation(userYaml, "homes.$home.")
    }

    fun getLastLocation(player: String): Location? {
        val userYaml = getUserYaml(player)
        return userYaml?.let { getLocation(it, "lastlocation.") }
    }

    fun getWarp(warp: String): Location {
        val warpFile =
            File(Story.instance.dataFolder, "../Essentials/warps/" + warp.lowercase(Locale.getDefault()) + ".yml")
        val warpYML = YamlConfiguration.loadConfiguration(warpFile)
        return getLocation(warpYML, "")
    }

    private fun getLocation(
        yaml: YamlConfiguration,
        header: String,
    ): Location {
        val world = Story.instance.server.getWorld(yaml.getString(header + "world")!!)
        val yaw = yaml.getDouble(header + "yaw")
        val pitch = yaml.getDouble(header + "pitch")
        val x = yaml.getDouble(header + "x")
        val y = yaml.getDouble(header + "y")
        val z = yaml.getDouble(header + "z")
        return Location(world, x, y, z, yaw.toFloat(), pitch.toFloat())
    }
}
