package com.canefe.story.npc.schedule

class NPCSchedule(
    val npcName: String,
) {
    val entries: MutableList<ScheduleEntry> = ArrayList()

    fun addEntry(entry: ScheduleEntry) {
        entries.add(entry)
        entries.sortBy { it.time }
    }

    fun getEntryForTime(currentHour: Int): ScheduleEntry? {
        var bestEntry: ScheduleEntry? = null
        var bestTimeDiff = 24

        for (entry in entries) {
            val entryTime = entry.time
            if (entryTime <= currentHour) {
                val diff = currentHour - entryTime
                if (diff < bestTimeDiff) {
                    bestTimeDiff = diff
                    bestEntry = entry
                }
            }
        }

        if (bestEntry == null && entries.isNotEmpty()) {
            return entries[entries.size - 1]
        }

        return bestEntry
    }
}

class ScheduleEntry(
    val time: Int,
    var locationName: String?,
    val action: String?,
    val dialogue: List<String>? = null,
    val random: Boolean = false,
    val duty: String? = null,
)

data class OccupancyInfo(
    val npcName: String,
    val action: String,
    val timestamp: Long = System.currentTimeMillis(),
)
