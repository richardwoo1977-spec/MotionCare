// ver 1.4.0 | 2026-11-11 19:05
package app.motioncare.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import app.motioncare.core.Gear
import app.motioncare.core.StartFlow
import app.motioncare.core.StartFlow.Stage
import app.motioncare.data.Live
import app.motioncare.data.LiveState
import app.motioncare.data.Store
import kotlinx.coroutines.delay

private class Tab(val label: String, val icon: String)

private val TABS = listOf(
    Tab("홈", "M3 11 L12 3 L21 11 V21 H15 V14 H9 V21 H3 Z"),
    Tab("쉬어 가기", "M5 8 H16 V14 A5 5 0 0 1 11 19 H10 A5 5 0 0 1 5 14 Z M16 10 H18 A2 2 0 0 1 18 14 H16 M8 3 V5 M12 3 V5"),
    Tab("기록", "M12 3 A9 9 0 1 0 12 21 A9 9 0 1 0 12 3 Z M12 7 V12 L15.5 14"),
    Tab("설정", "M4 8 H9.8 M14.2 8 H20 M4 16 H11.8 M16.2 16 H20 M12 5.8 A2.2 2.2 0 1 0 12 10.2 A2.2 2.2 0 1 0 12 5.8 Z M14 13.8 A2.2 2.2 0 1 0 14 18.2 A2.2 2.2 0 1 0 14 13.8 Z")
)

private val Teal = Color(0xFF0A7A68)
private val Lime = Color(0xFFCDF36B)

@Composable
fun AppRoot(needsPermission: Boolean, onRequestPermission: () -> Unit) {
    val live by Live.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    // title -> introduction (first run only) -> home. Guided screens exist for a parked car only.
    val guided = StartFlow.guidedAllowed(live.gear, live.speedKmh, live.signalsConnected)
    val guidedNow by rememberUpdatedState(guided)
    var stage by remember { mutableStateOf(StartFlow.first(guided)) }
    LaunchedEffect(stage) {
        if (stage == Stage.TITLE) {
            delay(StartFlow.TITLE_MS)
            stage = StartFlow.afterTitle(Store.onboardingDone, guidedNow)
        }
    }
    LaunchedEffect(guided) { if (!guided) stage = Stage.HOME }
    val shown = StartFlow.enforce(stage, guided)
    fun finishIntro() { Store.onboardingDone = true; stage = Stage.HOME }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Bg)) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val unit = minOf(wPx / DESIGN_W, hPx / DESIGN_H).coerceAtLeast(0.3f)
        CompositionLocalProvider(
            LocalUnit provides unit,
            LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified)
        ) {
            // the 1706 x 1210 design canvas, centred
            Box(Modifier.align(Alignment.Center).size(u(DESIGN_W), u(DESIGN_H))) {
              if (shown != Stage.HOME) {
                when (shown) {
                    Stage.TITLE -> TitleScreen()
                    Stage.ONB1 -> IntroOne(onNext = { stage = StartFlow.next(Stage.ONB1) }, onSkip = { finishIntro() })
                    Stage.ONB2 -> IntroTwo(
                        onAllow = { onRequestPermission(); stage = StartFlow.next(Stage.ONB2) },
                        onBack = { stage = StartFlow.back(Stage.ONB2) },
                        onSkip = { finishIntro() }
                    )
                    else -> IntroThree(
                        onStart = { finishIntro() },
                        onBack = { stage = StartFlow.back(Stage.ONB3) },
                        onSkip = { finishIntro() }
                    )
                }
              } else {
                Rail(tab, { tab = it }, live)
                Box(Modifier.offset(u(210), u(40)).size(u(1452), u(1130))) {
                    when (tab) {
                        0 -> HomeScreen(live, onOpenRest = { tab = 1 })
                        1 -> RestScreen(live)
                        2 -> HistoryScreen(live)
                        else -> CheckScreen(live)
                    }
                    // spoken guidance is also written on the screen: it is the only guidance when TTS is not ready
                    // (the home screen shows it in place of its title)
                    if (live.caption.isNotBlank() && tab != 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(u(104))
                                .background(Palette.Primary, RoundedCornerShape(u(24)))
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .padding(horizontal = u(32)),
                            contentAlignment = Alignment.Center
                        ) {
                            Txt(live.caption, 40, Palette.OnPrimary, FontWeight.Medium, maxLines = 1)
                        }
                    }
                    if (needsPermission) {
                        Row(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .background(Palette.WarnSoft, RoundedCornerShape(u(24)))
                                .padding(u(20)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(u(24))
                        ) {
                            Txt("속도를 읽으려면 권한이 필요해요", 34, Palette.Ink, FontWeight.Medium, Modifier.weight(1f))
                            BigButton(
                                text = "권한 허용", heightPx = 88, widthPx = 240,
                                bg = Palette.Primary, fg = Palette.OnPrimary, size = 34,
                                onClick = onRequestPermission
                            )
                        }
                    }
                }
              }
            }
        }
    }
}

