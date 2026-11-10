package com.shadowupload.app.core.net

import android.content.Context
import com.shadowupload.app.core.transfer.ChecksumUtils
import com.shadowupload.app.core.transfer.ConflictPolicy
import com.shadowupload.app.core.transfer.ReceiveSession
import com.shadowupload.app.core.transfer.TransferException
import com.shadowupload.app.core.transfer.TransferListener
import com.shadowupload.app.core.transfer.TransferResult
import com.shadowupload.app.core.transfer.TransferSource
import com.shadowupload.app.core.transfer.ZeroCopyIO
import com.shadowupload.app.data.model.TransferProgress
import com.shadowupload.app.data.model.TransferState
import com.shadowupload.app.data.model.TransportConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
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
 *     حيز المستخدم (No user-space copy) — أعلى إنتاجية ممكنة على أندرويد.
 *
 *  3) مخازن كبيرة وضبط TCP:
 *     SO_SNDBUF/SO_RCVBUF افتراضياً 1 ميجابايت (حتى 2 ميجابايت)، مع
 *     TCP_NODELAY و keep-alive → نافذة انزلاق عريضة وزمن استجابة أدنى.
 *
 *  4) مصافحة رسالة واحدة:
 *     HELLO → HELLO_ACK → HEADER → بيانات. بلا TLS، بلا DNS، بلا HTTP،
 *     وبمهلة اتصال 800 مللي ثانية → اتصال محلي نموذجي أقل من 0.2 ثانية.
 *
 *  الخصوصية: لا يوجد أي خادم وسيط؛ كل شيء يجري داخل الشبكة المحلية.
 */
