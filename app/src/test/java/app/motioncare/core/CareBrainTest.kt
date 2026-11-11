// ver 1.3.0 | 2026-11-06 19:08
package app.motioncare.core

import app.motioncare.data.Live
import app.motioncare.data.LiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CareBrainTest {

    private fun brain(): CareBrain {
        Live.update { LiveState() }
        val b = CareBrain(clock = { 1_000_000L }, speak = {}, onDesired = {})
        b.init()
        return b
    }

    private fun kmh(b: CareBrain, v: Double) = b.onSpeedMps(v / 3.6)

    @Test
    fun relief_can_be_started_while_driving() {
        val b = brain()
        b.onGear(Gear.DRIVE)
        kmh(b, 80.0)
        b.reliefPressed()
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
        assertTrue(b.desired.recircOff)
        assertEquals(FanWant.MAX, b.desired.fan)
        assertTrue(b.desired.faceDirection)
    }

    @Test
    fun window_opens_only_at_30_kmh_or_less_during_relief() {
        val b = brain()
        b.onGear(Gear.DRIVE)
        kmh(b, 80.0)
        b.reliefPressed()
        assertFalse(b.desired.windowOpen)
        kmh(b, 20.0)
        assertTrue(b.desired.windowOpen)
        kmh(b, 31.0)
        assertFalse(b.desired.windowOpen)
    }

    @Test
    fun relief_targets_an_occupied_third_row_seat_when_nobody_is_designated() {
        val b = brain()
        b.onOccupancy(mapOf<String, Boolean?>("ROW_3_RIGHT" to true))
        b.onGear(Gear.DRIVE)
        kmh(b, 60.0)
        b.reliefPressed()
        assertEquals(Seat.ROW_3_RIGHT, b.desired.target)
    }

    @Test
    fun a_designated_third_row_seat_is_looked_after_while_driving() {
        val b = brain()
        b.toggleDesignate("ROW_3_CENTER")
        b.onOccupancy(mapOf<String, Boolean?>("ROW_3_CENTER" to true))
        b.onGear(Gear.DRIVE)
        kmh(b, 60.0)
        assertEquals(CareMode.PREVENT, Live.state.value.mode)
        assertEquals(Seat.ROW_3_CENTER, b.desired.target)
    }

    // ---- relief by voice, with the question "which seat?" ----

    private var now = 1_000_000L

    private fun brainAtNow(): CareBrain {
        Live.update { LiveState() }
        now = 1_000_000L
        val b = CareBrain(clock = { now }, speak = {}, onDesired = {})
        b.init()
        return b
    }

    private fun sitting(b: CareBrain, vararg seats: Seat) =
        b.onOccupancy(seats.associate { it.key to (true as Boolean?) })

    @Test
    fun one_passenger_is_relieved_without_asking() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_3_RIGHT)
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(null))
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
        assertEquals(Seat.ROW_3_RIGHT, b.desired.target)
    }

    @Test
    fun the_front_passenger_counts_as_the_only_passenger() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_1_RIGHT)
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(null))
        assertEquals(Seat.ROW_1_RIGHT, b.desired.target)
    }

    @Test
    fun the_driver_alone_does_not_count_as_a_passenger() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_1_LEFT)
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(null))
        assertEquals(null, b.desired.target)
    }

    @Test
    fun two_passengers_and_no_seat_named_means_asking() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT)
        assertEquals(VoiceReliefResult.ASKING, b.voiceRelief(null))
        assertEquals(CareMode.IDLE, Live.state.value.mode)
        assertTrue(Live.state.value.caption.isNotBlank())
    }

    @Test
    fun the_seat_named_after_the_question_starts_relief_for_that_seat() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT)
        b.voiceRelief(null)
        now += 4_000L
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(Seat.ROW_3_RIGHT))
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
        assertEquals(Seat.ROW_3_RIGHT, b.desired.target)
    }

    @Test
    fun a_seat_named_in_the_first_call_needs_no_question() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT, Seat.ROW_1_RIGHT)
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(Seat.ROW_2_LEFT))
        assertEquals(Seat.ROW_2_LEFT, b.desired.target)
    }

    @Test
    fun without_an_answer_every_seat_is_relieved_after_the_wait() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT)
        b.voiceRelief(null)
        now += (Policy.SEAT_ASK_SEC - 1) * 1000L
        b.tick()
        assertEquals(CareMode.IDLE, Live.state.value.mode)
        now += 1_000L
        b.tick()
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
        assertEquals(null, b.desired.target)
    }

    @Test
    fun the_question_is_dropped_when_the_driver_ends_it_or_presses_the_button() {
        val a = brainAtNow()
        sitting(a, Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT)
        a.voiceRelief(null)
        a.reliefEnd()
        now += 60_000L
        a.tick()
        assertEquals(CareMode.IDLE, Live.state.value.mode)

        val b = brainAtNow()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT)
        b.voiceRelief(null)
        b.reliefPressed()
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
    }

    @Test
    fun naming_another_seat_while_relieving_moves_the_target() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_2_LEFT)
        b.voiceRelief(null)
        assertEquals(Seat.ROW_2_LEFT, b.desired.target)
        assertEquals(VoiceReliefResult.RETARGETED, b.voiceRelief(Seat.ROW_3_CENTER))
        assertEquals(Seat.ROW_3_CENTER, b.desired.target)
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
    }

    @Test
    fun the_screen_is_told_which_seat_the_relief_follows() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        assertEquals(null, Live.state.value.reliefSeat)
        b.voiceRelief(Seat.ROW_2_LEFT)
        assertEquals(Seat.ROW_2_LEFT.key, Live.state.value.reliefSeat)
        b.voiceRelief(Seat.ROW_2_RIGHT)
        assertEquals(Seat.ROW_2_RIGHT.key, Live.state.value.reliefSeat)
        b.reliefEnd()
        assertEquals(null, Live.state.value.reliefSeat)
    }

    // ---- after the 10 minutes: the settings go back, the seat stays marked ----

    private fun reliefRunsOut(b: CareBrain, seat: Seat?) {
        b.voiceRelief(seat)
        now += Policy.RELIEF_MAX_MIN * 60_000L
        b.tick()
    }

    @Test
    fun when_the_ten_minutes_run_out_the_settings_go_back_but_the_seat_stays_marked() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        reliefRunsOut(b, Seat.ROW_2_RIGHT)
        assertEquals(CareMode.IDLE, Live.state.value.mode)
        assertTrue(b.desired.isIdle)
        assertEquals(Seat.ROW_2_RIGHT.key, Live.state.value.reliefSeat)
    }

    @Test
    fun the_button_after_the_ten_minutes_restarts_the_relief_for_the_marked_seat() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        reliefRunsOut(b, Seat.ROW_2_RIGHT)
        b.reliefPressed()
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
        assertEquals(Seat.ROW_2_RIGHT, b.desired.target)
    }

    @Test
    fun a_voice_call_without_a_seat_after_the_ten_minutes_needs_no_question() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        reliefRunsOut(b, Seat.ROW_2_RIGHT)
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(null))
        assertEquals(Seat.ROW_2_RIGHT, b.desired.target)
    }

    @Test
    fun saying_it_is_over_clears_the_mark() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        reliefRunsOut(b, Seat.ROW_2_RIGHT)
        b.reliefEnd()
        assertEquals(null, Live.state.value.reliefSeat)
    }

    @Test
    fun tapping_the_marked_seat_clears_the_mark_and_does_not_designate_it() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        reliefRunsOut(b, Seat.ROW_2_RIGHT)
        b.toggleDesignate(Seat.ROW_2_RIGHT.key)
        assertEquals(null, Live.state.value.reliefSeat)
        assertFalse(Seat.ROW_2_RIGHT.key in Live.state.value.designated)
    }

    @Test
    fun a_relief_for_another_seat_moves_the_mark_there() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        reliefRunsOut(b, Seat.ROW_2_RIGHT)
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(Seat.ROW_2_LEFT))
        assertEquals(Seat.ROW_2_LEFT.key, Live.state.value.reliefSeat)
        now += Policy.RELIEF_MAX_MIN * 60_000L
        b.tick()
        assertEquals(Seat.ROW_2_LEFT.key, Live.state.value.reliefSeat)
    }

    @Test
    fun a_relief_without_a_single_seat_leaves_no_mark_when_it_runs_out() {
        val b = brainAtNow()
        reliefRunsOut(b, null)
        assertEquals(CareMode.IDLE, Live.state.value.mode)
        assertEquals(null, Live.state.value.reliefSeat)
    }

    @Test
    fun ending_the_relief_by_hand_leaves_no_mark() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        b.voiceRelief(Seat.ROW_2_RIGHT)
        b.reliefEnd()
        now += Policy.RELIEF_MAX_MIN * 60_000L
        b.tick()
        assertEquals(null, Live.state.value.reliefSeat)
    }

    @Test
    fun a_repeated_call_during_relief_changes_nothing() {
        val b = brainAtNow()
        sitting(b, Seat.ROW_2_LEFT)
        b.voiceRelief(null)
        assertEquals(VoiceReliefResult.ALREADY, b.voiceRelief(null))
        assertEquals(VoiceReliefResult.ALREADY, b.voiceRelief(Seat.ROW_2_LEFT))
        assertEquals(Seat.ROW_2_LEFT, b.desired.target)
    }

    @Test
    fun a_designated_seat_is_used_without_asking() {
        val b = brainAtNow()
        b.toggleDesignate("ROW_3_CENTER")
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_3_CENTER)
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(null))
        assertEquals(Seat.ROW_3_CENTER, b.desired.target)
    }

    @Test
    fun when_nobody_is_reported_relief_starts_with_no_single_seat() {
        val b = brainAtNow()
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(null))
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
        assertEquals(null, b.desired.target)
    }
}
