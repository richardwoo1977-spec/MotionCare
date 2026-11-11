// ver 1.7.0 | 2026-11-11 20:35
package app.motioncare.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.motioncare.core.CareMode
import app.motioncare.core.CheckRow
import app.motioncare.core.Gear
import app.motioncare.core.Level
import app.motioncare.core.Policy
import app.motioncare.core.RestDisplay
import app.motioncare.core.Seat
import app.motioncare.core.SeatKeys
import app.motioncare.data.DemoNavi
import app.motioncare.data.DiagLog
import app.motioncare.data.LiveState
import app.motioncare.service.CareService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ===================================================================== 홈

private fun gearLabel(g: Gear): String = when (g) {
    Gear.PARK -> "P 주차"
    Gear.REVERSE -> "R 후진"
    Gear.NEUTRAL -> "N 중립"
    Gear.DRIVE -> "D 주행"
    Gear.UNKNOWN -> "기어 확인 중"
}

// ===================================================================== 쉬어 가기

private fun facilities(toilet: Boolean, nursing: Boolean, kids: Boolean): String {
    val l = mutableListOf<String>()
    if (toilet) l += "화장실"
    if (nursing) l += "수유실"
    if (kids) l += "키즈존"
    return l.joinToString(" · ")
}

@Composable
fun RestScreen(live: LiveState) {
    Row(
        Modifier.fillMaxSize().padding(u(40)),
        horizontalArrangement = Arrangement.spacedBy(u(30))
    ) {
        Panel(Modifier.width(u(560)).fillMaxHeight()) {
            Txt("목적지", 40, weight = FontWeight.Bold)
            Spacer(Modifier.height(u(16)))
            DemoNavi.destinations.forEach { d ->
                val on = d == live.destination
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(u(100))
                        .background(if (on) Palette.Primary else Palette.Seg, RoundedCornerShape(u(24)))
                        .clickable { CareService.run { it.setDestination(d) } },
                    contentAlignment = Alignment.Center
                ) {
                    Txt(d, 38, if (on) Palette.OnPrimary else Palette.Ink, FontWeight.Bold)
                }
                Spacer(Modifier.height(u(14)))
            }
            Spacer(Modifier.height(u(10)))
            val after = live.restAfterMin
            Txt(
                if (after != null) "기록을 보니\n출발 ${after}분 뒤에 미리 알려드려요"
                else "이 목적지는 아직\n멀미 기록이 없어요",
                32,
                Palette.Sub
            )
            Spacer(Modifier.weight(1f))
            Pill("위치는 시연용 가상", Palette.WarnSoft, Palette.Warn)
            Spacer(Modifier.height(u(12)))
            Txt(
                if (DemoNavi.usingFallback) "휴게소 파일을 읽지 못해\n가상 목록을 쓰고 있어요"
                else "목록: 한국도로공사 파일 ${DemoNavi.catalogSize}곳\n(기준일 ${DemoNavi.dataAsOf}) · 거리는 직선 기준",
                30, Palette.Muted
            )
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(u(30))) {
            val s = live.suggestion
            if (s != null) {
                val added = live.waypoints.any { it.id == s.id }
                Panel(Modifier.fillMaxWidth(), background = Palette.PrimarySoft) {
                    Txt("지금 쉬어 가면 좋아요", 36, Palette.Primary, FontWeight.Bold)
                    Spacer(Modifier.height(u(10)))
                    Txt(s.name, 48, weight = FontWeight.Bold)
                    Txt("${RestDisplay.kmText(s.distanceKm)} · 약 ${s.etaMin}분\n${facilities(s.toilet, s.nursing, s.kids)}", 32, Palette.Sub)
                    Spacer(Modifier.height(u(10)))
                    Txt(live.suggestionWhy, 32, Palette.Ink)
                    Spacer(Modifier.height(u(20)))
                    BigButton(
                        text = if (added) "경유지에 추가됨" else "경유지로 추가",
                        heightPx = 100,
                        bg = Palette.Primary,
                        fg = Palette.OnPrimary,
                        enabled = !added,
                        size = 38,
                        onClick = { CareService.addWaypoint(s.id) }
                    )
                }
            } else {
                Panel(Modifier.fillMaxWidth()) {
                    Txt("추천할 휴게소 없음", 36, weight = FontWeight.Bold)
                    Spacer(Modifier.height(u(8)))
                    Txt("멀미 나요를 누르거나, 기록한 시간이 되면\n가까운 휴게소를 먼저 알려드려요", 32, Palette.Sub)
                }
            }
            Panel(Modifier.fillMaxWidth().weight(1f)) {
                Txt("앞쪽 휴게소", 36, weight = FontWeight.Bold)
                Spacer(Modifier.height(u(10)))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DemoNavi.stops.forEach { st ->
                        val added = live.waypoints.any { it.id == st.id }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = u(12)),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Txt(st.name, 36, weight = FontWeight.Bold)
                                Txt("${RestDisplay.kmText(st.distanceKm)} · ${st.etaMin}분\n${facilities(st.toilet, st.nursing, st.kids)}", 30, Palette.Sub)
                            }
                            BigButton(
                                text = if (added) "추가됨" else "추가",
                                heightPx = 88,
                                widthPx = 180,
                                bg = Palette.Seg,
                                fg = Palette.Ink,
                                enabled = !added,
                                size = 32,
                                onClick = { CareService.addWaypoint(st.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ===================================================================== 기록

@Composable
fun HistoryScreen(live: LiveState) {
    val fmt = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
    val events = live.history
    Row(
        Modifier.fillMaxSize().padding(u(40)),
        horizontalArrangement = Arrangement.spacedBy(u(30))
    ) {
        Panel(Modifier.width(u(560)).fillMaxHeight()) {
            Txt("멀미 기록", 40, weight = FontWeight.Bold)
            Spacer(Modifier.height(u(16)))
            if (events.isEmpty()) {
                Txt("아직 기록이 없어요.\n주행 중 멀미 나요를 누르면\n그 시각이 저장됩니다", 32, Palette.Sub)
            } else {
                Txt("${events.size}번 기록됨", 44, Palette.Primary, FontWeight.Bold)
                Spacer(Modifier.height(u(16)))
                // a group ("2열 전체") counts once for every seat in it; events with no seat known are left out
                val bySeat = events.flatMap { SeatKeys.split(it.seatKey) }.groupingBy { it }.eachCount().maxByOrNull { it.value }
                if (bySeat != null) {
                    Txt("가장 잦은 자리", 30, Palette.Muted)
                    Txt("${SeatKeys.label(bySeat.key)} · ${bySeat.value}번", 36, weight = FontWeight.Bold)
                    Spacer(Modifier.height(u(16)))
                }
                Txt("목적지별 평균 시각", 30, Palette.Muted)
                DemoNavi.destinations.forEach { d ->
                    val mins = events.filter { it.destination == d && it.elapsedMin > 0 }.map { it.elapsedMin }
                    if (mins.isNotEmpty()) {
                        Txt("$d · 출발 ${mins.sum() / mins.size}분째 (${mins.size}번)", 34, weight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(u(16)))
                val after = live.restAfterMin
                Txt(
                    if (after != null) "${live.destination}행은\n출발 ${after}분 뒤에 미리 쉬자고 알려요"
                    else "${live.destination}행은 아직\n미리 알릴 기록이 없어요",
                    32,
                    Palette.Sub
                )
            }
            Spacer(Modifier.weight(1f))
            if (events.isEmpty()) {
                BigButton(
                    text = "예시 기록 넣기 (시연)",
                    heightPx = 100,
                    bg = Palette.Primary,
                    fg = Palette.OnPrimary,
                    size = 34,
                    onClick = { CareService.run { it.demoSeedHistory() } }
                )
            } else {
                BigButton(
                    text = "기록 지우기",
                    heightPx = 100,
                    bg = Palette.Seg,
                    fg = Palette.Ink,
                    size = 34,
                    onClick = { CareService.run { it.demoClearHistory() } }
                )
            }
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            Txt("멀미가 난 때", 36, weight = FontWeight.Bold)
            Spacer(Modifier.height(u(10)))
            if (events.isEmpty()) {
                Txt("표시할 기록이 없어요", 32, Palette.Muted)
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    events.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = u(14)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(u(24))
                        ) {
                            Txt(fmt.format(Date(e.epochMs)), 32, Palette.Sub, modifier = Modifier.width(u(200)))
                            Txt("출발 ${e.elapsedMin}분째", 34, weight = FontWeight.Bold, modifier = Modifier.width(u(240)))
                            Txt(SeatKeys.label(e.seatKey), 32, maxLines = 1, modifier = Modifier.width(u(240)))
                            Txt(e.destination, 32, Palette.Sub)
                        }
                    }
                }
            }
        }
    }
}

// ===================================================================== 점검

@Composable
private fun LevelPill(level: Level) {
    val (text, bg, fg) = when (level) {
        Level.OK -> Triple("정상", Palette.OkSoft, Palette.Ok)
        Level.WARN -> Triple("주의", Palette.WarnSoft, Palette.Warn)
        Level.BAD -> Triple("오류", Palette.BadSoft, Palette.Bad)
        Level.NONE -> Triple("미연동", Palette.Seg, Palette.Sub)
    }
    Box(Modifier.width(u(140)), contentAlignment = Alignment.CenterStart) { Pill(text, bg, fg) }
}

@Composable
fun CheckScreen(live: LiveState) {
    val log by DiagLog.lines.collectAsState()
    val rows: List<CheckRow> = live.check + listOf(
        CheckRow("주행 신호 (기어·속도)", if (live.signalsConnected) Level.OK else Level.BAD,
            if (live.signalsConnected) "연결됨 · ${gearLabel(live.gear)}" else "연결 안 됨 (CAR_SPEED 권한 확인)"),
        CheckRow(
            "내비·휴게소 정보", if (DemoNavi.usingFallback) Level.WARN else Level.NONE,
            if (DemoNavi.usingFallback) "휴게소 파일을 읽지 못함 · 가상 목록 사용"
            else "경유지는 내비 연동(NaviHelper)으로 넣음 · 휴게소 목록은 도로공사 파일 ${DemoNavi.catalogSize}곳(기준일 ${DemoNavi.dataAsOf}) · 현재 위치는 시연용 가상"
        ),
        CheckRow("창문", Level.NONE, "앱이 열지 않음 · 안내 음성으로 권하기만 함"),
        CheckRow("음성 호출 (Gleo)", Level.NONE, "멀미 나요 · 괜찮아졌어요 · 화면 열기 3가지 · 자리는 한 자리 또는 묶음(뒷자리 모두 등)"),
        CheckRow("앱 마켓 권한 승인", Level.NONE, "SDK 문서상 일반 권한 · 마켓 추가 승인 여부 미확인")
    )
    Row(
        Modifier.fillMaxSize().padding(u(40)),
        horizontalArrangement = Arrangement.spacedBy(u(30))
    ) {
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            Txt("차량이 알려준 것", 40, weight = FontWeight.Bold)
            Spacer(Modifier.height(u(8)))
            Txt("버전 $VERSION_LABEL", 30, Palette.Muted)
            Spacer(Modifier.height(u(10)))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = u(12)), verticalAlignment = Alignment.Top) {
                        LevelPill(r.level)
                        Column(Modifier.weight(1f)) {
                            Txt(r.label, 34, weight = FontWeight.Bold)
                            Txt(r.detail, 30, Palette.Sub)
                        }
                    }
                }
            }
        }
        Column(
            Modifier.width(u(560)).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(u(30))
        ) {
            // the last voice calls and the seat value Gleo AI sent, kept apart so other log lines cannot push them out
            Panel(Modifier.fillMaxWidth()) {
                Txt("음성 호출 기록", 36, weight = FontWeight.Bold)
                Spacer(Modifier.height(u(8)))
                val calls = log.filter { it.contains("Gleo 호출") }.take(3)
                if (calls.isEmpty()) Txt("아직 없음", 28, Palette.Muted)
                calls.forEach { Txt(it, 26, Palette.Sub, maxLines = 2) }
            }
            Panel(Modifier.fillMaxWidth()) {
                Txt("시연 도구", 36, weight = FontWeight.Bold)
                Spacer(Modifier.height(u(6)))
                Txt("출발 후 시간을 앞당겨\n미리 쉬기 알림을 확인합니다", 30, Palette.Sub)
                Spacer(Modifier.height(u(16)))
                BigButton(
                    text = "출발 후 +10분", heightPx = 100,
                    bg = Palette.Seg, fg = Palette.Ink, size = 34,
                    onClick = { CareService.run { it.demoAddMinutes(10) } }
                )
            }
            // this card keeps its place and takes the height that is left; only the list inside scrolls
            Panel(Modifier.fillMaxWidth().weight(1f)) {
                Txt("최근 기록(로그)", 36, weight = FontWeight.Bold)
                Spacer(Modifier.height(u(8)))
                if (log.isEmpty()) Txt("아직 없음", 30, Palette.Muted)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    log.forEach { Txt(it, 24, Palette.Sub, maxLines = 2) }
                }
            }
        }
    }
}
