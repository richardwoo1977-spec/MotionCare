// ver 2.1.1 | 2026-11-12 12:35
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
import app.motioncare.core.NearbyState
import app.motioncare.core.Policy
import app.motioncare.core.RestDisplay
import app.motioncare.core.RestPlan
import app.motioncare.core.RestStop
import app.motioncare.data.LiveState
import app.motioncare.service.CareService

// ===================================================================== 쉬어 가기 (21차 시안 구도, 1452 x 1130 design px)
//
// The three places nearby that the navigation can reach fastest (highway rest areas and drowsy-driver shelters),
// with the road time it gives. A card only picks; the big button below says what it will do and shows the route
// it will make. Guiding to a destination: the place goes on the route as a waypoint. No destination: the navigation
// is asked to guide to the place. A place counts as on the route only once the navigation reports it.

private val Ink = Color(0xFF04201A)
private val QuietCard = Color(0x0FFFFFFF)          // white at 6 %
private val Off = Color(0x52F2F6F7)                // rgba(242,246,247,.32)

/** "평택" -> "평택으로", "고삼호수" -> "고삼호수로" (no final consonant, or ㄹ, takes 로). */
private fun withRo(word: String): String {
    val c = word.lastOrNull() ?: return word
    if (c !in '가'..'힣') return word + "로"
    val jong = (c - '가') % 28
    return word + if (jong == 0 || jong == 8) "로" else "으로"
}

@Composable
fun RestScreen(live: LiveState) {
    val stops = live.nearby
    // with no route the navigation holds no waypoints: nothing is marked as on the route then
    val ours = if (live.naviRouting) live.waypoints else emptyList()
    val destId = live.destRestId
    // the button guides to the place (no route, or the route goes to a place this app set)
    val guide = !live.naviRouting || destId != null
    val route = RestPlan.names(ours, live.naviWaypoints)
    val dest = live.destination
    // nothing on the route and nothing picked: the fastest place counts as picked, so one touch is enough
    val pickedId = live.pickedRest ?: if (ours.isEmpty() && destId == null) stops.firstOrNull()?.id else null
    val picked = stops.firstOrNull { it.id == pickedId } ?: ours.firstOrNull { it.id == pickedId }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.height(u(111)), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(u(28))) {
            Txt("쉬어 갈 곳", 60, Color.White, FontWeight.Medium, maxLines = 1, spacing = -0.03f, modifier = Modifier.alignByBaseline())
            Txt(
                if (live.destinationFromNavi) "목적지 $dest" else "내비 목적지 없음",
                28, Palette.Sub, maxLines = 1, modifier = Modifier.alignByBaseline()
            )
        }
        if (stops.isEmpty()) {
            val (title, sub) = when (live.nearbyState) {
                NearbyState.NO_POSITION -> "내비에서 위치를 받는 중이에요" to "위치를 받으면 가까운 쉼터를 찾아요"
                NearbyState.LOADING -> "가까운 쉼터를 찾는 중이에요" to "쉼터까지 걸리는 시간을 내비에 묻고 있어요"
                else -> "100km 안에 고속도로 쉼터가 없어요" to "안전한 곳에 잠시 세워 쉬어 가세요"
            }
            Column(Modifier.offset(u(0), u(220)), verticalArrangement = Arrangement.spacedBy(u(20))) {
                Txt(title, 56, Color.White, FontWeight.Medium, maxLines = 1)
                Txt(sub, 34, Palette.Sub, maxLines = 1)
            }
            return@Box
        }
        Row(Modifier.offset(u(0), u(150)).fillMaxWidth().height(u(560)), horizontalArrangement = Arrangement.spacedBy(u(24))) {
            stops.forEachIndexed { i, st ->
                val onRoute = live.naviRouting &&
                    (ours.any { it.id == st.id } || live.naviWaypoints?.any { RestPlan.same(it, st.name) } == true)
                RestCard(st, i, onRoute, st.id == destId, st.id == pickedId, live.pickedRest == null, Modifier.weight(1f))
            }
            repeat(3 - stops.size) { Spacer(Modifier.weight(1f)) }
        }
        // a change the navigation did not take: what happened, then what to do
        val note = live.restWork?.takeIf { it.failed }
        if (note != null) Row(
            Modifier.offset(u(0), u(736)).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(u(20))
        ) {
            Txt(note.title, 34, Color.White, FontWeight.Medium, maxLines = 1)
            Txt(note.line, 34, Palette.Sub, maxLines = 1, ellipsis = true, modifier = Modifier.weight(1f, fill = false))
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(u(200)),
            horizontalArrangement = Arrangement.spacedBy(u(16))
        ) {
            val work = live.restWork?.takeIf { !it.failed }
            when {
                work != null -> ActionButton(work.title, work.line, Kind.STATUS, Modifier.weight(1f)) {}
                guide -> GuideButtons(live, stops, picked, destId, Modifier.weight(1f))
                else -> WaypointButtons(picked, ours, route, dest, this)
            }
        }
    }
}

