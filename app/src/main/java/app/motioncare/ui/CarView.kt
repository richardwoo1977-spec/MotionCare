// ver 2.1.0 | 2026-11-11 19:10
package app.motioncare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.motioncare.core.CarLayout
import app.motioncare.core.Seat
import app.motioncare.core.SeatKeys
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The car seen from above, front up. Seats are chosen mostly by voice; a tap picks a seat (not while relieving).
 * The drawing is the confirmed home car (1040 x 520 drawing space, front to the right), turned 90 degrees
 * counter-clockwise and cut just in front of the windshield (no bonnet), scaled to fill the box it gets.
 *
 * Drawing (x, y) -> screen: x = (y - SHOWN_Y0) * k, y = (cut - x) * k. The driver side (small y) is on the left.
 */
private const val SHOWN_Y0 = 0f
private const val SHOWN_Y_SPAN = 500f
private const val SHOWN_REAR_X = 40f
/** The front edge of the shown part for a three-row car; it moves back with [CarLayout.frontShift]. */
private const val CUT_THREE_ROWS = 840f

private val Mint = Color(0xFF4FD6BC)
private val MintLight = Color(0xFF7BE6D0)
private val MintEdge = Color(0xFFCFF8EE)
private val Teal = Color(0xFF0A7A68)
private val GlassA = Color(0xFF2C4752)
private val GlassB = Color(0xFF101F26)
private val White = Color(0xFFFFFFFF)

private fun svg(d: String): Path = PathParser().parsePathString(d).toPath()

private val BODY = "M62 250 L62 165 C62 112 94 76 152 70 L772 70 C866 70 934 98 952 162 C960 190 964 220 964 250 " +
    "C964 280 960 310 952 338 C934 402 866 430 772 430 L152 430 C94 424 62 388 62 335 Z"

/** Speech bubble size in drawing units (scaled with the car). */
private const val BUBBLE_W = 128f
private const val BUBBLE_H = 40f
private const val BUBBLE_R = 10f
private const val BUBBLE_TAIL = 12f
private const val BUBBLE_TAIL_HALF = 9f
/** Seats are drawn this much bigger than in the confirmed drawing (around their own centre). */
private const val SEAT_SCALE = 1.25f
/** The head of a sitting person, in front of the seat centre (12 in the drawing, scaled with the seat). */
private const val HEAD_AHEAD = 12f * SEAT_SCALE
/** From the top of the bubble down to the centre of the head under it (tail tip 21 above the scaled head). */
private const val BUBBLE_ABOVE_HEAD = 52f + 21f + 12.5f * SEAT_SCALE
/** Tap area of one seat in drawing units: across the car (narrower than the 98 between 3rd-row seats) and along it. */
private const val HIT_ACROSS = 96f
private const val HIT_ALONG = 112f
private const val BUBBLE_FONT = 20f
private const val ROW_LABEL_FONT = 24f

/**
 * @param marked seat keys marked "멀미 나요" (while relieving, and still after the relief ran out by itself)
 * @param picked seat keys tapped (lit, no bubble yet)
 * @param whole the relief runs for the whole cabin (no seat known)
 * @param onTap a seat was tapped; null = taps are off (while relieving)
 * @param occupancy seat key -> sitting (true), empty (false) or not known (null / missing)
 * @param rows seat rows of the car (1, 2 or 3), from the vehicle's own report
 * @param boxW width of the box the car fills, in design px
 * @param boxH height of the box the car fills, in design px
 */
