// ver 2.4.0 | 2026-11-12 11:30
package app.motioncare.car

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import app.motioncare.core.NaviCmd
import app.motioncare.core.PlaceKind
import app.motioncare.core.RestArea

/**
 * Pleos NaviHelper SDK 3.0.5 (method names read from the library itself):
 *  - addWaypoint(RequestWaypointInfo) / removeWaypoint(WaypointIndex FIRST|SECOND): the route changes the app asks for
 *  - getDestinationInfo -> onDestinationInfo, getWaypointInfo -> onWayPointInfo, getRouteStateInfo -> onRouteStateInfo,
 *    getCurrentLocationInfo -> onCurrentLocationInfo; change events ask again.
 * Results arrive as events on a binder thread; the callbacks given here post them to the main thread.
 * The waypoint list is passed on only while the navigation is routing (with no route it reports an empty list,
 * which says nothing about what the app asked for).
 *
 * Routing is told by the status events and by whether the navigation has a destination. getRouteStateInfo is only
 * logged: on the emulator it answered "not routing" while the navigation was guiding to 부산역 (2026-11-11).
 * While a route is being changed the navigation reports no destination, so that time counts as not routing.
 */
class NaviLink(
    private val context: Context,
    private val main: Handler,
    private val log: (String) -> Unit,
    private val onWaypoints: (List<String>?) -> Unit,
    private val onDestination: (String?) -> Unit,
    private val onLocation: (address: String?, lat: Double, lon: Double) -> Unit,
    /** road distance (m) and the navigation's eta value for the last [requestTrip]; null = the navigation answered an error */
    private val onTrip: (distanceM: Int?, eta: Int?) -> Unit = { _, _ -> }
) {
    private var helper: NaviHelper? = null
    @Volatile var connected = false
        private set
    @Volatile private var routing = false

    private val listener = object : NaviHelperEventListener {
        override fun onError(errorCode: NaviErrorCode) {
            main.post {
                log("내비 오류: $errorCode")
                if (errorCode == NaviErrorCode.CAUSE_SEARCH_TRIP) onTrip(null, null)
            }
        }

        override fun onTripInfo(tripInfo: TripInfo) {
            val t = tripInfo
            main.post {
                log("내비 경로 예상: 거리 ${t.distance} · eta ${t.eta} · 경로점 ${t.summary?.routePoints?.size ?: 0}")
                onTrip(t.distance, t.eta)
            }
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            val said = routeStateInfo.isRouting
            main.post { log("내비 경로 상태(참고): ${if (said) "안내 중" else "안내 없음"}") }
            // not trusted (see above): the destination decides
            refreshRoute()
        }

        override fun onNavigationStatus(navigationStatus: NavigationStatus) {
            routing = navigationStatus == NavigationStatus.ROUTE_DRIVE
            main.post { log("내비 상태: $navigationStatus") }
            if (routing) refreshRoute() else main.post { onWaypoints(null); onDestination(null) }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val name = destinationInfo.name
            val was = routing
            routing = name.isNotBlank()
            main.post {
                log("내비 목적지: $name")
                onDestination(name)
                if (!routing) onWaypoints(null)
            }
            // the waypoint answer may have come before this one, while routing was not known yet
            if (routing && !was) safe("경유지 조회") { it.getWaypointInfo() }
        }

        override fun onDestinationChanged() {
            safe("목적지 조회") { it.getDestinationInfo(); it.getWaypointInfo() }
        }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            val names = waypointInfoList.map { it.name }
            main.post {
                log("내비 경유지: ${if (names.isEmpty()) "없음" else names.joinToString(" → ")}")
                onWaypoints(if (routing) names else null)
            }
        }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            main.post { log("내비 경유지 바뀜: ${waypointChangedInfo.cmdType} ${waypointChangedInfo.index}") }
            safe("경유지 조회") { it.getWaypointInfo() }
        }

        override fun onRouteCancelled() {
            routing = false
            main.post { log("내비 경로 취소"); onWaypoints(null); onDestination(null) }
        }

        override fun onRouteEnded() {
            routing = false
            main.post { log("내비 경로 끝"); onWaypoints(null); onDestination(null) }
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val c = currentLocationInfo
            main.post { onLocation(c.address, c.latitude, c.longitude) }
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
                    main.post { log("내비 연결됨 (NaviHelper)") }
                    safe("경로 상태 조회") { it.getRouteStateInfo(); it.getCurrentLocationInfo() }
                },
                onFailed = { t ->
                    connected = false
                    main.post { log("내비 연결 실패: ${t.message ?: t.javaClass.simpleName}") }
                }
            )
        } catch (e: Throwable) {
            log("내비 초기화 오류: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Test tool (debug build): starts route guidance to a destination, so waypoints can be tried on the emulator. */
    fun requestRoute(name: String, lat: Double, lon: Double, address: String) = safe("경로 요청") {
        it.requestRoute(RouteInfo(latitude = lat, longitude = lon, poiId = "", poiName = name, poiSubId = "0", address = address))
        main.post { log("내비 경로 요청: $name ($lat, $lon)") }
    }

    /**
     * Asks the navigation for the road distance and time to a place without starting guidance (answer: onTripInfo).
     * The answer carries no request id, so callers send one at a time.
     */
    fun requestTrip(name: String, lat: Double, lon: Double) = safe("경로 예상") {
        it.requestTrip(TripRequest(latitude = lat, longitude = lon, poiId = "", poiName = name, poiSubId = "0"))
    }

    /** Test tool (debug build): ends route guidance. */
    fun cancelRoute() = safe("경로 취소") { it.cancelRoute() }

    /** Asks the navigation for its route state, destination and waypoints again. */
    fun refresh() = safe("경로 상태 조회") { it.getRouteStateInfo(); it.getDestinationInfo(); it.getWaypointInfo() }

    /** Asks for the position again (the service calls this every so often). */
    fun requestLocation() = safe("위치 조회") { it.getCurrentLocationInfo() }

    private fun refreshRoute() = safe("경로 조회") { it.getDestinationInfo(); it.getWaypointInfo() }

    /**
     * Runs a route change. [area] is the rest area behind an [NaviCmd.Add] (null for the made-up demo stops, which
     * have no position and are not sent). Returns false when nothing was sent.
     */
    fun run(cmd: NaviCmd, area: RestArea?): Boolean = when (cmd) {
        is NaviCmd.Add -> {
            if (area == null) {
                log("내비 경유지: 시연용 휴게소라 좌표 없음")
                false
            } else safe("경유지 요청") {
                it.addWaypoint(
                    RequestWaypointInfo(
                        latitude = area.lat,
                        longitude = area.lon,
                        waypointIndex = index(cmd.index),
                        poiId = "",
                        poiName = labelOf(area),
                        poiSubId = "0",
                        address = "${area.route} ${area.direction}"
                    )
                )
                log("내비 경유지 요청: ${area.name} · ${index(cmd.index)} (${area.lat}, ${area.lon})")
            }
        }
        is NaviCmd.Remove -> safe("경유지 빼기") {
            it.removeWaypoint(index(cmd.index))
            log("내비 경유지 빼기 요청: ${cmd.name} · ${index(cmd.index)}")
        }
        is NaviCmd.Route -> {
            if (area == null) {
                log("내비 안내: 좌표 없는 장소")
                false
            } else safe("안내 요청") {
                it.requestRoute(
                    RouteInfo(
                        latitude = area.lat, longitude = area.lon, poiId = "", poiName = labelOf(area),
                        poiSubId = "0", address = "${area.route} ${area.direction}"
                    )
                )
                log("내비 안내 요청: ${labelOf(area)} (${area.lat}, ${area.lon})")
            }
        }
        NaviCmd.Cancel -> safe("안내 끝내기") {
            it.cancelRoute()
            log("내비 안내 끝내기 요청")
        }
    }

    /** "안성휴게소", "죽전 졸음쉼터" */
    fun labelOf(area: RestArea): String =
        if (area.kind == PlaceKind.REST_AREA) "${area.name}휴게소" else "${area.name} 졸음쉼터"

    private fun index(i: Int): WaypointIndex = if (i <= 0) WaypointIndex.FIRST else WaypointIndex.SECOND

    private fun safe(what: String, block: (NaviHelper) -> Unit): Boolean {
        val h = helper
        if (h == null || !connected) {
            main.post { log("내비 미연결: $what 못 함") }
            return false
        }
        return try {
            block(h)
            true
        } catch (e: Throwable) {
            main.post { log("내비 $what 오류: ${e.message ?: e.javaClass.simpleName}") }
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
