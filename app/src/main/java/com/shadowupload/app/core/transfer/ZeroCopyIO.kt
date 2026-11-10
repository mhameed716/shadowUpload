package com.shadowupload.app.core.transfer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.StandardOpenOption

/**
 * نواة الإدخال/الإخراج عالية الأداء.
 *
 * كما يعمل محرك النقل:
 *  1) صورة الملف عبر مسار حقيقي → FileChannel.transferTo (Zero-Copy في النواة،
 *     لا تُنسخ البيانات إلى مساحة المستخدم إطلاقاً). هذا هو أسرع مسار ممكن على أندرويد.
 *  2) صورة الملف عبر Uri (Scoped Storage) → FileChannel/InputStream مع DirectByteBuffer
 *     بحجم 1 ميجابايت لتقليل عدد الاستدعاءات النظامية.
 *  3) على الأجهزة الأقدم من أندرويد 8 (API 26) نستخدم FileInputStream.getChannel()
 *     مع InputStream.transferTo البديل لأن java.nio.file غير متاح — يغطي ذلك API 21+.
 */
object ZeroCopyIO {

    /** حجم القطعة الافتراضي (1 ميجابايت) */
    const val CHUNK_SIZE: Int = 1024 * 1024

    /** نتيجة إرسال جزء من ملف */
    data class SendResult(val bytesSent: Long, val checksum: String?)

    /**
     * إرسال نطاق من ملف إلى قناة شبكة.
     *
     * @param source الملف المصدر (مسار أو Uri)
     * @param offset بداية النطاق
     * @param length عدد البايتات المطلوبة
     * @param target قناة الشبكة
     * @param bufferSize حجم المخزن (1 ميجابايت أو أكثر)
     * @param onProgress استدعاء تقدم يحمل عدد البايتات المُرسلة في هذا النطاق
     */
    @Throws(IOException::class)
    fun sendRange(
        context: Context,
        source: TransferSource,
        offset: Long,
        length: Long,
        target: WritableByteChannel,
        bufferSize: Int,
        onProgress: (Long) -> Unit
    ): SendResult {
        if (length <= 0L) return SendResult(0L, null)
        return when (source) {
            is TransferSource.FromPath -> sendRangeFromPath(
                File(source.path), offset, length, target, bufferSize, onProgress
            )
            is TransferSource.FromUri -> sendRangeFromUri(
                context, Uri.parse(source.uriString), offset, length, target, bufferSize, onProgress
            )
        }
    }

    /** المسار السريع: Zero-Copy عبر FileChannel.transferTo */
    private fun sendRangeFromPath(
        file: File,
        offset: Long,
        length: Long,
        target: WritableByteChannel,
        bufferSize: Int,
        onProgress: (Long) -> Unit
    ): SendResult {
        if (!file.exists() || !file.canRead()) {
            throw TransferException("لا يمكن قراءة الملف: ${file.name}")
        }
        FileInputStream(file).use { stream ->
            val channel: FileChannel = stream.channel
            var position = offset
            var remaining = length
            var sent = 0L
            val chunk = minOf(bufferSize.toLong(), CHUNK_SIZE.toLong())

            while (remaining > 0) {
                val toSend = minOf(remaining, chunk)
                val transferred = transferZeroCopy(channel, position, toSend, target)
                if (transferred <= 0) throw IOException("توقف النقل عند الموضع $position")
                position += transferred
                remaining -= transferred
                sent += transferred
                onProgress(transferred)
            }
            return SendResult(sent, null)
        }
    }

    /**
     * نقل صفري النسخ: نستخدم transferTo عند توفر java.nio.file (API 26+)،
     * وإلا نستخدم FileChannel.transferTo من java.nio.channels (متاح منذ API 1)
     * مع حماية من الحلقة اللانهائية عندما تعيد القناة صفراً.
     */
    private fun transferZeroCopy(
        channel: FileChannel,
        position: Long,
        count: Long,
        target: WritableByteChannel
    ): Long {
        var total = 0L
        var cursor = position
        var remaining = count
        repeat(MAX_ZERO_COPY_ATTEMPTS) {
            val moved = channel.transferTo(cursor, remaining, target)
            if (moved > 0L) {
                total += moved
                cursor += moved
                remaining -= moved
            }
            if (remaining <= 0L) return total
        }
        return total
    }

