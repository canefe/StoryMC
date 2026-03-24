package com.canefe.story.storage

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.IndexOptions
import org.bson.Document
import org.bson.codecs.configuration.CodecRegistries
import org.bson.codecs.kotlinx.KotlinSerializerCodec
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class MongoClientManager(
    private val uri: String,
    private val databaseName: String,
    private val maxPoolSize: Int,
    private val connectTimeoutMs: Int,
    private val logger: Logger,
) {
    private lateinit var client: MongoClient
    private lateinit var database: MongoDatabase

    fun connect(): Boolean {
        val kotlinCodecs = CodecRegistries.fromCodecs(
            KotlinSerializerCodec.create<WorldEvent>(),
            KotlinSerializerCodec.create<Rumor>(),
            KotlinSerializerCodec.create<LocationDocument>(),
        )

        val codecRegistry = CodecRegistries.fromRegistries(
            kotlinCodecs,
            MongoClientSettings.getDefaultCodecRegistry(),
        )

        val settings =
            MongoClientSettings
                .builder()
                .applyConnectionString(ConnectionString(uri))
                .codecRegistry(codecRegistry)
                .applyToConnectionPoolSettings { builder ->
                    builder.maxSize(maxPoolSize)
                }.applyToClusterSettings { builder ->
                    builder.serverSelectionTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                }.build()

        client = MongoClients.create(settings)
        database = client.getDatabase(databaseName)

        return try {
            database.runCommand(Document("ping", 1))
            logger.info("[MongoDB] Connected to $uri (database: $databaseName)")
            true
        } catch (e: Exception) {
            logger.severe("[MongoDB] Failed to connect to $uri: ${e.message}")
            client.close()
            false
        }
    }

    fun getDatabase(): MongoDatabase = database

    fun getCollection(name: String): MongoCollection<Document> = database.getCollection(name)

    fun <T : Any> getTypedCollection(name: String, clazz: Class<T>): MongoCollection<T> =
        database.getCollection(name, clazz)

    fun close() {
        if (::client.isInitialized) {
            client.close()
            logger.info("[MongoDB] Connection closed")
        }
    }

    fun createIndexes() {
        // NPCs
        val npcs = getCollection("npcs")
        npcs.createIndex(Document("filename", 1), IndexOptions().unique(true))
        npcs.createIndex(Document("displayHandle", 1), IndexOptions().sparse(true))
        npcs.createIndex(Document("name", 1))

        // Locations
        val locations = getCollection("locations")
        locations.createIndex(Document("name", 1), IndexOptions().unique(true))
        locations.createIndex(Document("parentLocationName", 1))

        // Quests
        val quests = getCollection("quests")
        quests.createIndex(Document("questId", 1), IndexOptions().unique(true))

        // Player quests
        val playerQuests = getCollection("player_quests")
        playerQuests.createIndex(
            Document("playerId", 1).append("questId", 1),
            IndexOptions().unique(true),
        )

        // Sessions
        val sessions = getCollection("sessions")
        sessions.createIndex(Document("sessionId", 1), IndexOptions().unique(true))
        sessions.createIndex(Document("active", 1))

        // Relationships
        val relationships = getCollection("relationships")
        relationships.createIndex(
            Document("sourceId", 1).append("targetName", 1),
            IndexOptions().unique(true),
        )

        // Lore books
        val loreBooks = getCollection("lore_books")
        loreBooks.createIndex(Document("nameLower", 1), IndexOptions().unique(true))
        loreBooks.createIndex(Document("keywords", 1))

        // Teams
        val teams = getCollection("teams")
        teams.createIndex(Document("teamName", 1), IndexOptions().unique(true))

        logger.info("[MongoDB] Indexes created successfully")
    }
}
