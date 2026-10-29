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
            NotificationChannel(CHANNEL_TRANSFER, "Tâches en cours", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Tâches terminées", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CHANNEL_TRANSFER = "transfer"
        const val CHANNEL_DONE = "done"
        lateinit var instance: SuperEssenceApp
            private set
    }
}
