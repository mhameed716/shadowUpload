package com.shadowupload.app.core.net

import com.shadowupload.app.util.NetworkUtils
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ماسح الشبكة المحلية فائق السرعة.
 *
 * لماذا هذا التصميم؟
 *  - فحص 254 عنواناً بالتتابع مع مهلة 800 مللي ثانية يستغرق دقائق. هنا نفحص
 *    كل العناوين على 64 خيطاً متوازياً بمهلة 120 مللي ثانية → النتيجة خلال
 *    أقل من ثانية واحدة عملياً.
 *  - لا نستخدم ICMP (ممنوع على أندرويد بلا root) بل TCP connect + مصافحة
 *    بروتوكول shadowUpload، لذا لا يظهر إلا الأجهزة التي تشغّل التطبيق فعلاً.
 *  - يعمل أيضاً على نقاط اتصال الهاتف (Hotspot) حيث tethering.
 */
class LanScanner(
    private val port: Int,
    private val timeoutMs: Int = 140,
    private val parallelism: Int = 64
) {

    /** جهاز تم اكتشافه فعلياً */
    data class DiscoveredPeer(val name: String, val deviceId: String, val ip: String, val port: Int)

    /**
     * فحص الشبكة المحلية بحثاً عن أجهزة shadowUpload.
     * @param onFound يُستدعى عند كل اكتشاف (قد يكون على خيط خلفي)
     */
    fun scanOnce(
        onFound: (DiscoveredPeer) -> Unit,
        onFinished: (Int) -> Unit
    ): Thread {
        val cancelled = AtomicBoolean(false)
        val thread = Thread {
            val hosts = NetworkUtils.candidateHosts(SCAN_LIMIT)
            if (hosts.isEmpty()) {
                onFinished(0)
                return@Thread
            }
            val pool = Executors.newFixedThreadPool(parallelism)
            val found = ConcurrentLinkedQueue<DiscoveredPeer>()
            val latch = CountDownLatch(hosts.size)
            val localIp = NetworkUtils.localIpAddress()

            hosts.forEach { host ->
                if (cancelled.get()) {
                    latch.countDown()
                    return@forEach
                }
                pool.execute {
                    try {
                        if (host == localIp) return@execute
                        val peer = probeHost(host)
                        if (peer != null) {
                            found.add(peer)
                            onFound(peer)
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            // الانتظار بحد أقصى زمني حتى لا يتعلّق الفحص
            latch.await(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            pool.shutdownNow()
            onFinished(found.size)
        }
        thread.name = "LanScanner"
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /** فحص عنوان واحد: هل يشغّل shadowUpload؟ */
    fun probeHost(host: String): DiscoveredPeer? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            // نطابق بصمة البروتوكول فقط — استجابة سريعة بلا تخصيص ذاكرة كبير
            WireProtocol.writeMagic(out)
            WireProtocol.writeFrame(
                out,
                WireProtocol.MessageType.HELLO,
                ControlJson.encodeHello("scanner", "scanner", "1.0.0")
            )
            val response = runCatching { WireProtocol.readFrame(input) }.getOrNull() ?: return null
            if (response.type == WireProtocol.MessageType.HELLO_ACK) {
                val peer = ControlJson.decodePeer(response.text(), host, port)
                DiscoveredPeer(
                    name = peer.name,
                    deviceId = peer.deviceId,
                    ip = host,
                    port = port
                )
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    companion object {
        const val SCAN_LIMIT = 254
        const val SCAN_TIMEOUT_SECONDS = 8L
    }
}
