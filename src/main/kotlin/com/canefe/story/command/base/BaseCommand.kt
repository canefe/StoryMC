package com.canefe.story.command.base

/**
 * Base interface for all CommandAPI-based commands
 */
interface BaseCommand {
    /**
     * Register this command with CommandAPI
     */
    fun register()
}