/** No route, or guiding to a place this app set: start, change or end the guidance to a place. */
@Composable
private fun GuideButtons(live: LiveState, stops: List<RestStop>, picked: RestStop?, destId: String?, modifier: Modifier) {
    val destStop = stops.firstOrNull { it.id == destId }
    when {
        picked == null && destId != null -> ActionButton(
            "${withRo(destStop?.name ?: "쉼터")} 가고 있어요",
            "출발 → ${destStop?.label ?: "쉼터"}", Kind.STATUS, modifier
        ) {}
        picked == null -> ActionButton("카드를 눌러 고르세요", "고른 쉼터로 내비가 안내해요", Kind.STATUS, modifier) {}
        picked.id == destId -> ActionButton(
            "안내 끝내기", "${picked.label} 안내를 끝내요", Kind.PRIMARY, modifier
        ) { CareService.run { it.restRemove(picked.id) } }
        destId != null -> ActionButton(
            "${withRo(picked.name)} 바꾸기", "출발 → ${picked.label}", Kind.PRIMARY, modifier
        ) { CareService.run { it.restAdd(picked.id) } }
        else -> ActionButton(
            "${withRo(picked.name)} 안내", "출발 → ${picked.label}", Kind.PRIMARY, modifier
        ) { CareService.run { it.restAdd(picked.id) } }
    }
}

/** Guiding to a destination: the place goes on the route as a waypoint (two at most). */
@Composable
private fun WaypointButtons(picked: RestStop?, ours: List<RestStop>, route: List<String>, dest: String, scope: androidx.compose.foundation.layout.RowScope) {
    with(scope) {
        val pickedOnRoute = picked != null && (ours.any { it.id == picked.id } || route.any { RestPlan.same(it, picked.name) })
        val full = route.size >= RestPlan.MAX
        fun line(names: List<String>) = (listOf("출발") + names + dest).joinToString(" → ")
        when {
            picked == null && ours.isNotEmpty() -> ActionButton(
                "${ours.last().name}에 들렀다 가요", line(route),
                Kind.STATUS, Modifier.weight(1f)
            ) {}
            picked == null -> ActionButton("카드를 눌러 고르세요", line(route), Kind.STATUS, Modifier.weight(1f)) {}
            pickedOnRoute -> ActionButton(
                "${picked.name} 빼기", line(route.filterNot { RestPlan.same(it, picked.name) }), Kind.PRIMARY, Modifier.weight(1f)
            ) { CareService.run { it.restRemove(picked.id) } }
            ours.isEmpty() -> ActionButton(
                "${picked.name} 들렀다 가기", line(route + picked.label),
                if (full) Kind.OFF else Kind.PRIMARY, Modifier.weight(1f)
            ) { CareService.run { it.restAdd(picked.id) } }
            else -> {
                val old = ours.last()
                ActionButton(
                    "${withRo(picked.name)} 바꾸기",
                    if (route.size == ours.size) line(route.map { if (RestPlan.same(it, old.name)) picked.label else it })
                    else "${old.name} 대신 ${picked.name}, 다른 경유지는 그대로",
                    Kind.PRIMARY, Modifier.weight(1f)
                ) { CareService.run { it.restReplace(picked.id) } }
                if (full) ActionButton("경유지가 꽉 찼어요", "내비 경유지는 2개까지", Kind.OFF, Modifier.weight(1f)) {}
                else ActionButton("${picked.name}도 들르기", line(route + picked.label), Kind.SECONDARY, Modifier.weight(1f)) {
                    CareService.run { it.restAdd(picked.id) }
                }
            }
        }
    }
}

