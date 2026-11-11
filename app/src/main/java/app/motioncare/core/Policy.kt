// ver 1.2.0 | 2026-11-06 16:39
package app.motioncare.core

/** Pure rules, no Android types, so they can be unit tested. */
object Policy {
    /** Driving = gear D and moving. */
    fun isDriving(gear: Gear, speedKmh: Double): Boolean = gear == Gear.DRIVE && speedKmh >= 3.0

    /** (Not used by "멀미 나요" any more: it is active while driving too.) True when the car is (almost) stopped; in P by definition. */
    fun isStopped(gear: Gear, speedKmh: Double): Boolean = gear == Gear.PARK || speedKmh < 5.0

    /** The window is only cracked open at low speed. */
    const val WINDOW_MAX_KMH = 30.0

    fun windowAllowed(gear: Gear, speedKmh: Double): Boolean = gear == Gear.PARK || speedKmh <= WINDOW_MAX_KMH

    /** Relief ends by itself after this many minutes. */
    const val RELIEF_MAX_MIN = 10

    /** After Gleo AI asks "which seat?", wait this many seconds for the answer; then relieve every occupied seat. */
    const val SEAT_ASK_SEC = 12

    /** Parked this long after a drive = trip over. */
    const val TRIP_END_PARK_SEC = 30

    /**
     * Minutes after departure at which a rest should be offered for [destination]:
     * the median of earlier sick moments for that destination, minus a 5 minute lead (never below 5).
     * Null when there is no earlier record for the destination.
     */
    fun restAfterMin(history: List<HistoryEvent>, destination: String): Int? {
        if (destination.isBlank()) return null
        val mins = history.filter { it.destination == destination && it.elapsedMin > 0 }
            .map { it.elapsedMin }.sorted()
        if (mins.isEmpty()) return null
        val median = if (mins.size % 2 == 1) mins[mins.size / 2] else (mins[mins.size / 2 - 1] + mins[mins.size / 2]) / 2
        return (median - 5).coerceAtLeast(5)
    }

    /** Stops closer than this are skipped (too late to leave the road); the screen shows at most three digits. */
    const val REST_MIN_KM = 3.0
    const val REST_MAX_KM = 999.0

    /**
     * The stop to offer: the nearest one with a toilet; a stop with a nursing room or kids zone wins
     * when it is within 20 km. Only stops 3 to 999 km ahead are offered.
     */
    fun pickStop(stops: List<RestStop>): RestStop? {
        val usable = stops.filter { it.toilet && it.distanceKm in REST_MIN_KM..REST_MAX_KM }.sortedBy { it.distanceKm }
        val care = usable.firstOrNull { (it.nursing || it.kids) && it.distanceKm <= 20.0 }
        return care ?: usable.firstOrNull()
    }
}
