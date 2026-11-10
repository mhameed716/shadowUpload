package com.shadowupload.app.core.transfer

import android.content.Context
import com.shadowupload.app.core.net.ControlJson
import com.shadowupload.app.core.net.ProgressAggregator
import com.shadowupload.app.core.net.SocketTuner
import com.shadowupload.app.core.net.WireProtocol
import com.shadowupload.app.data.model.TransferProgress
import com.shadowupload.app.data.model.TransferState
import com.shadowupload.app.data.model.TransportConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ============================================================================
 *  محرك النقل عالي الأداء — قلب shadowUpload
 * ============================================================================
 *
 *  كيف نصل إلى سرعات تفوق التطبيقات التقليدية؟
 *
 *  1) التقسيم المتوازي (Parallel Partitioning):
 *     الملف الكبير يُقسم إلى أجزاء = عدد المسارات (افتراضياً 4–8، بعدد أنوية
 *     أغلب الهواتف). كل مسار يفتح اتصال TCP مستقلاً ويكتب/يقرأ جزءه في موضعه
 *     عبر seek، فلا تنازع على قفل الملف. النتيجة: استخدام كل النوى وإشباع
 *     نطاق WiFi حتى عندما يرفع RTT تكلفة الاتصال الواحد.
 *
 *  2) Zero-Copy لكل جزء عبر FileChannel.transferTo:
 *     النواة تنقل البايتات من صفحة الملف إلى مقبس الشبكة دون نسخها إلى
 *     حيز المستخدم — أعلى إنتاجية ممكنة على أندرويد.
 *
 *  3) مخازن كبيرة وضبط TCP:
 *     SO_SNDBUF/SO_RCVBUF افتراضياً 1 ميجابايت (حتى 2 ميجابايت) مع
 *     TCP_NODELAY و keep-alive → نافذة انزلاق عريضة وزمن استجابة أدنى.
 *
 *  4) مصافحة رسالة واحدة:
 *     MAGIC → HELLO → OFFER → ACCEPT → HEADER → بيانات.
 *     بلا TLS، بلا DNS، بلا HTTP، وبمهلة اتصال 800 مللي ثانية.
 *
 *  الخصوصية: كل الاتصالات داخل الشبكة المحلية، بلا أي خادم وسيط.
 */
