// ver 1.4.0 | 2026-11-06 19:08
package app.motioncare.core

import app.motioncare.data.DemoNavi
import app.motioncare.data.Live
import app.motioncare.data.Store

/**
 * The decision logic. No Android framework types except the on-device [Store] and the shared [Live]
 * state. Everything here runs on the main thread (the service posts all signals to it).
 *
 *  PREVENT  while a trip is on and a designated seat is occupied (or its occupancy is unknown):
 *           recirculation off (outside air), fan one step up toward that seat.
 *  RELIEF   after "멀미 나요" (also while driving): recirculation off, fan max, air to the face,
 *           HVAC on, window cracked only at low speed; ends by itself after [Policy.RELIEF_MAX_MIN] minutes
 *           (settings go back, but the seat stays marked "멀미 나요" until someone says it is over).
 */
class CareBrain(
    private val clock: () -> Long,
    private val speak: (String) -> Unit,
    private val onDesired: (Desired) -> Unit
) {
    private var gear = Gear.UNKNOWN
    private var speedKmh = 0.0
    private var occupancy: Map<String, Boolean?> = emptyMap()
    private var designated: Set<String> = emptySet()
    private var destination = ""
    private var history: List<HistoryEvent> = emptyList()

    private var mode = CareMode.IDLE
    private var reliefStartMs = 0L
    private var reliefSeat: Seat? = null

    /** The seat still marked "멀미 나요" after the relief ran out by itself; nobody has said it is over. */
    private var lingerSeat: Seat? = null

    /** When Gleo AI was asked "which seat?" and no answer has come yet; 0 = not asking. */
    private var seatAskSinceMs = 0L

    private var tripActive = false
    private var tripStartMs = 0L
    private var demoOffsetMin = 0
    private var parkedSinceMs = 0L

    private var suggestion: RestStop? = null
    private var suggestionWhy = ""
    private var offered = false
    private var waypoints: List<RestStop> = emptyList()

    private var caption = ""
    private var captionUntilMs = 0L

    var desired: Desired = Desired()
        private set

    fun init() {
        designated = Store.designated
        destination = Store.destination.ifBlank { DemoNavi.destinations.first() }
        history = Store.history()
        refresh()
    }

    // ------------------------------------------------------------------ signals

    fun onGear(g: Gear) {
        gear = g
        if (g == Gear.PARK) {
            if (parkedSinceMs == 0L) parkedSinceMs = clock()
        } else {
            parkedSinceMs = 0L
        }
        refresh()
    }

    fun onSpeedMps(mps: Double) {
        speedKmh = (mps * 3.6).coerceAtLeast(0.0)
        refresh()
    }

    fun onOccupancy(map: Map<String, Boolean?>) {
        occupancy = map
        refresh()
    }

    fun tick() {
        val now = clock()
        if (tripActive && gear == Gear.PARK && parkedSinceMs > 0L &&
            now - parkedSinceMs >= Policy.TRIP_END_PARK_SEC * 1000L
        ) endTrip()
        if (mode == CareMode.RELIEF && now - reliefStartMs >= Policy.RELIEF_MAX_MIN * 60_000L) {
            val still = reliefSeat
            endRelief("10분이 지나 원래 설정으로 되돌렸어요")
            lingerSeat = still
        }
        if (seatAskSinceMs > 0L && mode != CareMode.RELIEF && now - seatAskSinceMs >= Policy.SEAT_ASK_SEC * 1000L) {
            // nobody named the seat in time: relieve every seat the car reports (no single target)
            seatAskSinceMs = 0L
            startRelief(null)
        }
        offerRestInAdvance()
        if (caption.isNotEmpty() && now >= captionUntilMs) caption = ""
        refresh()
    }

    // ------------------------------------------------------------------ user actions

    fun toggleDesignate(seatKey: String) {
        if (lingerSeat?.key == seatKey) {
            // tapping the seat that is still marked from the relief clears the mark
            lingerSeat = null
            refresh()
            return
        }
        designated = if (seatKey in designated) designated - seatKey else designated + seatKey
        Store.designated = designated
        refresh()
    }

    fun setDestination(name: String) {
        destination = name
        Store.destination = name
        offered = false
        if (mode != CareMode.RELIEF) {
            suggestion = null
            suggestionWhy = ""
        }
        refresh()
    }

    /** "멀미 나요": always possible, also while driving. The window still only opens at low speed. */
    fun reliefPressed() {
        if (mode == CareMode.RELIEF) return
        cancelSeatAsk()
        startRelief()
        refresh()
    }

    fun reliefEnd() {
        cancelSeatAsk()
        lingerSeat = null
        if (mode == CareMode.RELIEF) endRelief("원래 설정으로 되돌렸어요")
        refresh()
    }

    /**
     * "멀미 나요" by voice, with the seat when the driver named it.
     * Seat named -> relief for that seat (also while relieving: the target moves there).
     * No seat -> the designated seat, else the only person sitting (front passenger included), else ask which seat.
     * Nobody reported as sitting -> relief with no single seat.
     */
    fun voiceRelief(seat: Seat?): VoiceReliefResult {
        if (mode == CareMode.RELIEF) {
            if (seat != null && seat != reliefSeat) {
                reliefSeat = seat
                refresh()
                return VoiceReliefResult.RETARGETED
            }
            return VoiceReliefResult.ALREADY
        }
        val people = occupiedPassengers()
        val chosen = seat ?: lingerSeat ?: activeDesignated().firstOrNull() ?: people.singleOrNull()
        if (chosen != null || people.isEmpty()) {
            seatAskSinceMs = 0L
            startRelief(chosen)
            refresh()
            return VoiceReliefResult.STARTED
        }
        seatAskSinceMs = clock()
        say("어느 좌석인가요? 말씀해 주세요", Policy.SEAT_ASK_SEC * 1000L)
        refresh()
        return VoiceReliefResult.ASKING
    }

    fun addWaypoint(id: String) {
        val stop = DemoNavi.stops.firstOrNull { it.id == id } ?: return
        if (waypoints.none { it.id == id }) waypoints = waypoints + stop
        say("${stop.name}을(를) 경유지로 추가했어요 (시연)", 6000)
        refresh()
    }

    // ------------------------------------------------------------------ demo tools

    fun demoAddMinutes(m: Int) {
        demoOffsetMin += m
        refresh()
    }

    fun demoSeedHistory() {
        history = DemoNavi.sampleHistory(clock())
        Store.setHistory(history)
        refresh()
    }

    fun demoClearHistory() {
        history = emptyList()
        Store.setHistory(history)
        offered = false
        refresh()
    }

    // ------------------------------------------------------------------ internals

    private fun say(text: String, forMs: Long) {
        caption = text
        captionUntilMs = clock() + forMs
    }

    private fun activeDesignated(): List<Seat> =
        Seat.entries.filter { it.key in designated && occupancy[it.key] != false }

    /** Everyone but the driver whom the car reports as sitting. */
    private fun occupiedPassengers(): List<Seat> =
        Seat.entries.filter { it != Seat.ROW_1_LEFT && occupancy[it.key] == true }

    private fun cancelSeatAsk() {
        if (seatAskSinceMs > 0L) {
            seatAskSinceMs = 0L
            caption = ""
        }
    }

    private fun reliefTarget(): Seat? {
        lingerSeat?.let { return it }
        activeDesignated().firstOrNull()?.let { return it }
        // nobody designated: the first occupied rear seat, otherwise unspecified (all reported areas)
        return listOf(
            Seat.ROW_2_LEFT, Seat.ROW_2_CENTER, Seat.ROW_2_RIGHT,
            Seat.ROW_3_LEFT, Seat.ROW_3_CENTER, Seat.ROW_3_RIGHT
        ).firstOrNull { occupancy[it.key] == true }
    }

    private fun elapsedMin(): Int =
        if (tripActive) ((clock() - tripStartMs) / 60_000L).toInt() + demoOffsetMin else 0

    private fun startTrip() {
        tripActive = true
        tripStartMs = clock()
        demoOffsetMin = 0
        offered = false
        suggestion = null
        suggestionWhy = ""
        waypoints = emptyList()
    }

    private fun endTrip() {
        lingerSeat = null
        if (mode == CareMode.RELIEF) endRelief("주행이 끝나 원래 설정으로 되돌렸어요")
        tripActive = false
        parkedSinceMs = 0L
        offered = false
        suggestion = null
        suggestionWhy = ""
        waypoints = emptyList()
    }

    private fun startRelief(seat: Seat? = reliefTarget()) {
        mode = CareMode.RELIEF
        reliefStartMs = clock()
        reliefSeat = seat
        lingerSeat = null
        if (tripActive) {
            val ev = HistoryEvent(clock(), elapsedMin(), seat?.key ?: Seat.ROW_2_LEFT.key, destination)
            Store.addEvent(ev)
            history = Store.history()
        }
        suggestion = Policy.pickStop(DemoNavi.stops)
        suggestionWhy = "멀미가 났어요. 가까운 휴게소에서 잠깐 쉬어 가요"
        offered = true
        val msg = "화면 대신 먼 곳을 보세요"
        say(msg, 15_000)
        speak(msg)
    }

    private fun endRelief(message: String) {
        mode = CareMode.IDLE
        reliefSeat = null
        say(message, 6000)
    }

    private fun offerRestInAdvance() {
        if (!tripActive || offered || mode == CareMode.RELIEF) return
        val after = Policy.restAfterMin(history, destination) ?: return
        if (elapsedMin() < after) return
        val stop = Policy.pickStop(DemoNavi.stops) ?: return
        suggestion = stop
        suggestionWhy = "같은 목적지에서 멀미가 났던 시간보다 5분 앞서 알려드려요. 미리 쉬어 가요"
        offered = true
        val msg = "곧 멀미가 날 수 있는 시간이에요. ${stop.name}에서 쉬어 갈까요?"
        say(msg, 15_000)
        speak(msg)
    }

    private fun computeDesired(): Desired {
        if (mode == CareMode.RELIEF) {
            return Desired(
                recircOff = true,
                fan = FanWant.MAX,
                faceDirection = true,
                powerOn = true,
                windowOpen = Policy.windowAllowed(gear, speedKmh),
                target = reliefSeat
            )
        }
        if (tripActive) {
            val seat = activeDesignated().firstOrNull()
            if (seat != null) return Desired(recircOff = true, fan = FanWant.PLUS_ONE, target = seat)
        }
        return Desired()
    }

    private fun refresh() {
        if (!tripActive && Policy.isDriving(gear, speedKmh)) startTrip()

        val d = computeDesired()
        if (d != desired) {
            desired = d
            onDesired(d)
        }
        mode = when {
            mode == CareMode.RELIEF -> CareMode.RELIEF
            !d.isIdle -> CareMode.PREVENT
            else -> CareMode.IDLE
        }
        val elapsed = elapsedMin()
        val restAfter = Policy.restAfterMin(history, destination)
        Live.update {
            it.copy(
                gear = gear,
                speedKmh = speedKmh,
                occupancy = occupancy,
                designated = designated,
                reliefSeat = if (mode == CareMode.RELIEF) reliefSeat?.key else lingerSeat?.key,
                mode = mode,
                caption = caption,
                tripActive = tripActive,
                tripElapsedMin = elapsed,
                destination = destination,
                restAfterMin = restAfter,
                suggestion = suggestion,
                suggestionWhy = suggestionWhy,
                waypoints = waypoints,
                history = history
            )
        }
    }
}

/** What [CareBrain.voiceRelief] did, so the voice call can answer Gleo AI. */
enum class VoiceReliefResult { STARTED, ASKING, RETARGETED, ALREADY }
