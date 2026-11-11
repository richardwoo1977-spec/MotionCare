// ver 1.0.0 | 2026-11-05 19:35
package app.motioncare.data

import android.content.Context
import app.motioncare.core.RestCatalog

/** Reads the bundled Korea Expressway Corporation rest-area file (assets/rest_areas.csv) once. */
object RestAssets {
    const val FILE = "rest_areas.csv"

    fun ensureLoaded(context: Context) {
        if (DemoNavi.catalogSize > 0) return
        try {
            val text = context.applicationContext.assets.open(FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
            DemoNavi.setCatalog(RestCatalog.parse(text))
            DiagLog.w("휴게소 파일 읽음 · ${DemoNavi.catalogSize}곳 · 기준일 ${DemoNavi.dataAsOf}")
        } catch (e: Exception) {
            DiagLog.w("휴게소 파일 읽기 실패: $e")
        }
    }
}
