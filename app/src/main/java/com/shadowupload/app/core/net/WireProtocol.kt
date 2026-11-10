package com.shadowupload.app.core.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * بروتوكول shadowUpload الثنائي — مبني على DataStreams بترتيب ثابت (Big-Endian).
 *
 * لماذا بروتوكول مخصص بدل HTTP؟
 *  - لا رؤوس نصية ولا تحليل سلاسل → إطار ثابت بحجم 9 بايتات لكل رسالة.
 *  - يمكننا إرسال البيانات الخام مباشرة بعد الرأس عبر FileChannel (Zero-Copy).
 *  - مصافحة في رسالة واحدة ذهاباً وإياباً = زمن اتصال أقل من 200 مللي ثانية.
 *
 * هيكل الإطار:
 *   byte  : النوع (MessageType)
 *   int   : طول الحمولة بالبايت
 *   bytes : الحمولة (اختيارية)
 *
 * للبايتات الخاصة بالملف تُستخدم رسائل CHUNK_DATA ومحتواها يُقرأ مباشرة
 * إلى ملف الهدف دون تحويل إلى كائنات.
 */
object WireProtocol {

    /** إصدار البروتوكول: يجب أن يتطابق الطرفان */
    const val VERSION = 1

    /** البصمة السحرية التي يبدأ بها كل اتصال: "SHDU" */
    val MAGIC: ByteArray = byteArrayOf(0x53, 0x48, 0x44, 0x55)

    /** الحد الأقصى لحمولة أي رسالة نصية/وصفية = 1 ميجابايت */
    const val MAX_PAYLOAD = 1024 * 1024

    /** حجم قطعة البيانات الافتراضي في وضع Zero-Copy */
    const val DEFAULT_CHUNK = 1024 * 1024

    enum class MessageType(val code: Byte) {
        HELLO(1),
        HELLO_ACK(2),
        JOIN_REQUEST(3),
        JOIN_ACCEPTED(4),
        JOIN_REJECTED(5),
        OFFER(6),
        ACCEPT(7),
        REJECT(8),
        FILE_START(9),
        CHUNK_DATA(10),
        FILE_END(11),
        FILE_RESULT(12),
        TRANSFER_DONE(13),
        PING(14),
        PONG(15),
        CANCEL(16),
        ERROR(17),
        DEVICE_INFO(18),
        CLONE_MANIFEST(19),
        PROGRESS_ACK(20);

        companion object {
            private val byCode = values().associateBy { it.code }
            fun from(code: Byte): MessageType? = byCode[code]
        }
    }

    /** إطار رسالة مقروء من الشبكة */
    data class Frame(val type: MessageType, val payload: ByteArray) {
        fun text(): String = String(payload, StandardCharsets.UTF_8)
    }

    /** كتابة البصمة السحرية في بداية الاتصال */
    @Throws(IOException::class)
    fun writeMagic(out: DataOutputStream) {
        out.write(MAGIC)
        out.writeInt(VERSION)
        out.flush()
    }

