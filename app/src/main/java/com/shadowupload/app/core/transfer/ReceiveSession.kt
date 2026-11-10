package com.shadowupload.app.core.transfer

import android.content.Context
import android.util.Log
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
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * ============================================================================
 *  جلسة الاستقبال — الخادم الذي يفتح المنفذ ويستقبل الأجزاء المتوازية.
 * ============================================================================
 *
 *  تصميم الأداء:
 *   - مقبس خادم واحد يقبل الاتصالات، وكل اتصال (جزء) يُخدم في خيط مستقل.
 *   - لكل اسم ملف كائن DestinationFile مشترك يحتفظ بعدد الأجزاء المتوقعة
 *     والمكتملة والبايتات المستلمة، فتُفتح الكتابة العشوائية على الملف نفسه
 *     بمواضع مختلفة (بلا أقفال متعارضة).
 *   - عند اكتمال كل الأجزاء: تحقق اختياري من SHA-256 ثم تسليم الملف.
 *
 *  البروتوكول لكل اتصال (كله على خيط واحد لكل جزء):
 *     MAGIC → HELLO → (HELLO_ACK) → OFFER → (ACCEPT) → HEADER → بيانات خام
 *  الخادم يقرأ حتى نهاية بيانات الجزء ثم يُعلم العميل ويغلق الاتصال.
 */
