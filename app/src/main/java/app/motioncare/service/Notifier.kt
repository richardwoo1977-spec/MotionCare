// ver 1.0.0 | 2026-11-05 13:34
package app.motioncare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.motioncare.R
import app.motioncare.ui.MainActivity

object Notifier {
    const val CHANNEL_STATUS = "motioncare_status"
    const val ID_STATUS = 2001

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "차멀미 케어 상태", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun status(context: Context, text: String): Notification =
        Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_care)
            .setContentTitle("차멀미 케어 작동 중")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .build()

    fun updateStatus(context: Context, text: String) {
        context.getSystemService(NotificationManager::class.java).notify(ID_STATUS, status(context, text))
    }
}
