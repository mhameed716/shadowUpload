package com.shadowupload.app.data.model

import android.net.Uri

/** تصنيفات الملفات المعروضة في تبويب «الملفات» */
enum class FileCategory {
    APPS,
    VIDEOS,
    PHOTOS,
    AUDIO,
    DOCUMENTS,
    STORAGE
}

/** عنصر قابل للنقل: ملف أو مجلد أو تطبيق */
data class FileItem(
    val id: String,
    val uri: Uri?,
    val path: String?,
    val name: String,
    val size: Long,
    val mimeType: String?,
    val lastModified: Long,
    val isDirectory: Boolean = false,
    val category: FileCategory = FileCategory.STORAGE,
    val packageName: String? = null
) {
    /** هل يمكن إرسال هذا العنصر عبر محرك النقل؟ */
    val isTransferable: Boolean
        get() = !isDirectory && (uri != null || path != null)
}

/** اتجاه عملية النقل */
enum class TransferDirection { SEND, RECEIVE }

/** حالة جلسة النقل */
enum class TransferState {
    IDLE,
    CONNECTING,
    HANDSHAKING,
    WAITING_PEER,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/** نموذج تقدم النقل الذي تراقبه الواجهة */
data class TransferProgress(
    val state: TransferState = TransferState.IDLE,
    val currentFileName: String = "",
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
    val fileBytesDone: Long = 0L,
    val fileBytesTotal: Long = 0L,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val etaMillis: Long = 0L,
    val message: String = ""
) {
    /** نسبة التقدم 0..100 */
    val percent: Float
        get() {
            if (bytesTotal <= 0L) return 0f
            return (bytesDone.toDouble() * 100.0 / bytesTotal.toDouble())
                .toFloat().coerceIn(0f, 100f)
        }
}

/** معلومات الجهاز الطرفي */
data class PeerDevice(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val lastSeen: Long = System.currentTimeMillis()
)

/** سجل عملية نقل منتهية */
data class TransferRecord(
    val id: Long = 0L,
    val direction: TransferDirection,
    val peerName: String,
    val fileCount: Int,
    val totalBytes: Long,
    val durationMillis: Long,
    val timestamp: Long,
    val success: Boolean,
    val averageSpeed: Long,
    val sampleFileName: String = ""
)

/** عناصر قابلة للنسخ ضمن «نسخ هاتف آخر بالكامل» */
enum class CloneItem(val key: String) {
    APPS("apps"),
    PHOTOS("photos"),
    VIDEOS("videos"),
    AUDIO("audio"),
    DOCUMENTS("documents"),
    CONTACTS("contacts"),
    SMS("sms"),
    CALL_LOG("call_log"),
    SETTINGS("settings")
}

/** إعدادات محرك النقل القابلة للضبط من شاشة الإعدادات */
data class TransportConfig(
    val parallelStreams: Int = 4,
    val socketBufferBytes: Int = 1024 * 1024,
    val verifyChecksums: Boolean = true,
    val compress: Boolean = false,
    val maxSpeedMode: Boolean = true,
    val receivePort: Int = DEFAULT_PORT
) {
    companion object {
        const val DEFAULT_PORT = 47821
        const val MIN_STREAMS = 1
        const val MAX_STREAMS = 8
        val BUFFER_CHOICES = intArrayOf(
            256 * 1024,
            512 * 1024,
            1024 * 1024,
            2 * 1024 * 1024
        )
    }
}
