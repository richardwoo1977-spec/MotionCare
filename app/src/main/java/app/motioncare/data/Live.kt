// ver 1.8.0 | 2026-11-11 22:50
package app.motioncare.data

import app.motioncare.core.CarLayout
import app.motioncare.core.CareMode
import app.motioncare.core.CheckRow
import app.motioncare.core.Gear
import app.motioncare.core.HistoryEvent
import app.motioncare.core.RestStop
import app.motioncare.core.RestWork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LiveState(
    val serviceRunning: Boolean = false,
    val sdkReady: Boolean = false,
    val signalsConnected: Boolean = false,
    val gear: Gear = Gear.UNKNOWN,
    val speedKmh: Double = 0.0,
    /** seat key -> occupied (null = unknown); only seats the vehicle reports are present */
    val occupancy: Map<String, Boolean?> = emptyMap(),
    /** seat keys marked "멀미 나요" by the relief: followed while it runs, still marked after it ran out by itself */
    val reliefSeats: Set<String> = emptySet(),
    /** the relief runs for the whole cabin (no seat known) */
    val reliefWhole: Boolean = false,
    /** seat keys tapped on the screen while no relief runs */
    val pickedSeats: Set<String> = emptySet(),
    /** the "멀미 나요" button may be pressed: seats are picked or still marked */
    val canRelieve: Boolean = false,
    /** seat rows to draw (1, 2 or 3); 2 until the vehicle says otherwise */
    val rows: Int = CarLayout.DEFAULT_ROWS,
    val mode: CareMode = CareMode.IDLE,
    /** what the app is doing to the cabin right now, in plain words */
    val notes: List<String> = emptyList(),
    val caption: String = "",
    val tripActive: Boolean = false,
    val tripElapsedMin: Int = 0,
    val destination: String = "",
    val restAfterMin: Int? = null,
    val suggestion: RestStop? = null,
    val suggestionWhy: String = "",
    /** a route change sent to the navigation and not confirmed yet, or one that just failed; null = none */
    val restWork: RestWork? = null,
    /** rest areas the app put on the route (only those the navigation has confirmed) */
    val waypoints: List<RestStop> = emptyList(),
    /** waypoint names on the navigation's route in order (it may hold the driver's own); null = not reported */
    val naviWaypoints: List<String>? = null,
    /** the navigation is guiding to a destination; waypoints can be put on the route only then */
    val naviRouting: Boolean = false,
    /** rest area id picked on the rest screen (not yet acted on) */
    val pickedRest: String? = null,
    /** the destination comes from the navigation (false = the demo destination chosen in the app) */
    val destinationFromNavi: Boolean = false,
    /** where this trip started, short words (from the navigation) */
    val origin: String = "",
    val history: List<HistoryEvent> = emptyList(),
    val check: List<CheckRow> = emptyList(),
    val windowOpenHigher: Boolean = true
)

/** In-process state shared by the service and the screens. */
object Live {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state

    fun update(change: (LiveState) -> LiveState) {
        _state.value = change(_state.value)
    }
}
