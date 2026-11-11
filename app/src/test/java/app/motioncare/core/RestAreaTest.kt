// ver 1.0.0 | 2026-11-05 19:29
package app.motioncare.core

import app.motioncare.data.DemoNavi
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestAreaTest {

    private fun area(name: String, lat: Double, lon: Double, route: String = "경부선", dir: String = "하행") =
        RestArea(name, route, dir, lat, lon, toilet = true, nursing = true, kids = false, asOf = "2026-09-17")

    private fun realCsv(): String {
        val f = listOf("src/main/assets/rest_areas.csv", "app/src/main/assets/rest_areas.csv").map(::File).first { it.exists() }
        return f.readText(Charsets.UTF_8)
    }

    // ---- 이름 정리
    @Test
    fun clean_name_drops_the_direction_in_parentheses() {
        assertEquals("강릉", RestCatalog.cleanName("강릉(인천)"))
        assertEquals("익산미륵사지", RestCatalog.cleanName("익산미륵사지(천안)"))
        assertEquals("고삼호수", RestCatalog.cleanName("고삼호수"))
    }

    @Test
    fun clean_name_drops_the_word_hugeso() {
        assertEquals("안성", RestCatalog.cleanName("안성휴게소(서울)"))
        assertEquals("안성", RestCatalog.cleanName(" 안성 휴게소 "))
    }

    // ---- CSV 읽기
    @Test
    fun parse_reads_columns_by_header_name_and_handles_bom_and_quotes() {
        val csv = "﻿휴게소명,도로노선명,도로노선방향,위도,경도,화장실유무,수유실유무,기타편의시설,데이터기준일자\r\n" +
            "안성(서울),경부선,하행,37.0123,127.2345,Y,Y,\"키즈존, 쉼터\",2026-09-17\r\n" +
            "좌표없음(서울),경부선,하행,,,Y,Y,,2026-09-17\r\n" +
            "화장실없음(서울),경부선,상행,36.5,127.5,N,N,,2026-09-17\r\n"
        val list = RestCatalog.parse(csv)
        assertEquals(2, list.size)
        val a = list[0]
        assertEquals("안성", a.name)
        assertEquals("경부선", a.route)
        assertEquals("하행", a.direction)
        assertEquals(37.0123, a.lat, 1e-9)
        assertTrue(a.toilet && a.nursing && a.kids)
        assertEquals("2026-09-17", a.asOf)
        assertFalse(list[1].toilet)
    }

    @Test
    fun the_bundled_file_has_215_rows_with_clean_names() {
        val list = RestCatalog.parse(realCsv())
        assertEquals(215, list.size)
        assertTrue(list.none { "(" in it.name || ")" in it.name || "휴게소" in it.name })
        assertTrue(list.all { it.lat in 33.0..39.0 && it.lon in 124.0..132.0 })
        assertTrue(list.all { it.asOf == "2026-09-17" })
    }

    @Test
    fun the_longest_name_is_six_characters() {
        val list = RestCatalog.parse(realCsv())
        assertEquals(6, list.maxOf { it.name.length })
        assertTrue(list.any { it.name == "정읍녹두장군" })
    }

    // ---- 앞쪽 휴게소 고르기
    @Test
    fun stops_ahead_keep_only_stops_in_front_and_sort_by_distance() {
        val catalog = listOf(
            area("남40", 36.64, 127.0),
            area("북5", 37.045, 127.0),
            area("동20", 37.0, 127.225),
            area("남10", 36.91, 127.0)
        )
        val s = RestAhead.stopsAhead(catalog, 37.0, 127.0, headingDeg = 180.0)
        assertEquals(listOf("남10", "남40"), s.map { it.name })
        assertEquals(10.0, s[0].distanceKm, 0.5)
        assertEquals(40.0, s[1].distanceKm, 1.0)
    }

    @Test
    fun a_stop_listed_for_both_directions_appears_once() {
        val catalog = listOf(area("강릉", 36.88, 127.0, dir = "하행"), area("강릉", 36.882, 127.0, dir = "상행"))
        val s = RestAhead.stopsAhead(catalog, 37.0, 127.0, headingDeg = 180.0)
        assertEquals(1, s.size)
        assertEquals("강릉", s[0].name)
    }

    @Test
    fun stops_carry_the_facility_flags() {
        val catalog = listOf(
            RestArea("가", "경부선", "하행", 36.9, 127.0, toilet = true, nursing = false, kids = true, asOf = "2026-09-17")
        )
        val s = RestAhead.stopsAhead(catalog, 37.0, 127.0, headingDeg = 180.0).single()
        assertTrue(s.toilet && !s.nursing && s.kids)
    }

    @Test
    fun the_demo_position_has_real_stops_ahead() {
        DemoNavi.setCatalog(RestCatalog.parse(realCsv()))
        try {
            val s = DemoNavi.stops
            assertTrue(s.size >= 3)
            assertEquals(s.sortedBy { it.distanceKm }, s)
            assertTrue(s.all { it.distanceKm in 0.0..999.0 })
            assertTrue(s.none { it.name.startsWith("시연") })
        } finally {
            DemoNavi.setCatalog(emptyList())
        }
    }

    // ---- 3~999km 규칙
    @Test
    fun a_stop_farther_than_999_km_is_never_offered() {
        val stops = listOf(RestStop("far", "먼곳", 1000.0, 600, toilet = true, nursing = true, kids = false))
        assertEquals(null, Policy.pickStop(stops))
        val ok = listOf(RestStop("edge", "끝", 999.0, 600, toilet = true, nursing = false, kids = false))
        assertEquals("edge", Policy.pickStop(ok)?.id)
    }
}
