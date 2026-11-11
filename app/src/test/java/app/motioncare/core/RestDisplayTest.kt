// ver 1.0.0 | 2026-11-05 19:29
package app.motioncare.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RestDisplayTest {

    @Test
    fun km_text_rounds_and_never_exceeds_three_digits() {
        assertEquals("18km 앞", RestDisplay.kmText(18.4))
        assertEquals("19km 앞", RestDisplay.kmText(18.6))
        assertEquals("999km 앞", RestDisplay.kmText(999.4))
        assertEquals("999km 앞", RestDisplay.kmText(1234.0))
    }

    @Test
    fun short_names_use_the_biggest_size() {
        assertEquals(42, RestDisplay.nameSizePx("안성", 18))
        assertEquals(42, RestDisplay.nameSizePx("서울만남", 999))
    }

    @Test
    fun longer_names_step_down_in_size() {
        assertEquals(40, RestDisplay.nameSizePx("고삼호수랜", 18))
        assertEquals(36, RestDisplay.nameSizePx("금산인삼랜드", 18))
        assertEquals(38, RestDisplay.nameSizePx("고삼호수랜", 999))
    }

    @Test
    fun the_longest_real_name_with_three_digits_fits_at_34() {
        assertEquals(34, RestDisplay.nameSizePx("정읍녹두장군", 999))
    }

    @Test
    fun a_name_that_never_fits_stays_at_34_and_is_cut_by_the_screen() {
        assertEquals(34, RestDisplay.nameSizePx("Jeongeup Nokdujanggun", 999))
    }
}
