// ver 1.0.0 | 2026-11-05 22:50
package app.motioncare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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

/*
 * Title and the three introduction screens (design 16차). Drawn on the same 1706 x 1210 canvas as the home screen.
 * Which of them is shown, and when, is decided by core/StartFlow.kt (parked car only).
 */

/**
 * False until Gleo accepts the voice functions. While false, no screen promises that speaking works
 * (the introduction only talks about the button and the bottom bar). Flip to true once Gleo approves.
 */
const val VOICE_CALL_READY = false

private const val ICON_WIND = "M3 9 H13 A2.5 2.5 0 1 0 10.5 6.5 M3 13 H18 A2.5 2.5 0 1 1 15.5 15.5 M3 17 H8"
private const val ICON_CLOCK = "M12 3 A9 9 0 1 0 12 21 A9 9 0 1 0 12 3 Z M12 7 V12 L15.5 14"
private const val ICON_BUBBLE = "M4 5 H20 V15 H12 L8 19 V15 H4 Z"
private const val ICON_GAUGE = "M4 17 A8 8 0 1 1 20 17 M12 17 L16 11"
private const val ICON_BELL = "M6 17 V11 A6 6 0 0 1 18 11 V17 L20 19 H4 Z M10 21 H14"
private const val ICON_MIC =
    "M12 3 A3 3 0 0 1 15 6 V12 A3 3 0 0 1 9 12 V6 A3 3 0 0 1 12 3 Z M6 11 A6 6 0 0 0 18 11 M12 17 V21"
private const val ICON_HOME = "M3 11 L12 3 L21 11 V21 H15 V14 H9 V21 H3 Z"

@Composable
private fun LineIcon(icon: String, sizeDp: Int, color: Color, stroke: Float) {
    val path = remember(icon) { PathParser().parsePathString(icon).toPath() }
    Canvas(Modifier.size(u(sizeDp))) {
        val k = size.width / 24f
        withTransform({ scale(k, k, Offset.Zero) }) {
            drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Title: concentric rings, the app icon, the name and one line. No motion. */
@Composable
fun TitleScreen() {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / DESIGN_W
            for (i in 0..6) {
                drawCircle(
                    color = Palette.Primary.copy(alpha = 0.22f - 0.03f * i),
                    radius = (170f + 90f * i) * k,
                    center = Offset(853f * k, 470f * k),
                    style = Stroke(width = 2f * k)
                )
            }
        }
        Box(Modifier.offset(u(733), u(350))) { AppIcon(240) }
        Txt(
            "차멀미 케어", 116, Color.White, FontWeight.Medium,
            Modifier.offset(u(0), u(640)).width(u(DESIGN_W)), align = TextAlign.Center, spacing = -0.03f
        )
        Txt(
            "같이 탄 사람의 멀미, 차가 먼저 살펴요", 38, Palette.Sub,
            modifier = Modifier.offset(u(0), u(810)).width(u(DESIGN_W)), align = TextAlign.Center
        )
    }
}

@Composable
private fun InfoRow(icon: String, title: String, sub: String) {
    Row(
        Modifier.fillMaxWidth().height(u(180)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u(30))
    ) {
        Box(
            Modifier.size(u(100)).background(Palette.Primary.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) { LineIcon(icon, 50, Palette.Primary, 1.8f) }
        Column {
            Txt(title, 40, Palette.Ink, FontWeight.Medium, spacing = -0.02f)
            Spacer(Modifier.height(u(8)))
            Txt(sub, 28, Palette.Sub)
        }
    }
}

@Composable
private fun StepDots(step: Int) {
    Row(
        Modifier
            .offset(u(120), u(104))
            .semantics { contentDescription = "3단계 중 ${step}단계" },
        horizontalArrangement = Arrangement.spacedBy(u(10)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 1..3) {
            Box(
                Modifier
                    .width(u(if (i == step) 56 else 14))
                    .height(u(14))
                    .background(if (i == step) Palette.Primary else Color.White.copy(alpha = 0.22f), RoundedCornerShape(u(7)))
            )
        }
    }
}

/** One introduction screen: step dots, skip, headline and text on the left, a card on the right, buttons at the bottom. */
@Composable
private fun IntroScreen(
    step: Int,
    headline: String,
    body: String,
    cta: String,
    onNext: () -> Unit,
    onBack: (() -> Unit)?,
    onSkip: () -> Unit,
    panel: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        StepDots(step)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(u(-120), u(68))
                .height(u(88))
                .clickable(onClick = onSkip)
                .padding(horizontal = u(28)),
            contentAlignment = Alignment.Center
        ) { Txt("건너뛰기", 32, Palette.Sub) }

        Column(Modifier.offset(u(120), u(250)).width(u(700))) {
            Txt(headline, 80, Color.White, FontWeight.Medium, spacing = -0.03f)
            Spacer(Modifier.height(u(44)))
            Txt(body, 32, Palette.Sub)
        }

        Column(
            Modifier
                .offset(u(880), u(190))
                .size(u(706), u(700))
                .background(Palette.Card, RoundedCornerShape(u(44)))
                .padding(u(48)),
            verticalArrangement = Arrangement.Center,
            content = panel
        )

        if (onBack != null) {
            Box(
                Modifier
                    .offset(u(120), u(940))
                    .size(u(300), u(150))
                    .border(u(2), Palette.Line, RoundedCornerShape(u(40)))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) { Txt("이전", 36, Palette.Sub) }
        }
        Box(
            Modifier
                .offset(u(880), u(940))
                .size(u(706), u(150))
                .background(Palette.Primary, RoundedCornerShape(u(40)))
                .clickable(onClick = onNext),
            contentAlignment = Alignment.Center
        ) { Txt(cta, 56, Palette.OnPrimary, FontWeight.Medium, spacing = -0.02f) }
    }
}

