// ver 1.0.0 | 2026-11-05 13:34
package app.motioncare.data

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Last 80 log lines, shown on the 점검 screen so the vehicle's answers can be read on the car's display. */
object DiagLog {
    private const val TAG = "MotionCare"
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun w(message: String) {
        Log.w(TAG, message)
        val line = "${fmt.format(Date())}  $message"
        _lines.value = (listOf(line) + _lines.value).take(80)
    }
}
