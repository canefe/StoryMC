package com.canefe.story.service

import com.canefe.story.Story
import com.canefe.story.conversation.ConversationMessage
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import kotlin.code
import kotlin.collections.get
import kotlin.compareTo
import kotlin.text.get
import kotlin.toString

class AIResponseService(private val plugin: Story) {
	private val gson = Gson()
	private val apiKey: String
		get() = plugin.config.openAIKey

	private val aiModel: String
		get() = plugin.config.aiModel

	private val aiConversationModel: String
		get() = plugin.config.aiConversationModel

	private val maxTokens: Int
		get() = plugin.config.maxTokens

	// Shared HTTP client for better connection pooling
	private val httpClient =
		OkHttpClient
			.Builder()
			.connectTimeout(30, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			.readTimeout(60, TimeUnit.SECONDS)
			.build()

	// Semaphore to limit concurrent API calls
	private val apiRateLimiter = Semaphore(10)

	// Request queue tracking
	private val pendingRequests = AtomicInteger(0)

	/**
	 * Gets an AI response from OpenRouter.ai with streaming support
	 *
	 * @param conversation The conversation history to send
	 * @param streamHandler A callback to process each token as it arrives
	 * @return The complete AI response or null if an error occurred
	 */
	fun getAIResponseStreaming(
		conversation: List<ConversationMessage>,
		streamHandler: (String) -> Unit,
		lowCost: Boolean = false,
	): String? {
		if (apiKey.isEmpty()) {
			plugin.logger.warning("OpenAI API Key is not set!")
			return null
		}

		val model =
			if (lowCost) {
				aiConversationModel
			} else {
				aiModel
			}

		// For very high load situations, reject requests if too many pending
		if (pendingRequests.get() > 20) {
			plugin.logger.warning("Too many pending AI requests, rejecting new request")
			return null
		}

		pendingRequests.incrementAndGet()

		try {
			// Try to acquire a permit with timeout
			if (!apiRateLimiter.tryAcquire(10, TimeUnit.SECONDS)) {
				pendingRequests.decrementAndGet()
				plugin.logger.warning("API rate limit reached, couldn't acquire permit within timeout")
				return null
			}

			try {
				// Create JSON request
				val requestObject = JsonObject()
				requestObject.addProperty("model", model)
				requestObject.addProperty("max_tokens", maxTokens)
				requestObject.addProperty("stream", true) // Enable streaming

				val messagesArray = JsonArray()
				for (message in conversation) {
					val messageObject = JsonObject()
					messageObject.addProperty("role", message.role)
					messageObject.addProperty("content", message.content)
					messagesArray.add(messageObject)
				}
				requestObject.add("messages", messagesArray)

				// Build request
				val body =
					requestObject
						.toString()
						.toRequestBody("application/json".toMediaTypeOrNull())

				val request =
					Request
						.Builder()
						.url("https://openrouter.ai/api/v1/chat/completions")
						.addHeader("Authorization", "Bearer $apiKey")
						.addHeader("Content-Type", "application/json")
						.post(body)
						.build()

				// Execute streaming request
				httpClient.newCall(request).execute().use { response ->
					if (!response.isSuccessful) {
						plugin.logger.warning("API request failed: ${response.code} - ${response.message}")
						return null
					}

					val responseBody = response.body ?: return null
					val source = responseBody.source()
					source.timeout().deadline(60, TimeUnit.SECONDS)

					val resultBuilder = StringBuilder()

					while (!source.exhausted()) {
						val line = source.readUtf8Line() ?: continue

						// Skip empty lines and [DONE] marker
						if (line.isEmpty() || line == "data: [DONE]") {
							continue
						}

						// Only process data: lines
						if (line.startsWith("data: ")) {
							val jsonData = line.substring(6).trim() // Skip "data: " prefix

							try {
								val chunk = gson.fromJson(jsonData, JsonObject::class.java)

								// Extract token from delta content if available
								if (chunk.has("choices") && chunk.getAsJsonArray("choices").size() > 0) {
									val choice = chunk.getAsJsonArray("choices").get(0).asJsonObject
									if (choice.has("delta") && choice.getAsJsonObject("delta").has("content")) {
										val token = choice.getAsJsonObject("delta").get("content").asString
										resultBuilder.append(token)
										streamHandler(token)
									}
								}
							} catch (e: Exception) {
								plugin.logger.fine("Error parsing streaming response chunk: $jsonData")
								// Continue processing other chunks even if one fails
							}
						}
					}

					val result = resultBuilder.toString()
					return if (result.isNotEmpty()) result else null
				}
			} finally {
				// Always release the permit
				apiRateLimiter.release()
			}
		} catch (e: Exception) {
			plugin.logger.log(Level.SEVERE, "Error getting streaming AI response", e)
			return null
		} finally {
			pendingRequests.decrementAndGet()
		}
	}

	/**
	 * Gets an AI response from OpenRouter.ai with improved concurrency handling
	 * This overloaded version supports both streaming and non-streaming behavior
	 *
	 * @param conversation The conversation history to send
	 * @param useStreaming Whether to use streaming mode
	 * @param streamHandler Optional callback for handling streaming tokens
	 * @return The AI response or null if an error occurred
	 */
	fun getAIResponse(
		conversation: List<ConversationMessage>,
		useStreaming: Boolean = false,
		streamHandler: (String) -> Unit = {},
		lowCost: Boolean = false,
	): String? = if (useStreaming) {
		getAIResponseStreaming(conversation, streamHandler, lowCost)
	} else {
		getAIResponseNonStreaming(conversation, lowCost)
	}

	/**
	 * Gets an AI response from OpenRouter.ai with improved concurrency handling (non-streaming)
	 *
	 * @param conversation The conversation history to send
	 * @return The AI response or null if an error occurred
	 */
	private fun getAIResponseNonStreaming(conversation: List<ConversationMessage>, lowCost: Boolean = false): String? {
		if (apiKey.isEmpty()) {
			plugin.logger.warning("OpenAI API Key is not set!")
			return null
		}

		val aiModel =
			if (lowCost) {
				aiConversationModel
			} else {
				this.aiModel
			}

		// For very high load situations, reject requests if too many pending
		if (pendingRequests.get() > 20) {
			plugin.logger.warning("Too many pending AI requests, rejecting new request")
			return null
		}

		pendingRequests.incrementAndGet()

		try {
			// Try to acquire a permit with timeout
			if (!apiRateLimiter.tryAcquire(10, TimeUnit.SECONDS)) {
				pendingRequests.decrementAndGet()
				plugin.logger.warning("API rate limit reached, couldn't acquire permit within timeout")
				return null
			}

			try {
				// Create JSON request
				val requestObject = JsonObject()
				requestObject.addProperty("model", aiModel)
				requestObject.addProperty("max_tokens", maxTokens)

				val messagesArray = JsonArray()
				for (message in conversation) {
					val messageObject = JsonObject()
					messageObject.addProperty("role", message.role)
					messageObject.addProperty("content", message.content)
					messagesArray.add(messageObject)
				}
				requestObject.add("messages", messagesArray)

				// Build request
				val body =
					requestObject
						.toString()
						.toRequestBody("application/json".toMediaTypeOrNull())

				val request =
					Request
						.Builder()
						.url("https://openrouter.ai/api/v1/chat/completions")
						.addHeader("Authorization", "Bearer $apiKey")
						.addHeader("Content-Type", "application/json")
						.post(body)
						.build()

				// Execute request synchronously
				httpClient.newCall(request).execute().use { response ->
					if (!response.isSuccessful) {
						plugin.logger.warning("API request failed: ${response.code} - ${response.message}")
						return null
					}

					// Parse response
					val jsonResponse = response.body?.string() ?: return null
					val responseObject = gson.fromJson(jsonResponse, JsonObject::class.java)

					if (responseObject.has("choices") && responseObject.getAsJsonArray("choices").size() > 0) {
						val choice = responseObject.getAsJsonArray("choices").get(0).asJsonObject
						if (choice.has("message") && choice.getAsJsonObject("message").has("content")) {
							return choice.getAsJsonObject("message").get("content").asString
						}
					}

					plugin.logger.warning("Unexpected API response format: $jsonResponse")
					return null
				}
			} finally {
				// Always release the permit
				apiRateLimiter.release()
			}
		} catch (e: Exception) {
			plugin.logger.log(Level.SEVERE, "Error getting AI response", e)
			return null
		} finally {
			pendingRequests.decrementAndGet()
		}
	}

	/**
	 * Gets an AI response asynchronously
	 *
	 * @param conversation The conversation history to send
	 * @return A CompletableFuture with the AI response
	 */
	fun getAIResponseAsync(
		conversation: List<ConversationMessage>,
		lowCost: Boolean = false,
	): CompletableFuture<String?> = CompletableFuture.supplyAsync { getAIResponse(conversation, lowCost = lowCost) }

	// Helper method to build conversation context
	private fun buildConversationContext(conversation: List<ConversationMessage>): String {
		val contextBuilder = StringBuilder()
		for (msg in conversation) {
			contextBuilder
				.append(msg.role)
				.append(": ")
				.append(msg.content)
				.append("\n")
		}
		return contextBuilder.toString()
	}

	companion object {
		private var instance: AIResponseService? = null

		@JvmStatic
		fun getInstance(plugin: Story): AIResponseService {
			if (instance == null) {
				instance = AIResponseService(plugin)
			}
			return instance!!
		}
	}
}
