// ver 1.0.1 | 2026-11-05 14:36
package app.motioncare.core

import app.motioncare.data.DemoNavi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {

    @Test
    fun driving_needs_drive_gear_and_motion() {
        assertTrue(Policy.isDriving(Gear.DRIVE, 3.0))
        assertFalse(Policy.isDriving(Gear.DRIVE, 2.9))
        assertFalse(Policy.isDriving(Gear.PARK, 50.0))
        assertFalse(Policy.isDriving(Gear.NEUTRAL, 50.0))
    }

    @Test
    fun park_counts_as_stopped_even_if_speed_signal_is_stale() {
        assertTrue(Policy.isStopped(Gear.PARK, 80.0))
        assertTrue(Policy.isStopped(Gear.DRIVE, 4.9))
        assertFalse(Policy.isStopped(Gear.DRIVE, 5.0))
        assertFalse(Policy.isStopped(Gear.UNKNOWN, 60.0))
    }

    @Test
    fun window_only_opens_at_low_speed_or_in_park() {
        assertTrue(Policy.windowAllowed(Gear.DRIVE, 30.0))
        assertFalse(Policy.windowAllowed(Gear.DRIVE, 30.1))
        assertTrue(Policy.windowAllowed(Gear.PARK, 100.0))
    }

    @Test
    fun rest_after_is_median_minus_five_per_destination() {
        val h = listOf(
            HistoryEvent(1, 42, "ROW_2_LEFT", "부산"),
            HistoryEvent(2, 38, "ROW_2_LEFT", "부산"),
            HistoryEvent(3, 40, "ROW_2_LEFT", "부산"),
            HistoryEvent(4, 25, "ROW_2_RIGHT", "강릉")
        )
        assertEquals(35, Policy.restAfterMin(h, "부산"))
        assertEquals(20, Policy.restAfterMin(h, "강릉"))
        assertNull(Policy.restAfterMin(h, "대전"))
        assertNull(Policy.restAfterMin(h, ""))
    }

    @Test
    fun rest_after_never_below_five_and_ignores_zero_minutes() {
        val h = listOf(
            HistoryEvent(1, 0, "ROW_2_LEFT", "부산"),
            HistoryEvent(2, 7, "ROW_2_LEFT", "부산")
        )
        assertEquals(5, Policy.restAfterMin(h, "부산"))
        assertNull(Policy.restAfterMin(listOf(HistoryEvent(1, 0, "ROW_2_LEFT", "부산")), "부산"))
    }

    @Test
    fun pick_stop_prefers_nursing_or_kids_within_20_km() {
        assertEquals("B", Policy.pickStop(DemoNavi.stops)?.id)
    }

    @Test
    fun pick_stop_skips_stops_that_are_too_close_or_without_toilet() {
        val stops = listOf(
            RestStop("X", "너무 가까움", 1.0, 1, toilet = true, nursing = true, kids = true),
            RestStop("Y", "화장실 없음", 10.0, 8, toilet = false, nursing = true, kids = true),
            RestStop("Z", "일반", 25.0, 20, toilet = true, nursing = false, kids = false)
        )
        assertEquals("Z", Policy.pickStop(stops)?.id)
        assertNull(Policy.pickStop(emptyList()))
    }
}
