// ver 2.2.0 | 2026-11-12 11:55
package app.motioncare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.motioncare.core.CareMode
import app.motioncare.core.Policy
import app.motioncare.core.NearbyState
import app.motioncare.core.RestDisplay
import app.motioncare.core.RestStop
import app.motioncare.data.DemoNavi
import app.motioncare.data.LiveState
import app.motioncare.service.CareService

// ===================================================================== 홈 (19차 시안, 1452 x 1130 design px)
//
//   누가 힘들어하나요?  (spoken guidance takes this place while it is shown)
//   ┌ 힘들어할 무렵 (2) ┐ ┌──────────────┐
//   ├ 쉬어 갈 곳   (4) ┤ │ car, front up│
//   └ 멀미 나요    (4) ┘ └──────────────┘

private val NoteColor = Color(0x8FF2F6F7)           // rgba(242,246,247,.56)
private val ActionCard = Color(0x294FD6BC)          // mint at 16 %
private val QuietCard = Color(0x12FFFFFF)           // white at 7 %

private const val TOP = 150f
private const val COL_W = 620f
private const val COL_H = 980f
private const val GAP = 24f
private const val CAR_X = 652f
private const val CAR_W = 800f

@Composable
fun HomeScreen(live: LiveState, onOpenRest: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        TitleRow(live)
        Column(
            Modifier.offset(u(0), u(TOP)).size(u(COL_W), u(COL_H)),
            verticalArrangement = Arrangement.spacedBy(u(GAP))
        ) {
            val unit = (COL_H - 2 * GAP) / 10f
            WhenCard(live, unit * 2)
            RestCard(live, unit * 4, onOpenRest)
            ReliefButton(live, unit * 4)
        }
        Box(Modifier.offset(u(CAR_X), u(TOP))) {
            CarView(
                marked = live.reliefSeats,
                picked = live.pickedSeats,
                whole = live.reliefWhole,
                onTap = if (live.mode == CareMode.RELIEF) null else { seat -> CareService.run { it.tapSeat(seat.key) } },
                occupancy = live.occupancy,
                rows = live.rows,
                boxW = CAR_W,
                boxH = COL_H
            )
        }
    }
}

