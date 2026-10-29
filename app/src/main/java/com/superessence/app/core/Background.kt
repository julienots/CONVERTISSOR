package com.superessence.app.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.superessence.app.MainActivity
import com.superessence.app.SuperEssenceApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compte les tâches en cours (conversions, liens YouTube…) et garde l'app
 * vivante au premier plan tant qu'il en reste, même si on quitte l'app.
 */
object Background {
    private val count = AtomicInteger(0)
    val active = MutableStateFlow(0)
    @Volatile
    var lastLabel = "Fluxo travaille…"
        private set
    private val doneId = AtomicInteger(5000)

    /** Vrai quand l'app est à l'écran (mis à jour par MainActivity). */
    @Volatile
    var visible = false

    fun begin(ctx: Context, label: String) {
        lastLabel = label
        val n = count.incrementAndGet()
        active.value = n
        if (n == 1 || !WorkService.running) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, WorkService::class.java))
            }
        }
    }

    fun end(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        active.value = count.decrementAndGet().coerceAtLeast(0)
    }

    /** Notification « c'est prêt » (visible même app fermée). */
    fun notifyDone(ctx: Context, text: String) {
        if (visible) return
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, SuperEssenceApp.CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Fluxo")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(doneId.incrementAndGet(), n) }
    }
}

class WorkService : Service() {
    companion object {
        @Volatile
        var running = false
            private set
        private const val ID = 4790
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        lastStartId = startId
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID, notif(Background.active.value), type)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fluxo:work")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        if (watcher == null) {
            watcher = scope.launch {
                Background.active.collect { n ->
                    if (n <= 0) {
                        ServiceCompat.stopForeground(this@WorkService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId)
                    } else {
                        getSystemService(NotificationManager::class.java).notify(ID, notif(n))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notif(n: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SuperEssenceApp.CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Fluxo — $n tâche(s) en cours")
            .setContentText("${Background.lastLabel} · tu peux quitter l'app, ça continue")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        running = false
        runCatching { wake?.release() }
        scope.cancel()
        super.onDestroy()
    }
}
