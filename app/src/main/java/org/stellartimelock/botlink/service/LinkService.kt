package org.stellartimelock.botlink.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.stellartimelock.botlink.BotLinkApp
import org.stellartimelock.botlink.MainActivity
import org.stellartimelock.botlink.R

class LinkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as BotLinkApp
        app.hub.start()
        val pending = app.notes.pendingCount()
        val text = if (pending > 0) {
            getString(R.string.service_pending, pending)
        } else {
            getString(R.string.service_listening)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, BotLinkApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(42, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        (application as BotLinkApp).hub.stop()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, LinkService::class.java)
            context.startForegroundService(intent)
        }
    }
}
