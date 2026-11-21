package com.fakelag.ff

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.*
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import kotlin.random.Random

class FakeLagVpnService : VpnService() {

    companion object {
        const val CMD_ENABLE   = "com.fakelag.ff.ENABLE"
        const val CMD_DISABLE  = "com.fakelag.ff.DISABLE"
        const val CMD_TOGGLE   = "com.fakelag.ff.TOGGLE"
        const val CHANNEL_ID   = "fakelag_fg"
        const val NOTIF_ID     = 1
        const val ROTATE_MS    = 25 * 60 * 1000L

        val FF_PREFIXES = setOf(
            "103.1.129","103.1.130",
            "43.251.47","43.251.48",
            "185.8.165","185.8.166"
        )

        val BYPASS_PROFILES = listOf(
            BypassProfile(170, 35, 140, 0.10, 32, 13),
            BypassProfile(210, 55, 190, 0.14, 28, 18),
            BypassProfile(140, 45, 110, 0.08, 36, 11),
            BypassProfile(240, 30, 160, 0.17, 24, 20),
            BypassProfile(160, 60, 200, 0.12, 40, 15),
            BypassProfile(195, 40, 130, 0.09, 30, 16),
        )
    }

    data class BypassProfile(
        val baseMs: Long,
        val sigmaMs: Long,
        val dripMs: Long,
        val noiseProb: Double,
        val heartbeatMax: Int,
        val baselineMax: Long
    )

    data class DelayedPkt(val data: ByteArray, val releaseAt: Long)

    private val lagActive     = AtomicBoolean(false)
    private val running       = AtomicBoolean(false)
    private val outQueue      = LinkedBlockingDeque<DelayedPkt>(4096)
    private val rng           = Random(System.nanoTime())
    private val handler       = Handler(Looper.getMainLooper())

    private var vpnInterface: ParcelFileDescriptor? = null
    private var currentProfile = BYPASS_PROFILES[0]
    private var profileIndex   = 0

    private val rotateRunnable = object : Runnable {
        override fun run() {
            profileIndex = (profileIndex + 1) % BYPASS_PROFILES.size
            currentProfile = BYPASS_PROFILES[profileIndex]
            updateNotification()
            handler.postDelayed(this, ROTATE_MS)
        }
    }

    private fun gaussDelay(base: Long, sigma: Long): Long {
        val u1 = rng.nextDouble().coerceAtLeast(1e-10)
        val u2 = rng.nextDouble()
        val z  = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
        return (base + sigma * z).toLong().coerceAtLeast(15L)
    }

    private fun isHeartbeat(pkt: ByteArray) =
        pkt.size > 28 && (pkt.size - 28) <= currentProfile.heartbeatMax

    private fun isFF(pkt: ByteArray): Boolean {
        if (pkt.size < 20 || (pkt[9].toInt() and 0xFF) != 17) return false
        val ip = "${pkt[16].toInt() and 0xFF}.${pkt[17].toInt() and 0xFF}.${pkt[18].toInt() and 0xFF}"
        return FF_PREFIXES.any { ip.startsWith(it) }
    }

    private fun fwd(pkt: ByteArray) {
        try {
            if (pkt.size < 28) return
            val dst  = InetAddress.getByAddress(pkt.copyOfRange(16, 20))
            val port = ((pkt[22].toInt() and 0xFF) shl 8) or (pkt[23].toInt() and 0xFF)
            val pay  = pkt.copyOfRange(28, pkt.size)
            DatagramSocket().use { s -> protect(s); s.send(DatagramPacket(pay, pay.size, dst, port)) }
        } catch (_: Exception) {}
    }

    private fun startVpn() {
        vpnInterface = Builder()
            .setSession("FakeLagFF")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)
            .setMtu(1500)
            .establish() ?: return

        running.set(true)
        val fis = FileInputStream(vpnInterface!!.fileDescriptor)

        Thread {
            val buf = ByteArray(32767)
            while (running.get()) {
                val len = try { fis.read(buf) } catch (_: Exception) { break }
                if (len <= 0) continue
                val pkt = buf.copyOf(len)
                val p   = currentProfile
                when {
                    !isFF(pkt)       -> fwd(pkt)
                    isHeartbeat(pkt) -> fwd(pkt)
                    lagActive.get()  -> {
                        val rel = System.currentTimeMillis() + gaussDelay(p.baseMs, p.sigmaMs)
                        if (outQueue.size < 4000) outQueue.offer(DelayedPkt(pkt, rel))
                    }
                    else -> {
                        if (rng.nextDouble() < p.noiseProb) {
                            val rel = System.currentTimeMillis() + rng.nextLong(p.baselineMax)
                            outQueue.offer(DelayedPkt(pkt, rel))
                        } else fwd(pkt)
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }

        Thread {
            while (running.get()) {
                val now = System.currentTimeMillis()
                val p   = currentProfile
                if (!lagActive.get() && outQueue.isNotEmpty()) {
                    val ready = mutableListOf<DelayedPkt>()
                    val it2 = outQueue.iterator()
                    while (it2.hasNext()) {
                        val d = it2.next()
                        if (!lagActive.get() || d.releaseAt <= now) { ready.add(d); it2.remove() }
                    }
                    if (ready.isNotEmpty()) {
                        val interval = p.dripMs / ready.size.coerceAtLeast(1)
                        ready.forEach { d -> fwd(d.data); Thread.sleep(interval.coerceAtLeast(1)) }
                    }
                } else {
                    val it2 = outQueue.iterator()
                    while (it2.hasNext()) {
                        val d = it2.next()
                        if (d.releaseAt <= now) { fwd(d.data); it2.remove() }
                    }
                }
                Thread.sleep(1)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun buildNotif(isOn: Boolean): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Fake Lag FF", NotificationManager.IMPORTANCE_LOW)
                )

        val toggleIntent = Intent(this, FakeLagVpnService::class.java).apply { action = CMD_TOGGLE }
        val pi = PendingIntent.getService(this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val label  = if (isOn) "● FAKE LAG ATIVO" else "○ FAKE LAG INATIVO"
        val action = if (isOn) "DESATIVAR" else "ATIVAR"
        val profN  = "perfil ${profileIndex+1}/${BYPASS_PROFILES.size}"

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(label)
            .setContentText("bypass $profN · rotaciona automaticamente")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .addAction(android.R.drawable.ic_media_play, action, pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotif(lagActive.get()))
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotif(false))
        if (!running.get()) startVpn()
        handler.postDelayed(rotateRunnable, ROTATE_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CMD_ENABLE  -> { lagActive.set(true);  updateNotification() }
            CMD_DISABLE -> { lagActive.set(false); updateNotification() }
            CMD_TOGGLE  -> { lagActive.set(!lagActive.get()); updateNotification() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        handler.removeCallbacks(rotateRunnable)
        vpnInterface?.close()
        val i = Intent(this, FakeLagVpnService::class.java).apply { action = CMD_ENABLE }
        startService(i)
        super.onDestroy()
    }
}
