// ver 1.0.0 | 2026-11-05 22:53
package app.motioncare.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.motioncare.data.DiagLog

/**
 * Starts the care service when the car finishes booting, so the app works without being opened.
 * Android starts this only for an app that was opened at least once since install. Whether the car sends the boot
 * signal on every ignition (instead of waking from sleep) is not confirmed yet; 점검 shows when the service started.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        DiagLog.w("부팅 완료 신호 받음 · 서비스 시작")
        CareService.start(context)
    }
}
