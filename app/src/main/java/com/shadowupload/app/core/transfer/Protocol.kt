package com.shadowupload.app.core.transfer


/** نتيجة التحقق من بصمة ملف */
data class ChecksumResult(
    val expected: String?,
    val actual: String,
    val matches: Boolean
)

/** استثناء خاص بمحرك النقل يحمل رسالة عربية قابلة للعرض */
class TransferException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** أوضاع التعامل مع ملف موجود مسبقاً */
enum class ConflictPolicy {
    SKIP,
    OVERWRITE,
    RENAME
}

/** المصدر الموحد للبيانات: مسار ملف حقيقي أو Uri من نظام أندرويد */
sealed class TransferSource {
    abstract val name: String
    abstract val size: Long
    abstract val mimeType: String?

    /** ملف بمسار في نظام الملفات — الأسرع (Zero-Copy ممكن) */
    data class FromPath(
        val path: String,
        override val name: String,
        override val size: Long,
        override val mimeType: String?,
        val lastModified: Long = 0L
    ) : TransferSource()

    /** ملف عبر ContentResolver (Scoped Storage) */
    data class FromUri(
        val uriString: String,
        override val name: String,
        override val size: Long,
        override val mimeType: String?,
        val lastModified: Long = 0L
    ) : TransferSource()
}

/** مستمع جلسة النقل: يُستدعى من خيط الخلفية — على الواجهة تحويله للخيط الرئيسي */
interface TransferListener {
    fun onStateChanged(state: TransferState) {}
    fun onProgress(progress: TransferProgress) {}
    fun onFileCompleted(name: String, success: Boolean, detail: String) {}
    fun onFinished(result: TransferResult) {}
}

/** نتيجة الجلسة النهائية */
data class TransferResult(
    val success: Boolean,
    val filesTransferred: Int,
    val filesFailed: Int,
    val totalBytes: Long,
    val durationMillis: Long,
    val averageBytesPerSecond: Long,
    val message: String
)