class ReceiveSession(
    private val context: Context,
    private val port: Int,
    private val destinationDir: File,
    private val config: TransportConfig,
    private val conflictPolicy: ConflictPolicy,
    private val listener: TransferListener
) {

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var serverSocket: java.net.ServerSocket? = null
    private val acceptPool = Executors.newSingleThreadExecutor()
    private val workerPool = Executors.newCachedThreadPool()

    /** الملفات النشطة مفتاحية بالاسم الآمن */
    private val activeFiles = ConcurrentHashMap<String, DestinationFile>()

    private val totalExpected = AtomicLong(0L)
    private val totalReceived = AtomicLong(0L)
    private val filesCompleted = AtomicInteger(0)
    private val aggregator = ProgressAggregator(0L)
    private val fileRegistryLock = Any()
    private val lastPeerName = AtomicReference("جهاز قريب")
    private var sessionStartedAt = System.currentTimeMillis()

    /** بيانات وصفية لملف قيد الاستقبال */
    private class DestinationFile(
        val file: File,
        val expectedSize: Long,
        val partCount: Int
    ) {
        val receivedParts = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        val receivedBytes = AtomicLong(0L)
    }

    /** بدء الاستماع. يعيد false إذا تعذر حجز المنفذ. */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            serverSocket = SocketTuner.tunedServerSocket(port, config.socketBufferBytes)
            listener.onStateChanged(TransferState.WAITING_PEER)
            acceptPool.execute { acceptLoop() }
            true
        } catch (error: Throwable) {
            running.set(false)
            listener.onStateChanged(TransferState.FAILED)
            Log.e(TAG, "تعذر بدء خادم الاستقبال على المنفذ $port", error)
            false
        }
    }

    /** إيقاف الاستماع وإغلاق كل شيء */
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        acceptPool.shutdownNow()
        workerPool.shutdownNow()
        activeFiles.clear()
    }

    fun cancel() {
        cancelled.set(true)
        listener.onStateChanged(TransferState.CANCELLED)
        stop()
    }

    fun isRunning(): Boolean = running.get()

    private fun acceptLoop() {
        while (running.get() && !cancelled.get()) {
            try {
                val socket = serverSocket?.accept() ?: break
                SocketTuner.configure(socket, config.socketBufferBytes)
                workerPool.execute { handleConnection(socket) }
            } catch (error: Throwable) {
                if (running.get()) Log.w(TAG, "خطأ في قبول اتصال: ${error.message}")
                break
            }
        }
    }

    /** معالجة اتصال واحد من البداية للنهاية */
    private fun handleConnection(socket: Socket) {
        var out: DataOutputStream? = null
        try {
            socket.soTimeout = SocketTuner.READ_TIMEOUT_MS
            // تدفقات غير مخزّنة: بيانات الملف تُقرأ مباشرة من المقبس بعد الرأس
            val input = DataInputStream(socket.getInputStream())
            out = DataOutputStream(socket.getOutputStream())

            // ---- بصمة البروتوكول ----
            WireProtocol.readAndVerifyMagic(input)
            WireProtocol.writeMagic(out)

            // ---- حلقة الرسائل ----
            loop@ while (running.get() && !cancelled.get()) {
                val frame = try {
                    WireProtocol.readFrame(input)
                } catch (_: IOException) {
                    break@loop // انتهى الاتصال
                }
                when (frame.type) {
                    WireProtocol.MessageType.HELLO -> {
                        val peer = ControlJson.decodePeer(
                            frame.text(),
                            socket.inetAddress?.hostAddress ?: "unknown",
                            socket.port
                        )
                        lastPeerName.set(peer.name)
                        WireProtocol.writeText(
                            out,
                            WireProtocol.MessageType.HELLO_ACK,
                            String(ControlJson.encodeHello("host", android.os.Build.MODEL, "1.0.0"))
                        )
                    }

                    WireProtocol.MessageType.OFFER -> {
                        handleOffer(frame.text(), out)
                    }

                    WireProtocol.MessageType.FILE_START -> {
                        val header = WireProtocol.decodeFileHeader(frame.payload)
                        val result = receivePart(header, input)
                        WireProtocol.writeFrame(
                            out,
                            WireProtocol.MessageType.FILE_RESULT,
                            ControlJson.encodeResult("file_result", result.ok)
                        )
                        out.flush()
                        if (result.completedFile) {
                            // لا مزيد من البيانات على هذا الاتصال بعد اكتمال آخر جزء
                            break@loop
                        }
                    }

                    WireProtocol.MessageType.CANCEL -> {
                        cancelled.set(true)
                        break@loop
                    }

                    WireProtocol.MessageType.TRANSFER_DONE -> break@loop
                    else -> Unit // رسائل غير معروفة تُتجاهل بأمان
                }
            }
        } catch (error: Throwable) {
            if (!cancelled.get()) Log.w(TAG, "فشل اتصال استقبال: ${error.message}")
        } finally {
            runCatching { out?.flush() }
            runCatching { socket.close() }
        }
    }

    /** نتيجة استقبال جزء */
    private data class PartOutcome(val ok: Boolean, val completedFile: Boolean)

    /** استقبال جزء وكتابته في موضعه */
    private fun receivePart(header: WireProtocol.FileHeader, input: DataInputStream): PartOutcome {
        if (cancelled.get()) return PartOutcome(false, false)
        val safeName = WireProtocol.sanitizeFileName(header.name)
        val partCount = header.partCount.coerceAtLeast(1)

        // تسجيل ذري للملف: getOrPut على ConcurrentHashMap غير ذرية على API 21،
        // وقد يؤدي تسابق مسارين إلى ملفين مختلفين على القرص فلا يكتمل أيٌّ منهما
        var target = activeFiles[safeName]
        if (target == null) {
            synchronized(fileRegistryLock) {
                target = activeFiles[safeName]
                if (target == null) {
                    val destination = resolveDestination(safeName)
                    totalExpected.addAndGet(header.size)
                    val created = DestinationFile(
                        file = destination,
                        expectedSize = header.size,
                        partCount = partCount
                    )
                    activeFiles[safeName] = created
                    target = created
                }
            }
        }
        val destinationFile = target!!

        val partLength = when {
            header.partLength >= 0L -> header.partLength
            else -> header.size
        }
        val written = ZeroCopyIO.receiveRangeFromStream(
            destination = destinationFile.file,
            offset = header.offset,
            length = partLength,
            source = input,
            bufferSize = config.socketBufferBytes
        ) { delta ->
            destinationFile.receivedBytes.addAndGet(delta)
            val all = totalReceived.addAndGet(delta)
            aggregator.add(delta)
            listener.onProgress(
                TransferProgress(
                    state = TransferState.TRANSFERRING,
                    currentFileName = safeName,
                    currentFileIndex = filesCompleted.get() + 1,
                    totalFiles = maxOf(activeFiles.size, 1),
                    fileBytesDone = destinationFile.receivedBytes.get(),
                    fileBytesTotal = destinationFile.expectedSize,
                    bytesDone = all,
                    bytesTotal = maxOf(totalExpected.get(), all),
                    bytesPerSecond = aggregator.sampleSpeed(),
                    etaMillis = aggregator.etaMillis()
                )
            )
        }

        destinationFile.receivedParts.add(header.partIndex)
        // ملاحظة دقة: هنا قد يكون آخر جزء ما زال يكتب على مسار آخر، فيبدو
        // الحجم أصغر من المتوقع للحظات. لا ننهي الجلسة إلا عند اكتمال آخر جزء
        // (كل مسار يتحقق بنفسه من receivedParts)، وقياس الحجم يعود للنهاية.
        val allPartsDone = destinationFile.receivedParts.size >= destinationFile.partCount
        val sizeOk = destinationFile.file.length() >= destinationFile.expectedSize

        if (allPartsDone && sizeOk) {
            activeFiles.remove(safeName)
            filesCompleted.incrementAndGet()

            var verified = true
            if (config.verifyChecksums && !header.checksumSha256.isNullOrBlank()) {
                listener.onStateChanged(TransferState.VERIFYING)
                val actual = ChecksumUtils.sha256OfFile(destinationFile.file)
                verified = actual != null && actual.equals(header.checksumSha256, ignoreCase = true)
            }
            listener.onFileCompleted(
                safeName,
                verified,
                if (verified) "تم التحقق من البصمة ✓" else "تحذير: البصمة غير مطابقة"
            )
            if (activeFiles.isEmpty()) {
                val duration = (System.currentTimeMillis() - sessionStartedAt).coerceAtLeast(1L)
                listener.onStateChanged(TransferState.COMPLETED)
                listener.onFinished(
                    TransferResult(
                        success = true,
                        filesTransferred = filesCompleted.get(),
                        filesFailed = 0,
                        totalBytes = totalReceived.get(),
                        durationMillis = duration,
                        averageBytesPerSecond = totalReceived.get() * 1000L / duration,
                        message = "اكتمل الاستقبال من ${lastPeerName.get()}"
                    )
                )
            }
            return PartOutcome(written >= 0, true)
        }
        return PartOutcome(written > 0, false)
    }

    /** قبول بيان الملفات من العميل */
    private fun handleOffer(payload: String, out: DataOutputStream) {
        val (files, total) = ControlJson.decodeOffer(payload)
        if (total > 0L) totalExpected.set(total)
        runCatching { destinationDir.mkdirs() }
        listener.onStateChanged(TransferState.TRANSFERRING)
        WireProtocol.writeFrame(
            out,
            WireProtocol.MessageType.ACCEPT,
            ControlJson.encodeResult("accept", true)
        )
        out.flush()
        files.firstOrNull()?.let { entry -> emitProgress(entry.name, 0L) }
    }

    /**
     * اختيار مسار الملف النهائي مع حماية من تجاوز المسار (Path Traversal)
     * وتطبيق سياسة التعارض.
     */
    private fun resolveDestination(safeName: String): File {
        destinationDir.mkdirs()
        var candidate = File(destinationDir, safeName)
        val basePath = destinationDir.canonicalPath
        if (!candidate.canonicalPath.startsWith(basePath)) {
            candidate = File(destinationDir, "ملف_${System.currentTimeMillis()}")
        }
        return when (conflictPolicy) {
            ConflictPolicy.OVERWRITE -> {
                candidate.delete()
                candidate
            }
            ConflictPolicy.SKIP, ConflictPolicy.RENAME ->
                if (candidate.exists()) uniqueFile(candidate) else candidate
        }
    }

    /** اسم فريد بإضافة رقم بين قوسين */
    private fun uniqueFile(file: File): File {
        val base = file.nameWithoutExtension
        val extension = file.extension
        var index = 1
        while (index < 10_000) {
            val suffix = if (extension.isBlank()) " ($index)" else " ($index).$extension"
            val candidate = File(file.parentFile, base + suffix)
            if (!candidate.exists()) return candidate
            index++
        }
        return File(file.parentFile, "${base}_${System.currentTimeMillis()}.$extension")
    }

    /** تحديث تقدم أولي عند بدء ملف جديد */
    private fun emitProgress(name: String, done: Long) {
        listener.onProgress(
            TransferProgress(
                state = TransferState.TRANSFERRING,
                currentFileName = name,
                currentFileIndex = filesCompleted.get() + 1,
                totalFiles = maxOf(activeFiles.size, 1),
                fileBytesDone = done,
                fileBytesTotal = activeFiles[name]?.expectedSize ?: 0L,
                bytesDone = totalReceived.get(),
                bytesTotal = maxOf(totalExpected.get(), totalReceived.get()),
                bytesPerSecond = aggregator.sampleSpeed(),
                etaMillis = aggregator.etaMillis()
            )
        )
    }

    private companion object {
        const val TAG = "ReceiveSession"
    }
}
