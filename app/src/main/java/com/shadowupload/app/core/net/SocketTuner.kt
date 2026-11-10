package com.shadowupload.app.core.net

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

/**
 * ضبط مقابس TCP لتحقيق أقصى إنتاجية على الشبكة المحلية.
 *
 * لماذا هذه الإعدادات؟
 *  - TCP_NODELAY: يمنع خوارزمية Nagle من تجميع الحزم → زمن استجابة أدنى.
 *  - SO_SNDBUF/SO_RCVBUF ≥ 1 ميجابايت: يسمح بنافذة انزلاق كبيرة، وهو العامل
 *    الأهم في رفع الإنتاجية على WiFi (RTT ~2-5 مللي ثانية).
 *  - SO_KEEPALIVE: يكتشف انقطاع الطرف الآخر بدل التعليق.
 *  - connect timeout قصير جداً (600 مللي ثانية) لتحقيق مصافحة < 0.2 ثانية
 *    على الشبكة المحلية، مع إعادة محاولة سريعة.
 */
object SocketTuner {

    const val CONNECT_TIMEOUT_MS = 800
    const val READ_TIMEOUT_MS = 30_000
    const val HANDSHAKE_TIMEOUT_MS = 4_000

    /** إنشاء مقبس مضبوط لأداء أقصى */
    fun tunedSocket(
        host: String,
        port: Int,
        bufferSize: Int,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS
    ): Socket {
        val socket = Socket()
        configure(socket, bufferSize)
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = READ_TIMEOUT_MS
        return socket
    }

    /** ضبط مقبس موجود (يُستخدم أيضاً للمقابس المقبولة في الخادم) */
    fun configure(socket: Socket, bufferSize: Int) {
        runCatching { socket.tcpNoDelay = true }
        runCatching { socket.keepAlive = true }
        runCatching { socket.sendBufferSize = bufferSize }
        runCatching { socket.receiveBufferSize = bufferSize }
        // محاولة تفعيل خيارات إضافية عند توفرها (لا تفشل إن لم تدعمها المنصة)
        runCatching {
            socket.setPerformancePreferences(0, 2, 1)
        }
    }

    /**
     * إنشاء مقبس خادم مع إعادة استخدام العنوان وإنشاء خلفية أقصى
     * لتفادي رفض الاتصالات المتوازية (backlog).
     */
    fun tunedServerSocket(port: Int, bufferSize: Int): java.net.ServerSocket {
        val server = java.net.ServerSocket()
        server.reuseAddress = true
        runCatching { server.receiveBufferSize = bufferSize }
        server.bind(InetSocketAddress(port), BACKLOG)
        return server
    }

    /** حجم مخزن فعلي بعد الضبط (قد يعدله النظام) */
    fun effectiveBuffer(socket: Socket): Int =
        runCatching { socket.sendBufferSize }.getOrDefault(0)

    /** هل الاستثناء ناتج عن إعادة استخدام المنفذ؟ */
    fun isAddressInUse(error: Throwable): Boolean =
        error is SocketException && error.message?.contains("in use", ignoreCase = true) == true

    const val BACKLOG = 128
}
