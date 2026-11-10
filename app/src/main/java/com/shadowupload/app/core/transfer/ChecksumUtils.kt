package com.shadowupload.app.core.transfer

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * حساب بصمة SHA-256 باستخدام NIO و DirectByteBuffer
 * لتقليل الضغط على جامع المهملات ورفع السرعة.
 */
object ChecksumUtils {

    private const val BUFFER_SIZE = 256 * 1024

    fun sha256OfFile(file: File): String? {
        if (!file.exists() || !file.canRead()) return null
        // FileInputStream.getChannel متاح منذ API 1 — لا نستخدم java.nio.file إطلاقاً
        return try {
            java.io.FileInputStream(file).use { stream ->
                digestChannel(stream.channel)
            }
        } catch (_: Throwable) {
            try {
                file.inputStream().use { digestStream(it) }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun sha256OfStream(input: InputStream): String? = try {
        digestStream(input)
    } catch (_: Exception) {
        null
    }

    private fun digestChannel(channel: FileChannel): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
        while (true) {
            buffer.clear()
            val read = channel.read(buffer)
            if (read <= 0) break
            buffer.flip()
            digest.update(buffer)
        }
        return digest.digest().toHex()
    }

    private fun digestStream(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            out.append(HEX[(b.toInt() shr 4) and 0x0F])
            out.append(HEX[b.toInt() and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
