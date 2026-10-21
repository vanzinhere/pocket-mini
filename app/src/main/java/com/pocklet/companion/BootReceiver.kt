package com.pocklet.companion

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = runBlocking { Prefs.autostart(ctx).first() }
        if (!enabled) return

        // Android 12+ membatasi FGS dari background, jadi minta pengguna mengetuk notifikasi.
        Channels.ensure(ctx)
        val open = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, Channels.REMINDER)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Pocklet Companion")
            .setContentText("Ketuk untuk mengaktifkan layanan")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(2, notif)
        } catch (e: SecurityException) {
            // izin notifikasi belum diberikan
        }
    }
}
