// ver 1.0.0 | 2026-11-12 11:10
package app.motioncare.core

import kotlin.math.ceil

/** Road distance and time to one place, as the navigation answered (requestTrip -> onTripInfo). */
data class Trip(val distanceM: Int, val seconds: Int)

/**
 * "가까운 쉼터": the places nearest by road time, not by straight line. The navigation's road time already
 * counts the side of the road and the way in (a rest area on the other side, or one behind an interchange,
 * comes out long), so no highway check is needed. The screen shows the fastest three and marks the far ones;
 * the driver chooses.
 *
 * Steps: [candidates] picks a few places by straight line, the navigation is asked the road time to each,
 * then [rank] orders them by that time.
 */
object NearbyPlan {
    /** Places asked about (by straight line, nearest first). */
    const val CANDIDATES = 6

    /** Places shown. */
    const val SHOWN = 3

    /** Farther than this in a straight line counts as "no place nearby". */
    const val MAX_KM = 100.0

    /** More road minutes than this are marked "멀어요". */
    const val FAR_MIN = 30

    /** Ask again after the car has moved this far, or after [REFRESH_MS]. */
    const val REFRESH_KM = 1.0
    const val REFRESH_MS = 120_000L

    /** Places with a toilet within [MAX_KM], nearest first by straight line, at most [CANDIDATES]. */
    fun candidates(all: List<RestArea>, lat: Double, lon: Double): List<RestArea> =
        all.asSequence()
            .filter { it.toilet }
            .map { it to Geo.km(lat, lon, it.lat, it.lon) }
            .filter { it.second <= MAX_KM }
            .sortedBy { it.second }
            .map { it.first }
            .distinctBy { it.id }
            .take(CANDIDATES)
            .toList()

    /** The places the navigation answered for, fastest first, at most [SHOWN]. Unanswered ones are left out. */
    fun rank(answers: List<Pair<RestArea, Trip?>>): List<RestStop> =
        answers.mapNotNull { (a, t) -> t?.let { a to it } }
            .sortedWith(compareBy({ it.second.seconds }, { it.second.distanceM }))
            .take(SHOWN)
            .map { (a, t) -> stopOf(a, t) }

    fun stopOf(a: RestArea, t: Trip): RestStop = RestStop(
        id = a.id,
        name = a.name,
        distanceKm = t.distanceM / 1000.0,
        etaMin = ceil(t.seconds / 60.0).toInt().coerceAtLeast(1),
        toilet = a.toilet,
        nursing = a.nursing,
        kids = a.kids,
        kind = a.kind,
        direction = directionText(a)
    )

    /**
     * Short words for the side of the road: "순천기점 + 천안종점" -> "천안 방향"; the rest-area file's
     * "상행" / "하행" / "양방향" stay as they are.
     */
    fun directionText(a: RestArea): String {
        val d = a.direction.trim()
        val end = Regex("([^\\s+]+)종점").find(d)?.groupValues?.get(1)
        return if (end != null) "$end 방향" else d
    }
}
