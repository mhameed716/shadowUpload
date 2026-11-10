package com.shadowupload.app

import android.content.res.Configuration
import com.shadowupload.app.data.prefs.AppPreferences
import com.shadowupload.app.data.repo.HistoryRepository
import java.util.Locale

/**
 * كلاس التطبيق: يهيّئ اللغة العربية (RTL) والموارد المشتركة.
 *
 * تثبيت العربية هنا (وليس في كل شاشة) يضمن:
 *  - بدء كل الشاشات من اليمين إلى اليسار.
 *  - بقاء أسماء المجلدات والقيم الرقمية سليمة.
 */
class ShadowUploadApp : android.multidex.MultiDexApplication() {

    lateinit var preferences: AppPreferences
        private set

    lateinit var historyRepository: HistoryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        applyArabicLocale()
        preferences = AppPreferences(this)
        historyRepository = HistoryRepository(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // إعادة فرض العربية إن غيّر النظام الإعدادات أثناء التشغيل
        if (Locale.getDefault().language != ARABIC) {
            applyArabicLocale()
        }
    }

    private fun applyArabicLocale() {
        val locale = Locale(ARABIC, "SA")
        Locale.setDefault(locale)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private companion object {
        const val ARABIC = "ar"
    }
}
