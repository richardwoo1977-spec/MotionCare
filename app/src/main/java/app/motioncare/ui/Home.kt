// ver 1.3.0 | 2026-11-06 17:05
package app.motioncare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.motioncare.core.CareMode
import app.motioncare.core.Policy
import app.motioncare.core.RestDisplay
import app.motioncare.core.RestStop
import app.motioncare.data.DemoNavi
import app.motioncare.data.LiveState
import app.motioncare.service.CareService
import kotlin.math.roundToInt

// ===================================================================== 홈 (confirmed design, 1452 x 1130 design px)

private val NoteColor = Color(0x8FF2F6F7)           // rgba(242,246,247,.56)
private val ActionCard = Color(0x294FD6BC)          // mint at 16 %

/**
 * "누가 힘들어하나요?": the car with the seats, and one row under it:
 * 힘들어할 무렵 | 쉬어 갈 곳 | 멀미 나요 (widths 1 : 1 : 1.5).
 */
@Composable
fun HomeScreen(live: LiveState, onOpenRest: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.height(u(111)), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(u(28))) {
            Txt(
                "누가 힘들어하나요?", 60, Color.White, FontWeight.Medium,
                modifier = Modifier.alignByBaseline(), maxLines = 1, spacing = -0.03f
            )
            Txt(
                "힘들어하는 사람이 앉은 좌석을 눌러 주세요", 28, Palette.Sub, FontWeight.Normal,
                modifier = Modifier.alignByBaseline(), maxLines = 1
            )
        }
        Box(Modifier.offset(u(0), u(150))) {
            CarView(
                designated = live.designated + listOfNotNull(live.reliefSeat),
                occupancy = live.occupancy,
                rows = live.rows,
                onToggle = { seat -> CareService.run { it.toggleDesignate(seat.key) } }
            )
        }
        Row(
            Modifier.offset(y = u(930)).width(u(1452)),
            horizontalArrangement = Arrangement.spacedBy(u(16))
        ) {
            WhenCard(live)
            RestCard(onOpenRest)
            ReliefButton(live)
        }
    }
}

private const val CARD_W = (1452f - 32f) / 3.5f

/** Label on top, value + note at the bottom. [content] is the bottom part. */
@Composable
private fun Card(label: String, action: Boolean, description: String? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    var mod = Modifier
        .width(u(CARD_W))
        .height(u(200))
        .background(if (action) ActionCard else Palette.Card, RoundedCornerShape(u(28)))
    if (description != null) mod = mod.semantics { contentDescription = description }
    if (onClick != null) mod = mod.clickable(onClick = onClick)
    Box(mod) {
        Column(Modifier.fillMaxSize().padding(u(24)), verticalArrangement = Arrangement.SpaceBetween) {
            Txt(label, 28, Palette.Sub, FontWeight.Normal, maxLines = 1)
            content()
        }
        if (action) Chevron(Modifier.align(Alignment.TopEnd).padding(top = u(20), end = u(22)))
    }
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

@Composable
private fun ValueText(value: String, note: String, dim: Boolean = false) {
    Column {
        Txt(value, 46, if (dim) Palette.Sub else Color.White, FontWeight.Medium, maxLines = 1, spacing = -0.02f)
        Txt(note, 23, NoteColor, FontWeight.Normal, maxLines = 1, modifier = Modifier.padding(top = u(6)))
    }
}

/** "힘들어할 무렵": minutes after leaving at which a rest is offered, from the earlier records. */
@Composable
private fun WhenCard(live: LiveState) {
    Card("힘들어할 무렵", action = false) {
        val after = live.restAfterMin
        if (after != null) ValueText("${after}분쯤", "지난번 기록이에요")
        else ValueText("아직 없어요", "기록이 쌓이면 알려드려요", dim = true)
    }
}

/** "쉬어 갈 곳": the stop that would be offered now, name and distance on one line. */
@Composable
private fun RestCard(onOpen: () -> Unit) {
    val stop: RestStop? = Policy.pickStop(DemoNavi.stops)
    val desc = if (stop != null) "쉬어 갈 곳 ${stop.name} ${RestDisplay.kmText(stop.distanceKm)}, 목록 열기" else "쉬어 갈 곳, 목록 열기"
    Card("쉬어 갈 곳", action = true, description = desc, onClick = onOpen) {
        if (stop != null) {
            val km = stop.distanceKm.roundToInt()
            val size = RestDisplay.nameSizePx(stop.name, km)
            Row(horizontalArrangement = Arrangement.spacedBy(u(11)), verticalAlignment = Alignment.Bottom) {
                Txt(
                    stop.name, size, Color.White, FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false), maxLines = 1, spacing = -0.02f, ellipsis = true
                )
                Txt(RestDisplay.kmText(stop.distanceKm), size, Color.White, FontWeight.Medium, maxLines = 1, spacing = -0.02f)
            }
        } else {
            ValueText("정해지면 알려요", "경로를 켜면 보여드려요", dim = true)
        }
    }
}

/** "멀미 나요": always active, also while driving. While relieving it becomes "괜찮아졌어요". */
@Composable
private fun ReliefButton(live: LiveState) {
    val relieving = live.mode == CareMode.RELIEF
    val title = if (relieving) "괜찮아졌어요" else "멀미 나요"
    val sub = if (relieving) "원래 설정으로 되돌려요" else "바람을 최대로 하고 쉴 곳을 찾아요"
    Box(
        Modifier
            .width(u((1452f - 32f) * 1.5f / 3.5f))
            .height(u(200))
            .background(Palette.Primary, RoundedCornerShape(u(36)))
            .clickable {
                if (relieving) CareService.run { it.reliefEnd() } else CareService.run { it.reliefPressed() }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = u(44))) {
            Txt(title, 88, Palette.OnPrimary, FontWeight.Medium, align = TextAlign.Center, maxLines = 1, spacing = -0.025f)
            Txt(sub, 28, Palette.OnPrimary, FontWeight.Medium, align = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(top = u(4)))
        }
    }
}
