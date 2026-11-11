// ver 1.0.0 | 2026-11-11 17:30
package app.motioncare.car

import ai.pleos.playground.navi.constants.*
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import app.motioncare.core.RestArea

/**
 * Pleos NaviHelper SDK 3.0.5: adds the chosen rest area to the built-in navigation as a waypoint.
 * Calls follow the NaviHelper programming guide (initialize -> addListener -> addWaypoint); results arrive as events.
 */
class NaviLink(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onResult: (ok: Boolean, text: String) -> Unit
) {
    private var helper: NaviHelper? = null
    @Volatile var connected = false
        private set

    private val listener = object : NaviHelperEventListener {
        override fun onError(errorCode: NaviErrorCode) {
            log("내비 오류: $errorCode")
            onResult(false, "내비가 경유지를 받지 못했어요 ($errorCode)")
        }
    }

    fun start() {
        try {
            val h = NaviHelper(context.applicationContext)
            helper = h
            h.initialize(
                onConnected = {
                    connected = true
                    h.addListener(listener)
                    log("내비 연결됨 (NaviHelper)")
                },
                onFailed = { t ->
                    connected = false
                    log("내비 연결 실패: ${t.message ?: t.javaClass.simpleName}")
                }
            )
        } catch (e: Throwable) {
            log("내비 초기화 오류: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Adds [area] as the first waypoint. Returns false when the navigation link is not up. */
    fun addWaypoint(area: RestArea): Boolean {
        val h = helper
        if (h == null || !connected) {
            log("내비 미연결: 경유지 요청을 보내지 못함")
            return false
        }
        return try {
            h.addWaypoint(
                RequestWaypointInfo(
                    latitude = area.lat,
                    longitude = area.lon,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = "",
                    poiName = "${area.name}휴게소",
                    poiSubId = "0",
                    address = "${area.route} ${area.direction}"
                )
            )
            log("내비 경유지 요청: ${area.name} (${area.lat}, ${area.lon})")
            true
        } catch (e: Throwable) {
            log("내비 경유지 요청 오류: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    fun stop() {
        try {
            helper?.removeListener(listener)
            helper?.release()
        } catch (_: Throwable) {
        }
        helper = null
        connected = false
    }
}