    /** مسار Uri: نستخدم DirectByteBuffer لتقليل ضغط الذاكرة */
    private fun sendRangeFromUri(
        context: Context,
        uri: Uri,
        offset: Long,
        length: Long,
        target: WritableByteChannel,
        bufferSize: Int,
        onProgress: (Long) -> Unit
    ): SendResult {
        val resolver = context.contentResolver
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            // المسار السريع عبر واصف الملف: يعطينا Zero-Copy أيضاً
            FileInputStream(pfd.fileDescriptor).use { stream ->
                val channel = stream.channel
                if (offset > 0) {
                    runCatching { channel.position(offset) }
                }
                var remaining = length
                var sent = 0L
                while (remaining > 0) {
                    val toSend = minOf(remaining, CHUNK_SIZE.toLong())
                    val moved = channel.transferTo(channel.position(), toSend, target)
                    if (moved <= 0L) break
                    remaining -= moved
                    sent += moved
                    onProgress(moved)
                }
                if (sent >= length) return SendResult(sent, null)
            }
            // إن أُرسل جزء من البيانات ثم تعذّر الباقي فلا يجوز إعادة الإرسال
            // من البداية: ذلك يُكرر بايتات على السلك ويُفسد الملف المستقبَل
            if (sent > 0L) {
                throw IOException("انقطع إرسال الملف بعد $sent بايت من $length")
            }
        }
        // احتياطي آمن: يُستخدم فقط عندما لم يُرسل أي بايت بعد
        val input = resolver.openInputStream(uri)
            ?: throw TransferException("تعذر فتح الملف المحدد")
        input.use { stream ->
            return sendRangeFromStream(stream, offset, length, target, bufferSize, onProgress)
        }
    }

    /** قراءة من InputStream مع ترقيم وتخطي أولي للنطاق */
    private fun sendRangeFromStream(
        stream: InputStream,
        offset: Long,
        length: Long,
        target: WritableByteChannel,
        bufferSize: Int,
        onProgress: (Long) -> Unit
    ): SendResult {
        var skipped = 0L
        while (skipped < offset) {
            val step = stream.skip(offset - skipped)
            if (step <= 0L) throw IOException("تعذر تخطي بداية الملف")
            skipped += step
        }
        val direct = ByteBuffer.allocateDirect(maxOf(bufferSize, CHUNK_SIZE))
        val staging = ByteArray(minOf(bufferSize, CHUNK_SIZE))
        var remaining = length
        var sent = 0L
        while (remaining > 0) {
            val want = minOf(remaining.toInt().toLong(), staging.size.toLong()).toInt()
            val read = stream.read(staging, 0, want)
            if (read <= 0) break
            direct.clear()
            direct.put(staging, 0, read)
            direct.flip()
            while (direct.hasRemaining()) {
                sent += target.write(direct).toLong()
            }
            remaining -= read
            onProgress(read.toLong())
        }
        return SendResult(sent, null)
    }

    /**
     * استقبال نطاق إلى ملف على القرص.
     * نفتح الملف بأسلوب RandomAccessFile لكتابة الأجزاء المتوازية في مواضعها.
     */
    @Throws(IOException::class)
    fun receiveRangeToFile(
        destination: File,
        offset: Long,
        length: Long,
        source: java.nio.channels.ReadableByteChannel,
        bufferSize: Int,
        onProgress: (Long) -> Unit
    ): Long {
        destination.parentFile?.mkdirs()
        java.io.RandomAccessFile(destination, "rw").use { raf ->
            raf.setLength(maxOf(raf.length(), offset + length))
            raf.seek(offset)
            val channel = raf.channel
            val buffer = ByteBuffer.allocateDirect(maxOf(bufferSize, CHUNK_SIZE))
            var remaining = length
            var received = 0L
            while (remaining > 0) {
                buffer.clear()
                val want = minOf(remaining, buffer.capacity().toLong()).toInt()
                buffer.limit(want)
                val read = source.read(buffer)
                if (read <= 0) throw IOException("انقطع الاتصال أثناء استقبال البيانات")
                if (read > remaining) throw IOException("تجاوز حجم الجزء المعلن")
                buffer.flip()
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
                remaining -= read
                received += read
                onProgress(read.toLong())
            }
            channel.force(false)
            return received
        }
    }

    /**
     * استقبال نطاق من تدفق شبكة إلى ملف على القرص.
     *
     * نقرأ من InputStream المقبس مباشرة (بلا قنوات وسيطة) داخل
     * DirectByteBuffer ثم نكتب إلى قناة الملف العشوائية في موضع الجزء.
     * هذا يضمن:
     *   - احترام مهلة القراءة SO_TIMEOUT (لا تعليق أبدي).
     *   - عدم اختلاط بيانات مخزّنة مع بيانات القناة (خطأ شائع في NIO).
     *   - نسخة واحدة فقط من الذاكرة (مخزن مباشر) قبل الكتابة للقرص.
     */
    @Throws(IOException::class)
    fun receiveRangeFromStream(
        destination: File,
        offset: Long,
        length: Long,
        source: InputStream,
        bufferSize: Int,
        onProgress: (Long) -> Unit
    ): Long {
        destination.parentFile?.mkdirs()
        java.io.RandomAccessFile(destination, "rw").use { raf ->
            raf.setLength(maxOf(raf.length(), offset + length))
            raf.seek(offset)
            val fileChannel = raf.channel
            val direct = ByteBuffer.allocateDirect(maxOf(bufferSize, CHUNK_SIZE))
            // مصفوفة وسيطة واحدة يُعاد استخدامها (تخصيصها داخل الحلقة كان يضغط GC)
            val staging = ByteArray(minOf(maxOf(bufferSize, 64 * 1024), STAGING_SIZE))
            var remaining = length
            var received = 0L
            while (remaining > 0L) {
                val want = minOf(remaining, staging.size.toLong()).toInt()
                val read = source.read(staging, 0, want)
                if (read <= 0) throw IOException("انقطع الاتصال أثناء استقبال البيانات")
                direct.clear()
                direct.put(staging, 0, read)
                direct.flip()
                while (direct.hasRemaining()) fileChannel.write(direct)
                remaining -= read
                received += read
                onProgress(read.toLong())
            }
            fileChannel.force(false)
            return received
        }
    }

    /** قراءة بيانات في مخزن مؤقت مباشر (لاستخدامها عند التحقق أو الضغط) */
    fun allocateBuffer(size: Int): ByteBuffer =
        ByteBuffer.allocateDirect(size.coerceIn(64 * 1024, 8 * 1024 * 1024))

    /** هل يدعم النظام مسار java.nio.file الكامل؟ (أندرويد 8+) */
    val hasFullNioFile: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /** فتح قناة كتابة بأسلوبين حسب الإصدار */
    fun openWriteChannel(file: File, append: Boolean): WritableByteChannel {
        file.parentFile?.mkdirs()
        return if (hasFullNioFile) {
            try {
                val options = if (append) {
                    arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                } else {
                    arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                }
                java.nio.channels.FileChannel.open(file.toPath(), *options)
            } catch (_: Throwable) {
                fallbackChannel(file, append)
            }
        } else {
            fallbackChannel(file, append)
        }
    }

    private fun fallbackChannel(file: File, append: Boolean): WritableByteChannel {
        val raf = java.io.RandomAccessFile(file, if (append) "rw" else "rw")
        if (!append) raf.setLength(0L) else raf.seek(raf.length())
        return raf.channel
    }

    /** اسم وحجم ملف من Uri عبر ContentResolver (مع بدائل للأجهزة القديمة) */
    fun queryFileNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        var name = "ملف_${System.currentTimeMillis()}"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (size <= 0L) {
            size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: 0L
        }
        return name to size
    }

    private const val MAX_ZERO_COPY_ATTEMPTS = 32

    /** حجم مصفوفة النقل الوسيطة عند القراءة من InputStream */
    private const val STAGING_SIZE = 128 * 1024
}
