package com.superessence.app.transfer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.superessence.app.MainActivity
import com.superessence.app.SuperEssenceApp
import com.superessence.app.core.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Service au premier plan : réception (serveur TCP + réponse aux découvertes) et envois. */
class TransferService : Service() {

    companion object {
        const val ACTION_RECEIVE_START = "receive_start"
        const val ACTION_RECEIVE_STOP = "receive_stop"
        const val ACTION_SEND = "send"
        const val ACTION_HOTSPOT_START = "hotspot_start"
        const val ACTION_HOTSPOT_STOP = "hotspot_stop"
        private const val NOTIF_ID = 4780
        private const val BUF = 1 shl 20
        private const val SOCKET_BUF = 4 shl 20
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var discovery: DatagramSocket? = null
    private var receiveJob: Job? = null
    private val activeSends = AtomicInteger(0)
    private val activeReceives = AtomicInteger(0)
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var lastNotif = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RECEIVE_START -> {
                goForeground("Prêt à recevoir des fichiers")
                startReceiving()
            }
            ACTION_RECEIVE_STOP -> {
                stopReceiving()
                stopIfIdle()
            }
            ACTION_SEND -> {
                goForeground("Envoi en cours…")
                while (true) {
                    val (peer, uris) = Transfer.sendQueue.poll() ?: break
                    activeSends.incrementAndGet()
                    scope.launch {
                        try {
                            send(peer, uris)
                        } catch (e: Throwable) {
                            Transfer.log("❌ Envoi vers ${peer.name} échoué : ${e.message ?: e.javaClass.simpleName}")
                        } finally {
                            Transfer.ui.update { it.copy(current = null) }
                            activeSends.decrementAndGet()
                            Handler(Looper.getMainLooper()).post { stopIfIdle() }
                        }
                    }
                }
            }
            ACTION_HOTSPOT_START -> {
                goForeground("Point d'accès Fluxo actif")
                startHotspot()
            }
            ACTION_HOTSPOT_STOP -> {
                stopHotspot()
                stopIfIdle()
            }
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopReceiving()
        stopHotspot()
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- Réception ----------

    private fun startReceiving() {
        if (receiveJob?.isActive == true) return
        acquireLocks()
        Transfer.ui.update { it.copy(receiving = true, addresses = Transfer.localAddresses()) }
        receiveJob = scope.launch {
            launch { discoveryResponder() }
            try {
                ServerSocket().use { ss ->
                    ss.reuseAddress = true
                    ss.receiveBufferSize = SOCKET_BUF
                    ss.bind(InetSocketAddress(Transfer.PORT))
                    server = ss
                    while (isActive) {
                        val socket = ss.accept()
                        launch { receive(socket) }
                    }
                }
            } catch (e: IOException) {
                if (server != null && Transfer.ui.value.receiving) Transfer.log("Réception arrêtée : ${e.message}")
            }
        }
    }

    private fun stopReceiving() {
        Transfer.ui.update { it.copy(receiving = false) }
        runCatching { server?.close() }
        runCatching { discovery?.close() }
        server = null
        discovery = null
        receiveJob?.cancel()
        receiveJob = null
    }

    private fun discoveryResponder() {
        try {
            DatagramSocket(null).use { sock ->
                sock.reuseAddress = true
                sock.broadcast = true
                sock.bind(InetSocketAddress(Transfer.DISCOVERY_PORT))
                discovery = sock
                val buf = ByteArray(256)
                val name = Transfer.deviceName(this).replace("|", " ")
                while (!sock.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    if (String(p.data, 0, p.length) == Transfer.DISCOVER) {
                        val reply = "${Transfer.HERE}|$name|${Transfer.PORT}".toByteArray()
                        sock.send(DatagramPacket(reply, reply.size, p.address, p.port))
                    }
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun receive(socket: Socket) {
        activeReceives.incrementAndGet()
        acquireWake()
        socket.use { s ->
            try {
                s.receiveBufferSize = SOCKET_BUF
                s.soTimeout = 120_000
                val input = DataInputStream(BufferedInputStream(s.getInputStream(), BUF))
                if (input.readInt() != Transfer.MAGIC) return
                val sender = input.readUTF()
                val count = input.readInt()
                val total = input.readLong()
                Transfer.log("📥 Réception de $count fichier(s) depuis $sender (${Storage.humanSize(total)})")
                val buf = ByteArray(BUF)
                val meter = Meter(total)
                repeat(count) { index ->
                    val name = input.readUTF()
                    val size = input.readLong()
                    val dest = Transfer.newInboxFile(this, name)
                    try {
                        dest.outputStream().use { out ->
                            var left = size
                            while (left > 0) {
                                val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                                if (n < 0) throw EOFException("Connexion interrompue")
                                out.write(buf, 0, n)
                                left -= n
                                meter.add(n)?.let { speed ->
                                    publish(TransferProgress(false, sender, name, index + 1, count, meter.done, total, speed))
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        dest.delete()
                        dest.parentFile?.delete()
                        throw e
                    }
                    Transfer.addReceived(ReceivedFile(dest.absolutePath, dest.name, size, sender, dest))
                }
                DataOutputStream(s.getOutputStream()).apply { writeInt(1); flush() }
                Transfer.log("✅ $count fichier(s) reçus de $sender en ${meter.elapsedText()} : appuie sur « Télécharger »")
            } catch (e: Throwable) {
                Transfer.log("❌ Réception échouée : ${e.message ?: e.javaClass.simpleName}")
            } finally {
                Transfer.ui.update { it.copy(current = null) }
                activeReceives.decrementAndGet()
                releaseWakeIfIdle()
                notifyText(if (Transfer.ui.value.receiving) "Prêt à recevoir des fichiers" else "Terminé")
            }
        }
    }

    // ---------- Envoi ----------

    private fun send(peer: Peer, uris: List<Uri>) {
        acquireWake()
        acquireLocks()
        // Taille de chaque fichier (copie en cache si le fournisseur ne la donne pas)
        val items = uris.map { uri ->
            val name = Storage.displayName(this, uri)
            var size = Storage.size(this, uri)
            var src = uri
            if (size < 0) {
                val tmp = Storage.copyToFile(this, uri, File(Storage.workDir(this, "send"), "f.bin"))
                size = tmp.length()
                src = Uri.fromFile(tmp)
            }
            Triple(name, size, src)
        }
        val total = items.sumOf { it.second }
        Transfer.log("📤 Connexion à ${peer.name} (${peer.host})…")
        Socket().use { s ->
            s.sendBufferSize = SOCKET_BUF
            s.connect(InetSocketAddress(peer.host, peer.port), 6_000)
            s.soTimeout = 120_000
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), BUF))
            out.writeInt(Transfer.MAGIC)
            out.writeUTF(Transfer.deviceName(this))
            out.writeInt(items.size)
            out.writeLong(total)
            val buf = ByteArray(BUF)
            val meter = Meter(total)
            items.forEachIndexed { index, (name, size, src) ->
                out.writeUTF(name)
                out.writeLong(size)
                val input = if (src.scheme == "file") File(src.path!!).inputStream() else contentResolver.openInputStream(src)
                    ?: throw IOException("Impossible de lire $name")
                input.use { inp ->
                    var left = size
                    while (left > 0) {
                        val n = inp.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                        if (n < 0) throw EOFException("$name est plus court que prévu")
                        out.write(buf, 0, n)
                        left -= n
                        meter.add(n)?.let { speed ->
                            publish(TransferProgress(true, peer.name, name, index + 1, items.size, meter.done, total, speed))
                        }
                    }
                }
            }
            out.flush()
            val ack = DataInputStream(s.getInputStream()).readInt()
            if (ack != 1) throw IOException("Le destinataire a refusé")
            Transfer.log("✅ ${items.size} fichier(s) envoyés à ${peer.name} en ${meter.elapsedText()} (${Storage.humanSize(total)})")
        }
        releaseWakeIfIdle()
    }

    // ---------- Point d'accès local ----------

    @SuppressLint("MissingPermission")
    private fun startHotspot() {
        if (hotspot != null) return
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspot = reservation
                    val (ssid, pass) = hotspotCredentials(reservation)
                    Transfer.ui.update { it.copy(hotspot = HotspotInfo(ssid, pass)) }
                    Transfer.log("📶 Point d'accès « $ssid » créé")
                    startReceiving()
                    Handler(Looper.getMainLooper()).postDelayed({
                        Transfer.ui.update { it.copy(addresses = Transfer.localAddresses()) }
                    }, 1500)
                }

                override fun onStopped() {
                    hotspot = null
                    Transfer.ui.update { it.copy(hotspot = null) }
                }

                override fun onFailed(reason: Int) {
                    Transfer.log("❌ Impossible de créer le point d'accès (code $reason). Désactive ton partage de connexion et réessaie.")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Throwable) {
            Transfer.log("❌ Point d'accès indisponible : ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun hotspotCredentials(r: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val c = r.softApConfiguration
            (c.ssid ?: "Fluxo") to (c.passphrase ?: "")
        } else {
            val c = r.wifiConfiguration
            (c?.SSID ?: "Fluxo") to (c?.preSharedKey ?: "")
        }

    private fun stopHotspot() {
        runCatching { hotspot?.close() }
        hotspot = null
        Transfer.ui.update { it.copy(hotspot = null) }
    }

    // ---------- Utilitaires ----------

    private fun publish(p: TransferProgress) {
        Transfer.ui.update { it.copy(current = p) }
        val now = System.currentTimeMillis()
        if (now - lastNotif > 1000) {
            lastNotif = now
            val pct = if (p.total > 0) (p.bytes * 100 / p.total).toInt() else 0
            notifyText(
                (if (p.sending) "Envoi à " else "Réception de ") + p.peer + " — $pct % · ${Storage.humanSize(p.speedBps)}/s",
                pct,
            )
        }
    }

    private fun buildNotif(text: String, progress: Int = -1): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SuperEssenceApp.CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Fluxo — Transfert")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply { if (progress >= 0) setProgress(100, progress, false) }
            .build()
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotif(text), type)
    }

    private fun notifyText(text: String, progress: Int = -1) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text, progress))
    }

    private fun stopIfIdle() {
        if (!Transfer.ui.value.receiving && hotspot == null && activeSends.get() == 0 && activeReceives.get() == 0) {
            releaseLocks()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "fluxo:transfer").apply { setReferenceCounted(false); acquire() }
        }
        if (multicastLock == null) {
            multicastLock = wm.createMulticastLock("fluxo:discovery").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun acquireWake() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fluxo:transfer")
                .apply { setReferenceCounted(false) }
        }
        wakeLock?.acquire(3 * 60 * 60 * 1000L)
    }

    private fun releaseWakeIfIdle() {
        if (activeSends.get() <= 1 && activeReceives.get() == 0) runCatching { wakeLock?.release() }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.release() }
        runCatching { multicastLock?.release() }
        runCatching { wakeLock?.release() }
        wifiLock = null
        multicastLock = null
    }

    /** Mesure de débit : renvoie la vitesse (o/s) au plus toutes les 250 ms. */
    private class Meter(val total: Long) {
        private val start = System.nanoTime()
        private var lastT = start
        private var lastB = 0L
        var done = 0L
            private set

        fun add(n: Int): Long? {
            done += n
            val now = System.nanoTime()
            if (now - lastT < 250_000_000L && done < total) return null
            val speed = ((done - lastB) * 1_000_000_000L / (now - lastT).coerceAtLeast(1))
            lastT = now
            lastB = done
            return speed
        }

        fun elapsedText(): String {
            val s = (System.nanoTime() - start) / 1_000_000_000.0
            return if (s < 60) String.format(java.util.Locale.FRANCE, "%.1f s", s) else "${(s / 60).toInt()} min ${(s % 60).toInt()} s"
        }
    }
}
