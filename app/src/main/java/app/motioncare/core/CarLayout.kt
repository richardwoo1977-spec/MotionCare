// ver 1.4.0 | 2026-11-05 21:21
package app.motioncare.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A rectangle in the car drawing space (1040 x 520). */
data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    fun overlaps(o: Box, margin: Float = 0f): Boolean =
        l < o.r + margin && o.l < r + margin && t < o.b + margin && o.t < b + margin

    fun inside(o: Box): Boolean = l >= o.l && r <= o.r && t >= o.t && b <= o.b

    /** Inside [o], a rectangle whose four corners are rounded with radius [radius]. */
    fun insideRounded(o: Box, radius: Float): Boolean {
        if (!inside(o)) return false
        for ((px, py) in listOf(l to t, r to t, l to b, r to b)) {
            val cx = min(max(px, o.l + radius), o.r - radius)
            val cy = min(max(py, o.t + radius), o.b - radius)
            val dx = px - cx
            val dy = py - cy
            if (sqrt(dx * dx + dy * dy) > radius) return false
        }
        return true
    }
}

/** A seat as drawn: [x], [y] are the seat centre. */
data class SeatSlot(val seat: Seat, val x: Float, val y: Float) {
    val box: Box get() = Box(x - 41f, y - 33f, x + 36f, y + 33f)
}

/** Where a speech bubble sits: top-left corner of its body, and whether its tail points down or up. */
data class BubbleSpot(val x: Float, val y: Float, val tailDown: Boolean)

/**
 * How the cabin is drawn for a car with 1, 2 or 3 seat rows, and where each seat's "멀미 나요" speech
 * bubble goes. The last row always sits at the rear (x = 290); the rows in front of it step forward by
 * 136, and the front of the cabin (windshield, dashboard, console, steering wheel) moves with them.
 *
 * A bubble sits on its own seat, centred on the person sitting there, so it is the same for every seat and
 * every layout. It stays inside the cabin, covers no other seat, steering wheel, console or dashboard,
 * and overlaps no other bubble (checked for all layouts in CarLayoutTest).
 */
class CarLayout private constructor(val rows: Int) {

    /** How far the cabin front has moved back compared with a three-row car. */
    val frontShift: Float = 136f * (3 - rows)

    val seats: List<SeatSlot> = buildSeats(rows)

    val cabin: Box = Box(206f, 98f, 722f - frontShift, 402f)

    /** The steering wheel with its ring stroke: centre (652, 184), outer radius 27. */
    val wheel: Box = Box(625f - frontShift, 157f, 679f - frontShift, 211f)
    val console: Box = Box(506f - frontShift, 230f, 638f - frontShift, 266f)
    val dashboard: Box = Box(686f - frontShift, 126f, 722f - frontShift, 374f)

    /** Everything a bubble must keep clear of. */
    val obstacles: List<Box> = seats.map { it.box } + listOf(wheel, console, dashboard)

    /**
     * Every bubble sits above the head of the person on its seat: the tail tip touches the top of the head circle
     * and the body extends toward the rear. This is the same for every seat and every layout, and it stays inside the
     * rounded cabin, clear of other seats, the wheel, console, dashboard and earlier bubbles.
     */
    val bubbles: Map<Seat, BubbleSpot> = placeBubbles()

    private fun placeBubbles(): Map<Seat, BubbleSpot> {
        val result = LinkedHashMap<Seat, BubbleSpot>()
        val placed = ArrayList<Box>()
        val order = seats.sortedWith(
            compareBy({ it.seat.row }, { listOf("LEFT", "CENTER", "RIGHT").indexOf(it.seat.key.substringAfterLast('_')) })
        )
        for (slot in order) {
            val others = obstacles.filter { it != slot.box }
            val spot = spotFor(slot, tailDown = true)
            val fits = footprint(spot).all { p ->
                p.insideRounded(cabin, CABIN_R) &&
                    others.none { p.overlaps(it, CLEAR) } &&
                    placed.none { p.overlaps(it, CLEAR) }
            }
            if (!fits) error("rows=$rows ${slot.seat.key}: 말풍선 자리가 없음")
            result[slot.seat] = spot
            placed += footprint(spot)
        }
        return result
    }

