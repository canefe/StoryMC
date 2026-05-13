package com.canefe.story.combat

enum class SwingDir {
    OVERHEAD,
    LEFT,
    RIGHT,
    THRUST,
    ;

    companion object {
        fun fromWire(name: String?): SwingDir? =
            when (name?.lowercase()) {
                "overhead" -> OVERHEAD
                "left" -> LEFT
                "right" -> RIGHT
                "thrust" -> THRUST
                else -> null
            }
    }
}
