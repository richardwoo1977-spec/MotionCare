// ver 1.3.0 | 2026-11-12 12:00
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
    private var now = 1_000_000L

    /** [sends] = whether the navigation can be reached */
    private fun brain(sends: Boolean = true): CareBrain {
        Live.update { LiveState() }
        cmds.clear()
        now = 1_000_000L
        val b = CareBrain(clock = { now }, speak = {}, onDesired = {}, onNavi = { cmds += it; sends })
        b.init()
        b.onNaviWaypoints(emptyList())  // guiding to a destination, no waypoints yet
        b.onNearby(NearbyState.READY, s.take(3))
        return b
    }

    /** The navigation reports its waypoints (only while guiding), as it does after each change. */
    private fun CareBrain.navi(vararg names: String) = onNaviWaypoints(names.toList())

    /** Adds and lets the navigation confirm it. */
    private fun CareBrain.addDone(stop: RestStop, vararg before: String) {
        assertEquals(RestOutcome.DONE, restAdd(stop.id))
        navi(*before, stop.name + "휴게소")
    }

    private fun work() = Live.state.value.restWork

    /** places nearby as the service would hand them over: fastest by road first */
    private val s = listOf(
        RestStop("R|경부선|안성|하행", "안성", 18.0, 14, toilet = true, nursing = true, kids = false),
        RestStop("S|경부선|죽전|서울기점 + 부산종점", "죽전", 21.0, 19, toilet = true, nursing = false, kids = false, kind = PlaceKind.SHELTER, direction = "부산 방향"),
        RestStop("R|경부선|천안|하행", "천안", 40.0, 33, toilet = true, nursing = false, kids = true),
        RestStop("R|영동선|용인|하행", "용인", 45.0, 38, toilet = true, nursing = false, kids = false)
    )

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

    // ---- screen buttons: one change at a time, shown only once the navigation reports it ----

    @Test
    fun adding_asks_the_navigation_and_shows_it_only_once_the_navigation_reports_it() {
        val b = brain()
        assertEquals(RestOutcome.DONE, b.restAdd(s[0].id))
        assertEquals(listOf<NaviCmd>(NaviCmd.Add(s[0], 0)), cmds)
        assertTrue(ours().isEmpty())
        assertEquals("${s[0].name} 넣는 중", work()?.title)
        // the navigation drops the route while it changes it, then reports the new one
        b.onNaviWaypoints(null)
        assertTrue(ours().isEmpty())
        b.navi(s[0].name + "휴게소")
        assertEquals(listOf(s[0].id), ours())
        assertNull(work())
    }

    @Test
    fun a_second_request_while_the_navigation_is_changing_is_not_sent() {
        val b = brain()
        b.restAdd(s[0].id)
        assertEquals(RestOutcome.BUSY, b.restAdd(s[1].id))
        assertEquals(1, cmds.size)
        b.navi(s[0].name)
        assertEquals(RestOutcome.DONE, b.restAdd(s[1].id))
        assertEquals(NaviCmd.Add(s[1], 1), cmds.last())
    }

    @Test
    fun no_report_in_time_means_it_did_not_go_through() {
        val b = brain()
        b.restAdd(s[0].id)
        now += Policy.REST_STEP_TIMEOUT_MS - 1
        b.tick()
        assertTrue(work()?.failed == false)
        now += 1
        b.tick()
        assertTrue(work()?.failed == true)
        assertEquals("내비가 받지 않았어요", work()?.title)
        assertTrue(ours().isEmpty())
        // the failure stays a while, and a new try is allowed right away
        assertEquals(RestOutcome.DONE, b.restAdd(s[0].id))
        assertTrue(work()?.failed == false)
    }

    @Test
    fun the_failure_note_goes_away_after_a_while() {
        val b = brain()
        b.restAdd(s[0].id)
        now += Policy.REST_STEP_TIMEOUT_MS
        b.tick()
        now += Policy.REST_FAIL_SHOW_MS
        b.tick()
        assertNull(work())
    }

    @Test
    fun when_the_navigation_cannot_be_reached_nothing_is_waiting() {
        val b = brain(sends = false)
        assertEquals(RestOutcome.NOT_SENT, b.restAdd(s[0].id))
        assertTrue(work()?.failed == true)
        assertTrue(ours().isEmpty())
    }

    @Test
    fun the_pick_stays_until_the_navigation_confirms() {
        val b = brain()
        b.pickRest(s[1].id)
        b.restAdd(s[1].id)
        assertEquals(s[1].id, Live.state.value.pickedRest)
        b.navi(s[1].name)
        assertNull(Live.state.value.pickedRest)
    }

    @Test
    fun adding_the_same_stop_twice_changes_nothing() {
        val b = brain()
        b.addDone(s[0])
        assertEquals(RestOutcome.ALREADY, b.restAdd(s[0].id))
        assertEquals(1, cmds.size)
    }

    @Test
    fun a_second_stop_goes_second_and_a_third_is_refused() {
        val b = brain()
        b.addDone(s[0])
        assertEquals(RestOutcome.DONE, b.restAdd(s[1].id))
        assertEquals(NaviCmd.Add(s[1], 1), cmds.last())
        b.navi(s[0].name, s[1].name)
        assertEquals(RestOutcome.FULL, b.restAdd(s[2].id))
        assertEquals(listOf(s[0].id, s[1].id), ours())
        assertEquals(2, cmds.size)
    }

    @Test
    fun the_drivers_own_waypoint_counts_toward_the_limit() {
        val b = brain()
        b.navi("시흥")
        assertEquals(RestOutcome.DONE, b.restAdd(s[0].id))
        assertEquals(NaviCmd.Add(s[0], 1), cmds.last())
        b.navi("시흥", s[0].name)
        assertEquals(RestOutcome.FULL, b.restAdd(s[1].id))
    }

    @Test
    fun replacing_takes_ours_off_first_and_sends_the_new_one_only_after_the_navigation_reports_it_gone() {
        val b = brain()
        b.navi("시흥")
        b.addDone(s[0], "시흥")
        cmds.clear()
        assertEquals(RestOutcome.DONE, b.restReplace(s[1].id))
        assertEquals(listOf<NaviCmd>(NaviCmd.Remove(1, s[0].name)), cmds)
        // while the navigation changes the route it reports nothing: still waiting, nothing more sent
        b.onNaviWaypoints(null)
        assertEquals(1, cmds.size)
        b.navi("시흥")
        assertEquals(listOf<NaviCmd>(NaviCmd.Remove(1, s[0].name), NaviCmd.Add(s[1], 1)), cmds)
        assertTrue(work()?.title?.let { it.startsWith(s[1].name) && it.endsWith(" 바꾸는 중") } == true)
        b.navi("시흥", s[1].name)
        assertEquals(listOf(s[1].id), ours())
        assertNull(work())
    }

    @Test
    fun a_replace_whose_new_stop_is_not_taken_says_only_the_old_one_went() {
        val b = brain()
        b.addDone(s[0])
        b.restReplace(s[1].id)
        b.navi()
        now += Policy.REST_STEP_TIMEOUT_MS
        b.tick()
        assertEquals("${s[0].name}만 빠졌어요", work()?.title)
        assertTrue(ours().isEmpty())
    }

    @Test
    fun replacing_with_nothing_of_ours_on_the_route_just_adds() {
        val b = brain()
        assertEquals(RestOutcome.DONE, b.restReplace(s[1].id))
        assertEquals(listOf<NaviCmd>(NaviCmd.Add(s[1], 0)), cmds)
    }

    @Test
    fun removing_takes_it_off_at_its_place_and_waits_for_the_navigation() {
        val b = brain()
        b.addDone(s[0])
        b.restAdd(s[1].id)
        b.navi(s[0].name, s[1].name)
        cmds.clear()
        assertEquals(RestOutcome.DONE, b.restRemove(s[0].id))
        assertEquals(listOf<NaviCmd>(NaviCmd.Remove(0, s[0].name)), cmds)
        assertEquals(listOf(s[0].id, s[1].id), ours())
        b.navi(s[1].name)
        assertEquals(listOf(s[1].id), ours())
    }

    @Test
    fun a_stop_the_driver_took_off_in_the_navigation_is_no_longer_ours() {
        val b = brain()
        b.addDone(s[0])
        b.navi()
        assertTrue(ours().isEmpty())
    }

    @Test
    fun only_waypoints_the_app_put_there_can_be_removed() {
        val b = brain()
        b.navi("시흥")
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
    fun with_no_destination_the_navigation_is_asked_to_guide_to_the_place() {
        val b = brain()
        b.onNaviWaypoints(null)
        assertTrue(!Live.state.value.naviRouting)
        assertEquals(RestOutcome.DONE, b.restAdd(s[1].id))
        assertEquals(listOf<NaviCmd>(NaviCmd.Route(s[1])), cmds)
        assertEquals("${s[1].name} 안내 준비 중", work()?.title)
        // the navigation reports the new destination: the place is now where it guides
        b.onNaviDestination(s[1].label)
        b.navi()
        assertNull(work())
        assertEquals(s[1].id, Live.state.value.destRestId)
    }

    @Test
    fun guiding_to_our_place_another_card_changes_the_destination_and_the_same_card_ends_it() {
        val b = brain()
        b.onNaviWaypoints(null)
        b.restAdd(s[0].id)
        b.onNaviDestination(s[0].label)
        b.navi()
        cmds.clear()
        // routing now, but to our place: picking another one changes the destination, not a waypoint
        assertEquals(RestOutcome.DONE, b.restAdd(s[2].id))
        assertEquals(listOf<NaviCmd>(NaviCmd.Route(s[2])), cmds)
        b.onNaviDestination(s[2].label)
        assertEquals(s[2].id, Live.state.value.destRestId)
        cmds.clear()
        assertEquals(RestOutcome.DONE, b.restRemove(s[2].id))
        assertEquals(listOf<NaviCmd>(NaviCmd.Cancel), cmds)
        b.onNaviDestination(null)
        b.onNaviWaypoints(null)
        assertNull(work())
        assertNull(Live.state.value.destRestId)
    }

    @Test
    fun voice_with_no_destination_guides_to_the_fastest_place_and_remove_ends_it() {
        val b = brain()
        b.onNaviWaypoints(null)
        assertEquals(RestOutcome.DONE to s[0].name, b.voiceRest(RestAction.ADD, null))
        assertEquals(NaviCmd.Route(s[0]), cmds.last())
        b.onNaviDestination(s[0].label)
        assertEquals(RestOutcome.DONE to s[0].name, b.voiceRest(RestAction.REMOVE, null))
        assertEquals(NaviCmd.Cancel, cmds.last())
    }

    @Test
    fun a_guidance_the_navigation_does_not_take_says_so() {
        val b = brain()
        b.onNaviWaypoints(null)
        b.restAdd(s[0].id)
        now += Policy.REST_STEP_TIMEOUT_MS
        b.tick()
        assertEquals("내비가 받지 않았어요", work()?.title)
        assertNull(Live.state.value.destRestId)
    }

    @Test
    fun a_destination_set_by_the_driver_is_not_ours() {
        val b = brain()
        b.onNaviDestination("부산역")
        b.navi()
        assertNull(Live.state.value.destRestId)
        assertEquals(RestOutcome.DONE, b.restAdd(s[0].id))
        assertEquals(NaviCmd.Add(s[0], 0), cmds.last())
    }

    @Test
    fun nothing_nearby_means_no_place_to_add_by_voice() {
        val b = brain()
        b.onNearby(NearbyState.NONE, emptyList())
        assertEquals(RestOutcome.NOT_FOUND, b.voiceRest(RestAction.ADD, null).first)
        assertTrue(cmds.isEmpty())
        assertEquals(NearbyState.NONE, Live.state.value.nearbyState)
    }

    @Test
    fun while_the_navigation_changes_the_route_a_new_request_is_busy_not_no_route() {
        val b = brain()
        b.restAdd(s[0].id)
        b.onNaviWaypoints(null)
        assertEquals(RestOutcome.BUSY, b.restAdd(s[1].id))
        assertEquals(RestOutcome.BUSY, b.voiceRest(RestAction.ADD, s[1].name).first)
    }

    // ---- voice ----

    @Test
    fun voice_add_by_name_with_or_without_hugeso() {
        val b = brain()
        val (o, name) = b.voiceRest(RestAction.ADD, s[1].name + "휴게소")
        assertEquals(RestOutcome.DONE, o)
        assertEquals(s[1].name, name)
        assertEquals(listOf<NaviCmd>(NaviCmd.Add(s[1], 0)), cmds)
    }

    @Test
    fun voice_add_without_a_name_uses_the_suggested_stop() {
        val b = brain()
        val (o, name) = b.voiceRest(RestAction.ADD, null)
        assertEquals(RestOutcome.DONE, o)
        assertEquals(s[0].name, name)
    }

    @Test
    fun voice_while_the_navigation_is_changing_is_busy() {
        val b = brain()
        b.voiceRest(RestAction.ADD, s[0].name)
        assertEquals(RestOutcome.BUSY, b.voiceRest(RestAction.ADD, s[1].name).first)
        assertEquals(1, cmds.size)
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
        b.addDone(s[0])
        assertEquals(RestOutcome.DONE, b.voiceRest(RestAction.REMOVE, null).first)
        b.navi()
        assertTrue(ours().isEmpty())
    }

    @Test
    fun voice_remove_without_a_name_and_two_of_ours_asks_which() {
        val b = brain()
        b.addDone(s[0])
        b.addDone(s[1], s[0].name)
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
        b.addDone(s[0])
        assertEquals(RestOutcome.DONE, b.voiceRest(RestAction.REPLACE, s[1].name).first)
        b.navi()
        b.navi(s[1].name)
        assertEquals(listOf(s[1].id), ours())
    }

    @Test
    fun voice_replace_without_a_name_asks() {
        val b = brain()
        b.addDone(s[0])
        assertEquals(RestOutcome.ASK_PLACE, b.voiceRest(RestAction.REPLACE, null).first)
        assertEquals(listOf(s[0].id), ours())
    }

    @Test
    fun voice_add_when_full_is_refused() {
        val b = brain()
        b.navi("시흥", "수원")
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