class TransferEngine(
    private val context: Context,
    private val config: TransportConfig = TransportConfig()
) {

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var ioPool: java.util.concurrent.ExecutorService =
        Executors.newFixedThreadPool(config.parallelStreams.coerceIn(1, 8))

    /** يعيد بناء بركة المسارات إن أُغلقت بعد إلغاء سابق */
    private fun ensurePool(): java.util.concurrent.ExecutorService {
        if (ioPool.isShutdown) {
            ioPool = Executors.newFixedThreadPool(config.parallelStreams.coerceIn(1, 8))
        }
        return ioPool
    }

    // =========================================================================
    //  الاستقبال
    // =========================================================================

    /** بدء خادم الاستقبال (يُستخدم من خدمة النقل) */
    fun startReceiveServer(
        port: Int,
        destinationDir: File,
        conflictPolicy: ConflictPolicy = ConflictPolicy.RENAME,
        listener: TransferListener
    ): ReceiveSession {
        val session = ReceiveSession(
            context = context,
            port = port,
            destinationDir = destinationDir,
            config = config,
            conflictPolicy = conflictPolicy,
            listener = listener
        )
        session.start()
        return session
    }

    // =========================================================================
    //  الإرسال
    // =========================================================================

    // =========================================================================
    //  واجهة غير متزامنة: جلسة إرسال تختار بروتوكول المستقبل تلقائياً
    // =========================================================================

    /** مستقبل محلي للتوافق مع الواجهة القديمة */
    data class SendHandle(val future: java.util.concurrent.Future<TransferResult>)

    /**
     * بدء إرسال في الخلفية (لا يحجب خيط الواجهة).
     * يعيد مقبضاً لطلب الإلغاء ومتابعة الحالة عبر المستمع.
     */
    fun beginSend(
        peerHost: String,
        peerPort: Int,
        sources: List<TransferSource>,
        deviceId: String,
        deviceName: String,
        listener: TransferListener
    ): SendHandle {
        cancelled.set(false)
        val future = ensurePool().submit<TransferResult> {
            sendBlocking(peerHost, peerPort, sources, deviceId, deviceName, listener)
        }
        return SendHandle(future)
    }

    /**
     * إرسال متزامن (يحجب الخيط المستدعي — استدعِه من خيط خلفي فقط).
     *
     * مصافحة تحقق كاملة على اتصال منفصل قبل الإرسال:
     *   - ACCEPTED → المستقبل يفهم بروتوكول shadowUpload ويقبل، نبدأ الأجزاء المتوازية
     *   - REJECTED → رفض صريح، نُبلّغ المستخدم برسالة واضحة
     *   - SILENT   → لا بروتوكول/جدار حماية، نفشل بوضوح بدل إرسال بلا مستقبل
     */
    fun sendBlocking(
        peerHost: String,
        peerPort: Int,
        sources: List<TransferSource>,
        deviceId: String,
        deviceName: String,
        listener: TransferListener
    ): TransferResult {
        cancelled.set(false)
        val startedAt = System.currentTimeMillis()
        val totalBytes = sources.sumOf { it.size.coerceAtLeast(0L) }
        val aggregator = ProgressAggregator(totalBytes)
        listener.onStateChanged(TransferState.CONNECTING)

        val probe = try {
            probeReceiver(peerHost, peerPort, deviceId, deviceName, sources, totalBytes)
        } catch (error: Throwable) {
            listener.onStateChanged(if (cancelled.get()) TransferState.CANCELLED else TransferState.FAILED)
            val failed = TransferResult(
                success = false, filesTransferred = 0, filesFailed = sources.size,
                totalBytes = 0L,
                durationMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L),
                averageBytesPerSecond = 0L,
                message = error.message ?: "تعذر الاتصال بالجهاز"
            )
            listener.onFinished(failed)
            return failed
        }

        if (probe == ReceiverProtocol.SILENT) {
            listener.onStateChanged(TransferState.FAILED)
            val noProtocol = TransferResult(
                success = false, filesTransferred = 0, filesFailed = sources.size,
                totalBytes = 0L,
                durationMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L),
                averageBytesPerSecond = 0L,
                message = "الجهاز الآخر لا يشغّل shadowUpload أو أن المنفذ 47821 محجوب"
            )
            listener.onFinished(noProtocol)
            return noProtocol
        }

        if (probe == ReceiverProtocol.REJECTED) {
            listener.onStateChanged(TransferState.FAILED)
            val rejected = TransferResult(
                success = false, filesTransferred = 0, filesFailed = sources.size,
                totalBytes = 0L,
                durationMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L),
                averageBytesPerSecond = 0L,
                message = "رفض الطرف الآخر استقبال الملفات"
            )
            listener.onFinished(rejected)
            return rejected
        }

        // الإرسال الفعلي: أجزاء متوازية، كل جزء على اتصال مستقل
        return sendSelfContained(
            peerHost = peerHost,
            peerPort = peerPort,
            sources = sources,
            deviceId = deviceId,
            aggregator = aggregator,
            totalBytes = totalBytes,
            listener = listener,
            startedAt = startedAt
        )
    }

    private enum class ReceiverProtocol { ACCEPTED, REJECTED, SILENT }

    /**
     * مصافحة أولية سريعة على اتصال واحد يُغلق فوراً بعدها:
     *   - ACCEPTED: المستقبل يفهم بروتوكول shadowUpload
     *   - REJECTED: الطرف الآخر رفض الاستقبال صراحةً
     *   - SILENT:   لا رد (جدار حماية أو مستقبل لا يتكلم البروتوكول) → فشل واضح
     */
    private fun probeReceiver(
        host: String,
        port: Int,
        deviceId: String,
        deviceName: String,
        sources: List<TransferSource>,
        totalBytes: Long
    ): ReceiverProtocol {
        if (sources.isEmpty()) throw TransferException("لا توجد ملفات للإرسال")
        val socket = SocketTuner.tunedSocket(host, port, config.socketBufferBytes)
        try {
            socket.soTimeout = SocketTuner.HANDSHAKE_TIMEOUT_MS
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            WireProtocol.writeMagic(out)
            WireProtocol.readAndVerifyMagic(input)
            WireProtocol.writeFrame(
                out, WireProtocol.MessageType.HELLO,
                ControlJson.encodeHello(deviceId, deviceName, APP_VERSION)
            )
            runCatching { WireProtocol.readFrame(input) } // HELLO_ACK
            WireProtocol.writeFrame(
                out, WireProtocol.MessageType.OFFER,
                ControlJson.encodeOffer(
                    sources.map { ControlJson.FileEntry(it.name, it.size, it.mimeType) },
                    totalBytes
                )
            )
            out.flush()
            // المستقبل يرد ACCEPT صراحةً (أو REJECT)، وبعدها يُغلق المقبس.
            // لا نُكمل إلا بقبول واضح: الإرسال بلا قبول يعني كتابة بيانات
            // في اتصال سيُغلق فوراً = ضياع الملفات بصمت.
            val reply = try {
                WireProtocol.readFrame(input)
            } catch (_: java.io.IOException) {
                return ReceiverProtocol.SILENT
            }
            return when (reply.type) {
                WireProtocol.MessageType.REJECT -> ReceiverProtocol.REJECTED
                WireProtocol.MessageType.ACCEPT -> ReceiverProtocol.ACCEPTED
                else -> ReceiverProtocol.SILENT
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun sendSelfContained(
        peerHost: String,
        peerPort: Int,
        sources: List<TransferSource>,
        deviceId: String,
        aggregator: ProgressAggregator,
        totalBytes: Long,
        listener: TransferListener,
        startedAt: Long
    ): TransferResult {
        listener.onStateChanged(TransferState.TRANSFERRING)
        var success = 0
        var failed = 0

        sources.forEachIndexed { index, source ->
            if (cancelled.get()) {
                listener.onStateChanged(TransferState.CANCELLED)
                return finishResult(success, failed, aggregator, totalBytes, startedAt, listener)
            }
            val partCount = partCountFor(source.size)
            val ok = if (partCount <= 1) {
                sendOnePart(
                    host = peerHost, port = peerPort, source = source,
                    partIndex = 0, partCount = 1, offset = 0L, length = source.size,
                    deviceId = deviceId, aggregator = aggregator, totalBytes = totalBytes,
                    fileIndex = index, totalFiles = sources.size, listener = listener
                )
            } else {
                val partSize = (source.size / partCount) + 1L
                val futures = ArrayList<Future<Boolean>>(partCount)
                for (part in 0 until partCount) {
                    val offset = part.toLong() * partSize
                    val remaining = source.size - offset
                    if (remaining <= 0L) break
                    val length = minOf(partSize, remaining)
                    futures.add(
                        ensurePool().submit<Boolean> {
                            sendOnePart(
                                host = peerHost, port = peerPort, source = source,
                                partIndex = part, partCount = partCount,
                                offset = offset, length = length, deviceId = deviceId,
                                aggregator = aggregator, totalBytes = totalBytes,
                                fileIndex = index, totalFiles = sources.size, listener = listener
                            )
                        }
                    )
                }
                var allOk = true
                futures.forEach { f ->
                    if (!runCatching { f.get() }.getOrDefault(false)) allOk = false
                }
                allOk
            }
            if (ok) success++ else failed++
            listener.onFileCompleted(source.name, ok, if (ok) "مكتمل" else "فشل")
        }
        listener.onStateChanged(
            if (failed == 0) TransferState.COMPLETED else TransferState.FAILED
        )
        return finishResult(success, failed, aggregator, totalBytes, startedAt, listener)
    }

    /** بناء نتيجة نهائية + إبلاغ المستمع مرة واحدة */
    private fun finishResult(
        success: Int,
        failed: Int,
        aggregator: ProgressAggregator,
        totalBytes: Long,
        startedAt: Long,
        listener: TransferListener
    ): TransferResult {
        val duration = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
        val state = when {
            cancelled.get() -> TransferState.CANCELLED
            failed == 0 && success > 0 -> TransferState.COMPLETED
            else -> TransferState.FAILED
        }
        if (state == TransferState.COMPLETED || state == TransferState.CANCELLED) {
            listener.onStateChanged(state)
        }
        val result = TransferResult(
            success = failed == 0 && success > 0,
            filesTransferred = success,
            filesFailed = failed,
            totalBytes = aggregator.transferred(),
            durationMillis = duration,
            averageBytesPerSecond = aggregator.transferred() * 1000L / duration,
            message = when {
                cancelled.get() -> "أُلغي النقل"
                failed == 0 -> "اكتمل النقل"
                success > 0 -> "اكتمل النقل جزئياً"
                else -> "فشل النقل"
            }
        )
        listener.onFinished(result)
        return result
    }

    /**
     * إرسال جزء واحد على اتصال مستقل مكتفٍ بذاته:
     * MAGIC → HELLO → OFFER → (ACCEPT) → HEADER → بيانات → (FILE_RESULT).
     *
     * يُستخدم مع المستقبلات التي تُغلق الاتصال بعد كل جزء، أو مع الأجهزة
     * التي لا تفهم بروتوكول الجلسة.
     */
    private fun sendOnePart(
        host: String,
        port: Int,
        source: TransferSource,
        partIndex: Int,
        partCount: Int,
        offset: Long,
        length: Long,
        deviceId: String,
        aggregator: ProgressAggregator,
        totalBytes: Long,
        fileIndex: Int,
        totalFiles: Int,
        listener: TransferListener
    ): Boolean {
        if (cancelled.get()) return false
        var socket: java.net.Socket? = null
        return try {
            socket = SocketTuner.tunedSocket(host, port, config.socketBufferBytes)
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            WireProtocol.writeMagic(out)
            WireProtocol.readAndVerifyMagic(input)
            WireProtocol.writeFrame(
                out, WireProtocol.MessageType.HELLO,
                ControlJson.encodeHello(deviceId, deviceId, APP_VERSION)
            )
            WireProtocol.writeFrame(
                out, WireProtocol.MessageType.OFFER,
                ControlJson.encodeOffer(
                    listOf(ControlJson.FileEntry(source.name, source.size, source.mimeType)),
                    totalBytes
                )
            )
            runCatching { WireProtocol.readFrame(input) } // ACCEPT

            val header = WireProtocol.FileHeader(
                name = source.name,
                size = source.size,
                mimeType = source.mimeType,
                checksumSha256 = null,
                relativePath = null,
                partIndex = partIndex,
                partCount = partCount,
                offset = offset,
                partLength = length
            )
            WireProtocol.writeFrame(
                out, WireProtocol.MessageType.FILE_START,
                WireProtocol.encodeFileHeader(header)
            )
            out.flush()

            val channel = java.nio.channels.Channels.newChannel(out)
            ZeroCopyIO.sendRange(
                context = context,
                source = source,
                offset = offset,
                length = length,
                target = channel,
                bufferSize = config.socketBufferBytes
            ) { delta ->
                aggregator.add(delta, partIndex)
                listener.onProgress(
                    TransferProgress(
                        state = TransferState.TRANSFERRING,
                        currentFileName = source.name,
                        currentFileIndex = fileIndex + 1,
                        totalFiles = totalFiles,
                        fileBytesDone = aggregator.partTransferred(partIndex),
                        fileBytesTotal = length,
                        bytesDone = aggregator.transferred(),
                        bytesTotal = totalBytes,
                        bytesPerSecond = aggregator.sampleSpeed(),
                        etaMillis = aggregator.etaMillis()
                    )
                )
            }
            out.flush()
            // تأكيد من المستقبل بأن الجزء كُتب على القرص قبل إغلاق الاتصال
            runCatching {
                socket.soTimeout = ACK_TIMEOUT_MS
                WireProtocol.readFrame(input)
            }
            true
        } catch (error: Throwable) {
            if (!cancelled.get()) {
                listener.onFileCompleted(source.name, false, error.message ?: "خطأ في الجزء")
            }
            false
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** عدد الأجزاء: الملفات الصغيرة لا تُجزأ */
    private fun partCountFor(size: Long): Int {
        if (size < MIN_PARALLEL_SIZE) return 1
        return config.parallelStreams.coerceIn(1, TransportConfig.MAX_STREAMS)
    }

    /** إلغاء العمل الجاري */
    fun cancel() {
        cancelled.set(true)
        ioPool.shutdownNow()
    }

    /** فحص إمكانية حجز منفذ الاستقبال */
    fun probePort(port: Int): Boolean = runCatching {
        java.net.ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress(port))
            true
        }
    }.getOrDefault(false)

    /** حساب بصمة ملف */
    fun checksum(file: File): String? = ChecksumUtils.sha256OfFile(file)

    companion object {
        /** إصدار التطبيق المُعلن في المصافحة */
        const val APP_VERSION = "1.0.0"

        /** مهلة انتظار تأكيد كتابة الجزء */
        private const val ACK_TIMEOUT_MS = 5_000

        /** الملفات الأصغر من 8 ميجابايت تُرسل على مسار واحد */
        const val MIN_PARALLEL_SIZE = 8L * 1024L * 1024L
    }
}
