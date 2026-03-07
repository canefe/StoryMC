package com.canefe.story.storage

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.logging.Logger

class SQLiteManager(
    private val dataFolder: File,
    private val logger: Logger,
) {
    private var connection: Connection? = null

    fun connect(): Boolean =
        try {
            val dbFile = File(dataFolder, "story.db")
            dataFolder.mkdirs()
            connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            connection!!.autoCommit = true
            logger.info("[SQLite] Connected to ${dbFile.absolutePath}")
            true
        } catch (e: Exception) {
            logger.severe("[SQLite] Failed to connect: ${e.message}")
            false
        }

    fun getConnection(): Connection = connection ?: throw IllegalStateException("SQLite not connected")

    fun close() {
        connection?.let {
            if (!it.isClosed) {
                it.close()
                logger.info("[SQLite] Connection closed")
            }
        }
        connection = null
    }

    fun createTables() {
        val conn = getConnection()
        conn.createStatement().use { stmt ->
            // NPCs
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS npcs (
                    filename TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    role TEXT,
                    location TEXT,
                    context TEXT,
                    appearance TEXT,
                    avatar TEXT,
                    custom_voice TEXT,
                    generic INTEGER DEFAULT 0,
                    random_pathing INTEGER DEFAULT 1,
                    knowledge_categories TEXT,
                    name_bank TEXT,
                    npc_id TEXT,
                    anchor_key TEXT,
                    canonical_name TEXT,
                    display_handle TEXT,
                    callsign TEXT
                )
                """.trimIndent(),
            )

            // NPC Memories
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS npc_memories (
                    npc_filename TEXT NOT NULL,
                    memory_id TEXT NOT NULL,
                    content TEXT NOT NULL,
                    real_created_at TEXT,
                    game_created_at INTEGER DEFAULT 0,
                    power REAL DEFAULT 1.0,
                    last_accessed INTEGER DEFAULT 0,
                    significance REAL DEFAULT 1.0,
                    PRIMARY KEY (npc_filename, memory_id)
                )
                """.trimIndent(),
            )

            // Locations
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS locations (
                    name TEXT PRIMARY KEY,
                    context TEXT,
                    parent_location_name TEXT,
                    world TEXT,
                    x REAL DEFAULT 0,
                    y REAL DEFAULT 0,
                    z REAL DEFAULT 0,
                    yaw REAL DEFAULT 0,
                    pitch REAL DEFAULT 0,
                    allowed_npcs TEXT,
                    hide_title INTEGER DEFAULT 0,
                    random_pathing_action TEXT
                )
                """.trimIndent(),
            )

            // Quests
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS quests (
                    quest_id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    description TEXT,
                    type TEXT DEFAULT 'MAIN',
                    prerequisites TEXT,
                    next_quests TEXT,
                    objectives TEXT,
                    rewards TEXT
                )
                """.trimIndent(),
            )

            // Player Quests
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS player_quests (
                    player_id TEXT NOT NULL,
                    quest_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    completion_date INTEGER DEFAULT 0,
                    objective_progress TEXT,
                    PRIMARY KEY (player_id, quest_id)
                )
                """.trimIndent(),
            )

            // Sessions
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    session_id TEXT PRIMARY KEY,
                    start_time INTEGER DEFAULT 0,
                    end_time INTEGER,
                    players TEXT,
                    history TEXT,
                    active INTEGER DEFAULT 1
                )
                """.trimIndent(),
            )

            // Relationships
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS relationships (
                    source_id TEXT NOT NULL,
                    target_id TEXT NOT NULL,
                    target_name TEXT,
                    type TEXT DEFAULT 'acquaintance',
                    score REAL DEFAULT 0,
                    traits TEXT,
                    memory_ids TEXT,
                    PRIMARY KEY (source_id, target_id)
                )
                """.trimIndent(),
            )

            // Lore Books
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS lore_books (
                    name_lower TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    context TEXT,
                    keywords TEXT,
                    categories TEXT
                )
                """.trimIndent(),
            )

            // Teams
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS teams (
                    team_name TEXT PRIMARY KEY,
                    members TEXT
                )
                """.trimIndent(),
            )

            // Disabled Players
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS disabled_players (
                    player_name TEXT PRIMARY KEY
                )
                """.trimIndent(),
            )

            // Player Quest Display
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS player_quest_display (
                    player_id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    objective TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }

        // Create indexes
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_npcs_display_handle ON npcs(display_handle)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_npc_memories_filename ON npc_memories(npc_filename)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_quests_player ON player_quests(player_id)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_relationships_source ON relationships(source_id)")
        }

        logger.info("[SQLite] Tables and indexes created")
    }
}
