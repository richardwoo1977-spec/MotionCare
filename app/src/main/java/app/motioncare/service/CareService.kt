// ver 1.2.0 | 2026-11-11 17:30
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

        /** "경유지로 추가": the app's own list, and the built-in navigation through NaviHelper when it is connected. */
        fun addWaypoint(id: String) {
            val s = instance ?: return
            s.main.post {
                s.brain?.addWaypoint(id)
                val area = DemoNavi.areaOf(id)
                if (area == null) DiagLog.w("내비 경유지: 시연용 휴게소라 좌표 없음")
                else s.navi?.addWaypoint(area)
            }
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
            main.postDelayed(this, 1_000L)
        }
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
            onDesired = { d -> link?.apply(d) }
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
        navi = NaviLink(this, DiagLog::w) { _, text -> main.post { DiagLog.w(text) } }.also { it.start() }
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
