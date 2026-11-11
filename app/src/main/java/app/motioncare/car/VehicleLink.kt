// ver 1.1.0 | 2026-11-05 20:53
package app.motioncare.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Door
import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.Window
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.os.Handler
import app.motioncare.core.CarLayout
import app.motioncare.core.CheckRow
import app.motioncare.core.Desired
import app.motioncare.core.FanWant
import app.motioncare.core.Level
import app.motioncare.core.Seat
import kotlin.math.roundToInt

/**
 * Typed Pleos Vehicle SDK 2.3.2 calls for seat occupancy, HVAC (recirculation, fan speed, fan direction,
 * power) and window position. Every method name and signature used here was read from the SDK sources
 * (Vehicle-2.3.2-sources.jar). All SDK callbacks are posted to [main] before any state is touched.
 *
 * The link never assumes what the vehicle supports: each feature is checked first, and the areas the
 * vehicle reports are the only ones that are ever written. What it changes is remembered and restored.
 */
class VehicleLink(
    context: Context,
    private val main: Handler,
    private val log: (String) -> Unit,
    private val onOccupancy: (Map<String, Boolean?>) -> Unit,
    private val onNotes: (List<String>) -> Unit,
    private val onCheck: (List<CheckRow>) -> Unit,
    /** Called once the seat, door and window answers are all in: how many seat rows to draw. */
    private val onRows: (Int) -> Unit = {}
) {
    private val vehicle = Vehicle(context.applicationContext)
    private var ready = false
    private var hvac: HVAC? = null
    private var seatApi: ai.pleos.playground.vehicle.api.Seat? = null
    private var windowApi: Window? = null
    private var doorApi: Door? = null

    /** Bumped whenever a capability answer arrives, so the caller can re-apply what it wants. */
    @Volatile var capsVersion: Int = 0
        private set

    // capabilities, as the vehicle reported them
    private var seatAreas: List<SeatArea> = emptyList()
    private var fanBounds: Map<SeatArea, AreaBounds<Int>> = emptyMap()
    private var recircAreas: List<SeatArea> = emptyList()
    private var powerAreas: List<SeatArea> = emptyList()
    private var directionAreas: Map<SeatArea, List<FanDirectionType>> = emptyMap()
    private var windowBounds: Map<WindowArea, AreaBounds<Int>> = emptyMap()

    // which parts of the car the vehicle reports, to work out how many seat rows to draw
    private var seatKeys: Set<String> = emptySet()
    private var doorKeys: Set<String> = emptySet()
    private var windowKeys: Set<String> = emptySet()
    private val layoutAnswered = linkedSetOf<String>()

    private val checkRows = linkedMapOf<String, CheckRow>()
    private val occupancy = linkedMapOf<String, Boolean?>()
    private val seatListeners = mutableListOf<Pair<SeatArea, SeatOccupancyListener>>()

    // what we changed (previous values), so it can be put back
    private val recircBackup = linkedMapOf<SeatArea, Boolean?>()
    private val fanBackup = linkedMapOf<SeatArea, Int?>()
    private val powerBackup = linkedMapOf<SeatArea, Boolean?>()
    private val directionBackup = linkedMapOf<SeatArea, FanDirectionType?>()
    private val windowBackup = linkedMapOf<WindowArea, Int?>()
    private val notes = linkedMapOf<String, String>()

    var windowOpenHigher: Boolean = true

    // ---------------------------------------------------------------- lifecycle

    fun start(): Boolean {
        try {
            vehicle.initialize()
            ready = true
        } catch (e: Exception) {
            log("Vehicle 초기화 실패: $e")
            setRow("sdk", CheckRow("차량 SDK", Level.BAD, "초기화 실패: ${e.message ?: e}"))
            return false
        }
        hvac = vehicle.getHVAC()
        seatApi = vehicle.getSeat()
        windowApi = vehicle.getWindow()
        doorApi = vehicle.getDoor()
        setRow("sdk", CheckRow("차량 SDK", Level.OK, "Vehicle SDK 2.3.2 초기화 완료"))
        checkSeat()
        checkDoor()
        checkFan()
        checkRecirculation()
        checkPower()
        checkDirection()
        checkWindow()
        return true
    }

    fun stop() {
        restoreAll(immediatePower = true)
        val s = seatApi
        if (s != null) {
            for ((area, l) in seatListeners) {
                try { s.unregisterSeatOccupancy(area, l) } catch (e: Exception) { log("착석 감지 해제 실패: $e") }
            }
        }
        seatListeners.clear()
        if (ready) {
            try { vehicle.release() } catch (e: Exception) { log("Vehicle 해제 실패: $e") }
            ready = false
        }
    }

    // ---------------------------------------------------------------- capability checks

    private fun areaName(a: SeatArea): String = a.values.joinToString("+") { it.name }
    private fun areaName(a: WindowArea): String = a.values.joinToString("+") { it.name }

    private fun setRow(key: String, row: CheckRow) {
        checkRows[key] = row
        onCheck(checkRows.values.toList())
    }

    private fun failRow(key: String, label: String, e: Exception) {
        log("$label 지원 확인 실패: $e")
        setRow(key, CheckRow(label, Level.BAD, "확인 실패: ${e.message ?: e}"))
        if (key == "seat" || key == "door" || key == "window") layoutAnswer(key)
        capsVersion++
    }

    private fun checkSeat() {
        val s = seatApi ?: return
        s.checkSeatOccupancyCapabilities(
            onComplete = { config: CapabilityConfig, areas: List<SeatArea> ->
                main.post {
                    seatAreas = areas
                    seatKeys = areas.flatMap { a -> a.values.map { it.name } }.toSet()
                    val names = areas.joinToString(" · ") { areaName(it) }
                    setRow(
                        "seat",
                        CheckRow(
                            "착석 감지",
                            if (areas.isEmpty()) Level.WARN else Level.OK,
                            "${config.accessType} · ${areas.size}개 구역: $names"
                        )
                    )
                    areas.forEach { watchSeat(it) }
                    layoutAnswer("seat")
                    capsVersion++
                }
            },
            onFailed = { e: Exception -> main.post { failRow("seat", "착석 감지", e) } }
        )
    }

    private fun watchSeat(area: SeatArea) {
        val s = seatApi ?: return
        val listener = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
                main.post { setOccupied(area, occupancy) }
            }

            override fun onFailed(e: Exception) {
                main.post { log("착석 감지 갱신 실패 ${areaName(area)}: $e") }
            }
        }
        seatListeners += area to listener
        try {
            s.registerSeatOccupancy(area, listener)
        } catch (e: Exception) {
            log("착석 감지 등록 실패 ${areaName(area)}: $e")
        }
        s.getCurrentSeatOccupancy(
            area,
            onComplete = { v: Boolean? -> main.post { setOccupied(area, v) } },
            onFailed = { e: Exception -> main.post { log("착석 감지 읽기 실패 ${areaName(area)}: $e") } }
        )
    }

    private fun setOccupied(area: SeatArea, value: Boolean?) {
        area.values.forEach { occupancy[it.name] = value }
        onOccupancy(LinkedHashMap(occupancy))
    }

    private fun checkDoor() {
        val d = doorApi ?: return
        d.checkDoorLockCapabilities(
            onComplete = { config: CapabilityConfig, areas: List<DoorArea> ->
                main.post {
                    doorKeys = areas.flatMap { a -> a.values.map { it.name } }.toSet()
                    val names = areas.joinToString(" · ") { a -> a.values.joinToString("+") { it.name } }
                    setRow("door", CheckRow("차량 문", if (areas.isEmpty()) Level.WARN else Level.OK, "${config.accessType} · ${areas.size}개 구역: $names"))
                    layoutAnswer("door")
                    capsVersion++
                }
            },
            onFailed = { e: Exception -> main.post { failRow("door", "차량 문", e) } }
        )
    }

    /** Seat, door and window answers decide the drawing together, so wait for all three. */
    private fun layoutAnswer(part: String) {
        layoutAnswered += part
        if (layoutAnswered.size < 3) return
        val rows = CarLayout.rowsFrom(seatKeys, doorKeys, windowKeys)
        val known = seatKeys.size + doorKeys.size + windowKeys.size
        setRow(
            "layout",
            CheckRow(
                "좌석 배치",
                if (known == 0) Level.WARN else Level.OK,
                if (known == 0) "차량이 알려 주지 않아 기본 ${rows}열로 그림"
                else "${rows}열 · 좌석 ${seatKeys.size} · 문 ${doorKeys.size} · 창문 ${windowKeys.size}개 구역을 근거로 함"
            )
        )
        onRows(rows)
    }

    private fun checkFan() {
        val h = hvac ?: return
        h.checkHvacFanSpeedCapabilities(
            onComplete = { config: CapabilityConfig, map: Map<SeatArea, AreaBounds<Int>> ->
                main.post {
                    fanBounds = map
                    val txt = map.entries.joinToString(" · ") { "${areaName(it.key)} ${it.value.minValue}~${it.value.maxValue}" }
                    setRow("fan", CheckRow("풍량", if (map.isEmpty()) Level.WARN else Level.OK, "${config.accessType} · $txt"))
                    capsVersion++
                }
            },
            onFailed = { e: Exception -> main.post { failRow("fan", "풍량", e) } }
        )
    }

    private fun checkRecirculation() {
        val h = hvac ?: return
        h.checkHvacRecirculationCapabilities(
            onComplete = { config: CapabilityConfig, areas: List<SeatArea> ->
                main.post {
                    recircAreas = areas
                    val names = areas.joinToString(" · ") { areaName(it) }
                    setRow("recirc", CheckRow("내기순환", if (areas.isEmpty()) Level.WARN else Level.OK, "${config.accessType} · $names"))
                    capsVersion++
                }
            },
            onFailed = { e: Exception -> main.post { failRow("recirc", "내기순환", e) } }
        )
    }

    private fun checkPower() {
        val h = hvac ?: return
        h.checkHvacPowerCapabilities(
            onComplete = { config: CapabilityConfig, areas: List<SeatArea> ->
                main.post {
                    powerAreas = areas
                    val names = areas.joinToString(" · ") { areaName(it) }
                    setRow("power", CheckRow("공조 전원", if (areas.isEmpty()) Level.WARN else Level.OK, "${config.accessType} · $names"))
                    capsVersion++
                }
            },
            onFailed = { e: Exception -> main.post { failRow("power", "공조 전원", e) } }
        )
    }

    private fun checkDirection() {
        val h = hvac ?: return
        h.checkHvacFanDirectionCapabilities(
            onComplete = { config: CapabilityConfig, map: Map<SeatArea, List<FanDirectionType>> ->
                main.post {
                    directionAreas = map
                    val txt = map.entries.joinToString(" · ") { "${areaName(it.key)} ${it.value.joinToString("/") { d -> d.name }}" }
                    val hasFace = map.values.any { FanDirectionType.FACE in it }
                    setRow(
                        "direction",
                        CheckRow("바람 방향", if (hasFace) Level.OK else Level.WARN, "${config.accessType} · ${if (txt.isEmpty()) "구역 없음" else txt}")
                    )
                    capsVersion++
                }
            },
            onFailed = { e: Exception -> main.post { failRow("direction", "바람 방향", e) } }
        )
    }

    private fun checkWindow() {
        val w = windowApi ?: return
        w.checkWindowPositionCapabilities(
            onComplete = { config: CapabilityConfig, map: Map<WindowArea, AreaBounds<Int>> ->
                main.post {
                    windowBounds = map
                    windowKeys = map.keys.flatMap { a -> a.values.map { it.name } }.toSet()
                    val txt = map.entries.joinToString(" · ") { "${areaName(it.key)} ${it.value.minValue}~${it.value.maxValue}" }
                    setRow("window", CheckRow("창문 위치", if (map.isEmpty()) Level.WARN else Level.OK, "${config.accessType} · $txt"))
                    layoutAnswer("window")
                    capsVersion++
                }
            },
            onFailed = { e: Exception -> main.post { failRow("window", "창문 위치", e) } }
        )
    }

    // ---------------------------------------------------------------- apply / restore

    /** Seat -> the window next to it (the middle rear seat has none). */
    private fun windowOf(seat: Seat?): WindowValue? = when (seat) {
        Seat.ROW_1_LEFT -> WindowValue.ROW_1_LEFT
        Seat.ROW_1_RIGHT -> WindowValue.ROW_1_RIGHT
        Seat.ROW_2_LEFT -> WindowValue.ROW_2_LEFT
        Seat.ROW_2_RIGHT -> WindowValue.ROW_2_RIGHT
        else -> null
    }

    private fun seatValueOf(seat: Seat?): SeatValue? = seat?.let { s -> SeatValue.entries.firstOrNull { it.name == s.key } }

    /** The areas that cover [seat]; when the vehicle reports none that does (or no seat is given), all reported areas. */
    private fun pickSeatAreas(all: Collection<SeatArea>, seat: Seat?): List<SeatArea> {
        val v = seatValueOf(seat)
        val covering = if (v == null) emptyList() else all.filter { a -> a.values.any { it == v } }
        return if (covering.isNotEmpty()) covering else all.toList()
    }

    private fun why(e: Exception): String = (e.message ?: e.javaClass.simpleName).replace('\n', ' ').take(220)

    private fun note(key: String, text: String?) {
        if (text == null) notes.remove(key) else notes[key] = text
        onNotes(notes.values.toList())
    }

    private var gen = 0

    fun apply(d: Desired) {
        if (!ready) return
        gen++
        if (d.isIdle) {
            restoreAll()
            return
        }
        if (d.powerOn) {
            val areas = pickSeatAreas(powerAreas, d.target)
            if (areas.isEmpty()) applyRest(d) else ensurePowerOn(areas.first()) { applyRest(d) }
        } else {
            restorePower()
            applyRest(d)
        }
    }

    private fun applyRest(d: Desired) {
        // recirculation
        if (d.recircOff) {
            val areas = pickSeatAreas(recircAreas, d.target)
            if (areas.isEmpty()) note("recirc", "내기순환 조절 미지원") else areas.forEach { ensureRecircOff(it) }
        } else restoreRecirc()

        // fan speed
        if (d.fan != FanWant.NONE) {
            val areas = pickSeatAreas(fanBounds.keys, d.target)
            if (areas.isEmpty()) {
                note("fan", "풍량 조절 미지원")
            } else if (d.fan == FanWant.PLUS_ONE && !d.powerOn) {
                // prevention never switches the climate system on by itself; a blower that is off cannot be changed
                val pa = pickSeatAreas(powerAreas, d.target).firstOrNull()
                val h = hvac
                if (pa == null || h == null) {
                    areas.forEach { ensureFan(it, d.fan, d.target) }
                } else {
                    h.getHvacPowerState(
                        pa,
                        onComplete = { on: Boolean? ->
                            main.post {
                                if (on == false) note("fan", "공조가 꺼져 있어 풍량은 그대로예요")
                                else areas.forEach { ensureFan(it, d.fan, d.target) }
                            }
                        },
                        onFailed = { e: Exception -> main.post { log("공조 전원 읽기 실패: $e"); areas.forEach { ensureFan(it, d.fan, d.target) } } }
                    )
                }
            } else {
                areas.forEach { ensureFan(it, d.fan, d.target) }
            }
        } else restoreFan()

        // fan direction
        if (d.faceDirection) {
            val areas = pickSeatAreas(directionAreas.filter { FanDirectionType.FACE in it.value }.keys, d.target)
            if (areas.isEmpty()) note("direction", "얼굴 방향 미지원") else areas.forEach { ensureFace(it) }
        } else restoreDirection()

        // window
        val wv = windowOf(d.target)
        if (d.windowOpen && wv != null) {
            val area = windowBounds.keys.firstOrNull { a -> a.values.any { it == wv } }
            if (area == null) note("window", "창문 조절 미지원 (${wv.name})") else ensureWindowOpen(area)
        } else restoreWindow()
    }

    fun restoreAll(immediatePower: Boolean = false) {
        val g = ++gen
        restoreRecirc()
        restoreFan()
        restoreDirection()
        restoreWindow()
        if (immediatePower) restorePower()
        else main.postDelayed({ if (g == gen) restorePower() }, 1500L)
    }

    private fun ensureRecircOff(area: SeatArea) {
        val h = hvac ?: return
        h.getHvacRecirculationState(
            area,
            onComplete = { cur: Boolean? ->
                main.post {
                    if (!recircBackup.containsKey(area)) recircBackup[area] = cur
                    if (cur == false) {
                        note("recirc", "내기순환 꺼짐 (외기 유입)")
                    } else {
                        h.setHvacRecirculation(
                            area,
                            false,
                            onComplete = { a: SeatArea, v: Boolean? -> main.post { log("내기순환 설정 ${areaName(a)} = $v"); note("recirc", "내기순환 끔 (외기 유입)") } },
                            onFailed = { e: Exception -> main.post { log("내기순환 설정 실패: $e"); note("recirc", "내기순환 설정 실패: ${why(e)}") } }
                        )
                    }
                }
            },
            onFailed = { e: Exception -> main.post { log("내기순환 읽기 실패 ${areaName(area)}: $e"); note("recirc", "내기순환 읽기 실패: ${why(e)}") } }
        )
    }

    private fun restoreRecirc() {
        val h = hvac ?: return
        if (recircBackup.isEmpty()) { note("recirc", null); return }
        for ((area, prev) in recircBackup.toMap()) {
            if (prev == true) {
                h.setHvacRecirculation(
                    area,
                    true,
                    onComplete = { a: SeatArea, v: Boolean? -> main.post { log("내기순환 복원 ${areaName(a)} = $v") } },
                    onFailed = { e: Exception -> main.post { log("내기순환 복원 실패: $e") } }
                )
            }
        }
        recircBackup.clear()
        note("recirc", null)
    }

    private fun ensurePowerOn(area: SeatArea, then: () -> Unit) {
        val h = hvac ?: run { then(); return }
        h.getHvacPowerState(
            area,
            onComplete = { cur: Boolean? ->
                main.post {
                    if (!powerBackup.containsKey(area)) powerBackup[area] = cur
                    if (cur != true) {
                        h.setHvacPower(
                            area,
                            true,
                            onComplete = { a: SeatArea, v: Boolean? -> main.post { log("공조 전원 켬 ${areaName(a)} = $v"); note("power", "공조 켬"); then() } },
                            onFailed = { e: Exception -> main.post { log("공조 전원 설정 실패: $e"); note("power", "공조 켜기 실패: ${why(e)}"); then() } }
                        )
                    } else {
                        then()
                    }
                }
            },
            onFailed = { e: Exception -> main.post { log("공조 전원 읽기 실패 ${areaName(area)}: $e"); note("power", "공조 전원 읽기 실패: ${why(e)}"); then() } }
        )
    }

    private fun restorePower() {
        val h = hvac ?: return
        if (powerBackup.isEmpty()) { note("power", null); return }
        for ((area, prev) in powerBackup.toMap()) {
            if (prev == false) {
                h.setHvacPower(
                    area,
                    false,
                    onComplete = { a: SeatArea, v: Boolean? -> main.post { log("공조 전원 복원 ${areaName(a)} = $v") } },
                    onFailed = { e: Exception -> main.post { log("공조 전원 복원 실패: $e") } }
                )
            }
        }
        powerBackup.clear()
        note("power", null)
    }

    private fun ensureFan(area: SeatArea, want: FanWant, target: Seat?) {
        val h = hvac ?: return
        val b = fanBounds[area] ?: return
        val tv = seatValueOf(target)
        val covers = tv != null && area.values.any { it == tv }
        val zone = if (covers || target == null) "" else " · 좌석별 조절 불가, ${areaName(area)} 구역"
        h.getHvacFanSpeed(
            area,
            onComplete = { cur: Int? ->
                main.post {
                    if (!fanBackup.containsKey(area)) fanBackup[area] = cur
                    val base = fanBackup[area] ?: b.minValue
                    val goal = if (want == FanWant.MAX) b.maxValue else (base + 1).coerceIn(b.minValue, b.maxValue)
                    if (cur == goal) {
                        note("fan", "풍량 $goal 유지$zone")
                    } else {
                        h.setHvacFanSpeed(
                            area,
                            goal,
                            onComplete = { a: SeatArea, v: Int? ->
                                main.post {
                                    log("풍량 설정 ${areaName(a)} = $v")
                                    note("fan", (if (want == FanWant.MAX) "풍량 최대 ($goal)" else "풍량 한 단계 올림 (${base} → $goal)") + zone)
                                }
                            },
                            onFailed = { e: Exception -> main.post { log("풍량 설정 실패: $e"); note("fan", "풍량 설정 실패: ${why(e)}") } }
                        )
                    }
                }
            },
            onFailed = { e: Exception -> main.post { log("풍량 읽기 실패 ${areaName(area)}: $e"); note("fan", "풍량 읽기 실패: ${why(e)}") } }
        )
    }

    private fun restoreFan() {
        val h = hvac ?: return
        if (fanBackup.isEmpty()) { note("fan", null); return }
        for ((area, prev) in fanBackup.toMap()) {
            val b = fanBounds[area]
            if (prev != null && b != null && prev in b.minValue..b.maxValue) {
                h.setHvacFanSpeed(
                    area,
                    prev,
                    onComplete = { a: SeatArea, v: Int? -> main.post { log("풍량 복원 ${areaName(a)} = $v") } },
                    onFailed = { e: Exception -> main.post { log("풍량 복원 실패: $e") } }
                )
            }
        }
        fanBackup.clear()
        note("fan", null)
    }

    private fun ensureFace(area: SeatArea) {
        val h = hvac ?: return
        h.getHvacFanDirection(
            area,
            onComplete = { cur: FanDirectionType? ->
                main.post {
                    if (!directionBackup.containsKey(area)) directionBackup[area] = cur
                    if (cur == FanDirectionType.FACE) {
                        note("direction", "바람 방향 얼굴")
                    } else {
                        h.setHvacFanDirection(
                            area,
                            FanDirectionType.FACE,
                            onComplete = { a: SeatArea, v: FanDirectionType? -> main.post { log("바람 방향 ${areaName(a)} = $v"); note("direction", "바람 방향 얼굴") } },
                            onFailed = { e: Exception -> main.post { log("바람 방향 설정 실패: $e") } }
                        )
                    }
                }
            },
            onFailed = { e: Exception -> main.post { log("바람 방향 읽기 실패 ${areaName(area)}: $e") } }
        )
    }

    private fun restoreDirection() {
        val h = hvac ?: return
        if (directionBackup.isEmpty()) { note("direction", null); return }
        for ((area, prev) in directionBackup.toMap()) {
            if (prev != null && prev != FanDirectionType.UNKNOWN) {
                h.setHvacFanDirection(
                    area,
                    prev,
                    onComplete = { a: SeatArea, v: FanDirectionType? -> main.post { log("바람 방향 복원 ${areaName(a)} = $v") } },
                    onFailed = { e: Exception -> main.post { log("바람 방향 복원 실패: $e") } }
                )
            }
        }
        directionBackup.clear()
        note("direction", null)
    }

    private fun ensureWindowOpen(area: WindowArea) {
        val w = windowApi ?: return
        val b = windowBounds[area] ?: return
        w.getCurrentWindowPosition(
            area,
            onComplete = { cur: Int? ->
                main.post {
                    if (!windowBackup.containsKey(area)) windowBackup[area] = cur
                    val base = windowBackup[area] ?: if (windowOpenHigher) b.minValue else b.maxValue
                    val step = ((b.maxValue - b.minValue) * 0.2).roundToInt().coerceAtLeast(1)
                    val goal = if (windowOpenHigher) (base + step).coerceAtMost(b.maxValue) else (base - step).coerceAtLeast(b.minValue)
                    if (cur == goal) {
                        note("window", "창문 조금 열림")
                    } else {
                        w.setWindowPosition(
                            area,
                            goal,
                            onComplete = { a: WindowArea, v: Int? -> main.post { log("창문 위치 ${areaName(a)} = $v"); note("window", "창문 조금 열림 (${base} → $goal)") } },
                            onFailed = { e: Exception -> main.post { log("창문 설정 실패: $e"); note("window", "창문 설정 실패: ${why(e)}") } }
                        )
                    }
                }
            },
            onFailed = { e: Exception -> main.post { log("창문 읽기 실패 ${areaName(area)}: $e"); note("window", "창문 읽기 실패: ${why(e)}") } }
        )
    }

    private fun restoreWindow() {
        val w = windowApi ?: return
        if (windowBackup.isEmpty()) { note("window", null); return }
        for ((area, prev) in windowBackup.toMap()) {
            if (prev != null) {
                w.setWindowPosition(
                    area,
                    prev,
                    onComplete = { a: WindowArea, v: Int? -> main.post { log("창문 복원 ${areaName(a)} = $v") } },
                    onFailed = { e: Exception -> main.post { log("창문 복원 실패: $e") } }
                )
            }
        }
        windowBackup.clear()
        note("window", null)
    }
}
