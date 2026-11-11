// ver 1.0.0 | 2026-11-05 19:30
package app.motioncare.core

/** Text and counts shown under the car on the home screen. No Android types. */
object SeatSummary {

    /** One short line for the chosen seats, e.g. "3열 전체 · 1열 왼쪽". Rows are listed from the back. */
    fun text(selected: Set<String>): String {
        val parts = mutableListOf<String>()
        for (row in 3 downTo 1) {
            val chosen = Seat.entries.filter { it.row == row && it.key in selected }
            if (chosen.isEmpty()) continue
            val drawnInRow = Seat.drawn.filter { it.row == row }
            parts += if (chosen.containsAll(drawnInRow)) {
                "${row}열 전체"
            } else {
                "${row}열 " + chosen.joinToString(", ") { it.side }
            }
        }
        return if (parts.isEmpty()) "고르지 않았어요" else parts.joinToString(" · ")
    }

    /** People sitting: the drawn seats the vehicle reports as occupied. */
    fun occupiedCount(occupancy: Map<String, Boolean?>): Int =
        Seat.drawn.count { occupancy[it.key] == true }
}
