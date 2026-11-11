// ver 1.1.0 | 2026-11-05 22:50
package app.motioncare.data

import android.content.Context
import android.content.SharedPreferences
import app.motioncare.core.HistoryEvent
import org.json.JSONArray
import org.json.JSONObject

/** Small on-device store (SharedPreferences). Nothing leaves the vehicle. */
object Store {
    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        if (sp == null) sp = context.applicationContext.getSharedPreferences("motioncare", Context.MODE_PRIVATE)
    }

    var designated: Set<String>
        get() = sp?.getStringSet("designated", emptySet())?.toSet() ?: emptySet()
        set(value) { sp?.edit()?.putStringSet("designated", value)?.apply() }

    /** True when a bigger window value means "more open" (Android Automotive convention; confirm on the car). */
    var windowOpenHigher: Boolean
        get() = sp?.getBoolean("windowOpenHigher", true) ?: true
        set(value) { sp?.edit()?.putBoolean("windowOpenHigher", value)?.apply() }

    /** True once the three introduction screens were finished or skipped (they are shown once, in a parked car). */
    var onboardingDone: Boolean
        get() = sp?.getBoolean("onboardingDone", false) ?: false
        set(value) { sp?.edit()?.putBoolean("onboardingDone", value)?.apply() }

    var destination: String
        get() = sp?.getString("destination", "") ?: ""
        set(value) { sp?.edit()?.putString("destination", value)?.apply() }

    fun history(): List<HistoryEvent> {
        val raw = sp?.getString("history", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEvent(o.getLong("t"), o.getInt("m"), o.getString("s"), o.optString("d", ""))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setHistory(list: List<HistoryEvent>) {
        val prefs = sp ?: return
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("t", it.epochMs).put("m", it.elapsedMin).put("s", it.seatKey).put("d", it.destination))
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    fun addEvent(event: HistoryEvent) {
        setHistory((listOf(event) + history()).take(200))
    }
}
