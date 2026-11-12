// ver 1.16.0 | 2026-11-12 12:25
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
 *
 * Rest areas on the route: the navigation (checked on the emulator, NaviHelper 3.0.5) takes about 8 seconds for one
 * change (it cancels the route, shows the new one for 5 seconds, then guides again) and silently drops a second
 * request sent in that time. So one change is sent at a time, the next one only after the navigation reports the
 * first, and a stop is shown as on the route only once the navigation reports it.
 */
class CareBrain(
    private val clock: () -> Long,
    private val speak: (String) -> Unit,
    private val onDesired: (Desired) -> Unit,
    /** route changes for the navigation (NaviHelper); false = could not be sent */
    private val onNavi: (NaviCmd) -> Boolean = { false }
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

    /** the route change waiting for the navigation */
    private var job: RestJob? = null

    /** places nearby, fastest by road first, and how far the search got (see [onNearby]) */
    private var nearby: List<RestStop> = emptyList()
    private var nearbyState = NearbyState.NO_POSITION

    /** the place this app set as the navigation's destination (when there was no route) */
    private var destRest: RestStop? = null

    /** [destRest] was found by the destination's name (after the app started again), not set by this app's request */
    private var destByName = false

    /** what the rest screen shows about the last change that failed, until [workNoteUntilMs] */
    private var workNote: RestWork? = null
    private var workNoteUntilMs = 0L

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
        checkJobTimeout(now)
        if (workNote != null && now >= workNoteUntilMs) workNote = null
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

    /**
     * The card's button. Guiding to a destination: the stop goes on the route as a waypoint. Not guiding (or guiding
     * to a stop this app set as the destination): the navigation is asked to guide to the stop.
     */
    fun restAdd(id: String): RestOutcome {
        val stop = stopById(id) ?: return RestOutcome.NOT_FOUND
        if (job == null && (!routing() || destIsOurs())) return finish(doRoute(stop))
        return finish(doAdd(stop))
    }

    /** Puts [id] on the route in place of the stop the app put there (the later one when there are two). */
    fun restReplace(id: String): RestOutcome {
        val stop = stopById(id) ?: return RestOutcome.NOT_FOUND
        if (job == null && (!routing() || destIsOurs())) return finish(doRoute(stop))
        val old = waypoints.lastOrNull() ?: return finish(doAdd(stop))
        return finish(doReplace(old, stop))
    }

    /** Takes [id] off the route; when it is the destination this app set, the guidance ends. */
    fun restRemove(id: String): RestOutcome {
        if (job == null && destIsOurs() && destRest?.id == id) return finish(doCancel())
        val stop = waypoints.firstOrNull { it.id == id } ?: stopById(id) ?: return RestOutcome.NOT_FOUND
        return finish(doRemove(stop))
    }

    /**
     * "쉬어 갈 곳" by voice. [place] is the place as said ("평택", "평택휴게소", "죽전 졸음쉼터"), null when not said.
     * Add without a place = the fastest place nearby; remove without a place = ours when there is just one;
     * replace without a place cannot be told and is asked.
     */
    fun voiceRest(action: RestAction, place: String?): Pair<RestOutcome, String> {
        val named = RestPlan.find(place, waypoints + listOfNotNull(destRest) + nearby)
        if (!place.isNullOrBlank() && named == null) return RestOutcome.NOT_FOUND to place
        // one change at a time: the navigation drops a request sent while it is still changing the route
        if (job != null) return finish(RestOutcome.BUSY) to ""
        val guideMode = !routing() || destIsOurs()
        val result = when (action) {
            RestAction.ADD -> {
                val stop = named ?: nearby.firstOrNull()
                when {
                    stop == null -> RestOutcome.NOT_FOUND to ""
                    guideMode -> doRoute(stop) to stop.name
                    else -> doAdd(stop) to stop.name
                }
            }
            RestAction.REMOVE -> {
                val d = destRest
                when {
                    destIsOurs() && d != null && (named == null || named.id == d.id) -> doCancel() to d.name
                    else -> {
                        val stop = named ?: waypoints.singleOrNull()
                        when {
                            stop != null -> doRemove(stop) to stop.name
                            waypoints.isEmpty() -> RestOutcome.NOTHING_OURS to ""
                            else -> RestOutcome.ASK_PLACE to ""
                        }
                    }
                }
            }
            RestAction.REPLACE -> {
                val old = waypoints.lastOrNull()
                when {
                    named == null -> RestOutcome.ASK_PLACE to ""
                    guideMode -> doRoute(named) to named.name
                    old == null -> doAdd(named) to named.name
                    else -> doReplace(old, named) to named.name
                }
            }
        }
        finish(result.first)
        return result
    }

    /**
     * The places nearby, fastest by road first (the service asks the navigation for the road time), and how far the
     * search got. A list stays until a new one is complete, so the screen does not jump while it is worked out.
     */
    fun onNearby(state: NearbyState, stops: List<RestStop>) {
        nearbyState = state
        if (state == NearbyState.READY || state == NearbyState.NONE) nearby = stops
        // a destination found by name: the same place in the list has the road time (and the side of the road)
        if (destByName && job == null) NearbyPlan.destinationStop(naviDestination, nearby, emptyList(), null, null)?.let { destRest = it }
        refresh()
    }

    /**
     * The navigation's waypoints in route order ([names]); null while it is not guiding (no route, or the route is being
     * changed). A list is the navigation's own word: ours not in it are dropped, and a change waiting for it is checked.
     */
    fun onNaviWaypoints(names: List<String>?) {
        naviWaypoints = names
        if (names != null) {
            waypoints = waypoints.filter { w -> names.any { RestPlan.same(it, w.name) } }
            checkJob(names)
        }
        refresh()
    }

    fun onNaviDestination(name: String?) {
        val now = name?.takeIf { it.isNotBlank() }
        // the advance rest offer is made once per destination (this is called again on every refresh)
        if (now != naviDestination && now != null) offered = false
        naviDestination = now
        if (now == null && job == null) {
            destRest = null
            destByName = false
        }
        // after the app starts again it no longer knows it set the destination: a destination with exactly the
        // name this app gives the navigation ("안성휴게소", "죽전 졸음쉼터") is taken as set by this app
        if (now != null && job == null && !destIsOurs()) {
            val found = NearbyPlan.destinationStop(now, nearby, DemoNavi.catalogAreas(), locLat, locLon)
            if (found != null) {
                destRest = found
                destByName = true
            }
        }
        checkDestJob()
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
        nearby.firstOrNull { it.id == id } ?: waypoints.firstOrNull { it.id == id } ?: destRest?.takeIf { it.id == id }

    /** The navigation is guiding to the place this app set as its destination. */
    private fun destIsOurs(): Boolean {
        val d = destRest ?: return false
        return naviDestination?.let { RestPlan.same(it, d.name) } == true
    }

    private fun onRoute(stop: RestStop): Boolean =
        waypoints.any { it.id == stop.id } || naviWaypoints?.any { RestPlan.same(it, stop.name) } == true

    private fun routeCount(): Int = RestPlan.names(waypoints, naviWaypoints).size

    private fun doAdd(stop: RestStop): RestOutcome {
        if (job != null) return RestOutcome.BUSY
        if (onRoute(stop)) return RestOutcome.ALREADY
        val index = routeCount()
        if (index >= RestPlan.MAX) return RestOutcome.FULL
        val route = RestPlan.names(waypoints, naviWaypoints)
        return begin(
            RestJob(RestAction.ADD, stop, null, index, removing = false),
            NaviCmd.Add(stop, index),
            RestWork("${stop.name} 넣는 중", line(route + stop.name)),
            "${stop.label}를 내비에 넣고 있어요"
        )
    }

    private fun doRemove(stop: RestStop): RestOutcome {
        if (job != null) return RestOutcome.BUSY
        val index = RestPlan.indexOf(stop.name, waypoints, naviWaypoints)
        if (index < 0 || waypoints.none { it.id == stop.id }) return RestOutcome.NOT_FOUND
        val route = RestPlan.names(waypoints, naviWaypoints)
        return begin(
            RestJob(RestAction.REMOVE, stop, null, index, removing = true),
            NaviCmd.Remove(index, stop.name),
            RestWork("${stop.name} 빼는 중", line(route.filterNot { RestPlan.same(it, stop.name) })),
            "${stop.label}를 내비에서 빼고 있어요"
        )
    }

    /** Takes [old] off first; [new] is sent only after the navigation reports [old] gone (see [checkJob]). */
    private fun doReplace(old: RestStop, new: RestStop): RestOutcome {
        if (job != null) return RestOutcome.BUSY
        if (onRoute(new)) return RestOutcome.ALREADY
        val index = RestPlan.indexOf(old.name, waypoints, naviWaypoints)
        if (index < 0) return doAdd(new)
        val route = RestPlan.names(waypoints, naviWaypoints)
        return begin(
            RestJob(RestAction.REPLACE, new, old, index, removing = true),
            NaviCmd.Remove(index, old.name),
            RestWork("${withRo(new.name)} 바꾸는 중", line(route.map { if (RestPlan.same(it, old.name)) new.name else it })),
            "${old.name} 대신 ${new.label}를 내비에 넣고 있어요"
        )
    }

    /** Not guiding (or guiding to our stop): the navigation is asked to guide to [stop]. */
    private fun doRoute(stop: RestStop): RestOutcome {
        if (job != null) return RestOutcome.BUSY
        if (destIsOurs() && destRest?.id == stop.id) return RestOutcome.ALREADY
        return begin(
            RestJob(RestAction.ADD, stop, null, 0, removing = false, mode = JobMode.ROUTE),
            NaviCmd.Route(stop),
            RestWork("${stop.name} 안내 준비 중", "출발 → ${stop.label}"),
            "${stop.label}로 안내를 시작할게요"
        )
    }

    /** Ends the guidance to the stop this app set as the destination. */
    private fun doCancel(): RestOutcome {
        if (job != null) return RestOutcome.BUSY
        val d = destRest ?: return RestOutcome.NOTHING_OURS
        return begin(
            RestJob(RestAction.REMOVE, d, null, 0, removing = true, mode = JobMode.CANCEL),
            NaviCmd.Cancel,
            RestWork("안내 끝내는 중", "${d.label} 안내를 끝내요"),
            "${d.label} 안내를 끝낼게요"
        )
    }

    /** A guidance change waiting for the navigation's destination ([onNaviDestination]). */
    private fun checkDestJob() {
        val j = job ?: return
        when (j.mode) {
            JobMode.ROUTE -> if (naviDestination?.let { RestPlan.same(it, j.stop.name) } == true) {
                job = null
                destRest = j.stop
                destByName = false
                waypoints = emptyList()
                pickedRest = null
                say("${j.stop.label}로 안내를 시작했어요", 6000)
            }
            JobMode.CANCEL -> if (naviDestination == null) {
                job = null
                destRest = null
                destByName = false
                pickedRest = null
                say("${j.stop.label} 안내를 끝냈어요", 6000)
            }
            JobMode.WAYPOINT -> {}
        }
    }

    private fun begin(j: RestJob, cmd: NaviCmd, work: RestWork, text: String): RestOutcome {
        workNote = null
        if (!onNavi(cmd)) {
            fail(RestWork("내비에 보내지 못했어요", "내비 연결을 확인한 뒤 다시 해 주세요", failed = true))
            return RestOutcome.NOT_SENT
        }
        j.work = work
        j.stepSinceMs = clock()
        job = j
        say(text, Policy.REST_STEP_TIMEOUT_MS * 2)
        return RestOutcome.DONE
    }

    /** The navigation reported its waypoints ([names], while guiding): done, next step, or still waiting. */
    private fun checkJob(names: List<String>) {
        val j = job ?: return
        if (j.mode != JobMode.WAYPOINT) return
        val gone = names.none { RestPlan.same(it, (j.old ?: j.stop).name) }
        val there = names.any { RestPlan.same(it, j.stop.name) }
        when {
            j.removing && gone && j.kind == RestAction.REPLACE -> {
                // the old one is off; now the new one, at the same place on the route
                j.removing = false
                waypoints = waypoints.filter { it.id != j.old?.id }
                val index = minOf(j.index, names.size)
                if (!onNavi(NaviCmd.Add(j.stop, index))) {
                    job = null
                    fail(RestWork("${j.old?.name}만 빠졌어요", "${j.stop.label}는 내비에 보내지 못했어요. 다시 해 주세요", failed = true))
                    return
                }
                j.index = index
                j.stepSinceMs = clock()
            }
            j.removing && gone -> {
                job = null
                waypoints = waypoints.filter { it.id != j.stop.id }
                pickedRest = null
                say("${j.stop.label}를 내비 경유지에서 뺐어요", 6000)
            }
            !j.removing && there -> {
                job = null
                if (waypoints.none { it.id == j.stop.id }) waypoints = waypoints + j.stop
                pickedRest = null
                say(
                    if (j.kind == RestAction.REPLACE) "${j.old?.name} 대신 ${j.stop.label}가 내비 경유지에 들어갔어요"
                    else "${j.stop.label}가 내비 경유지에 들어갔어요", 6000
                )
            }
        }
    }

    private fun checkJobTimeout(now: Long) {
        val j = job ?: return
        if (now - j.stepSinceMs < Policy.REST_STEP_TIMEOUT_MS) return
        job = null
        fail(
            when {
                j.mode == JobMode.ROUTE ->
                    RestWork("내비가 받지 않았어요", "${j.stop.label} 안내가 시작되지 않았어요. 다시 해 주세요", failed = true)
                j.mode == JobMode.CANCEL ->
                    RestWork("내비가 받지 않았어요", "${j.stop.label} 안내가 그대로예요. 다시 해 주세요", failed = true)
                j.kind == RestAction.REPLACE && !j.removing ->
                    RestWork("${j.old?.name}만 빠졌어요", "${j.stop.label}는 내비가 받지 않았어요. 다시 해 주세요", failed = true)
                j.kind == RestAction.REMOVE || j.kind == RestAction.REPLACE ->
                    RestWork("내비가 받지 않았어요", "${(j.old ?: j.stop).label}가 그대로 있어요. 다시 해 주세요", failed = true)
                else -> RestWork("내비가 받지 않았어요", "${j.stop.label}가 들어가지 않았어요. 다시 해 주세요", failed = true)
            }
        )
    }

    private fun fail(note: RestWork) {
        workNote = note
        workNoteUntilMs = clock() + Policy.REST_FAIL_SHOW_MS
        say("${note.title}. ${note.line.substringBefore(". ")}", Policy.REST_FAIL_SHOW_MS)
    }

    /** The navigation is guiding to a destination (it reports its waypoints only then). */
    private fun routing(): Boolean = naviWaypoints != null

    private fun line(names: List<String>): String = (listOf("출발") + names + dest()).joinToString(" → ")

    private fun finish(outcome: RestOutcome): RestOutcome {
        when (outcome) {
            RestOutcome.DONE, RestOutcome.NOT_SENT -> {}
            RestOutcome.BUSY -> say("내비가 아직 경로를 바꾸는 중이에요. 잠시 뒤에 다시 해 주세요", 5000)
            RestOutcome.FULL -> say("경유지가 꽉 찼어요. 내비 경유지는 2개까지예요", 6000)
            RestOutcome.ALREADY -> say("이미 경유지에 있어요", 4000)
            RestOutcome.NOT_FOUND -> say(if (nearby.isEmpty()) "근처에 쉼터가 없어요. 안전한 곳에 잠시 세워 쉬어 가세요" else "근처에 그런 쉼터가 없어요", 6000)
            RestOutcome.NOTHING_OURS -> say("차멀미 케어가 넣은 쉼터가 없어요", 5000)
            RestOutcome.ASK_PLACE -> say("어느 쉼터인가요?", 8000)
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
        job = null
        workNote = null
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
        job = null
        workNote = null
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
        suggestion = nearby.firstOrNull()
        suggestionWhy = "멀미가 났어요. 가까운 쉼터에서 잠깐 쉬어 가요"
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
        val stop = nearby.firstOrNull() ?: return
        suggestion = stop
        suggestionWhy = "같은 목적지에서 멀미가 났던 시간보다 5분 앞서 알려드려요. 미리 쉬어 가요"
        offered = true
        val msg = "곧 멀미가 날 수 있는 시간이에요. 약 ${stop.etaMin}분 거리 ${stop.label}에서 쉬어 갈까요?"
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
                naviRouting = routing(),
                pickedRest = pickedRest,
                restAfterMin = restAfter,
                suggestion = suggestion,
                suggestionWhy = suggestionWhy,
                waypoints = waypoints,
                restWork = job?.work ?: workNote,
                nearby = nearby,
                nearbyState = nearbyState,
                destRestId = destRest?.takeIf { destIsOurs() }?.id,
                history = history
            )
        }
    }
}

