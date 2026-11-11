// ver 1.0.0 | 2026-11-05 13:34
package app.motioncare.service

import android.content.Context
import android.speech.tts.TextToSpeech
import app.motioncare.data.DiagLog
import java.util.Locale

/** Text-to-speech for the spoken tips. Every spoken line is also shown as a caption on screen. */
class Speaker(context: Context) {
    private var ready = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale.KOREAN)
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                DiagLog.w("TTS 준비 한국어=${if (ready) "가능" else "불가"}")
            } else {
                DiagLog.w("TTS 초기화 실패 status=$status")
            }
        }
    }

    fun say(text: String) {
        if (!ready) {
            DiagLog.w("TTS 미준비: 화면 자막만 표시 — $text")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "motioncare")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