@Composable
fun CarView(
    marked: Set<String>,
    picked: Set<String>,
    whole: Boolean,
    onTap: ((Seat) -> Unit)?,
    occupancy: Map<String, Boolean?>,
    rows: Int,
    boxW: Float,
    boxH: Float
) {
    val layout = remember(rows) { CarLayout.of(rows) }
    val body = remember { svg(BODY) }
    val cut = CUT_THREE_ROWS - layout.frontShift
    val k = min(boxW / SHOWN_Y_SPAN, boxH / (cut - SHOWN_REAR_X))
    val w = SHOWN_Y_SPAN * k
    val h = (cut - SHOWN_REAR_X) * k
    fun sx(y: Float) = (y - SHOWN_Y0) * k
    fun sy(x: Float) = (cut - x) * k

    // a bubble for every marked seat: it sits in the gap in front of its own seat, so rows never cover each other
    val bubbleSeats = layout.seats.filter { p -> p.seat.key in marked }
    val desc = when {
        whole -> "차 전체에 멀미 대응 중"
        marked.isEmpty() -> "위에서 본 차, 멀미 나는 자리 없음"
        else -> "멀미 나요: " + SeatKeys.label(marked.joinToString("+"))
    }

    Box(Modifier.size(u(boxW), u(boxH)).semantics { contentDescription = desc }) {
        Box(Modifier.align(Alignment.Center).size(u(w), u(h))) {
            Canvas(Modifier.fillMaxSize()) {
                val px = size.width / w          // pixels per design px
                val s = k * px
                withTransform({
                    scale(s, s, Offset.Zero)
                    translate(-SHOWN_Y0, cut)
                    rotate(-90f, Offset.Zero)
                }) {
                    clipRect(left = 0f, top = -40f, right = cut, bottom = 540f) {
                        drawCarBody(body, layout)
                        layout.seats.forEach { p ->
                            withTransform({ scale(SEAT_SCALE, SEAT_SCALE, Offset(p.x, p.y)) }) {
                                drawSeat(p.x, p.y, p.seat.key in marked || p.seat.key in picked, occupancy[p.seat.key] == true)
                            }
                        }
                        drawSteeringWheel(layout.wheel)
                    }
                }
                // bubbles are drawn upright, above the head of the person on the seat
                bubbleSeats.forEach { p ->
                    val hx = sx(p.y) * px
                    val top = (sy(p.x + HEAD_AHEAD) - BUBBLE_ABOVE_HEAD * k) * px
                    drawPath(bubblePath(hx, top, k * px, w * px), Palette.Bg)
                    drawPath(bubblePath(hx, top, k * px, w * px), Mint, style = Stroke(width = 4f * px, join = StrokeJoin.Round))
                }
            }

            // row names, left of the car (a one-row car needs none)
            if (layout.rows >= 2) {
                layout.seats.map { it.seat.row to it.x }.distinctBy { it.first }.forEach { (row, x) ->
                    Box(
                        Modifier.offset(u(0), u(sy(x) - 20f * k)).size(u(56f * k), u(40f * k)),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Txt("${row}열", (ROW_LABEL_FONT * k).roundToInt(), Palette.Sub, FontWeight.Normal, maxLines = 1)
                    }
                }
            }

            // seat tap areas (not the driver seat; none while relieving)
            if (onTap != null) {
                layout.seats.filter { it.seat != Seat.ROW_1_LEFT }.forEach { p ->
                    val on = p.seat.key in picked || p.seat.key in marked
                    val label = SeatKeys.label(p.seat.key) + (if (on) ", 골라짐" else "") + ", 누르면 " + (if (on) "빼기" else "고르기")
                    Box(
                        Modifier
                            .offset(u(sx(p.y) - HIT_ACROSS * k / 2f), u(sy(p.x) - HIT_ALONG * k / 2f))
                            .size(u(HIT_ACROSS * k), u(HIT_ALONG * k))
                            .semantics { contentDescription = label }
                            .clickable { onTap(p.seat) }
                    )
                }
            }

            // bubble text
            bubbleSeats.forEach { p ->
                val left = bubbleLeft(sx(p.y), k, w)
                val top = sy(p.x + HEAD_AHEAD) - BUBBLE_ABOVE_HEAD * k
                Box(
                    Modifier.offset(u(left), u(top)).size(u(BUBBLE_W * k), u(BUBBLE_H * k)),
                    contentAlignment = Alignment.Center
                ) {
                    Txt("멀미 나요", (BUBBLE_FONT * k).roundToInt(), Palette.Primary, FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

/** Left edge of a bubble centred on [cx], kept inside the car box. All in design px. */
private fun bubbleLeft(cx: Float, k: Float, boxW: Float): Float =
    (cx - BUBBLE_W * k / 2f).coerceIn(0f, boxW - BUBBLE_W * k)

/** The bubble outline in pixels: a rounded box with its tail pointing down at ([hx], top + height + tail). */
private fun bubblePath(hx: Float, top: Float, k: Float, boxW: Float): Path {
    val w = BUBBLE_W * k
    val h = BUBBLE_H * k
    val r = BUBBLE_R * k
    val t = BUBBLE_TAIL * k
    val b = BUBBLE_TAIL_HALF * k
    val x = (hx - w / 2f).coerceIn(0f, boxW - w)
    val y = top
    return Path().apply {
        moveTo(x + r, y)
        lineTo(x + w - r, y)
        quadraticBezierTo(x + w, y, x + w, y + r)
        lineTo(x + w, y + h - r)
        quadraticBezierTo(x + w, y + h, x + w - r, y + h)
        lineTo(hx + b, y + h)
        lineTo(hx, y + h + t)
        lineTo(hx - b, y + h)
        lineTo(x + r, y + h)
        quadraticBezierTo(x, y + h, x, y + h - r)
        lineTo(x, y + r)
        quadraticBezierTo(x, y, x + r, y)
        close()
    }
}

private fun DrawScope.drawCarBody(body: Path, layout: CarLayout) {
    val s = layout.frontShift                  // how far the front of the cabin has moved back (0 for a three-row car)
    // soft light on the floor
    drawCircle(
        brush = Brush.radialGradient(listOf(Mint.copy(alpha = 0.09f), Color.Transparent), center = Offset(513f, 262f), radius = 600f),
        radius = 600f, center = Offset(513f, 262f)
    )
    // shadow (no blur: two layers)
    withTransform({ translate(0f, 22f) }) {
        drawPath(svg(BODY), Color.Black.copy(alpha = 0.30f), style = Stroke(width = 26f, join = StrokeJoin.Round))
        drawPath(svg(BODY), Color.Black.copy(alpha = 0.35f))
    }
    for (wx in listOf(170f, 770f)) {
        drawRoundRect(Color(0xFF05090B), Offset(wx, 50f), Size(124f, 54f), CornerRadius(22f))
        drawRoundRect(Color(0xFF05090B), Offset(wx, 396f), Size(124f, 54f), CornerRadius(22f))
    }
    drawPath(
        body,
        Brush.verticalGradient(0f to Color(0xFF4A5A61), 0.5f to Color(0xFF2B383D), 1f to Color(0xFF1A2429), startY = 70f, endY = 430f)
    )
    drawPath(body, Color.White.copy(alpha = 0.20f), style = Stroke(width = 2f, join = StrokeJoin.Round))
    drawPath(
        svg("M150 80 L770 80 C850 80 910 102 936 160"), Color.White.copy(alpha = 0.20f),
        style = Stroke(width = 3f, cap = StrokeCap.Round)
    )
    for (sgn in listOf(1f, -1f)) {
        val y0 = 250f - sgn * 74f
        drawPath(
            svg("M810 $y0 C868 $y0 912 ${250f - sgn * 58f} 944 ${250f - sgn * 34f}"), Color.White.copy(alpha = 0.13f),
            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
        )
    }
    // door seams between neighbouring rows, one handle per door (rows 1 and 2)
    val seams = (1 until layout.rows).map { r -> 290f + 136f * (layout.rows - (r + 1)) + 82f + (if (r == 1) 4f else 0f) }
    val handles = (1..minOf(layout.rows, 2)).map { r -> 290f + 136f * (layout.rows - r) + (if (r == 1) -2f else -6f) }
    for (dx in seams) {
        drawLine(Color.White.copy(alpha = 0.13f), Offset(dx, 74f), Offset(dx, 98f), strokeWidth = 2.5f, cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.13f), Offset(dx, 402f), Offset(dx, 426f), strokeWidth = 2.5f, cap = StrokeCap.Round)
    }
    for (hx in handles) {
        drawRoundRect(Color.White.copy(alpha = 0.17f), Offset(hx, 86f), Size(34f, 8f), CornerRadius(4f))
        drawRoundRect(Color.White.copy(alpha = 0.17f), Offset(hx, 406f), Size(34f, 8f), CornerRadius(4f))
    }
    val glass = Brush.linearGradient(listOf(GlassA, GlassB), start = Offset(148f, 128f), end = Offset(206f, 372f))
    val rear = Path().apply { moveTo(206f, 128f); lineTo(148f, 154f); lineTo(148f, 346f); lineTo(206f, 372f); close() }
    val front = Path().apply { moveTo(722f - s, 120f); lineTo(798f - s, 152f); lineTo(798f - s, 348f); lineTo(722f - s, 380f); close() }
    val frontGlass = Brush.linearGradient(listOf(GlassA, GlassB), start = Offset(722f - s, 120f), end = Offset(798f - s, 380f))
    drawPath(rear, glass)
    drawPath(rear, Color.White.copy(alpha = 0.22f), style = Stroke(width = 2f, join = StrokeJoin.Round))
    drawPath(front, frontGlass)
    drawPath(front, Color.White.copy(alpha = 0.22f), style = Stroke(width = 2f, join = StrokeJoin.Round))
    // floor of the cabin
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF15252B), Color(0xFF0C171B)), startY = 98f, endY = 402f),
        Offset(206f, 98f), Size(516f - s, 304f), CornerRadius(30f)
    )
    drawRoundRect(Color.White.copy(alpha = 0.20f), Offset(206f, 98f), Size(516f - s, 304f), CornerRadius(30f), style = Stroke(width = 2f))
    drawRoundRect(Color(0xFF2F3F46), Offset(686f - s, 126f), Size(36f, 248f), CornerRadius(18f))
    drawRoundRect(Color.White.copy(alpha = 0.13f), Offset(686f - s, 126f), Size(36f, 248f), CornerRadius(18f), style = Stroke(width = 1.5f))
    drawRoundRect(Color(0xFF1E2D33), Offset(506f - s, 230f), Size(132f, 36f), CornerRadius(16f))
    drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(506f - s, 230f), Size(132f, 36f), CornerRadius(16f), style = Stroke(width = 1.5f))
    // lights, rear edge, mirrors
    for ((yy, rot) in listOf(130f to 58f, 370f to -58f)) {
        rotate(rot, Offset(932f, yy)) {
            drawRoundRect(Color(0xFFF4FBFF), Offset(900f, yy - 5f), Size(64f, 10f), CornerRadius(5f))
        }
    }
    drawLine(Color.White.copy(alpha = 0.55f), Offset(70f, 126f), Offset(70f, 374f), strokeWidth = 8f, cap = StrokeCap.Round)
    for (my in listOf(46f, 426f)) {
        drawRoundRect(Color(0xFF2B3A41), Offset(738f - s, my), Size(56f, 28f), CornerRadius(13f))
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(738f - s, my), Size(56f, 28f), CornerRadius(13f), style = Stroke(width = 2f))
    }
}

