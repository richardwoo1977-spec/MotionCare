// ver 1.0.0 | 2026-11-05 19:30
package app.motioncare.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One row of the Korea Expressway Corporation rest-area file (name already cleaned). */
data class RestArea(
    val name: String,
    val route: String,
    val direction: String,
    val lat: Double,
    val lon: Double,
    val toilet: Boolean,
    val nursing: Boolean,
    val kids: Boolean,
    val asOf: String
)

object RestCatalog {
    /** "안성휴게소(서울)" -> "안성". The direction in parentheses and the word 휴게소 are not shown. */
    fun cleanName(raw: String): String =
        raw.trim()
            .replace(Regex("\\s*\\([^)]*\\)\\s*$"), "")
            .replace("휴게소", "")
            .trim()

    /** Reads the file by column name. Rows without a name or a usable position are skipped. */
    fun parse(csv: String): List<RestArea> {
        val table = readCsv(csv.removePrefix("﻿"))
        if (table.size < 2) return emptyList()
        val head = table[0].mapIndexed { i, h -> h.trim() to i }.toMap()
        fun cell(row: List<String>, col: String): String = head[col]?.let { row.getOrNull(it) }?.trim() ?: ""
        return table.drop(1).mapNotNull { row ->
            val name = cleanName(cell(row, "휴게소명"))
            val lat = cell(row, "위도").toDoubleOrNull()
            val lon = cell(row, "경도").toDoubleOrNull()
            if (name.isEmpty() || lat == null || lon == null) null
            else RestArea(
                name = name,
                route = cell(row, "도로노선명"),
                direction = cell(row, "도로노선방향"),
                lat = lat,
                lon = lon,
                toilet = cell(row, "화장실유무") == "Y",
                nursing = cell(row, "수유실유무") == "Y",
                kids = "키즈" in cell(row, "기타편의시설"),
                asOf = cell(row, "데이터기준일자")
            )
        }
    }

    /** Minimal CSV reader: quotes, doubled quotes, commas and line breaks inside quotes. */
    private fun readCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row.add(cell.toString()); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row.add(cell.toString()); cell.clear()
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            if (row.any { it.isNotBlank() }) rows.add(row)
        }
        return rows
    }
}

/** Picks the rest areas in front of the car from a position and a heading (straight-line distance). */
object RestAhead {
    private const val EARTH_KM = 6371.0088

    fun stopsAhead(
        catalog: List<RestArea>,
        lat: Double,
        lon: Double,
        headingDeg: Double,
        maxBearingDiffDeg: Double = 60.0,
        maxKm: Double = Policy.REST_MAX_KM,
        averageKmh: Double = 80.0
    ): List<RestStop> =
        catalog
            .map { it to distanceKm(lat, lon, it.lat, it.lon) }
            .filter { (a, d) ->
                d <= maxKm && angleDiff(bearingDeg(lat, lon, a.lat, a.lon), headingDeg) <= maxBearingDiffDeg
            }
            .groupBy { it.first.name }
            .map { (_, list) -> list.minByOrNull { it.second }!! }
            .sortedBy { it.second }
            .map { (a, d) ->
                RestStop(
                    id = "${a.route}|${a.name}|${a.direction}",
                    name = a.name,
                    distanceKm = d,
                    etaMin = (d / averageKmh * 60.0).roundToInt(),
                    toilet = a.toilet,
                    nursing = a.nursing,
                    kids = a.kids
                )
            }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_KM * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Compass bearing from point 1 to point 2, 0..360 degrees. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angleDiff(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)
}
