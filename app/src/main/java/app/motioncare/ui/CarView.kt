// ver 1.7.0 | 2026-11-06 17:05
package app.motioncare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import app.motioncare.core.CarLayout
import app.motioncare.core.BubbleSpot
import app.motioncare.core.Seat
import kotlin.math.roundToInt

/**
 * The car seen from above. All numbers below are in the 1040 x 520 drawing space of the design, but only the part that
 * is shown is used: x from the rear edge of the body (62) to the nose (964), y from 0 to 446. That part is scaled to
 * 1452 design px, so the car lines up with the left edge of the first card and the right edge of the button.
 */
private const val VB_LEFT = 62f
private const val VB_SHOWN_W = 902f
private const val VB_SHOWN_H = 446f
private const val DESIGN_CAR_W = 1452f
private const val SCALE = DESIGN_CAR_W / VB_SHOWN_W

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

private const val CAR_SHIFT_Y = -11.55f           // the whole car group sits a little higher than its drawing numbers (as in the design)

/**
 * The speech bubble of a chosen seat: an outline with one pointed corner as its tail, the point touching the head of the
 * person on that seat. Where it goes is decided by [CarLayout] (inside the cabin, covering no other seat, wheel,
 * console or dashboard, overlapping no other bubble).
 */
private fun bubblePath(spot: BubbleSpot): Path {
    val x = spot.x
    val y = spot.y
    val w = CarLayout.BW
    val h = CarLayout.BH
    val r = CarLayout.BR
    val t = CarLayout.BT
    val b = CarLayout.BTW
    // the tail hangs from the right corner of the body; its point touches the head of the person on that seat
    return if (spot.tailDown) {
        svg(
            "M${x + r} $y H${x + w - r} A$r $r 0 0 1 ${x + w} ${y + r} V${y + h + t} L${x + w - b} ${y + h} " +
                "H${x + r} A$r $r 0 0 1 $x ${y + h - r} V${y + r} A$r $r 0 0 1 ${x + r} $y Z"
        )
    } else {
        svg(
            "M${x + r} ${y + h} H${x + w - r} A$r $r 0 0 0 ${x + w} ${y + h - r} V${y - t} L${x + w - b} $y " +
                "H${x + r} A$r $r 0 0 0 $x ${y + r} V${y + h - r} A$r $r 0 0 0 ${x + r} ${y + h} Z"
        )
    }
}

private const val BUBBLE_FONT_VB = 18f

@Composable
private fun vbDp(v: Float): Dp = u(v * SCALE)

/** A horizontal position: the shown part of the drawing starts at [VB_LEFT]. */
@Composable
private fun vbX(v: Float): Dp = u((v - VB_LEFT) * SCALE)

/**
 * @param designated seat keys the person marked as feeling sick
 * @param occupancy seat key -> sitting (true), empty (false) or not known (null / missing)
 * @param rows seat rows of the car (1 = sports car, 2 = most cars, 3 = SUV / van), from the vehicle's own report
 */
@Composable
fun CarView(
    designated: Set<String>,
    occupancy: Map<String, Boolean?>,
    onToggle: (Seat) -> Unit,
    rows: Int = CarLayout.DEFAULT_ROWS
) {
    val layout = remember(rows) { CarLayout.of(rows) }
    val bubblePaths = remember(rows) { layout.bubbles.mapValues { bubblePath(it.value) } }
    val body = remember { svg(BODY) }
    Box(Modifier.size(u(DESIGN_CAR_W), u(VB_SHOWN_H * SCALE))) {
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / VB_SHOWN_W
            withTransform({ scale(k, k, Offset.Zero); translate(-VB_LEFT, 0f) }) {
                withTransform({ translate(0f, CAR_SHIFT_Y) }) {
                    drawCarBody(body, layout)
                    layout.seats.forEach { p ->
                        drawSeat(p.x, p.y, p.seat.key in designated, occupancy[p.seat.key] == true)
                    }
                    drawSteeringWheel(layout.wheel)
                    layout.seats.forEach { p ->
                        if (p.seat.key in designated) {
                            bubblePaths[p.seat]?.let { path ->
                                drawPath(path, Palette.Bg)
                                drawPath(path, Mint, style = Stroke(width = 3f, join = StrokeJoin.Round))
                            }
                        }
                    }
                }
            }
        }

        // row names (a one-row car needs none)
        if (layout.rows >= 2) {
            layout.seats.map { it.seat.row to it.x }.distinct().forEach { (row, x) ->
                VbText("${row}열", x - 2f, 38f - 8f + CAR_SHIFT_Y, 24f, Palette.Sub, FontWeight.Normal)
            }
        }

        // the text of the speech bubbles (the bubble itself is drawn above)
        layout.seats.forEach { p ->
            val spot = layout.bubbles.getValue(p.seat)
            if (p.seat.key in designated) {
                Box(
                    Modifier.offset(vbX(spot.x), vbDp(spot.y + CAR_SHIFT_Y)).size(vbDp(CarLayout.BW), vbDp(CarLayout.BH)),
                    contentAlignment = Alignment.Center
                ) {
                    Txt("멀미 나요", (BUBBLE_FONT_VB * SCALE).roundToInt(), Palette.Primary, FontWeight.Medium, maxLines = 1)
                }
            }
        }

        // seat hit areas (108 x 98 in the drawing; they never overlap the neighbouring seat)
        layout.seats.forEach { p ->
            val on = p.seat.key in designated
            val label = "${p.seat.row}열 ${p.seat.side}" +
                (if (occupancy[p.seat.key] == true) ", 앉음" else "") +
                (if (on) ", 멀미 나요로 선택됨" else "")
            Box(
                Modifier
                    .offset(vbX(p.x - 52f), vbDp(p.y - 49f + CAR_SHIFT_Y))
                    .size(vbDp(108f), vbDp(98f))
                    .semantics { contentDescription = label }
                    .clickable { onToggle(p.seat) }
            )
        }
    }
}

/** A short text centred on ([cx], [cy]) in drawing units; [sizeVb] is the font size in drawing units. */
@Composable
private fun VbText(text: String, cx: Float, cy: Float, sizeVb: Float, color: Color, weight: FontWeight) {
    Box(
        Modifier.offset(vbX(cx - 60f), vbDp(cy - 20f)).width(vbDp(120f)).height(vbDp(40f)),
        contentAlignment = Alignment.Center
    ) {
        Txt(text, (sizeVb * SCALE).roundToInt(), color, weight, maxLines = 1)
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
