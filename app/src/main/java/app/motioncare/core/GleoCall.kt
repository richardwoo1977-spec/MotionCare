// ver 1.4.0 | 2026-11-11 22:25
package app.motioncare.core

/**
 * The voice functions the Gleo AI app may call (at most three). The action names are the function names in
 * assets/schema.json and the actions in AndroidManifest.xml; GleoCallTest keeps the three in step.
 * The Pleos guide shows the bare name as the Intent action, but the Gleo AI app on the emulator sends it with the
 * package name in front ("app.motioncare.OPEN_MOTIONCARE"), so both forms are accepted.
 */
enum class GleoCall(val action: String, val needsScreen: Boolean) {
    /** "멀미 나요": same as pressing the button. No screen is needed. */
    RELIEF("MOTION_SICKNESS_RELIEF", false),

    /** "괜찮아졌어요": same as pressing the button while relieving. No screen is needed. */
    RECOVERED("MOTION_SICKNESS_RECOVERED", false),

    /** "쉬어 갈 곳": put a rest area on the navigation route, swap ours for another, or take one off. No screen. */
    REST_STOP("REST_STOP_WAYPOINT", false);

    companion object {
        const val PACKAGE_PREFIX = "app.motioncare."

        fun fromAction(action: String?): GleoCall? =
            values().firstOrNull { it.action == action || PACKAGE_PREFIX + it.action == action }
    }
}

/**
 * The "seat" value of the relief call in assets/schema.json, as Gleo AI passes it, and the seats it names.
 * One value names one seat or a group of seats ("뒷자리 모두", "2열 전체", "조수석 앞뒤" ...).
 * The values are English words; the driver says the place in Korean and Gleo AI picks the value.
 * "Driver side" is the left side of the car, the side of the driver's seat.
 */
object GleoSeat {
    private val ROW_2 = setOf(Seat.ROW_2_LEFT, Seat.ROW_2_CENTER, Seat.ROW_2_RIGHT)
    private val ROW_3 = setOf(Seat.ROW_3_LEFT, Seat.ROW_3_CENTER, Seat.ROW_3_RIGHT)

    val values: Map<String, Set<Seat>> = linkedMapOf(
        "FRONT_PASSENGER" to setOf(Seat.ROW_1_RIGHT),
        "SECOND_ROW_DRIVER_SIDE" to setOf(Seat.ROW_2_LEFT),
        "SECOND_ROW_CENTER" to setOf(Seat.ROW_2_CENTER),
        "SECOND_ROW_PASSENGER_SIDE" to setOf(Seat.ROW_2_RIGHT),
        "THIRD_ROW_DRIVER_SIDE" to setOf(Seat.ROW_3_LEFT),
        "THIRD_ROW_CENTER" to setOf(Seat.ROW_3_CENTER),
        "THIRD_ROW_PASSENGER_SIDE" to setOf(Seat.ROW_3_RIGHT),
        "SECOND_ROW_ALL" to ROW_2,
        "THIRD_ROW_ALL" to ROW_3,
        "ALL_REAR_SEATS" to ROW_2 + ROW_3,
        "PASSENGER_SIDE_FRONT_AND_REAR" to setOf(Seat.ROW_1_RIGHT, Seat.ROW_2_RIGHT, Seat.ROW_3_RIGHT),
        "DRIVER_SIDE_REAR" to setOf(Seat.ROW_2_LEFT, Seat.ROW_3_LEFT),
        "ALL_PASSENGER_SEATS" to setOf(Seat.ROW_1_RIGHT) + ROW_2 + ROW_3
    )

    // matches "seat":"VALUE" and also the same text with escaped quotes (\"seat\":\"VALUE\")
    private val SEAT_FIELD = Regex("""seat\\?"\s*:\s*\\?"([A-Za-z_]+)""")

    /** The seats a value names; empty when absent or unknown. */
    fun fromValue(value: String?): Set<Seat> = value?.trim()?.uppercase()?.let { values[it] } ?: emptySet()

    /** Reads the seats out of the parameter text Gleo AI sends, e.g. {"seat":"ALL_REAR_SEATS"}. Empty when absent or unknown. */
    fun fromParamText(text: String?): Set<Seat> {
        if (text.isNullOrBlank()) return emptySet()
        val m = SEAT_FIELD.find(text) ?: return emptySet()
        return fromValue(m.groupValues[1])
    }
}

/** The parameters of the rest-area call ([GleoCall.REST_STOP]) as Gleo AI passes them, e.g. {"action":"ADD","place":"평택"}. */
object GleoRest {
    private val ACTION_FIELD = Regex("""action\\?"\s*:\s*\\?"([A-Za-z_]+)""")
    private val PLACE_FIELD = Regex("""place\\?"\s*:\s*\\?"((?:[^"\\]|\\u[0-9a-fA-F]{4})*)""")
    private val ESCAPED = Regex("""\\u([0-9a-fA-F]{4})""")

    /** The action in [text]; ADD when it is missing (the schema marks it required, Gleo AI may still leave it out). */
    fun action(text: String?): RestAction? {
        if (text.isNullOrBlank()) return null
        val m = ACTION_FIELD.find(text) ?: return if (text.contains("place")) RestAction.ADD else null
        return RestAction.entries.firstOrNull { it.name == m.groupValues[1].uppercase() }
    }

    /** The rest area name in [text] (escaped code points decoded), or null when absent or blank. */
    fun place(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val raw = PLACE_FIELD.find(text)?.groupValues?.get(1) ?: return null
        val decoded = ESCAPED.replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }
        return decoded.trim().ifEmpty { null }
    }
}
