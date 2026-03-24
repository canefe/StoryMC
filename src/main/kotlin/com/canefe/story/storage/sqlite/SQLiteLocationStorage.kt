package com.canefe.story.storage.sqlite

import com.canefe.story.storage.LocationDocument
import com.canefe.story.storage.LocationStorage
import com.canefe.story.storage.SQLiteManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SQLiteLocationStorage(
    private val sqlite: SQLiteManager,
) : LocationStorage {
    private val gson = Gson()

    override fun loadAllLocations(): Map<String, LocationDocument> {
        val locations = mutableMapOf<String, LocationDocument>()
        val conn = sqlite.getConnection()
        val stmt = conn.prepareStatement("SELECT * FROM locations")
        val rs = stmt.executeQuery()

        while (rs.next()) {
            val locDoc = rowToLocationDocument(rs)
            locations[locDoc.name] = locDoc
        }

        rs.close()
        stmt.close()
        return locations
    }

    override fun loadLocation(name: String): LocationDocument? {
        val conn = sqlite.getConnection()
        val stmt = conn.prepareStatement("SELECT * FROM locations WHERE name = ?")
        stmt.setString(1, name)
        val rs = stmt.executeQuery()

        val result = if (rs.next()) rowToLocationDocument(rs) else null
        rs.close()
        stmt.close()
        return result
    }

    override fun saveLocation(location: LocationDocument) {
        val conn = sqlite.getConnection()
        val stmt =
            conn.prepareStatement(
                """REPLACE INTO locations (name, context, parent_location_name, world, x, y, z,
               yaw, pitch, allowed_npcs, hide_title, random_pathing_action)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            )

        stmt.setString(1, location.name)
        stmt.setString(2, location.description)
        stmt.setString(3, location.parentLocationName)
        stmt.setString(4, location.world)
        stmt.setDouble(5, location.x)
        stmt.setDouble(6, location.y)
        stmt.setDouble(7, location.z)
        stmt.setFloat(8, location.yaw)
        stmt.setFloat(9, location.pitch)
        stmt.setString(10, gson.toJson(location.allowedNPCs))
        stmt.setInt(11, if (location.hideTitle) 1 else 0)
        stmt.setString(12, location.randomPathingAction)
        stmt.executeUpdate()
        stmt.close()
    }

    override fun deleteLocation(name: String) {
        val conn = sqlite.getConnection()
        val stmt = conn.prepareStatement("DELETE FROM locations WHERE name = ?")
        stmt.setString(1, name)
        stmt.executeUpdate()
        stmt.close()
    }

    private fun rowToLocationDocument(rs: java.sql.ResultSet): LocationDocument {
        // Migration: handle both old JSON list format and new plain string format
        val contextRaw = rs.getString("context") ?: ""
        val description: String =
            try {
                val list: List<String> = gson.fromJson(contextRaw, object : TypeToken<List<String>>() {}.type)
                list.joinToString(". ")
            } catch (_: Exception) {
                contextRaw
            }

        val allowedNpcsJson = rs.getString("allowed_npcs")
        val allowedNpcs: List<String> =
            if (allowedNpcsJson != null) {
                gson.fromJson(allowedNpcsJson, object : TypeToken<List<String>>() {}.type)
            } else {
                emptyList()
            }

        return LocationDocument(
            name = rs.getString("name") ?: "",
            description = description,
            parentLocationName = rs.getString("parent_location_name"),
            world = rs.getString("world"),
            x = rs.getDouble("x"),
            y = rs.getDouble("y"),
            z = rs.getDouble("z"),
            yaw = rs.getFloat("yaw"),
            pitch = rs.getFloat("pitch"),
            allowedNPCs = allowedNpcs,
            hideTitle = rs.getInt("hide_title") == 1,
            randomPathingAction = rs.getString("random_pathing_action"),
        )
    }
}
