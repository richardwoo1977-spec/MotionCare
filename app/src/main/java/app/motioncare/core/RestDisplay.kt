// ver 1.0.0 | 2026-11-05 19:30
package app.motioncare.core

import kotlin.math.roundToInt

/** How the "쉬어 갈 곳" card is written. Widths are the measured Asta Sans widths of the confirmed design. */
object RestDisplay {
    /** "안성" + 18 -> "18km 앞". Never more than three digits. */
    fun kmText(km: Double): String = "${km.roundToInt().coerceIn(0, 999)}km 앞"

    private const val AVAILABLE_PX = 330.0
    private const val HANGUL_PX = 35.2        // one Hangul character at 42 px
    private const val GAP_PX = 11.0           // gap between the name and the distance at 42 px
    private const val KM_TEXT_PX = 146.3      // "NNkm 앞" with two digits at 42 px
    private const val PER_DIGIT_PX = 28.9
    private val SIZES = listOf(42, 40, 38, 36, 34)

    /** The biggest size (42 down to 34) at which name and distance share one line; 34 when none does. */
    fun nameSizePx(name: String, km: Int): Int {
        val digits = km.coerceIn(0, 999).toString().length
        val distancePx = KM_TEXT_PX + PER_DIGIT_PX * (digits - 2)
        return SIZES.firstOrNull { size ->
            val k = size / 42.0
            (HANGUL_PX * name.length + GAP_PX) * k + distancePx * k <= AVAILABLE_PX
        } ?: SIZES.last()
    }
}