private fun DrawScope.drawSteeringWheel(wheel: app.motioncare.core.Box) {
    val c = Color(0xFFB8C9CE)
    val cx = (wheel.l + wheel.r) / 2f
    val cy = (wheel.t + wheel.b) / 2f
    // seen from above the wheel is a straight bar across the car, with the column running forward from its middle
    drawLine(c, Offset(cx, cy - 27f), Offset(cx, cy + 27f), strokeWidth = 7f, cap = StrokeCap.Round)
    drawLine(c, Offset(cx, cy), Offset(cx + 22f, cy), strokeWidth = 4f, cap = StrokeCap.Round)
}

/**
 * One seat, front to the right. [selected] = pure highlight (mint fill and glow, no ring).
 * A sitting person ([occupied], read from the vehicle) is a glyph on the seat cushion, white with a thin dark outline
 * when the seat is selected. Empty and unknown seats are drawn the same way, only without the person.
 */
private fun DrawScope.drawSeat(xc: Float, yc: Float, selected: Boolean, occupied: Boolean) {
    val fill: Brush = if (selected) {
        Brush.verticalGradient(listOf(MintLight, Mint), startY = yc - 33f, endY = yc + 33f)
    } else {
        Brush.verticalGradient(listOf(Color(0xFF52656C), Color(0xFF3A4A51)), startY = yc - 33f, endY = yc + 33f)
    }
    val edge = if (selected) MintEdge else Color.White.copy(alpha = 0.36f)
    if (selected) {
        for ((grow, a) in listOf(12f to 0.07f, 6f to 0.14f, 0f to 0.26f)) {
            drawRoundRect(
                Mint.copy(alpha = a),
                Offset(xc - 40f - grow, yc - 38f - grow), Size(84f + 2 * grow, 78f + 2 * grow), CornerRadius(26f + grow)
            )
        }
    }
    drawRoundRect(if (selected) MintLight else Color(0xFF5A6D74), Offset(xc - 41f, yc - 14f), Size(9f, 28f), CornerRadius(4.5f))
    drawRoundRect(edge, Offset(xc - 41f, yc - 14f), Size(9f, 28f), CornerRadius(4.5f), style = Stroke(width = 1.5f))
    drawRoundRect(fill, Offset(xc - 18f, yc - 28f), Size(54f, 56f), CornerRadius(16f))
    drawRoundRect(edge, Offset(xc - 18f, yc - 28f), Size(54f, 56f), CornerRadius(16f), style = Stroke(width = 2f))
    drawRoundRect(fill, Offset(xc - 36f, yc - 33f), Size(22f, 66f), CornerRadius(11f))
    drawRoundRect(edge, Offset(xc - 36f, yc - 33f), Size(22f, 66f), CornerRadius(11f), style = Stroke(width = 2f))
    if (occupied) {
        val shoulder = if (selected) White else Color(0xFFB5C6CB)
        val head = if (selected) White else Color(0xFFE6EFF1)
        // the person sits on the cushion: shoulders just in front of the backrest, head in the middle of the cushion
        drawOval(shoulder, Offset(xc + 2f - 11f, yc - 25f), Size(22f, 50f))
        drawCircle(head, radius = 12.5f, center = Offset(xc + 12f, yc))
        if (selected) {
            drawOval(Teal, Offset(xc + 2f - 11f, yc - 25f), Size(22f, 50f), style = Stroke(width = 1.5f))
            drawCircle(Teal, radius = 12.5f, center = Offset(xc + 12f, yc), style = Stroke(width = 1.5f))
        }
    }
}
