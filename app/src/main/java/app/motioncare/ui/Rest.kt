// ver 1.3.0 | 2026-11-11 22:58
package app.motioncare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.motioncare.core.Policy
import app.motioncare.core.RestDisplay
import app.motioncare.core.RestPlan
import app.motioncare.core.RestStop
import app.motioncare.data.DemoNavi
import app.motioncare.data.LiveState
import app.motioncare.service.CareService

// ===================================================================== 쉬어 가기 (21차 시안, 1452 x 1130 design px)
//
// Three rest areas side by side. A card only picks; the big button below says what it will do and shows the route
// it will make. The stop on the route is a filled card with a flag; the picked one has a thick outline.
// A stop counts as on the route only once the navigation reports it. While a change waits for the navigation the
// buttons give way to one status ("평택 넣는 중"); a change that did not go through is written above the buttons.

private val Ink = Color(0xFF04201A)
private val QuietCard = Color(0x0FFFFFFF)          // white at 6 %
private val Faint = Color(0x73F2F6F7)              // rgba(242,246,247,.45)
private val Off = Color(0x52F2F6F7)                // rgba(242,246,247,.32)

/** The three cards: the suggested stop first, then the next two nearest stops with a toilet. */
private fun threeStops(live: LiveState): List<RestStop> {
    val usable = DemoNavi.stops.filter { it.toilet && it.distanceKm in Policy.REST_MIN_KM..Policy.REST_MAX_KM }
    val first = live.suggestion?.takeIf { s -> usable.any { it.id == s.id } } ?: Policy.pickStop(DemoNavi.stops)
    val rest = usable.filter { it.id != first?.id }.sortedBy { it.distanceKm }
    return (listOfNotNull(first) + rest).take(3)
}

/** "평택" -> "평택으로", "고삼호수" -> "고삼호수로" (no final consonant, or ㄹ, takes 로). */
private fun withRo(word: String): String {
    val c = word.lastOrNull() ?: return word
    if (c !in '가'..'힣') return word + "로"
    val jong = (c - '가') % 28
    return word + if (jong == 0 || jong == 8) "로" else "으로"
}