/** The left rail: its own panel (156 x 1170 at 20, 20) with the app icon, four tabs and the gear. */
@Composable
private fun Rail(tab: Int, onTab: (Int) -> Unit, live: LiveState) {
    Box(
        Modifier
            .offset(u(20), u(20))
            .size(u(156), u(1170))
            .background(Palette.Rail, RoundedCornerShape(u(38)))
            .border(u(1), Palette.RailLine, RoundedCornerShape(u(38)))
    )
    Column(Modifier.offset(u(30), u(40)).width(u(136)).height(u(1130))) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AppIcon(68) }
        Spacer(Modifier.height(u(44)))
        Column(verticalArrangement = Arrangement.spacedBy(u(8))) {
            TABS.forEachIndexed { i, t -> RailButton(t, i == tab) { onTab(i) } }
        }
        Spacer(Modifier.weight(1f))
        GearPill(live)
    }
}

@Composable
private fun RailButton(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(u(136))
            .height(u(116))
            .background(if (selected) Palette.Primary.copy(alpha = 0.20f) else Color.Transparent, RoundedCornerShape(u(30)))
            .semantics { contentDescription = tab.label }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(u(10), Alignment.CenterVertically)
    ) {
        val path = remember(tab.icon) { PathParser().parsePathString(tab.icon).toPath() }
        val color = if (selected) Palette.Primary else Palette.Sub
        Canvas(Modifier.size(u(46))) {
            val k = size.width / 24f
            withTransform({ scale(k, k, Offset.Zero) }) {
                drawPath(path, color, style = Stroke(width = 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Txt(
            tab.label, 26, if (selected) Color.White else Palette.Sub,
            if (selected) FontWeight.Medium else FontWeight.Normal, align = TextAlign.Center, maxLines = 1
        )
    }
}

/** Teal rounded square with the lime seat (the chosen app icon). */
@Composable
internal fun AppIcon(sizeDp: Int) {
    Canvas(Modifier.size(u(sizeDp))) {
        val k = size.width / 220f
        withTransform({ scale(k, k, Offset.Zero) }) {
            drawRoundRect(Teal, Offset.Zero, Size(220f, 220f), CornerRadius(52f))
            drawRoundRect(Color.White, Offset(74f, 26f), Size(72f, 168f), CornerRadius(34f), style = Stroke(width = 9f))
            drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(86f, 62f), Size(48f, 44f), CornerRadius(12f))
            drawRoundRect(Lime, Offset(86f, 118f), Size(48f, 52f), CornerRadius(14f))
        }
    }
}

private fun gearLetter(g: Gear): String = when (g) {
    Gear.PARK -> "P"
    Gear.REVERSE -> "R"
    Gear.NEUTRAL -> "N"
    Gear.DRIVE -> "D"
    Gear.UNKNOWN -> "-"
}

/** The gear at the bottom of the rail; the dot is mint while the car signals are connected. */
@Composable
private fun GearPill(live: LiveState) {
    Row(
        Modifier
            .width(u(136))
            .height(u(84))
            .background(Color.White.copy(alpha = 0.09f), RoundedCornerShape(u(28)))
            .semantics { contentDescription = "기어 ${gearLetter(live.gear)}" },
        horizontalArrangement = Arrangement.spacedBy(u(10), Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(u(12)).background(if (live.signalsConnected) Palette.Primary else Palette.Gray, CircleShape))
        Txt(gearLetter(live.gear), 26, Palette.Ink, FontWeight.Medium)
    }
}
