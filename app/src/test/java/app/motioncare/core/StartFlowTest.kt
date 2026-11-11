// ver 1.0.0 | 2026-11-05 22:50
package app.motioncare.core

import app.motioncare.core.StartFlow.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartFlowTest {

    // ---- when the guided screens (title, introduction) may be shown at all ----

    @Test
    fun parked_car_allows_guided_screens() {
        assertTrue(StartFlow.guidedAllowed(Gear.PARK, 0.0, signalsConnected = true))
    }

    @Test
    fun parked_gear_wins_over_a_stale_speed() {
        assertTrue(StartFlow.guidedAllowed(Gear.PARK, 40.0, signalsConnected = true))
    }

    @Test
    fun driving_never_allows_guided_screens() {
        assertFalse(StartFlow.guidedAllowed(Gear.DRIVE, 60.0, signalsConnected = true))
        assertFalse(StartFlow.guidedAllowed(Gear.DRIVE, 3.0, signalsConnected = true))
    }

    @Test
    fun drive_gear_standing_still_is_not_parked() {
        assertFalse(StartFlow.guidedAllowed(Gear.DRIVE, 0.0, signalsConnected = true))
    }

    @Test
    fun reverse_and_neutral_are_not_parked() {
        assertFalse(StartFlow.guidedAllowed(Gear.REVERSE, 0.0, signalsConnected = true))
        assertFalse(StartFlow.guidedAllowed(Gear.NEUTRAL, 0.0, signalsConnected = true))
    }

    @Test
    fun unknown_gear_without_car_signals_is_a_demo_and_allows_guided_screens() {
        assertTrue(StartFlow.guidedAllowed(Gear.UNKNOWN, 0.0, signalsConnected = false))
    }

    @Test
    fun unknown_gear_with_car_signals_connected_does_not_allow_guided_screens() {
        assertFalse(StartFlow.guidedAllowed(Gear.UNKNOWN, 0.0, signalsConnected = true))
    }

    // ---- where the app starts ----

    @Test
    fun while_driving_the_app_goes_straight_home_even_for_a_first_run() {
        assertEquals(Stage.HOME, StartFlow.first(guided = false))
    }

    @Test
    fun parked_the_app_starts_with_the_title() {
        assertEquals(Stage.TITLE, StartFlow.first(guided = true))
    }

    @Test
    fun after_the_title_a_first_run_shows_the_introduction() {
        assertEquals(Stage.ONB1, StartFlow.afterTitle(onboardingDone = false, guided = true))
    }

    @Test
    fun after_the_title_a_returning_user_goes_home() {
        assertEquals(Stage.HOME, StartFlow.afterTitle(onboardingDone = true, guided = true))
    }

    @Test
    fun after_the_title_nothing_guided_when_not_parked() {
        assertEquals(Stage.HOME, StartFlow.afterTitle(onboardingDone = false, guided = false))
    }

    // ---- moving through the three introduction screens ----

    @Test
    fun next_walks_forward_and_ends_at_home() {
        assertEquals(Stage.ONB2, StartFlow.next(Stage.ONB1))
        assertEquals(Stage.ONB3, StartFlow.next(Stage.ONB2))
        assertEquals(Stage.HOME, StartFlow.next(Stage.ONB3))
        assertEquals(Stage.HOME, StartFlow.next(Stage.HOME))
    }

    @Test
    fun back_walks_backward_and_stops_at_the_first_screen() {
        assertEquals(Stage.ONB2, StartFlow.back(Stage.ONB3))
        assertEquals(Stage.ONB1, StartFlow.back(Stage.ONB2))
        assertEquals(Stage.ONB1, StartFlow.back(Stage.ONB1))
    }

    @Test
    fun the_title_has_no_next_step_of_its_own() {
        assertEquals(Stage.TITLE, StartFlow.next(Stage.TITLE))
        assertEquals(Stage.TITLE, StartFlow.back(Stage.TITLE))
    }

    // ---- the car starts moving while a guided screen is open ----

    @Test
    fun a_guided_screen_is_left_for_home_the_moment_guidance_is_not_allowed() {
        assertEquals(Stage.HOME, StartFlow.enforce(Stage.ONB2, guided = false))
        assertEquals(Stage.HOME, StartFlow.enforce(Stage.TITLE, guided = false))
    }

    @Test
    fun a_guided_screen_stays_while_parked() {
        assertEquals(Stage.ONB2, StartFlow.enforce(Stage.ONB2, guided = true))
        assertEquals(Stage.TITLE, StartFlow.enforce(Stage.TITLE, guided = true))
    }

    @Test
    fun home_stays_home() {
        assertEquals(Stage.HOME, StartFlow.enforce(Stage.HOME, guided = true))
        assertEquals(Stage.HOME, StartFlow.enforce(Stage.HOME, guided = false))
    }

    // ---- a few facts the screens rely on ----

    @Test
    fun title_lasts_one_and_a_half_seconds() {
        assertEquals(1500L, StartFlow.TITLE_MS)
    }

    @Test
    fun only_the_three_introduction_screens_can_be_skipped() {
        assertFalse(StartFlow.skippable(Stage.TITLE))
        assertTrue(StartFlow.skippable(Stage.ONB1))
        assertTrue(StartFlow.skippable(Stage.ONB2))
        assertTrue(StartFlow.skippable(Stage.ONB3))
        assertFalse(StartFlow.skippable(Stage.HOME))
    }
}