private const val FIRST_GUIDE_MS = 8_000L
private const val SPEAK_DELAY_MS = 5_000L
private const val RELIEF_GUIDE = "화면 대신 먼 곳을 보세요"
private const val WINDOW_HINT = "밖이 괜찮으면 창문을 조금 열어 보세요"

/**
 * One route change waiting for the navigation. [removing] = the Remove step is out (a replace sends the Add after it);
 * [stop] is the stop added (or removed for [RestAction.REMOVE]); [old] is the stop a replace takes off.
 */
private class RestJob(
    val kind: RestAction,
    val stop: RestStop,
    val old: RestStop?,
    var index: Int,
    var removing: Boolean,
    val mode: JobMode = JobMode.WAYPOINT
) {
    var stepSinceMs = 0L
    var work: RestWork? = null
}

/** WAYPOINT: a waypoint added or taken off; ROUTE: guidance to a stop started; CANCEL: that guidance ended. */
private enum class JobMode { WAYPOINT, ROUTE, CANCEL }

/** "평택" -> "평택으로", "고삼호수" -> "고삼호수로" (no final consonant, or ㄹ, takes 로). */
private fun withRo(word: String): String {
    val c = word.lastOrNull() ?: return word
    if (c !in '가'..'힣') return word + "로"
    val jong = (c - '가') % 28
    return word + if (jong == 0 || jong == 8) "로" else "으로"
}

/** What [CareBrain.voiceRelief] did, so the voice call can answer Gleo AI. */
enum class VoiceReliefResult { STARTED, ASKING, RETARGETED, ALREADY }
