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
class TaskManager private constructor(
    private val plugin: Story,
) {
    private val tasks = ConcurrentHashMap<Int, Task>()
    private val dialoguePathTasks = ConcurrentHashMap<Int, DialoguePathTask>()
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

        val task =
            Task(
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
    fun acceptTask(
        taskId: Int,
        player: CommandSender,
    ): Boolean {
        val task = tasks.remove(taskId) ?: return false

        // Notify players with permission
        val acceptMessage =
            "<dark_gray>[<gold>Tasks</gold>]</dark_gray>" +
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
    fun refuseTask(
        taskId: Int,
        player: CommandSender? = null,
    ): Boolean {
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
     * Creates a new dialogue path selection task with three options
     *
     * @param description The description of the dialogue selection
     * @param option1 First dialogue option
     * @param option2 Second dialogue option
     * @param option3 Third dialogue option
     * @param onOption1 Runnable to execute when option 1 is selected
     * @param onOption2 Runnable to execute when option 2 is selected
     * @param onOption3 Runnable to execute when option 3 is selected
     * @param permission Permission required to respond to this task
     * @param timeoutSeconds Time after which the task automatically expires (-1 for no timeout)
     * @param limitToSender If true, only sends task request to the provided sender
     * @param sender Optional sender to limit task to
     * @return The ID of the created task
     */
    fun createDialoguePathTask(
        description: String,
        option1: String,
        option2: String,
        option3: String,
        onOption1: Runnable,
        onOption2: Runnable,
        onOption3: Runnable,
        permission: String = "story.dm",
        timeoutSeconds: Int = 120,
        limitToSender: Boolean = false,
        sender: CommandSender? = null,
    ): Int {
        val taskId = taskIdCounter.getAndIncrement()

        val task =
            DialoguePathTask(
                id = taskId,
                description = description,
                option1 = option1,
                option2 = option2,
                option3 = option3,
                onOption1 = onOption1,
                onOption2 = onOption2,
                onOption3 = onOption3,
                permission = permission,
                createdAt = System.currentTimeMillis(),
                timeoutAt = if (timeoutSeconds > 0) System.currentTimeMillis() + (timeoutSeconds * 1000) else -1,
            )

        dialoguePathTasks[taskId] = task

        // Send task request to qualified players
        if (limitToSender && sender != null) {
            if (sender.hasPermission(permission)) {
                sendDialoguePathRequest(sender, task)
            }
        } else {
            // Send to all online players with permission
            Bukkit.getOnlinePlayers().forEach { player ->
                if (player.hasPermission(permission)) {
                    sendDialoguePathRequest(player, task)
                }
            }

            // Also send to console
            sendDialoguePathRequest(plugin.server.consoleSender, task)
        }

        // Set up timeout if needed
        if (timeoutSeconds > 0) {
            Bukkit.getScheduler().runTaskLater(
                plugin,
                Runnable {
                    if (dialoguePathTasks.containsKey(taskId)) {
                        // Task has timed out - select option 1 as default
                        handleDialoguePathSelection(taskId, 1)
                    }
                },
                timeoutSeconds.toLong() * 20,
            ) // Convert seconds to ticks
        }

        return taskId
    }

    /**
     * Handle dialogue path selection
     *
     * @param taskId The ID of the dialogue path task
     * @param optionNumber The selected option (1, 2, or 3)
     * @param player The player who made the selection (null if timed out)
     * @return true if task was found and option selected, false otherwise
     */
    fun selectDialogueOption(
        taskId: Int,
        optionNumber: Int,
        player: CommandSender? = null,
    ): Boolean {
        val task = dialoguePathTasks.remove(taskId) ?: return false

        if (optionNumber < 1 || optionNumber > 3) return false

        val selectionBy = if (player != null) "selected by ${player.name}" else "auto-selected (timeout)"

        // Notify players with permission
        val selectionMessage =
            "<dark_gray>[<gold>Dialogue</gold>]</dark_gray>" +
                " <yellow>Dialogue path #$taskId option $optionNumber was $selectionBy</yellow>"
        broadcastToPermissionHolders(selectionMessage, task.permission)

        // Execute the appropriate handler
        when (optionNumber) {
            1 -> task.onOption1.run()
            2 -> task.onOption2.run()
            3 -> task.onOption3.run()
        }

        return true
    }

    /**
     * Private helper for handling dialogue path selection (used for timeouts)
     */
    private fun handleDialoguePathSelection(
        taskId: Int,
        optionNumber: Int,
    ) {
        selectDialogueOption(taskId, optionNumber, null)
    }

    /**
     * Sends a task request to a specific player
     */
    private fun sendTaskRequest(
        sender: CommandSender,
        task: Task,
    ) {
        val mm = plugin.miniMessage

        // Empty line for better visibility
        sender.sendRaw(" ")

        // Create header
        val header = "<dark_gray>[<gold>Task Request #${task.id}</gold>]</dark_gray>"
        sender.sendRaw(header)

        // Send description
        sender.sendRaw("<white>${task.description}</white>")

        // Create buttons
        val acceptButton =
            CommandComponentUtils.createButton(
                mm,
                "Accept",
                "green",
                "run_command",
                "/story task accept ${task.id}",
                "Accept this task request",
            )

        val refuseButton =
            CommandComponentUtils.createButton(
                mm,
                "Refuse",
                "red",
                "run_command",
                "/story task deny ${task.id}",
                "Refuse this task request",
            )

        // Combine buttons with a separator
        val buttons =
            CommandComponentUtils.combineComponentsWithSeparator(
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
     * Sends a dialogue path selection request to a specific player
     */
    private fun sendDialoguePathRequest(
        sender: CommandSender,
        task: DialoguePathTask,
    ) {
        val mm = plugin.miniMessage

        // Empty line for better visibility
        sender.sendRaw(" ")

        // Create header
        val header = "<dark_gray>[<gold>Dialogue Path Selection #${task.id}</gold>]</dark_gray>"
        sender.sendRaw(header)

        // Send description
        sender.sendRaw("<white>${task.description}</white>")

        // Create option buttons
        val option1Button =
            CommandComponentUtils.createButton(
                mm,
                "Option 1",
                "green",
                "run_command",
                "/story dialogue select ${task.id} 1",
                "Select: ${task.option1.take(50)}${if (task.option1.length > 50) "..." else ""}",
            )

        val option2Button =
            CommandComponentUtils.createButton(
                mm,
                "Option 2",
                "yellow",
                "run_command",
                "/story dialogue select ${task.id} 2",
                "Select: ${task.option2.take(50)}${if (task.option2.length > 50) "..." else ""}",
            )

        val option3Button =
            CommandComponentUtils.createButton(
                mm,
                "Option 3",
                "red",
                "run_command",
                "/story dialogue select ${task.id} 3",
                "Select: ${task.option3.take(50)}${if (task.option3.length > 50) "..." else ""}",
            )

        // Send each option with its text
        sender.sendRaw("<green>1.</green> <white>${task.option1}</white>")
        sender.sendMessage(option1Button)

        sender.sendRaw("<yellow>2.</yellow> <white>${task.option2}</white>")
        sender.sendMessage(option2Button)

        sender.sendRaw("<red>3.</red> <white>${task.option3}</white>")
        sender.sendMessage(option3Button)

        // Empty line for better visibility
        sender.sendRaw(" ")
    }

    /**
     * Broadcasts a message to all online players with a specific permission
     */
    private fun broadcastToPermissionHolders(
        message: String,
        permission: String,
    ) {
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

    /**
     * Represents a dialogue path selection task with three options
     */
    data class DialoguePathTask(
        val id: Int,
        val description: String,
        val option1: String,
        val option2: String,
        val option3: String,
        val onOption1: Runnable,
        val onOption2: Runnable,
        val onOption3: Runnable,
        val permission: String,
        val createdAt: Long,
        val timeoutAt: Long = -1,
    )
}
