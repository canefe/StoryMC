package me.casperge.realisticseasons.api

data class SeasonDate(
    val month: Int = 1,
    val day: Int = 1,
    val year: Int = 1,
) {
    fun toString(formatted: Boolean): String =
        if (formatted) {
            "Day $day of Month $month, Year $year"
        } else {
            "$day/$month/$year"
        }
}

class SeasonsAPI private constructor() {
    companion object {
        private val instance: SeasonsAPI = SeasonsAPI()

        @JvmStatic
        fun getInstance(): SeasonsAPI = instance
    }

    fun getDate(defaultWorld: Any): SeasonDate = SeasonDate()

    fun getHours(defaultWorld: Any): Int = 0

    fun getMinutes(defaultWorld: Any): Int = 0

    fun getSeason(defaultWorld: Any): String? = null
}
