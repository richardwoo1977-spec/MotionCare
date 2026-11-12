// ver 1.3.0 | 2026-11-12 11:25
package app.motioncare.core

/** What the app asks the navigation for. [index] 0 = first waypoint, 1 = second. */
sealed class NaviCmd {
    data class Add(val stop: RestStop, val index: Int) : NaviCmd()
    data class Remove(val index: Int, val name: String) : NaviCmd()
    /** No route (or guiding to our stop): guide to [stop] as the destination. */
    data class Route(val stop: RestStop) : NaviCmd()
    /** End the guidance to the stop this app set as the destination. */
    object Cancel : NaviCmd()
}

/**
 * A route change the navigation has not confirmed yet, for the rest screen. [failed] = it did not go through;
 * [title] / [line] are what the screen shows meanwhile.
 */
data class RestWork(val title: String, val line: String, val failed: Boolean = false)

/** "쉬어 갈 곳" by voice: put a rest area on the route, swap the one we put there, or take one off. */
enum class RestAction { ADD, REPLACE, REMOVE }

/** How a rest-area request ended. */
enum class RestOutcome {
    /** The navigation was asked to change the route (it is shown as done only once the navigation reports it). */
    DONE,
    /** An earlier change is still waiting for the navigation; nothing was sent. */
    BUSY,
    /** The navigation could not be reached; nothing was sent. */
    NOT_SENT,
    /** That rest area is already on the route. */
    ALREADY,
    /** The navigation already has two waypoints (its limit). */
    FULL,
    /** No rest area by that name ahead, or (for removing) none of ours by that name. */
    NOT_FOUND,
    /** The rest area to use was not said and cannot be told. */
    ASK_PLACE,
    /** Nothing of ours is on the route to take off or swap. */
    NOTHING_OURS
}

/** Pure helpers for the waypoint plan (no Android types). */
object RestPlan {
    /** The navigation takes at most two waypoints. */
    const val MAX = 2

    /** "안성 휴게소" / "안성휴게소" / "안성" -> "안성". */
    fun norm(s: String): String = s.replace("휴게소", "").replace(" ", "").trim()

    fun same(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.contains(y) || y.contains(x))
    }

    /** The rest area [place] names among [candidates]: an exact name first, then a partial one. */
    fun find(place: String?, candidates: List<RestStop>): RestStop? {
        if (place.isNullOrBlank()) return null
        val p = norm(place)
        return candidates.firstOrNull { norm(it.name) == p } ?: candidates.firstOrNull { same(it.name, place) }
    }

    /**
     * The waypoint names in route order: the navigation's own list when it reports one (it may hold waypoints the
     * driver put there), otherwise the ones the app added.
     */
    fun names(ours: List<RestStop>, navi: List<String>?): List<String> = navi ?: ours.map { it.name }

    fun indexOf(name: String, ours: List<RestStop>, navi: List<String>?): Int =
        names(ours, navi).indexOfFirst { same(it, name) }
}

/** Short place words for the history: "경기도 성남시 수정구 시흥동 123" -> "성남시 수정구". */
object PlaceText {
    private val PROVINCE = Regex("(도|특별시|광역시|특별자치시|특별자치도)$")

    fun short(address: String?): String {
        if (address.isNullOrBlank()) return ""
        val parts = address.trim().split(Regex("\\s+"))
        val rest = if (parts.isNotEmpty() && PROVINCE.containsMatchIn(parts[0]) && parts.size > 1) parts.drop(1) else parts
        return rest.take(2).joinToString(" ")
    }
}
