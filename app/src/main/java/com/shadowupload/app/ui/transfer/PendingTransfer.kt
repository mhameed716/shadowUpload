package com.shadowupload.app.ui.transfer

import com.shadowupload.app.data.model.FileItem

/**
 * حاوية مؤقتة للملفات المحددة في تبويب «الملفات» بانتظار اختيار طريقة
 * الاتصال (QR، تحريك، مجموعة). لا تُحفظ على القرص ولا تُرسل لأي مكان.
 */
object PendingTransfer {
    @Volatile
    var items: List<FileItem> = emptyList()

    /** هل لدى المستخدم ملفات جاهزة للإرسال؟ */
    val hasItems: Boolean get() = items.isNotEmpty()

    /** تفريغ القائمة بعد الإرسال أو الإلغاء */
    fun clear() {
        items = emptyList()
    }
}
