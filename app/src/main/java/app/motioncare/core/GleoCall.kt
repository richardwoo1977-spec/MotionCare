// ver 1.2.0 | 2026-11-06 16:39
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

    /** Open the app. */
    OPEN_APP("OPEN_MOTIONCARE", true);

    companion object {
        const val PACKAGE_PREFIX = "app.motioncare."

        fun fromAction(action: String?): GleoCall? =
            values().firstOrNull { it.action == action || PACKAGE_PREFIX + it.action == action }
    }
}

/**
 * The "seat" value of the relief call in assets/schema.json, as Gleo AI passes it, and the seat it names.
 * The values are English words; the driver says the place in Korean and Gleo AI picks the value.
 * "Driver side" is the left side of the car, the side of the driver's seat.
 */
object GleoSeat {
    val values: Map<String, Seat> = linkedMapOf(
        "FRONT_PASSENGER" to Seat.ROW_1_RIGHT,
        "SECOND_ROW_DRIVER_SIDE" to Seat.ROW_2_LEFT,
        "SECOND_ROW_CENTER" to Seat.ROW_2_CENTER,
        "SECOND_ROW_PASSENGER_SIDE" to Seat.ROW_2_RIGHT,
        "THIRD_ROW_DRIVER_SIDE" to Seat.ROW_3_LEFT,
        "THIRD_ROW_CENTER" to Seat.ROW_3_CENTER,
        "THIRD_ROW_PASSENGER_SIDE" to Seat.ROW_3_RIGHT
    )

    // matches "seat":"VALUE" and also the same text with escaped quotes (\"seat\":\"VALUE\")
    private val SEAT_FIELD = Regex("""seat\\?"\s*:\s*\\?"([A-Za-z_]+)""")

    fun fromValue(value: String?): Seat? = value?.trim()?.uppercase()?.let { values[it] }

    /** Reads the seat out of the parameter text Gleo AI sends, e.g. {"seat":"FRONT_PASSENGER"}. Null when absent or unknown. */
    fun fromParamText(text: String?): Seat? {
        if (text.isNullOrBlank()) return null
        val m = SEAT_FIELD.find(text) ?: return null
        return fromValue(m.groupValues[1])
    }
}
