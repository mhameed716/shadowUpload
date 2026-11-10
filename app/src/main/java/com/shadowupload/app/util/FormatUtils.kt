package com.shadowupload.app.util

import android.content.Context
import android.os.Build
import android.text.format.Formatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * أدوات التنسيق العربية: الأحجام، السرعات، المدد الزمنية، وأسماء الملفات.
 * تعتمد على أرقام لاتينية مع وحدات عربية لتسهيل القراءة على كل الأجهزة.
 */
object FormatUtils {

    private val ARABIC_DIGITS = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

    /** هل الجهاز يستخدم الأرقام العربية الهندية في الإعدادات؟ */
    private fun prefersArabicDigits(): Boolean {
        val locale = Locale.getDefault()
        return locale.language == "ar" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
    }

    /** تحويل الأرقام إلى أرقام عربية هندية عند الحاجة */
    fun localizeDigits(input: String): String {
        if (!prefersArabicDigits()) return input
        val sb = StringBuilder(input.length)
        for (ch in input) {
            if (ch in '0'..'9') sb.append(ARABIC_DIGITS[ch - '0']) else sb.append(ch)
        }
        return sb.toString()
    }

    /** تنسيق حجم بالبايت بشكل مقروء: 1.4 م.ب */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 ب"
        if (bytes < 1024) return "$bytes ب"
        val units = arrayOf("ك.ب", "م.ب", "ج.ب", "ت.ب")
        val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
        val value = bytes / 1024.0.pow(digitGroups.toDouble())
        val pattern = if (value >= 100) "%.0f" else if (value >= 10) "%.1f" else "%.2f"
        return String.format(Locale.US, pattern, value) + " " + units[digitGroups - 1]
    }

    /** تنسيق عبر نظام أندرويد (يحترم لغة النظام) */
    fun formatSizeLocalized(context: Context, bytes: Long): String =
        Formatter.formatShortFileSize(context, bytes)

    /** تنسيق السرعة: 42.7 م.ب/ث */
    fun formatSpeed(bytesPerSecond: Long): String = formatSize(bytesPerSecond) + "/ث"

    /** تنسيق المدة الزمنية المتبقية */
    fun formatDuration(millis: Long): String {
        if (millis <= 0L) return "—"
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> String.format(Locale.US, "%d س %02d د", hours, minutes)
            minutes > 0 -> String.format(Locale.US, "%d د %02d ث", minutes, seconds)
            else -> String.format(Locale.US, "%d ث", seconds)
        }
    }

    /** مدة قصيرة جداً للإشعارات: ٣ ث أو ١ د ٢٠ ث */
    fun shortDuration(millis: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis).coerceAtLeast(0L)
        return when {
            totalSeconds < 60 -> "$totalSeconds ث"
            else -> "${totalSeconds / 60} د ${totalSeconds % 60} ث"
        }
    }

    /** نسبة مئوية مقروءة */
    fun formatPercent(value: Float): String = "${value.coerceIn(0f, 100f).toInt()}%"

    /** حجم تجاوز بشكل مدمج للعرض في مساحات ضيقة */
    fun compactSize(bytes: Long): String {
        if (bytes <= 0L) return "0"
        val units = arrayOf("B", "K", "M", "G", "T")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return String.format(Locale.US, if (value >= 10) "%.0f%s" else "%.1f%s", value, units[index])
    }

    /** إضافة أرقام عربية للتسميات النهائية */
    fun arabicNumber(value: Long): String = localizeDigits(value.toString())

    /** تنسيق التاريخ بشكل مختصر */
    fun formatTimestamp(millis: Long): String {
        val now = System.currentTimeMillis()
        val diff = abs(now - millis)
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "الآن"
            diff < TimeUnit.HOURS.toMillis(1) ->
                "${TimeUnit.MILLISECONDS.toMinutes(diff)} د"
            diff < TimeUnit.DAYS.toMillis(1) ->
                "${TimeUnit.MILLISECONDS.toHours(diff)} س"
            else -> {
                val days = TimeUnit.MILLISECONDS.toDays(diff)
                if (days == 1L) "أمس" else "$days يوم"
            }
        }
    }
}
