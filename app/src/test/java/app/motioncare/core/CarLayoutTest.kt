// ver 1.4.0 | 2026-11-05 21:21
package app.motioncare.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLayoutTest {

    private fun rows(seats: Set<String> = emptySet(), doors: Set<String> = emptySet(), windows: Set<String> = emptySet()) =
        CarLayout.rowsFrom(seats, doors, windows)

    // ------------------------------------------------------------ how many rows the vehicle has

    @Test
    fun two_rows_when_the_vehicle_reports_nothing() {
        assertEquals(2, rows())
    }

    @Test
    fun missing_rear_seat_sensors_do_not_mean_a_one_row_car() {
        // many cars have occupancy sensors only in the front; that says nothing about the rear
        assertEquals(2, rows(seats = setOf("ROW_1_LEFT", "ROW_1_RIGHT")))
    }

    @Test
    fun one_row_when_doors_show_only_row_one() {
        assertEquals(1, rows(seats = setOf("ROW_1_LEFT"), doors = setOf("ROW_1_LEFT", "ROW_1_RIGHT", "HOOD", "REAR")))
    }

    @Test
    fun one_row_when_windows_show_only_row_one() {
        assertEquals(1, rows(windows = setOf("FRONT_WINDSHIELD", "REAR_WINDSHIELD", "ROW_1_LEFT", "ROW_1_RIGHT")))
    }

    @Test
    fun hood_and_trunk_alone_say_nothing_about_rows() {
        assertEquals(2, rows(doors = setOf("HOOD", "REAR")))
    }

    @Test
    fun row_two_evidence_from_any_source_gives_two_rows() {
        assertEquals(2, rows(doors = setOf("ROW_1_LEFT", "ROW_2_LEFT")))
        assertEquals(2, rows(windows = setOf("ROW_2_RIGHT")))
        assertEquals(2, rows(seats = setOf("ROW_2_CENTER")))
    }

    @Test
    fun row_three_evidence_gives_three_rows() {
        assertEquals(3, rows(doors = setOf("ROW_3_LEFT")))
        assertEquals(3, rows(seats = setOf("ROW_1_LEFT", "ROW_3_CENTER")))
    }

    // ------------------------------------------------------------ which seats are drawn

    @Test
    fun seats_per_layout() {
        assertEquals(listOf("ROW_1_LEFT", "ROW_1_RIGHT"), CarLayout.of(1).seats.map { it.seat.key }.sorted())
        assertEquals(
            listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT"),
            CarLayout.of(2).seats.map { it.seat.key }.sorted()
        )
        assertEquals(7, CarLayout.of(3).seats.size)
    }

    @Test
    fun unknown_row_counts_fall_back_to_the_nearest_layout() {
        assertEquals(1, CarLayout.of(0).rows)
        assertEquals(3, CarLayout.of(9).rows)
    }

    @Test
    fun the_last_row_stays_at_the_rear_and_rows_step_forward() {
        for (n in 1..3) {
            val l = CarLayout.of(n)
            assertEquals(290.0, l.seats.first { it.seat.row == n }.x.toDouble(), 0.01)
            assertEquals(290.0 + 136.0 * (n - 1), l.seats.first { it.seat.row == 1 }.x.toDouble(), 0.01)
        }
    }

    // ------------------------------------------------------------ speech bubbles

    @Test
    fun every_drawn_seat_has_a_bubble() {
        for (n in 1..3) {
            val l = CarLayout.of(n)
            assertEquals(l.seats.map { it.seat }.toSet(), l.bubbles.keys)
        }
    }

    @Test
    fun every_bubble_is_inside_the_cabin() {
        for (n in 1..3) {
            val l = CarLayout.of(n)
            for ((seat, spot) in l.bubbles) {
                for (b in CarLayout.footprint(spot)) {
                    assertTrue("rows=$n ${seat.key} 말풍선이 실내 밖: $b / ${l.cabin}", b.insideRounded(l.cabin, CarLayout.CABIN_R))
                }
            }
        }
    }

    @Test
    fun no_bubble_covers_another_seat_the_wheel_the_console_or_the_dashboard() {
        for (n in 1..3) {
            val l = CarLayout.of(n)
            for (slot in l.seats) {
                val spot = l.bubbles.getValue(slot.seat)
                val others = l.obstacles.filter { it != slot.box }
                for (b in CarLayout.footprint(spot)) {
                    for (o in others) {
                        assertTrue("rows=$n ${slot.seat.key} 말풍선이 $o 를 가림: $b", !b.overlaps(o))
                    }
                }
            }
        }
    }

    @Test
    fun a_bubble_keeps_a_few_units_of_air_from_the_steering_wheel() {
        for (n in 1..3) {
            val l = CarLayout.of(n)
            for (slot in l.seats) {
                for (b in CarLayout.footprint(l.bubbles.getValue(slot.seat))) {
                    assertTrue("rows=$n ${slot.seat.key} 핸들과 너무 가까움", !b.overlaps(l.wheel, 3f))
                }
            }
        }
    }

    @Test
    fun no_two_bubbles_overlap_so_all_seats_can_be_selected_at_once() {
        for (n in 1..3) {
            val l = CarLayout.of(n)
            val all = l.bubbles.entries.toList()
            for (i in all.indices) for (j in i + 1 until all.size) {
                for (a in CarLayout.footprint(all[i].value)) for (b in CarLayout.footprint(all[j].value)) {
                    assertTrue(
                        "rows=$n ${all[i].key.key} 와 ${all[j].key.key} 말풍선이 겹침",
                        !a.overlaps(b)
                    )
                }
            }
        }
    }

    @Test
    fun the_tail_tip_touches_the_head_circle_of_its_own_seat() {
        for (n in 1..3) {
            val l = CarLayout.of(n)
            for (slot in l.seats) {
                val tip = CarLayout.tip(l.bubbles.getValue(slot.seat))
                val dx = tip.first - (slot.x + CarLayout.HEAD_DX)
                val dy = tip.second - slot.y
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                assertTrue("rows=$n ${slot.seat.key} 꼭지가 머리 원 둘레에서 ${d - CarLayout.HEAD_R}만큼 벗어남", kotlin.math.abs(d - CarLayout.HEAD_R) < 0.01f)
            }
        }
    }

    @Test
    fun every_bubble_sits_above_the_head_with_its_tail_pointing_down() {
        for (n in 1..3) {
            val l = CarLayout.of(n)
            for (slot in l.seats) {
                val spot = l.bubbles.getValue(slot.seat)
                assertTrue("rows=$n ${slot.seat.key} 말풍선이 머리 위가 아님", spot.tailDown)
                val tip = CarLayout.tip(spot)
                assertTrue("rows=$n ${slot.seat.key} 꼭지가 머리 윗쪽이 아님", tip.second < slot.y)
            }
        }
    }

    @Test
    fun the_bubble_body_does_not_cover_the_head() {
        for (n in 1..3) {
            val l = CarLayout.of(n)
            for (slot in l.seats) {
                val head = Box(
                    slot.x + CarLayout.HEAD_DX - CarLayout.HEAD_R, slot.y - CarLayout.HEAD_R,
                    slot.x + CarLayout.HEAD_DX + CarLayout.HEAD_R, slot.y + CarLayout.HEAD_R
                )
                for (b in CarLayout.footprint(l.bubbles.getValue(slot.seat))) {
                    assertTrue("rows=$n ${slot.seat.key} 말풍선이 머리를 가림", !b.overlaps(head))
                }
            }
        }
    }
}
