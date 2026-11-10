package com.shadowupload.app.core.net

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * توليد رموز QR محلياً (بدون أي خدمة خارجية) بألوان الثيم السيبراني.
 * نستخدم ZXing core مباشرة ونرسم النقاط بأنفسنا للتحكم الكامل بالألوان
 * وللحصول على حواف حادة على كل الشاشات.
 */
object QrUtils {

    private const val QUIET_ZONE = 1

    /**
     * توليد صورة QR.
     *
     * @param content النص (رابط shadowupload://join...)
     * @param sizePx الحجم بالبكسل
     * @param foreground اللون الأمامي (سماوي نيون افتراضياً)
     * @param background لون الخلفية (داكن)
     */
    fun generate(
        content: String,
        sizePx: Int = 640,
        foreground: Int = Color.parseColor("#00F0FF"),
        background: Int = Color.parseColor("#0B0E16")
    ): Bitmap? {
        if (content.isBlank()) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to QUIET_ZONE
            )
            val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (matrix.get(x, y)) foreground else background
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }.getOrNull()
    }

    /** توليد QR بحجم صغير لمعاينة داخل الحوارات */
    fun generateSmall(content: String): Bitmap? = generate(content, 320)
}
