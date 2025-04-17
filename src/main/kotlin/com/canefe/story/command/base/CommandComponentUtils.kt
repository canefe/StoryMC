package com.canefe.story.command.base

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage

object CommandComponentUtils {

    fun combineWithSeparator(mm: MiniMessage, components: List<Component>, separator: String): Component {
        val sep = mm.deserialize(separator)
        return components.reduceIndexed { i, acc, comp ->
            if (i == 0) acc else acc.append(sep).append(comp)
        }
    }

    fun createButton(
        mm: MiniMessage,
        label: String,
        color: String,
        clickAction: String,
        command: String,
        hoverText: String
    ): Component {
        return mm.deserialize(
            "<click:${clickAction}:'$command'>" +
                    "<hover:show_text:'$hoverText'>" +
                    "<gray>[<$color>$label</$color>]</gray></hover></click>"
        )
    }

    fun combineComponentsWithSeparator(
        mm: MiniMessage,
        components: List<Component>,
        separatorText: String
    ): Component {
        val separator = mm.deserialize(separatorText)

        var result = Component.empty()
        var first = true

        for (component in components) {
            if (!first) {
                result = result.append(separator)
            } else {
                first = false
            }

            result = result.append(component)
        }

        return result
    }

    fun escapeForCommand(text: String): String {
        return text.replace("'", "\\'").replace("\"", "\\\"")
    }
}
