// ver 1.7.0 | 2026-11-12 11:35
package app.motioncare.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.motioncare.car.CarSignals
import app.motioncare.car.NaviLink
import app.motioncare.data.DemoNavi
import app.motioncare.car.VehicleLink
import app.motioncare.core.CareBrain
import app.motioncare.core.CareMode
import app.motioncare.core.NaviCmd
import app.motioncare.core.Geo
import app.motioncare.core.NearbyPlan
import app.motioncare.core.NearbyState
import app.motioncare.core.RestArea
import app.motioncare.core.Trip
import app.motioncare.data.DiagLog
import app.motioncare.data.Live
import app.motioncare.data.RestAssets
import app.motioncare.data.Store

/**
 * Foreground service that runs the care logic without any driver input.
 * All brain and vehicle-link calls happen on the main thread; SDK callbacks are posted here first.
 */
class CareService : Service() {

    companion object {
        @Volatile private var instance: CareService? = null

        /** The position is asked from the navigation this often (seconds). */
        private const val LOCATION_EVERY_SEC = 30

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, CareService::class.java))
            } catch (e: Exception) {
                DiagLog.w("서비스 시작 실패: $e")
            }
        }

        /** A voice call that arrived before the service was up; runs once the brain exists. */
        @Volatile private var pendingCall: ((CareBrain) -> Unit)? = null

        /** Run [block] now, or start the service and run it as soon as the service is up. */
        fun runOrStart(context: Context, block: (CareBrain) -> Unit) {
            if (run(block)) return
            pendingCall = block
            start(context)
        }

        /** Run [block] on the service's brain (main thread). Returns false when the service is not running. */
        fun run(block: (CareBrain) -> Unit): Boolean {
            val s = instance ?: return false
            s.main.post { s.brain?.let(block) }
            return true
        }

        /** "경유지로 넣기" (home card): the route change goes to the navigation through [CareBrain]'s onNavi. */
        fun addWaypoint(id: String) {
            run { it.addWaypoint(id) }
        }

        /** Test tools (debug build): route guidance on the emulator's navigation. */
        fun testRoute(start: Boolean) {
            val s = instance ?: return
            s.main.post {
                if (start) s.navi?.requestRoute("부산역", 35.1151, 129.0415, "부산광역시 동구 중앙대로 206")
                else s.navi?.cancelRoute()
            }
        }

        /**
         * Test tool (debug build): asks the navigation for road distance and time to the rest areas nearest to where
         * it says the car is, one at a time, and logs each answer with how long it took.
         */
        fun testTrip() {
            val s = instance ?: return
            s.main.post { s.refreshNearby(force = true) }
        }

        fun testNaviRefresh() {
            val s = instance ?: return
            s.main.post { s.navi?.refresh() }
        }

        fun setWindowOpenHigher(value: Boolean) {
            Store.windowOpenHigher = value
            Live.update { it.copy(windowOpenHigher = value) }
            val s = instance ?: return
            s.main.post {
                s.link?.windowOpenHigher = value
                s.link?.apply(s.brain?.desired ?: return@post)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var brain: CareBrain? = null
    private var link: VehicleLink? = null
    private var navi: NaviLink? = null
    private var car: CarSignals? = null
    private var speaker: Speaker? = null
    private var lastCaps = -1
    private var lastStatus = ""

    private val ticker = object : Runnable {
        override fun run() {
            brain?.tick()
            val l = link
            val b = brain
            if (l != null && b != null && l.capsVersion != lastCaps) {
                lastCaps = l.capsVersion
                l.apply(b.desired)
            }
            val text = statusText()
            if (text != lastStatus) {
                lastStatus = text
                Notifier.updateStatus(this@CareService, text)
            }
            // position, and the route state again: a route state missed at start would otherwise keep the rest
            // buttons saying there is no destination
            if (++ticks % LOCATION_EVERY_SEC == 0) {
                navi?.requestLocation()
                navi?.refresh()
            }
            main.postDelayed(this, 1_000L)
        }
    }

    private var ticks = 0

    /**
     * A route change for the navigation. A removal followed by an addition (a swap) is sent 0.6 s apart so the
     * navigation has taken the waypoint off before the new one goes to the same place.
     */

    // ------------------------------------------------------------------ places nearby

    /** where the navigation last said the car is */
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    /** where and when the last search started */
    private var searchLat: Double? = null
    private var searchLon: Double? = null
    private var searchAtMs = 0L

    /** the search in progress: places still to ask, answers so far, the one asked now */
    private val askQueue = ArrayDeque<RestArea>()
    private val answers = mutableListOf<Pair<RestArea, Trip?>>()
    private var asking: RestArea? = null
    private var askSentMs = 0L

    /**
     * Starts a search when there is none running and the car has moved [NearbyPlan.REFRESH_KM] or
     * [NearbyPlan.REFRESH_MS] has passed ([force]: now). The navigation is asked one place at a time
     * (its answer carries no request id).
     */
    private fun refreshNearby(force: Boolean = false) {
        val b = brain ?: return
        val lat = lastLat ?: return
        val lon = lastLon ?: return
        if (asking != null) return
        val now = System.currentTimeMillis()
        val moved = searchLat?.let { Geo.km(it, searchLon ?: lon, lat, lon) } ?: Double.MAX_VALUE
        if (!force && moved < NearbyPlan.REFRESH_KM && now - searchAtMs < NearbyPlan.REFRESH_MS) return
        searchLat = lat
        searchLon = lon
        searchAtMs = now
        val list = NearbyPlan.candidates(DemoNavi.catalogAreas(), lat, lon)
        DiagLog.w("근처 쉼터 찾기: 후보 ${list.size}곳 " + list.joinToString { "${it.name}(${NearbyPlan.directionText(it)})" })
        if (list.isEmpty()) {
            b.onNearby(NearbyState.NONE, emptyList())
            return
        }
        b.onNearby(NearbyState.LOADING, emptyList())
        askQueue.clear()
        askQueue.addAll(list)
        answers.clear()
        askNext()
    }

    private fun askNext() {
        val a = askQueue.removeFirstOrNull()
        if (a == null) {
            asking = null
            val ranked = NearbyPlan.rank(answers)
            DiagLog.w("근처 쉼터: " + ranked.joinToString { "${it.name} ${it.etaMin}분 ${"%.1f".format(it.distanceKm)}km" })
            brain?.onNearby(if (ranked.isEmpty()) NearbyState.NONE else NearbyState.READY, ranked)
            return
        }
        asking = a
        askSentMs = System.currentTimeMillis()
        val sent = askSentMs
        if (navi?.requestTrip(navi?.labelOf(a) ?: a.name, a.lat, a.lon) != true) {
            onTripAnswer(null, null)
            return
        }
        // no answer in time: that place is left out
        main.postDelayed({ if (askSentMs == sent && asking === a) onTripAnswer(null, null) }, ASK_TIMEOUT_MS)
    }

    private fun onTripAnswer(distanceM: Int?, seconds: Int?) {
        val a = asking ?: return
        askSentMs = 0L
        answers.add(a to if (distanceM != null && seconds != null && distanceM > 0) Trip(distanceM, seconds) else null)
        askNext()
    }

    /** Sends one route change now (the brain sends the next only after the navigation reports this one). */
    private fun sendToNavi(cmd: NaviCmd): Boolean {
        val area = when (cmd) {
            is NaviCmd.Add -> DemoNavi.areaOf(cmd.stop.id)
            is NaviCmd.Route -> DemoNavi.areaOf(cmd.stop.id)
            else -> null
        }
        return navi?.run(cmd, area) ?: false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Store.init(this)
        RestAssets.ensureLoaded(this)
        Notifier.createChannels(this)
        goForeground("대기 중")
        DiagLog.w("서비스 시작")
        Live.update { it.copy(serviceRunning = true, windowOpenHigher = Store.windowOpenHigher) }

        val spk = Speaker(this).also { speaker = it }
        val b = CareBrain(
            clock = System::currentTimeMillis,
            speak = { text -> spk.say(text) },
            onDesired = { d -> link?.apply(d) },
            onNavi = { cmd -> sendToNavi(cmd) }
        )
        brain = b
        b.init()

        val l = VehicleLink(
            context = this,
            main = main,
            log = DiagLog::w,
            onOccupancy = { map -> main.post { b.onOccupancy(map) } },
            onNotes = { notes -> main.post { Live.update { s -> s.copy(notes = notes) } } },
            onCheck = { rows -> main.post { Live.update { s -> s.copy(check = rows) } } },
            onRows = { n -> main.post { Live.update { s -> s.copy(rows = n) } } }
        )
        l.windowOpenHigher = Store.windowOpenHigher
        navi = NaviLink(
            context = this,
            main = main,
            log = DiagLog::w,
            onWaypoints = { names -> b.onNaviWaypoints(names) },
            onDestination = { name -> b.onNaviDestination(name) },
            onLocation = { address, lat, lon ->
                lastLat = lat
                lastLon = lon
                b.onLocation(address, lat, lon)
                refreshNearby()
            },
            onTrip = { d, eta -> onTripAnswer(d, eta) }
        ).also { it.start() }
        link = l
        val ok = l.start()
        Live.update { it.copy(sdkReady = ok) }

        car = CarSignals(
            context = this,
            onGear = { gear, _ -> main.post { b.onGear(gear); Live.update { s -> s.copy(gear = gear) } } },
            onSpeed = { mps, _ -> main.post { b.onSpeedMps(mps) } },
            onConnected = { connected -> main.post { Live.update { s -> s.copy(signalsConnected = connected) } } },
            log = DiagLog::w
        ).also { it.start() }

        main.post(ticker)
        pendingCall?.let { call ->
            pendingCall = null
            main.post { call(b) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // every startForegroundService() must be answered with startForeground(), even when already running
        goForeground(lastStatus.ifEmpty { "대기 중" })
        return START_STICKY
    }

    private fun goForeground(text: String) {
        val notification = Notifier.status(this, text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_STATUS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_STATUS, notification)
        }
    }

    private fun statusText(): String {
        val s = Live.state.value
        return when (s.mode) {
            CareMode.RELIEF -> "멀미 완화 중"
            CareMode.PREVENT -> "멀미 예방 중"
            CareMode.IDLE -> if (s.tripActive) "주행 중 · 지켜보는 중" else "대기 중"
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        car?.stop()
        link?.stop()
        navi?.stop()
        speaker?.shutdown()
        instance = null
        Live.update { it.copy(serviceRunning = false, sdkReady = false, signalsConnected = false) }
        DiagLog.w("서비스 종료")
        super.onDestroy()
    }
}

/** How long the navigation may take to answer one road-time question. */
private const val ASK_TIMEOUT_MS = 10_000L
