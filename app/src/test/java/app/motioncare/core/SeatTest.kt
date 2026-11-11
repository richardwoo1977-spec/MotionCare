// ver 1.0.0 | 2026-11-05 19:29
package app.motioncare.core

import ai.pleos.playground.vehicle.constant.area.SeatValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SeatTest {

    @Test
    fun third_row_seats_exist() {
        assertNotNull(Seat.fromKey("ROW_3_LEFT"))
        assertNotNull(Seat.fromKey("ROW_3_CENTER"))
        assertNotNull(Seat.fromKey("ROW_3_RIGHT"))
    }

    @Test
    fun every_seat_key_is_a_vehicle_sdk_seat_value() {
        val sdk = SeatValue.entries.map { it.name }.toSet()
        Seat.entries.forEach { assertEquals("SDK에 없는 좌석 ${it.key}", true, it.key in sdk) }
    }

    @Test
    fun drawn_seats_are_two_two_and_three_per_row() {
        assertEquals(7, Seat.drawn.size)
        assertEquals(2, Seat.drawn.count { it.key.startsWith("ROW_1") })
        assertEquals(2, Seat.drawn.count { it.key.startsWith("ROW_2") })
        assertEquals(3, Seat.drawn.count { it.key.startsWith("ROW_3") })
    }
}
