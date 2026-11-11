// ver 1.0.0 | 2026-11-11 22:20
package app.motioncare.core

import app.motioncare.data.DemoNavi
import app.motioncare.data.Live
import app.motioncare.data.LiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestPlanTest {

    private val cmds = mutableListOf<NaviCmd>()

    private fun brain(): CareBrain {
        Live.update { LiveState() }
        cmds.clear()
        val b = CareBrain(clock = { 1_000_000L }, speak = {}, onDesired = {}, onNavi = { cmds += it })
        b.init()
        return b
    }

    private val s get() = DemoNavi.stops

    private fun ours() = Live.state.value.waypoints.map { it.id }

    // ---- names ----

    @Test
    fun names_match_with_or_without_the_word_hugeso() {
        assertTrue(RestPlan.same("안성휴게소", "안성"))
        assertTrue(RestPlan.same("안성 휴게소", "안성휴게소"))
        assertTrue(!RestPlan.same("안성", "평택"))
        assertTrue(!RestPlan.same("", "평택"))
        val list = listOf(
            RestStop("1", "평택", 20.0, 15, true, true, false),
            RestStop("2", "평택호", 30.0, 20, true, false, false)
        )
        assertEquals("1", RestPlan.find("평택휴게소", list)?.id)
        assertEquals("2", RestPlan.find("평택호", list)?.id)
        assertNull(RestPlan.find("부산", list))
        assertNull(RestPlan.find(null, list))
    }

    @Test
    fun short_place_words_drop_the_province() {
        assertEquals("성남시 수정구", PlaceText.short("경기도 성남시 수정구 시흥동 123"))
        assertEquals("성동구 연무장13길", PlaceText.short("서울특별시 성동구 연무장13길 9"))
        assertEquals("해운대구 우동", PlaceText.short("부산광역시 해운대구 우동"))
        assertEquals("", PlaceText.short(null))
        assertEquals("", PlaceText.short("  "))
    }

    // ---- screen buttons ----

    @Test
    fun adding_puts_the_stop_first_and_asks_the_navigation() {
        val b = brain()
        assertEquals(RestOutcome.DONE, b.restAdd(s[0].id))
        assertEquals(listOf(s[0].id), ours())
        assertEquals(listOf<NaviCmd>(NaviCmd.Add(s[0], 0)), cmds)
    }

    @Test
    fun adding_the_same_stop_twice_changes_nothing() {
        val b = brain()
        b.restAdd(s[0].id)
        assertEquals(RestOutcome.ALREADY, b.restAdd(s[0].id))
        assertEquals(1, cmds.size)
    }

    @Test
    fun a_second_stop_goes_second_and_a_third_is_refused() {
        val b = brain()
        b.restAdd(s[0].id)
        assertEquals(RestOutcome.DONE, b.restAdd(s[1].id))
        assertEquals(NaviCmd.Add(s[1], 1), cmds.last())
        assertEquals(RestOutcome.FULL, b.restAdd(s[2].id))
        assertEquals(listOf(s[0].id, s[1].id), ours())
        assertEquals(2, cmds.size)
    }

    @Test
    fun the_drivers_own_waypoint_counts_toward_the_limit() {
        val b = brain()
        b.onNaviWaypoints(listOf("시흥"))
        assertEquals(RestOutcome.DONE, b.restAdd(s[0].id))
        assertEquals(NaviCmd.Add(s[0], 1), cmds.last())
        assertEquals(RestOutcome.FULL, b.restAdd(s[1].id))
    }

    @Test
    fun replacing_takes_ours_off_and_puts_the_new_one_in_its_place() {
        val b = brain()
        b.onNaviWaypoints(listOf("시흥"))
        b.restAdd(s[0].id)
        cmds.clear()
        assertEquals(RestOutcome.DONE, b.restReplace(s[1].id))
        assertEquals(listOf<NaviCmd>(NaviCmd.Remove(1, s[0].name), NaviCmd.Add(s[1], 1)), cmds)
        assertEquals(listOf(s[1].id), ours())
        assertEquals(listOf("시흥", s[1].name), Live.state.value.naviWaypoints)
    }

    @Test
    fun replacing_with_nothing_of_ours_on_the_route_just_adds() {
        val b = brain()
        assertEquals(RestOutcome.DONE, b.restReplace(s[1].id))
        assertEquals(listOf<NaviCmd>(NaviCmd.Add(s[1], 0)), cmds)
    }

    @Test
    fun removing_takes_it_off_at_its_place_on_the_route() {
        val b = brain()
        b.restAdd(s[0].id)
        b.restAdd(s[1].id)
        cmds.clear()
        assertEquals(RestOutcome.DONE, b.restRemove(s[0].id))
        assertEquals(listOf<NaviCmd>(NaviCmd.Remove(0, s[0].name)), cmds)
        assertEquals(listOf(s[1].id), ours())
    }

    @Test
    fun only_waypoints_the_app_put_there_can_be_removed() {
        val b = brain()
        b.onNaviWaypoints(listOf("시흥"))
        assertEquals(RestOutcome.NOT_FOUND, b.restRemove(s[2].id))
        assertTrue(cmds.isEmpty())
    }

    @Test
    fun picking_a_card_changes_nothing_on_the_route_and_a_second_tap_unpicks() {
        val b = brain()
        b.pickRest(s[1].id)
        assertEquals(s[1].id, Live.state.value.pickedRest)
        assertTrue(cmds.isEmpty())
        b.pickRest(s[1].id)
        assertNull(Live.state.value.pickedRest)
    }

    @Test
    fun a_done_change_clears_the_pick() {
        val b = brain()
        b.pickRest(s[1].id)
        b.restAdd(s[1].id)
        assertNull(Live.state.value.pickedRest)
    }

    // ---- voice ----

    @Test
    fun voice_add_by_name_with_or_without_hugeso() {
        val b = brain()
        val (o, name) = b.voiceRest(RestAction.ADD, s[1].name + "휴게소")
        assertEquals(RestOutcome.DONE, o)
        assertEquals(s[1].name, name)
        assertEquals(listOf(s[1].id), ours())
    }

    @Test
    fun voice_add_without_a_name_uses_the_suggested_stop() {
        val b = brain()
        val (o, name) = b.voiceRest(RestAction.ADD, null)
        assertEquals(RestOutcome.DONE, o)
        assertEquals(Policy.pickStop(s)?.name, name)
    }

    @Test
    fun voice_with_an_unknown_name_is_not_found_and_changes_nothing() {
        val b = brain()
        assertEquals(RestOutcome.NOT_FOUND, b.voiceRest(RestAction.ADD, "부산역").first)
        assertTrue(cmds.isEmpty())
    }

    @Test
    fun voice_remove_without_a_name_takes_off_our_only_one() {
        val b = brain()
        b.restAdd(s[0].id)
        assertEquals(RestOutcome.DONE, b.voiceRest(RestAction.REMOVE, null).first)
        assertTrue(ours().isEmpty())
    }

    @Test
    fun voice_remove_without_a_name_and_two_of_ours_asks_which() {
        val b = brain()
        b.restAdd(s[0].id)
        b.restAdd(s[1].id)
        assertEquals(RestOutcome.ASK_PLACE, b.voiceRest(RestAction.REMOVE, null).first)
        assertEquals(2, ours().size)
    }

    @Test
    fun voice_remove_with_nothing_of_ours_says_so() {
        val b = brain()
        assertEquals(RestOutcome.NOTHING_OURS, b.voiceRest(RestAction.REMOVE, null).first)
    }

    @Test
    fun voice_replace_swaps_ours_for_the_named_one() {
        val b = brain()
        b.restAdd(s[0].id)
        assertEquals(RestOutcome.DONE, b.voiceRest(RestAction.REPLACE, s[1].name).first)
        assertEquals(listOf(s[1].id), ours())
    }

    @Test
    fun voice_replace_without_a_name_asks() {
        val b = brain()
        b.restAdd(s[0].id)
        assertEquals(RestOutcome.ASK_PLACE, b.voiceRest(RestAction.REPLACE, null).first)
        assertEquals(listOf(s[0].id), ours())
    }

    @Test
    fun voice_add_when_full_is_refused() {
        val b = brain()
        b.onNaviWaypoints(listOf("시흥", "수원"))
        assertEquals(RestOutcome.FULL, b.voiceRest(RestAction.ADD, s[0].name).first)
        assertTrue(cmds.isEmpty())
    }

    // ---- destination and route records ----

    @Test
    fun the_navigation_destination_wins_over_the_demo_one() {
        val b = brain()
        assertTrue(!Live.state.value.destinationFromNavi)
        b.onNaviDestination("해운대해수욕장")
        assertEquals("해운대해수욕장", Live.state.value.destination)
        assertTrue(Live.state.value.destinationFromNavi)
        b.onNaviDestination(null)
        assertEquals(DemoNavi.destinations.first(), Live.state.value.destination)
    }

    @Test
    fun the_start_is_where_the_car_was_when_the_trip_began() {
        val b = brain()
        b.onLocation("경기도 성남시 수정구 시흥동", 37.43, 127.08)
        b.onGear(Gear.DRIVE)
        b.onSpeedMps(60 / 3.6)
        b.onLocation("경기도 용인시 기흥구 공세동", 37.24, 127.11)
        assertEquals("성남시 수정구", Live.state.value.origin)
    }

    @Test
    fun a_rest_is_offered_only_from_records_of_the_same_route() {
        val far = HistoryEvent(1L, 40, "", "부산", "광주 북구", 35.17, 126.91)
        val near = HistoryEvent(2L, 30, "", "부산", "성남시 수정구", 37.44, 127.09)
        val old = HistoryEvent(3L, 50, "", "부산")
        val h = listOf(far, near, old)
        // from 성남: the 광주 start is too far; 30 and 50 -> median 40 -> 35
        assertEquals(35, Policy.restAfterMin(h, "부산", 37.43, 127.08))
        // start not known: every record counts (30, 40, 50 -> 40 -> 35)
        assertEquals(35, Policy.restAfterMin(h, "부산", null, null))
        // from 광주: 40 and 50 -> 45 -> 40
        assertEquals(40, Policy.restAfterMin(h, "부산", 35.16, 126.90))
        assertNull(Policy.restAfterMin(h, "강릉", 37.43, 127.08))
    }

    @Test
    fun distance_between_two_points() {
        val km = Geo.km(37.5665, 126.9780, 35.1796, 129.0756)  // 서울시청 - 부산시청
        assertTrue(km in 320.0..330.0)
    }
}
