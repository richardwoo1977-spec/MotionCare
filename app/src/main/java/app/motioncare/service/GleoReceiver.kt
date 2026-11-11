// ver 1.6.0 | 2026-11-11 22:50
package app.motioncare.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.motioncare.core.GleoCall
import app.motioncare.core.GleoRest
import app.motioncare.core.GleoSeat
import app.motioncare.core.RestAction
import app.motioncare.core.RestOutcome
import app.motioncare.core.Seat
import app.motioncare.core.VoiceReliefResult
import app.motioncare.data.DiagLog
import org.json.JSONObject

/**
 * Receives the voice functions that need no screen (component "service" in assets/schema.json).
 * Gleo sends them as broadcasts to this app. The "open the app" call is an activity action on MainActivity.
 * If the care service is not running yet it is started and the call runs as soon as it is up.
 *
 * The relief call may carry the seat of the affected passenger. When several people sit and no seat is named,
 * the app answers "asking" (which seat?) and Gleo AI may call again with the seat; if no answer comes the
 * care logic relieves every seat after a few seconds.
 */
class GleoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val call = GleoCall.fromAction(intent.action)
        if (call == null || call.needsScreen) {
            DiagLog.w("Gleo 호출 무시: ${intent.action}")
            return
        }
        val name = intent.action ?: call.action
        val app = context.applicationContext
        val seats = seatsFrom(intent)
        DiagLog.w("Gleo 호출: ${call.action}" + (if (seats.isEmpty()) "" else " · 좌석 " + seats.joinToString("+") { it.key }))
        when (call) {
            GleoCall.RELIEF -> {
                // the answer to Gleo depends on what the care logic decided, so it is sent from there
                CareService.runOrStart(context) { brain ->
                    when (brain.voiceRelief(seats)) {
                        VoiceReliefResult.ASKING ->
                            reply(app, name, "asking", "Which seat is the affected passenger in?", MISSING_SEAT)
                        VoiceReliefResult.RETARGETED ->
                            reply(app, name, "success", "Relief now follows the named seats.")
                        else ->
                            reply(app, name, "success", "Relief has started.")
                    }
                }
                return
            }
            GleoCall.RECOVERED -> {
                CareService.runOrStart(context) { it.reliefEnd() }
                reply(app, name, "success", "Relief has ended and the cabin air is back to its earlier settings.")
                return
            }
            GleoCall.REST_STOP -> {
                val text = paramText(intent)
                val action = GleoRest.action(text) ?: RestAction.ADD
                val place = GleoRest.place(text)
                DiagLog.w("Gleo 휴게소: ${action.name}" + (place?.let { " · $it" } ?: ""))
                CareService.runOrStart(context) { brain ->
                    val (outcome, stop) = brain.voiceRest(action, place)
                    when (outcome) {
                        // the navigation takes several seconds and reports the change later: say it is being done, not done
                        RestOutcome.DONE -> reply(app, name, "success", when (action) {
                            RestAction.ADD -> "Asked the navigation to add $stop rest area as a waypoint. The navigation screen shows it once the route is updated."
                            RestAction.REPLACE -> "Asked the navigation to put $stop rest area in place of the earlier rest stop. The navigation screen shows it once the route is updated."
                            RestAction.REMOVE -> "Asked the navigation to take $stop rest area off the route. The navigation screen shows it once the route is updated."
                        })
                        RestOutcome.BUSY -> reply(app, name, "fail", "The navigation is still changing the route. Please ask again in a moment.")
                        RestOutcome.NO_ROUTE -> reply(app, name, "fail", "The navigation has no destination yet. Please set a destination in the navigation first.")
                        RestOutcome.NOT_SENT -> reply(app, name, "fail", "The navigation could not be reached, so the route was not changed.")
                        RestOutcome.ALREADY -> reply(app, name, "success", "$stop rest area is already on the route.")
                        RestOutcome.FULL -> reply(app, name, "fail", "The navigation already has two waypoints, its limit.")
                        RestOutcome.NOT_FOUND -> reply(app, name, "fail", "There is no rest area by that name ahead on the route.")
                        RestOutcome.NOTHING_OURS -> reply(app, name, "fail", "This app has not put any rest area on the route.")
                        RestOutcome.ASK_PLACE -> reply(app, name, "asking", "Which rest area?", MISSING_PLACE)
                    }
                }
                return
            }
        }
        reply(app, name, "success", "요청을 받았어요")
    }

    /** The seats Gleo AI passed with the call (one seat or a group), or none. The extra's name is not fixed in the guide, so every extra is read. */
    @Suppress("DEPRECATION")
    private fun seatsFrom(intent: Intent): Set<Seat> {
        val extras = intent.extras ?: return emptySet()
        DiagLog.w("Gleo 값: " + extras.keySet().joinToString { k -> "$k=${extras.get(k)}" }.take(300))
        for (k in extras.keySet()) {
            val seats = GleoSeat.fromParamText(extras.get(k)?.toString())
            if (seats.isNotEmpty()) return seats
        }
        return emptySet()
    }

    /** All extras as one text, for the parameters of the rest-area call (the extra's name is not fixed in the guide). */
    @Suppress("DEPRECATION")
    private fun paramText(intent: Intent): String {
        val extras = intent.extras ?: return ""
        return extras.keySet().joinToString(" ") { k -> extras.get(k)?.toString() ?: "" }
    }

    /** Tells Gleo the call was handled (response format from the Pleos callback guide: success / asking / fail / error). */
    private fun reply(context: Context, functionName: String, status: String, message: String, data: String? = null) {
        DiagLog.w("Gleo 응답: $status · ${message.take(40)}")
        try {
            val body = JSONObject()
                .put("functionName", functionName)
                .put("status", status)
                .put("message", message)
            if (data != null) body.put("data", data)
            context.sendBroadcast(
                Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE")
                    .putExtra("result", status)
                    .putExtra("packageName", context.packageName)
                    .putExtra("response", body.toString())
            )
        } catch (e: Exception) {
            DiagLog.w("Gleo 응답 실패: $e")
        }
    }

    private companion object {
        /** "data" of an asking answer: names the missing field. */
        const val MISSING_SEAT = "[{\"missingField\":\"seat\"}]"

        /** "data" of an asking answer for the rest-area call. */
        const val MISSING_PLACE = "[{\"missingField\":\"place\"}]"
    }
}
