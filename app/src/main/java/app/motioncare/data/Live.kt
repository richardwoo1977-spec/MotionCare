// ver 1.3.0 | 2026-11-06 19:08
package app.motioncare.data

import app.motioncare.core.CarLayout
import app.motioncare.core.CareMode
import app.motioncare.core.CheckRow
import app.motioncare.core.Gear
import app.motioncare.core.HistoryEvent
import app.motioncare.core.RestStop
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
    val designated: Set<String> = emptySet(),
    /** seat key marked "멀미 나요" by the relief: followed while it runs, still marked after it ran out by itself; null when none */
    val reliefSeat: String? = null,
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
    val waypoints: List<RestStop> = emptyList(),
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
