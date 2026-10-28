package com.superessence.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class SuperEssenceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFER, "Transferts", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_TRANSFER = "transfer"
        lateinit var instance: SuperEssenceApp
            private set
    }
}
