// ver 1.3.0 | 2026-11-11 22:50
package app.motioncare.data

import app.motioncare.core.HistoryEvent
import app.motioncare.core.RestAhead
import app.motioncare.core.RestArea
import app.motioncare.core.RestStop

/**
 * The rest-area list and a demo position (the navigation's own position is used for records when it reports one).
 * Stand-in for the navigation link. The rest areas are the real list of the Korea Expressway
 * Corporation file bundled with the app (see [setCatalog]); the car position is a made-up demo
 * position because the Pleos NaviHelper position / route is not connected yet, and the distances are
 * straight-line. When the file could not be read, four made-up stops are used and the screens say so.
 */
object DemoNavi {
    val destinations = listOf("부산", "강릉", "대전")

    /** Made-up position on the Gyeongbu line heading south (heading 165 degrees). */
    const val DEMO_LAT = 37.2
    const val DEMO_LON = 127.05
    const val DEMO_HEADING_DEG = 165.0

    @Volatile private var catalog: List<RestArea> = emptyList()

    fun setCatalog(list: List<RestArea>) {
        catalog = list
    }

    /** True while the made-up four stops are used (the bundled file was not read). */
    val usingFallback: Boolean get() = catalog.isEmpty()

    /** "기준일 2026-09-17" of the bundled file, or null while it is not loaded. */
    val dataAsOf: String? get() = catalog.firstOrNull()?.asOf

    val catalogSize: Int get() = catalog.size

    /** The rest area behind a [RestStop.id] ("route|name|direction"), or null for the made-up stops. */
    fun areaOf(id: String): RestArea? = catalog.firstOrNull { "${it.route}|${it.name}|${it.direction}" == id }

    /** The stops in front of the demo position, nearest first (at most 10). */
    val stops: List<RestStop>
        get() {
            val c = catalog
            return if (c.isEmpty()) fallbackStops
            else RestAhead.stopsAhead(c, DEMO_LAT, DEMO_LON, DEMO_HEADING_DEG).take(10)
        }

    private val fallbackStops = listOf(
        RestStop("A", "시연 휴게소 A", 6.2, 5, toilet = true, nursing = false, kids = false),
        RestStop("B", "시연 휴게소 B", 14.8, 12, toilet = true, nursing = true, kids = true),
        RestStop("C", "시연 휴게소 C", 28.0, 22, toilet = true, nursing = false, kids = true),
        RestStop("D", "시연 휴게소 D", 41.5, 33, toilet = true, nursing = true, kids = false)
    )

    /** Sample earlier trips so the history screen and the "rest in advance" rule can be shown. */
    fun sampleHistory(now: Long): List<HistoryEvent> {
        val day = 24L * 60 * 60 * 1000
        return listOf(
            HistoryEvent(now - 21 * day, 42, "ROW_2_LEFT", "부산", "성남시 수정구", 37.44, 127.13, "안성시 원곡면"),
            HistoryEvent(now - 14 * day, 38, "ROW_2_LEFT", "부산", "성남시 수정구", 37.44, 127.13, "안성시 공도읍"),
            HistoryEvent(now - 9 * day, 25, "ROW_2_RIGHT", "강릉", "성남시 수정구", 37.44, 127.13, "여주시 가남읍"),
            HistoryEvent(now - 3 * day, 40, "ROW_2_LEFT", "부산", "성남시 수정구", 37.44, 127.13, "안성시 원곡면")
        ).sortedByDescending { it.epochMs }
    }
}
