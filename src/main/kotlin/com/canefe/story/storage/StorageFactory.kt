package com.canefe.story.storage

import com.canefe.story.storage.mongo.*
import com.canefe.story.storage.sqlite.*
import com.canefe.story.storage.yaml.*
import java.io.File
import java.util.logging.Logger

class StorageFactory private constructor(
    var npcStorage: NpcStorage,
    var locationStorage: LocationStorage,
    var questStorage: QuestStorage,
    var sessionStorage: SessionStorage,
    var relationshipStorage: RelationshipStorage,
    var loreStorage: LoreStorage,
    var playerStorage: PlayerStorage,
    var backendName: String,
    private var mongoClientManager: MongoClientManager?,
    private var sqliteManager: SQLiteManager?,
    private val dataFolder: File,
    private val logger: Logger,
    private var mongoUri: String,
    private var mongoDatabase: String,
    private var mongoMaxPoolSize: Int,
    private var mongoConnectTimeoutMs: Int,
) {
    val isMongoConnected: Boolean
        get() = mongoClientManager != null

    val isSQLite: Boolean
        get() = sqliteManager != null

    fun shutdown() {
        mongoClientManager?.close()
        mongoClientManager = null
        sqliteManager?.close()
        sqliteManager = null
    }

    /**
     * Switches to the given backend, shutting down the old connections and
     * creating new storage implementations. Returns true if the switch succeeded.
     */
    @Suppress("DEPRECATION")
    fun switchBackend(
        newBackend: String,
        newMongoUri: String = mongoUri,
        newMongoDatabase: String = mongoDatabase,
        newMongoMaxPoolSize: Int = mongoMaxPoolSize,
        newMongoConnectTimeoutMs: Int = mongoConnectTimeoutMs,
    ): Boolean {
        val wantsMongo = newBackend.equals("mongodb", ignoreCase = true)
        val wantsSQLite = newBackend.equals("sqlite", ignoreCase = true)

        // Update stored config
        mongoUri = newMongoUri
        mongoDatabase = newMongoDatabase
        mongoMaxPoolSize = newMongoMaxPoolSize
        mongoConnectTimeoutMs = newMongoConnectTimeoutMs

        if (wantsMongo) {
            val mongo =
                MongoClientManager(
                    uri = newMongoUri,
                    databaseName = newMongoDatabase,
                    maxPoolSize = newMongoMaxPoolSize,
                    connectTimeoutMs = newMongoConnectTimeoutMs,
                    logger = logger,
                )

            if (!mongo.connect()) {
                logger.severe("[Storage] MongoDB connection failed. Keeping current backend ($backendName).")
                return false
            }

            mongo.createIndexes()
            shutdown()
            mongoClientManager = mongo

            npcStorage = MongoNpcStorage(mongo, logger)
            locationStorage = MongoLocationStorage(mongo)
            questStorage = MongoQuestStorage(mongo)
            sessionStorage = MongoSessionStorage(mongo)
            relationshipStorage = MongoRelationshipStorage(mongo)
            loreStorage = MongoLoreStorage(mongo)
            playerStorage = MongoPlayerStorage(mongo)
            backendName = "MongoDB"

            logger.info("[Storage] Switched to MongoDB.")
            return true
        } else if (wantsSQLite) {
            val sqlite = SQLiteManager(dataFolder, logger)
            if (!sqlite.connect()) {
                logger.severe("[Storage] SQLite initialization failed. Keeping current backend ($backendName).")
                return false
            }

            sqlite.createTables()
            shutdown()
            sqliteManager = sqlite

            npcStorage = SQLiteNpcStorage(sqlite, logger)
            locationStorage = SQLiteLocationStorage(sqlite)
            questStorage = SQLiteQuestStorage(sqlite)
            sessionStorage = SQLiteSessionStorage(sqlite)
            relationshipStorage = SQLiteRelationshipStorage(sqlite)
            loreStorage = SQLiteLoreStorage(sqlite)
            playerStorage = SQLitePlayerStorage(sqlite)
            backendName = "SQLite"

            logger.info("[Storage] Switched to SQLite.")
            return true
        } else {
            // YAML
            shutdown()

            npcStorage = YamlNpcStorage(File(dataFolder, "npcs"), logger)
            locationStorage = YamlLocationStorage(File(dataFolder, "locations"), logger)
            questStorage = YamlQuestStorage(File(dataFolder, "quests"), File(dataFolder, "playerquests"), logger)
            sessionStorage = YamlSessionStorage(File(dataFolder, "sessions"), logger)
            relationshipStorage = YamlRelationshipStorage(File(dataFolder, "relationships"), logger)
            loreStorage = YamlLoreStorage(File(dataFolder, "lore"), logger)
            playerStorage = YamlPlayerStorage(dataFolder, logger)
            backendName = "YAML (deprecated)"

            logger.info("[Storage] Switched to YAML (deprecated).")
            return true
        }
    }

    companion object {
        fun create(
            dataFolder: File,
            logger: Logger,
            backend: String,
            mongoUri: String = "",
            mongoDatabase: String = "",
            mongoMaxPoolSize: Int = 10,
            mongoConnectTimeoutMs: Int = 10000,
        ): StorageFactory {
            val wantsMongo = backend.equals("mongodb", ignoreCase = true)
            val wantsSQLite = backend.equals("sqlite", ignoreCase = true)
            var mongoClient: MongoClientManager? = null
            var sqliteClient: SQLiteManager? = null
            var useMongo = false
            var useSQLite = false

            if (wantsMongo) {
                val mongo =
                    MongoClientManager(
                        uri = mongoUri,
                        databaseName = mongoDatabase,
                        maxPoolSize = mongoMaxPoolSize,
                        connectTimeoutMs = mongoConnectTimeoutMs,
                        logger = logger,
                    )

                if (mongo.connect()) {
                    mongo.createIndexes()
                    mongoClient = mongo
                    useMongo = true
                } else {
                    logger.severe("[Storage] Could not connect to MongoDB. Falling back to YAML storage.")
                    logger.severe("[Storage] Use '/story reload' to retry the MongoDB connection.")
                }
            } else if (wantsSQLite) {
                val sqlite = SQLiteManager(dataFolder, logger)
                if (sqlite.connect()) {
                    sqlite.createTables()
                    sqliteClient = sqlite
                    useSQLite = true
                } else {
                    logger.severe("[Storage] Could not initialize SQLite. Falling back to YAML storage.")
                }
            }

            val npcStorage: NpcStorage
            val locationStorage: LocationStorage
            val questStorage: QuestStorage
            val sessionStorage: SessionStorage
            val relationshipStorage: RelationshipStorage
            val loreStorage: LoreStorage
            val playerStorage: PlayerStorage

            if (useMongo) {
                val mc = mongoClient!!
                npcStorage = MongoNpcStorage(mc, logger)
                locationStorage = MongoLocationStorage(mc)
                questStorage = MongoQuestStorage(mc)
                sessionStorage = MongoSessionStorage(mc)
                relationshipStorage = MongoRelationshipStorage(mc)
                loreStorage = MongoLoreStorage(mc)
                playerStorage = MongoPlayerStorage(mc)
            } else if (useSQLite) {
                val sc = sqliteClient!!
                npcStorage = SQLiteNpcStorage(sc, logger)
                locationStorage = SQLiteLocationStorage(sc)
                questStorage = SQLiteQuestStorage(sc)
                sessionStorage = SQLiteSessionStorage(sc)
                relationshipStorage = SQLiteRelationshipStorage(sc)
                loreStorage = SQLiteLoreStorage(sc)
                playerStorage = SQLitePlayerStorage(sc)
            } else {
                @Suppress("DEPRECATION")
                npcStorage = YamlNpcStorage(File(dataFolder, "npcs"), logger)
                @Suppress("DEPRECATION")
                locationStorage = YamlLocationStorage(File(dataFolder, "locations"), logger)
                @Suppress("DEPRECATION")
                questStorage = YamlQuestStorage(File(dataFolder, "quests"), File(dataFolder, "playerquests"), logger)
                @Suppress("DEPRECATION")
                sessionStorage = YamlSessionStorage(File(dataFolder, "sessions"), logger)
                @Suppress("DEPRECATION")
                relationshipStorage = YamlRelationshipStorage(File(dataFolder, "relationships"), logger)
                @Suppress("DEPRECATION")
                loreStorage = YamlLoreStorage(File(dataFolder, "lore"), logger)
                @Suppress("DEPRECATION")
                playerStorage = YamlPlayerStorage(dataFolder, logger)
            }

            val backendName =
                when {
                    useMongo -> "MongoDB"
                    useSQLite -> "SQLite"
                    else -> "YAML (deprecated)"
                }
            logger.info("Storage backend: $backendName")

            return StorageFactory(
                npcStorage = npcStorage,
                locationStorage = locationStorage,
                questStorage = questStorage,
                sessionStorage = sessionStorage,
                relationshipStorage = relationshipStorage,
                loreStorage = loreStorage,
                playerStorage = playerStorage,
                backendName = backendName,
                mongoClientManager = mongoClient,
                sqliteManager = sqliteClient,
                dataFolder = dataFolder,
                logger = logger,
                mongoUri = mongoUri,
                mongoDatabase = mongoDatabase,
                mongoMaxPoolSize = mongoMaxPoolSize,
                mongoConnectTimeoutMs = mongoConnectTimeoutMs,
            )
        }
    }
}
