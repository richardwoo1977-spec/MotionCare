// ver 1.2.0 | 2026-11-11 18:30
package app.motioncare.core

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** The seats of the app. [key] equals the Vehicle SDK's SeatValue name. The home screen draws [drawn]. */
enum class Seat(val key: String, val ko: String, val short: String) {
    ROW_1_LEFT("ROW_1_LEFT", "운전석", "운전석"),
    ROW_1_RIGHT("ROW_1_RIGHT", "조수석", "조수석"),
    ROW_2_LEFT("ROW_2_LEFT", "뒷좌석 왼쪽", "뒤 왼쪽"),
    ROW_2_CENTER("ROW_2_CENTER", "뒷좌석 가운데", "뒤 가운데"),
    ROW_2_RIGHT("ROW_2_RIGHT", "뒷좌석 오른쪽", "뒤 오른쪽"),
    ROW_3_LEFT("ROW_3_LEFT", "3열 왼쪽", "3열 왼쪽"),
    ROW_3_CENTER("ROW_3_CENTER", "3열 가운데", "3열 가운데"),
    ROW_3_RIGHT("ROW_3_RIGHT", "3열 오른쪽", "3열 오른쪽");

    /** 1, 2 or 3, read from [key] (ROW_3_LEFT -> 3). */
    val row: Int get() = key.split('_')[1].toInt()

    /** 왼쪽 / 가운데 / 오른쪽 */
    val side: String
        get() = when (key.substringAfterLast('_')) {
            "LEFT" -> "왼쪽"
            "CENTER" -> "가운데"
            else -> "오른쪽"
        }

    companion object {
        fun fromKey(key: String): Seat? = entries.firstOrNull { it.key == key }

        /** The seats the home screen draws: 1열 2, 2열 2, 3열 3. */
        val drawn: List<Seat> = listOf(
            ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_RIGHT, ROW_3_LEFT, ROW_3_CENTER, ROW_3_RIGHT
        )
    }
}

enum class CareMode { IDLE, PREVENT, RELIEF }

enum class FanWant { NONE, PLUS_ONE, MAX }

/** What the cabin should look like right now. The vehicle link reconciles to this and restores the rest. */
data class Desired(
    val recircOff: Boolean = false,
    val fan: FanWant = FanWant.NONE,
    val faceDirection: Boolean = false,
    val powerOn: Boolean = false,
    val windowOpen: Boolean = false,
    /** the seats to look after; empty = the whole cabin */
    val targets: Set<Seat> = emptySet()
) {
    val isIdle: Boolean get() = !recircOff && fan == FanWant.NONE && !faceDirection && !powerOn && !windowOpen
}

data class RestStop(
    val id: String,
    val name: String,
    val distanceKm: Double,
    val etaMin: Int,
    val toilet: Boolean,
    val nursing: Boolean,
    val kids: Boolean
)

data class HistoryEvent(
    val epochMs: Long,
    val elapsedMin: Int,
    /** the seats, as [SeatKeys.join] writes them; empty = no seat known (whole cabin) */
    val seatKey: String,
    val destination: String
)

/** Several seats in one text field (history), and the words for them. */
object SeatKeys {
    fun join(seats: Set<Seat>): String = Seat.entries.filter { it in seats }.joinToString("+") { it.key }

    fun split(text: String): Set<String> = text.split('+').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** e.g. "2열 전체", "조수석 · 2열 오른쪽"; "자리 모름" when none. Old single keys read the same way. */
    fun label(text: String): String {
        val keys = split(text)
        return if (keys.isEmpty()) "자리 모름" else SeatSummary.text(keys).replace("1열 오른쪽", "조수석")
    }
}

enum class Level { OK, WARN, BAD, NONE }

data class CheckRow(val label: String, val level: Level, val detail: String)
