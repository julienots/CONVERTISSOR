package com.superessence.app.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.superessence.app.core.SavedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class Peer(val name: String, val host: String, val port: Int = Transfer.PORT)

data class HotspotInfo(val ssid: String, val password: String)

data class TransferProgress(
    val sending: Boolean,
    val peer: String,
    val fileName: String,
    val fileIndex: Int,
    val fileCount: Int,
    val bytes: Long,
    val total: Long,
    val speedBps: Long,
)

data class TransferUi(
    val receiving: Boolean = false,
    val addresses: List<String> = emptyList(),
    val hotspot: HotspotInfo? = null,
    val current: TransferProgress? = null,
    val events: List<String> = emptyList(),
    val received: List<SavedFile> = emptyList(),
)

object Transfer {
    const val PORT = 47800
    const val DISCOVERY_PORT = 47801
    const val MAGIC = 0x53455331 // "SES1"
    const val DISCOVER = "SE_DISCOVER"
    const val HERE = "SE_HERE"

    val ui = MutableStateFlow(TransferUi())

    internal val sendQueue = java.util.concurrent.ConcurrentLinkedQueue<Pair<Peer, List<Uri>>>()

    fun log(msg: String) = ui.update { it.copy(events = (listOf(msg) + it.events).take(30)) }

    fun deviceName(ctx: Context): String =
        runCatching { Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL)

    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .filter { it.isNotBlank() }
            .distinct()
    }.getOrDefault(emptyList())

    /** Contenu du QR code affiché par le récepteur. */
    fun qrPayload(ctx: Context, host: String) = "fluxo://$host:$PORT/" + Uri.encode(deviceName(ctx))

    fun parseQr(text: String): Peer? {
        if (!text.startsWith("fluxo://")) return null
        val rest = text.removePrefix("fluxo://")
        val hostPort = rest.substringBefore('/')
        val name = Uri.decode(rest.substringAfter('/', "Appareil"))
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', PORT.toString()).toIntOrNull() ?: PORT
        return Peer(name.ifBlank { host }, host, port)
    }

    fun startReceiving(ctx: Context) = service(ctx, TransferService.ACTION_RECEIVE_START)
    fun stopReceiving(ctx: Context) = service(ctx, TransferService.ACTION_RECEIVE_STOP)
    fun startHotspot(ctx: Context) = service(ctx, TransferService.ACTION_HOTSPOT_START)
    fun stopHotspot(ctx: Context) = service(ctx, TransferService.ACTION_HOTSPOT_STOP)

    fun send(ctx: Context, peer: Peer, uris: List<Uri>) {
        sendQueue += peer to uris
        service(ctx, TransferService.ACTION_SEND)
    }

    private fun service(ctx: Context, action: String) {
        val i = Intent(ctx, TransferService::class.java).setAction(action)
        if (action == TransferService.ACTION_RECEIVE_STOP || action == TransferService.ACTION_HOTSPOT_STOP) {
            ctx.startService(i)
        } else {
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    /** Cherche les appareils Fluxo en mode réception sur le réseau local (~4 s). */
    suspend fun discover(onPeer: (Peer) -> Unit) = withContext(Dispatchers.IO) {
        val mine = localAddresses().toSet()
        val found = HashSet<String>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 400
            val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
            val msg = DISCOVER.toByteArray()
            val buf = ByteArray(512)
            repeat(10) {
                targets.forEach { addr ->
                    runCatching { socket.send(DatagramPacket(msg, msg.size, addr, DISCOVERY_PORT)) }
                }
                val until = System.currentTimeMillis() + 400
                while (System.currentTimeMillis() < until) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                    val text = String(packet.data, 0, packet.length)
                    val parts = text.split('|')
                    val host = packet.address.hostAddress ?: continue
                    if (parts.size >= 3 && parts[0] == HERE && host !in mine && found.add(host)) {
                        val peer = Peer(parts[1], host, parts[2].toIntOrNull() ?: PORT)
                        withContext(Dispatchers.Main) { onPeer(peer) }
                    }
                }
            }
        }
    }

    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())
}