/** [auto] = nobody picked a card: the fastest place counts as picked and is called the fastest, not "picked". */
@Composable
private fun RestCard(st: RestStop, index: Int, onRoute: Boolean, isDest: Boolean, picked: Boolean, auto: Boolean, modifier: Modifier) {
    val added = onRoute || isDest
    val shape = RoundedCornerShape(u(36))
    var m = modifier.fillMaxHeight()
    m = when {
        added && picked -> m.background(Palette.Primary, shape).border(u(5), Color.White, shape)
        added -> m.background(Palette.Primary, shape)
        picked -> m.background(QuietCard, shape).border(u(5), Palette.Primary, shape)
        else -> m.background(QuietCard, shape)
    }
    val desc = "${st.label} 약 ${st.etaMin}분 ${"%.0f".format(st.distanceKm)}킬로미터" +
        (if (st.far) ", 멀어요" else "") + (if (isDest) ", 지금 갈 곳" else if (onRoute) ", 지금 들를 곳" else "") +
        (if (picked) ", 고른 곳" else "")
    Column(
        m.clickable { CareService.run { it.pickRest(st.id) } }.semantics { contentDescription = desc }.padding(horizontal = u(40), vertical = u(36))
    ) {
        val name = if (added) Ink else Color.White
        val sub = if (added) Ink.copy(alpha = 0.72f) else Palette.Sub
        when {
            added -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(10))) {
                Flag(Ink)
                Txt(if (isDest) "지금 갈 곳" else "지금 들를 곳", 32, Ink, FontWeight.SemiBold, maxLines = 1)
            }
            picked -> Txt((if (auto && index == 0) "가장 빠른 " else "고른 ") + st.kind.word, 30, Palette.Primary, FontWeight.SemiBold, maxLines = 1)
            else -> Txt((if (index == 0) "가장 빠른 " else "") + st.kind.word, 28, Palette.Sub, maxLines = 1)
        }
        Spacer(Modifier.height(u(22)))
        Txt(st.name, if (st.name.length > 4) 72 else 84, name, FontWeight.Medium, maxLines = 1, spacing = -0.03f, ellipsis = true)
        Spacer(Modifier.height(u(18)))
        Txt("약 ${st.etaMin}분", 48, name, FontWeight.Medium, maxLines = 1)
        Txt(
            // "일산 방향 11km", "하행 12km"; the rest-area file's "양방향" means both sides use it
            "${"%.0f".format(st.distanceKm)}km".let { km ->
                when {
                    st.direction.isBlank() -> km
                    st.direction == "양방향" -> "$km, 양방향 이용"
                    else -> "${st.direction} $km"
                }
            },
            30, sub, maxLines = 1, ellipsis = true, modifier = Modifier.padding(top = u(6))
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(u(10))) {
            listOfNotNull("멀어요".takeIf { st.far }, "화장실".takeIf { st.toilet }, "수유실".takeIf { st.nursing }, "키즈존".takeIf { st.kids }).forEach { f ->
                val farTag = f == "멀어요"
                Box(
                    Modifier.height(u(46))
                        .then(
                            if (farTag) Modifier.border(u(3), if (added) Ink else Color.White, RoundedCornerShape(u(23)))
                            else Modifier.background(if (added) Ink.copy(alpha = 0.14f) else Color(0x14FFFFFF), RoundedCornerShape(u(23)))
                        )
                        .padding(horizontal = u(16)),
                    contentAlignment = Alignment.Center
                ) { Txt(f, 26, if (added) Ink else Color(0xFFDDE6E8), FontWeight.Medium.takeIf { farTag } ?: FontWeight.Normal, maxLines = 1) }
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
