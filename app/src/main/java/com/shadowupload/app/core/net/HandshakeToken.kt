package com.shadowupload.app.core.net

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * رموز المصافحة الفورية (Instant Handshake Tokens).
 *
 * بدل إدخال كلمة مرور أو انتظار اكتشاف، يولّد المضيف رمزاً قصيراً عمره
 * ثوانٍ قليلة ويُضمَّن داخل رمز QR. عند مسحه يبدأ الاتصال مباشرة على
 * عنوان IP والمنفذ المضمّنين، فلا حاجة لأي بحث (discovery) إضافي.
 *
 * الأمان: الرمز عشوائي آمن تشفيرياً (SecureRandom) وله صلاحية زمنية،
 * ويُقارن بمقارنة ثابتة الزمن (constant-time) لتفادي هجمات التوقيت.
 */
object HandshakeToken {

    /** صلاحية الرمز الافتراضية بالمللي ثانية */
    const val DEFAULT_TTL_MS = 120_000L

    /** رمز مصافحة كامل */
    data class Token(
        val code: String,
        val host: String,
        val port: Int,
        val expiresAt: Long,
        val fingerprint: String
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() > expiresAt
    }

    private val random = SecureRandom()

    /**
     * توليد رمز 6 أرقام سهل القراءة (بدون أرقام متشابهة يدوياً).
     * لا يستخدم للتحقق الأمني المطلق بل كمفتاح جلسة قصير العمر.
     */
    fun generateNumericCode(): String {
        val digits = buildString {
            repeat(6) { append(random.nextInt(10)) }
        }
        return digits
    }

    /** رمز نصي أطول مع تشفير عشوائي (للروابط) */
    fun generateSecret(length: Int = 24): String {
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        return bytes.toHex().take(length)
    }

    /** بناء رابط عميق لرمز QR: shadowupload://join?h=IP&p=PORT&c=CODE */
    fun buildUri(host: String, port: Int, code: String): String =
        "shadowupload://join?h=$host&p=$port&c=$code"

    /** تحليل رابط QR؛ يعيد null إن كان الرابط غير صالح */
    fun parseUri(uri: String): Token? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("shadowupload://join", ignoreCase = true)) return null
        val query = trimmed.substringAfter('?', "")
        if (query.isBlank()) return null
        val params = query.split('&').mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
        val host = params["h"] ?: return null
        val port = params["p"]?.toIntOrNull() ?: return null
        val code = params["c"] ?: return null
        if (code.length !in 4..64) return null
        return Token(
            code = code,
            host = host,
            port = port,
            expiresAt = System.currentTimeMillis() + DEFAULT_TTL_MS,
            fingerprint = fingerprintOf(host, port, code)
        )
    }

    /** بصمة قصيرة تُعرض للطرف الآخر للتأكيد البصري */
    fun fingerprintOf(host: String, port: Int, code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest("$host:$port:$code".toByteArray(Charsets.UTF_8))
        return hash.take(4).joinToString("") { byte -> "%02x".format(byte) }.uppercase()
    }

    /** مقارنة ثابتة الزمن (تمنع تسريب معلومات عبر فروق التوقيت) */
    fun constantTimeEquals(a: String, b: String): Boolean {
        val left = a.toByteArray(Charsets.UTF_8)
        val right = b.toByteArray(Charsets.UTF_8)
        if (left.size != right.size) return false
        var result = 0
        for (index in left.indices) {
            result = result or (left[index].toInt() xor right[index].toInt())
        }
        return result == 0
    }

    private fun ByteArray.toHex(): String {
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            builder.append("%02x".format(byte))
        }
        return builder.toString()
    }
}
