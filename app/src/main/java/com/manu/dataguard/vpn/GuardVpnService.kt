package com.manu.dataguard.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import com.manu.dataguard.R
import com.manu.dataguard.data.AppResolver
import com.manu.dataguard.data.Event
import com.manu.dataguard.data.EventStore
import com.manu.dataguard.data.Rules
import com.manu.dataguard.data.Trackers
import com.manu.dataguard.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * An on-device "VPN" that only captures DNS. Every app on the phone must look up a hostname
 * before it can talk to a server, so by answering those lookups ourselves we learn which app
 * wants to reach which company — and we can refuse to answer for the ones the user blocked.
 * No traffic is sent to any outside VPN server.
 */
class GuardVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.manu.dataguard.START"
        const val ACTION_STOP = "com.manu.dataguard.STOP"

        @Volatile
        var isRunning = false
            private set

        private const val TAG = "DataGuard"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val VPN_DNS = "10.111.222.2"
        private const val MTU = 4096
        private const val CHANNEL = "guard"
        private val FALLBACK_DNS = listOf("1.1.1.1", "9.9.9.9")
    }

    private var tun: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private var reader: Thread? = null
    private lateinit var workers: ExecutorService
    private lateinit var store: EventStore
    private lateinit var rules: Rules
    private lateinit var apps: AppResolver
    private lateinit var connectivity: ConnectivityManager

    @Volatile private var upstreamCache: List<InetAddress> = emptyList()
    @Volatile private var upstreamCachedAt = 0L

    override fun onCreate() {
        super.onCreate()
        workers = Executors.newFixedThreadPool(8)
        store = EventStore.get(this)
        rules = Rules.get(this)
        apps = AppResolver.get(this)
        connectivity = getSystemService(ConnectivityManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        showNotification()
        if (tun == null && !startVpn()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onRevoke() {   // another VPN took over, or the user turned us off in Settings
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        workers.shutdownNow()
        super.onDestroy()
    }

    private fun startVpn(): Boolean {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32)            // only DNS enters the tunnel; real traffic flows normally
            .setMtu(MTU)
            .setBlocking(true)
            .setMetered(false)
        try {
            builder.addDisallowedApplication(packageName)   // our own upstream lookups bypass the tunnel
        } catch (_: PackageManager.NameNotFoundException) {
        }
        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e); null
        } ?: return false

        tun = pfd
        tunOut = FileOutputStream(pfd.fileDescriptor)
        isRunning = true
        val input = FileInputStream(pfd.fileDescriptor)
        reader = Thread({ readLoop(input) }, "dns-reader").also { it.start() }
        return true
    }

    private fun stopVpn() {
        isRunning = false
        reader?.interrupt()
        reader = null
        try { tun?.close() } catch (_: IOException) {}
        tun = null
        tunOut = null
    }

    private fun readLoop(input: FileInputStream) {
        val buf = ByteArray(32767)
        while (!Thread.currentThread().isInterrupted) {
            val n = try { input.read(buf) } catch (e: IOException) { break }
            if (n < 0) break
            if (n == 0) continue
            val q = Packets.parseDnsQuery(buf, n) ?: continue
            // Must be looked up right away, while the asking app's socket still exists.
            val uid = ownerUid(q)
            try {
                workers.execute { handle(q, uid) }
            } catch (_: Exception) {
                break   // pool shut down
            }
        }
    }

    private fun ownerUid(q: DnsQuery): Int = try {
        connectivity.getConnectionOwnerUid(
            OsConstants.IPPROTO_UDP,
            InetSocketAddress(InetAddress.getByAddress(q.srcAddr), q.srcPort),
            InetSocketAddress(InetAddress.getByAddress(q.dstAddr), q.dstPort),
        )
    } catch (e: Exception) {
        Process.INVALID_UID
    }

    private fun handle(q: DnsQuery, uid: Int) {
        val app = apps.resolve(uid)
        val tracker = Trackers.lookup(q.name)
        val decision = rules.decide(app.pkg, q.name, tracker != null)
        val answer = if (decision.blocked) Packets.blockedAnswer(q) else forward(q.dns) ?: Packets.failureAnswer(q)
        write(Packets.wrapAnswer(q, answer))
        if (q.name.isNotEmpty()) {
            store.record(
                Event(
                    ts = System.currentTimeMillis(),
                    pkg = app.pkg,
                    label = app.label,
                    domain = q.name,
                    tracker = tracker?.describe(),
                    blocked = decision.blocked,
                    reason = decision.reason,
                )
            )
        }
    }

    private fun write(packet: ByteArray) {
        val out = tunOut ?: return
        synchronized(out) {
            try { out.write(packet) } catch (_: IOException) {}
        }
    }

    /** Sends the lookup to a real DNS server over the phone's normal connection. */
    private fun forward(query: ByteArray): ByteArray? {
        for (server in upstreamServers()) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 4000
                    s.send(DatagramPacket(query, query.size, server, 53))
                    val rb = ByteArray(MTU)
                    val p = DatagramPacket(rb, rb.size)
                    s.receive(p)
                    return rb.copyOf(p.length)
                }
            } catch (_: IOException) {
                // try the next server
            }
        }
        return null
    }

    /** The DNS servers of the underlying Wi-Fi/mobile network, then public fallbacks. */
    @Suppress("DEPRECATION")
    private fun upstreamServers(): List<InetAddress> {
        val now = System.currentTimeMillis()
        if (now - upstreamCachedAt < 30_000 && upstreamCache.isNotEmpty()) return upstreamCache
        val found = mutableListOf<InetAddress>()
        for (net in connectivity.allNetworks) {
            val caps = connectivity.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val lp = connectivity.getLinkProperties(net) ?: continue
            for (dns in lp.dnsServers) {
                if (dns.hostAddress != VPN_DNS && dns !in found) found += dns
            }
        }
        for (f in FALLBACK_DNS) found += InetAddress.getByName(f)
        upstreamCache = found
        upstreamCachedAt = now
        return found
    }

    private fun showNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Protection status", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GuardVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Data Guard is on")
            .setContentText("Watching where your apps send data")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }
}
