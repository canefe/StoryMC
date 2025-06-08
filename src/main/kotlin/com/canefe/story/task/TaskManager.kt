package com.canefe.story.task

import com.canefe.story.Story
import com.canefe.story.command.base.CommandComponentUtils
import com.canefe.story.util.Msg.sendRaw
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages permission tasks that require explicit approval from players with appropriate permissions
 */
class TaskManager private constructor(private val plugin: Story) {
	private val tasks = ConcurrentHashMap<Int, Task>()
	private val taskIdCounter = AtomicInteger(1)

	companion object {
		private var instance: TaskManager? = null

		@JvmStatic
		fun getInstance(plugin: Story): TaskManager {
			if (instance == null) {
				instance = TaskManager(plugin)
			}
			return instance!!
		}
	}

	/**
	 * Creates a new permission task and sends it to players with the specified permission
	 *
	 * @param description The description of the task
	 * @param permission Permission required to respond to this task
	 * @param onAccept Runnable to execute when the task is accepted
	 * @param onRefuse Runnable to execute when the task is refused
	 * @param timeoutSeconds Time after which the task automatically expires (-1 for no timeout)
	 * @param limitToSender If true, only sends task request to the provided sender
	 * @param sender Optional sender to limit task to
	 * @return The ID of the created task
	 */
	fun createTask(
		description: String,
		permission: String,
		onAccept: Runnable,
		onRefuse: Runnable,
		timeoutSeconds: Int = 300,
		limitToSender: Boolean = false,
		sender: CommandSender? = null,
	): Int {
		val taskId = taskIdCounter.getAndIncrement()

		val task = Task(
			id = taskId,
			description = description,
			permission = permission,
			onAccept = onAccept,
			onRefuse = onRefuse,
			createdAt = System.currentTimeMillis(),
			timeoutAt = if (timeoutSeconds > 0) System.currentTimeMillis() + (timeoutSeconds * 1000) else -1,
		)

		tasks[taskId] = task

		// Send task request to qualified players
		if (limitToSender && sender != null) {
			if (sender.hasPermission(permission)) {
				sendTaskRequest(sender, task)
			}
		} else {
			// Send to all online players with permission
			Bukkit.getOnlinePlayers().forEach { player ->
				if (player.hasPermission(permission)) {
					sendTaskRequest(player, task)
				}
			}

			// Also send to console
			sendTaskRequest(plugin.server.consoleSender, task)
		}

		// Set up timeout if needed
		if (timeoutSeconds > 0) {
			Bukkit.getScheduler().runTaskLater(
				plugin,
				Runnable {
					if (tasks.containsKey(taskId)) {
						// Task has timed out
						handleTaskRefusal(taskId)
					}
				},
				timeoutSeconds.toLong() * 20,
			) // Convert seconds to ticks
		}

		return taskId
	}

	/**
	 * Handle task acceptance
	 *
	 * @param taskId The ID of the task to accept
	 * @param player The player who accepted the task
	 * @return true if task was found and accepted, false otherwise
	 */
	fun acceptTask(taskId: Int, player: CommandSender): Boolean {
		val task = tasks.remove(taskId) ?: return false

		// Notify players with permission
		val acceptMessage = "<dark_gray>[<gold>Tasks</gold>]</dark_gray>" +
			" <green>Task #$taskId was accepted by ${player.name}</green>"
		broadcastToPermissionHolders(acceptMessage, task.permission)

		// Execute acceptance handler
		task.onAccept.run()
		return true
	}

	/**
	 * Handle task refusal
	 *
	 * @param taskId The ID of the task to refuse
	 * @param player The player who refused the task (null if timed out)
	 * @return true if task was found and refused, false otherwise
	 */
	fun refuseTask(taskId: Int, player: CommandSender? = null): Boolean {
		val task = tasks.remove(taskId) ?: return false

		val reason = if (player != null) "refused by ${player.name}" else "timed out"

		// Notify players with permission
		val refuseMessage = "<dark_gray>[<gold>Tasks</gold>]</dark_gray> <red>Task #$taskId was $reason</red>"
		broadcastToPermissionHolders(refuseMessage, task.permission)

		// Execute refusal handler
		task.onRefuse.run()
		return true
	}

	/**
	 * Private helper for handling task refusal (used for timeouts)
	 */
	private fun handleTaskRefusal(taskId: Int) {
		refuseTask(taskId, null)
	}

	/**
	 * Gets a list of all current tasks
	 * @return List of current tasks
	 */
	fun getActiveTasks(): List<Task> = tasks.values.toList()

	/**
	 * Sends a task request to a specific player
	 */
	private fun sendTaskRequest(sender: CommandSender, task: Task) {
		val mm = plugin.miniMessage

		// Empty line for better visibility
		sender.sendRaw(" ")

		// Create header
		val header = "<dark_gray>[<gold>Task Request #${task.id}</gold>]</dark_gray>"
		sender.sendRaw(header)

		// Send description
		sender.sendRaw("<white>${task.description}</white>")

		// Create buttons
		val acceptButton = CommandComponentUtils.createButton(
			mm,
			"Accept",
			"green",
			"run_command",
			"/story task accept ${task.id}",
			"Accept this task request",
		)

		val refuseButton = CommandComponentUtils.createButton(
			mm,
			"Refuse",
			"red",
			"run_command",
			"/story task deny ${task.id}",
			"Refuse this task request",
		)

		// Combine buttons with a separator
		val buttons = CommandComponentUtils.combineComponentsWithSeparator(
			mm,
			listOf(acceptButton, refuseButton),
			" ",
		)

		// Send buttons
		sender.sendMessage(buttons)

		// Empty line for better visibility
		sender.sendRaw(" ")
	}

	/**
	 * Broadcasts a message to all online players with a specific permission
	 */
	private fun broadcastToPermissionHolders(message: String, permission: String) {
		Bukkit.getOnlinePlayers().forEach { player ->
			if (player.hasPermission(permission)) {
				player.sendRaw(message)
			}
		}

		// Also send to console
		plugin.server.consoleSender.sendRaw(message)
	}

	/**
	 * Represents a permission task that is waiting for approval or rejection
	 */
	data class Task(
		val id: Int,
		val description: String,
		val permission: String,
		val onAccept: Runnable,
		val onRefuse: Runnable,
		val createdAt: Long,
		val timeoutAt: Long = -1,
	)
}
