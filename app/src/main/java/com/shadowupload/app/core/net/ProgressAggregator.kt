package com.shadowupload.app.core.net

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * مُجمِّع تقدّم ذري لعدة مسارات متوازية.
 *
 * كل مسار يكتب في خليته الخاصة (بلا تنازع)، والقراءة تجمع الكل.
 * السرعة اللحظية تُحسب بنافذة زمنية متحركة (متوسط 500 مللي ثانية)
 * حتى لا ترتجف الأرقام المعروضة في الواجهة.
 */
class ProgressAggregator(
    private val totalBytes: Long,
    private val partCount: Int? = null
) {
    private val completed = AtomicLong(0L)
    private val parts: AtomicLongArray? = partCount?.let { AtomicLongArray(partCount) }

    private var lastSampleTime = System.currentTimeMillis()
    private var lastSampleBytes = 0L
    private var smoothedSpeed = 0L

    /** إضافة بايتات إلى المسار رقم index (أو إلى المجموع العام) */
    fun add(bytes: Long, index: Int = -1) {
        if (bytes <= 0L) return
        completed.addAndGet(bytes)
        val array = parts
        if (array != null && index in 0 until array.length()) {
            array.addAndGet(index, bytes)
        }
    }

    /** إجمالي البايتات المنقولة */
    fun transferred(): Long = completed.get()

    /** عدد البايتات المنقولة في جزء محدد */
    fun partTransferred(index: Int): Long {
        val array = parts ?: return completed.get()
        return if (index in 0 until array.length()) array.get(index) else 0L
    }

    /** نسبة التقدم 0..1 */
    fun fraction(): Double {
        if (totalBytes <= 0L) return 0.0
        return (completed.get().toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * السرعة اللحظية بالبايت/ثانية (ممهّدة).
     * تُستدعى بشكل دوري من خيط الإبلاغ فقط.
     */
    fun sampleSpeed(): Long {
        val now = System.currentTimeMillis()
        val elapsed = now - lastSampleTime
        if (elapsed < MIN_SAMPLE_WINDOW_MS) return smoothedSpeed

        val current = completed.get()
        val delta = current - lastSampleBytes
        val instant = if (elapsed > 0) (delta * 1000.0 / elapsed.toDouble()).toLong() else 0L

        smoothedSpeed = if (smoothedSpeed == 0L) {
            instant
        } else {
            // متوسط متحرك أسي: 60% للحظة الحالية
            (instant * 0.6 + smoothedSpeed * 0.4).toLong()
        }

        lastSampleTime = now
        lastSampleBytes = current
        return smoothedSpeed.coerceAtLeast(0L)
    }

    /** الوقت المتوقع للانتهاء بالمللي ثانية */
    fun etaMillis(): Long {
        val speed = if (smoothedSpeed > 0) smoothedSpeed else sampleSpeed()
        if (speed <= 0L) return 0L
        val remaining = (totalBytes - completed.get()).coerceAtLeast(0L)
        return ((remaining.toDouble() / speed.toDouble()) * 1000.0).toLong()
    }

    /** إعادة الضبط لجلسة جديدة */
    fun reset() {
        completed.set(0L)
        parts?.let { array ->
            for (index in 0 until array.length()) array.set(index, 0L)
        }
        lastSampleTime = System.currentTimeMillis()
        lastSampleBytes = 0L
        smoothedSpeed = 0L
    }

    private companion object {
        const val MIN_SAMPLE_WINDOW_MS = 300L
    }
}