@Composable
fun IntroOne(onNext: () -> Unit, onSkip: () -> Unit) {
    IntroScreen(
        step = 1,
        headline = "시동을 걸면\n알아서 작동해요",
        body = "앱을 열지 않아도 괜찮아요.\n차가 알려 주는 정보로\n앉은 자리를 살펴요.",
        cta = "다음",
        onNext = onNext, onBack = null, onSkip = onSkip
    ) {
        InfoRow(ICON_WIND, "미리 조절해요", "앉은 자리 쪽 바람을\n한 단계 올려요")
        InfoRow(ICON_CLOCK, "미리 알려 줘요", "같은 길에서 멀미가 났던\n시간보다 5분 앞서요")
        InfoRow(
            ICON_BUBBLE, "바로 도와요",
            if (VOICE_CALL_READY) "멀미 나요를 누르거나 말하면\n바람을 최대로 하고 쉴 곳을 찾아요"
            else "멀미 나요를 누르면\n바람을 최대로 하고 쉴 곳을 찾아요"
        )
    }
}

/** "허용하고 계속" asks for the two permissions ([onAllow]) and goes on; it does not wait for the answer. */
@Composable
fun IntroTwo(onAllow: () -> Unit, onBack: () -> Unit, onSkip: () -> Unit) {
    IntroScreen(
        step = 2,
        headline = "두 가지를\n허용해 주세요",
        body = "허용하지 않으면 바람과 창문을\n안전하게 조절할 수 없어요.",
        cta = "허용하고 계속",
        onNext = onAllow, onBack = onBack, onSkip = onSkip
    ) {
        InfoRow(ICON_GAUGE, "속도 정보", "주행 중인지 알아야 창문을\n안전하게 열고 닫아요")
        InfoRow(ICON_BELL, "알림", "작동 중이라는 표시와 안내를\n화면에 보여 드려요")
        Spacer(Modifier.height(u(20)))
        Txt("다음 창이 뜨면 허용을 눌러 주세요", 28, Palette.Sub)
    }
}

@Composable
private fun VoiceChip(text: String) {
    Box(
        Modifier.background(Palette.Primary.copy(alpha = 0.16f), RoundedCornerShape(u(999))).padding(horizontal = u(28), vertical = u(14))
    ) { Txt(text, 34, Palette.Primary, FontWeight.Medium, maxLines = 1, spacing = -0.01f) }
}

@Composable
private fun SectionLabel(icon: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(14))) {
        LineIcon(icon, 38, Palette.Sub, 1.8f)
        Txt(text, 30, Palette.Sub)
    }
}

/** The voice phrases are examples; the "말로" part is only shown once Gleo accepts the voice functions (see [VOICE_CALL_READY]). */
@Composable
fun IntroThree(onStart: () -> Unit, onBack: () -> Unit, onSkip: () -> Unit) {
    IntroScreen(
        step = 3,
        headline = "멀미가 나면\n이렇게 불러요",
        body = if (VOICE_CALL_READY) "말하면 화면을 켜지 않아도 돼요.\n화면으로 열려면 하단 바에\n고정해 두세요." else "화면으로 열려면 하단 바에\n고정해 두세요.",
        cta = "시작하기",
        onNext = onStart, onBack = onBack, onSkip = onSkip
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(u(56))) {
            if (VOICE_CALL_READY) {
                Column(verticalArrangement = Arrangement.spacedBy(u(22))) {
                    SectionLabel(ICON_MIC, "말로")
                    Column(verticalArrangement = Arrangement.spacedBy(u(16))) {
                        Row(horizontalArrangement = Arrangement.spacedBy(u(16))) {
                            VoiceChip("“멀미 나요”")
                            VoiceChip("“괜찮아졌어요”")
                        }
                        VoiceChip("“차멀미 케어 열어 줘”")
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(u(22))) {
                SectionLabel(ICON_HOME, "화면으로")
                Row(
                    Modifier
                        .size(u(610), u(104))
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(u(52))),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0..4) {
                        if (i == 2) {
                            Box(
                                Modifier
                                    .border(u(4), Palette.Primary.copy(alpha = 0.55f), RoundedCornerShape(u(18)))
                                    .padding(u(4))
                            ) { AppIcon(64) }
                        } else {
                            Box(Modifier.size(u(60)).background(Color.White.copy(alpha = 0.16f), CircleShape))
                        }
                    }
                }
                Txt("하단 바에 고정하면 한 번에 열려요", 28, Palette.Sub)
            }
        }
    }
}
