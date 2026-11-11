// ver 1.0.0 | 2026-11-05 22:50
package app.motioncare.core

/**
 * Which screen the app opens with: title, three introduction screens, then home.
 * Pure rules, no Android types. The title and the introduction are only for a parked car;
 * the moment the car is not parked the app goes to home, so a driver never has to read them.
 */
object StartFlow {

    enum class Stage { TITLE, ONB1, ONB2, ONB3, HOME }

    /** The title stays this long (no motion on it). */
    const val TITLE_MS = 1_500L

    /**
     * True when the title and introduction may be shown.
     * Parked = gear P. With no car signals at all (gear unknown, not connected: a phone or emulator demo) it is allowed too.
     * Gear unknown while signals ARE connected means the car has not said yet, so it is treated as not parked.
     */
    fun guidedAllowed(gear: Gear, speedKmh: Double, signalsConnected: Boolean): Boolean {
        if (Policy.isDriving(gear, speedKmh)) return false
        return when (gear) {
            Gear.PARK -> true
            Gear.UNKNOWN -> !signalsConnected
            else -> false
        }
    }

    /** First screen when the app opens. */
    fun first(guided: Boolean): Stage = if (guided) Stage.TITLE else Stage.HOME

    /** Screen after the title: the introduction on the very first run, otherwise home. */
    fun afterTitle(onboardingDone: Boolean, guided: Boolean): Stage =
        if (guided && !onboardingDone) Stage.ONB1 else Stage.HOME

    fun next(stage: Stage): Stage = when (stage) {
        Stage.TITLE -> Stage.TITLE
        Stage.ONB1 -> Stage.ONB2
        Stage.ONB2 -> Stage.ONB3
        Stage.ONB3 -> Stage.HOME
        Stage.HOME -> Stage.HOME
    }

    fun back(stage: Stage): Stage = when (stage) {
        Stage.TITLE -> Stage.TITLE
        Stage.ONB1 -> Stage.ONB1
        Stage.ONB2 -> Stage.ONB1
        Stage.ONB3 -> Stage.ONB2
        Stage.HOME -> Stage.HOME
    }

    /** A guided screen is dropped for home as soon as guidance is not allowed any more (the car started moving). */
    fun enforce(stage: Stage, guided: Boolean): Stage = if (guided) stage else Stage.HOME

    /** "건너뛰기" is on the three introduction screens only. */
    fun skippable(stage: Stage): Boolean = stage == Stage.ONB1 || stage == Stage.ONB2 || stage == Stage.ONB3
}
