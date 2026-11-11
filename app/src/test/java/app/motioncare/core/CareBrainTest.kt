// ver 1.9.0 | 2026-11-11 20:30
package app.motioncare.core

import app.motioncare.data.Live
import app.motioncare.data.LiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CareBrainTest {

    private var now = 1_000_000L

    private fun brain(): CareBrain {
        Live.update { LiveState() }
        now = 1_000_000L
        val b = CareBrain(clock = { now }, speak = {}, onDesired = {})
        b.init()
        return b
    }

    private fun kmh(b: CareBrain, v: Double) = b.onSpeedMps(v / 3.6)

    /** A three-row car: the given seats are taken, the others not known (null). */
    private fun sitting(b: CareBrain, vararg seats: Seat) =
        b.onOccupancy(Seat.entries.associate { it.key to (if (it in seats) true else null) })

    /** A two-row car (5 seats, as the emulator reports): the given seats are taken, the others empty. */
    private fun twoRowCar(b: CareBrain, vararg seats: Seat) =
        b.onOccupancy(
            listOf(Seat.ROW_1_LEFT, Seat.ROW_1_RIGHT, Seat.ROW_2_LEFT, Seat.ROW_2_CENTER, Seat.ROW_2_RIGHT)
                .associate { it.key to (it in seats) as Boolean? }
        )

    private fun keys(vararg seats: Seat): Set<String> = seats.map { it.key }.toSet()

    // ---- relief ----

    @Test
    fun relief_can_be_started_while_driving() {
        val b = brain()
        b.onGear(Gear.DRIVE)
        kmh(b, 80.0)
        b.tapSeat(Seat.ROW_2_LEFT.key)
        b.reliefPressed()
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
        assertTrue(b.desired.recircOff)
        assertEquals(FanWant.MAX, b.desired.fan)
        assertTrue(b.desired.faceDirection)
    }

    @Test
    fun the_window_is_never_opened_by_the_app_only_suggested() {
        val spoken = mutableListOf<String>()
        Live.update { LiveState() }
        now = 1_000_000L
        val b = CareBrain(clock = { now }, speak = { spoken += it }, onDesired = {})
        b.init()
        b.onGear(Gear.DRIVE)
        kmh(b, 20.0)
        b.tapSeat(Seat.ROW_2_LEFT.key)
        b.reliefPressed()
        assertFalse(b.desired.windowOpen)
        b.onGear(Gear.PARK)
        kmh(b, 0.0)
        assertFalse(b.desired.windowOpen)
        assertEquals("화면 대신 먼 곳을 보세요", Live.state.value.caption)
        // the guidance is spoken 5 s after the start (after Gleo AI's own answer), once
        assertTrue(spoken.isEmpty())
        now += 4_000L
        b.tick()
        assertTrue(spoken.isEmpty())
        now += 1_000L
        b.tick()
        assertEquals("화면 대신 먼 곳을 보세요. 밖이 괜찮으면 창문을 조금 열어 보세요", spoken.single())
        now += 3_000L
        b.tick()
        assertEquals(1, spoken.size)
        assertEquals("밖이 괜찮으면 창문을 조금 열어 보세요", Live.state.value.caption)
    }

    @Test
    fun nothing_is_spoken_when_the_relief_ends_within_five_seconds() {
        val spoken = mutableListOf<String>()
        Live.update { LiveState() }
        now = 1_000_000L
        val b = CareBrain(clock = { now }, speak = { spoken += it }, onDesired = {})
        b.init()
        b.tapSeat(Seat.ROW_2_LEFT.key)
        b.reliefPressed()
        now += 2_000L
        b.reliefEnd()
        now += 10_000L
        b.tick()
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun nothing_changes_while_driving_until_someone_says_it() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_3_CENTER)
        b.onGear(Gear.DRIVE)
        kmh(b, 60.0)
        assertEquals(CareMode.IDLE, Live.state.value.mode)
        assertTrue(b.desired.isIdle)
    }

    // ---- the screen: tap seats, then the button ----

    @Test
    fun the_button_does_nothing_until_a_seat_is_picked() {
        val b = brain()
        sitting(b, Seat.ROW_1_RIGHT, Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT)
        assertFalse(Live.state.value.canRelieve)
        b.reliefPressed()
        assertEquals(CareMode.IDLE, Live.state.value.mode)
        assertTrue(b.desired.isIdle)
        assertEquals(emptySet<String>(), Live.state.value.reliefSeats)
    }

    @Test
    fun the_button_relieves_exactly_the_picked_seats() {
        val b = brain()
        sitting(b, Seat.ROW_1_RIGHT, Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT)
        b.tapSeat(Seat.ROW_2_LEFT.key)
        b.tapSeat(Seat.ROW_3_RIGHT.key)
        assertTrue(Live.state.value.canRelieve)
        assertEquals(keys(Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT), Live.state.value.pickedSeats)
        b.reliefPressed()
        assertEquals(setOf(Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT), b.desired.targets)
        assertEquals(emptySet<String>(), Live.state.value.pickedSeats)
        assertFalse(Live.state.value.canRelieve)
    }

    @Test
    fun a_second_tap_unpicks_and_the_driver_seat_cannot_be_picked() {
        val b = brain()
        b.tapSeat(Seat.ROW_2_LEFT.key)
        b.tapSeat(Seat.ROW_2_LEFT.key)
        b.tapSeat(Seat.ROW_1_LEFT.key)
        assertEquals(emptySet<String>(), Live.state.value.pickedSeats)
        assertFalse(Live.state.value.canRelieve)
    }

    @Test
    fun taps_are_ignored_while_relieving() {
        val b = brain()
        b.tapSeat(Seat.ROW_2_LEFT.key)
        b.reliefPressed()
        b.tapSeat(Seat.ROW_2_RIGHT.key)
        b.tapSeat(Seat.ROW_2_LEFT.key)
        assertEquals(setOf(Seat.ROW_2_LEFT), b.desired.targets)
        assertEquals(emptySet<String>(), Live.state.value.pickedSeats)
    }

    @Test
    fun a_voice_call_starts_at_once_whatever_is_picked_and_clears_the_picks() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        b.tapSeat(Seat.ROW_2_LEFT.key)
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(setOf(Seat.ROW_1_RIGHT)))
        assertEquals(setOf(Seat.ROW_1_RIGHT), b.desired.targets)
        assertEquals(emptySet<String>(), Live.state.value.pickedSeats)
    }

    @Test
    fun a_voice_call_without_a_seat_uses_the_picked_seats() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        b.tapSeat(Seat.ROW_2_RIGHT.key)
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(emptySet()))
        assertEquals(setOf(Seat.ROW_2_RIGHT), b.desired.targets)
    }

    @Test
    fun a_seat_said_after_the_button_moves_the_relief() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        b.tapSeat(Seat.ROW_2_LEFT.key)
        b.reliefPressed()
        assertEquals(VoiceReliefResult.RETARGETED, b.voiceRelief(setOf(Seat.ROW_2_RIGHT)))
        assertEquals(setOf(Seat.ROW_2_RIGHT), b.desired.targets)
    }

    // ---- relief by voice, with the question "which seat?" ----

    @Test
    fun one_passenger_is_relieved_without_asking() {
        val b = brain()
        sitting(b, Seat.ROW_3_RIGHT)
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(emptySet()))
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
        assertEquals(setOf(Seat.ROW_3_RIGHT), b.desired.targets)
    }

    @Test
    fun the_front_passenger_counts_as_the_only_passenger() {
        val b = brain()
        sitting(b, Seat.ROW_1_RIGHT)
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(emptySet()))
        assertEquals(setOf(Seat.ROW_1_RIGHT), b.desired.targets)
    }

    @Test
    fun the_driver_alone_does_not_count_as_a_passenger() {
        val b = brain()
        sitting(b, Seat.ROW_1_LEFT)
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(emptySet()))
        assertEquals(emptySet<Seat>(), b.desired.targets)
    }

    @Test
    fun two_passengers_and_no_seat_named_means_asking() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT)
        assertEquals(VoiceReliefResult.ASKING, b.voiceRelief(emptySet()))
        assertEquals(CareMode.IDLE, Live.state.value.mode)
        assertTrue(Live.state.value.caption.isNotBlank())
    }

    @Test
    fun the_seat_named_after_the_question_starts_relief_for_that_seat() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT)
        b.voiceRelief(emptySet())
        now += 4_000L
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(setOf(Seat.ROW_3_RIGHT)))
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
        assertEquals(setOf(Seat.ROW_3_RIGHT), b.desired.targets)
    }

    @Test
    fun a_seat_named_in_the_first_call_needs_no_question() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT, Seat.ROW_1_RIGHT)
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(setOf(Seat.ROW_2_LEFT)))
        assertEquals(setOf(Seat.ROW_2_LEFT), b.desired.targets)
    }

    @Test
    fun without_an_answer_nothing_is_guessed_and_a_later_answer_still_starts_the_relief() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT)
        b.voiceRelief(emptySet())
        now += Policy.SEAT_ASK_SEC * 1000L
        b.tick()
        assertEquals(CareMode.IDLE, Live.state.value.mode)
        assertTrue(b.desired.isIdle)
        assertEquals(emptySet<String>(), Live.state.value.reliefSeats)
        assertEquals("어느 자리인지 말씀해 주세요", Live.state.value.caption)
        now += 20_000L
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(setOf(Seat.ROW_1_RIGHT)))
        assertEquals(setOf(Seat.ROW_1_RIGHT), b.desired.targets)
    }

    @Test
    fun the_question_is_dropped_when_the_driver_ends_it_or_presses_the_button() {
        val a = brain()
        sitting(a, Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT)
        a.voiceRelief(emptySet())
        a.reliefEnd()
        now += 60_000L
        a.tick()
        assertEquals(CareMode.IDLE, Live.state.value.mode)

        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_3_RIGHT)
        b.voiceRelief(emptySet())
        b.tapSeat(Seat.ROW_3_RIGHT.key)
        b.reliefPressed()
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
        assertEquals(setOf(Seat.ROW_3_RIGHT), b.desired.targets)
    }

    @Test
    fun naming_another_seat_while_relieving_moves_the_target() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT)
        b.voiceRelief(emptySet())
        assertEquals(setOf(Seat.ROW_2_LEFT), b.desired.targets)
        assertEquals(VoiceReliefResult.RETARGETED, b.voiceRelief(setOf(Seat.ROW_3_CENTER)))
        assertEquals(setOf(Seat.ROW_3_CENTER), b.desired.targets)
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
    }

    @Test
    fun the_screen_is_told_which_seats_the_relief_follows() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        assertEquals(emptySet<String>(), Live.state.value.reliefSeats)
        b.voiceRelief(setOf(Seat.ROW_2_LEFT))
        assertEquals(keys(Seat.ROW_2_LEFT), Live.state.value.reliefSeats)
        b.voiceRelief(setOf(Seat.ROW_2_RIGHT))
        assertEquals(keys(Seat.ROW_2_RIGHT), Live.state.value.reliefSeats)
        b.reliefEnd()
        assertEquals(emptySet<String>(), Live.state.value.reliefSeats)
    }

    // ---- groups of seats said at once ----

    @Test
    fun a_group_looks_after_every_seat_in_it() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT, Seat.ROW_3_CENTER)
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(GleoSeat.fromValue("SECOND_ROW_ALL")))
        // the car reports nothing about the middle seat of row 2, so it is kept
        assertEquals(setOf(Seat.ROW_2_LEFT, Seat.ROW_2_CENTER, Seat.ROW_2_RIGHT), b.desired.targets)
    }

    @Test
    fun a_group_leaves_out_seats_the_car_reports_as_empty() {
        val b = brain()
        b.onOccupancy(
            mapOf<String, Boolean?>(
                "ROW_1_RIGHT" to true, "ROW_2_RIGHT" to false, "ROW_3_RIGHT" to true,
                "ROW_2_LEFT" to true
            )
        )
        b.voiceRelief(GleoSeat.fromValue("PASSENGER_SIDE_FRONT_AND_REAR"))
        assertEquals(setOf(Seat.ROW_1_RIGHT, Seat.ROW_3_RIGHT), b.desired.targets)
    }

    @Test
    fun a_group_of_seats_all_reported_empty_is_used_as_said() {
        val b = brain()
        b.onOccupancy(mapOf<String, Boolean?>("ROW_3_LEFT" to false, "ROW_3_CENTER" to false, "ROW_3_RIGHT" to false))
        b.voiceRelief(GleoSeat.fromValue("THIRD_ROW_ALL"))
        assertEquals(setOf(Seat.ROW_3_LEFT, Seat.ROW_3_CENTER, Seat.ROW_3_RIGHT), b.desired.targets)
    }

    @Test
    fun a_group_said_while_relieving_moves_the_relief_to_the_group() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT)
        b.voiceRelief(emptySet())
        assertEquals(VoiceReliefResult.RETARGETED, b.voiceRelief(GleoSeat.fromValue("ALL_REAR_SEATS")))
        assertEquals(6, b.desired.targets.size)
    }

    @Test
    fun the_history_keeps_every_seat_of_a_group() {
        // the store has no device storage in unit tests, so the text the brain writes is checked directly
        val text = SeatKeys.join(setOf(Seat.ROW_2_RIGHT, Seat.ROW_2_LEFT))
        assertEquals("ROW_2_LEFT+ROW_2_RIGHT", text)
        assertEquals(keys(Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT), SeatKeys.split(text))
        assertEquals("2열 전체", SeatKeys.label(text))
        assertEquals("", SeatKeys.join(emptySet()))
    }

    @Test
    fun seat_words_for_the_history() {
        assertEquals("자리 모름", SeatKeys.label(""))
        assertEquals("조수석", SeatKeys.label("ROW_1_RIGHT"))
        assertEquals("2열 왼쪽", SeatKeys.label("ROW_2_LEFT"))
        assertEquals("3열 오른쪽 · 2열 오른쪽 · 조수석", SeatKeys.label("ROW_1_RIGHT+ROW_2_RIGHT+ROW_3_RIGHT"))
    }

    // ---- a seat this car does not have ----

    @Test
    fun a_third_row_seat_in_a_two_row_car_is_asked_again_and_nothing_starts() {
        val b = brain()
        twoRowCar(b, Seat.ROW_1_RIGHT, Seat.ROW_2_RIGHT)
        assertEquals(VoiceReliefResult.ASKING, b.voiceRelief(setOf(Seat.ROW_3_RIGHT)))
        assertEquals(CareMode.IDLE, Live.state.value.mode)
        assertTrue(b.desired.isIdle)
        assertEquals("다시 말씀해 주시겠어요? 어느 자리인가요?", Live.state.value.caption)
        // the answer that follows starts the relief for the right seat
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(setOf(Seat.ROW_2_RIGHT)))
        assertEquals(setOf(Seat.ROW_2_RIGHT), b.desired.targets)
    }

    @Test
    fun a_third_row_seat_said_while_relieving_a_two_row_car_changes_nothing_and_asks_again() {
        val b = brain()
        twoRowCar(b, Seat.ROW_2_LEFT)
        b.voiceRelief(setOf(Seat.ROW_2_LEFT))
        assertEquals(VoiceReliefResult.ASKING, b.voiceRelief(setOf(Seat.ROW_3_CENTER)))
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
        assertEquals(setOf(Seat.ROW_2_LEFT), b.desired.targets)
    }

    @Test
    fun a_group_keeps_only_the_seats_this_car_has() {
        val b = brain()
        twoRowCar(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        b.voiceRelief(GleoSeat.fromValue("ALL_REAR_SEATS"))
        assertEquals(setOf(Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT), b.desired.targets)
    }

    @Test
    fun a_third_row_group_in_a_two_row_car_is_asked_again() {
        val b = brain()
        twoRowCar(b, Seat.ROW_2_LEFT)
        assertEquals(VoiceReliefResult.ASKING, b.voiceRelief(GleoSeat.fromValue("THIRD_ROW_ALL")))
        assertEquals(CareMode.IDLE, Live.state.value.mode)
    }

    // ---- after the 10 minutes: the settings go back, the seats stay marked ----

    private fun reliefRunsOut(b: CareBrain, seats: Set<Seat>) {
        b.voiceRelief(seats)
        now += Policy.RELIEF_MAX_MIN * 60_000L
        b.tick()
    }

    @Test
    fun when_the_ten_minutes_run_out_the_settings_go_back_but_the_seats_stay_marked() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        reliefRunsOut(b, setOf(Seat.ROW_2_RIGHT))
        assertEquals(CareMode.IDLE, Live.state.value.mode)
        assertTrue(b.desired.isIdle)
        assertEquals(keys(Seat.ROW_2_RIGHT), Live.state.value.reliefSeats)
    }

    @Test
    fun the_button_after_the_ten_minutes_restarts_the_relief_for_the_marked_seats() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        reliefRunsOut(b, setOf(Seat.ROW_2_RIGHT))
        b.reliefPressed()
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
        assertEquals(setOf(Seat.ROW_2_RIGHT), b.desired.targets)
    }

    @Test
    fun a_voice_call_without_a_seat_after_the_ten_minutes_needs_no_question() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        reliefRunsOut(b, setOf(Seat.ROW_2_RIGHT))
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(emptySet()))
        assertEquals(setOf(Seat.ROW_2_RIGHT), b.desired.targets)
    }

    @Test
    fun after_the_ten_minutes_a_tap_toggles_the_marked_seats_and_the_button_restarts_them() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        reliefRunsOut(b, setOf(Seat.ROW_2_RIGHT))
        assertTrue(Live.state.value.canRelieve)
        b.tapSeat(Seat.ROW_2_LEFT.key)
        assertEquals(keys(Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT), Live.state.value.reliefSeats)
        b.tapSeat(Seat.ROW_2_RIGHT.key)
        assertEquals(keys(Seat.ROW_2_LEFT), Live.state.value.reliefSeats)
        b.reliefPressed()
        assertEquals(setOf(Seat.ROW_2_LEFT), b.desired.targets)
    }

    @Test
    fun saying_it_is_over_clears_the_mark() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        reliefRunsOut(b, setOf(Seat.ROW_2_RIGHT))
        b.reliefEnd()
        assertEquals(emptySet<String>(), Live.state.value.reliefSeats)
    }

    @Test
    fun a_relief_for_another_seat_moves_the_mark_there() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        reliefRunsOut(b, setOf(Seat.ROW_2_RIGHT))
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(setOf(Seat.ROW_2_LEFT)))
        assertEquals(keys(Seat.ROW_2_LEFT), Live.state.value.reliefSeats)
        now += Policy.RELIEF_MAX_MIN * 60_000L
        b.tick()
        assertEquals(keys(Seat.ROW_2_LEFT), Live.state.value.reliefSeats)
    }

    @Test
    fun a_relief_for_the_whole_cabin_leaves_no_mark_when_it_runs_out() {
        val b = brain()
        reliefRunsOut(b, emptySet())
        assertEquals(CareMode.IDLE, Live.state.value.mode)
        assertEquals(emptySet<String>(), Live.state.value.reliefSeats)
        assertFalse(Live.state.value.reliefWhole)
    }

    @Test
    fun ending_the_relief_by_hand_leaves_no_mark() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT, Seat.ROW_2_RIGHT)
        b.voiceRelief(setOf(Seat.ROW_2_RIGHT))
        b.reliefEnd()
        now += Policy.RELIEF_MAX_MIN * 60_000L
        b.tick()
        assertEquals(emptySet<String>(), Live.state.value.reliefSeats)
    }

    @Test
    fun a_repeated_call_during_relief_changes_nothing() {
        val b = brain()
        sitting(b, Seat.ROW_2_LEFT)
        b.voiceRelief(emptySet())
        assertEquals(VoiceReliefResult.ALREADY, b.voiceRelief(emptySet()))
        assertEquals(VoiceReliefResult.ALREADY, b.voiceRelief(setOf(Seat.ROW_2_LEFT)))
        assertEquals(setOf(Seat.ROW_2_LEFT), b.desired.targets)
    }

    @Test
    fun when_nobody_is_reported_relief_starts_for_the_whole_cabin() {
        val b = brain()
        assertEquals(VoiceReliefResult.STARTED, b.voiceRelief(emptySet()))
        assertEquals(CareMode.RELIEF, Live.state.value.mode)
        assertEquals(emptySet<Seat>(), b.desired.targets)
        assertTrue(Live.state.value.reliefWhole)
    }
}
