package com.canefe.story.bridge

import com.canefe.story.Story
import com.canefe.story.intelligence.EventType
import com.canefe.story.intelligence.PermissionAskDTO
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bukkit.Bukkit
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges story-go permission.ask events to (a) the in-game [com.canefe.story.task.TaskManager]
 * UX (chat-button accept/deny, console echo, server-side timeout) and (b) a
 * parallel sliding toast HUD in StoryClient via plugin-message packets.
 *
 * Inbound from Go (WebSocket → StoryEventBus):
 *   - [EventType.PERMISSION_ASK] → [PermissionAskDTO] → createTask + toast packet
 *
 * Inbound from clients (plugin message channel "story:permission_response"):
 *   - Parse [PermissionResponseDTO] → resolve requestId → taskId → accept/refuse
 *
 * Outbound to clients:
 *   - "story:permission_prompt" carrying [PermissionPromptDTO] JSON
 *
 * Outbound to Go (event bus):
 *   - [PermissionResponseEvent] — emitted from the task's onAccept/onRefuse,
 *     so the chat-button UX, the toast UX, and the timeout all funnel
 *     through the same single response per request.
 */
class PermissionRelay(private val plugin: Story) : PacketListener {

    private val CHANNEL_OUT = "story:permission_prompt"
    private val CHANNEL_IN = "story:permission_response"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Tracks pending tasks so an inbound C2S response (toast Y/N) can route
     * to the matching TaskManager entry. The chat-button UX uses /story task
     * accept|deny directly, so it doesn't need this map.
     */
    private val pendingByRequestId = ConcurrentHashMap<String, Int>()

    fun register() {
        plugin.eventBus.onType(EventType.PERMISSION_ASK) { event ->
            val data = event.toWireData() ?: return@onType
            val dto = try {
                json.decodeFromString<PermissionAskDTO>(data.toString())
            } catch (e: Exception) {
                plugin.logger.warning("[PermissionRelay] Failed to parse PermissionAskDTO: ${e.message}")
                return@onType
            }
            handleAsk(dto)
        }

        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CHANNEL_OUT)
        PacketEvents.getAPI().eventManager.registerListener(this, PacketListenerPriority.NORMAL)

        plugin.logger.info(
            "[PermissionRelay] Registered listener for ${EventType.PERMISSION_ASK}; channels out=$CHANNEL_OUT in=$CHANNEL_IN",
        )
    }

    fun unregister() {
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL_OUT)
    }

    private fun handleAsk(ask: PermissionAskDTO) {
        // Short-circuit when no DM is online — story-go's contract is
        // default-deny, and we want to reply *immediately* rather than make
        // it wait for the full configured timeout to elapse.
        val dmPlayers = Bukkit.getOnlinePlayers().filter { it.hasPermission(ask.permission) }
        if (dmPlayers.isEmpty()) {
            plugin.logger.info(
                "[PermissionRelay] No online ${ask.permission} holder for trigger=${ask.trigger} req=${ask.requestId} — denying immediately",
            )
            reply(ask.requestId, accepted = false)
            return
        }

        val taskId = plugin.taskManager.createTask(
            description = ask.description,
            permission = ask.permission,
            onAccept = Runnable {
                pendingByRequestId.remove(ask.requestId)
                reply(ask.requestId, accepted = true)
            },
            onRefuse = Runnable {
                pendingByRequestId.remove(ask.requestId)
                reply(ask.requestId, accepted = false)
            },
            timeoutSeconds = ask.timeoutSec.coerceAtLeast(1),
        )
        pendingByRequestId[ask.requestId] = taskId

        // Also push the toast to every online DM. Failure to send to one
        // player doesn't affect the others or the TaskManager flow.
        val prompt = PermissionPromptDTO(
            requestId = ask.requestId,
            trigger = ask.trigger,
            description = ask.description,
            timeoutSec = ask.timeoutSec,
        )
        val payload = json.encodeToString(prompt).toByteArray(Charsets.UTF_8)
        for (player in dmPlayers) {
            try {
                player.sendPluginMessage(plugin, CHANNEL_OUT, payload)
            } catch (e: Exception) {
                plugin.logger.warning("[PermissionRelay] toast send to ${player.name} failed: ${e.message}")
            }
        }
    }

    private fun reply(requestId: String, accepted: Boolean) {
        plugin.eventBus.emit(PermissionResponseEvent(requestId = requestId, accepted = accepted))
    }

    override fun onPacketReceive(event: PacketReceiveEvent) {
        if (event.packetType !== PacketType.Play.Client.PLUGIN_MESSAGE) return
        val wrapper = WrapperPlayClientPluginMessage(event)
        if (wrapper.channelName != CHANNEL_IN) return

        val player = Bukkit.getPlayer(event.user.uuid) ?: return
        val data = wrapper.data

        Bukkit.getScheduler().runTask(plugin, Runnable {
            handleResponse(player, data)
        })
    }

    private fun handleResponse(player: org.bukkit.entity.Player, data: ByteArray) {
        val text = try {
            String(data, Charsets.UTF_8)
        } catch (e: Exception) {
            plugin.logger.warning("[PermissionRelay] decode plugin message from ${player.name} failed: ${e.message}")
            return
        }

        val dto = try {
            json.decodeFromString<PermissionResponseDTO>(text)
        } catch (e: Exception) {
            plugin.logger.warning("[PermissionRelay] parse PermissionResponseDTO from ${player.name} failed: ${e.message}")
            return
        }

        val taskId = pendingByRequestId[dto.requestId]
        if (taskId == null) {
            // Already resolved (timeout, chat-button, race with another DM).
            // Silent — the toast on this client should just dismiss locally.
            return
        }

        if (dto.accepted) {
            plugin.taskManager.acceptTask(taskId, player)
        } else {
            plugin.taskManager.refuseTask(taskId, player)
        }
    }
}

/**
 * S2C body of "story:permission_prompt". Sent as raw UTF-8 JSON.
 */
@Serializable
private data class PermissionPromptDTO(
    val requestId: String,
    val trigger: String,
    val description: String,
    val timeoutSec: Int,
)

/**
 * C2S body of "story:permission_response". Sent as raw UTF-8 JSON.
 */
@Serializable
private data class PermissionResponseDTO(
    val requestId: String,
    val accepted: Boolean,
)
