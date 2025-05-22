package com.canefe.story.webui

import com.canefe.story.Story
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import fi.iki.elonen.NanoHTTPD
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WebUIServer(
	private val plugin: Story,
	private val port: Int = 7777, // Default port for WebUI
) : NanoHTTPD(port) {
	private val gson: Gson =
		GsonBuilder()
			.serializeNulls()
			.setPrettyPrinting()
			.create()

	private val activeSessions = ConcurrentHashMap<String, Long>() // Session ID -> Timestamp
	private val sessionExpiryMs = 3600000 // 1 hour

	init {
		plugin.logger.info("Starting WebUI server on port $port")
		start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
		plugin.logger.info("WebUI server started successfully")
	}

	override fun serve(session: IHTTPSession): Response {
		// Add CORS headers for development
		val headers = HashMap<String, String>()
		headers["Access-Control-Allow-Origin"] = "*"
		headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
		headers["Access-Control-Allow-Headers"] = "Content-Type"

		// Handle preflight OPTIONS request
		if (session.method == Method.OPTIONS) {
			return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
				.apply { headers.forEach { (k, v) -> addHeader(k, v) } }
		}

		// Check path
		val path = session.uri
		when {
			path == "/api/ping" -> {
				return handlePing(headers)
			}
			path == "/api/create-session" -> {
				return handleCreateSession(headers)
			}
			path.startsWith("/api/plugin-state") -> {
				// Extract session from query parameters
				val params = session.parameters
				val sessionId = params["session"]?.get(0)

				if (sessionId == null || !validateSession(sessionId)) {
					return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Invalid session")
						.apply { headers.forEach { (k, v) -> addHeader(k, v) } }
				}

				return when (session.method) {
					Method.GET -> handleGetPluginState(headers)
					Method.POST -> handlePostPluginState(session, headers)
					else ->
						newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
							.apply { headers.forEach { (k, v) -> addHeader(k, v) } }
				}
			}
			else -> {
				return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
					.apply { headers.forEach { (k, v) -> addHeader(k, v) } }
			}
		}
	}

	private fun handlePing(headers: Map<String, String>): Response {
		val response = mapOf("status" to "ok", "version" to plugin.description.version)
		return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
			.apply { headers.forEach { (k, v) -> addHeader(k, v) } }
	}

	private fun handleCreateSession(headers: Map<String, String>): Response {
		val sessionId = UUID.randomUUID().toString()
		activeSessions[sessionId] = System.currentTimeMillis()

		cleanExpiredSessions()

		val response = mapOf("sessionId" to sessionId)
		return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
			.apply { headers.forEach { (k, v) -> addHeader(k, v) } }
	}

	private fun handleGetPluginState(headers: Map<String, String>): Response {
		try {
			val pluginState = serializePluginState()
			val jsonState = gson.toJson(pluginState)

			return newFixedLengthResponse(Response.Status.OK, "application/json", jsonState)
				.apply { headers.forEach { (k, v) -> addHeader(k, v) } }
		} catch (e: Exception) {
			plugin.logger.severe("Error serializing plugin state: ${e.message}")
			e.printStackTrace()

			return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
				.apply { headers.forEach { (k, v) -> addHeader(k, v) } }
		}
	}

	private fun handlePostPluginState(
		session: IHTTPSession,
		headers: Map<String, String>,
	): Response {
		try {
			// Parse the request body
			val bodySize = session.headers["content-length"]?.toInt() ?: 0
			val buffer = ByteArray(bodySize)
			session.inputStream.read(buffer, 0, bodySize)
			val body = String(buffer)

			// Parse JSON into plugin state
			val updatedState = gson.fromJson(body, PluginStateDTO::class.java)

			val response = mapOf("status" to "success", "message" to "Plugin state updated successfully")
			return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(response))
				.apply { headers.forEach { (k, v) -> addHeader(k, v) } }
		} catch (e: Exception) {
			plugin.logger.severe("Error applying plugin state: ${e.message}")
			e.printStackTrace()

			return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
				.apply { headers.forEach { (k, v) -> addHeader(k, v) } }
		}
	}

	private fun serializePluginState(): PluginStateDTO {
		// Create a complete snapshot of the current plugin state
		val conversations = plugin.conversationManager.getAllActiveConversations().map { it.toDTO() }

		return PluginStateDTO(
			conversations = conversations,
		)
	}

	private fun validateSession(sessionId: String): Boolean {
		val timestamp = activeSessions[sessionId] ?: return false
		val now = System.currentTimeMillis()

		// Check if session is expired
		if (now - timestamp > sessionExpiryMs) {
			activeSessions.remove(sessionId)
			return false
		}

		// Refresh session timestamp
		activeSessions[sessionId] = now
		return true
	}

	private fun cleanExpiredSessions() {
		val now = System.currentTimeMillis()
		val expiredSessions =
			activeSessions.entries
				.filter { now - it.value > sessionExpiryMs }
				.map { it.key }

		expiredSessions.forEach { activeSessions.remove(it) }
	}

	fun shutdown() {
		try {
			stop()
			plugin.logger.info("WebUI server stopped")
		} catch (e: Exception) {
			plugin.logger.warning("Error stopping WebUI server: ${e.message}")
		}
	}
}