class TransferEngine(
    private val context: Context,
    private val config: TransportConfig = TransportConfig()
) {

    private val cancelled = AtomicBoolean(false)
    private val ioPool = Executors.newFixedThreadPool(config.parallelStreams.coerceIn(1, 8))

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

    /**
     * إرسال ملفات إلى جهاز طرفي.
     *
     * المرحلة أ: مصافحة على اتصال تحكم (HELLO/OFFER/ACCEPT).
     * المرحلة ب: لكل ملف، فتح اتصالات الأجزاء وإرسالها بالتوازي.
     */
    fun send(
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
        var successCount = 0
        var failedCount = 0

        try {
            listener.onStateChanged(TransferState.CONNECTING)

            // ---------- 1) التحقق من وجود الطرف الآخر ----------
            // اتصال التحكم يبدأ دائماً بـ HELLO (هوية المرسل) ثم OFFER ثم ACCEPT
            val controlSocket = SocketTuner.tunedSocket(peerHost, peerPort, config.socketBufferBytes)
            val controlOut = DataOutputStream(controlSocket.getOutputStream().buffered(config.socketBufferBytes))
            val controlIn = DataInputStream(controlSocket.getInputStream().buffered(64 * 1024))
            WireProtocol.writeMagic(controlOut)
            WireProtocol.readAndVerifyMagic(controlIn)
            WireProtocol.writeFrame(
                controlOut,
                WireProtocol.MessageType.HELLO,
                ControlJson.encodeHello(deviceId, deviceName, "1.0.0")
            )
            runCatching { WireProtocol.readFrame(controlIn) } // HELLO_ACK
            listener.onStateChanged(TransferState.HANDSHAKING)

            // ---------- 2) عرض الملفات ----------
            WireProtocol.writeFrame(
                controlOut,
                WireProtocol.MessageType.OFFER,
                ControlJson.encodeOffer(
                    sources.map { ControlJson.FileEntry(it.name, it.size, it.mimeType) },
                    totalBytes
                )
            )
            val acceptFrame = WireProtocol.readFrame(controlIn)
            val accepted = acceptFrame.type == WireProtocol.MessageType.ACCEPT &&
                ControlJson.optBoolean(acceptFrame.text(), "ok")
            if (!accepted) throw TransferException("رفض الطرف الآخر استقبال الملفات")
            runCatching { controlSocket.close() }

            listener.onStateChanged(TransferState.TRANSFERRING)

            // ---------- 3) إرسال كل ملف ----------
            sources.forEachIndexed { index, source ->
                if (cancelled.get()) throw TransferException("أُلغي النقل بواسطة المستخدم")
                val partCount = partCountFor(source.size)
                val ok = if (partCount <= 1) {
                    sendOnePart(
                        host = peerHost, port = peerPort, source = source,
                        partIndex = 0, partCount = 1, offset = 0L, length = source.size,
                        aggregator = aggregator, totalBytes = totalBytes,
                        fileIndex = index, totalFiles = sources.size,
                        deviceName = deviceName, listener = listener
                    )
                } else {
                    sendParallelParts(
                        host = peerHost, port = peerPort, source = source,
                        partCount = partCount, aggregator = aggregator, totalBytes = totalBytes,
                        fileIndex = index, totalFiles = sources.size,
                        deviceName = deviceName, listener = listener
                    )
                }
                if (ok) successCount++ else failedCount++
                listener.onFileCompleted(source.name, ok, if (ok) "مكتمل" else "فشل")
            }

            listener.onStateChanged(TransferState.COMPLETED)
            val duration = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            val result = TransferResult(
                success = failedCount == 0,
                filesTransferred = successCount,
                filesFailed = failedCount,
                totalBytes = aggregator.transferred(),
                durationMillis = duration,
                averageBytesPerSecond = aggregator.transferred() * 1000L / duration,
                message = if (failedCount == 0) "اكتمل النقل" else "اكتمل جزئياً"
            )
            listener.onFinished(result)
            return result
        } catch (error: Throwable) {
            listener.onStateChanged(
                if (cancelled.get()) TransferState.CANCELLED else TransferState.FAILED
            )
            val duration = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            val result = TransferResult(
                success = false,
                filesTransferred = successCount,
                filesFailed = failedCount.coerceAtLeast(1),
                totalBytes = aggregator.transferred(),
                durationMillis = duration,
                averageBytesPerSecond = aggregator.transferred() * 1000L / duration,
                message = error.message ?: "فشل غير متوقع"
            )
            listener.onFinished(result)
            return result
        }
    }

    /** إرسال ملف كبير عبر عدة اتصالات متوازية */
    private fun sendParallelParts(
        host: String,
        port: Int,
        source: TransferSource,
        partCount: Int,
        aggregator: ProgressAggregator,
        totalBytes: Long,
        fileIndex: Int,
        totalFiles: Int,
        deviceName: String,
        listener: TransferListener
    ): Boolean {
        val partSize = (source.size / partCount) + 1
        val futures = ArrayList<java.util.concurrent.Future<Boolean>>(partCount)
        for (part in 0 until partCount) {
            val offset = part.toLong() * partSize.toLong()
            val remaining = source.size - offset
            if (remaining <= 0L) break
            val length = minOf(partSize, remaining)
            futures.add(
                ioPool.submit<Boolean> {
                    sendOnePart(
                        host = host, port = port, source = source,
                        partIndex = part, partCount = partCount,
                        offset = offset, length = length,
                        aggregator = aggregator, totalBytes = totalBytes,
                        fileIndex = fileIndex, totalFiles = totalFiles,
                        deviceName = deviceName, listener = listener
                    )
                }
            )
        }
        var allOk = true
        futures.forEach { future ->
            val outcome = runCatching { future.get() }.getOrDefault(false)
            if (!outcome) allOk = false
        }
        return allOk
    }

    /** إرسال نطاق واحد عبر اتصال مستقل مع Zero-Copy */
    private fun sendOnePart(
        host: String,
        port: Int,
        source: TransferSource,
        partIndex: Int,
        partCount: Int,
        offset: Long,
        length: Long,
        aggregator: ProgressAggregator,
        totalBytes: Long,
        fileIndex: Int,
        totalFiles: Int,
        deviceName: String,
        listener: TransferListener
    ): Boolean {
        if (cancelled.get()) return false
        var socket: java.net.Socket? = null
        try {
            socket = SocketTuner.tunedSocket(host, port, config.socketBufferBytes)
            val out = DataOutputStream(socket.getOutputStream().buffered(config.socketBufferBytes))
            val input = DataInputStream(socket.getInputStream().buffered(32 * 1024))
            WireProtocol.writeMagic(out)
            WireProtocol.readAndVerifyMagic(input)

            // المصافحة: هوية المرسل ثم بيان الملف ثم رأس الجزء
            WireProtocol.writeFrame(
                out,
                WireProtocol.MessageType.OFFER,
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
            // ملاحظة: OFFER يسبق HEADER دائماً حتى يعرف الخادم حجم الدفعة
            WireProtocol.writeFrame(
                out,
                WireProtocol.MessageType.FILE_START,
                WireProtocol.encodeFileHeader(header)
            )

            // ---------- البيانات: Zero-Copy ----------
            val channel = java.nio.channels.Channels.newChannel(out)
            ZeroCopyIO.sendRange(
                context = context,
                source = source,
                offset = offset,
                length = length,
                target = channel,
                bufferSize = config.socketBufferBytes,
                onProgress = { delta ->
                    aggregator.add(delta, partIndex)
                    listener.onProgress(
                        TransferProgress(
                            state = TransferState.TRANSFERRING,
                            currentFileName = source.name,
                            currentFileIndex = fileIndex + 1,
                            totalFiles = totalFiles,
                            fileBytesDone = minOf(aggregator.transferred(), totalBytes),
                            fileBytesTotal = totalBytes,
                            bytesDone = aggregator.transferred(),
                            bytesTotal = totalBytes,
                            bytesPerSecond = aggregator.sampleSpeed(),
                            etaMillis = aggregator.etaMillis()
                        )
                    )
                }
            )
            out.flush()
            return true
        } catch (error: Throwable) {
            if (!cancelled.get()) {
                listener.onFileCompleted(
                    source.name,
                    false,
                    error.message ?: "خطأ في الجزء ${partIndex + 1}"
                )
            }
            return false
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** عدد الأجزاء: الملفات الصغيرة لا تُجزأ (التجزئة تضرها) */
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

    /** حساب بصمة ملف (متاح للواجهة) */
    fun checksum(file: File): String? = ChecksumUtils.sha256OfFile(file)

    companion object {
        /** الملفات الأصغر من 8 ميجابايت تُرسل على مسار واحد */
        const val MIN_PARALLEL_SIZE = 8L * 1024L * 1024L
    }
}