@Composable
fun RestScreen(live: LiveState) {
    val stops = threeStops(live)
    // with no route the navigation holds no waypoints: nothing is marked as on the route then
    val ours = if (live.naviRouting) live.waypoints else emptyList()
    val route = RestPlan.names(ours, live.naviWaypoints)
    val dest = live.destination
    // nothing on the route and nothing picked: the suggested stop counts as picked, so one touch is enough
    val pickedId = live.pickedRest ?: if (ours.isEmpty()) stops.firstOrNull()?.id else null
    val picked = stops.firstOrNull { it.id == pickedId } ?: ours.firstOrNull { it.id == pickedId }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.height(u(111)), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(u(28))) {
            Txt("쉬어 갈 곳", 60, Color.White, FontWeight.Medium, maxLines = 1, spacing = -0.03f, modifier = Modifier.alignByBaseline())
            Txt(
                (if (live.destinationFromNavi) "목적지 $dest" else "내비 목적지 없음") + " · 화장실 있는 앞쪽 휴게소",
                28, Palette.Sub, maxLines = 1, modifier = Modifier.alignByBaseline()
            )
        }
        if (stops.isEmpty()) {
            Txt("앞쪽에 화장실 있는 휴게소가 없어요", 44, Palette.Sub, modifier = Modifier.offset(u(0), u(200)))
            return@Box
        }
        Row(Modifier.offset(u(0), u(150)).fillMaxWidth().height(u(560)), horizontalArrangement = Arrangement.spacedBy(u(24))) {
            stops.forEachIndexed { i, st ->
                val added = live.naviRouting &&
                    (ours.any { it.id == st.id } || live.naviWaypoints?.any { RestPlan.same(it, st.name) } == true)
                RestCard(st, i, added, st.id == pickedId, Modifier.weight(1f))
            }
        }
        val note = live.restWork?.takeIf { it.failed }
        if (note != null) Txt(
            "${note.title} · ${note.line}", 34, Color.White, FontWeight.Medium, maxLines = 1, ellipsis = true,
            modifier = Modifier.offset(u(0), u(736))
        ) else Txt(
            "카드를 눌러 고른 뒤 아래 버튼을 누르세요 · 휴게소 자료 ${DemoNavi.catalogSize}곳(한국도로공사, 기준일 ${DemoNavi.dataAsOf ?: "-"}) · 거리는 직선 기준",
            26, Faint, maxLines = 1, modifier = Modifier.offset(u(0), u(740))
        )
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(u(200)),
            horizontalArrangement = Arrangement.spacedBy(u(16))
        ) {
            val pickedOnRoute = picked != null && (ours.any { it.id == picked.id } || route.any { RestPlan.same(it, picked.name) })
            val full = route.size >= RestPlan.MAX
            fun line(names: List<String>) = (listOf("출발") + names + dest).joinToString(" → ")
            val work = live.restWork?.takeIf { !it.failed }
            when {
                work != null -> ActionButton(work.title, work.line, Kind.STATUS, Modifier.weight(1f)) {}
                // with no destination the navigation answers a waypoint with an error dialog: nothing to press
                !live.naviRouting -> ActionButton(
                    "내비에 목적지를 먼저 넣어 주세요", "목적지가 있어야 휴게소를 경유지로 넣을 수 있어요", Kind.STATUS, Modifier.weight(1f)
                ) {}
                picked == null && ours.isNotEmpty() -> ActionButton(
                    "${ours.last().name}에 들렀다 가요", line(route) + " · 다른 곳을 고르면 바꿀 수 있어요",
                    Kind.STATUS, Modifier.weight(1f)
                ) {}
                picked == null -> ActionButton("카드를 눌러 고르세요", line(route), Kind.STATUS, Modifier.weight(1f)) {}
                pickedOnRoute -> ActionButton(
                    "${picked.name} 빼기", line(route.filterNot { RestPlan.same(it, picked.name) }), Kind.PRIMARY, Modifier.weight(1f)
                ) { CareService.run { it.restRemove(picked.id) } }
                ours.isEmpty() -> ActionButton(
                    "${picked.name} 들렀다 가기", line(route + picked.name),
                    if (full) Kind.OFF else Kind.PRIMARY, Modifier.weight(1f)
                ) { CareService.run { it.restAdd(picked.id) } }
                else -> {
                    val old = ours.last()
                    ActionButton(
                        "${withRo(picked.name)} 바꾸기",
                        if (route.size == ours.size) line(route.map { if (RestPlan.same(it, old.name)) picked.name else it })
                        else "${old.name} 대신 ${picked.name} · 다른 경유지는 그대로",
                        Kind.PRIMARY, Modifier.weight(1f)
                    ) { CareService.run { it.restReplace(picked.id) } }
                    if (full) ActionButton("경유지가 꽉 찼어요", "내비 경유지는 2개까지", Kind.OFF, Modifier.weight(1f)) {}
                    else ActionButton("${picked.name}도 들르기", line(route + picked.name), Kind.SECONDARY, Modifier.weight(1f)) {
                        CareService.run { it.restAdd(picked.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RestCard(st: RestStop, index: Int, added: Boolean, picked: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(u(36))
    var m = modifier.fillMaxHeight()
    m = when {
        added && picked -> m.background(Palette.Primary, shape).border(u(5), Color.White, shape)
        added -> m.background(Palette.Primary, shape)
        picked -> m.background(QuietCard, shape).border(u(5), Palette.Primary, shape)
        else -> m.background(QuietCard, shape)
    }
    val desc = "${st.name} 휴게소 ${RestDisplay.kmText(st.distanceKm)}" + (if (added) ", 지금 들를 곳" else "") + (if (picked) ", 고른 곳" else "")
    Column(
        m.clickable { CareService.run { it.pickRest(st.id) } }.semantics { contentDescription = desc }.padding(horizontal = u(40), vertical = u(36))
    ) {
        val name = if (added) Ink else Color.White
        val sub = if (added) Ink.copy(alpha = 0.72f) else Palette.Sub
        when {
            added -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(10))) {
                Flag(Ink)
                Txt("지금 들를 곳", 32, Ink, FontWeight.SemiBold, maxLines = 1)
            }
            picked -> Txt("고른 곳", 30, Palette.Primary, FontWeight.SemiBold, maxLines = 1)
            else -> Txt(if (index == 0) "추천" else "${index + 1}번째로 가까움", 28, Palette.Sub, maxLines = 1)
        }
        Spacer(Modifier.height(u(22)))
        Txt(st.name, if (st.name.length > 4) 72 else 84, name, FontWeight.Medium, maxLines = 1, spacing = -0.03f, ellipsis = true)
        Spacer(Modifier.height(u(18)))
        Txt(RestDisplay.kmText(st.distanceKm), 48, name, FontWeight.Medium, maxLines = 1)
        Txt("약 ${st.etaMin}분", 30, sub, maxLines = 1, modifier = Modifier.padding(top = u(6)))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(u(10))) {
            listOfNotNull("화장실".takeIf { st.toilet }, "수유실".takeIf { st.nursing }, "키즈존".takeIf { st.kids }).forEach { f ->
                Box(
                    Modifier.height(u(46)).background(if (added) Ink.copy(alpha = 0.14f) else Color(0x14FFFFFF), RoundedCornerShape(u(23)))
                        .padding(horizontal = u(16)),
                    contentAlignment = Alignment.Center
                ) { Txt(f, 26, if (added) Ink else Color(0xFFDDE6E8), maxLines = 1) }
            }
        }
    }
}

@Composable
private fun Flag(color: Color) {
    val path = remember { PathParser().parsePathString("M6 21 V4 M6 4 H18 L15 8 L18 12 H6").toPath() }
    val fill = remember { PathParser().parsePathString("M6 4 H18 L15 8 L18 12 H6 Z").toPath() }
    Canvas(Modifier.size(u(44))) {
        val k = size.width / 24f
        withTransform({ scale(k, k, Offset.Zero) }) {
            drawPath(fill, color.copy(alpha = 0.18f), style = Fill)
            drawPath(path, color, style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private enum class Kind { PRIMARY, SECONDARY, STATUS, OFF }

@Composable
private fun ActionButton(title: String, route: String, kind: Kind, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(u(36))
    var m = modifier.fillMaxHeight()
    val (tc, rc) = when (kind) {
        Kind.PRIMARY -> { m = m.background(Palette.Primary, shape); Ink to Ink }
        Kind.SECONDARY -> { m = m.border(u(4), Palette.Primary, shape); Color.White to Palette.Primary }
        Kind.STATUS -> { m = m.background(Palette.Primary.copy(alpha = 0.16f), shape); Color.White to Palette.Sub }
        Kind.OFF -> { m = m.border(u(4), Color(0x24FFFFFF), shape); Off to Off }
    }
    if (kind == Kind.PRIMARY || kind == Kind.SECONDARY) m = m.clickable(onClick = onClick)
    Column(
        m.semantics { contentDescription = "$title, $route" }.padding(horizontal = u(24)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Txt(title, if (title.length > 9) 64 else 80, tc, FontWeight.Medium, align = TextAlign.Center, maxLines = 1, spacing = -0.02f, ellipsis = true)
        Txt(route, 28, rc, FontWeight.Medium, align = TextAlign.Center, maxLines = 1, ellipsis = true, modifier = Modifier.padding(top = u(8)))
    }
}
