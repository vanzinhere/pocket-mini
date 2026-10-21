package com.pocklet.companion

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class ServerService : Service() {

    companion object {
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.pocklet.companion.STOP"

        @Volatile var running = false
        @Volatile var lastError: String? = null
        private var server: LocalServer? = null

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ServerService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ServerService::class.java).setAction(ACTION_STOP))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        Channels.ensure(this)
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }

        synchronized(ServerService::class.java) {
            if (server == null) {
                try {
                    server = LocalServer(applicationContext).also { it.start() }
                    lastError = null
                    running = true
                } catch (e: java.net.BindException) {
                    lastError = "Port ${LocalServer.PORT} sudah dipakai. Paksa berhenti aplikasi lalu coba lagi."
                    running = false
                    stopSelf()
                    return START_NOT_STICKY
                } catch (e: Exception) {
                    lastError = "Gagal memulai server: ${e.message}"
                    running = false
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        synchronized(ServerService::class.java) {
            server?.stop()
            server = null
        }
        running = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Channels.SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Pocklet Companion aktif")
            .setContentText("Melayani ESP di port ${LocalServer.PORT}")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Hentikan", stop)
            .build()
    }
}
