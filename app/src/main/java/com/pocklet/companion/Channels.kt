package com.pocklet.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Channels {
    const val SERVICE = "pocklet_service"
    const val REMINDER = "pocklet_reminder"

    fun ensure(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(SERVICE, "Layanan Pocklet", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(REMINDER, "Pengingat", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
}