    /** قراءة والتحقق من البصمة السحرية؛ يرفع استثناءً إن كان الطرف الآخر غريباً */
    @Throws(IOException::class)
    fun readAndVerifyMagic(input: DataInputStream): Int {
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw IOException("بصمة بروتوكول غير صالحة")
        }
        val version = input.readInt()
        if (version != VERSION) {
            throw IOException("إصدار بروتوكول غير متوافق: $version")
        }
        return version
    }

    /** كتابة إطار رسالة كامل */
    @Throws(IOException::class)
    fun writeFrame(out: DataOutputStream, type: MessageType, payload: ByteArray?) {
        val body = payload ?: ByteArray(0)
        require(body.size <= MAX_PAYLOAD) { "حمولة الرسالة كبيرة جداً" }
        out.writeByte(type.code.toInt())
        out.writeInt(body.size)
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    @Throws(IOException::class)
    fun writeText(out: DataOutputStream, type: MessageType, text: String) {
        writeFrame(out, type, text.toByteArray(StandardCharsets.UTF_8))
    }

    /** قراءة إطار رسالة كامل */
    @Throws(IOException::class)
    fun readFrame(input: DataInputStream): Frame {
        val typeCode = try {
            input.readByte()
        } catch (eof: EOFException) {
            throw IOException("انقطع الاتصال بالطرف الآخر", eof)
        }
        val type = MessageType.from(typeCode)
            ?: throw IOException("نوع رسالة غير معروف: $typeCode")
        val length = input.readInt()
        if (length < 0 || length > MAX_PAYLOAD) {
            throw IOException("طول حمولة غير صالح: $length")
        }
        val payload = ByteArray(length)
        if (length > 0) input.readFully(payload)
        return Frame(type, payload)
    }

    /**
     * رأس وصف الملف/الجزء: يُرسل مرة واحدة قبل بيانات كل ملف أو جزء.
     * يدعم التقسيم المتوازي: partIndex/partCount/offset تسمح لعدة اتصالات
     * بكتابة أجزاء مختلفة من الملف نفسه في الوقت ذاته (متعدد المسارات).
     */
    data class FileHeader(
        val name: String,
        val size: Long,
        val mimeType: String?,
        val checksumSha256: String?,
        val relativePath: String?,
        val partIndex: Int = 0,
        val partCount: Int = 1,
        val offset: Long = 0L,
        val partLength: Long = -1L
    )

    /** ترتيب ثابت للبايتات: أطوال النصوص (int/short) ثم محتواها ثم الأرقام */
    fun encodeFileHeader(header: FileHeader): ByteArray {
        val nameBytes = header.name.toByteArray(StandardCharsets.UTF_8)
        val mimeBytes = (header.mimeType ?: "").toByteArray(StandardCharsets.UTF_8)
        val checksumBytes = (header.checksumSha256 ?: "").toByteArray(StandardCharsets.UTF_8)
        val pathBytes = (header.relativePath ?: "").toByteArray(StandardCharsets.UTF_8)
        val capacity = 4 + nameBytes.size + 8 + 2 + mimeBytes.size + 2 +
            checksumBytes.size + 2 + pathBytes.size + 4 + 4 + 8 + 8
        val buffer = java.nio.ByteBuffer.allocate(capacity)
        buffer.putInt(nameBytes.size)
        buffer.put(nameBytes)
        buffer.putLong(header.size)
        buffer.putShort(mimeBytes.size.toShort())
        buffer.put(mimeBytes)
        buffer.putShort(checksumBytes.size.toShort())
        buffer.put(checksumBytes)
        buffer.putShort(pathBytes.size.toShort())
        buffer.put(pathBytes)
        buffer.putInt(header.partIndex)
        buffer.putInt(header.partCount)
        buffer.putLong(header.offset)
        buffer.putLong(header.partLength)
        val out = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(out)
        return out
    }

    /** فك ترميز رأس الملف مع تحقق صريح من كل طول قبل القراءة */
    fun decodeFileHeader(payload: ByteArray): FileHeader {
        val buffer = java.nio.ByteBuffer.wrap(payload)
        val nameLen = buffer.int
        if (nameLen < 0 || nameLen > payload.size) throw IOException("طول اسم ملف غير صالح")
        val nameBytes = ByteArray(nameLen).also { buffer.get(it) }
        val size = buffer.long
        val mimeLen = buffer.short.toInt()
        if (mimeLen < 0 || mimeLen > payload.size) throw IOException("طول نوع MIME غير صالح")
        val mimeBytes = ByteArray(mimeLen).also { buffer.get(it) }
        val checksumLen = buffer.short.toInt()
        if (checksumLen < 0 || checksumLen > payload.size) throw IOException("طول البصمة غير صالح")
        val checksumBytes = ByteArray(checksumLen).also { buffer.get(it) }
        val pathLen = buffer.short.toInt()
        if (pathLen < 0 || pathLen > payload.size) throw IOException("طول المسار غير صالح")
        val pathBytes = ByteArray(pathLen).also { buffer.get(it) }
        val partIndex = buffer.int
        val partCount = buffer.int
        val offset = buffer.long
        val partLength = buffer.long
        return FileHeader(
            name = String(nameBytes, StandardCharsets.UTF_8),
            size = size,
            mimeType = String(mimeBytes, StandardCharsets.UTF_8).ifBlank { null },
            checksumSha256 = String(checksumBytes, StandardCharsets.UTF_8).ifBlank { null },
            relativePath = String(pathBytes, StandardCharsets.UTF_8).ifBlank { null },
            partIndex = partIndex,
            partCount = partCount,
            offset = offset,
            partLength = partLength
        )
    }

    /** حماية أسماء الملفات القادمة من الشبكة (منع تجاوز المسار) */
    fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace('/', '_')
            .replace('\\', '_')
            .replace("\u0000", "")
            .trim()
        val safe = cleaned.ifBlank { "ملف_${System.currentTimeMillis()}" }
        return if (safe == "." || safe == "..") "ملف_${System.currentTimeMillis()}" else safe.take(180)
    }
}
