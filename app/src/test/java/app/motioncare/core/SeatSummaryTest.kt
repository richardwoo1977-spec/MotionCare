// ver 1.0.0 | 2026-11-05 19:29
package app.motioncare.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SeatSummaryTest {

    @Test
    fun nothing_selected() {
        assertEquals("고르지 않았어요", SeatSummary.text(emptySet()))
    }

    @Test
    fun one_seat_names_its_row_and_side() {
        assertEquals("3열 오른쪽", SeatSummary.text(setOf("ROW_3_RIGHT")))
    }

    @Test
    fun a_whole_row_is_summed_up_as_whole() {
        assertEquals("3열 전체", SeatSummary.text(setOf("ROW_3_LEFT", "ROW_3_CENTER", "ROW_3_RIGHT")))
        assertEquals("2열 전체", SeatSummary.text(setOf("ROW_2_LEFT", "ROW_2_RIGHT")))
        assertEquals("1열 전체", SeatSummary.text(setOf("ROW_1_LEFT", "ROW_1_RIGHT")))
    }

    @Test
    fun two_of_three_lists_sides_left_to_right() {
        assertEquals("3열 왼쪽, 오른쪽", SeatSummary.text(setOf("ROW_3_RIGHT", "ROW_3_LEFT")))
    }

    @Test
    fun rows_are_joined_from_the_back_to_the_front() {
        assertEquals(
            "3열 가운데 · 1열 왼쪽",
            SeatSummary.text(setOf("ROW_1_LEFT", "ROW_3_CENTER"))
        )
    }

    @Test
    fun occupied_count_only_counts_drawn_seats_that_are_true() {
        val occ = mapOf<String, Boolean?>(
            "ROW_1_LEFT" to true, "ROW_1_RIGHT" to false, "ROW_2_LEFT" to null,
            "ROW_3_LEFT" to true, "ROW_3_RIGHT" to true, "ROW_2_CENTER" to true
        )
        assertEquals(3, SeatSummary.occupiedCount(occ))
    }
}
