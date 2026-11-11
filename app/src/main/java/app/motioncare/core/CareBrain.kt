// ver 1.11.0 | 2026-11-11 22:15
package app.motioncare.core

import app.motioncare.data.DemoNavi
import app.motioncare.data.Live
import app.motioncare.data.Store

/**
 * The decision logic. No Android framework types except the on-device [Store] and the shared [Live]
 * state. Everything here runs on the main thread (the service posts all signals to it).
 *
 * Seats are chosen by voice (one seat or a group: "뒷자리 모두", "2열 전체" ...), or on the screen by tapping seats.
 * The screen button works only once at least one seat is picked: it never guesses the seats.
 *
 *  RELIEF   after "멀미 나요" (also while driving): recirculation off, fan max, air to the face, HVAC on.
 *           The window is never opened by the app (rain, snow, wind, smell or dust outside cannot be known):
 *           the guidance suggests opening it a little when it is fine outside. Ends by itself after [Policy.RELIEF_MAX_MIN] minutes
 *           (settings go back, but the seats stay marked "멀미 나요" until someone says it is over).
 *  PREVENT  only when [preventTargets] names seats.
 */
class CareBrain(
    private val clock: () -> Long,
    private val speak: (String) -> Unit,
    private val onDesired: (Desired) -> Unit,
    /** route changes for the navigation (NaviHelper) */
    private val onNavi: (NaviCmd) -> Unit = {}
) {
    private var gear = Gear.UNKNOWN
    private var speedKmh = 0.0
    private var occupancy: Map<String, Boolean?> = emptyMap()
    private var destination = ""
    private var history: List<HistoryEvent> = emptyList()

    private var mode = CareMode.IDLE
    private var reliefStartMs = 0L

    /** The seats the relief looks after; empty = the whole cabin (no seat known). */
    private var reliefSeats: Set<Seat> = emptySet()

    /** The seats still marked "멀미 나요" after the relief ran out by itself; nobody has said it is over. */
    private var lingerSeats: Set<Seat> = emptySet()

    /** Seats tapped on the screen while no relief runs; the "멀미 나요" button relieves exactly these. */
    private var picked: Set<Seat> = emptySet()

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

    /** waypoint names the navigation reports in route order; null while it reports none (no route, or not connected) */
    private var naviWaypoints: List<String>? = null

    /** destination the navigation reports; null = none, then the demo destination chosen in the app is used */
    private var naviDestination: String? = null

    private var pickedRest: String? = null

    /** last position the navigation reported */
    private var locText = ""
    private var locLat: Double? = null
    private var locLon: Double? = null

    /** where this trip started */
    private var originText = ""
    private var originLat: Double? = null
    private var originLon: Double? = null

    private var caption = ""
    private var captionUntilMs = 0L

    /** The window hint is written on the screen once the first guidance has been shown for a while. */
    private var windowHintPending = false

    /** The relief guidance is spoken [SPEAK_DELAY_MS] after the relief starts, after Gleo AI's own answer. */
    private var speechPending = false

    var desired: Desired = Desired()
        private set

    fun init() {
        destination = Store.destination.ifBlank { DemoNavi.destinations.first() }
        // the destination used for records and advance rests: the navigation's when it has one
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
            val still = reliefSeats
            endRelief("10분이 지나 원래 설정으로 되돌렸어요")
            lingerSeats = still
        }
        if (seatAskSinceMs > 0L && mode != CareMode.RELIEF && now - seatAskSinceMs >= Policy.SEAT_ASK_SEC * 1000L) {
            // nobody named the seat in time: no guess; the seat can still be said (or the button pressed) at any time
            seatAskSinceMs = 0L
            say("어느 자리인지 말씀해 주세요", 15_000)
        }
        if (speechPending && mode == CareMode.RELIEF && now - reliefStartMs >= SPEAK_DELAY_MS) {
            speechPending = false
            speak("$RELIEF_GUIDE. $WINDOW_HINT")
        }
        if (windowHintPending && mode == CareMode.RELIEF && now - reliefStartMs >= FIRST_GUIDE_MS) {
            windowHintPending = false
            say(WINDOW_HINT, 10_000)
        }
        offerRestInAdvance()
        if (caption.isNotEmpty() && now >= captionUntilMs) caption = ""
        refresh()
    }

    // ------------------------------------------------------------------ user actions

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

    /**
     * A seat tapped on the screen. Ignored while relieving (the seats are changed by voice then).
     * Seats still marked after a relief ran out are toggled there; otherwise the tap picks or unpicks the seat.
     */
    fun tapSeat(seatKey: String) {
        if (mode == CareMode.RELIEF) return
        val seat = Seat.fromKey(seatKey) ?: return
        if (seat == Seat.ROW_1_LEFT) return
        if (lingerSeats.isNotEmpty()) {
            lingerSeats = if (seat in lingerSeats) lingerSeats - seat else lingerSeats + seat
        } else {
            picked = if (seat in picked) picked - seat else picked + seat
        }
        refresh()
    }

    /** "멀미 나요" on the screen: works only when seats are picked (or still marked); relieves exactly those. */
    fun reliefPressed() {
        if (mode == CareMode.RELIEF) return
        val seats = lingerSeats.ifEmpty { picked }
        if (seats.isEmpty()) return
        cancelSeatAsk()
        startRelief(seats)
        refresh()
    }

    fun reliefEnd() {
        cancelSeatAsk()
        lingerSeats = emptySet()
        if (mode == CareMode.RELIEF) endRelief("원래 설정으로 되돌렸어요")
        refresh()
    }

    /**
     * "멀미 나요" by voice, with the seats when the driver named them (one seat or a group).
     * Seats named -> relief for those seats (also while relieving: the relief moves there).
     * None named -> the seats still marked, else the seats picked on the screen, else the only passenger sitting,
     * else ask which seat.
     * Nobody reported as sitting -> relief for the whole cabin.
     */
    fun voiceRelief(seats: Set<Seat>): VoiceReliefResult {
        val named = sitting(seats)
        if (seats.isNotEmpty() && named.isEmpty()) {
            // a seat was said but none of it is in this car (e.g. the 3rd row of a two-row car): ask again
            if (mode != CareMode.RELIEF) seatAskSinceMs = clock()
            say("다시 말씀해 주시겠어요? 어느 자리인가요?", Policy.SEAT_ASK_SEC * 1000L)
            refresh()
            return VoiceReliefResult.ASKING
        }
        if (mode == CareMode.RELIEF) {
            if (named.isNotEmpty() && named != reliefSeats) {
                reliefSeats = named
                refresh()
                return VoiceReliefResult.RETARGETED
            }
            return VoiceReliefResult.ALREADY
        }
        val people = occupiedPassengers()
        val chosen = named.ifEmpty { lingerSeats }.ifEmpty { picked }.ifEmpty { if (people.size == 1) people else emptySet() }
        if (chosen.isNotEmpty() || people.isEmpty()) {
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

    /** Kept for the home card: one touch puts the stop on the route. */
    fun addWaypoint(id: String) {
        restAdd(id)
    }

    // ------------------------------------------------------------------ rest areas (waypoints)

    /** A rest card tapped: picks it (a second tap on the picked card unpicks it). Nothing changes on the route. */
    fun pickRest(id: String) {
        pickedRest = if (pickedRest == id) null else id
        refresh()
    }

    fun restAdd(id: String): RestOutcome {
        val stop = stopById(id) ?: return RestOutcome.NOT_FOUND
        return finish(doAdd(stop))
    }

    /** Puts [id] on the route in place of the rest area the app put there (the later one when there are two). */
    fun restReplace(id: String): RestOutcome {
        val stop = stopById(id) ?: return RestOutcome.NOT_FOUND
        val old = waypoints.lastOrNull() ?: return finish(doAdd(stop))
        return finish(doReplace(old, stop))
    }

    fun restRemove(id: String): RestOutcome {
        val stop = waypoints.firstOrNull { it.id == id } ?: stopById(id) ?: return RestOutcome.NOT_FOUND
        return finish(doRemove(stop))
    }

    /**
     * "쉬어 갈 곳" by voice. [place] is the rest area as said ("평택", "평택휴게소"), null when not said.
     * Add without a place = the suggested stop; remove without a place = ours when there is just one;
     * replace without a place cannot be told and is asked.
     */
    fun voiceRest(action: RestAction, place: String?): Pair<RestOutcome, String> {
        val ahead = DemoNavi.stops
        val named = RestPlan.find(place, waypoints + ahead)
        if (!place.isNullOrBlank() && named == null) return RestOutcome.NOT_FOUND to place
        val result = when (action) {
            RestAction.ADD -> {
                val stop = named ?: suggestion ?: Policy.pickStop(ahead)
                if (stop == null) RestOutcome.NOT_FOUND to "" else doAdd(stop) to stop.name
            }
            RestAction.REMOVE -> {
                val stop = named ?: waypoints.singleOrNull()
                when {
                    stop != null -> doRemove(stop) to stop.name
                    waypoints.isEmpty() -> RestOutcome.NOTHING_OURS to ""
                    else -> RestOutcome.ASK_PLACE to ""
                }
            }
            RestAction.REPLACE -> {
                val old = waypoints.lastOrNull()
                when {
                    named == null -> RestOutcome.ASK_PLACE to ""
                    old == null -> doAdd(named) to named.name
                    else -> doReplace(old, named) to named.name
                }
            }
        }
        finish(result.first)
        return result
    }

    /** The navigation's waypoints in route order ([names]), or null when it reports none. */
    fun onNaviWaypoints(names: List<String>?) {
        naviWaypoints = names
        refresh()
    }

    fun onNaviDestination(name: String?) {
        naviDestination = name?.takeIf { it.isNotBlank() }
        offered = false
        refresh()
    }

    fun onLocation(address: String?, lat: Double, lon: Double) {
        locText = PlaceText.short(address)
        locLat = lat
        locLon = lon
        if (tripActive && originLat == null) {
            originText = locText
            originLat = lat
            originLon = lon
        }
        refresh()
    }

    private fun stopById(id: String): RestStop? =
        DemoNavi.stops.firstOrNull { it.id == id } ?: waypoints.firstOrNull { it.id == id }

    private fun onRoute(stop: RestStop): Boolean =
        waypoints.any { it.id == stop.id } || naviWaypoints?.any { RestPlan.same(it, stop.name) } == true

    private fun routeCount(): Int = RestPlan.names(waypoints, naviWaypoints).size

    private fun doAdd(stop: RestStop): RestOutcome {
        if (onRoute(stop)) return RestOutcome.ALREADY
        val index = routeCount()
        if (index >= RestPlan.MAX) return RestOutcome.FULL
        waypoints = waypoints + stop
        naviWaypoints = naviWaypoints?.plus(stop.name)
        onNavi(NaviCmd.Add(stop, index))
        say("${stop.name}휴게소를 경유지로 넣었어요", 6000)
        return RestOutcome.DONE
    }

    private fun doRemove(stop: RestStop): RestOutcome {
        val index = RestPlan.indexOf(stop.name, waypoints, naviWaypoints)
        if (index < 0 || waypoints.none { it.id == stop.id }) return RestOutcome.NOT_FOUND
        waypoints = waypoints.filter { it.id != stop.id }
        naviWaypoints = naviWaypoints?.filterIndexed { i, _ -> i != index }
        onNavi(NaviCmd.Remove(index, stop.name))
        say("${stop.name}휴게소를 경유지에서 뺐어요", 6000)
        return RestOutcome.DONE
    }

    private fun doReplace(old: RestStop, new: RestStop): RestOutcome {
        if (onRoute(new)) return RestOutcome.ALREADY
        val index = RestPlan.indexOf(old.name, waypoints, naviWaypoints)
        if (index < 0) return doAdd(new)
        waypoints = waypoints.map { if (it.id == old.id) new else it }
        naviWaypoints = naviWaypoints?.mapIndexed { i, n -> if (i == index) new.name else n }
        onNavi(NaviCmd.Remove(index, old.name))
        onNavi(NaviCmd.Add(new, index))
        say("${old.name} 대신 ${new.name}휴게소를 경유지로 넣었어요", 6000)
        return RestOutcome.DONE
    }

    private fun finish(outcome: RestOutcome): RestOutcome {
        when (outcome) {
            RestOutcome.DONE -> pickedRest = null
            RestOutcome.FULL -> say("경유지가 꽉 찼어요. 내비 경유지는 2개까지예요", 6000)
            RestOutcome.ALREADY -> say("이미 경유지에 있어요", 4000)
            RestOutcome.NOT_FOUND -> say("앞쪽에 그런 휴게소가 없어요", 5000)
            RestOutcome.NOTHING_OURS -> say("차멀미 케어가 넣은 경유지가 없어요", 5000)
            RestOutcome.ASK_PLACE -> say("어느 휴게소인가요?", 8000)
        }
        refresh()
        return outcome
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

    /** The destination: the navigation's when it has one, otherwise the demo destination chosen in the app. */
    private fun dest(): String = naviDestination ?: destination

    private fun say(text: String, forMs: Long) {
        caption = text
        captionUntilMs = clock() + forMs
    }

    /** Everyone but the driver whom the car reports as sitting. */
    private fun occupiedPassengers(): Set<Seat> =
        Seat.entries.filter { it != Seat.ROW_1_LEFT && occupancy[it.key] == true }.toSet()

    /**
     * Of the named seats, those this car has (the seats its occupancy report lists; all when it lists none), then of
     * those the ones not reported as empty (a group like "뒷자리 모두" names seats the car may not have). When the car
     * reports all of them as empty, they are used as said: a seat sensor can miss a child in a child seat.
     * Empty when none of the named seats is in this car.
     */
    private fun sitting(seats: Set<Seat>): Set<Seat> {
        val inCar = if (occupancy.isEmpty()) seats else seats.filter { it.key in occupancy }.toSet()
        val kept = inCar.filter { occupancy[it.key] != false }.toSet()
        return kept.ifEmpty { inCar }
    }

    /** Seats looked after while driving with no relief on. None: the app changes nothing until someone says "멀미 나요". */
    private fun preventTargets(): Set<Seat> = emptySet()

    private fun cancelSeatAsk() {
        if (seatAskSinceMs > 0L) {
            seatAskSinceMs = 0L
            caption = ""
        }
    }

    private fun elapsedMin(): Int =
        if (tripActive) ((clock() - tripStartMs) / 60_000L).toInt() + demoOffsetMin else 0

    private fun startTrip() {
        tripActive = true
        tripStartMs = clock()
        originText = locText
        originLat = locLat
        originLon = locLon
        demoOffsetMin = 0
        offered = false
        suggestion = null
        suggestionWhy = ""
        waypoints = emptyList()
    }

    private fun endTrip() {
        lingerSeats = emptySet()
        picked = emptySet()
        pickedRest = null
        originText = ""
        originLat = null
        originLon = null
        if (mode == CareMode.RELIEF) endRelief("주행이 끝나 원래 설정으로 되돌렸어요")
        tripActive = false
        parkedSinceMs = 0L
        offered = false
        suggestion = null
        suggestionWhy = ""
        waypoints = emptyList()
    }

    private fun startRelief(seats: Set<Seat>) {
        mode = CareMode.RELIEF
        reliefStartMs = clock()
        reliefSeats = seats
        lingerSeats = emptySet()
        picked = emptySet()
        if (tripActive) {
            val ev = HistoryEvent(
                clock(), elapsedMin(), SeatKeys.join(seats), dest(),
                origin = originText, originLat = originLat, originLon = originLon, place = locText
            )
            Store.addEvent(ev)
            history = Store.history()
        }
        suggestion = Policy.pickStop(DemoNavi.stops)
        suggestionWhy = "멀미가 났어요. 가까운 휴게소에서 잠깐 쉬어 가요"
        offered = true
        say(RELIEF_GUIDE, FIRST_GUIDE_MS)
        windowHintPending = true
        speechPending = true
    }

    private fun endRelief(message: String) {
        mode = CareMode.IDLE
        reliefSeats = emptySet()
        windowHintPending = false
        speechPending = false
        say(message, 6000)
    }

    private fun offerRestInAdvance() {
        if (!tripActive || offered || mode == CareMode.RELIEF) return
        val after = Policy.restAfterMin(history, dest(), originLat, originLon) ?: return
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
                windowOpen = false,
                targets = reliefSeats
            )
        }
        if (tripActive) {
            val seats = preventTargets()
            if (seats.isNotEmpty()) return Desired(recircOff = true, fan = FanWant.PLUS_ONE, targets = seats)
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
        val restAfter = Policy.restAfterMin(history, dest(), originLat, originLon)
        val marked = if (mode == CareMode.RELIEF) reliefSeats else lingerSeats
        Live.update {
            it.copy(
                gear = gear,
                speedKmh = speedKmh,
                occupancy = occupancy,
                reliefSeats = marked.map { s -> s.key }.toSet(),
                reliefWhole = mode == CareMode.RELIEF && reliefSeats.isEmpty(),
                pickedSeats = if (mode == CareMode.RELIEF) emptySet() else picked.map { s -> s.key }.toSet(),
                canRelieve = mode != CareMode.RELIEF && (lingerSeats.isNotEmpty() || picked.isNotEmpty()),
                mode = mode,
                caption = caption,
                tripActive = tripActive,
                tripElapsedMin = elapsed,
                destination = dest(),
                destinationFromNavi = naviDestination != null,
                origin = originText,
                naviWaypoints = naviWaypoints,
                pickedRest = pickedRest,
                restAfterMin = restAfter,
                suggestion = suggestion,
                suggestionWhy = suggestionWhy,
                waypoints = waypoints,
                history = history
            )
        }
    }
}

private const val FIRST_GUIDE_MS = 8_000L
private const val SPEAK_DELAY_MS = 5_000L
private const val RELIEF_GUIDE = "화면 대신 먼 곳을 보세요"
private const val WINDOW_HINT = "밖이 괜찮으면 창문을 조금 열어 보세요"

/** What [CareBrain.voiceRelief] did, so the voice call can answer Gleo AI. */
enum class VoiceReliefResult { STARTED, ASKING, RETARGETED, ALREADY }