/** The title, or the spoken guidance in its place while it is shown (the only guidance when TTS is not ready). */
@Composable
private fun TitleRow(live: LiveState) {
    Row(Modifier.height(u(111)), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(u(28))) {
        if (live.caption.isNotBlank()) {
            Txt(
                live.caption, 60, Palette.Primary, FontWeight.Medium, maxLines = 1, spacing = -0.03f,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        } else {
            Txt(
                "누가 힘들어하나요?", 60, Color.White, FontWeight.Medium,
                modifier = Modifier.alignByBaseline(), maxLines = 1, spacing = -0.03f
            )
            Txt(
                "말로 알려 주세요. 예: 뒷자리 모두 멀미 나요", 28, Palette.Sub, FontWeight.Normal,
                modifier = Modifier.alignByBaseline(), maxLines = 1
            )
        }
    }
}

/** "힘들어할 무렵": minutes after leaving at which a rest is offered, from the earlier records. */
@Composable
private fun WhenCard(live: LiveState, height: Float) {
    Row(
        Modifier.fillMaxWidth().height(u(height)).background(QuietCard, RoundedCornerShape(u(28))).padding(horizontal = u(28)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Txt("힘들어할 무렵", 28, Palette.Sub, maxLines = 1)
        val after = live.restAfterMin
        Column(horizontalAlignment = Alignment.End) {
            if (after != null) {
                Txt("${after}분쯤", 48, Color.White, FontWeight.Medium, maxLines = 1, spacing = -0.02f)
                Txt("지난번 기록이에요", 22, NoteColor, maxLines = 1)
            } else {
                Txt("아직 없어요", 40, Palette.Sub, FontWeight.Medium, maxLines = 1)
                Txt("기록이 쌓이면 알려드려요", 22, NoteColor, maxLines = 1)
            }
        }
    }
}

/**
 * "쉬어 갈 곳": one touch puts the stop on the route as a waypoint (the navigation then comes to the front);
 * "다른 곳" opens the list. Once added, the card says so and opens the list.
 */
@Composable
private fun RestCard(live: LiveState, height: Float, onOpen: () -> Unit) {
    val stop: RestStop? = live.suggestion?.takeIf { s -> live.nearby.any { it.id == s.id } } ?: live.nearby.firstOrNull()
    val added = stop != null && ((live.naviRouting && live.waypoints.any { it.id == stop.id }) || live.destRestId == stop.id)
    if (stop == null) {
        Box(
            Modifier.fillMaxWidth().height(u(height)).background(QuietCard, RoundedCornerShape(u(28))).clickable(onClick = onOpen)
                .semantics { contentDescription = "쉬어 갈 곳, 목록 열기" }
                .padding(u(32))
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Txt("쉬어 갈 곳", 30, Palette.Sub, maxLines = 1)
                Txt(
                    when (live.nearbyState) {
                        NearbyState.NONE -> "근처에 없어요"
                        else -> "찾는 중이에요"
                    },
                    56, Palette.Sub, FontWeight.Medium, maxLines = 1
                )
                Txt(
                    when (live.nearbyState) {
                        NearbyState.NONE -> "안전한 곳에 잠시 세워 쉬어 가세요"
                        NearbyState.NO_POSITION -> "내비에서 위치를 받는 중"
                        else -> "쉼터까지 걸리는 시간을 묻는 중"
                    },
                    28, NoteColor, maxLines = 1
                )
            }
        }
        return
    }
    val headline = "${stop.name} 약 ${stop.etaMin}분"
    if (added) {
        Box(
            Modifier.fillMaxWidth().height(u(height)).background(ActionCard, RoundedCornerShape(u(28))).clickable(onClick = onOpen)
                .semantics { contentDescription = "$headline, 경유지에 넣었어요. 목록 열기" }
                .padding(u(32))
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Txt(if (live.destRestId == stop.id) "안내 중이에요" else "경유지에 넣었어요", 30, Palette.Primary, FontWeight.Medium, maxLines = 1)
                Txt(headline, headlineSize(headline, COL_W - 64f), Color.White, FontWeight.Medium, maxLines = 1, spacing = -0.02f)
                Txt(
                    if (live.destRestId == stop.id) "내비가 ${stop.label}로 안내해요" else "내비가 안내해요 · 경유지 ${live.waypoints.size}/2",
                    28, Palette.Sub, maxLines = 1
                )
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().height(u(height)), horizontalArrangement = Arrangement.spacedBy(u(12))) {
        Box(
            Modifier.weight(1f).fillMaxHeight().background(ActionCard, RoundedCornerShape(u(28)))
                .clickable { CareService.addWaypoint(stop.id) }
                .semantics { contentDescription = if (live.naviRouting && live.destRestId == null) "$headline 을 경유지로 넣기" else "$headline 로 안내하기" }
                .padding(u(32))
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Txt("쉬어 갈 곳 · ${stop.kind.word}" + if (stop.far) " · 멀어요" else "", 30, Palette.Sub, maxLines = 1)
                Txt(headline, headlineSize(headline, COL_W - 104f - 12f - 64f), Color.White, FontWeight.Medium, maxLines = 1, spacing = -0.02f)
                Txt(
                    if (live.naviRouting && live.destRestId == null) "누르면 경유지로 넣어요" else "누르면 내비가 안내해요",
                    28, Palette.Primary, FontWeight.Medium, maxLines = 1
                )
            }
        }
        Column(
            Modifier.width(u(104)).fillMaxHeight().background(QuietCard, RoundedCornerShape(u(28)))
                .clickable(onClick = onOpen)
                .semantics { contentDescription = "다른 쉼터 보기" },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(u(8), Alignment.CenterVertically)
        ) {
            Chevron(Modifier)
            Txt("다른 곳", 22, Palette.Sub, maxLines = 1)
        }
    }
}

/**
 * The biggest size from 72 down that fits [text] on one line in [widthPx] design px.
 * Widths per character at 1 px: Hangul 1.0, digits and Latin 0.6, space 0.3 (close to Asta Sans, a little generous).
 */
private fun headlineSize(text: String, widthPx: Float): Int {
    val em = text.sumOf { c ->
        when {
            c == ' ' -> 0.3
            c in '가'..'힣' -> 1.0
            else -> 0.6
        }
    }
    val fit = (widthPx / em * 0.98).toInt()
    return fit.coerceIn(36, 72)
}

@Composable
private fun Chevron(modifier: Modifier) {
    val path = remember { PathParser().parsePathString("M9 4 L17 12 L9 20").toPath() }
    Canvas(modifier.size(u(40))) {
        val k = size.width / 24f
        withTransform({ scale(k, k, Offset.Zero) }) {
            drawPath(path, Palette.Primary, style = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * "멀미 나요": on only when seats are picked (it never guesses the seats); also while driving.
 * While relieving it becomes "괜찮아졌어요" (also when the relief was started by voice).
 */
@Composable
private fun ReliefButton(live: LiveState, height: Float) {
    val relieving = live.mode == CareMode.RELIEF
    val enabled = relieving || live.canRelieve
    val title = if (relieving) "괜찮아졌어요" else "멀미 나요"
    val sub = when {
        relieving -> "원래 설정으로 되돌려요"
        enabled -> "바람을 최대로 하고 쉴 곳을 찾아요"
        else -> "자리를 말하거나 눌러서 골라 주세요"
    }
    val ink = if (enabled) Palette.OnPrimary else Palette.Gray
    var mod = Modifier
        .fillMaxWidth()
        .height(u(height))
        .background(if (enabled) Palette.Primary else Palette.Seg, RoundedCornerShape(u(36)))
    mod = if (enabled) {
        mod.clickable { if (relieving) CareService.run { it.reliefEnd() } else CareService.run { it.reliefPressed() } }
    } else {
        mod.semantics { contentDescription = "멀미 나요, 자리를 먼저 골라 주세요" }
    }
    Box(mod, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = u(32))) {
            Txt(title, if (relieving) 88 else 96, ink, FontWeight.Medium, align = TextAlign.Center, maxLines = 1, spacing = -0.025f)
            Txt(sub, 28, ink, FontWeight.Medium, align = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(top = u(8)))
        }
    }
}
