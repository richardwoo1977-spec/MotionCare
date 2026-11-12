// ver 1.0.0 | 2026-11-12 12:05
package app.motioncare.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPlanTest {

    private fun area(name: String, lat: Double, lon: Double, toilet: Boolean = true, dir: String = "하행", kind: PlaceKind = PlaceKind.REST_AREA) =
        RestArea(name, "경부선", dir, lat, lon, toilet, false, false, "2026-02-25", kind)

    // 수정구 시흥동 (where the emulator's navigation says the car is)
    private val lat = 37.44
    private val lon = 127.13

    @Test
    fun candidates_are_the_nearest_with_a_toilet_within_100_km_at_most_six() {
        val all = listOf(
            area("a", 37.45, 127.13),
            area("b", 37.50, 127.13),
            area("no toilet", 37.441, 127.13, toilet = false),
            area("far", 35.10, 129.04),           // 부산: farther than 100 km
            area("c", 37.55, 127.13), area("d", 37.60, 127.13), area("e", 37.65, 127.13),
            area("f", 37.70, 127.13), area("g", 37.75, 127.13)
        )
        val c = NearbyPlan.candidates(all, lat, lon)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), c.map { it.name })
    }

    @Test
    fun nothing_within_100_km_gives_no_candidates() {
        assertTrue(NearbyPlan.candidates(listOf(area("far", 35.10, 129.04)), lat, lon).isEmpty())
    }

    @Test
    fun ranking_is_by_road_time_not_by_straight_line_and_unanswered_places_are_left_out() {
        // measured on the emulator 2026-11-12: 죽전(상행) is 9 km away in a straight line but 28 km by road
        val near = area("죽전", 37.36, 127.10, dir = "상행")
        val mid = area("처인", 37.30, 127.20, dir = "양방향")
        val far = area("하남드림", 37.55, 127.25, dir = "양방향")
        val silent = area("무응답", 37.44, 127.14)
        val ranked = NearbyPlan.rank(
            listOf(
                near to Trip(28122, 2024),
                mid to Trip(18724, 2158),
                far to Trip(22329, 3095),
                silent to null
            )
        )
        assertEquals(listOf("죽전", "처인", "하남드림"), ranked.map { it.name })
        assertEquals(34, ranked[0].etaMin)          // 2024 s rounds up to 34 min
        assertEquals(28.122, ranked[0].distanceKm, 0.001)
    }

    @Test
    fun at_most_three_are_shown() {
        val answers = (1..6).map { area("p$it", 37.44 + it / 100.0, 127.13) to Trip(1000 * it, 600 * it) }
        assertEquals(listOf("p1", "p2", "p3"), NearbyPlan.rank(answers).map { it.name })
    }

    @Test
    fun more_than_thirty_minutes_is_far() {
        val r = NearbyPlan.rank(listOf(area("a", 37.5, 127.1) to Trip(30000, 30 * 60), area("b", 37.6, 127.1) to Trip(31000, 30 * 60 + 1)))
        assertEquals(listOf(false, true), r.map { it.far })
    }

    @Test
    fun direction_words_for_shelters_and_rest_areas() {
        assertEquals("천안 방향", NearbyPlan.directionText(area("x", 0.0, 0.0, dir = "순천기점 + 천안종점", kind = PlaceKind.SHELTER)))
        assertEquals("하행", NearbyPlan.directionText(area("x", 0.0, 0.0, dir = "하행")))
        assertEquals("", NearbyPlan.directionText(area("x", 0.0, 0.0, dir = "")))
    }

    @Test
    fun labels_name_the_kind() {
        val r = NearbyPlan.stopOf(area("안성", 0.0, 0.0), Trip(1000, 60))
        val s = NearbyPlan.stopOf(area("죽전", 0.0, 0.0, kind = PlaceKind.SHELTER), Trip(1000, 60))
        assertEquals("안성휴게소", r.label)
        assertEquals("죽전 졸음쉼터", s.label)
        assertTrue(s.id.startsWith("S|"))
    }

    @Test
    fun the_bundled_shelter_file_reads_all_253_shelters() {
        val text = File("src/main/assets/drowsy_shelters.csv").readText(Charsets.UTF_8)
        val list = RestCatalog.parseShelters(text)
        assertEquals(253, list.size)
        assertTrue(list.all { it.kind == PlaceKind.SHELTER && it.toilet })
        assertTrue(list.all { it.lat in 33.0..39.0 && it.lon in 124.0..132.0 })
        assertTrue(list.none { it.name.isBlank() })
        // route, name and direction tell every shelter apart (both sides of a road are two places)
        assertEquals(253, list.map { it.id }.toSet().size)
    }
}
