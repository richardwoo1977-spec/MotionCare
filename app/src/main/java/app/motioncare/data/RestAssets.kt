// ver 1.1.0 | 2026-11-12 11:25
package app.motioncare.data

import android.content.Context
import app.motioncare.core.RestCatalog

/**
 * Reads the bundled Korea Expressway Corporation files once: rest areas (assets/rest_areas.csv) and drowsy-driver
 * shelters (assets/drowsy_shelters.csv, 전국졸음쉼터표준데이터 converted to UTF-8).
 */
object RestAssets {
    const val FILE = "rest_areas.csv"
    const val SHELTERS = "drowsy_shelters.csv"

    fun ensureLoaded(context: Context) {
        if (DemoNavi.catalogSize > 0) return
        try {
            val text = context.applicationContext.assets.open(FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val shelters = try {
                context.applicationContext.assets.open(SHELTERS).bufferedReader(Charsets.UTF_8).use { RestCatalog.parseShelters(it.readText()) }
            } catch (e: Exception) {
                DiagLog.w("졸음쉼터 파일 읽기 실패: $e")
                emptyList()
            }
            DemoNavi.setCatalog(RestCatalog.parse(text) + shelters)
            DiagLog.w("쉼터 파일 읽음 · 휴게소 ${DemoNavi.restAreaCount}곳 · 졸음쉼터 ${DemoNavi.shelterCount}곳")
        } catch (e: Exception) {
            DiagLog.w("휴게소 파일 읽기 실패: $e")
        }
    }
}