    private fun spotFor(slot: SeatSlot, tailDown: Boolean): BubbleSpot {
        val tipX = slot.x + HEAD_DX
        val tipY = if (tailDown) slot.y - HEAD_R else slot.y + HEAD_R
        val y = if (tailDown) tipY - BT - BH else tipY + BT
        return BubbleSpot(tipX - BW, y, tailDown)
    }

    companion object {
        const val DEFAULT_ROWS = 2

        /** The person's head circle: centre this far in front of the seat x (drawn at x + 12), radius 12.5. */
        const val HEAD_DX = 12f
        const val HEAD_R = 12.5f

        /** Corner radius of the cabin floor, and the smallest gap kept between a bubble and anything else. */
        const val CABIN_R = 30f
        private const val CLEAR = 1f

        /** Bubble body size, corner radius, tail height and tail width (drawing units). */
        const val BW = 84f
        const val BH = 28f
        const val BR = 10f
        const val BT = 7f
        const val BTW = 12f

        private val cache = HashMap<Int, CarLayout>()

        /** The layout for [rows] seat rows (clamped to 1..3). */
        @Synchronized
        fun of(rows: Int): CarLayout {
            val n = rows.coerceIn(1, 3)
            return cache.getOrPut(n) { CarLayout(n) }
        }

        /**
         * How many seat rows to draw, from the areas the vehicle reports (SeatValue / DoorValue /
         * WindowValue names). Nothing reported -> [DEFAULT_ROWS]. A one-row car is concluded only when the
         * doors or windows are reported and show nothing behind row 1: missing rear seat sensors alone say
         * nothing about whether the car has a rear seat.
         */
        fun rowsFrom(seatKeys: Set<String>, doorKeys: Set<String>, windowKeys: Set<String>): Int {
            fun rowOf(key: String): Int? {
                val parts = key.split('_')
                return if (parts.size >= 2 && parts[0] == "ROW") parts[1].toIntOrNull() else null
            }
            val bodyRows = (doorKeys + windowKeys).mapNotNull(::rowOf)
            val all = seatKeys.mapNotNull(::rowOf) + bodyRows
            val deepest = all.maxOrNull() ?: return DEFAULT_ROWS
            if (deepest >= 2) return min(deepest, 3)
            return if (bodyRows.isNotEmpty()) 1 else DEFAULT_ROWS
        }

        /** The bubble body and its tail, as two boxes. The tail hangs from the right corner of the body. */
        fun footprint(spot: BubbleSpot): List<Box> {
            val body = Box(spot.x, spot.y, spot.x + BW, spot.y + BH)
            val tail = if (spot.tailDown) Box(spot.x + BW - BTW, spot.y + BH, spot.x + BW, spot.y + BH + BT)
            else Box(spot.x + BW - BTW, spot.y - BT, spot.x + BW, spot.y)
            return listOf(body, tail)
        }

        /** The point of the tail. */
        fun tip(spot: BubbleSpot): Pair<Float, Float> =
            (spot.x + BW) to (if (spot.tailDown) spot.y + BH + BT else spot.y - BT)

        private fun buildSeats(rows: Int): List<SeatSlot> {
            val list = ArrayList<SeatSlot>()
            for (r in 1..rows) {
                val x = 290f + 136f * (rows - r)
                val keys = if (r == 3) listOf(152f, 250f, 348f) else listOf(184f, 316f)
                val sides = if (r == 3) listOf("LEFT", "CENTER", "RIGHT") else listOf("LEFT", "RIGHT")
                for (i in keys.indices) {
                    val seat = Seat.fromKey("ROW_${r}_${sides[i]}") ?: continue
                    list += SeatSlot(seat, x, keys[i])
                }
            }
            return list
        }
    }
}
