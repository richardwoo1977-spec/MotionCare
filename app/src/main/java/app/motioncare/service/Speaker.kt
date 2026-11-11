// ver 2.0.0 | 2026-11-11 20:15
package app.motioncare.service

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.motioncare.data.DiagLog
import java.util.Locale

/**
 * Spoken guidance through Pleos Connect's Speech Service (Pleos TextToSpeech SDK, on-device engine), the engine that
 * speaks on the Pleos emulator (Android's own TextToSpeech has no Korean voice there). Every spoken line is also
 * shown on the screen. Where the engine is missing or fails, nothing is said and the reason is logged.
 *
 * The engine says the first ~2.5 s of every utterance roughly (measured with TripSign on the same emulator),
 * so every line opens with [LEAD_IN] and the guidance follows it.
 */
class Speaker(context: Context) {

    private enum class State { STARTING, READY, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var state = State.STARTING
    private var pending: String? = null

    private val events = object : EventListener {
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onStart() = onMain { DiagLog.w("음성 안내 시작") }
        override fun onDone() = Unit
        override fun onStop() = Unit
        override fun onError(errorType: ErrorType) = onMain { DiagLog.w("음성 안내 실패: $errorType") }
    }

    init {
        main.postDelayed({ if (state == State.STARTING) fail("응답 없음") }, START_TIMEOUT_MS)
        try {
            val t = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
            tts = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() = onMain { ready() }
                override fun onFailure(errorType: ErrorType) = onMain { fail(errorType.toString()) }
            })
        } catch (e: RuntimeException) {
            fail(e.javaClass.simpleName)
        } catch (e: LinkageError) {
            fail(e.javaClass.simpleName)
        }
    }

    /** Says [text] after the lead-in, replacing anything still being said. */
    fun say(text: String) = onMain {
        when (state) {
            State.STARTING -> pending = text
            State.FAILED -> DiagLog.w("음성 엔진 없음: 화면 자막만 표시 — $text")
            State.READY -> speakNow(text)
        }
    }

    fun shutdown() = onMain {
        main.removeCallbacksAndMessages(null)
        pending = null
        tts?.let {
            if (state == State.READY) runCatching { it.removeEventListener(events) }
            runCatching { it.release() }
        }
        tts = null
        state = State.FAILED
    }

    private fun ready() {
        val t = tts
        if (state != State.STARTING || t == null) return
        t.addEventListener(events)
        t.setLocale(Locale.KOREA)
        state = State.READY
        DiagLog.w("음성 안내 준비됨")
        pending?.let { pending = null; speakNow(it) }
    }

    private fun fail(reason: String) {
        if (state != State.STARTING) return
        state = State.FAILED
        DiagLog.w("음성 엔진을 쓸 수 없음: $reason")
        tts?.let { runCatching { it.release() } }
        tts = null
        pending = null
    }

    private fun speakNow(text: String) {
        val t = tts ?: return
        try {
            t.stop()
            t.speak(LEAD_IN + text, true)
        } catch (e: RuntimeException) {
            DiagLog.w("음성 안내 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        private const val START_TIMEOUT_MS = 15_000L

        /** Said before every line; the rough start of the engine falls on it. */
        const val LEAD_IN = "차멀미 케어에서 안내해 드릴게요. "
    }
}
